package xyz.block.trailblaze.host.recording.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.recording.DeviceScreenStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the set of devices that the HTTP recording API has connected to. Each entry maps
 * a [TrailblazeDeviceId] to its live [DeviceScreenStream]. The stream is created by
 * [xyz.block.trailblaze.host.recording.DeviceConnectionService.connectToDevice] and held
 * here so that subsequent [GetHostDeviceScreenHandler] and [DeviceInteractionHandler] calls
 * can reach the already-running connection without reconnecting.
 *
 * Ownership: sessions created through [connectIfAbsent] are owned by this manager ([remove]
 * closes them). Sessions published through [attach] are owned by the caller — this manager
 * never closes them, whichever removal path drops the entry. [claim] records a device held by an
 * owner that has no stream to publish at all, so that a conflicting connect is still refused.
 *
 * Thread-safety:
 * - [get], [isConnected]: plain [ConcurrentHashMap] reads — no lock needed.
 * - [connectIfAbsent]: per-device [Mutex] prevents two concurrent connect calls from both
 *   racing past the "already connected?" check. Its final publication is atomic with [attach],
 *   which can publish an externally-owned stream while physical connection setup is suspended.
 * - [remove]: removes from the map and closes the stream if this manager owns it.
 */
class HostDeviceSessionManager {

  private class Session(
    val stream: DeviceScreenStream,
    val externallyOwned: Boolean,
    /** What this session was connected FOR. */
    val binding: Binding = Binding(),
  )

  /**
   * What a live connection is for, in the two ways that decide whether another connect can share it.
   *
   * [targetId] is the target app the connect named, or null when it named none. Null is a wildcard
   * on this axis: a session opened with no target and a connect asking for whatever is there don't
   * name apps that could contradict each other.
   *
   * [driverKey] identifies the driver the connect actually built, and is NOT a wildcard - null is a
   * real value meaning "the plain base driver". It exists because [targetId] alone can't tell a
   * targetless iOS connection (base driver) from one for a target that wraps that driver in its own
   * subclass, and `HostIosDriverFactory` will close and rebuild the cached driver when those
   * disagree. Sharing across that line either drives the app through the wrong driver or pulls the
   * driver out from under a live stream, so it is a conflict even though neither side names a
   * target the other contradicts.
   *
   * [buildsMaestroDriver] says whether that axis applies to this connect at all, and is the reason
   * it isn't enough to compare [driverKey] alone: an AXe or other host-native iOS connect goes
   * nowhere near `HostIosDriverFactory`, so it has no driver key AND can't rebuild anyone else's -
   * yet a plain Maestro connect has no driver key either. Without this, "no driver of my own" and
   * "the base driver" are the same null, and a wrapper-target holder refuses an AXe connection it
   * could never have been disturbed by.
   *
   * Callers derive all three through `DeviceConnectionService.connectionBinding`.
   */
  data class Binding(
    val targetId: String? = null,
    val driverKey: String? = null,
    val buildsMaestroDriver: Boolean = true,
  ) {
    /**
     * How a refusal names this binding to a user, as a phrase that reads after "connected for".
     * Spelled out rather than "no target" so the message reads as a deliberate state - a connect
     * made while nothing was selected - instead of a value we failed to look up.
     */
    fun describe(): String = targetId?.let { "target '$it'" } ?: "no particular target"
  }

  private val sessions = ConcurrentHashMap<TrailblazeDeviceId, Session>()

  /**
   * Devices held by an owner that drives them itself and has no stream to share - today the MCP
   * bridge's persistent drivers. Separate from [sessions] rather than an entry in it, because a
   * claim is a different thing: [sessions] is what this registry serves pixels from, and a device
   * can be claimed and streamed at the same time by two different owners.
   *
   * A claim exists only to be refused against. It is never returned by [get], so a claimed device
   * with no session still reads as "not connected" to the screen and interaction handlers, which is
   * the truth - there is nothing here for them to read.
   */
  private val claims = ConcurrentHashMap<TrailblazeDeviceId, Binding>()

