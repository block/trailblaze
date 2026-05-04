package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Captures device screen video using `adb screenrecord`.
 *
 * The video is recorded on the device at [DEVICE_VIDEO_DIR] and pulled to the session directory
 * when stopped. The recording uses 720p resolution to keep file sizes reasonable (~2-4MB per
 * minute).
 *
 * ### Limitations
 * - `adb screenrecord` has a 3-minute max per invocation. For longer sessions, this implementation
 *   chains recordings automatically.
 * - Not available on all emulator images (requires API 19+).
 * - Emulator GPU settings may affect recording availability.
 */
class AndroidVideoCapture : CaptureStream {
  override val type = CaptureType.VIDEO

  private var streamHandle: AutoCloseable? = null
  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var startTimestampMs: Long = 0
  private var chainThread: Thread? = null
  @Volatile private var stopRequested = false
  private var isLandscape: Boolean = false
  private var videoSize: String = VIDEO_SIZE_FALLBACK

  // Collected segment files on device
  private val deviceSegments = mutableListOf<String>()

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.startTimestampMs = DeviceClock.nowMs(deviceId)
    stopRequested = false

    // Query actual device dimensions for accurate recording size.
    // Scales to ~720p on the short side while preserving the device's real aspect ratio,
    // so tablets, foldables, and non-16:9 devices all record correctly.
    val dims = getDeviceDisplaySize(deviceId)
    if (dims != null) {
      isLandscape = dims.first > dims.second
      videoSize = scaleToRecordingSize(dims.first, dims.second)
    } else {
      videoSize = VIDEO_SIZE_FALLBACK
    }
    Console.log("Android video recording: deviceSize=${dims ?: "unknown"}, videoSize=$videoSize, landscape=$isLandscape")

    startSegment(0)

    // Chain recordings for sessions longer than 3 minutes
    chainThread =
      Thread(
          {
            try {
              var segmentIndex = 1
              while (!stopRequested) {
                Thread.sleep(CHAIN_INTERVAL_MS)
                if (stopRequested) break
                // Start a new segment before the current one hits the 3-min limit
                Console.log("Chaining video recording segment $segmentIndex...")
                startSegment(segmentIndex)
                segmentIndex++
              }
            } catch (_: InterruptedException) {
              // Expected when stop() interrupts the sleep — exit gracefully
            }
          },
          "video-capture-chain",
        )
        .apply {
          isDaemon = true
          start()
        }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    stopRequested = true
    chainThread?.interrupt()
    chainThread = null

    val dev = deviceId ?: return null
    val dir = sessionDir ?: return null

    val trailblazeDeviceId = TrailblazeDeviceId(dev, TrailblazeDevicePlatform.ANDROID)

