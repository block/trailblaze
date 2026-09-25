package xyz.block.trailblaze.rules

import kotlin.time.TimeSource

/**
 * Whether the log server can be reached — asked again when it could not be.
 *
 * This used to be one lazy ping. A single failed ping — a runner that started while the device's
 * network was still routed through a dead proxy, an `adb reverse` not yet in place, a host too busy
 * to answer inside the two-second budget — sent every log for the rest of that process to the disk
 * fallback, where nothing on the host ever looked for them. The host's recording generator then saw
 * none of the device's recordable tool logs, and `blaze --save` wrote a trail of bare steps.
 *
 * A `true` is final: once the server has answered, it stays the target. A `false` is re-asked once a
 * backoff has passed — [initialRetryAfterMs], doubling per miss up to [maxRetryAfterMs] — so a
 * channel that comes up late is picked up by the next log rather than never, while a process that
 * genuinely has no server (plain on-device runs writing to disk) pays at most one probe a minute.
 *
 * What this does NOT do is go back for the logs already written to disk during a backoff window.
 * Those stay there, unread, exactly as before. The window is now bounded — at most one backoff
 * rather than the whole process — but a device whose channel arrives late still loses the logs from
 * before it did. Closing that needs the rule to buffer and re-send, which is a different change
 * from deciding availability; this one only stops the loss from being permanent.
 */
class LogServerAvailability(
  private val probe: () -> Boolean,
  private val initialRetryAfterMs: Long = DEFAULT_INITIAL_RETRY_AFTER_MS,
  private val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
  private val nowMs: () -> Long = MONOTONIC_MS,
  private val onProbe: (available: Boolean, tookMs: Long) -> Unit = { _, _ -> },
) {
  private var reached = false
  private var lastMissAtMs: Long? = null
  private var retryAfterMs = initialRetryAfterMs

  /** Cheap once the server has answered; otherwise probes at most once per backoff window. */
  @Synchronized
  fun isAvailable(): Boolean {
    if (reached) return true
    val now = nowMs()
    val lastMiss = lastMissAtMs
    if (lastMiss != null && now - lastMiss < retryAfterMs) return false

    val available = probe()
    onProbe(available, nowMs() - now)
    if (available) {
      reached = true
    } else {
      if (lastMiss != null) retryAfterMs = (retryAfterMs * 2).coerceAtMost(maxRetryAfterMs)
      lastMissAtMs = now
    }
    return available
  }

  companion object {
    const val DEFAULT_INITIAL_RETRY_AFTER_MS = 5_000L
    const val DEFAULT_MAX_RETRY_AFTER_MS = 60_000L

    private val PROCESS_START = TimeSource.Monotonic.markNow()

    /**
     * Elapsed milliseconds since this class loaded, from a monotonic source rather than the wall
     * clock. A device syncs its clock over the network soon after boot, which is exactly when a
     * runner is waiting on its log channel; on wall time a backward correction makes the elapsed
     * backoff negative, and every reprobe is then suppressed until wall time climbs back to where
     * it had been — the indefinite disk-only run this class exists to prevent.
     */
    internal val MONOTONIC_MS: () -> Long = { PROCESS_START.elapsedNow().inWholeMilliseconds }
  }
}
