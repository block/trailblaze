package xyz.block.trailblaze.capture.video

/**
 * Finds clip-time zero for an `xcrun simctl io ... recordVideo` recording by watching the
 * recorder's own output.
 *
 * simctl does not start recording when it is spawned. It resolves a display, attaches to it, and
 * only then processes a first frame — announcing that with a `Recording started` line. Measured on
 * a booted simulator, that takes **389 ms**, and the resulting file's duration came up 388 ms short
 * of a spawn-anchored window: the footage begins at the announcement, not at the spawn.
 *
 * That matters because the report scales clip time onto the artifact's window. A window anchored at
 * the spawn claims the recording covers a few hundred ms it has no footage for, so every early step
 * is shown a frame from later than it should be. Anchoring here instead put the window within
 * **13 ms** of the screen over a 10 s recording, with no drift.
 *
 * Split out from [IosVideoCapture] so the decision — which line, and the instant it arrived — is
 * testable against real simctl output without a simulator.
 */
internal class SimctlRecordingStartWatcher(
  /** Test seam: the host clock the announcement is stamped against. */
  private val nowMs: () -> Long = System::currentTimeMillis,
) {

  /**
   * Host epoch of the first frame, or null when simctl never announced one — it failed to start,
   * or this Xcode words the line differently. Callers fall back to the spawn instant, which is what
   * this recorder used before and is still closer than nothing.
   *
   * Volatile because the drain thread writes it and `stop()` reads it.
   */
  @Volatile
  var recordingStartedAtMs: Long? = null
    private set

  /**
   * Feeds one line of recorder output. Only the **first** announcement counts: simctl prints other
   * lines before it (a display-defaulting note) and after it (completion, output path), and a
   * re-announcement would otherwise move a window that already has footage behind it.
   */
  fun observe(line: String) {
    if (recordingStartedAtMs == null && isRecordingStartedLine(line)) {
      recordingStartedAtMs = nowMs()
    }
  }

  /**
   * Clip-time zero for the artifact window: the measured first-frame instant when simctl announced
   * one, otherwise [spawnedAtMs].
   *
   * The fallback is deliberately the spawn rather than "no window". A recording with a window a few
   * hundred ms too wide is still usable — the report places steps slightly early — whereas an
   * artifact with no window is dropped by the report entirely, which would cost the session its
   * video over a missing log line.
   */
  fun clipZeroMs(spawnedAtMs: Long): Long = recordingStartedAtMs ?: spawnedAtMs

  companion object {
    /**
     * True for simctl's first-frame announcement. Matched as a prefix of the trimmed line rather
     * than by equality, so a future suffix (a display name, a resolution) still counts; and
     * deliberately NOT a `contains`, so the `Wrote video to: ...` line can't match by way of a
     * session path that happens to contain the phrase.
     */
    internal fun isRecordingStartedLine(line: String): Boolean =
      line.trim().startsWith(RECORDING_STARTED_PREFIX, ignoreCase = true)

    private const val RECORDING_STARTED_PREFIX = "Recording started"
  }
}
