package xyz.block.trailblaze.cli

import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import xyz.block.trailblaze.util.Console

/**
 * Renders a generated Trailblaze HTML report into an animated WebP — the third format
 * alongside [ReportGifExporter] and [ReportVideoExporter]. Frame capture is identical to
 * the GIF path (shared via [PlaywrightReportCapture]); only the final encode differs.
 *
 * Why WebP? Modern animated WebP typically lands 25–50% smaller than the equivalent GIF
 * (24-bit color vs GIF's 256-color palette, sophisticated inter-frame deltas vs LZW
 * per-frame). GitHub renders animated WebP inline in PR comments via the same `<img>`
 * path as GIF, so reviewer UX is identical — the win is fitting under the 10 MB
 * inline-attachment limit without scaling the viewport down.
 *
 * **Toolchain: libwebp, not ffmpeg.** homebrew-core's plain `ffmpeg` is not built against
 * libwebp, so it cannot encode animated WebP. Rather than pull in the much heavier
 * `ffmpeg-full`, this path uses libwebp's own CLIs (the `webp` package — `brew install
 * webp`, `apt-get install webp`, `apk add libwebp-tools`):
 *   * **common path** — a single `img2webp` call assembles the captured PNG frames into an
 *     animated WebP. NOTE: img2webp defaults to *lossless*; we pass `-lossy -q 75 -m 6` to
 *     suit the report UI's mix of anti-aliased text and screenshot thumbnails (raise `-q`
 *     if text gets mushy; lower = smaller/lossier).
 *   * **`--max-size` path** — img2webp has no resize knob, so each frame is downscaled and
 *     encoded with `cwebp` (`-resize W 0`, height auto from aspect; cwebp is lossy by
 *     default), then `webpmux` muxes the resized frames into one animation. cwebp's own
 *     rescaler does the scaling rather than asking ffmpeg to scale stills.
 *
 * Frame timing is uniform: every frame is held for `1000 / measuredFps` ms (`-d` for
 * img2webp, the `+<ms>` frame-duration suffix for webpmux) and `-loop 0` loops forever,
 * matching the GIF default.
 *
 * The only production caller is the orchestrator in `ReportCommand.kt`, which runs a
 * single shared capture and then calls [encode] on each requested exporter — mirroring
 * the structure of [ReportGifExporter].
 */
object ReportWebpExporter {

  // Lossy quality + compression method, shared by img2webp (common path) and cwebp
  // (per-frame, --max-size path) so both paths produce comparable output.
  private const val QUALITY = "75"
  private const val METHOD = "6"

  // Every libwebp CLI the two encode paths shell out to. img2webp drives the common path;
  // cwebp + webpmux drive the --max-size rescale path. All three ship in the same `webp`
  // package, but a partial/broken install can have one without the others, so the
  // preflight probes each rather than trusting img2webp as a proxy.
  private val WEBP_TOOLS = listOf("img2webp", "cwebp", "webpmux")

  /** Matches the `frame_NNNNN.png` files [PlaywrightReportCapture] writes, capturing the index. */
  private val FRAME_PNG = Regex("""frame_(\d+)\.png""")

  /**
   * Encode an animated WebP from frames already captured by [PlaywrightReportCapture].
   * The orchestrator in `ReportCommand.kt` drives capture once and calls this on each
   * requested exporter — see the `--gif`/`--webp` shared-capture wiring there. The
   * orchestrator is responsible for calling [requireWebpTools] before driving the capture
   * loop so we fail fast on a missing tool instead of after 30s of capture.
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
      // Same scale-down loop the GIF exporter uses, re-encoded against the captured PNG
      // frames each iteration. The use-case that motivates `--max-size` on --webp
      // specifically is GitHub's 10MB inline-attachment cap — see the validate-migration
      // skill, which posts an animated WebP per case row.
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
   * Probe that every libwebp CLI the WebP paths use ([WEBP_TOOLS]) is on PATH and runnable,
   * and throw a clear, actionable error naming any that aren't. Run this BEFORE the
   * frame-capture loop so a misconfigured environment fails in ~100ms instead of after 30s
   * of headless screenshotting — and probe all three (not just `img2webp`) so a partial
   * install doesn't pass preflight only for the `--max-size` path's `cwebp`/`webpmux` to
   * fail later. Internal so the orchestrator in `ReportCommand.kt` can preflight when both
   * --gif and --webp are requested under one shared capture.
   */
  internal fun requireWebpTools(probe: (String) -> Boolean = ::binaryOnPath) {
    val missing = WEBP_TOOLS.filterNot(probe)
    check(missing.isEmpty()) { missingWebpToolsMessage(missing) }
  }

  /** Actionable error naming the missing libwebp tool(s). Pure, so the message is unit-tested. */
  internal fun missingWebpToolsMessage(missing: List<String>): String =
    "the libwebp tool(s) ${missing.joinToString(", ")} required for --webp export were " +
      "not found (or not runnable) on PATH. Install libwebp's tools — typically " +
      "`brew install webp` on macOS, `apt-get install webp` on Debian/Ubuntu, or " +
      "`apk add libwebp-tools` on Alpine. If you only need a GIF, invoke as " +
      "`--gif --no-webp` to skip the WebP companion entirely."

