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
   * Sane bounds for the synthetic rate computed in `maybeRestamp`. 60 fps is the practical
   * ceiling for any of the three capture backends today; doubling to 120 fps leaves headroom
   * for future high-rate devices. The 0.1 fps lower bound is below any sensible capture rate.
   * Outside this range, we treat the inputs as broken (e.g., near-zero `expectedDurationMs`
   * combined with a non-trivial frame count) and skip the re-stamp rather than emit a filter
   * chain that ffmpeg would render meaninglessly.
   */
  private const val SYNTHETIC_RATE_MIN = 0.1
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
    try {
      val originalKB = videoFile.length() / 1024
      Console.log(
        "Generating sprite sheet from video (${originalKB}KB) at ${fps}fps, ${frameHeight}p..."
      )

      val tempDir = java.nio.file.Files.createTempDirectory("trailblaze_sprites_").toFile()
      try {
        // Step 1: Extract individual frames as PNG (lossless intermediary for dedup accuracy).
        val (baseVf, noAutorotate) = buildVideoFilter(videoFile, fps, frameHeight, isLandscape)
        // If the caller passed an expected wall-clock duration, validate the input mp4's
        // self-reported duration against it. When the two disagree (Android raw-H.264 wrap
        // producing a 2-second mp4 for an 80-second recording is the canonical case), prepend
        // a `setpts=N/<rate>/TB` filter that re-stamps each frame at a constant synthetic rate
        // chosen so the captured frames spread uniformly across the expected window. The
        // `fps=<sprite>` filter further down the chain then resamples from that re-stamped
        // timeline. For correctly-timestamped mp4s the prepended filter is omitted entirely.
        val vf = maybeRestamp(videoFile, baseVf, expectedDurationMs)
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
              "videoSize=${videoFile.length()}B fps=$fps frameHeight=$frameHeight isLandscape=$isLandscape" +
              if (extractResult != null && extractResult.output.isNotBlank())
                "\nstdout/stderr:\n${sanitizeSubprocessOutputForLog(extractResult.output.trim())}"
              else ""
          Console.log(reason)
          writeFailureMarker(dir, reason)
          return null
        }

        val frameFiles =
          tempDir.listFiles { _, name -> name.startsWith("vf_") && name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        if (frameFiles.isEmpty()) {
          Console.log("No frames extracted from video")
          writeFailureMarker(
            dir,
            "ffmpeg ran (exit=0) but produced 0 frames\n" +
              "videoSize=${videoFile.length()}B fps=$fps frameHeight=$frameHeight isLandscape=$isLandscape\n" +
              "Likely cause: video too short or unreadable at the requested fps.\n" +
              "ffmpeg stdout/stderr:\n${sanitizeSubprocessOutputForLog(extractResult.output.trim())}",
          )
          return null
        }

        val frameCount = frameFiles.size

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
   *  - ffprobe can't read both `duration` and `nb_frames` from the input,
   *  - the reported duration is within the mismatch tolerance.
   *
   * Logs a single line via `Console.log` whenever a re-stamp is applied so the underlying
   * recording bug is visible in CI artifacts without spamming healthy runs.
   */
  private fun maybeRestamp(videoFile: File, baseVf: String, expectedDurationMs: Long?): String {
    if (expectedDurationMs == null || expectedDurationMs <= 0L) return baseVf
    val expectedS = expectedDurationMs / 1000.0
    val probe = probeDurationAndFrameCount(videoFile) ?: return baseVf
    val (reportedS, nbFrames) = probe
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
    val tolerance = maxOf(DURATION_MISMATCH_TOLERANCE_FLOOR_S, expectedS * DURATION_MISMATCH_TOLERANCE_FRAC)
    if (kotlin.math.abs(reportedS - expectedS) <= tolerance) return baseVf

    val syntheticRate = nbFrames / expectedS
    // Defensive clamp: a pathological input (e.g. nbFrames=10_000 against expectedS=0.1)
    // would produce a rate ffmpeg's setpts filter accepts but renders meaningless output.
    // 1000 fps is well above any realistic capture rate (60/120 fps is the practical ceiling)
    // and 0.1 fps is below any sane lower bound; outside that range, trust the un-re-stamped
    // pipeline rather than write a definitely-broken filter chain.
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
   * Probes [videoFile] via ffprobe for `(durationSeconds, nb_frames)`. Reads only container
   * header metadata — no `-count_frames` full-decode pass — so the cost is bounded regardless
   * of video length. Returns null if ffprobe is unavailable, times out, exits non-zero, or
   * either field is missing/unparseable; logs a single line at each null-return so an operator
   * looking at sparse sprite sheets can distinguish "ffprobe failed" from "duration was within
   * tolerance" from "expectedDurationMs wasn't passed."
   *
   * `nb_frames` may be reported as `N/A` for some containers; we treat that case as "can't
   * validate" and return null so the caller falls back to the un-re-stamped pipeline.
   */
  private fun probeDurationAndFrameCount(videoFile: File): Pair<Double, Long>? {
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
      Console.log(
        "[VideoSpriteExtractor] ffprobe could not be run or timed out for ${videoFile.name} — " +
          "duration check skipped"
      )
      return null
    }
    if (result.exitCode != 0) {
      Console.log(
        "[VideoSpriteExtractor] ffprobe exited with code ${result.exitCode} for ${videoFile.name}; " +
          "duration check skipped. stdout/stderr:\n${sanitizeSubprocessOutputForLog(result.output.trim())}"
      )
      return null
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
      Console.log(
        "[VideoSpriteExtractor] ffprobe output missing duration or nb_frames for " +
          "${videoFile.name}: duration=$duration nbFrames=$nbFrames — duration check skipped. " +
          "Raw output:\n${sanitizeSubprocessOutputForLog(result.output.trim())}"
      )
      return null
    }
    return Pair(duration, nbFrames)
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
