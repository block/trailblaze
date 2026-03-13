package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Tracks which MCP session has claimed each device.
 *
 * Ensures exclusive device access: only one MCP session can use a device at a time.
 * This prevents two clients from accidentally controlling the same device simultaneously.
 *
 * Device claims are:
 * - Created when [DeviceManagerToolSet] connects to a device
 * - Released when an MCP session closes (client disconnect)
 * - Force-taken when a client passes `force=true` (e.g., to reclaim a stale session)
 */
class DeviceClaimRegistry(
  /** Maximum age of a claim before it's considered stale and auto-released. Default: 4 hours. */
  private val claimTtlMs: Long = 4 * 60 * 60 * 1000L,
  /**
   * Optional check for whether an MCP session is still alive.
   * When provided, claims from dead sessions are auto-released instead of blocking new claims.
   * This handles the case where a device session crashes (e.g., ExceptionThrown) and the
   * MCP session closes, but the claim wasn't released (e.g., transport closed uncleanly).
   */
  private val isSessionAlive: ((mcpSessionId: String) -> Boolean)? = null,
) {

  data class DeviceClaim(
    val mcpSessionId: String,
    val deviceId: TrailblazeDeviceId,
    val claimedAt: Long = System.currentTimeMillis(),
  )

  private val claims = HashMap<String, DeviceClaim>()

  /**
   * Claims a device for an MCP session.
   *
   * @param deviceId The device to claim
   * @param mcpSessionId The MCP session claiming the device
   * @param force If true, takes over the device even if another session owns it
   * @return The previous claim if force-taken, null if no conflict
   * @throws DeviceAlreadyClaimedException if claimed by another session and force=false
   */
  fun claim(
    deviceId: TrailblazeDeviceId,
    mcpSessionId: String,
    force: Boolean = false,
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

      // Another session owns this device
      if (existingClaim != null) {
        if (force) {
          Console.log(
            "[DeviceClaimRegistry] Force-claiming $key from session " +
              "${existingClaim.mcpSessionId} for session $mcpSessionId"
          )
        } else if (isSessionAlive != null && !isSessionAlive.invoke(existingClaim.mcpSessionId)) {
          // Owning session is no longer alive — auto-release the stale claim.
          // This handles the common case where a device session crashes, the MCP client
          // disconnects (removing its session context), and then a new session tries
          // to connect before the TTL eviction kicks in.
          Console.log(
            "[DeviceClaimRegistry] Auto-released claim for $key " +
              "(owning session ${existingClaim.mcpSessionId} is no longer alive)"
          )
        } else {
          throw DeviceAlreadyClaimedException(
            deviceId = deviceId,
            owningSessionId = existingClaim.mcpSessionId,
            claimedAt = existingClaim.claimedAt,
          )
        }
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
 * Thrown when a device is already claimed by another MCP session.
 */
class DeviceAlreadyClaimedException(
  val deviceId: TrailblazeDeviceId,
  val owningSessionId: String,
  val claimedAt: Long,
) : RuntimeException(
  "Device ${deviceId.instanceId} is already in use by another MCP session " +
    "(claimed ${formatTimeSince(claimedAt)}). " +
    "Use force=true to take over, or disconnect the other session first."
)

private fun formatTimeSince(timestampMs: Long): String {
  val elapsedMs = System.currentTimeMillis() - timestampMs
  val minutes = elapsedMs / 60_000
  return if (minutes < 1) "less than a minute ago" else "$minutes minute(s) ago"
}