    // Stop screenrecord on device
    try {
      AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = trailblazeDeviceId,
        args = listOf("pkill", "-INT", "screenrecord"),
      )
      // Give screenrecord time to finalize the MP4
      Thread.sleep(1500)
    } catch (e: Exception) {
      Console.log("Error stopping screenrecord: ${e.message}")
    }

    // Also tear down our local stream handle
    streamHandle?.let { runCatching { it.close() } }
    streamHandle = null

    // Snapshot segments under the lock to avoid ConcurrentModificationException
    // from the chaining thread calling startSegment() concurrently.
    val segmentsSnapshot = synchronized(deviceSegments) { deviceSegments.toList() }

    // Pull all segments from device
    Console.log("Pulling ${deviceSegments.size} video segment(s) from device $dev...")
    val localFiles = mutableListOf<File>()
    for (segment in segmentsSnapshot) {
      val localFile = File(dir, File(segment).name)
      try {
        val pulled = AndroidHostAdbUtils.pullFile(
          deviceId = trailblazeDeviceId,
          remotePath = segment,
          localFile = localFile,
        )
        if (pulled && localFile.exists() && localFile.length() > 0) {
          Console.log("Pulled $segment -> ${localFile.name} (${localFile.length() / 1024}KB)")
          localFiles.add(localFile)
        } else {
          Console.log("Video segment empty or missing after pull: $segment")
        }
      } catch (e: Exception) {
        Console.log("Failed to pull video segment $segment: ${e.message}")
      }
    }

    // Clean up device files
    for (segment in segmentsSnapshot) {
      try {
        AndroidHostAdbUtils.execAdbShellCommand(
          deviceId = trailblazeDeviceId,
          args = listOf("rm", "-f", segment),
        )
      } catch (_: Exception) {}
    }
    deviceSegments.clear()

    if (localFiles.isEmpty()) {
      Console.log("Android video capture: no video segments recovered from device")
      return null
    }

    // Merge into a single video.mp4 — if there are multiple segments, concatenate
    // them so the full session is available as one file.
    val videoFile =
      if (localFiles.size == 1) {
        val target = File(dir, "video.mp4")
        if (!localFiles[0].renameTo(target)) {
          // renameTo can fail (cross-filesystem, permissions); fall back to original file
          localFiles[0]
        } else {
          target
        }
      } else {
        mergeSegments(localFiles, dir) ?: localFiles[0]
      }

    val endTimestampMs = DeviceClock.nowMs(dev)

    // Generate a WebP sprite sheet from the video.
    // If ffmpeg is available, this replaces the full video with a compact sprite image.
    // If not, fall back to keeping the original video.
    val spriteSheet =
      VideoSpriteExtractor.generateSpriteSheet(
        videoFile,
        fps = options.spriteFrameFps,
        frameHeight = options.spriteFrameHeight,
        webpQuality = options.spriteQuality,
        isLandscape = isLandscape,
      )
    if (spriteSheet != null) {
      return CaptureArtifact(
        file = spriteSheet,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }

    // Fallback: keep original video (no ffmpeg available)
    return CaptureArtifact(
      file = videoFile,
      type = CaptureType.VIDEO,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /**
   * Queries the Android device's current display size via `adb shell wm size`.
   * Returns (width, height) reflecting the current orientation, or null if the query fails.
   */
  private fun getDeviceDisplaySize(deviceId: String): Pair<Int, Int>? {
    return try {
      val output = AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
        args = listOf("wm", "size"),
      ).trim()
      // Output format: "Physical size: 1080x1920" or "Override size: ..."
      // Use the last line (override takes precedence if present)
      val lastLine = output.lines().lastOrNull { it.contains("size:") } ?: return null
      val match = Regex("(\\d+)x(\\d+)").find(lastLine) ?: return null
      val w = match.groupValues[1].toIntOrNull() ?: return null
      val h = match.groupValues[2].toIntOrNull() ?: return null
      Pair(w, h)
    } catch (_: Exception) {
      null
    }
  }

  private fun startSegment(index: Int) {
    val dev = deviceId ?: return
    val devicePath = "$DEVICE_VIDEO_DIR/video_${String.format("%03d", index)}.mp4"

    synchronized(deviceSegments) { deviceSegments.add(devicePath) }

    val trailblazeDeviceId = TrailblazeDeviceId(dev, TrailblazeDevicePlatform.ANDROID)

    // Gracefully stop any previous recording so the MP4 container is finalized.
    // Closing the streaming handle alone would close the wire stream but adbd may not propagate
    // SIGINT to screenrecord — explicit pkill -INT is what produces a clean MP4 trailer.
    try {
      AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = trailblazeDeviceId,
        args = listOf("pkill", "-INT", "screenrecord"),
      )
      Thread.sleep(500) // Brief wait for MP4 finalization
    } catch (_: Exception) {}
    streamHandle?.let { runCatching { it.close() } }
    streamHandle = null

    try {
      val command = "screenrecord --size $videoSize --bit-rate $BIT_RATE $devicePath"
      streamHandle = AndroidHostAdbUtils.streamingShell(
        deviceId = trailblazeDeviceId,
        command = command,
        onLine = { /* screenrecord is silent on stdout; ignore */ },
      )
    } catch (e: Exception) {
      Console.log("Failed to start screenrecord segment $index: ${e.message}")
    }
  }

  /**
   * Re-encodes video with every frame as a keyframe (all-intra) so the player can seek to any
   * frame. Falls back to the original file if ffmpeg is not installed.
   */
  private fun reencodeAllKeyframes(input: File): File {
    val output = File(input.parent, "video_scrub.mp4")
    try {
      Console.log("Re-encoding video for frame-accurate scrubbing...")
      val process =
        ProcessBuilder(
            "ffmpeg",
            "-i",
            input.absolutePath,
            "-c:v",
            "libx264",
            "-g",
            "1",
            "-keyint_min",
            "1",
            "-preset",
            "ultrafast",
            "-crf",
            "18",
            "-an",
            "-y",
            output.absolutePath,
          )
          .redirectErrorStream(true)
          .start()
      // Drain output to prevent blocking
      process.inputStream.bufferedReader().readText()
      val finished = process.waitFor(60, TimeUnit.SECONDS)
      if (!finished) process.destroyForcibly()
      if (finished && process.exitValue() == 0 && output.exists() && output.length() > 0) {
        if (input.delete() && output.renameTo(input)) {
          Console.log("Video re-encoded for scrubbing (${input.length() / 1024}KB)")
          return input
        }
        // Rename failed — return whichever file exists
        Console.log("Re-encode succeeded but rename failed, using output file directly")
        return if (output.exists()) output else input
      }
      Console.log(
        "ffmpeg re-encode failed (exit=${if (finished) process.exitValue() else "timeout"}), using original"
      )
      output.delete()
    } catch (e: Exception) {
      Console.log("ffmpeg not available, skipping re-encode: ${e.message}")
      output.delete()
    }
    return input
  }

  /**
   * Merges multiple MP4 segments into a single video.mp4 using ffmpeg's concat demuxer.
   * Returns the merged file, or null if ffmpeg is unavailable or the merge fails.
   */
  private fun mergeSegments(segments: List<File>, dir: File): File? {
    val listFile = File(dir, "segments.txt")
    val merged = File(dir, "video.mp4")
    return try {
      // Write concat list
      listFile.writeText(segments.joinToString("\n") { "file '${it.absolutePath}'" })
      val process =
        ProcessBuilder(
            "ffmpeg", "-f", "concat", "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy", "-y", merged.absolutePath,
          )
          .redirectErrorStream(true)
          .start()
      process.inputStream.bufferedReader().readText()
      val finished = process.waitFor(120, TimeUnit.SECONDS)
      if (!finished) process.destroyForcibly()
      if (finished && process.exitValue() == 0 && merged.exists() && merged.length() > 0) {
        segments.forEach { it.delete() }
        Console.log("Merged ${segments.size} video segments into ${merged.name}")
        merged
      } else {
        Console.log("ffmpeg segment merge failed, using first segment only")
        merged.delete()
        null
      }
    } catch (e: Exception) {
      Console.log("ffmpeg not available for segment merge: ${e.message}")
      null
    } finally {
      listFile.delete()
    }
  }

  companion object {
    private const val DEVICE_VIDEO_DIR = "/sdcard"
    /** Fallback when we can't query the device's display size. */
    private const val VIDEO_SIZE_FALLBACK = "720x1280"
    /** Target for the short side when scaling down for recording. */
    private const val TARGET_SHORT_SIDE = 720
    private const val BIT_RATE = "4000000" // 4Mbps
    // Chain 10 seconds before the 3-minute limit
    private const val CHAIN_INTERVAL_MS = 170_000L

    /**
     * Scales the device's real display dimensions down so the short side is ~[TARGET_SHORT_SIDE]px.
     * Preserves the device's actual aspect ratio. Both dimensions are rounded to even numbers
     * (required by most video codecs). If the device is already at or below target, uses the
     * original dimensions.
     */
    fun scaleToRecordingSize(deviceWidth: Int, deviceHeight: Int): String {
      val shortSide = minOf(deviceWidth, deviceHeight)
      if (shortSide <= TARGET_SHORT_SIDE) return "${deviceWidth}x${deviceHeight}"
      val scale = TARGET_SHORT_SIDE.toDouble() / shortSide
      val w = (deviceWidth * scale).toInt().let { it - it % 2 } // round to even
      val h = (deviceHeight * scale).toInt().let { it - it % 2 }
      return "${w}x${h}"
    }
  }
}
