package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.DeviceClaimHooks
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Borrows devices, of any platform, from other Trailblaze daemons that lend them
 * ([DeviceLeaseEndpoint]), so they show up and behave like this machine's own devices.
 *
 * Device discovery lists each lender's devices ([remoteDevices]) as
 * `<platform>/<lender's instance>@<lender host:port>`, whoever holds them, as local devices are
 * listed whichever session holds them. Using one follows the local rule too: it is taken from a
 * holder that is idle, and refused, naming the holder, while the holder is using it.
 *
 * The agent, logs and report stay here; each tool call and screen capture goes to the lender
 * ([call]), which runs it on the device with its own driver. The first call, claim or run on a
 * device leases it ([ensureLeased]), and the lease is renewed in the background. It is kept while a
 * session claims it or a run is on it, and for [lingerMs] after it was last used, so the next
 * command of a multi-call CLI session gets the same device; then the lease is returned. Once a lease
 * is lost, the next call says so instead of driving a device that may now belong to someone else.
 *
 * When this daemon also lends its own devices ([lender]), it tells the lender which ones its
 * sessions, runs, device viewers and recorders are using ([isLocallyInUse]), and each of those
 * takes a lent device by the same rule a borrower does.
 */
class RemoteDevicePools(
  /** The lenders to ask, read on every listing so pairing takes effect at once. */
  private val poolUrls: () -> List<String>,
  private val holder: String = defaultHolder(),
  private val lingerMs: Long = DEFAULT_LINGER_MS,
  private val clientFor: (String) -> DeviceLeaseClient = ::DeviceLeaseClient,
  private val now: () -> Long = System::currentTimeMillis,
  private val busyWindowMs: Long = BUSY_WINDOW_MS,
  /** This daemon's book of lent devices, when it lends them. */
  private val lender: () -> DeviceLeaseRegistry? = { null },
  /** Runs the lease renewals; tests step them by hand. */
  private val repeater: Repeater = Repeater.Threads,
) : DeviceClaimHooks {

  /** Runs a task every `intervalMs` until it returns false. */
  fun interface Repeater {
    fun every(name: String, intervalMs: Long, task: () -> Boolean)

    companion object {
      /** A daemon thread per task, sleeping between runs. */
      val Threads = Repeater { name, intervalMs, task ->
        thread(isDaemon = true, name = name) {
          do Thread.sleep(intervalMs) while (task())
        }
      }
    }
  }

  /** A lender's device as of the last discovery pass. [lenderId] is the lender's name for it. */
  private data class Offer(val poolUrl: String, val lenderId: String, val summary: TrailblazeConnectedDeviceSummary)

  private class Held(val device: TrailblazeDeviceId, val offer: Offer, val lease: Lease, val client: DeviceLeaseClient, usedAtMs: Long) {
    @Volatile var claimed = false
    val runs = AtomicInteger()

    /** Calls to the lender not answered yet. */
    val calls = AtomicInteger()
    @Volatile var lastUsedAtMs = usedAtMs

    /** What the lender was last told; a lease starts busy. */
    @Volatile var reportedBusy = true

    /** Held across each renewal, so an older idle report can't land after a newer busy one. */
    val renewLock = Any()
  }

  /** Replaced whole by each discovery pass, so a lookup never sees one half-built. */
  @Volatile private var offers: Map<TrailblazeDeviceId, Offer> = emptyMap()
  private val held = ConcurrentHashMap<TrailblazeDeviceId, Held>()
  private val locks = ConcurrentHashMap<TrailblazeDeviceId, Any>()

  /** Why each lease that was lost went, so the next call on the device can say so. */
  private val lostReasons = ConcurrentHashMap<TrailblazeDeviceId, String>()

  /** One client per lender, so its HTTP connections are reused across discovery passes and leases. */
  private val clients = ConcurrentHashMap<String, DeviceLeaseClient>()

  @Volatile private var shutdownHookAdded = false

  /** Whether a session is in the middle of a tool call on a device; from the claim registry. */
  @Volatile private var toolCallInFlight: (TrailblazeDeviceId) -> Boolean = { false }

  /**
   * This machine's own devices (fully qualified ids) that sessions here claim, and the runs on them;
   * see [isLocallyInUse].
   */
  private val localClaims = ConcurrentHashMap.newKeySet<String>()
  private val localRuns = ConcurrentHashMap<String, AtomicInteger>()

  /** Live views of each of this machine's own devices in this daemon's device viewer or recorder. */
  private val viewers = ConcurrentHashMap<String, AtomicInteger>()

  /** The devices this daemon holds a lease on. */
  fun leasedDevices(): Set<TrailblazeDeviceId> = held.keys.toSet()

  /**
   * The lenders' devices, each described by the machine it is on, plus any this daemon still holds
   * whose lender stopped listing it. Asks every lender; one that can't be reached adds nothing.
   */
  fun remoteDevices(): List<TrailblazeConnectedDeviceSummary> {
    val urls = poolUrls()
    if (urls.isEmpty() && held.isEmpty()) {
      offers = emptyMap()
      return emptyList()
    }
    val fresh = refreshOffers(urls)
    return (fresh.values.map { it.summary } + held.values.map { it.offer.summary }).distinctBy { it.trailblazeDeviceId }
  }

  /** True for a device a lender offered at the last discovery pass, or one this daemon holds. */
  fun isRemote(device: TrailblazeDeviceId): Boolean = held.containsKey(device) || offers.containsKey(device)

  /**
   * Runs [request] on borrowed [device], leasing it first if needed; [RemoteDeviceCall.device] is
   * filled in with the lender's name for it. The device counts as busy until the lender answers.
   * Throws, with a message for the user, when it can't be leased or the lender no longer lends it.
   */
  fun call(device: TrailblazeDeviceId, request: RemoteDeviceCall): RemoteDeviceReply =
    onLender(device, request.op.name) { entry -> entry.client.call(entry.lease, request.copy(device = entry.offer.lenderId)) }

  /**
   * Installs the app in [appFile] (an `.apk`, or a `.zip` holding an `.app`) on borrowed [device]:
   * the file goes to its lender, which installs it as it would on its own device. Leases the device
   * first if needed, and throws as [call] does.
   */
  fun install(device: TrailblazeDeviceId, appFile: File): RemoteInstallReply =
    onLender(device, "install") { entry -> entry.client.install(entry.lease, entry.offer.lenderId, appFile) }

  /** Runs [send] against [device]'s lender, leasing it first; the device counts as busy until it answers. */
  private fun <T> onLender(device: TrailblazeDeviceId, what: String, send: (Held) -> T): T {
    val entry = synchronized(lockFor(device)) {
      ensureLeased(device)
      val entry = held[device] ?: throw IllegalStateException("${device.toFullyQualifiedDeviceId()} is not a borrowed device")
      entry.also { it.calls.incrementAndGet() }
    }
    try {
      return send(entry)
    } catch (e: LeaseLostException) {
      synchronized(lockFor(device)) { dropLost(entry, e.message ?: "the lender took it back") }
      throw IllegalStateException("${device.toFullyQualifiedDeviceId()} is no longer lent to this machine: ${e.message}", e)
    } catch (e: IOException) {
      throw IllegalStateException("$what on ${device.toFullyQualifiedDeviceId()} failed: ${e.message}", e)
    } finally {
      entry.calls.decrementAndGet()
      entry.lastUsedAtMs = now()
    }
  }

  /**
   * Leases [device] if it is a lender's device and not held yet, and marks it used. A no-op for any
   * other device. Throws, with a message for the user, when it can't be leased.
   */
  fun ensureLeased(device: TrailblazeDeviceId) {
    if (!isRemote(device)) return
    // Under the lock even when held: [keepAlive] decides to return an idle device under it, so a use
    // that marks it used outside the lock could land just as it is given back.
    synchronized(lockFor(device)) {
      held[device]?.let { if (resume(it)) return }
      lostReasons.remove(device)?.let { reason ->
        Console.log("[RemoteDevicePools] Leasing ${device.toFullyQualifiedDeviceId()} again after losing it: $reason")
      }
      val offer = offers[device] ?: run {
        refreshOffers(poolUrls())
        offers[device]
      } ?: throw IllegalStateException("${device.toFullyQualifiedDeviceId()} is not offered by any paired daemon right now")
      val client = clientOf(offer.poolUrl)
      val lease = when (val outcome = leaseOrThrow(client, offer, device)) {
        is DeviceLeaseClient.LeaseOutcome.Granted -> outcome.lease
        is DeviceLeaseClient.LeaseOutcome.Busy -> throw IllegalStateException(
          outcome.holder?.let { busyMessage(device, it) }
            ?: "${device.toFullyQualifiedDeviceId()} can't be leased: ${outcome.message}",
        )
      }
      val entry = Held(device, offer, lease, client, usedAtMs = now())
      held[device] = entry
      addShutdownHookOnce()
      keepAlive(device, entry)
      Console.log("[RemoteDevicePools] Leased ${device.toFullyQualifiedDeviceId()} (${offer.lenderId} from ${offer.poolUrl})")
    }
  }

  /**
   * Before a device viewer or recorder connects to [deviceId] or sends it a gesture: this machine's
   * own device is taken from a borrower idle on it. A borrowed device can't be viewed yet. Returns
   * why it can't be used, the busy error when someone is using it, or null.
   */
  fun beforeInteractiveUse(deviceId: TrailblazeDeviceId): String? {
    if (isRemote(deviceId)) {
      return "${deviceId.toFullyQualifiedDeviceId()} is borrowed from another machine; " +
        "viewing a borrowed device isn't supported yet. Run trails and tools on it instead."
    }
    return try {
      takeLentDevice(deviceId)
      null
    } catch (e: IllegalStateException) {
      e.message ?: "${deviceId.toFullyQualifiedDeviceId()} can't be used right now"
    }
  }

  /** A device viewer or recorder opened this machine's [deviceId]: it is busy to borrowers until [viewerClosed]. */
  fun viewerOpened(deviceId: TrailblazeDeviceId) {
    if (isRemote(deviceId)) return
    viewers.computeIfAbsent(deviceId.toFullyQualifiedDeviceId()) { AtomicInteger() }.incrementAndGet()
  }

  fun viewerClosed(deviceId: TrailblazeDeviceId) {
    viewers[deviceId.toFullyQualifiedDeviceId()]?.updateAndGet { open -> (open - 1).coerceAtLeast(0) }
  }

  /**
   * Connects a device viewer or recorder to [deviceId] with [connect], which can take a minute (an
   * APK install, waiting for the on-device runner). The device counts as a run for all of it, so a
   * lent device is busy to others until the viewer that follows takes over; returns why it can't be
   * used instead of connecting, the busy error when someone is using it.
   */
  suspend fun <T> whileConnecting(deviceId: TrailblazeDeviceId, connect: suspend () -> T): Result<T> {
    beforeInteractiveUse(deviceId)?.let { return Result.failure(IllegalStateException(it)) }
    try {
      runStarted(deviceId)
    } catch (e: IllegalStateException) {
      return Result.failure(e)
    }
    return try {
      Result.success(connect())
    } finally {
      runEnded(deviceId)
    }
  }

  /** A run started on [deviceId]: a borrowed device stays leased until [runEnded], however quiet the run. */
  fun runStarted(deviceId: TrailblazeDeviceId) {
    if (isBorrowersCall(deviceId)) return
    if (!isRemote(deviceId)) {
      takeLentDevice(deviceId)
      localRuns.computeIfAbsent(deviceId.toFullyQualifiedDeviceId()) { AtomicInteger() }.incrementAndGet()
      return
    }
    // One lock hold, so the device can't be returned between leasing it and counting the run.
    synchronized(lockFor(deviceId)) {
      ensureLeased(deviceId)
      held[deviceId]?.runs?.incrementAndGet()
    }
  }

  fun runEnded(deviceId: TrailblazeDeviceId) {
    if (isBorrowersCall(deviceId)) return
    localRuns[deviceId.toFullyQualifiedDeviceId()]?.updateAndGet { runs -> (runs - 1).coerceAtLeast(0) }
    held[deviceId]?.let {
      it.runs.updateAndGet { runs -> (runs - 1).coerceAtLeast(0) }
      it.lastUsedAtMs = now()
    }
  }

  override fun beforeClaim(deviceId: TrailblazeDeviceId) {
    if (isRemote(deviceId)) ensureLeased(deviceId) else takeLentDevice(deviceId)
  }

  override fun onClaimed(deviceId: TrailblazeDeviceId) {
    val entry = held[deviceId]
    if (entry != null) entry.claimed = true else localClaims += deviceId.toFullyQualifiedDeviceId()
  }

  override fun onReleased(deviceId: TrailblazeDeviceId) {
    localClaims -= deviceId.toFullyQualifiedDeviceId()
    held[deviceId]?.let {
      it.claimed = false
      it.lastUsedAtMs = now()
    }
  }

  override fun watchToolCalls(inFlight: (TrailblazeDeviceId) -> Boolean) {
    toolCallInFlight = inFlight
  }

  /**
   * True while a session here claims this machine's own device [id] (fully qualified), a run is on
   * it, or a viewer or recorder has it open.
   */
  fun isLocallyInUse(id: String): Boolean =
    id in localClaims || (localRuns[id]?.get() ?: 0) > 0 || (viewers[id]?.get() ?: 0) > 0

  /** Returns every lease this daemon holds, at once. */
  fun returnAll() {
    held.keys.toList().forEach { device -> held[device]?.let { giveBack(device, it) } }
  }

  /**
   * Takes this machine's own [deviceId] back from a borrower that is idle on it, so a session here
   * can use it; refuses, naming the borrower, while the borrower is using it.
   */
  private fun takeLentDevice(deviceId: TrailblazeDeviceId) {
    if (isBorrowersCall(deviceId)) return
    val borrower = lender()?.takeForLocalUse(deviceId.toFullyQualifiedDeviceId()) ?: return
    throw IllegalStateException(busyMessage(deviceId, borrower, "borrowed from this machine"))
  }

  /**
   * Marks a held device used and returns true when it is still held. One the lender was last told
   * is idle could be handed to someone else at any moment, so it is told busy first; when it turns
   * out to be gone already, it is forgotten and this returns false.
   */
  private fun resume(entry: Held): Boolean {
    entry.lastUsedAtMs = now()
    if (entry.reportedBusy || renew(entry, busy = true) != DeviceLeaseClient.RenewOutcome.LOST) return true
    dropLost(entry, "the lender gave it to someone else while it was idle here")
    return false
  }

  /** Renews [entry]'s lease, telling the lender whether it is in use ([isBusy] unless [busy] says). */
  private fun renew(entry: Held, busy: Boolean? = null): DeviceLeaseClient.RenewOutcome = synchronized(entry.renewLock) {
    val inUse = busy ?: isBusy(entry)
    entry.client.renew(entry.lease, busy = inUse).also {
      if (it == DeviceLeaseClient.RenewOutcome.RENEWED) entry.reportedBusy = inUse
    }
  }

  /** Forgets a lease that was lost. Called under its lock. */
  private fun dropLost(entry: Held, reason: String): Boolean {
    if (!held.remove(entry.device, entry)) return false
    lostReasons[entry.device] = reason
    return true
  }

  /** Worded like the busy error for a local device another session is using. */
  private fun busyMessage(device: TrailblazeDeviceId, holder: String, where: String = "on ${offers[device]?.poolUrl ?: "another machine"}") =
    "Device ${device.toFullyQualifiedDeviceId()} is busy.\n" +
      "  Held by: $holder ($where)\n" +
      "Wait for it to finish, or stop the holder before retrying."

  private fun leaseOrThrow(client: DeviceLeaseClient, offer: Offer, device: TrailblazeDeviceId) = try {
    client.lease(LeaseRequest(devices = listOf(offer.lenderId), holder = holder))
  } catch (e: IOException) {
    throw IllegalStateException("could not lease ${device.toFullyQualifiedDeviceId()} from ${offer.poolUrl}: ${e.message}", e)
  }

  /** Renews until the lease is lost or no longer needed (then returns it). */
  private fun keepAlive(device: TrailblazeDeviceId, entry: Held) {
    val renewals = LeaseRenewals(entry.lease, now)
    repeater.every("trailblaze-lease-${device.toFullyQualifiedDeviceId()}", renewIntervalMs(entry.lease)) {
      if (held[device] === entry && isUnneeded(entry)) {
        // Re-checked under the lock: a use may have arrived after the lease fell idle.
        synchronized(lockFor(device)) { if (isUnneeded(entry)) giveBack(device, entry) }
      }
      if (held[device] !== entry) return@every false
      val lost = renewals.record(renew(entry)) ?: return@every true
      synchronized(lockFor(device)) { dropLost(entry, lost) }
      Console.log("[RemoteDevicePools] Lost the lease on ${device.toFullyQualifiedDeviceId()}: $lost")
      false
    }
  }

  /**
   * In use right now, so the lender keeps others from taking it: a run is on it, a call to the
   * lender is unanswered, a session is in the middle of a tool call on it, or it was used within
   * [busyWindowMs]. A session that merely holds the device between calls is idle, as a local one is.
   */
  private fun isBusy(entry: Held): Boolean =
    entry.runs.get() > 0 ||
      entry.calls.get() > 0 ||
      toolCallInFlight(entry.device) ||
      now() - entry.lastUsedAtMs < busyWindowMs

  private fun isUnneeded(entry: Held): Boolean =
    !entry.claimed && entry.runs.get() == 0 && entry.calls.get() == 0 && now() - entry.lastUsedAtMs >= lingerMs

  private fun giveBack(device: TrailblazeDeviceId, entry: Held) {
    if (!held.remove(device, entry)) return
    entry.client.release(entry.lease)
    Console.log("[RemoteDevicePools] Returned ${device.toFullyQualifiedDeviceId()} to ${entry.offer.poolUrl}")
  }

  /** Asks every lender what it has, by the id each device has here. */
  private fun refreshOffers(urls: List<String>): Map<TrailblazeDeviceId, Offer> {
    val fresh = urls.parallelStream().flatMap { url ->
      val authority = runCatching { URI(url).authority }.getOrNull() ?: return@flatMap java.util.stream.Stream.empty()
      try {
        clientOf(url).devices().map { offered ->
          val summary = TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = offered.driverType,
            instanceId = "${offered.id.substringAfter('/')}@$authority",
            description = listOf(offered.description, "on $authority").filter { it.isNotBlank() }.joinToString(" "),
          )
          summary.trailblazeDeviceId to Offer(url, offered.id, summary)
        }.stream()
      } catch (e: Exception) {
        Console.log("[RemoteDevicePools] Could not list devices at $url: ${e.message}")
        java.util.stream.Stream.empty()
      }
    }.toList().toMap()
    offers = fresh
    return fresh
  }

  private fun renewIntervalMs(lease: Lease) = RENEW_INTERVAL_MS.coerceAtMost(lease.ttlMs / 3).coerceAtLeast(1)

  private fun clientOf(url: String): DeviceLeaseClient = clients.computeIfAbsent(url, clientFor)

  private fun lockFor(device: TrailblazeDeviceId): Any = locks.computeIfAbsent(device) { Any() }

  private fun addShutdownHookOnce() {
    if (shutdownHookAdded) return
    synchronized(this) {
      if (shutdownHookAdded) return
      Runtime.getRuntime().addShutdownHook(thread(start = false, name = "trailblaze-lease-return") { returnAll() })
      shutdownHookAdded = true
    }
  }

  companion object {
    /** The device of the borrower's call running on this thread, if any (see [asBorrowersCall]). */
    private val borrowersCall = ThreadLocal<TrailblazeDeviceId?>()

    /**
     * Runs [block], a call from the borrower holding this machine's [device], as the borrower's use
     * of it: not a local run, and not refused because the borrower is busy on it.
     */
    suspend fun <T> asBorrowersCall(device: TrailblazeDeviceId, block: suspend () -> T): T =
      withContext(borrowersCall.asContextElement(device)) { block() }

    private fun isBorrowersCall(device: TrailblazeDeviceId): Boolean = borrowersCall.get() == device

    /** Comma-separated base URLs of daemons to borrow devices from. */
    const val ENV_VAR = "TRAILBLAZE_DEVICE_POOLS"

    /** How long an unused lease is kept, so back-to-back CLI commands keep their device. */
    const val DEFAULT_LINGER_MS: Long = 120_000

    private const val RENEW_INTERVAL_MS = 3_000L
    private const val BUSY_WINDOW_MS = 5_000L

    /** The lenders paired with `trailblaze device pair`, plus any named by [ENV_VAR]. */
    val shared: RemoteDevicePools by lazy {
      RemoteDevicePools(
        poolUrls = ::configuredUrls,
        lender = { if (DeviceLeaseEndpoint.lending) DeviceLeaseEndpoint.registry else null },
      )
    }

    /** The lenders paired with `trailblaze device pair`, plus any named by [ENV_VAR]. */
    fun configuredUrls(): List<String> = (parseUrls(System.getenv(ENV_VAR)) + PairedDevicePools().urls()).distinct()

    fun parseUrls(value: String?): List<String> =
      value.orEmpty().split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    private fun defaultHolder(): String {
      val user = System.getProperty("user.name").orEmpty()
      val machine = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("")
      return "$user@$machine"
    }
  }
}
