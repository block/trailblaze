package xyz.block.trailblaze.ui

/**
 * Short-lived freshness gate for device discovery.
 *
 * A single `trailblaze` CLI command (e.g. `device connect`, `snapshot`) fans out into several
 * *sequential* daemon-side device-discovery passes — LIST, platform-select, a connect fallback,
 * and the session pin all call `getAvailableDevices()` / `loadDevicesSuspend()` independently.
 * Each pass re-runs the full enumeration from scratch: `adb devices`, then per Android device
 * `adb shell pm list packages`; on macOS also `xcrun simctl list devices booted` plus one
 * `xcrun simctl listapps` per booted simulator and one `plutil` per installed iOS app. Because
 * the passes are sequential, the in-flight coalescing mutex never helps — it only merges
 * *concurrent* callers.
 *
 * This gate lets a pass reuse the immediately-preceding pass's result while it is still within
 * [ttlMs], collapsing the per-command fan-out to a single real discovery. The TTL is deliberately
 * short so a device plugged in or unplugged between user commands is still picked up on the next
 * command; [invalidate] and `forceRefresh` cover the cases where the caller knows the topology
 * just changed.
 *
 * Pure and clock-injected so the freshness policy is unit-testable without a real device or a
 * wall clock.
 */
internal class DeviceDiscoveryCache(
  private val ttlMs: Long,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  @Volatile private var lastRefreshMs: Long? = null

  /**
   * True when a discovery pass completed within [ttlMs] of now — i.e. the cached result is still
   * fresh enough to reuse instead of re-running discovery. Always false when [ttlMs] is `<= 0`
   * (the cache is disabled) or no pass has completed yet.
   *
   * A negative elapsed time (the clock moved backwards via NTP/manual adjustment) is treated as
   * stale, not fresh — otherwise a backwards jump would keep the cache "fresh" past the TTL and
   * mask a topology change until the clock caught back up.
   */
  fun isFresh(): Boolean {
    if (ttlMs <= 0L) return false
    val last = lastRefreshMs ?: return false
    val elapsed = nowMs() - last
    return elapsed in 0L until ttlMs
  }

  /** Record that a full discovery pass just completed successfully. */
  fun markRefreshed() {
    lastRefreshMs = nowMs()
  }

  /**
   * Drop the cache so the next [isFresh] returns false. Call this when device topology
   * deliberately changed (e.g. a browser slot was just launched) so a subsequent cached read
   * can't serve the pre-change device list.
   */
  fun invalidate() {
    lastRefreshMs = null
  }
}
