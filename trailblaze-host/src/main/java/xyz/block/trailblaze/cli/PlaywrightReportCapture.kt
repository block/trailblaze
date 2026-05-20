package xyz.block.trailblaze.cli

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotAnimations
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.util.Console

/**
 * Shared scaffolding for the headless-Playwright report-export pipeline. Both
 * [ReportGifExporter] and [ReportWebpExporter] capture PNG frames in exactly the same way
 * — only the final encoder step differs. [ReportVideoExporter] takes a different path
 * (Playwright's `setRecordVideoDir` for a WebM→MP4 transcode) but still benefits from the
 * URL / install-progress helpers here.
 *
 * Keeping the capture loop in one place is the practical mitigation for the lead-dev-review
 * comment on PR #3083: with three exporters in the tree, the screenshot cadence + playback
 * end detection + measured-fps computation only need to be right once.
 */
internal object PlaywrightReportCapture {

  /** Upper bound on how long we'll wait for the timeline's playback-ended signal. */
  internal const val MAX_PLAYBACK_WAIT_MS: Long = 10 * 60 * 1000L

  /**
   * Capture cadence — `page.screenshot()` synchronously costs roughly 50–150ms per frame
   * on a typical workstation, so 5fps is the realistic ceiling without dropping/sliding
   * frames. It's also a sweet spot for output file size: at 30fps a 60s autoplay would
   * easily blow past 50MB as a GIF.
   */
  internal const val FRAME_INTERVAL_MS: Long = 200L

  data class CaptureResult(val frameCount: Int, val measuredFps: Int)

  /**
   * Per-export filesystem workspace for the GIF / WebP path: a unique `deviceId` (avoids
   * collisions when multiple exports run concurrently in the same JVM — CI matrix, etc.),
   * a temp `tempDir` rooted under `java.io.tmpdir`, and a `framesDir` inside it where
   * Playwright drops `frame_NNNNN.png` files. Caller owns the cleanup
   * (`tempDir.deleteRecursively()` in a `finally` block).
   */
  data class FrameWorkspace(val deviceId: String, val tempDir: File, val framesDir: File)

