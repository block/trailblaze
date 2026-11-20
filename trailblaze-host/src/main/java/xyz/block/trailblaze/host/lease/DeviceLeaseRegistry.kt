package xyz.block.trailblaze.host.lease

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * A device this daemon can lend, of any platform. [id] is its fully qualified Trailblaze device id
 * here (`<platform>/<instance>`); [driverType] is the driver this daemon runs it with.
 */
@Serializable
data class LeasableDevice(
  val id: String,
  val driverType: TrailblazeDriverType,
  val description: String = "",
  /** The device's classifiers as measured here (`android`, `phone`, ...); empty when unmeasured. */
  val classifiers: List<String> = emptyList(),
)

/** One row of `GET /leases/devices`. */
@Serializable
data class LeasableDeviceStatus(
  val id: String,
  val driverType: TrailblazeDriverType,
  val description: String = "",
  val leased: Boolean,
  val holder: String? = null,
  /** True while the holder reports it is using the device, so it can't be taken. */
  val busy: Boolean = false,
  /** See [LeasableDevice.classifiers]. */
  val classifiers: List<String> = emptyList(),
)

/** Asks for the devices named by [devices] (their [LeasableDevice.id]s), all or none. */
@Serializable
data class LeaseRequest(
  val devices: List<String>,
  /** Free-form label of who holds the lease (host name, user), shown in the device list. */
  val holder: String = "",
)

/**
 * A granted lease. [secret] is required to renew or return it, so another caller that learns the
 * [leaseId] cannot end it. [ttlMs] is how long the lease lives without a renewal.
 */
@Serializable
data class Lease(
  val leaseId: String,
  val secret: String,
  val ttlMs: Long,
  val devices: List<LeasableDevice>,
)

/** An error answer; [holder] is set when a device was refused because someone is using it. */
@Serializable
data class LeaseError(val error: String, val holder: String? = null)

sealed interface LeaseResult {
  data class Granted(val lease: Lease) : LeaseResult

  /**
   * Not granted; [reason] names the first requested device that could not be lent, and why.
   * [holder] is set when that device is in use by someone.
   */
  data class Busy(val reason: String, val holder: String? = null) : LeaseResult
}

/**
 * The serving daemon's in-memory book of which devices are lent out.
 *
 * A lease covers one or more named devices, all or none, and expires [ttlMs] after it was granted
 * or last renewed. Like a local device claimed by another session, a leased device goes to whoever
 * asks next unless its holder said in its last renewal that it is using the device ([renew]).
 * Only the device asked for is taken; the holder keeps the rest of its lease, and finds out at its
 * next renewal when nothing is left. Expiry is evaluated on every call, so a holder that
 * disappears (crashed, offline, laptop asleep) gives its devices back without any timer. A device is
 * lent only while it is connected here, so one that went away is never handed out.
 *
 * This machine's own sessions share the devices with borrowers, one at a time: a device a local
 * session or run is using ([locallyInUse]) is busy to borrowers, and a local session takes a lent
 * device by the same rule as a borrower ([takeForLocalUse]).
 */
