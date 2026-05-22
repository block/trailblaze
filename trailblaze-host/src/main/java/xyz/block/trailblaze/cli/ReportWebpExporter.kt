package xyz.block.trailblaze.cli

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Renders a generated Trailblaze HTML report into an animated WebP — the third format
 * alongside [ReportGifExporter] and [ReportVideoExporter]. Frame capture is identical to
 * the GIF path (shared via [PlaywrightReportCapture]); only the final ffmpeg encoder
 * differs.
 *
 * Why WebP? Modern animated WebP typically lands 25–50% smaller than the equivalent GIF
 * (24-bit color vs GIF's 256-color palette, sophisticated inter-frame deltas vs LZW
 * per-frame). GitHub renders animated WebP inline in PR comments via the same `<img>`
 * path as GIF, so reviewer UX is identical — the win is fitting under the 10 MB
 * inline-attachment limit without scaling the viewport down.
 *
 * **ffmpeg quirks to know about.** ffmpeg's animated-WebP encoder is less battle-tested
 * than its GIF encoder; the incantation below is the one that produced a valid file
 * across ffmpeg 5.x / 6.x in initial testing:
 *   * `-c:v libwebp_anim` — selects the multi-frame WebP muxer (vs `libwebp` which can
 *     also emit animated WebP but defaults to single-frame in many builds).
 *   * `-vsync 0` (a.k.a. `passthrough`) — without this the encoder re-bases frame
 *     timestamps against its own clock and the playback speed drifts.
 *   * `-loop 0` — infinite loop (matches the GIF default).
 *   * `-q:v 75` + `-compression_level 6` — middle of the road for the report UI's mix of
 *     anti-aliased text and screenshot thumbnails; bump `-q:v` (lower = smaller, lossier;
 *     higher = larger, sharper) if you find text getting mushy.
 *
 * The only production caller is the orchestrator in `ReportCommand.kt`, which runs a
 * single shared capture and then calls [encode] on each requested exporter — mirroring
 * the structure of [ReportGifExporter].
 */
object ReportWebpExporter {

  /**
   * Encode an animated WebP from frames already captured by [PlaywrightReportCapture].
   * The orchestrator in `ReportCommand.kt` drives capture once and calls this on each
   * requested exporter — see the `--gif`/`--webp` shared-capture wiring there. The
   * orchestrator is responsible for calling [requireLibwebpAnim] before driving the
   * capture loop so we fail fast on a missing encoder instead of after 30s of capture.
   *
   * @param framesDir Directory containing `frame_NNNNN.png` files written by
   *   [PlaywrightReportCapture.captureFrames].
   * @param capture The [PlaywrightReportCapture.CaptureResult] from that same call,
   *   needed for the measured-fps value.
   * @param outputWebp Destination path for the final WebP. Parents are created if
   *   needed and an existing file at the path is overwritten.
   * @param maxBytes When non-null, iteratively re-assemble at smaller widths until the
   *   output fits under the cap. See [MaxArtifactSize]. If even the readability floor
   *   can't satisfy the cap, this throws — callers should surface the message verbatim.
   */
  internal fun encode(
    framesDir: File,
    capture: PlaywrightReportCapture.CaptureResult,
    outputWebp: File,
    maxBytes: Long? = null,
  ) {
    outputWebp.parentFile?.mkdirs()
    if (outputWebp.exists()) outputWebp.delete()

    assembleWebp(framesDir, outputWebp, capture.measuredFps, targetWidthPx = null)
    Console.log(
      "[ReportWebpExporter] wrote ${outputWebp.absolutePath} " +
        "(${outputWebp.length() / 1024}KB, ${capture.measuredFps}fps)",
    )

    if (maxBytes != null) {
      // Same scale-down loop the GIF exporter uses, with libwebp_anim re-encoded
      // against the captured PNG frames each iteration. The use-case that motivates
      // `--max-size` on --webp specifically is GitHub's 10MB inline-attachment cap —
      // see the validate-migration skill, which posts an animated WebP per case row.
      val rescaleStartMs = System.currentTimeMillis()
      val result = MaxArtifactSize.enforce(outputWebp, maxBytes) { w ->
        Console.log("[ReportWebpExporter] over ${maxBytes}B — re-assembling at ${w}px width")
        assembleWebp(framesDir, outputWebp, capture.measuredFps, targetWidthPx = w)
        Console.log(
          "[ReportWebpExporter] after ${w}px: ${outputWebp.length() / 1024}KB " +
            "(cap: ${maxBytes / 1024}KB)",
        )
      }
      val rescaleElapsedMs = System.currentTimeMillis() - rescaleStartMs
      if (!result.fits) {
        error(
          "WebP still exceeds ${maxBytes}B at the ${MaxArtifactSize.READABILITY_FLOOR_PX}px " +
            "readability floor (current size: ${outputWebp.length()}B). WebP is typically " +
            "the most space-efficient of the three formats, so this usually means the " +
            "session is unusually long. Switch to --video (libx264 absorbs duration " +
            "better, especially at a higher CRF), or shorten the session — split the " +
            "trail into smaller recordings.",
        )
      }
      if (result.widthPx != null) {
        Console.log(
          "[ReportWebpExporter] final size ${outputWebp.length() / 1024}KB at ${result.widthPx}px " +
            "(rescale took ${rescaleElapsedMs}ms)",
        )
      }
    }
  }

