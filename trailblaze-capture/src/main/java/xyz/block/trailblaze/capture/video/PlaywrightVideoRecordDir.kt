package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge between [PlaywrightVideoCapture] (in this module) and the Playwright browser
 * manager (in `trailblaze-playwright`) — needed because Playwright's video recording
 * is configured at `Browser.newContext()` time, but the capture stream lifecycle is
 * driven by [xyz.block.trailblaze.capture.CaptureSession] before the browser exists.
 *
 * Lifecycle for a WEB capture run:
 *  1. `CaptureSession.startAll(...)` calls [PlaywrightVideoCapture.start], which
 *     publishes the per-session temp directory here via [setRecordDir].
 *  2. The Playwright browser manager is constructed later. Inside
 *     `createFreshContextAndPage()` it calls [getRecordDir] for its device id and,
 *     if non-null, passes the directory to `Browser.NewContextOptions.setRecordVideoDir`.
 *     The manager may also publish a [setFinalizer] callback that closes the active
 *     `BrowserContext` so the `.webm` is flushed on demand.
 *  3. `CaptureSession.stopAll()` triggers [PlaywrightVideoCapture.stop], which calls
 *     [runFinalizer] (no-op if the browser was already closed normally), then locates
 *     the `.webm`, transcodes to `video.mp4`, and returns a `CaptureArtifact`.
 *
 * Keyed by Playwright device id (e.g. the value of
 * `TrailblazeDeviceId.instanceId` for a WEB device) — same key the rest of the
 * capture pipeline uses, so multi-device parallel runs do not collide.
 */
object PlaywrightVideoRecordDir {

  private data class Entry(
    val dir: File,
    @Volatile var finalizer: (() -> Unit)? = null,
  )

  private val entries = ConcurrentHashMap<String, Entry>()

  fun setRecordDir(deviceId: String, dir: File) {
    entries[deviceId] = Entry(dir = dir)
  }

  fun clearRecordDir(deviceId: String) {
    entries.remove(deviceId)
  }

  fun getRecordDir(deviceId: String): File? = entries[deviceId]?.dir

  /**
   * Publishes a "flush the current video to disk" callback for [deviceId]. The Playwright
   * manager registers this once its `BrowserContext` is up; [runFinalizer] invokes it so
   * the capture stream can force the `.webm` to finalize even when the caller-owned
   * browser is being kept alive across sessions.
   */
  fun setFinalizer(deviceId: String, finalizer: () -> Unit) {
    entries[deviceId]?.finalizer = finalizer
  }

  fun clearFinalizer(deviceId: String) {
    entries[deviceId]?.finalizer = null
  }

  /** Runs the finalizer for [deviceId] if one is registered. Swallows callback exceptions. */
  fun runFinalizer(deviceId: String) {
    val finalizer = entries[deviceId]?.finalizer ?: return
    try {
      finalizer()
    } catch (_: Throwable) {
      // Best-effort flush — capture stream will fall through to whatever is on disk.
    }
  }
}