class DeviceLeaseRegistry(
  val ttlMs: Long = DEFAULT_TTL_MS,
  private val clock: () -> Long = System::currentTimeMillis,
  /** True while a session or run on this machine is using the device. */
  private val locallyInUse: (id: String) -> Boolean = { false },
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val newSecret: () -> String = ::randomSecret,
) {
  init {
    require(ttlMs > 0) { "ttlMs must be positive, was $ttlMs" }
  }

  private class Entry(
    var lease: Lease,
    val holder: String,
    var expiresAtMs: Long,
    // Busy until the holder's first renewal says otherwise: a device is leased in order to use it.
    var busy: Boolean = true,
  )

  private val leases = mutableMapOf<String, Entry>()

  /**
   * Devices whose lease was returned ([release]) but whose cleanup has not finished
   * ([finishReturn]), with who held them. Nobody can take one until then, so the old lease's
   * cleanup never ends the next user's session.
   */
  private val returning = mutableMapOf<String, String>()

  /**
   * When a local session last took each device ([takeForLocalUse]). Busy to borrowers for
   * [LOCAL_TAKE_GRACE_MS] after, covering the moment before [locallyInUse] sees the new claim.
   */
  private val takenLocallyAtMs = mutableMapOf<String, Long>()

  @Synchronized
  fun status(attached: List<LeasableDevice>): List<LeasableDeviceStatus> {
    expire()
    val holders = holdingEntries()
    return attached.sortedBy { it.id }.map {
      val entry = holders[it.id]
      val returningFrom = returning[it.id]
      LeasableDeviceStatus(
        id = it.id,
        driverType = it.driverType,
        description = it.description,
        leased = entry != null || returningFrom != null,
        holder = entry?.holder ?: returningFrom,
        busy = entry?.busy == true || returningFrom != null,
        classifiers = it.classifiers,
      )
    }
  }

  @Synchronized
  fun lease(attached: List<LeasableDevice>, request: LeaseRequest): LeaseResult {
    require(request.devices.isNotEmpty()) { "a lease must name at least one device" }
    expire()
    val holders = holdingEntries()
    val byId = attached.associateBy { it.id }
    val devices = request.devices.distinct().map { id ->
      val device = byId[id] ?: return LeaseResult.Busy("$id is not connected here")
      if (isLocallyInUse(id)) return LeaseResult.Busy("$id is busy: $LOCAL_HOLDER is using it", LOCAL_HOLDER)
      returning[id]?.let { holder -> return LeaseResult.Busy("$id is busy: it is still being returned", holder) }
      holders[id]?.takeIf { it.busy }?.let { entry ->
        val holder = entry.holder.ifBlank { "someone else" }
        return LeaseResult.Busy("$id is busy: $holder is using it", holder)
      }
      device
    }
    // Idle holders yield, as an idle session's local device does.
    devices.forEach { device -> holders[device.id]?.let { yieldDevice(it, device.id) } }
    val lease = Lease(
      leaseId = newId(),
      secret = newSecret(),
      ttlMs = ttlMs,
      devices = devices,
    )
    leases[lease.leaseId] = Entry(lease, request.holder, clock() + ttlMs)
    return LeaseResult.Granted(lease)
  }

  /**
   * Extends the lease by [ttlMs] and records whether the holder is [busy] using its devices. False
   * when it expired, was returned, was given to someone else, or the secret is wrong.
   */
  @Synchronized
  fun renew(leaseId: String, secret: String, busy: Boolean = false): Boolean {
    expire()
    val entry = authorized(leaseId, secret) ?: return false
    entry.expiresAtMs = clock() + ttlMs
    entry.busy = busy
    return true
  }

  /**
   * Ends the lease and names its devices, which stay busy until each one's cleanup calls
   * [finishReturn]. Null when there was no such live lease.
   */
  @Synchronized
  fun release(leaseId: String, secret: String): List<LeasableDevice>? {
    expire()
    val entry = authorized(leaseId, secret) ?: return null
    leases.remove(leaseId)
    entry.lease.devices.forEach { returning[it.id] = entry.holder.ifBlank { "someone else" } }
    return entry.lease.devices
  }

  /** Puts a device [release] named back in the pool, once its cleanup is done. */
  @Synchronized
  fun finishReturn(id: String) {
    returning.remove(id)
  }

  /**
   * Lets a session on this machine use [id], by the rule a borrower follows: a borrower that is
   * idle on it loses it, and one using it keeps it.
   * Returns that borrower's name when it keeps it, null when the device is this machine's now.
   */
  @Synchronized
  fun takeForLocalUse(id: String): String? {
    expire()
    returning[id]?.let { return it }
    val entry = holdingEntries()[id]
    if (entry != null && entry.busy) return entry.holder.ifBlank { "someone else" }
    if (entry != null) yieldDevice(entry, id)
    takenLocallyAtMs[id] = clock()
    return null
  }

  /**
   * True when the live lease [leaseId], proven by [secret], holds device [id]: the check before a
   * borrower's call reaches the device. A device taken back from the lease fails it.
   */
  @Synchronized
  fun holds(leaseId: String, secret: String, id: String): Boolean {
    expire()
    val entry = authorized(leaseId, secret) ?: return false
    return entry.lease.devices.any { it.id == id }
  }

  private fun isLocallyInUse(id: String): Boolean =
    locallyInUse(id) || takenLocallyAtMs[id]?.let { clock() - it < LOCAL_TAKE_GRACE_MS } == true

  /**
   * Takes [id] out of [entry]'s lease, which keeps its other devices; a lease left with none
   * ends, and its holder finds out at its next renewal.
   */
  private fun yieldDevice(entry: Entry, id: String) {
    val remaining = entry.lease.devices.filterNot { it.id == id }
    if (remaining.isEmpty()) leases.remove(entry.lease.leaseId) else entry.lease = entry.lease.copy(devices = remaining)
  }

  private fun authorized(leaseId: String, secret: String): Entry? {
    val entry = leases[leaseId] ?: return null
    val matches = MessageDigest.isEqual(entry.lease.secret.toByteArray(), secret.toByteArray())
    return entry.takeIf { matches }
  }

  private fun holdingEntries(): Map<String, Entry> =
    leases.values.flatMap { entry -> entry.lease.devices.map { it.id to entry } }.toMap()

  private fun expire() {
    val now = clock()
    leases.values.removeAll { it.expiresAtMs <= now }
  }

  companion object {
    const val DEFAULT_TTL_MS: Long = 30_000

    /** How a session on the lending machine is named to a borrower it refuses. */
    const val LOCAL_HOLDER = "a session on the lending machine"

    private const val LOCAL_TAKE_GRACE_MS = 10_000L

    private val random = SecureRandom()

    private fun randomSecret(): String {
      val bytes = ByteArray(24).also(random::nextBytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}
