package xyz.block.trailblaze.capture.video

import java.io.File
import javax.imageio.ImageIO
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.util.Console

/**
 * Extracts frames from a video and assembles them into WebP sprite sheet(s).
 *
 * **Deduplication**: Near-identical frames (common when the UI is static) are detected via
 * pixel-level comparison with a small perceptual threshold ([DEDUP_PIXEL_THRESHOLD]) and
 * stored only once in the sprite grid. A `frameMap` in the metadata maps each logical frame
 * index to its physical position in the grid.
 *
 * Unique frames are arranged in a grid: multiple columns of stacked rows, laid out
 * left-to-right, top-to-bottom. Physical frame N is at column `N / rows` and row `N % rows`.
 * WebP has a 16383px dimension limit, so for long sessions frames are split across multiple
 * sprite sheet files (`video_sprites_0.webp`, `video_sprites_1.webp`, etc.).
 *
 * A companion metadata file (`video_sprites.txt`) records the layout so consumers can index
 * into the image(s):
 * ```
 * fps=2
 * frames=120
 * height=720
 * columns=2
 * rows=22
 * uniqueFrames=60
 * sheets=1
 * frameMap=0,0,1,2,2,3,...
 * ```
 *
 * This produces file(s) that are trivially loadable in Compose, WASM, and desktop
 * environments — no video codec or media player required.
 */
object VideoSpriteExtractor {

  const val SPRITE_FILENAME = "video_sprites.webp"
  const val SPRITE_META_FILENAME = "video_sprites.txt"

  /**
   * Always-written diagnostic file capturing the inputs to the duration mismatch / re-stamp
   * decision plus the final `-vf` chain. Lives alongside [SPRITE_META_FILENAME] on success
   * and alongside [FAILURE_MARKER_FILENAME] on failure. The motivating use case: CI silences
   * host-side `Console.log`, so when a sprite sheet comes back unexpectedly small (e.g. the
   * 4-frame-from-87s K1 case in build trailblaze-ios-pr/11092), there's no way to tell from
   * artifacts alone whether re-stamp fired, what ffprobe returned, or what synthetic rate was
   * chosen. This file makes the next regression self-diagnosing — it's part of every session
   * dir so it rides along with whatever artifact upload the CI pipeline runs.
   */
  const val DIAG_FILENAME = "video_sprites_diag.txt"

  /**
   * Marker file written when the input mp4's timing is broken beyond what `maybeRestamp` can
   * recover — extraction produced far fewer frames than the wall-clock window called for,
   * even after attempting re-stamp. When this marker is present, the underlying `video.mp4`
   * is misleading enough that consumers should skip it entirely (don't emit a VIDEO fallback,
   * don't extract frames from it at report-gen time) and let the timeline fall back to the
   * per-step screenshot slideshow.
   *
   * The motivating failure mode is the K1 CI build (trailblaze-ios-pr/11092) where a 2-second
   * mp4 was the only thing left from an 87-second recording; processing it produced 4 sprite
   * frames misleadingly stretched across the full timeline. Better to surface the disconnect
   * via marker + screenshot fallback than to ship a "video" that's nothing of the kind.
   *
   * Callers detect this via [isMp4DetectedAsBroken].
   */
  const val MP4_BROKEN_MARKER_FILENAME = "video_mp4_broken.txt"

  /**
   * Coverage gate thresholds: when [generateSpriteSheet] has [expectedDurationMs] and the
   * wall-clock window is long enough to call for at least [BROKEN_MP4_MIN_EXPECTED_FRAMES]
   * sprite frames, the post-extraction frame count must clear
   * [BROKEN_MP4_MIN_COVERAGE_RATIO] of expected.
   *
   * **Both bounds matter.** The absolute floor (60 expected frames at the default 2fps =
   * 30-second session) keeps short captures and static-screen recordings — the CI smoke
   * test's 11-second single-frame fixture, in particular — out of the broken bucket
   * regardless of how few frames they produce. The fractional ratio (10%) is conservative
   * enough that a static-screen recording where ffmpeg-replicate yielded "only" ~18% of
   * expected still passes; the K1-style pathology (4 frames vs 174 expected ≈ 2.3% over
   * an 87-second test) is well below it. False positives on this gate are tolerable —
   * the timeline falls back to the per-step screenshot slideshow, which is a strict
   * improvement on a sparse sprite stretched across many seconds.
   */
  private const val BROKEN_MP4_MIN_EXPECTED_FRAMES = 60
  private const val BROKEN_MP4_MIN_COVERAGE_RATIO = 0.1

  /**
   * Filename used by [writeFailureMarker] when sprite extraction bails out. Exposed as
   * a constant so the CI smoke test (`cli_smoke_tests_common.sh`) and any other
   * consumer can reference the same string without hardcoding it.
   *
   * **Overwrites on each failure.** [writeFailureMarker] uses `File.writeText`, so a session
   * that hits more than one failure path before bailing keeps only the *last* reason. In
   * practice the extractor short-circuits on first failure (every `writeFailureMarker` call
   * is followed by `return null`), so there's exactly one writer per session — but CI
   * operators should read the marker as "the failure that ended this session," not "every
   * failure that happened."
   */
  const val FAILURE_MARKER_FILENAME = "video_sprites_failed.txt"

  /** Legacy JPEG sprite filename — checked by consumers for backwards compatibility. */
  const val LEGACY_SPRITE_FILENAME = "video_sprites.jpg"

  /**
   * Result of an ffprobe header-only metadata read on a captured mp4 — the two fields the
   * duration-mismatch / re-stamp decision needs. Returned from [probeDurationAndFrameCount]
   * and threaded into both [maybeRestamp] and [buildExtractionDiagnostics] so a single probe
   * call serves both the filter decision and the failure-marker diagnostic.
   */
  private data class ProbedDuration(val reportedSeconds: Double, val frameCount: Long)

  /**
   * Outcome of a [probeDurationAndFrameCount] call — either the parsed values or a specific
   * failure reason. Carrying the failure detail (instead of just returning `null`) means the
   * failure marker can report *why* the probe returned no values, which is the difference
   * between "ffprobe is missing on the agent" and "ffprobe returned but `nb_frames` was N/A"
   * — same null-shape, very different fixes.
   */
  private sealed interface ProbeOutcome {
    data class Success(val duration: ProbedDuration) : ProbeOutcome

