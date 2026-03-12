package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.util.Console

/**
 * Extracts frames from a video and assembles them into a single sprite sheet JPEG.
 *
 * **Deduplication**: Near-identical frames (common when the UI is static) are detected via
 * pixel-level comparison with a small perceptual threshold ([DEDUP_PIXEL_THRESHOLD]) and
 * stored only once in the sprite grid. A `frameMap` in the metadata maps each logical frame
 * index to its physical position in the grid.
 *
 * Unique frames are arranged in a grid: multiple columns of stacked rows, laid out
 * left-to-right, top-to-bottom. Physical frame N is at column `N / rows` and row `N % rows`.
 * This avoids the JPEG 65500px dimension limit that a single tall column would hit.
 *
 * A companion metadata file (`video_sprites.txt`) records the layout so consumers can index
 * into the image:
 * ```
 * fps=2
 * frames=120
 * height=720
 * columns=2
 * rows=30
 * uniqueFrames=60
 * frameMap=0,0,1,2,2,3,...
 * ```
 *
 * This produces a single file that is trivially loadable in Compose, WASM, and desktop
 * environments — no video codec or media player required.
 */
object VideoSpriteExtractor {

  const val SPRITE_FILENAME = "video_sprites.jpg"
  const val SPRITE_META_FILENAME = "video_sprites.txt"

  /** JPEG encoders cap at 65500px per dimension. */
  private const val MAX_JPEG_DIMENSION = 65500

  /**
   * Maximum average pixel difference (per channel, 0–255) for two frames to be considered
   * identical. JPEG re-encoding can introduce tiny noise even for visually identical frames.
   * A threshold of 1.0 catches codec noise while still distinguishing real UI changes.
   */
  private const val DEDUP_PIXEL_THRESHOLD = 1.0