  /**
   * Assemble an animated WebP from the captured PNG frames. Marked `internal` so the smoke
   * test can drive it directly without going through Playwright.
   *
   * @param targetWidthPx When non-null, each frame is downscaled to that pixel width
   *   (height auto from aspect) via `cwebp -resize` and the result muxed with `webpmux`;
   *   when null, the native-resolution frames are assembled in a single `img2webp` call.
   */
  internal fun assembleWebp(framesDir: File, outputWebp: File, fps: Int, targetWidthPx: Int?) {
    val frames = framePngs(framesDir)
    check(frames.isNotEmpty()) {
      "no frame_NNNNN.png files found in ${framesDir.absolutePath} to assemble a WebP from"
    }
    val durationMs = frameDurationMs(fps)
    if (targetWidthPx == null) {
      runToTemp(outputWebp, "img2webp assembly") { temp -> img2webpArgs(frames, temp, durationMs) }
    } else {
      assembleScaledWebp(frames, outputWebp, durationMs, targetWidthPx)
    }
  }

  /**
   * `--max-size` path: `cwebp -resize` each captured frame into an intermediate WebP, then
   * `webpmux` assembles them into the final animation. The intermediates live in a
   * throwaway temp dir that's always cleaned up, regardless of how the assembly exits.
   *
   * Cost note: unlike the GIF/video paths (one ffmpeg invocation that scales the whole
   * sequence in-process), this spawns one `cwebp` per frame — and `MaxArtifactSize.enforce`
   * may call it at several ladder widths — so a multi-thousand-frame report under a tight
   * `--max-size` is markedly more subprocess-heavy. cwebp is the only libwebp tool that
   * resizes, and it takes one image at a time, so per-frame spawning is inherent here. The
   * per-width elapsed time is logged by [encode] so the cost is observable in prod logs.
   */
  private fun assembleScaledWebp(
    frames: List<File>,
    outputWebp: File,
    durationMs: Int,
    widthPx: Int,
  ) {
    val workDir = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-webp-scale-${UUID.randomUUID().toString().take(8)}",
    )
    check(workDir.mkdirs()) {
      "failed to create temp work directory for WebP rescale at ${workDir.absolutePath}"
    }
    try {
      val scaledFrames = frames.mapIndexed { i, png ->
        File(workDir, "f_%05d.webp".format(i)).also { frameWebp ->
          runCommand(
            cwebpResizeArgs(png, frameWebp, widthPx),
            "cwebp resize ${png.name} to ${widthPx}px",
          )
        }
      }
      runToTemp(outputWebp, "webpmux assembly at ${widthPx}px") { temp ->
        webpmuxArgs(scaledFrames, temp, durationMs)
      }
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  // ---- pure argv builders (unit-tested in ReportWebpExporterTest) ----

  /**
   * Per-frame display duration in ms for the measured fps. Clamped to at least 1ms: a very
   * high fps would otherwise round to 0, which some decoders treat as "use the default
   * duration" and play back at the wrong speed.
   */
  internal fun frameDurationMs(fps: Int): Int {
    require(fps > 0) { "fps must be positive, got $fps" }
    return (1000.0 / fps).roundToInt().coerceAtLeast(1)
  }

  /** `img2webp` argv: native-resolution PNG frames → one lossy, infinite-loop animated WebP. */
  internal fun img2webpArgs(frames: List<File>, output: File, durationMs: Int): List<String> =
    buildList {
      add("img2webp")
      add("-loop"); add("0") // 0 = infinite loop
      add("-lossy") // img2webp defaults to lossless; force lossy to match the GIF/video sizing
      add("-q"); add(QUALITY)
      add("-m"); add(METHOD)
      add("-d"); add(durationMs.toString()) // applies to the frames that follow it
      frames.forEach { add(it.absolutePath) }
      add("-o"); add(output.absolutePath)
    }

  /** `cwebp` argv: one PNG frame → one lossy WebP, downscaled to [widthPx] (aspect preserved). */
  internal fun cwebpResizeArgs(source: File, output: File, widthPx: Int): List<String> {
    require(widthPx > 0) { "widthPx must be positive, got $widthPx" }
    return listOf(
      "cwebp",
      "-quiet",
      "-resize", widthPx.toString(), "0", // height 0 = preserve aspect ratio
      "-q", QUALITY,
      "-m", METHOD, // cwebp is lossy by default — there is no -lossy flag
      source.absolutePath,
      "-o", output.absolutePath,
    )
  }

  /** `webpmux` argv: per-frame WebPs → one animated WebP, each frame held for [durationMs]. */
  internal fun webpmuxArgs(frames: List<File>, output: File, durationMs: Int): List<String> =
    buildList {
      add("webpmux")
      frames.forEach { frame ->
        add("-frame"); add(frame.absolutePath); add("+$durationMs") // +<ms> = frame display duration
      }
      add("-loop"); add("0")
      add("-o"); add(output.absolutePath)
    }

  // ---- subprocess plumbing ----

  /**
   * The captured frames, ordered by their numeric index. We sort on the parsed index rather
   * than the filename so order is correct even if the producer's zero-padding ever changes
   * (a lexicographic sort would put `frame_10` before `frame_2`).
   */
  private fun framePngs(framesDir: File): List<File> =
    (framesDir.listFiles() ?: emptyArray())
      .mapNotNull { f -> FRAME_PNG.matchEntire(f.name)?.let { f to it.groupValues[1].toInt() } }
      .sortedBy { it.second }
      .map { it.first }

  /**
   * Run a command that writes [dest] via a unique system-temp file, then atomically rename
   * it onto [dest]. Mirrors the crash-safety pattern in [FfmpegRescaleSupport.runFfmpegToTemp]
   * (temp in `java.io.tmpdir`, `deleteOnExit`, rename-or-copy, finally-cleanup). It is kept
   * separate from that helper deliberately: the WebP path runs the libwebp CLIs (not ffmpeg),
   * the scaled path is multi-command (per-frame `cwebp` + a final `webpmux`), and every
   * invocation routes through an argument file ([runViaArgFile]) — none of which fit
   * `runFfmpegToTemp`'s single-ffmpeg-command shape. Folding both onto one shared primitive
   * is a reasonable future cleanup but would mean touching the working GIF/video paths, so
   * it's out of scope here. [buildArgs] receives the temp file and returns the full argv
   * ending in that path.
   */
  private fun runToTemp(dest: File, errorContext: String, buildArgs: (File) -> List<String>) {
    dest.parentFile?.let {
      require(it.exists() && it.isDirectory) {
        "dest parent directory does not exist: ${it.absolutePath}"
      }
    }
    val temp = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-reportwebpexporter-${UUID.randomUUID().toString().take(8)}.webp",
    )
    temp.deleteOnExit()
    try {
      runViaArgFile(buildArgs(temp), errorContext)
      check(temp.exists() && temp.length() > 0) {
        "webp $errorContext reported success but produced no output at ${temp.absolutePath}"
      }
      if (dest.exists()) dest.delete()
      if (!temp.renameTo(dest)) temp.copyTo(dest, overwrite = true)
    } finally {
      runCatching { if (temp.exists()) temp.delete() }
    }
  }