  private val connectMutexes = ConcurrentHashMap<TrailblazeDeviceId, Mutex>()

  fun get(deviceId: TrailblazeDeviceId): DeviceScreenStream? = sessions[deviceId]?.stream

  /** What [connectIfAbsent] could do with the target the caller named. */
  sealed interface ConnectResult {
    /** A live session bound to the requested target: the one that already existed, or a new one. */
    data class Ready(val stream: DeviceScreenStream) : ConnectResult

    /**
     * The device is held by everyone in [heldBy] whose binding the caller's own conflicts with.
     * Reusing it would drive the app that connection installed and launched while the caller believes
     * it named another - or hand back a stream running on a driver the caller's connect would rebuild
     * - so the caller has to release the device before it can bind its own target.
     *
     * A **list**, because a session and a [claim] can hold one device at once and a caller can
     * conflict with both. Naming only the first would state a remedy that isn't enough: the caller
     * disconnects, retries, and is refused again by a holder it was never told about.
     */
    data class BoundToOtherTarget(val heldBy: List<Held>) : ConnectResult {
      /** One holder and the binding it holds the device for. */
      data class Held(val boundTo: Binding, val holder: Holder)

      /**
       * The whole refusal as one sentence: who has the device, for what, and everything that has to
       * happen before [action] (a verb phrase like "connecting") can succeed. Lives here so the
       * three callers that report a refusal can't word it differently or drop a holder between them.
       */
      fun explain(deviceId: TrailblazeDeviceId, binding: Binding, action: String): String {
        val holds = heldBy.joinToString(" and ") { "${it.holder.holds} for ${it.boundTo.describe()}" }
        val remedy = heldBy.joinToString(" and ") { it.holder.remedy }
        return "${deviceId.toFullyQualifiedDeviceId()} $holds. " +
          remedy.replaceFirstChar { it.uppercase() } +
          " before $action it for ${binding.describe()}."
      }
    }

    /**
     * Who holds a device, and therefore what a user has to do to get it back. Three holders rather
     * than one "releasable" flag, because "disconnect it" is true for exactly one of them: telling
     * someone to disconnect a recorder or an agent session sends them round a loop that frees
     * nothing, since dropping the registry entry leaves the physical connection with its owner.
     *
     * [holds] and [remedy] are the two phrases every refusal message needs, kept here so the three
     * callers that produce one can't describe the same holder differently. Lower-case because a
     * refusal may have to list two of them; [BoundToOtherTarget.explain] capitalizes the sentence.
     */
    enum class Holder(val holds: String, val remedy: String) {
      /** A viewer connection this registry owns. [remove] closes it, so a disconnect really frees the device. */
      VIEWER("is already connected", "disconnect it"),

      /** Trail Runner's recorder, which keeps the physical connection whatever this registry drops. */
      RECORDER("is being recorded", "stop that recording"),

      /** An agent session driving the device through a driver of its own - see [claim]. */
      AGENT("is being driven by an agent session", "stop that session"),
    }

    /** [connect] produced no stream and no session appeared underneath it. */
    object Unavailable : ConnectResult
  }

