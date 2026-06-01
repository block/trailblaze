package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.block.trailblaze.util.Console

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

  /**
   * Runs the finalizer for [deviceId] if one is registered, bounded by
   * [FINALIZER_TIMEOUT_MS]. Swallows callback exceptions and timeouts.
   *
   * The finalizer typically dispatches `BrowserContext.close()` onto a Playwright-
   * affinity dispatcher via `runBlocking(playwrightDispatcher) { ... }` (see
   * `PlaywrightBrowserManager.setFinalizer`). If that dispatcher is wedged (the
   * known thread-affinity deadlock pattern flagged in `PlaywrightThreadBridge.kt`'s
   * kdoc — "wedged a 1800s timeout on a 30-second test"), the `runBlocking` will
   * never return. Caller-side coroutine cancellation (`withTimeoutOrNull`) does not
   * help here because the blocking `runBlocking(dispatcher)` is not cooperatively
   * cancellable — only a separate-thread bound + abandon does.
   *
   * On timeout we log + drop the in-flight flush and let the capture stream fall
   * back to whatever was already written to disk. The Playwright `.webm` is
   * append-only as the page runs, so the on-disk file is typically close to
   * complete even when the close-and-finalize step fails. The thread is left to
   * unwind on its own (the executor's daemon flag prevents JVM-shutdown hang);
   * `future.cancel(true)` interrupts it best-effort.
   */
  fun runFinalizer(deviceId: String) {
    runFinalizer(deviceId, FINALIZER_TIMEOUT_MS)
  }

  /**
   * Timeout-injectable variant of [runFinalizer]. Visible for testing so the bounded-return
   * guarantee (a wedged finalizer must not hang the caller) can be exercised without waiting
   * the full production [FINALIZER_TIMEOUT_MS]. Production callers use the no-argument overload
   * above; [timeoutMs] is only varied from tests.
   */
  internal fun runFinalizer(deviceId: String, timeoutMs: Long) {
    val finalizer = entries[deviceId]?.finalizer ?: return
    val executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "playwright-video-finalizer-$deviceId").apply { isDaemon = true }
    }
    val future = executor.submit {
      try {
        finalizer()
      } catch (_: Throwable) {
        // Best-effort flush — capture stream will fall through to whatever is on disk.
      }
    }
    try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      Console.log(
        "[PlaywrightVideoRecordDir] finalizer for deviceId=$deviceId timed out after " +
          "${timeoutMs}ms — falling back to on-disk artifact",
      )
      future.cancel(true)
    } catch (_: Throwable) {
      // Other Future-side errors (InterruptedException, ExecutionException) — swallowed,
      // preserving the existing "best-effort flush" contract.
    } finally {
      // Drop the executor reference; the daemon thread (if still running after a
      // timeout) will not block JVM shutdown.
      executor.shutdownNow()
    }
  }

  /**
   * Upper bound on a finalizer invocation. The legitimate path is fast (a few hundred
   * ms for `BrowserContext.close()` + recreate); 30 s is generous enough to absorb a
   * slow CI runner without letting a deadlock wedge the daemon's run-status pipeline
   * the way the historical 1800s symptom did.
   */
  private const val FINALIZER_TIMEOUT_MS = 30_000L
}