  /**
   * Probe `ffmpeg -encoders` for `libwebp_anim` and throw a clear, actionable error if
   * absent (or if ffmpeg itself isn't on PATH). Run this BEFORE the frame-capture loop so
   * a misconfigured environment fails in ~100ms instead of after 30s of headless
   * screenshotting + a generic non-zero exit from the encode call. Internal so the
   * orchestrator in `ReportCommand.kt` can preflight when both --gif and --webp are
   * requested under one shared capture.
   */
  internal fun requireLibwebpAnim() {
    FfmpegRescaleSupport.requireEncoder(
      encoderName = "libwebp_anim",
      missingHint = "Required for --webp export. Install ffmpeg with libwebp_anim " +
        "support — typically `brew install ffmpeg` on macOS, `apt-get install ffmpeg` " +
        "on Debian/Ubuntu, or `apk add ffmpeg` on Alpine. If libwebp_anim is " +
        "unavailable on this system and you only need a GIF, invoke as " +
        "`--gif --no-webp` to skip the shared-capture WebP companion entirely.",
    )
  }

  /**
   * Single-pass ffmpeg invocation: PNG sequence → animated WebP. Marked `internal` so
   * the smoke test can drive it directly without going through Playwright. Atomic
   * temp-file-then-rename + cleanup is handled by [FfmpegRescaleSupport.runFfmpegToTemp]
   * — same crash-safety pattern the GIF and MP4 exporters use.
   *
   * @param targetWidthPx When non-null, add a `scale=W:-2:flags=lanczos` filter so the
   *   WebP is downsampled to that pixel width. Height uses `-2` (round to even) rather
   *   than `-1`: libwebp_anim itself accepts odd dimensions, but some downstream WebP
   *   decoders / viewers do not, and the even-dimension constraint matches what we use
   *   on the MP4 path so behavior is consistent across formats.
   */
  internal fun assembleWebp(framesDir: File, outputWebp: File, fps: Int, targetWidthPx: Int?) {
    // libwebp_anim itself accepts odd dimensions, but some downstream WebP decoders do
    // not — LANCZOS_EVEN matches what libx264 needs on the MP4 path so behavior stays
    // consistent across formats.
    val scaleArgs = FfmpegRescaleSupport
      .scaleFilter(targetWidthPx, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN)
      ?.let { listOf("-vf", it) } ?: emptyList()
    val widthCtx = targetWidthPx?.let { " at ${it}px" } ?: ""
    FfmpegRescaleSupport.runFfmpegToTemp(
      tag = "ReportWebpExporter",
      dest = outputWebp,
      tempSuffix = ".webp",
      errorContext = "webp assembly$widthCtx",
    ) { tempFile ->
      buildList {
        add("ffmpeg")
        add("-y")
        add("-framerate")
        add(fps.toString())
        add("-i")
        add(File(framesDir, "frame_%05d.png").absolutePath)
        addAll(scaleArgs)
        add("-c:v")
        add("libwebp_anim")
        add("-lossless")
        add("0")
        add("-compression_level")
        add("6")
        add("-q:v")
        add("75")
        add("-preset")
        add("picture")
        add("-loop")
        add("0")
        add("-an")
        add("-vsync")
        add("0")
        // Explicit muxer so the temp filename's extension is the only thing ffmpeg
        // needs to dispatch on — and `FfmpegRescaleSupport` already controls it (`.webp`).
        add("-f")
        add("webp")
        add(tempFile.absolutePath)
      }
    }
  }
}