  /**
   * Returns the existing stream for [deviceId] if one is already connected for [boundTo],
   * otherwise calls [connect] to produce a new stream, stores it against that binding, and returns
   * it. The check-then-act is protected by a per-device [Mutex] so only one connect attempt can run
   * at a time for any given device. If [attach] publishes first while [connect] is suspended, its
   * stream wins and the unused candidate is closed.
   *
   * [binding] is what this connect is for - see [Binding] for the two axes and which of them
   * wildcards on null. A live session whose binding conflicts is reported as
   * [ConnectResult.BoundToOtherTarget] rather than reused, because the connection really is the
   * bound app: the Android connect installs its instrumentation runner and the iOS connect builds
   * its driver wrapper. Handing that connection back would drive one app while reporting the other,
   * or serve a stream whose driver this connect is about to replace.
   */
  suspend fun connectIfAbsent(
    deviceId: TrailblazeDeviceId,
    binding: Binding = Binding(),
    connect: suspend () -> DeviceScreenStream?,
  ): ConnectResult {
    val mutex = connectMutexes.getOrPut(deviceId) { Mutex() }
    return mutex.withLock {
      resultFromCurrentState(deviceId, binding)?.let { return@withLock it }
      val candidate = connect()
        ?: return@withLock resultFromCurrentState(deviceId, binding) ?: ConnectResult.Unavailable
      val winner = sessions.putIfAbsent(deviceId, Session(candidate, externallyOwned = false, binding = binding))
      if (winner == null) {
        ConnectResult.Ready(candidate)
      } else {
        if (candidate !== winner.stream) (candidate as? AutoCloseable)?.close()
        // [remove] runs outside this mutex, so the winner can have been dropped AND closed between
        // that putIfAbsent and this read. Report that rather than handing back a stream we can no
        // longer vouch for - a dead stream looks like a working connection to every caller.
        resultFromCurrentState(deviceId, binding) ?: ConnectResult.Unavailable
      }
    }
  }

  /**
   * The refusal a connect for [binding] gets from whoever holds [deviceId], or null when nothing is
   * in the way. Both kinds of holder are consulted, so no caller has to know that a device can be
   * held either by a session this registry streams or by a [claim] from an owner driving it itself.
   *
   * Public for the callers that open the device themselves instead of through [connectIfAbsent] -
   * Trail Runner's recorder and the MCP bridge - so the one rule, and the wording of its refusal,
   * live here rather than being restated per caller. Such a caller has to ask BEFORE it connects:
   * its own [attach] is a no-op while another session holds the device, so on a conflict it would
   * leave the registry serving the existing stream while reporting its own target as connected, and
   * the connect it already performed may have rebuilt (and closed) the driver that stream was
   * running on.
   */
  fun refusalFor(deviceId: TrailblazeDeviceId, binding: Binding): ConnectResult.BoundToOtherTarget? =
    refusal(deviceId, binding, asClaim = false)

  /**
   * The refusal a caller gets when what it wants is a [claim] rather than a session - it will drive
   * [deviceId] itself and publish no stream.
   *
   * Narrower than [refusalFor] on purpose, and the reason is the same one that narrows the claim
   * branch below: **a holder and an asker are only ever in each other's way on the axes they both
   * occupy.** A session owns a device's target, so two sessions contend over the target as well as
   * the driver. A claim owns nothing but the driver, so a claimer contends over the driver alone -
   * whichever side of the comparison it is on. Asking through [refusalFor] instead would refuse a
   * claimer for plain target B while a viewer holds plain target A, even though both run the
   * identical base driver and neither would disturb the other; it would also make the outcome depend
   * on connect order, since the reverse arrangement is allowed.
   */
  fun refusalForClaim(deviceId: TrailblazeDeviceId, binding: Binding): ConnectResult.BoundToOtherTarget? =
    refusal(deviceId, binding, asClaim = true)

  private fun refusal(
    deviceId: TrailblazeDeviceId,
    binding: Binding,
    asClaim: Boolean,
  ): ConnectResult.BoundToOtherTarget? =
    heldBy(sessions[deviceId], deviceId, binding, asClaim)

  /**
   * Every holder of [deviceId] that a connect for [binding] conflicts with, as a refusal, or null
   * when none of them are in the way. Both maps are consulted every time: a caller that conflicts
   * with a session AND a claim has to hear about both, or the remedy it is given won't be enough.
   *
   * [session] is passed in rather than read here so a caller that also needs the stream can decide
   * both from one snapshot - see [resultFromCurrentState].
   */
  private fun heldBy(
    session: Session?,
    deviceId: TrailblazeDeviceId,
    binding: Binding,
    asClaim: Boolean,
  ): ConnectResult.BoundToOtherTarget? {
    val heldBy = buildList {
      session?.heldFor(binding, asClaim)?.let { add(it) }
      claimHolder(deviceId, binding)?.let { add(it) }
    }
    return heldBy.takeIf { it.isNotEmpty() }?.let { ConnectResult.BoundToOtherTarget(it) }
  }