    /** [reason] is a short tag (e.g. "exit=1", "missing-fields"). [detail] is free-form. */
    data class Failure(val reason: String, val detail: String) : ProbeOutcome
  }

  /** WebP encoders cap at 16383px per dimension. */
  private const val MAX_WEBP_DIMENSION = 16383

  /**
   * Maximum average pixel difference (per channel, 0–255) for two frames to be considered
   * identical. Re-encoding can introduce tiny noise even for visually identical frames.
   * A threshold of 1.0 catches codec noise while still distinguishing real UI changes.
   */
  private const val DEDUP_PIXEL_THRESHOLD = 1.0

  /**
   * Fractional tolerance for the reported-vs-expected video duration check. Multiplied by
   * the *expected* (recorder-observed wall-clock) duration to derive a tolerance window:
   * if the container's reported duration is within `expectedS * FRAC` of expected, the
   * re-stamp is skipped. Slightly looser than the obvious choice (5%) because
   * variable-frame-rate captures and stop-latency jitter can legitimately move the
   * container duration by a few percent from the wall-clock window even on healthy
   * recordings.
   */
  private const val DURATION_MISMATCH_TOLERANCE_FRAC = 0.10

  /**
   * Lower-bound (in seconds) on the duration-mismatch tolerance. For short clips, 10% of the
   * expected duration can be a fraction of a second; we don't want to trigger a re-stamp on
   * sub-second timing wobble. Two seconds is well above start/stop latency for any of the
   * three capture backends.
   */
  private const val DURATION_MISMATCH_TOLERANCE_FLOOR_S = 2.0

  /**
   * Minimum ratio of `min(reported, expected) / max(reported, expected)` we treat as "healthy"
   * regardless of the absolute tolerance. The absolute floor [DURATION_MISMATCH_TOLERANCE_FLOOR_S]
   * can swallow the broken-timing signal when the expected window is itself short — a CI smoke
   * run with `expectedS=1.0s` and `reportedS=0.04s` has `|diff|=0.96s`, inside the 2s floor — and
   * the un-re-stamped pipeline then feeds a 0.04-second timeline into `fps=2` and produces zero
   * PNG frames. The ratio bound catches that: if the two durations disagree by more than 2×, the
   * timing is treated as broken and the re-stamp runs regardless of absolute slack.
   *
   * This bound — not [SYNTHETIC_RATE_MIN] — is the primary gate for short-clip recovery. The
   * synthetic-rate floor is now a downstream defensive clamp; whenever a static-screen recording
   * needs re-stamping, this ratio check is what trips first.
   *
   * Healthy timing wobble (the original motivation for the floor) is always well inside this
   * ratio — a 1.0s recording reporting 0.9s has ratio 0.9, well above the 0.5 threshold — so
   * adding the ratio bound doesn't introduce false positives.
   */
  private const val DURATION_MISMATCH_RATIO_MIN = 0.5

  /**
   * Sane bounds for the synthetic rate computed in `maybeRestamp`. These are a *defensive
   * clamp* on the `nbFrames / expectedS` rate that gets baked into the
   * `setpts=N/<rate>/TB` filter — they exist to refuse rates that ffmpeg would technically
   * accept but render meaningless output for. They are **not** the primary gate that decides
   * whether a re-stamp runs at all; that decision lives upstream in [DURATION_MISMATCH_RATIO_MIN]
   * + [DURATION_MISMATCH_TOLERANCE_FLOOR_S].
   *
   * 60 fps is the practical capture ceiling for any of the three backends today; doubling to
   * 120 fps leaves headroom for future high-rate devices.
   *
   * The lower bound is intentionally tiny (1 frame per ~3 minutes) so that low-activity
   * recordings — e.g. a static-screen AVD that emits a single IDR over an ~11-second blaze
   * (`nb_frames=1, duration=0.04s`, synthetic rate `1 / 11 ≈ 0.09` fps) — still get
   * re-stamped successfully. A higher floor would silently fall back to the un-re-stamped
   * pipeline, which then can't extract a frame from the 0.04s timeline.
   *
   * Outside this range, we treat the inputs as broken (e.g., near-zero `expectedDurationMs`
   * combined with a non-trivial frame count) and skip the re-stamp rather than emit a filter
   * chain that ffmpeg would render meaninglessly.
   */
  private const val SYNTHETIC_RATE_MIN = 0.005
  private const val SYNTHETIC_RATE_MAX = 120.0

  /**
   * Timeout for the ffprobe-based duration probe. Header-only metadata (no `-count_frames`)
   * should return in milliseconds; 10s is a generous bound that catches a wedged subprocess
   * without making CI runs sit on a hang.
   */
  private const val FFPROBE_TIMEOUT_SECONDS: Long = 10L

  /**
   * Timeout for the heavier-weight ffmpeg invocations (frame extraction, sprite tile assembly).
   * 120s mirrors what the open-coded versions used before they were migrated to
   * `runSubprocessWithTimeout` — long enough for a multi-minute trail's worth of frames on a
   * slow CI agent, short enough that a wedged ffmpeg doesn't pin the capture-stop path
   * indefinitely.
   */
  private const val FFMPEG_LONG_TIMEOUT_SECONDS: Long = 120L

