package xyz.block.trailblaze.cli

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
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
 * Wall-clock recording: the timeline's in-app `playbackSpeed` setting (default 4x in
 * the report UI) decides how fast the autoplay advances. The exporter just records
 * what the viewport shows in real time, so a 60s session at 4x autoplay lands as a
 * ~15s MP4. 4x can occasionally produce a torn frame when the Compose canvas hasn't
 * finished painting before the scrubber moves on; users who care about export fidelity
 * over length can drop to 2x via the timeline speed picker before re-running.
 *
 * No session log dir is created — the exporter doesn't go through `HostTrailblazeLoggingRule`
 * or `LogsRepo`. The only artifact is the MP4 at [outputMp4].
 */
object ReportVideoExporter {

  /**
   * @param reportHtml The generated `trailblaze_report*.html` (single-session reports are
   *   already auto-advanced to the session detail view by the WASM app — multi-session
   *   reports land on the session list and won't trigger autoplay; pass a single-session
   *   report for now).
   * @param outputMp4 Destination path for the final MP4. Created (and parents created)
   *   as needed; overwrites if it already exists.
   * @param headless When true (default), the browser window is hidden. Set false locally
   *   to watch the playback happen in a real window — useful for debugging timing.
   * @param maxBytes When non-null, the MP4 is iteratively re-encoded at smaller widths
   *   until it fits under the cap. See [MaxArtifactSize]. If even the readability floor
   *   can't satisfy the cap, this throws.
   */
  fun export(reportHtml: File, outputMp4: File, headless: Boolean = true, maxBytes: Long? = null) {
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

    val onInstallProgress = PlaywrightReportCapture.makeInstallProgressLogger("ReportVideoExporter")

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
        val url = PlaywrightReportCapture.buildReportUrl(reportHtml)
        Console.log("[ReportVideoExporter] navigating to $url")
        page.navigate(url)
        // DOMCONTENTLOADED is enough — the WASM app boots on `window.onload`, but the
        // autoplay LaunchedEffect is gated on the timeline session-end timestamps being
        // populated, so the wait below for `__tbPlaybackEnded` is the real timing bound.
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)

        Console.log("[ReportVideoExporter] waiting for timeline playback to finish...")
        // Honors the same MAX_PLAYBACK_WAIT_MS override as the GIF/WebP path (https://github.com/block/trailblaze/issues/173).
        val waitMs = PlaywrightReportCapture.maxPlaybackWaitMs
        try {
          page.waitForFunction(
            "() => globalThis.__tbPlaybackEnded === true",
            null,
            Page.WaitForFunctionOptions().setTimeout(waitMs.toDouble()),
          )
          Console.log("[ReportVideoExporter] timeline reported playback complete")
        } catch (_: TimeoutError) {
          // Fail soft: the WebM has been recording continuously, so let the finally block
          // finalize a best-effort truncated MP4 rather than aborting with no output.
          Console.log(
            "[ReportVideoExporter] WARNING: timeline playback did not signal completion " +
              "within ${waitMs / 1000}s — finalizing a truncated MP4. Set " +
              "${PlaywrightReportCapture.MAX_PLAYBACK_WAIT_ENV} (ms) higher to capture the " +
              "full timeline.",
          )
        }
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

    if (maxBytes != null && outputMp4.exists()) {
      // Re-encode the full-res MP4 down to successive widths until the file fits.
      //
      // We rescale from a copy of the *original* full-res encode each iteration rather
      // than from the previous rescale's output — re-encoding an already-h.264 stream
      // three times to walk 1280→1024→720 compounds compression artifacts (generational
      // quality loss). One copy upfront, one libx264 pass per ladder rung from a stable
      // source.
      //
      // The source copy lives in the system temp dir (not the user's chosen output dir)
      // so it's invisible to them and gets cleaned up on JVM exit even if we crash
      // mid-loop.
      val rescaleSource = File(
        System.getProperty("java.io.tmpdir"),
        "trailblaze-report-video-source-${UUID.randomUUID().toString().take(8)}.mp4",
      )
      // SIGKILL safety: registered before any I/O so a JVM crash (CI timeout, OOM
      // killer) doesn't leak a full-res MP4 into the system temp dir. Idempotent
      // with the explicit delete in the finally below.
      rescaleSource.deleteOnExit()
      try {
        outputMp4.copyTo(rescaleSource, overwrite = true)
        val rescaleStartMs = System.currentTimeMillis()
        val result = MaxArtifactSize.enforce(outputMp4, maxBytes) { w ->
          Console.log("[ReportVideoExporter] over ${maxBytes}B — re-encoding at ${w}px width")
          rescaleMp4(source = rescaleSource, dest = outputMp4, targetWidthPx = w)
          Console.log(
            "[ReportVideoExporter] after ${w}px: ${outputMp4.length() / 1024}KB " +
              "(cap: ${maxBytes / 1024}KB)",
          )
        }
        val rescaleElapsedMs = System.currentTimeMillis() - rescaleStartMs
        if (!result.fits) {
          error(
            "MP4 still exceeds ${maxBytes}B at the ${MaxArtifactSize.READABILITY_FLOOR_PX}px " +
              "readability floor (current size: ${outputMp4.length()}B). libx264 already " +
              "compresses well, so this usually means the session is just long. Shorten the " +
              "session — split the trail into smaller recordings, or remove intermediate " +
              "verification steps.",
          )
        }
        if (result.widthPx != null) {
          // Each rescale rung re-encodes from the sidecar source with libx264 -preset
          // veryfast. On a full-ladder walk on a long session that's still seconds per
          // rung; total wall-clock helps distinguish "rescale was free" from "rescale
          // dominated the export" when reading worker logs.
          Console.log(
            "[ReportVideoExporter] final size ${outputMp4.length() / 1024}KB at ${result.widthPx}px " +
              "(rescale took ${rescaleElapsedMs}ms)",
          )
        }
      } finally {
        runCatching { if (rescaleSource.exists()) rescaleSource.delete() }
      }
    }
  }

  /**
   * Transcode [source] at [targetWidthPx] (height auto-computed via `-2` so libx264's
   * even-dimension requirement is satisfied) and atomically replace [dest] with the
   * result. Atomic temp-file-then-rename + cleanup is handled by
   * [FfmpegRescaleSupport.runFfmpegToTemp] — same crash-safety pattern the GIF and
   * WebP exporters use.
   *
   * Callers may pass an extension-less `dest` path (`--video out`); the helper writes
   * to a `.mp4`-suffixed temp file regardless so ffmpeg's muxer detection always works.
   */
  private fun rescaleMp4(source: File, dest: File, targetWidthPx: Int) {
    // libx264 requires even dimensions — LANCZOS_EVEN rounds height down to the
    // nearest even pixel so the encoder never rejects the frame at run time.
    // `scaleFilter` only returns null for a null width; this caller passes a non-null
    // `Int` so the result is always non-null here.
    val scale = FfmpegRescaleSupport
      .scaleFilter(targetWidthPx, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN)!!
    FfmpegRescaleSupport.runFfmpegToTemp(
      tag = "ReportVideoExporter",
      dest = dest,
      tempSuffix = ".mp4",
      errorContext = "mp4 rescale to ${targetWidthPx}px",
    ) { tempFile ->
      listOf(
        "ffmpeg",
        "-y",
        "-i",
        source.absolutePath,
        "-vf",
        scale,
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        "-an",
        tempFile.absolutePath,
      )
    }
  }
}