  /**
   * Run [cmd] (a full `[binary, arg, arg, …]` list) with every argument *after* the binary
   * written to a libwebp "argument file" — one token per line — and passed as the binary's
   * single argv element (`<binary> <argfile>`). Both `img2webp` and `webpmux` accept this
   * form.
   *
   * Why not pass the frames on argv directly: a long report produces one frame path per
   * captured PNG (thousands near the capture watchdog), and that many absolute paths can
   * blow past a small `ARG_MAX` (notably macOS) and fail `ProcessBuilder.start()` with
   * E2BIG *after* the expensive capture. The argument file keeps the process argv O(1)
   * regardless of frame count. The per-frame `cwebp` calls don't go through here — each has
   * a fixed, single-input argv — so they stay direct [runCommand] invocations.
   */
  private fun runViaArgFile(cmd: List<String>, errorContext: String) {
    require(cmd.isNotEmpty()) { "command must include at least the binary name" }
    val binary = cmd.first()
    val argFile = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-reportwebpexporter-args-${UUID.randomUUID().toString().take(8)}.txt",
    )
    argFile.deleteOnExit()
    try {
      argFile.writeText(cmd.drop(1).joinToString("\n", postfix = "\n"))
      runCommand(listOf(binary, argFile.absolutePath), errorContext)
    } finally {
      runCatching { argFile.delete() }
    }
  }

  /** Run a subprocess, draining its merged stdout/stderr into the log, and `check` exit 0. */
  private fun runCommand(cmd: List<String>, errorContext: String) {
    val tool = cmd.first().substringAfterLast('/')
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().useLines { lines ->
      lines.forEach { Console.log("[ReportWebpExporter/$tool] $it") }
    }
    val exit = proc.waitFor()
    check(exit == 0) { "$tool $errorContext failed with exit code $exit" }
  }

  /**
   * Probe whether [binary] is on PATH **and healthy**. We invoke it with `-version` and
   * require a zero exit: a non-zero exit (broken install, missing shared libs) means the
   * tool would fail at encode time, so treating it as "present" would defeat the fail-fast
   * preflight. All three [WEBP_TOOLS] exit 0 on `-version`, so this is a true health check,
   * not just a PATH lookup. A failure to launch at all (no such binary) is caught and
   * reported as absent.
   */
  private fun binaryOnPath(binary: String): Boolean = runCatching {
    val proc = ProcessBuilder(binary, "-version").redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().readText() // drain so the pipe can't block the exit
    proc.waitFor() == 0
  }.getOrDefault(false)
}
