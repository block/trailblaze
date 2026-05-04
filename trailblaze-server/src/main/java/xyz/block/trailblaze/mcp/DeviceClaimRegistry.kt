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
   */
  fun claim(
    deviceId: TrailblazeDeviceId,
    mcpSessionId: String,
  ): DeviceClaim? {
    val key = deviceId.instanceId

    synchronized(this) {
      // Evict stale claims on each claim attempt to prevent permanent lockout
      // after unclean shutdown or disconnects that bypass onSessionClosed.
      evictStaleClaims()

      val existingClaim = claims[key]

      // Same session re-claiming — always allowed, but refresh timestamp to prevent stale eviction
      if (existingClaim?.mcpSessionId == mcpSessionId) {
        claims[key] = existingClaim.copy(claimedAt = System.currentTimeMillis())
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
        claims.remove(key)
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
    val now = System.currentTimeMillis()
    val staleKeys = claims.entries
      .filter { now - it.value.claimedAt > claimTtlMs }
      .map { it.key }
    staleKeys.forEach { key ->
      val stale = claims.remove(key)
      if (stale != null) {
        Console.log(
          "[DeviceClaimRegistry] Evicted stale claim for device $key " +
            "(session ${stale.mcpSessionId}, claimed ${formatTimeSince(stale.claimedAt)})"
        )
      }
    }
  }
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
  val deviceId: TrailblazeDeviceId,
  val owningSessionId: String,
  val owningClient: DeviceClaimRegistry.ClaimingClientInfo?,
  val inFlight: InFlightToolCall,
) : RuntimeException(
  buildBusyMessage(deviceId, owningSessionId, owningClient, inFlight),
)

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