  /** How this session holds the device against a connect for [binding], or null when it can share. */
  private fun Session.heldFor(binding: Binding, asClaim: Boolean): ConnectResult.BoundToOtherTarget.Held? {
    val conflicts = if (asClaim) {
      this.binding.driverConflictsWith(binding)
    } else {
      this.binding.conflictsWith(binding)
    }
    if (!conflicts) return null
    val holder = if (externallyOwned) ConnectResult.Holder.RECORDER else ConnectResult.Holder.VIEWER
    return ConnectResult.BoundToOtherTarget.Held(this.binding, holder)
  }

  private fun claimHolder(
    deviceId: TrailblazeDeviceId,
    binding: Binding,
  ): ConnectResult.BoundToOtherTarget.Held? {
    // A claim is refused against on the driver axis alone, whoever is asking - the same rule
    // [refusalForClaim] applies from the other side. A claim exists to stop a connect from closing
    // and rebuilding the driver its owner is using, and only a driver mismatch does that - two plain
    // targets share one driver, so refusing across them would cost a reconnect and protect nothing.
    // It is also the only axis that can't go stale: `selectAppTarget`
    // keeps the persistent driver when the wrapper doesn't change, so a claim's target can name an
    // app the agent has since stopped driving, while its driver key by definition still names the
    // driver the agent holds.
    //
    // Known limit, stated rather than implied: this can only ever refuse an iOS Maestro connect.
    // Nothing else builds a driver through `HostIosDriverFactory`, so on Android, web and the
    // host-native iOS drivers a claim is inert - even though an Android connect installs its
    // target's instrumentation runner and a second owner would replace it. Giving Android a
    // claimable axis means widening the per-connect target contract those connects already follow,
    // which is its own decision with its own blast radius; not folded in here.
    //
    // Agreeing on the driver key is a share, not a copy: both owners hold the one driver
    // `HostIosDriverFactory` caches. What keeps a teardown on one side from killing the other is that
    // factory handing out a lease rather than the driver itself, so the XCUITest connection goes away
    // only when the last owner lets go. Not a binding problem and not this registry's to solve:
    // nothing is bound wrongly, and refusing the agreeing case would forbid the one arrangement where
    // no rebuild is needed at all.
    return claims[deviceId]
      ?.takeIf { it.driverConflictsWith(binding) }
      ?.let { ConnectResult.BoundToOtherTarget.Held(it, ConnectResult.Holder.AGENT) }
  }

  /**
   * What the registry as it stands right now gives a connect for [binding], or null when it has
   * nothing to give and the caller has to connect for itself.
   *
   * One read of [sessions], not two: [attach] can publish between two reads, and asking [refusalFor]
   * and then re-reading the map would check one session's binding and hand back a different
   * session's stream - which is how a connect for one target ends up holding a recorder's connection
   * to another.
   */
  private fun resultFromCurrentState(deviceId: TrailblazeDeviceId, binding: Binding): ConnectResult? {
    val session = sessions[deviceId]
    heldBy(session, deviceId, binding, asClaim = false)?.let { return it }
    return session?.let { ConnectResult.Ready(it.stream) }
  }

  /**
   * Whether a connection made for this binding can serve one asking for [other].
   *
   * Two independent reasons it can't: the two name different target apps, or they produced
   * different drivers. Only the first wildcards on null - see [Binding].
   */
  private fun Binding.conflictsWith(other: Binding): Boolean =
    driverConflictsWith(other) ||
      (targetId != null && other.targetId != null && targetId != other.targetId)

  /**
   * Whether these two connects would fight over one Maestro driver. Only asked of connects that
   * build one: a host-native iOS connect has no driver key because it has no driver here at all, and
   * comparing that absence against a real key refuses a connection nothing could disturb.
   */
  private fun Binding.driverConflictsWith(other: Binding): Boolean =
    buildsMaestroDriver && other.buildsMaestroDriver && driverKey != other.driverKey

