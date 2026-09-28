package xyz.block.trailblaze.cli

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Renders a generated Trailblaze HTML report into an animated GIF from the frames
 * [PlaywrightReportCapture] steps out of the report's timeline (`?autoplay=step`), each
 * held for its own duration.
 *
 * Compared to [ReportVideoExporter], this path skips the whole video-capture pipeline
 * (no `setRecordVideoDir`, no WebM→MP4 ffmpeg pass) — we ask Playwright for PNGs
 * directly and hand them to a single ffmpeg invocation that assembles the GIF with a
 * generated palette. GIFs are much smaller and easier to drop into PR descriptions,
 * Slack, etc., at the cost of a lower frame rate and 256-color palette.
 *
 * The frame-capture loop itself lives in [PlaywrightReportCapture] — see that class for
 * the frame timing + playback-end-detection logic shared with [ReportWebpExporter]. The only
 * production caller is the orchestrator in `ReportCommand.kt`, which runs a single shared
 * capture and then calls [encode] on each requested exporter.
 */
object ReportGifExporter {

  /**
   * Encode an animated GIF from frames already captured by [PlaywrightReportCapture].
   * The orchestrator in `ReportCommand.kt` drives capture once and calls this on each
   * requested exporter — see the `--gif`/`--webp` shared-capture wiring there.
   *
   * @param framesDir Directory containing `frame_NNNNN.png` files written by
   *   [PlaywrightReportCapture.captureFrames].
   * @param capture The [PlaywrightReportCapture.CaptureResult] from that same call,
   *   needed for each frame's duration.
   * @param outputGif Destination path for the final GIF. Parents are created if needed
   *   and an existing file at the path is overwritten.
   * @param maxBytes When non-null, iteratively re-assemble at smaller widths until the
   *   output fits under the cap. See [MaxArtifactSize]. If even the readability floor
   *   can't satisfy the cap, this throws — callers should surface the message verbatim.
   * @param maxBytesStrict Whether floor exhaustion is fatal. `true` (the default) is the
   *   behavior an explicitly-requested cap gets; the CLI passes `false` when the cap is
   *   only [MaxArtifactSize.DEFAULT_MAX_BYTES], which warns and keeps the oversized GIF.
   */
  internal fun encode(
    framesDir: File,
    capture: PlaywrightReportCapture.CaptureResult,
    outputGif: File,
    maxBytes: Long? = null,
    maxBytesStrict: Boolean = true,
  ) {
    outputGif.parentFile?.mkdirs()
    if (outputGif.exists()) outputGif.delete()

    assembleGif(framesDir, outputGif, capture.frameDurationsMs, targetWidthPx = null)
    Console.log(
      "[ReportGifExporter] wrote ${outputGif.absolutePath} " +
        "(${outputGif.length() / 1024}KB, ${capture.frameCount} frames)",
    )

    if (maxBytes != null) {
      // The frames on disk are the canonical source — every iteration re-runs
      // palettegen/paletteuse with a leading scale filter, which is much cheaper than
      // re-capturing. Timing doesn't drift across iterations because the frames and their
      // durations are fixed.
      val rescaleStartMs = System.currentTimeMillis()
      val result = MaxArtifactSize.enforce(outputGif, maxBytes) { w ->
        Console.log("[ReportGifExporter] over ${maxBytes}B — re-assembling at ${w}px width")
        assembleGif(framesDir, outputGif, capture.frameDurationsMs, targetWidthPx = w)
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
        MaxArtifactSize.failOrWarn(
          strict = maxBytesStrict,
          artifact = outputGif,
          formatLabel = "GIF",
          maxBytes = maxBytes,
          remedies = "GIF's per-frame 256-color palette is the least space-efficient of " +
            "the three formats. Switch to --webp (typically 25–50% smaller at the same " +
            "width) or --video (libx264 compresses dramatically better), or shorten the " +
            "session — split the trail into smaller recordings, or remove intermediate " +
            "verification steps.",
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
   * Frames are read through ffmpeg's concat demuxer ([concatList]) rather than an image
   * pattern at a fixed rate, because each one is held for its own duration. GIF delays are
   * in centiseconds, so each lands within 10ms of the capture's; the muxer's `final_delay`
   * carries the last one, which concat has no following frame to measure against. No frame-rate
   * mode flag is passed: the GIF muxer is variable-rate by default, and `-fps_mode` (5.1+) and
   * `-vsync` (removed in 9) each break on one side of the ffmpeg versions users have.
   *
   * @param frameDurationsMs How long to hold each `frame_NNNNN.png`, in order.
   * @param targetWidthPx When non-null, prepend a `scale=W:-1` filter so the GIF is
   *   downsampled to that pixel width (height auto-computed to preserve aspect ratio).
   *   The palette is then generated from the *scaled* frames, which is the right place
   *   to compute it.
   */
  internal fun assembleGif(framesDir: File, outputGif: File, frameDurationsMs: List<Int>, targetWidthPx: Int?) {
    require(frameDurationsMs.isNotEmpty()) { "no frames to assemble a GIF from" }
    val frames = frameDurationsMs.indices.map { File(framesDir, "frame_%05d.png".format(it)) }
    frames.firstOrNull { !it.isFile }?.let { error("missing captured frame ${it.absolutePath}") }
    // GIF89a accepts any pixel dimensions, so we use LANCZOS_AUTO (no even-rounding).
    val scalePrefix = FfmpegRescaleSupport
      .scaleFilter(targetWidthPx, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO)
      ?.let { "$it," } ?: ""
    val filter = "${scalePrefix}split[s0][s1];" +
      "[s0]palettegen=stats_mode=diff[p];" +
      "[s1][p]paletteuse=dither=bayer:bayer_scale=5"
    val widthCtx = targetWidthPx?.let { " at ${it}px" } ?: ""
    val listFile = File.createTempFile("trailblaze-gif-frames-", ".ffconcat").apply { deleteOnExit() }
    try {
      listFile.writeText(concatList(frames, frameDurationsMs))
      FfmpegRescaleSupport.runFfmpegToTemp(
        tag = "ReportGifExporter",
        dest = outputGif,
        tempSuffix = ".gif",
        errorContext = "gif assembly$widthCtx",
      ) { tempFile ->
        listOf(
          "ffmpeg",
          "-y",
          "-f",
          "concat",
          "-safe",
          "0",
          "-i",
          listFile.absolutePath,
          "-vf",
          filter,
          "-loop",
          "0",
          "-final_delay",
          ((frameDurationsMs.last() + 5) / 10).coerceAtLeast(1).toString(),
          "-f",
          "gif",
          tempFile.absolutePath,
        )
      }
    } finally {
      runCatching { listFile.delete() }
    }
  }

  /**
   * An ffconcat script holding each of [frames] for its entry in [frameDurationsMs]. Paths are
   * absolute and single-quoted (a `'` inside one is closed, escaped and reopened, as ffmpeg's
   * quoting reads it), so a temp dir with spaces or quotes in its name still resolves.
   *
   * Each file is opened at 100fps, GIF's own centisecond resolution. Without it ffmpeg reads a
   * still image on a 25fps clock and every frame boundary snaps to 40ms. The `option` directive
   * needs ffmpeg 5.0 or newer.
   */
  internal fun concatList(frames: List<File>, frameDurationsMs: List<Int>): String {
    require(frames.size == frameDurationsMs.size) {
      "${frames.size} frames but ${frameDurationsMs.size} durations"
    }
    return buildString {
      appendLine("ffconcat version 1.0")
      frames.zip(frameDurationsMs).forEach { (frame, ms) ->
        appendLine("file '${frame.absolutePath.replace("'", "'\\''")}'")
        appendLine("option framerate 100")
        appendLine("duration ${"%.3f".format(java.util.Locale.ROOT, ms / 1000.0)}")
      }
    }
  }
}