  /**
   * Generates a sprite sheet from [videoFile]. Returns the sprite sheet JPEG file, or null
   * if ffmpeg is unavailable or the extraction fails.
   *
   * @param fps Frames per second to extract (default: 2)
   * @param frameHeight Height in pixels for each frame (default: 360)
   * @param jpegQuality ffmpeg `-q:v` value — lower is better quality (default: 5)
   * @param isLandscape Whether the device was in landscape orientation during recording.
   *   When true and the video is in portrait orientation (common with iOS simulator's native
   *   pixel buffer), a transpose filter is applied to rotate frames to landscape.
   */
  fun generateSpriteSheet(
    videoFile: File,
    fps: Int = CaptureOptions.DEFAULT_SPRITE_FPS,
    frameHeight: Int = CaptureOptions.DEFAULT_SPRITE_HEIGHT,
    jpegQuality: Int = CaptureOptions.DEFAULT_SPRITE_JPEG_QUALITY,
    isLandscape: Boolean = false,
  ): File? {
    val dir = videoFile.parentFile ?: return null
    val spriteFile = File(dir, SPRITE_FILENAME)
    try {
      val originalKB = videoFile.length() / 1024
      Console.log("Generating sprite sheet from video (${originalKB}KB) at ${fps}fps, ${frameHeight}p...")

      val tempDir = java.nio.file.Files.createTempDirectory("trailblaze_sprites_").toFile()
      try {
        // Step 1: Extract individual frames.
        // If the device was in landscape but the video was recorded in portrait
        // (iOS simulator native buffer), prepend a transpose filter to rotate frames.
        val (vf, noAutorotate) = buildVideoFilter(videoFile, fps, frameHeight, isLandscape)
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
            "-q:v",
            jpegQuality.toString(),
            "-loglevel",
            "error",
            "${tempDir.absolutePath}/vf_%06d.jpg",
          )
        )
        val extractProcess = ProcessBuilder(extractCmd).redirectErrorStream(true).start()
        extractProcess.inputStream.bufferedReader().readText()
        val extractFinished = extractProcess.waitFor(120, TimeUnit.SECONDS)
        if (!extractFinished || extractProcess.exitValue() != 0) {
          Console.log("ffmpeg frame extraction failed")
          return null
        }

        val frameFiles =
          tempDir.listFiles { _, name -> name.startsWith("vf_") && name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: emptyList()
        if (frameFiles.isEmpty()) {
          Console.log("No frames extracted from video")
          return null
        }

        val frameCount = frameFiles.size

        // Step 2: Deduplicate identical (or near-identical) frames.
        // Mobile UIs often sit still, so many consecutive frames are identical.
        // We compare pixel data with a small threshold to absorb JPEG codec noise.
        val frameMap = IntArray(frameCount) // logical frame index -> physical (unique) index
        val uniqueFrameFiles = mutableListOf<File>()
        val uniquePixels = mutableListOf<IntArray>() // RGB pixel arrays for each unique frame

        for ((logicalIndex, file) in frameFiles.withIndex()) {
          val image = ImageIO.read(file)
          val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

          // Compare against recent unique frames to find a perceptual match.
          // Duplicates are almost always consecutive (static UI), so we only check the last
          // few unique frames to keep this O(n) overall instead of O(n^2).
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
            "Frame dedup: $uniqueCount unique frames from $frameCount total (${dedupSavings} duplicates removed)"
          )
        }

        // Copy unique frames with sequential names for ffmpeg tile input
        val tileDir = java.nio.file.Files.createTempDirectory("trailblaze_tile_").toFile()
        try {
          for ((index, file) in uniqueFrameFiles.withIndex()) {
            file.copyTo(File(tileDir, "uf_%06d.jpg".format(index + 1)))
          }

          // Calculate grid layout: enough rows per column to stay under JPEG max height,
          // then as many columns as needed to fit all frames.
          val maxRows = MAX_JPEG_DIMENSION / frameHeight
          val columns = ((uniqueCount + maxRows - 1) / maxRows).coerceAtLeast(1)
          val rows = ((uniqueCount + columns - 1) / columns).coerceAtLeast(1)

          // Step 3: Arrange unique frames into a grid sprite sheet using ffmpeg tile filter
          val tileProcess =
            ProcessBuilder(
                "ffmpeg",
                "-f",
                "image2",
                "-framerate",
                "1",
                "-i",
                "${tileDir.absolutePath}/uf_%06d.jpg",
                "-vf",
                "tile=${columns}x${rows}",
                "-q:v",
                jpegQuality.toString(),
                "-frames:v",
                "1",
                "-loglevel",
                "error",
                "-y",
                spriteFile.absolutePath,
              )
              .redirectErrorStream(true)
              .start()
          tileProcess.inputStream.bufferedReader().readText()
          val tileFinished = tileProcess.waitFor(120, TimeUnit.SECONDS)
          if (!tileFinished || tileProcess.exitValue() != 0 || !spriteFile.exists()) {
            Console.log("ffmpeg sprite sheet assembly failed")
            spriteFile.delete()
            return null
          }

          val spriteKB = spriteFile.length() / 1024
          Console.log(
            "Sprite sheet: $uniqueCount unique frames from $frameCount total " +
              "(${columns}x${rows} grid), ${spriteKB}KB (from ${originalKB}KB video)"
          )

          // Write metadata so consumers know how to index into the sprite.
          // `frames` = total logical frame count, `uniqueFrames` = physical frames in the grid,
          // `frameMap` = comma-separated mapping of logical index -> physical index.
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
              appendLine("frameMap=$frameMapStr")
            }
          )
        } finally {
          tileDir.deleteRecursively()
        }

        return spriteFile
      } finally {
        tempDir.deleteRecursively()
      }
    } catch (e: Exception) {
      Console.log("ffmpeg not available for sprite extraction: ${e.message}")
      spriteFile.delete()
      return null
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
    return try {
      val process =
        ProcessBuilder(
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
          )
          .redirectErrorStream(true)
          .start()
      val output = process.inputStream.bufferedReader().readText().trim()
      process.waitFor(5, TimeUnit.SECONDS)
      val parts = output.split("x")
      if (parts.size == 2) {
        val w = parts[0].trim().toIntOrNull()
        val h = parts[1].trim().toIntOrNull()
        if (w != null && h != null) Pair(w, h) else null
      } else null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Gets the rotation metadata (in degrees) from a video file using ffprobe. Returns 0 if no
   * rotation metadata is found.
   */
  private fun getVideoRotation(videoFile: File): Int {
    return try {
      val process =
        ProcessBuilder(
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
          )
          .redirectErrorStream(true)
          .start()
      val output = process.inputStream.bufferedReader().readText().trim()
      process.waitFor(5, TimeUnit.SECONDS)
      output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0
    } catch (_: Exception) {
      0
    }
  }
}
