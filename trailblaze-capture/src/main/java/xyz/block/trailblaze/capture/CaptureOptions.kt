package xyz.block.trailblaze.capture

/**
 * Options for capture, controlled by CLI flags or desktop app settings.
 *
 * Video capture is off by default — it writes large files and its timing signatures drift on some
 * hosts. Opt in per run with `--capture-video` on the CLI (or the desktop app's "Capture video"
 * toggle).
 */
data class CaptureOptions(
  val captureVideo: Boolean = false,
  /**
   * Capture Android logcat (filtered to the app under test) to `device.log`. On by default
   * (only takes effect when running on Android). Disable with `--no-capture-logcat`.
   */
  val captureLogcat: Boolean = true,
  /**
   * Capture the iOS Simulator system log via `xcrun simctl spawn log stream`. On by default
   * (only takes effect when running on iOS). [xyz.block.trailblaze.capture.logcat.IosLogCapture]
   * scopes the stream to the app under test at `--level info`, so this is the logcat-equivalent
   * app log — not the system firehose. Disable with `--no-capture-ios-logs`.
   */
  val captureIosLogs: Boolean = true,
  /**
   * Track the app under test's memory for the whole session as the `memory` event stream: a
   * sample around every tool call, plus a periodic one whenever it changed — see
   * [xyz.block.trailblaze.capture.memory.MemoryCapture]. Each event is just heap used vs. the
   * heap limit and whether a GC ran first (Android), or the footprint (iOS Simulator). By default
   * every reading is taken in the background, so the trail never waits for one. On by default;
   * disable with `--no-capture-memory`.
   */
  val captureMemory: Boolean = true,
  /**
   * Memory diagnostics mode: each tool call is bracketed by a reading taken synchronously right
   * before and right after it (an exact per-action delta), and on Android the app is asked to
   * collect garbage first so `heap used` is live objects only. Off by default because it adds two
   * readings to every tool call's wall clock; turn it on for a run where you are diagnosing memory
   * with `TRAILBLAZE_MEMORY_DIAGNOSTICS=true` on the daemon (see [ENV_MEMORY_DIAGNOSTICS]). Each
   * `memory` event records whether a GC happened (`gcForced`) and how long the reading took
   * (`readMs`).
   */
  val memoryDiagnostics: Boolean = false,
) {
  val hasAnyCaptureEnabled: Boolean
    get() = captureVideo || captureLogcat || captureIosLogs || captureMemory

  companion object {
    /**
     * No capture at all — every stream off. Explicit (not `CaptureOptions()`) because the
     * constructor defaults logcat/iOS-logs/memory ON, so `CaptureOptions()` is not "none". Used as
     * `CaptureStream.stop`'s default arg.
     */
    val NONE = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = false,
      captureMemory = false,
    )

    /**
     * Turns session video back ON for every host-driven run in this process. Video is opt-in
     * (see [captureVideo]), and a CI pipeline has no CLI flag to reach through — its trails are
     * launched by scripts it doesn't own. This is the one-line, no-release lever that gets the
     * recording back for a lane or a debugging session.
     *
     * Only a truthy value opts in; a falsey one reads the same as unset. It is outranked by an
     * explicit per-run choice, so `--no-capture-video` still turns video off in a lane that
     * exports this.
     */
    const val ENV_CAPTURE_VIDEO = "TRAILBLAZE_CAPTURE_VIDEO"

    /**
     * Turns memory capture OFF for every session in this process — the reverse of
     * [ENV_CAPTURE_VIDEO], because memory is on by default (see [captureMemory]).
     *
     * A lane whose trails are launched by scripts it does not own has no CLI flag to reach
     * through, so without this the only way off a stream misbehaving on some device or emulator
     * image is a release. Only an explicit falsey value (`0` / `false`) turns it off; anything
     * else, including a typo, reads as unset and leaves the default alone — a malformed value
     * must not silently cost a lane its diagnostics.
     */
    const val ENV_CAPTURE_MEMORY = "TRAILBLAZE_CAPTURE_MEMORY"

    /**
     * Whether [ENV_CAPTURE_MEMORY] explicitly switches memory capture off.
     *
     * Read where memory capture is STARTED rather than folded into [captureMemory], so it covers
     * every route in — the CLI builds its own options, the daemon resolves a per-run override
     * against a persisted setting, and a kill-switch covering only one of them would not be one.
     */
    fun memoryCaptureDisabledInEnv(env: (String) -> String? = System::getenv): Boolean {
      val raw = env(ENV_CAPTURE_MEMORY)?.trim()?.lowercase() ?: return false
      return raw == "0" || raw == "false"
    }

    /**
     * Turns memory diagnostics on for every session in this process — see [memoryDiagnostics].
     * Read by the daemon, so a running one must be restarted for a change to apply. Only a truthy
     * value opts in; anything else reads as off.
     */
    const val ENV_MEMORY_DIAGNOSTICS = "TRAILBLAZE_MEMORY_DIAGNOSTICS"

    /**
     * Capture options for host-driven sessions.
     *
     * Video resolves in one place here, in precedence order: an explicit per-run [captureVideo]
     * (the CLI's `--capture-video` / `--no-capture-video`) wins outright; a null one — the user
     * said nothing — inherits [ENV_CAPTURE_VIDEO], then the persisted
     * [persistedCaptureVideo] (`trailblaze config capture-video`), then off. The env and config
     * tiers are what reach the interactive session and MCP paths, which have no per-run flag to
     * pass. Callers must NOT pre-collapse their null with `?: persisted` — that would make an
     * explicit "no video" indistinguishable from silence and let the environment override it.
     */
    fun hostCaptureOptions(
      captureVideo: Boolean? = null,
      persistedCaptureVideo: Boolean = false,
      captureLogcat: Boolean = true,
      captureIosLogs: Boolean = true,
      captureMemory: Boolean = true,
      memoryDiagnostics: Boolean? = null,
      env: (String) -> String? = System::getenv,
    ): CaptureOptions = CaptureOptions(
      captureVideo = captureVideo ?: (envFlagOn(env, ENV_CAPTURE_VIDEO) || persistedCaptureVideo),
      captureLogcat = captureLogcat,
      captureIosLogs = captureIosLogs,
      captureMemory = captureMemory,
      memoryDiagnostics = memoryDiagnostics ?: envFlagOn(env, ENV_MEMORY_DIAGNOSTICS),
    )

    /**
     * True only for an explicit truthy value (`1` / `true`, case-insensitive). Anything else —
     * unset, blank, `0`, `false`, or a typo — reads as "no opt-in", so a malformed value can
     * never silently switch a large-artifact stream on.
     */
    private fun envFlagOn(env: (String) -> String?, name: String): Boolean {
      val raw = env(name)?.trim()?.lowercase() ?: return false
      return raw == "1" || raw == "true"
    }
  }
}