  /**
   * Generates sprite sheet(s) from [videoFile]. Returns the first sprite sheet file, or null
   * if ffmpeg is unavailable or the extraction fails. Additional sheets (for long sessions)
   * are written alongside it with sequential suffixes.
   *
   * @param fps Frames per second to extract (default: 2)
   * @param frameHeight Height in pixels for each frame (default: 360)
   * @param webpQuality WebP quality (0–100, higher is better; default: 80)
   * @param isLandscape Whether the device was in landscape orientation during recording.
   *   When true and the video is in portrait orientation (common with iOS simulator's native
   *   pixel buffer), a transpose filter is applied to rotate frames to landscape.
   * @param expectedDurationMs Wall-clock duration the recorder observed (typically
   *   `endTimestampMs - startTimestampMs` from the capture stream). Strongly recommended for
   *   all production callers — every in-tree capture site (Android, iOS, Playwright) passes
   *   it, and omitting it (or passing `null`) silently restores the pre-fix under-sampling
   *   behavior on broken-timing mp4s. The nullable default exists only so any external/legacy
   *   caller that hasn't been updated doesn't break the build; it is not an endorsed mode.
   *
   *   When provided, the input mp4's self-reported duration is sanity-checked against this
   *   value; if they disagree by more than the [DURATION_MISMATCH_TOLERANCE_FRAC] fractional
   *   tolerance (with a floor at [DURATION_MISMATCH_TOLERANCE_FLOOR_S]), the captured frames
   *   are re-stamped at a constant rate of `nb_frames / expectedDuration` so they spread
   *   uniformly across the wall-clock window. This handles broken-timing mp4s — most
   *   importantly the raw-H.264-wrapped output from the Android `screenrecord` chain, where
   *   ffmpeg's `-c copy` wrap stamps PTS at a default 25fps that doesn't reflect real
   *   wall-clock — and is a no-op for mp4s whose container timestamps already match
   *   wall-clock (iOS sim, Playwright).
   */
  fun generateSpriteSheet(
    videoFile: File,
    fps: Int = CaptureOptions.DEFAULT_SPRITE_FPS,
    frameHeight: Int = CaptureOptions.DEFAULT_SPRITE_HEIGHT,
    webpQuality: Int = CaptureOptions.DEFAULT_SPRITE_QUALITY,
    isLandscape: Boolean = false,
    expectedDurationMs: Long? = null,
  ): File? {
    // Treat `fps <= 0` as "caller doesn't want sprites." Without this guard, the ffmpeg
    // frame-extraction below runs with `-vf fps=0`, fails noisily, and the caller has to
    // sift through misleading "ffmpeg frame extraction failed" output. Used by
    // `trailblaze report --video`, which only needs the underlying MP4.
    if (fps <= 0) {
      Console.log("Sprite extraction skipped (fps=$fps)")
      return null
    }
    val dir = videoFile.parentFile ?: return null
    // Clear any marker / failure-marker / diag files left over from an earlier attempt in this
    // session dir. In practice session dirs are unique per recording, but a stale broken-mp4
    // marker from a prior failed run would otherwise cause this run's healthy output to be
    // silently suppressed by the platform wrappers. Best-effort delete — the writeText calls
    // later in this function overwrite atomically anyway, but the broken-mp4 marker has no
    // overwrite path, so explicit cleanup is the only way to guarantee a fresh verdict.
    runCatching { File(dir, MP4_BROKEN_MARKER_FILENAME).delete() }
    try {
      val originalKB = videoFile.length() / 1024
      Console.log(
        "Generating sprite sheet from video (${originalKB}KB) at ${fps}fps, ${frameHeight}p..."
      )

      // Resolve the probe + re-stamp + diagnostics block *before* createTempDirectory so the
      // diagnostic file is present even if temp-dir allocation throws (no-space, permission
      // denied, etc.). Honest "always-written" semantics: the only failure mode that can
      // skip the diag file is an exception inside ffprobe/buildVideoFilter themselves, which
      // gets caught by the outer try and routed through the failure marker.
      //
      // If the caller passed an expected wall-clock duration, validate the input mp4's
      // self-reported duration against it. When the two disagree (Android raw-H.264 wrap
      // producing a 2-second mp4 for an 80-second recording is the canonical case), prepend
      // a `setpts=N/<rate>/TB` filter that re-stamps each frame at a constant synthetic rate
      // chosen so the captured frames spread uniformly across the expected window. The
      // `fps=<sprite>` filter further down the chain then resamples from that re-stamped
      // timeline. For correctly-timestamped mp4s the prepended filter is omitted entirely.
      //
      // Run the ffprobe duration/frame-count probe once and share the result between
      // `maybeRestamp` and the failure-marker writers below, so a sprite-extraction failure
      // can dump the probe outcome (including the specific failure reason on the unhappy
      // path) inline. CI silences `Console.log`, so this is the only path the diagnostics
      // survive an artifact snapshot.
      val (baseVf, noAutorotate) = buildVideoFilter(videoFile, fps, frameHeight, isLandscape)
      val probeOutcome: ProbeOutcome? = expectedDurationMs?.takeIf { it > 0L }
        ?.let { probeDurationAndFrameCount(videoFile) }
      val probe = (probeOutcome as? ProbeOutcome.Success)?.duration
      val vf = maybeRestamp(videoFile, baseVf, expectedDurationMs, probe)
      val diagSuffix = buildExtractionDiagnostics(expectedDurationMs, probeOutcome, vf)
      writeDiagnostics(dir, diagSuffix)

      val tempDir = java.nio.file.Files.createTempDirectory("trailblaze_sprites_").toFile()
      try {
        // Step 1: Extract individual frames as PNG (lossless intermediary for dedup accuracy).
        val extractCmd =
          mutableListOf(
            "ffmpeg",
            "-i",
            videoFile.absolutePath,
          )
        if (noAutorotate) extractCmd.add("-noautorotate")
        extractCmd.addAll(
          listOf(
            "-vf",
            vf,
            "-loglevel",
            "error",
            "${tempDir.absolutePath}/vf_%06d.png",
          )
        )
        val extractResult = runSubprocessWithTimeout(extractCmd, FFMPEG_LONG_TIMEOUT_SECONDS)
        if (extractResult == null || extractResult.exitCode != 0) {
          val reason =
            "ffmpeg frame extraction failed " +
              "(${if (extractResult == null) "process did not start or timed out" else "exit=${extractResult.exitCode}"})\n" +
              "cmd: ${extractCmd.joinToString(" ")}\n" +
              "videoSize=${videoFile.length()}B fps=$fps frameHeight=$frameHeight isLandscape=$isLandscape\n" +
              diagSuffix +
              if (extractResult != null && extractResult.output.isNotBlank())
                "stdout/stderr:\n${sanitizeSubprocessOutputForLog(extractResult.output.trim())}"
              else ""
          Console.log(reason)
          writeFailureMarker(dir, reason)
          return null
        }

        var frameFiles =
          tempDir.listFiles { _, name -> name.startsWith("vf_") && name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        // Single-frame fallback. The fps-sampling pipeline can yield zero PNG frames when:
        //   (a) the input mp4's container has bogus / missing timing metadata that defeats
        //       the `fps=N` filter even though ffmpeg can still decode the stream itself, or
        //   (b) the probe couldn't validate the timing for re-stamp upstream so `maybeRestamp`
        //       fell back to baseVf and the un-re-stamped 0.04s timeline produced no samples.
        // In both shapes the mp4 *is* decodable — just not at the requested fps — so retry
        // with `-frames:v 1` to grab the first decoded frame. A single static sprite is a
        // strictly better timeline-scrubber outcome than bailing entirely (which is what the
        // CI smoke test currently sees: `video_sprites.webp missing`).
        if (frameFiles.isEmpty()) {
          Console.log("Primary extraction yielded 0 frames — retrying with single-frame fallback")
          frameFiles = extractSingleFrameFallback(videoFile, tempDir, frameHeight, isLandscape)
        }
        if (frameFiles.isEmpty()) {
          Console.log("No frames extracted from video (single-frame fallback also yielded 0)")
          writeFailureMarker(
            dir,
            "ffmpeg ran (exit=0) but produced 0 frames\n" +
              "single-frame fallback also produced 0 frames — the mp4 is not decodable\n" +
              "videoSize=${videoFile.length()}B fps=$fps frameHeight=$frameHeight isLandscape=$isLandscape\n" +
              diagSuffix +
              "ffmpeg stdout/stderr:\n${sanitizeSubprocessOutputForLog(extractResult.output.trim())}",
          )
          return null
        }

        val frameCount = frameFiles.size

        // Broken-MP4 coverage gate. When the input mp4's timing was so off that even
        // maybeRestamp couldn't get the `fps=<sprite>` resampler to spread frames across the
        // wall-clock window, ffmpeg returns a count far below what the expected duration calls
        // for. Producing a sprite from that wreckage gives the timeline a "video" that's
        // really just a handful of frames misleadingly stretched across many seconds (K1 CI,
        // build trailblaze-ios-pr/11092 — 4 frames over 87s). Better to skip emission and let
        // consumers fall back to the per-step screenshot slideshow.
        //
        // The gate only fires when (a) we have a wall-clock expected duration to compare
        // against and (b) that expected duration calls for at least
        // [BROKEN_MP4_MIN_EXPECTED_FRAMES] sprites — both bounds keep short / static-screen
        // recordings, which legitimately produce one or two frames, out of this bucket.
        val expectedFrames = expectedDurationMs?.takeIf { it > 0L }
          ?.let { ((it * fps) / 1000L).toInt() }
        if (expectedFrames != null && expectedFrames >= BROKEN_MP4_MIN_EXPECTED_FRAMES) {
          val coverage = frameCount.toFloat() / expectedFrames.toFloat()
          if (coverage < BROKEN_MP4_MIN_COVERAGE_RATIO) {
            val reason =
              "MP4 timing is broken beyond re-stamp recovery: extracted $frameCount frames " +
                "vs expected ~$expectedFrames (${"%.1f".format(coverage * 100)}% coverage; " +
                "gate trips below ${"%.0f".format(BROKEN_MP4_MIN_COVERAGE_RATIO * 100)}%). " +
                "Skipping sprite emission so the timeline falls back to the screenshot " +
                "slideshow instead of presenting a tiny video stretched across many seconds.\n" +
                diagSuffix
            Console.log("[VideoSpriteExtractor] $reason")
            writeFailureMarker(dir, reason)
            writeBrokenMp4Marker(dir, reason)
            return null
          }
        }

        // Step 2: Deduplicate identical (or near-identical) frames.
        val frameMap = IntArray(frameCount)
        val uniqueFrameFiles = mutableListOf<File>()
        val uniquePixels = mutableListOf<IntArray>()

        for ((logicalIndex, file) in frameFiles.withIndex()) {
          val image = ImageIO.read(file)
          val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

          var matchIndex = -1
          val searchWindow = 5.coerceAtMost(uniquePixels.size)
          for (j in 1..searchWindow) {
            val i = uniquePixels.size - j
            if (uniquePixels[i].size == pixels.size && areFramesSimilar(uniquePixels[i], pixels)) {
              matchIndex = i
              break
            }
          }

          if (matchIndex >= 0) {
            frameMap[logicalIndex] = matchIndex
          } else {
            frameMap[logicalIndex] = uniqueFrameFiles.size
            uniqueFrameFiles.add(file)
            uniquePixels.add(pixels)
          }
        }

        val uniqueCount = uniqueFrameFiles.size
        val dedupSavings = frameCount - uniqueCount
        if (dedupSavings > 0) {
          Console.log(
            "Frame dedup: $uniqueCount unique frames from $frameCount total " +
              "(${dedupSavings} duplicates removed)"
          )
        }

        // Read the actual frame width from the first extracted frame
        val firstFrame = ImageIO.read(uniqueFrameFiles.first())
          ?: run {
            Console.log("Failed to decode first video frame — sprite sheet extraction aborted")
            writeFailureMarker(
              dir,
              "ImageIO.read returned null for the first extracted PNG frame " +
                "(${uniqueFrameFiles.first().name}, ${uniqueFrameFiles.first().length()}B). " +
                "Likely cause: JVM lacks PNG codec or the file is corrupt.",
            )
            return null
          }
        val frameWidth = firstFrame.width

        // Step 3: Assemble sprite sheet(s) as WebP.
        // WebP max dimension is 16383px in both axes, so constrain rows by height
        // and columns by width. Split into multiple sheets for long sessions.
        val maxRows = (MAX_WEBP_DIMENSION / frameHeight).coerceAtLeast(1)
        val maxCols = (MAX_WEBP_DIMENSION / frameWidth).coerceAtLeast(1)
        val framesPerSheet = maxRows * maxCols
        val sheetCount = ((uniqueCount + framesPerSheet - 1) / framesPerSheet).coerceAtLeast(1)

        // Multi-sheet sprites are not yet supported by downstream consumers — the
        // VideoMetadata / WasmReport / VideoFrameCache code paths only ever load the
        // first sheet, so frames whose physical index lands on `video_sprites_1.webp`
        // (or higher) are unreachable. Bail out cleanly so the timeline falls back to
        // the screenshot slideshow rather than silently dropping frames.
        if (sheetCount > 1) {
          Console.log(
            "Sprite sheet would require $sheetCount sheets ($uniqueCount unique frames), " +
              "but consumers only support a single sheet — skipping sprite extraction. " +
              "Reduce session length, frame fps, or frame height to fit one sheet.",
          )
          writeFailureMarker(
            dir,
            "Sprite sheet would require $sheetCount sheets " +
              "($uniqueCount unique frames, frameWidth=$frameWidth, frameHeight=$frameHeight, " +
              "framesPerSheet=$framesPerSheet) — consumers only support 1.",
          )
          return null
        }

        var totalSpriteKB = 0L
        val spriteFiles = mutableListOf<File>()

        // Overall grid dimensions for metadata (describes the logical layout)
        val columns = ((uniqueCount + maxRows - 1) / maxRows).coerceAtLeast(1).coerceAtMost(maxCols)
        val rows = ((uniqueCount + columns - 1) / columns).coerceAtLeast(1)

        for (sheetIndex in 0 until sheetCount) {
          val startFrame = sheetIndex * framesPerSheet
          val endFrame = ((sheetIndex + 1) * framesPerSheet).coerceAtMost(uniqueCount)
          val sheetFrameCount = endFrame - startFrame

          val sheetColumns =
            ((sheetFrameCount + maxRows - 1) / maxRows).coerceAtLeast(1).coerceAtMost(maxCols)
          val sheetRows =
            ((sheetFrameCount + sheetColumns - 1) / sheetColumns).coerceAtLeast(1)

          val tileDir = java.nio.file.Files.createTempDirectory("trailblaze_tile_").toFile()
          try {
            for ((index, globalIndex) in (startFrame until endFrame).withIndex()) {
              uniqueFrameFiles[globalIndex].copyTo(
                File(tileDir, "uf_%06d.png".format(index + 1))
              )
            }

            val spriteFilename =
              if (sheetCount == 1) SPRITE_FILENAME
              else "video_sprites_$sheetIndex.webp"
            val spriteFile = File(dir, spriteFilename)

            val tileResult =
              runSubprocessWithTimeout(
                command =
                  listOf(
                    "ffmpeg",
                    "-f",
                    "image2",
                    "-framerate",
                    "1",
                    "-i",
                    "${tileDir.absolutePath}/uf_%06d.png",
                    "-vf",
                    "tile=${sheetColumns}x${sheetRows}",
                    "-c:v",
                    "libwebp",
                    "-quality",
                    webpQuality.toString(),
                    "-frames:v",
                    "1",
                    "-loglevel",
                    "error",
                    "-y",
                    spriteFile.absolutePath,
                  ),
                timeoutSeconds = FFMPEG_LONG_TIMEOUT_SECONDS,
              )
            if (tileResult == null || tileResult.exitCode != 0 || !spriteFile.exists()) {
              val reason =
                "ffmpeg sprite sheet assembly failed for sheet $sheetIndex " +
                  "(${if (tileResult == null) "process did not start or timed out" else "exit=${tileResult.exitCode}, fileExists=${spriteFile.exists()}"})\n" +
                  "grid=${sheetColumns}x${sheetRows} frames=$sheetFrameCount quality=$webpQuality" +
                  if (tileResult != null && tileResult.output.isNotBlank())
                    "\nstdout/stderr:\n${sanitizeSubprocessOutputForLog(tileResult.output.trim())}"
                  else ""
              Console.log(reason)
              writeFailureMarker(dir, reason)
              spriteFile.delete()
              return null
            }

            totalSpriteKB += spriteFile.length() / 1024
            spriteFiles.add(spriteFile)
          } finally {
            tileDir.deleteRecursively()
          }
        }

        Console.log(
          "Sprite sheet: $uniqueCount unique frames from $frameCount total " +
            "(${columns}x${rows} grid, $sheetCount sheet(s)), " +
            "${totalSpriteKB}KB (from ${originalKB}KB video)"
        )

        // Write metadata so consumers know how to index into the sprite(s).
        val metaFile = File(dir, SPRITE_META_FILENAME)
        val frameMapStr = frameMap.joinToString(",")
        metaFile.writeText(
          buildString {
            appendLine("fps=$fps")
            appendLine("frames=$frameCount")
            appendLine("height=$frameHeight")
            appendLine("columns=$columns")
            appendLine("rows=$rows")
            appendLine("uniqueFrames=$uniqueCount")
            appendLine("sheets=$sheetCount")
            appendLine("frameMap=$frameMapStr")
          }
        )

        return spriteFiles.firstOrNull()
      } finally {
        tempDir.deleteRecursively()
      }
    } catch (e: Exception) {
      Console.log("ffmpeg not available for sprite extraction: ${e.message}")
      val parent = videoFile.parentFile
      if (parent != null) {
        writeFailureMarker(
          parent,
          "Exception during sprite extraction: ${e::class.simpleName}: ${e.message}\n" +
            e.stackTraceToString(),
        )
      }
      return null
    }
  }

  /**
   * Re-runs ffmpeg on [videoFile] requesting exactly one decoded frame (`-frames:v 1`), with
   * the same scale (and rotation-if-needed) applied as the primary extraction. This is the
   * fallback path when the primary `fps=N` sampling yields zero PNG files — the mp4 is
   * decodable but the container's timing is too sparse / broken for `fps=N` to find any
   * samples. Returns the resulting PNG file(s) in [tempDir] (typically 0 or 1), or empty if
   * the fallback also fails (binary missing, file truly corrupt, etc.). Logs the failure
   * reason via `Console.log` for parity with the rest of this object's failure-path style.
   */
  private fun extractSingleFrameFallback(
    videoFile: File,
    tempDir: File,
    frameHeight: Int,
    isLandscape: Boolean,
  ): List<File> {
    val (baseVf, noAutorotate) = buildVideoFilter(videoFile, fps = 1, frameHeight = frameHeight, isLandscape = isLandscape)
    // Strip the leading `fps=1,` — `-frames:v 1` already bounds the output to one frame and
    // keeping the fps filter risks the same zero-samples problem we're falling back from.
    val scaleOnlyVf = baseVf.removePrefix("fps=1,")
    val cmd = mutableListOf("ffmpeg", "-i", videoFile.absolutePath)
    if (noAutorotate) cmd.add("-noautorotate")
    cmd.addAll(
      listOf(
        "-vf",
        scaleOnlyVf,
        "-frames:v",
        "1",
        "-loglevel",
        "error",
        "${tempDir.absolutePath}/vf_fallback_%06d.png",
      )
    )
    val result = runSubprocessWithTimeout(cmd, FFMPEG_LONG_TIMEOUT_SECONDS)
    if (result == null || result.exitCode != 0) {
      Console.log(
        "[VideoSpriteExtractor] single-frame fallback failed " +
          "(${if (result == null) "process did not start or timed out" else "exit=${result.exitCode}"}): " +
          if (result != null) sanitizeSubprocessOutputForLog(result.output.trim()) else ""
      )
      return emptyList()
    }
    return tempDir.listFiles { _, name -> name.startsWith("vf_fallback_") && name.endsWith(".png") }
      ?.sortedBy { it.name } ?: emptyList()
  }

  /**
   * Returns a short multi-line diagnostic block that summarizes the inputs to the duration
   * mismatch / re-stamp decision: the wall-clock expected duration, the ffprobe-reported
   * (duration, nb_frames) pair, and the actual ffmpeg `-vf` chain we chose. CI silences
   * `Console.log`, so the only signal that survives an artifact snapshot is whatever lands in
   * `video_sprites_failed.txt`. Embedding this block in both failure-marker call sites makes
   * the next sprite-extraction regression self-diagnosing — an operator opening the marker
   * sees expected-vs-reported timing and whether `setpts=…` was injected.
   *
   * The block always ends with a trailing newline so callers can concatenate it inline
   * without worrying about separator placement.
   */
  private fun buildExtractionDiagnostics(
    expectedDurationMs: Long?,
    probeOutcome: ProbeOutcome?,
    vf: String,
  ): String = buildString {
    appendLine("expectedDurationMs=$expectedDurationMs")
    when (probeOutcome) {
      null ->
        appendLine("probe=skipped (expectedDurationMs was null or non-positive)")
      is ProbeOutcome.Success ->
        appendLine(
          "probe=reportedS=${"%.3f".format(probeOutcome.duration.reportedSeconds)} " +
            "nb_frames=${probeOutcome.duration.frameCount}"
        )
      is ProbeOutcome.Failure -> {
        appendLine("probe=failed reason=${probeOutcome.reason}")
        // Indent the detail so a multi-line stdout/stderr block stays visually contained when
        // the marker is printed by the smoke-test `sed 's/^/      /'` indenter.
        appendLine("probe-detail=${probeOutcome.detail.replace("\n", " | ")}")
      }
    }
    appendLine("vf=$vf")
  }

  /**
   * Writes a `video_sprites_failed.txt` next to the video so the failure reason
   * survives in CI artifact snapshots even when `Console.log` is silenced. The
   * file is listed by `SessionCaptureCoordinator.stopForSession`'s
   * `filesAfterStop=` diagnostic, so it's surfaced wherever the session dir is
   * inspected post-mortem.
   *
   * Best-effort: a write failure here is swallowed because we're already on the
   * failure path and don't want to mask the original cause.
   */
  private fun writeFailureMarker(dir: File, reason: String) {
    runCatching { File(dir, FAILURE_MARKER_FILENAME).writeText(reason) }
      .onFailure { e ->
        // Best-effort: a write failure here is logged but not propagated — we're
        // already on the failure path and don't want to mask the original cause.
        // The Console.log line at least leaves a footprint when the test harness is
        // capturing daemon output (CI typically silences Console.log, but local
        // repros see it).
        Console.log(
          "[VideoSpriteExtractor] failed to write failure marker " +
            "(${dir.absolutePath}/$FAILURE_MARKER_FILENAME): ${e::class.simpleName}: ${e.message}",
        )
      }
  }

  /**
   * Writes [DIAG_FILENAME] alongside the video. Called once per extraction attempt, before
   * any heavy ffmpeg work — so when something downstream goes wrong (extraction yields the
   * wrong number of frames, the artifact upload runs after a crash, etc.) the probe + re-stamp
   * decision is preserved on disk.
   *
   * Best-effort like [writeFailureMarker]: a write failure here doesn't propagate, because
   * the diagnostic is a debugging aid — losing it shouldn't fail the recording.
   */
  private fun writeDiagnostics(dir: File, content: String) {
    runCatching { File(dir, DIAG_FILENAME).writeText(content) }
      .onFailure { e ->
        Console.log(
          "[VideoSpriteExtractor] failed to write diagnostics " +
            "(${dir.absolutePath}/$DIAG_FILENAME): ${e::class.simpleName}: ${e.message}",
        )
      }
  }

  /**
   * Writes [MP4_BROKEN_MARKER_FILENAME] alongside the video. The marker's presence — not its
   * contents — is the signal consumers (platform capture, report generation) read; the body
   * just carries the same diagnostic block as the failure marker so the marker is also
   * human-readable when opened directly.
   *
   * Best-effort. Same reasoning as [writeDiagnostics] / [writeFailureMarker].
   */
  private fun writeBrokenMp4Marker(dir: File, reason: String) {
    runCatching { File(dir, MP4_BROKEN_MARKER_FILENAME).writeText(reason) }
      .onFailure { e ->
        Console.log(
          "[VideoSpriteExtractor] failed to write broken-mp4 marker " +
            "(${dir.absolutePath}/$MP4_BROKEN_MARKER_FILENAME): ${e::class.simpleName}: ${e.message}",
        )
      }
  }

  /**
   * Returns true when an earlier [generateSpriteSheet] attempt determined the mp4 in [dir]
   * is broken beyond what re-stamp can fix. Platform capture wrappers consult this to decide
   * whether to emit a VIDEO fallback artifact alongside a null sprite return — and
   * report-generation paths consult it to decide whether to attempt their own raw-mp4 frame
   * extraction. When this returns true, callers should treat the recording as having no
   * usable video stream at all.
   *
   * Returns false when [dir] is null — used by platform wrappers passing
   * `File.parentFile`, which is platform-nullable. A null parent means "no sibling dir to
   * check," so the broken-mp4 verdict cannot apply.
   */
  fun isMp4DetectedAsBroken(dir: File?): Boolean =
    dir != null && File(dir, MP4_BROKEN_MARKER_FILENAME).exists()

  /**
   * Helper for the three platform capture wrappers (Android / iOS / Playwright). After a null
   * return from [generateSpriteSheet], check whether the underlying mp4 was flagged as broken
   * beyond re-stamp recovery; if so, log via [Console.log] with [platformTag] and return true
   * so the caller skips emitting a VIDEO fallback artifact. The shared helper keeps the
   * "consult marker, log, decide" three-liner from drifting between callers.
   */
  fun shouldSkipVideoFallbackForBrokenMp4(dir: File?, platformTag: String): Boolean {
    if (!isMp4DetectedAsBroken(dir)) return false
    Console.log(
      "[$platformTag] Skipping VIDEO fallback: $MP4_BROKEN_MARKER_FILENAME present" +
        (dir?.let { " in ${it.absolutePath}" } ?: ""),
    )
    return true
  }

  /**
   * Returns true if two frames (as packed ARGB int arrays) are perceptually similar —
   * i.e., the average per-channel pixel difference is within [DEDUP_PIXEL_THRESHOLD].
   *
   * Uses a sampling strategy for speed: checks every Nth pixel first (fast reject), then
   * falls back to a full comparison only when the sample passes.
   */
  private fun areFramesSimilar(a: IntArray, b: IntArray): Boolean {
    if (a.size != b.size) return false
    val totalPixels = a.size
    if (totalPixels == 0) return true

    // Quick sample: check ~1% of pixels (at least 100, at most all)
    val sampleStep = (totalPixels / 100).coerceIn(1, 1000)
    var sampleDiffSum = 0L
    var sampleCount = 0
    var i = 0
    while (i < totalPixels) {
      sampleDiffSum += pixelDiff(a[i], b[i])
      sampleCount++
      i += sampleStep
    }
    val sampleAvg = sampleDiffSum.toDouble() / (sampleCount * 3) // 3 channels (R, G, B)
    // Fast reject: if the sample average is way over threshold, skip full scan
    if (sampleAvg > DEDUP_PIXEL_THRESHOLD * 2) return false
    // Fast accept: if sample is very close, likely a match
    if (sampleAvg < DEDUP_PIXEL_THRESHOLD * 0.5 && sampleCount >= 100) return true

    // Full comparison
    var diffSum = 0L
    for (idx in 0 until totalPixels) {
      diffSum += pixelDiff(a[idx], b[idx])
    }
    val avgDiff = diffSum.toDouble() / (totalPixels.toLong() * 3)
    return avgDiff <= DEDUP_PIXEL_THRESHOLD
  }

  /** Sum of absolute per-channel differences for a single packed ARGB pixel pair. */
  private fun pixelDiff(p1: Int, p2: Int): Int {
    val dr = kotlin.math.abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
    val dg = kotlin.math.abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
    val db = kotlin.math.abs((p1 and 0xFF) - (p2 and 0xFF))
    return dr + dg + db
  }

  /**
   * Builds the ffmpeg video filter chain for frame extraction.
   *
   * When the device was in landscape orientation but the video was recorded in portrait
   * (common with iOS simulator's native pixel buffer), a `transpose` filter is prepended
   * to rotate frames to landscape before scaling.
   *
   * @return Pair of (filter string, whether -noautorotate should be added)
   */
  private fun buildVideoFilter(
    videoFile: File,
    fps: Int,
    frameHeight: Int,
    isLandscape: Boolean,
  ): Pair<String, Boolean> {
    val baseFilter = "fps=$fps,scale=-2:$frameHeight"
    if (!isLandscape) return Pair(baseFilter, false)

    val dims = getVideoDimensions(videoFile) ?: return Pair(baseFilter, false)
    val (videoWidth, videoHeight) = dims

    // Video coded dimensions are already landscape — no rotation needed.
    // ffmpeg auto-rotation (enabled by default) will handle any rotation metadata.
    if (videoWidth >= videoHeight) return Pair(baseFilter, false)

    // Video is portrait but device was landscape — need to rotate.
    // Disable auto-rotation and manually transpose to prevent double-rotation
    // in case the video also has rotation metadata.
    val rotation = getVideoRotation(videoFile)
    val transposeFilter =
      when (rotation) {
        90 -> "transpose=1" // clockwise 90°
        270 -> "transpose=2" // counterclockwise 90°
        else -> "transpose=1" // default to clockwise (LANDSCAPE_RIGHT)
      }

    Console.log(
      "Rotating video frames: coded=${videoWidth}x${videoHeight}, " +
        "rotation=$rotation, applying $transposeFilter"
    )
    return Pair("$transposeFilter,$baseFilter", true)
  }

  /** Gets the coded dimensions of a video file using ffprobe. */
  private fun getVideoDimensions(videoFile: File): Pair<Int, Int>? {
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height",
            "-of",
            "csv=s=x:p=0",
            videoFile.absolutePath,
          ),
        timeoutSeconds = FFPROBE_TIMEOUT_SECONDS,
      ) ?: return null
    if (result.exitCode != 0) return null
    val parts = result.output.trim().split("x")
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull()
    val h = parts[1].trim().toIntOrNull()
    return if (w != null && h != null) Pair(w, h) else null
  }

  /**
   * Prepend a `setpts=N/<rate>/TB` filter to [baseVf] when the input mp4's container duration
   * doesn't match the recorder-observed wall-clock window. The synthetic rate is chosen so the
   * input's frames spread evenly across the expected window; the downstream `fps=<sprite>`
   * filter then resamples from that re-stamped timeline.
   *
   * Returns [baseVf] unchanged when:
   *  - the caller didn't provide an expected duration (`null` or `<= 0`),
   *  - the caller couldn't supply a [probe] result (ffprobe was unavailable, timed out, or
   *    didn't report both `duration` and `nb_frames`),
   *  - the reported duration is within the mismatch tolerance.
   *
   * Logs a single line via `Console.log` whenever a re-stamp is applied so the underlying
   * recording bug is visible in CI artifacts without spamming healthy runs.
   *
   * @param probe ffprobe-derived (reportedSeconds, frameCount), or `null` when the probe
   *   wasn't run (no [expectedDurationMs]) or failed. The caller — not this function — runs
   *   the probe so the same result can be shared with [buildExtractionDiagnostics].
   */
  private fun maybeRestamp(
    videoFile: File,
    baseVf: String,
    expectedDurationMs: Long?,
    probe: ProbedDuration?,
  ): String {
    if (expectedDurationMs == null || expectedDurationMs <= 0L) return baseVf
    val expectedS = expectedDurationMs / 1000.0
    probe ?: return baseVf
    val reportedS = probe.reportedSeconds
    val nbFrames = probe.frameCount
    if (nbFrames <= 0L || reportedS <= 0.0) {
      Console.log(
        "[VideoSpriteExtractor] duration probe yielded non-positive values for " +
          "${videoFile.name}: reportedS=$reportedS nbFrames=$nbFrames — skipping re-stamp"
      )
      return baseVf
    }
    // Base the fractional tolerance on the expected (recorder-observed) duration rather than
    // the reported one: the recorder's wall-clock is the authoritative source here, and the
    // canonical failure mode this guards against — a 79s recording showing up as a 2s
    // container — has a tiny reported duration whose 10% fraction (0.2s) is meaningless,
    // while expected*0.10 (~8s) is the actual "is this close enough to ignore" window.
    //
    // The absolute tolerance alone is insufficient for short expected windows: a 1s recording
    // reporting 0.04s has |diff|=0.96s, inside the 2s floor, even though the durations
    // disagree by 25×. We additionally require `ratio = min/max >= DURATION_MISMATCH_RATIO_MIN`
    // — by construction ratio is always in (0, 1], so the gate is one-sided. A "small absolute
    // diff" only counts as healthy timing when the two durations are also in the same order of
    // magnitude.
    val tolerance = maxOf(DURATION_MISMATCH_TOLERANCE_FLOOR_S, expectedS * DURATION_MISMATCH_TOLERANCE_FRAC)
    val absDiff = kotlin.math.abs(reportedS - expectedS)
    val ratio = minOf(reportedS, expectedS) / maxOf(reportedS, expectedS)
    if (absDiff <= tolerance && ratio >= DURATION_MISMATCH_RATIO_MIN) return baseVf

    val syntheticRate = nbFrames / expectedS
    // Defensive clamp: a pathological input (e.g. nbFrames=10_000 against expectedS=0.1)
    // would produce a rate ffmpeg's setpts filter accepts but renders meaningless output.
    // The upper bound (120 fps) sits above the practical 60-fps capture ceiling with headroom
    // for future high-rate devices; the lower bound (0.005 fps ≈ 1 frame per 3 minutes) is
    // wide enough to admit a static-screen recording that produced a single IDR frame across
    // an 11-second blaze (the CI smoke failure mode). Outside that range, trust the
    // un-re-stamped pipeline rather than write a definitely-broken filter chain. See
    // SYNTHETIC_RATE_MIN / SYNTHETIC_RATE_MAX for the load-bearing constants.
    if (syntheticRate !in SYNTHETIC_RATE_MIN..SYNTHETIC_RATE_MAX) {
      Console.log(
        "[VideoSpriteExtractor] computed synthetic rate ${"%.3f".format(syntheticRate)} fps " +
          "is outside the sane range [$SYNTHETIC_RATE_MIN, $SYNTHETIC_RATE_MAX] " +
          "(nbFrames=$nbFrames expectedS=${"%.3f".format(expectedS)}, file=${videoFile.name}) " +
          "— skipping re-stamp"
      )
      return baseVf
    }
    Console.log(
      "[VideoSpriteExtractor] video duration mismatch: " +
        "reported=${"%.2f".format(reportedS)}s expected=${"%.2f".format(expectedS)}s " +
        "frames=$nbFrames — re-stamping at ${"%.3f".format(syntheticRate)} fps across wall-clock " +
        "(file=${videoFile.name})"
    )
    return "setpts=N/$syntheticRate/TB,$baseVf"
  }

  /**
   * Probes [videoFile] via ffprobe for `ProbedDuration(reportedSeconds, frameCount)`. Reads
   * only container header metadata — no `-count_frames` full-decode pass — so the cost is
   * bounded regardless of video length. Returns [ProbeOutcome.Failure] with a specific reason
   * when ffprobe is unavailable, times out, exits non-zero, or either field is
   * missing/unparseable; logs a single line at each failure so an operator looking at sparse
   * sprite sheets can distinguish "ffprobe failed" from "duration was within tolerance" from
   * "expectedDurationMs wasn't passed."
   *
   * `nb_frames` may be reported as `N/A` for some containers (notably the Android
   * `screenrecord` raw-H.264 stream re-wrapped with `ffmpeg -c copy`, where the container
   * never carries a frame count). When that happens the `[ProbeOutcome.Failure]` detail
   * captures the raw ffprobe output so the failure marker can show exactly what came back.
   */
  private fun probeDurationAndFrameCount(videoFile: File): ProbeOutcome {
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=nb_frames,duration",
            "-of",
            "default=nw=1",
            videoFile.absolutePath,
          ),
        timeoutSeconds = FFPROBE_TIMEOUT_SECONDS,
      )
    if (result == null) {
      val msg =
        "[VideoSpriteExtractor] ffprobe could not be run or timed out for ${videoFile.name} — " +
          "duration check skipped"
      Console.log(msg)
      return ProbeOutcome.Failure(
        reason = "ffprobe-unavailable-or-timeout",
        detail = "ffprobe binary not on PATH or hung past ${FFPROBE_TIMEOUT_SECONDS}s",
      )
    }
    if (result.exitCode != 0) {
      val out = sanitizeSubprocessOutputForLog(result.output.trim())
      Console.log(
        "[VideoSpriteExtractor] ffprobe exited with code ${result.exitCode} for ${videoFile.name}; " +
          "duration check skipped. stdout/stderr:\n$out"
      )
      return ProbeOutcome.Failure(reason = "ffprobe-exit=${result.exitCode}", detail = out)
    }
    var duration: Double? = null
    var nbFrames: Long? = null
    for (line in result.output.lines()) {
      val trimmed = line.trim()
      when {
        trimmed.startsWith("duration=") ->
          duration = parseDoubleOrNull(trimmed.removePrefix("duration=").trim())
        trimmed.startsWith("nb_frames=") ->
          nbFrames = trimmed.removePrefix("nb_frames=").trim().toLongOrNull()
      }
    }
    if (duration == null || nbFrames == null) {
      val out = sanitizeSubprocessOutputForLog(result.output.trim())
      Console.log(
        "[VideoSpriteExtractor] ffprobe output missing duration or nb_frames for " +
          "${videoFile.name}: duration=$duration nbFrames=$nbFrames — duration check skipped. " +
          "Raw output:\n$out"
      )
      return ProbeOutcome.Failure(
        reason = "missing-fields (duration=$duration nb_frames=$nbFrames)",
        detail = out,
      )
    }
    return ProbeOutcome.Success(ProbedDuration(reportedSeconds = duration, frameCount = nbFrames))
  }

  private fun parseDoubleOrNull(s: String): Double? = try {
    java.lang.Double.parseDouble(s)
  } catch (_: NumberFormatException) {
    null
  }

  /**
   * Gets the rotation metadata (in degrees) from a video file using ffprobe. Returns 0 if no
   * rotation metadata is found.
   */
  private fun getVideoRotation(videoFile: File): Int {
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream_side_data=rotation",
            "-of",
            "default=nw=1:nk=1",
            videoFile.absolutePath,
          ),
        timeoutSeconds = FFPROBE_TIMEOUT_SECONDS,
      ) ?: return 0
    if (result.exitCode != 0) return 0
    return result.output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0
  }
}