  /**
   * Allocate a fresh [FrameWorkspace] for a frame-capture export. [kind] names the
   * exporter (`gif` / `webp`) for log + path readability — keeps temp dirs from
   * colliding when both exporters run side-by-side, and makes leftover dirs
   * recognizable for cleanup.
   */
  fun newFrameWorkspace(kind: String): FrameWorkspace {
    val deviceId = "report-$kind-${UUID.randomUUID().toString().take(8)}"
    val tempDir = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-report-$kind-$deviceId",
    ).apply { mkdirs() }
    val framesDir = File(tempDir, "frames").apply { mkdirs() }
    return FrameWorkspace(deviceId, tempDir, framesDir)
  }

  /**
   * Loads [reportHtml] with `?autoplay=1` in a headless Playwright tab, screenshots the
   * viewport at [FRAME_INTERVAL_MS] cadence until the timeline's
   * `__tbPlaybackEnded` global flips true, and returns counts the caller needs to feed
   * the downstream ffmpeg encoder.
   *
   * Throws (via `error`) on capture failure — timeout or zero frames — so the caller
   * doesn't silently produce a truncated artifact and exit 0.
   *
   * @param reportHtml Single-session report HTML (multi-session reports land on the list
   *   view and never trigger autoplay).
   * @param framesDir Directory to write `frame_NNNNN.png` files into. Created by caller.
   * @param tag Log-line prefix, e.g. `ReportGifExporter` / `ReportWebpExporter`. Used so
   *   each exporter's logs stay grep-distinguishable.
   */
  fun captureFrames(
    reportHtml: File,
    framesDir: File,
    headless: Boolean,
    deviceId: String,
    tag: String,
  ): CaptureResult {
    val onInstallProgress = makeInstallProgressLogger(tag)
    var manager: PlaywrightBrowserManager? = null
    var capturedFrames = 0
    var playbackEnded = false
    var captureStartMs = 0L
    var captureEndMs = 0L
    try {
      manager = PlaywrightBrowserManager(
        headless = headless,
        deviceId = deviceId,
        onBrowserInstallProgress = onInstallProgress,
      )
      val mgr = manager
      runBlocking(mgr.playwrightDispatcher) {
        val page = mgr.currentPage
        val url = buildReportUrl(reportHtml)
        Console.log("[$tag] navigating to $url")
        page.navigate(url)
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)

        Console.log("[$tag] capturing frames until timeline playback ends...")
        val screenshotOptions = Page.ScreenshotOptions()
          .setFullPage(false)
          .setAnimations(ScreenshotAnimations.DISABLED)
        captureStartMs = System.currentTimeMillis()
        val deadline = captureStartMs + MAX_PLAYBACK_WAIT_MS
        var nextFrameTime = captureStartMs
        while (System.currentTimeMillis() < deadline) {
          val now = System.currentTimeMillis()
          if (now < nextFrameTime) delay(nextFrameTime - now)
          val png = page.screenshot(screenshotOptions)
          val frame = File(framesDir, String.format("frame_%05d.png", capturedFrames))
          frame.writeBytes(png)
          capturedFrames++

          val ended = page.evaluate("() => globalThis.__tbPlaybackEnded === true") as? Boolean ?: false
          if (ended) {
            playbackEnded = true
            break
          }
          nextFrameTime += FRAME_INTERVAL_MS
        }
        captureEndMs = System.currentTimeMillis()
        Console.log("[$tag] captured $capturedFrames frames")
      }
    } finally {
      runCatching { manager?.close() }
    }

    if (!playbackEnded) {
      error(
        "Timeline playback did not signal completion within " +
          "${MAX_PLAYBACK_WAIT_MS / 1000}s — the captured output would be truncated. " +
          "Try increasing MAX_PLAYBACK_WAIT_MS or shortening the session.",
      )
    }
    if (capturedFrames == 0) error("No frames were captured — Playwright produced zero screenshots.")

    val measuredFps = computeFps(capturedFrames, captureEndMs - captureStartMs)
    return CaptureResult(frameCount = capturedFrames, measuredFps = measuredFps)
  }

  /**
   * Throttled install-progress callback for [PlaywrightBrowserManager]. The underlying
   * `playwright install chromium` invocation emits one callback per output line — too
   * chatty for a CLI status line. Forward only when the visible percent bumps by >=10
   * (plus 100% so the user always sees completion).
   */
  fun makeInstallProgressLogger(tag: String): (Int, String) -> Unit {
    var lastEmittedPct = -1
    return { pct, message ->
      if (pct == 100 || pct >= lastEmittedPct + 10) {
        Console.log("[$tag] Chromium install: ${pct}% — $message")
        lastEmittedPct = pct
      }
    }
  }

  /**
   * Turns an absolute filesystem path into a `file://` URL with the autoplay query
   * parameter the WASM app reads at startup. We let `File.toURI().toASCIIString()` do
   * the percent-encoding (generated report paths in `logs/reports/` don't realistically
   * contain `?`, but the `contains("?")` guard keeps the URL well-formed for the edge
   * case where they do).
   */
  fun buildReportUrl(reportHtml: File): String {
    val base = reportHtml.toURI().toASCIIString()
    val separator = if (base.contains("?")) "&" else "?"
    return "$base${separator}autoplay=1"
  }

  /**
   * Convert measured frame count + elapsed wall time into a whole-number fps for ffmpeg.
   * `coerceAtLeast(1)` guards the degenerate "captured one frame in <1s" case.
   */
  private fun computeFps(frameCount: Int, elapsedMs: Long): Int {
    if (elapsedMs <= 0) return (1000 / FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)
    return (frameCount * 1000.0 / elapsedMs).toInt().coerceAtLeast(1)
  }
}
