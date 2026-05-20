package xyz.block.trailblaze.cli

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Renders a generated Trailblaze HTML report into an animated GIF by loading it in a
 * headless Playwright browser, triggering timeline autoplay via the `?autoplay=1` URL
 * parameter, and screenshotting the viewport at a fixed interval until the timeline's
 * `__tbPlaybackEnded` signal fires.
 *
 * Compared to [ReportVideoExporter], this path skips the whole video-capture pipeline
 * (no `setRecordVideoDir`, no WebM→MP4 ffmpeg pass) — we ask Playwright for PNGs
 * directly and hand them to a single ffmpeg invocation that assembles the GIF with a
 * generated palette. GIFs are much smaller and easier to drop into PR descriptions,
 * Slack, etc., at the cost of a lower frame rate and 256-color palette.
 *
 * The frame-capture loop itself lives in [PlaywrightReportCapture] — see that class for
 * the cadence + playback-end-detection logic shared with [ReportWebpExporter]. The only
 * production caller is the orchestrator in `ReportCommand.kt`, which runs a single shared
 * capture and then calls [encode] on each requested exporter.
 */
object ReportGifExporter {

  /**
   * Encode an animated GIF from frames already captured by [PlaywrightReportCapture].
   * The orchestrator in `ReportCommand.kt` drives capture once and calls this on each
   * requested exporter — see the `--gif`/`--webp` shared-capture wiring there. Encodes
   * with the *measured* cadence rather than the nominal FRAME_INTERVAL_MS so any
   * per-frame slip (slow screenshots, GC pauses) lands as accurate playback speed
   * rather than a sped-up output.
   *
   * @param framesDir Directory containing `frame_NNNNN.png` files written by
   *   [PlaywrightReportCapture.captureFrames].
   * @param capture The [PlaywrightReportCapture.CaptureResult] from that same call,
   *   needed for the measured-fps value.
   * @param outputGif Destination path for the final GIF. Parents are created if needed
   *   and an existing file at the path is overwritten.
   * @param maxBytes When non-null, iteratively re-assemble at smaller widths until the
   *   output fits under the cap. See [MaxArtifactSize]. If even the readability floor
   *   can't satisfy the cap, this throws — callers should surface the message verbatim.
   */
  internal fun encode(
    framesDir: File,
    capture: PlaywrightReportCapture.CaptureResult,
    outputGif: File,
    maxBytes: Long? = null,
  ) {
    outputGif.parentFile?.mkdirs()
    if (outputGif.exists()) outputGif.delete()

    assembleGif(framesDir, outputGif, capture.measuredFps, targetWidthPx = null)
    Console.log(
      "[ReportGifExporter] wrote ${outputGif.absolutePath} " +
        "(${outputGif.length() / 1024}KB, ${capture.measuredFps}fps)",
    )

    if (maxBytes != null) {
      // The frames on disk are the canonical source — every iteration re-runs
      // palettegen/paletteuse with a leading scale filter, which is much cheaper than
      // re-capturing. FPS doesn't drift across iterations because the captured-frame
      // count and measured cadence are fixed.
      val rescaleStartMs = System.currentTimeMillis()
      val result = MaxArtifactSize.enforce(outputGif, maxBytes) { w ->
        Console.log("[ReportGifExporter] over ${maxBytes}B — re-assembling at ${w}px width")
        assembleGif(framesDir, outputGif, capture.measuredFps, targetWidthPx = w)
        Console.log(
          "[ReportGifExporter] after ${w}px: ${outputGif.length() / 1024}KB " +
            "(cap: ${maxBytes / 1024}KB)",
        )
      }
      val rescaleElapsedMs = System.currentTimeMillis() - rescaleStartMs
      if (!result.fits) {
        // Note: we deliberately don't suggest lowering the autoplay speed here — a
        // slower playback makes the *wall-clock* longer, which produces a LARGER GIF,
        // not smaller. The actionable levers are recording length and codec.
        error(
          "GIF still exceeds ${maxBytes}B at the ${MaxArtifactSize.READABILITY_FLOOR_PX}px " +
            "readability floor (current size: ${outputGif.length()}B). GIF's per-frame " +
            "256-color palette is the least space-efficient of the three formats. Switch " +
            "to --webp (typically 25–50% smaller at the same width) or --video (libx264 " +
            "compresses dramatically better), or shorten the session — split the trail " +
            "into smaller recordings, or remove intermediate verification steps.",
        )
      }
      if (result.widthPx != null) {
        Console.log(
          "[ReportGifExporter] final size ${outputGif.length() / 1024}KB at ${result.widthPx}px " +
            "(rescale took ${rescaleElapsedMs}ms)",
        )
      }
    }
  }

  /**
   * Two-pass ffmpeg via the `split→palettegen→paletteuse` filter graph: building a
   * palette from the actual frames produces dramatically better color fidelity than
   * GIF89a's default 216-color web palette, especially for the report UI's anti-aliased
   * text and screenshot thumbnails.
   *
   * `-f gif` is passed explicitly so the muxer doesn't depend on `outputGif`'s filename
   * — callers may pass an extension-less path (`--gif out`) and we still need to write a
   * valid GIF89a stream. Atomic temp-file-then-rename + cleanup is handled by
   * [FfmpegRescaleSupport.runFfmpegToTemp].
   *
   * @param targetWidthPx When non-null, prepend a `scale=W:-1` filter so the GIF is
   *   downsampled to that pixel width (height auto-computed to preserve aspect ratio).
   *   The palette is then generated from the *scaled* frames, which is the right place
   *   to compute it.
   */
  internal fun assembleGif(framesDir: File, outputGif: File, fps: Int, targetWidthPx: Int?) {
    // GIF89a accepts any pixel dimensions, so we use LANCZOS_AUTO (no even-rounding).
    val scalePrefix = FfmpegRescaleSupport
      .scaleFilter(targetWidthPx, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO)
      ?.let { "$it," } ?: ""
    val filter = "${scalePrefix}split[s0][s1];" +
      "[s0]palettegen=stats_mode=diff[p];" +
      "[s1][p]paletteuse=dither=bayer:bayer_scale=5"
    val widthCtx = targetWidthPx?.let { " at ${it}px" } ?: ""
    FfmpegRescaleSupport.runFfmpegToTemp(
      tag = "ReportGifExporter",
      dest = outputGif,
      tempSuffix = ".gif",
      errorContext = "gif assembly$widthCtx",
    ) { tempFile ->
      listOf(
        "ffmpeg",
        "-y",
        "-framerate",
        fps.toString(),
        "-i",
        File(framesDir, "frame_%05d.png").absolutePath,
        "-vf",
        filter,
        "-loop",
        "0",
        "-f",
        "gif",
        tempFile.absolutePath,
      )
    }
  }
}
