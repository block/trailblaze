package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Tracks which MCP session has claimed each device.
 *
 * Policy is **yield-unless-busy**: a new claim attempt for a device that
 * another session already owns will silently take over UNLESS that session is
 * actively running a tool call right now. In that case the new attempt fails
 * with a rich [DeviceBusyException] that names the holder, the running tool,
 * and how long it's been running, so the user can decide whether to wait.
 *
 * Both lookups are nullable for testability — pass them when constructing the
 * registry for a real server. Without them the registry falls back to "always
 * yield" (no information, but never blocks).
 */
class DeviceClaimRegistry(
  /** Maximum age of a claim before it's considered stale and auto-released. Default: 4 hours. */
  private val claimTtlMs: Long = 4 * 60 * 60 * 1000L,
  /**
   * Returns the in-flight tool call for a session, or null when the session is idle.
   * A null result means the holder isn't actively driving the device, so a new
   * claimant takes over silently.
   */
  private val inFlightLookup: ((mcpSessionId: String) -> InFlightToolCall?)? = null,
  /**
   * Returns identifying info about a session for the busy-error message.
   * Null when the session is unknown to the server (already cleaned up).
   */
  private val clientInfoLookup: ((mcpSessionId: String) -> ClaimingClientInfo?)? = null,
  /** Told when a device gains its first claim or loses its last one; see [DeviceClaimHooks]. */
  private val hooks: DeviceClaimHooks? = null,
) {

  /** Identifying info about the session holding a claim. Surfaced in [DeviceBusyException]. */
  data class ClaimingClientInfo(
    val mcpClientName: String?,
    val origin: String?,
  )

  data class DeviceClaim(
    val mcpSessionId: String,
    val deviceId: TrailblazeDeviceId,
    val claimedAt: Long = System.currentTimeMillis(),
  )

  private val claims = HashMap<String, DeviceClaim>()

  init {
    hooks?.watchToolCalls(::hasToolCallInFlight)
  }

  /** True while the session claiming [deviceId] is in the middle of a tool call. */
  fun hasToolCallInFlight(deviceId: TrailblazeDeviceId): Boolean {
    val claim = getClaim(deviceId) ?: return false
    return inFlightLookup?.invoke(claim.mcpSessionId) != null
  }

  /**
   * Claims a device for an MCP session.
   *
   * Same-session re-claims always succeed and refresh the timestamp.
   * Cross-session claims yield silently when the prior holder is idle, and
   * throw [DeviceBusyException] when the prior holder is running a tool call.
   *
   * @return The previous claim if it was displaced, null when there was no
   *   conflict. Callers use the non-null return to terminate the displaced
   *   session cleanly.
   * @throws DeviceBusyException when the prior holder has a tool call in flight.
   * @throws DeviceUnavailableException when [DeviceClaimHooks.beforeClaim] refuses the device (a
   *   borrowed emulator someone else is using, or one that can't be reached).
   */
  fun claim(
    deviceId: TrailblazeDeviceId,
    mcpSessionId: String,
  ): DeviceClaim? {
    val key = deviceId.instanceId
    // Outside the lock: a hook may block on the network (leasing a remote device). A busy device
    // is already claimed, so whatever the hook holds for it stays held for the current holder.
    try {
      hooks?.beforeClaim(deviceId)
    } catch (e: DeviceUnavailableException) {
      throw e
    } catch (e: RuntimeException) {
      throw DeviceUnavailableException(deviceId, e.message ?: "${deviceId.instanceId} can't be claimed", e)
    }
    return claimLocked(deviceId, key, mcpSessionId)
  }

  private fun claimLocked(deviceId: TrailblazeDeviceId, key: String, mcpSessionId: String): DeviceClaim? {
    synchronized(this) {
      // Evict stale claims on each claim attempt to prevent permanent lockout
      // after unclean shutdown or disconnects that bypass onSessionClosed.
      evictStaleClaims()

      val existingClaim = claims[key]

      // Same session re-claiming — always allowed, but refresh timestamp to prevent stale eviction
      if (existingClaim?.mcpSessionId == mcpSessionId) {
        claims[key] = existingClaim.copy(claimedAt = System.currentTimeMillis())
        hooks?.onClaimed(deviceId)
        return null
      }

      // Another session owns this device — yield unless they're busy.
      if (existingClaim != null) {
        val inFlight = inFlightLookup?.invoke(existingClaim.mcpSessionId)
        if (inFlight != null) {
          throw DeviceBusyException(
            deviceId = deviceId,
            owningSessionId = existingClaim.mcpSessionId,
            owningClient = clientInfoLookup?.invoke(existingClaim.mcpSessionId),
            inFlight = inFlight,
          )
        }
        Console.log(
          "[DeviceClaimRegistry] Yielded $key from session ${existingClaim.mcpSessionId} " +
            "to $mcpSessionId (prior holder was idle)"
        )
      }

      claims[key] = DeviceClaim(
        mcpSessionId = mcpSessionId,
        deviceId = deviceId,
      )
      hooks?.onClaimed(deviceId)
      Console.log("[DeviceClaimRegistry] Device $key claimed by session $mcpSessionId")
      return existingClaim
    }
  }

  /**
   * Releases a specific device claim held by a session.
   * Only releases the claim if it is currently owned by the given session.
   *
   * @return true if the claim was released, false if the device was not claimed by this session.
   */
  fun release(deviceId: TrailblazeDeviceId, mcpSessionId: String): Boolean {
    val key = deviceId.instanceId
    synchronized(this) {
      val existing = claims[key]
      if (existing != null && existing.mcpSessionId == mcpSessionId) {
        claims.remove(key)
        hooks?.onReleased(existing.deviceId)
        Console.log("[DeviceClaimRegistry] Device $key released by session $mcpSessionId")
        return true
      }
      return false
    }
  }

  /**
   * Releases all device claims held by a session.
   * Called when an MCP session is closed/disconnected.
   */
  fun releaseAllForSession(mcpSessionId: String) {
    synchronized(this) {
      val toRemove = claims.entries
        .filter { it.value.mcpSessionId == mcpSessionId }
        .map { it.key }
      toRemove.forEach { key ->
        claims.remove(key)?.let { hooks?.onReleased(it.deviceId) }
        Console.log("[DeviceClaimRegistry] Device $key released (session $mcpSessionId closed)")
      }
    }
  }

  /**
   * Returns the current claim for a device, if any.
   */
  fun getClaim(deviceId: TrailblazeDeviceId): DeviceClaim? {
    synchronized(this) {
      return claims[deviceId.instanceId]
    }
  }

  /**
   * Removes claims older than [claimTtlMs].
   * Called inside [claim] while holding the synchronized lock.
   */
  private fun evictStaleClaims() {
    if (claims.isEmpty()) return
    val now = System.currentTimeMillis()
    val staleKeys = claims.entries
      .filter { now - it.value.claimedAt > claimTtlMs }
      .map { it.key }
    staleKeys.forEach { key ->
      val stale = claims.remove(key)
      if (stale != null) {
        hooks?.onReleased(stale.deviceId)
        Console.log(
          "[DeviceClaimRegistry] Evicted stale claim for device $key " +
            "(session ${stale.mcpSessionId}, claimed ${formatTimeSince(stale.claimedAt)})"
        )
      }
    }
  }
}

