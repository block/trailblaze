package xyz.block.trailblaze.cli

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.util.Console

/**
 * Renders a generated Trailblaze HTML report into an MP4 by loading it in a headless
 * Playwright browser, triggering timeline autoplay via the `?autoplay=1` URL parameter,
 * and capturing the resulting browser-tab playback via the same `setRecordVideoDir`
 * pipeline that powers `video.mp4` for live Playwright sessions (see
 * `PlaywrightVideoCapture` + `PlaywrightVideoRecordDir`).
 *
 * The exporter deliberately reuses [CaptureSession] for the WEB platform rather than
 * driving Playwright directly: that path already handles record-dir registration,
 * `BrowserContext` finalizer wiring, and WebM→MP4 transcoding with the right ffmpeg
 * flags — duplicating it here would just be a place for the two implementations to
 * drift. The result lands in the temp dir as `video.mp4`; we move it to the
 * caller-supplied [outputMp4].
 *
 * Wall-clock recording: the timeline's in-app `playbackSpeed` setting (default 2x in
 * the report UI) decides how fast the autoplay advances. The exporter just records
 * what the viewport shows in real time, so a 60s session at 2x autoplay lands as a
 * ~30s MP4. 4x was tried briefly but the Compose canvas couldn't paint screenshot
 * frames fast enough on the headless export path, producing torn output.
 *
 * No session log dir is created — the exporter doesn't go through `HostTrailblazeLoggingRule`
 * or `LogsRepo`. The only artifact is the MP4 at [outputMp4].
 */
object ReportVideoExporter {

  /** Upper bound on how long we'll wait for the timeline's playback-ended signal. */
  private const val MAX_PLAYBACK_WAIT_MS: Double = 10 * 60 * 1000.0

  /**
   * @param reportHtml The generated `trailblaze_report*.html` (single-session reports are
   *   already auto-advanced to the session detail view by the WASM app — multi-session
   *   reports land on the session list and won't trigger autoplay; pass a single-session
   *   report for now).
   * @param outputMp4 Destination path for the final MP4. Created (and parents created)
   *   as needed; overwrites if it already exists.
   * @param headless When true (default), the browser window is hidden. Set false locally
   *   to watch the playback happen in a real window — useful for debugging timing.
   */
  fun export(reportHtml: File, outputMp4: File, headless: Boolean = true) {
    require(reportHtml.exists() && reportHtml.isFile) {
      "Report HTML not found: ${reportHtml.absolutePath}"
    }
    outputMp4.parentFile?.mkdirs()
    if (outputMp4.exists()) outputMp4.delete()

    // Per-export device id keeps PlaywrightVideoRecordDir entries isolated even if
    // multiple exporters run concurrently (e.g. CI matrix exporting one MP4 per session).
    val deviceId = "report-export-${UUID.randomUUID().toString().take(8)}"
    val tempDir = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-report-video-$deviceId",
    ).apply { mkdirs() }

    // Sprite extraction would otherwise run during `captureSession.stopAll()` (two ffmpeg
    // passes — frame extraction + WebP assembly) but the caller asked for an MP4, not a
    // scrubber-friendly sheet, so we set `spriteFrameFps = 0` to short-circuit it. The
    // sprite path treats `fps <= 0` as a deliberate "skip" — see VideoSpriteExtractor.
    val captureSession = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, spriteFrameFps = 0),
      TrailblazeDevicePlatform.WEB,
    ) ?: error("CaptureSession.fromOptions returned null for WEB — capture wiring regressed")

    captureSession.startAll(tempDir, deviceId, appId = null)

    // First-time first-use heads-up. Playwright will print its own
    // "Chromium not found — installing (one-time, ~150 MB)..." line when the cache is
    // empty, but until that fires the CLI looks like it's hanging — surface the
    // possibility up front so the user knows what to expect on a fresh install.
    Console.log(
      "[ReportVideoExporter] launching headless Chromium for export " +
        "(one-time ~150MB download on first use; reuses the same browser cache " +
        "as other Trailblaze web commands)",
    )

    // Throttle install progress updates: ensureBrowserInstalled emits one callback per
    // line of `playwright install chromium` output, which is too chatty for a CLI status
    // line. Only forward updates that bump the visible percent by >=10, plus the very
    // first non-zero update so the user sees the download actually started.
    var lastEmittedPct = -1
    val onInstallProgress: (Int, String) -> Unit = { pct, message ->
      if (pct == 100 || pct >= lastEmittedPct + 10) {
        Console.log("[ReportVideoExporter] Chromium install: ${pct}% — $message")
        lastEmittedPct = pct
      }
    }

    // Single outer try/finally covers everything: a `navigate`/`waitForFunction` throw,
    // a crashed Playwright driver, or even an OOM during capture must still tear down
    // the manager, drain `captureSession.stopAll()`, and remove the temp dir — otherwise
    // a JVM that runs many exports (e.g. CI matrix) accumulates leaked
    // PlaywrightVideoRecordDir entries + tens of MB per failure on disk.
    var manager: PlaywrightBrowserManager? = null
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
        Console.log("[ReportVideoExporter] navigating to $url")
        page.navigate(url)
        // DOMCONTENTLOADED is enough — the WASM app boots on `window.onload`, but the
        // autoplay LaunchedEffect is gated on the timeline session-end timestamps being
        // populated, so the wait below for `__tbPlaybackEnded` is the real timing bound.
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)

        Console.log("[ReportVideoExporter] waiting for timeline playback to finish...")
        page.waitForFunction(
          "() => globalThis.__tbPlaybackEnded === true",
          null,
          Page.WaitForFunctionOptions().setTimeout(MAX_PLAYBACK_WAIT_MS),
        )
        Console.log("[ReportVideoExporter] timeline reported playback complete")
      }
    } finally {
      // Order matters: close the browser first (flushes the in-progress `.webm`), then
      // stop the capture stream (which looks for the `.webm` to transcode), then delete
      // the temp dir last. Each step is best-effort so one failure can't mask another.
      runCatching { manager?.close() }
      val artifacts = runCatching { captureSession.stopAll() }.getOrDefault(emptyList())
      val mp4 = artifacts.firstOrNull { it.file.name.endsWith(".mp4") }?.file
        ?: tempDir.listFiles { f -> f.name == "video.mp4" }?.firstOrNull()
      if (mp4 != null && mp4.exists()) {
        if (!mp4.renameTo(outputMp4)) {
          // Cross-filesystem rename can fail; fall back to copy+delete.
          runCatching { mp4.copyTo(outputMp4, overwrite = true) }
          runCatching { mp4.delete() }
        }
        Console.log(
          "[ReportVideoExporter] wrote ${outputMp4.absolutePath} " +
            "(${outputMp4.length() / 1024}KB)",
        )
      } else {
        Console.log(
          "[ReportVideoExporter] no MP4 was produced — files in tempDir: " +
            (tempDir.list()?.toList() ?: emptyList<String>()),
        )
      }
      runCatching { tempDir.deleteRecursively() }
    }
  }

  /**
   * Turns an absolute filesystem path into a `file://` URL with the autoplay query
   * parameter the WASM app reads at startup. We let `File.toURI().toASCIIString()` do
   * the actual percent-encoding (generated report paths in `logs/reports/` don't
   * realistically contain `?`, but the `if (base.contains("?"))` guard keeps the URL
   * well-formed for the edge case where they do).
   */
  private fun buildReportUrl(reportHtml: File): String {
    val base = reportHtml.toURI().toASCIIString()
    val separator = if (base.contains("?")) "&" else "?"
    return "$base${separator}autoplay=1"
  }
}