  /**
   * Records that some other owner holds [deviceId] for [binding] and is driving it itself, so a
   * connect that would rebuild that owner's driver is refused instead. For the MCP bridge, whose
   * persistent drivers live in its own map and never produce a stream this registry could serve -
   * see [claims].
   *
   * Pass the whole [binding], not just the driver half [refusalFor] compares: the target is what a
   * refusal names to a user, and a non-null driver key means the target anyway.
   *
   * Overwrites any previous claim, unlike [attach]: a claim carries no stream that clobbering could
   * leak.
   *
   * Claim **before** you connect, as a reservation, and release it if the connect fails - not after,
   * once you have a driver to protect. The connect itself is the long part and the part that rebuilds
   * a mismatched driver, so claiming afterwards leaves exactly that stretch unguarded: `createIOS` is
   * `@Synchronized`, so another connect that already passed its own check waits out this whole build
   * on the factory monitor and then rebuilds the driver it produced, never rechecking. Drop the claim
   * with [releaseClaim] once the driver is closed.
   *
   * Even so, ask-then-claim is not atomic against a concurrent [connectIfAbsent] - the claiming owner
   * connects on its own thread, outside the per-device [Mutex]. Two connects that check at the same
   * instant can both see a clear device, which is the same narrow window [refusalFor] already has for
   * Trail Runner's recorder. Closing it entirely needs both connect paths under one lock; reserving
   * first shrinks it from "the whole physical connect" to "the gap between the two checks", which
   * covers the case that actually happens - one owner established, a second arriving later.
   */
  fun claim(deviceId: TrailblazeDeviceId, binding: Binding) {
    claims[deviceId] = binding
  }

  /** Drops [deviceId]'s [claim]. Safe to call when there is none. */
  fun releaseClaim(deviceId: TrailblazeDeviceId) {
    claims.remove(deviceId)
  }

  /**
   * Publishes an already-open, **externally-owned** [stream] for [deviceId] so the streaming and
   * screen-poll handlers can reach it, without this manager taking over its lifecycle. Used by
   * Trail Runner's recorder, which holds the connection (and its interaction tool factory) in its
   * own registry and closes it itself — see [detach].
   *
   * If a session is already registered for [deviceId] (e.g. a viewer-owned one from
   * [connectIfAbsent]) this is a no-op: clobbering it would leak the displaced stream, and the
   * existing one serves the same device's pixels anyway. That also makes re-attaching on every
   * recorder connect safe, which is how the recorder self-heals after a viewer-side [remove].
   *
   * [binding] is what the caller opened this connection for, recorded so a later conflicting
   * [connectIfAbsent] is refused rather than handed this stream. Leaving it at the default says the
   * connection was opened without a target and on the plain driver, not that its binding is unknown
   * - a caller that can't say what its connection is driving makes that refusal impossible.
   */
  fun attach(deviceId: TrailblazeDeviceId, stream: DeviceScreenStream, binding: Binding = Binding()) {
    sessions.putIfAbsent(deviceId, Session(stream, externallyOwned = true, binding = binding))
  }

  /**
   * Removes [deviceId]'s **externally-owned** entry ([attach]) from the registry **without**
   * closing its stream — the caller owns the lifecycle. A manager-owned session ([connectIfAbsent])
   * in the same slot is left untouched: it belongs to the viewer path, not the detaching caller.
   */
  fun detach(deviceId: TrailblazeDeviceId) {
    sessions.computeIfPresent(deviceId) { _, session -> if (session.externallyOwned) null else session }
  }

  /**
   * Drops [deviceId] from the registry, closing the stream only when this manager owns it (the
   * [connectIfAbsent] path). An externally-owned entry is removed without closing — its owner
   * (Trail Runner's recorder) keeps using it and re-publishes via [attach] on its next connect.
   */
  fun remove(deviceId: TrailblazeDeviceId) {
    val session = sessions.remove(deviceId) ?: return
    if (!session.externallyOwned) (session.stream as? AutoCloseable)?.close()
  }

  fun isConnected(deviceId: TrailblazeDeviceId): Boolean = sessions.containsKey(deviceId)
}