/**
 * Lets the host do work as devices are claimed and released — leasing a remote device and giving
 * it back, today. A claim passed from one session to another (a yield) is not a release: the
 * device stays claimed throughout.
 */
interface DeviceClaimHooks {
  /**
   * Called before every claim of [deviceId], re-claims included, outside the registry's lock so
   * it may block (on the network, say), and therefore idempotent. May throw to refuse the claim,
   * with a message the user will see.
   */
  fun beforeClaim(deviceId: TrailblazeDeviceId)

  /**
   * Called under the registry's lock each time [deviceId] is claimed, re-claims and yields
   * included. Called in the registry's order with [onReleased], so must return at once.
   */
  fun onClaimed(deviceId: TrailblazeDeviceId)

  /** Called under the registry's lock once [deviceId]'s claim is gone. Must return at once. */
  fun onReleased(deviceId: TrailblazeDeviceId)

  /**
   * Called once, as the registry is made, with a way to ask whether the session claiming a device
   * is in the middle of a tool call. Not under the registry's lock.
   */
  fun watchToolCalls(inFlight: (TrailblazeDeviceId) -> Boolean) {}
}

/**
 * Thrown when a device is currently being driven by another session and
 * yielding the claim would interrupt real work in progress.
 *
 * The message embeds everything the user needs to decide whether to wait:
 * - which client/origin holds the device,
 * - what tool is running and (if available) its objective,
 * - how long it's been running,
 * - the trace ID for log correlation.
 */
class DeviceBusyException(
  deviceId: TrailblazeDeviceId,
  val owningSessionId: String,
  val owningClient: DeviceClaimRegistry.ClaimingClientInfo?,
  val inFlight: InFlightToolCall,
) : DeviceUnavailableException(
  deviceId,
  buildBusyMessage(deviceId, owningSessionId, owningClient, inFlight),
)

/**
 * [deviceId] can't be claimed right now, for the reason in [message], which is written for the user.
 * Callers of [DeviceClaimRegistry.claim] catch this to roll back and report, whatever the reason.
 */
open class DeviceUnavailableException(
  val deviceId: TrailblazeDeviceId,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

private fun buildBusyMessage(
  deviceId: TrailblazeDeviceId,
  owningSessionId: String,
  owningClient: DeviceClaimRegistry.ClaimingClientInfo?,
  inFlight: InFlightToolCall,
): String {
  val durationSec = ((System.currentTimeMillis() - inFlight.startedAtMs) / 1000).coerceAtLeast(0)
  val holder = buildString {
    append(owningClient?.mcpClientName ?: "another MCP session")
    owningClient?.origin?.let { append(" (origin=").append(it).append(")") }
  }
  val running = buildString {
    append(inFlight.toolName)
    inFlight.argsSummary?.takeIf { it.isNotBlank() }?.let { append("(").append(it).append(")") }
  }
  return buildString {
    append("Device ${deviceId.instanceId} is busy.\n")
    append("  Held by: $holder (session ${owningSessionId.take(8)}…)\n")
    append("  Running: $running for ${durationSec}s\n")
    append("  Trace:   ${inFlight.traceId}\n")
    append("Wait for it to finish, or stop the holder before retrying.")
  }
}

private fun formatTimeSince(timestampMs: Long): String {
  val elapsedMs = System.currentTimeMillis() - timestampMs
  val minutes = elapsedMs / 60_000
  return if (minutes < 1) "less than a minute ago" else "$minutes minute(s) ago"
}
