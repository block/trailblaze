package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * Captures iOS Simulator screen video using `xcrun simctl io recordVideo`.
 *
 * Unlike Android's `adb screenrecord`, the simulator has no time limit so no segment chaining is
 * needed. The recording is stopped by sending SIGINT to the process.
 */
class IosVideoCapture : CaptureStream {
  override val type = CaptureType.VIDEO

  private var process: Process? = null
  private var videoFile: File? = null
  private var startTimestampMs: Long = 0
  private var isLandscape: Boolean = false

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    val output = File(sessionDir, "video.mp4")
    this.videoFile = output
    this.startTimestampMs = System.currentTimeMillis()

    // Detect simulator orientation before recording starts so we can rotate
    // video frames during sprite sheet generation if needed. iOS simulator
    // recordVideo captures the native portrait pixel buffer, but screenshots
    // are rotated to match the device orientation — this bridges that gap.
    isLandscape = detectSimulatorLandscape(deviceId)

    try {
      Console.log("Starting iOS video recording: device=$deviceId output=${output.absolutePath} landscape=$isLandscape")
      process =
        ProcessBuilder(
            "xcrun",
            "simctl",
            "io",
            deviceId,
            "recordVideo",
            "--codec=h264",
            "--force",
            output.absolutePath,
          )
          .redirectErrorStream(true)
          .start()
      Console.log("iOS video recording process started (pid=${process?.pid()})")
    } catch (e: Exception) {
      Console.log("Failed to start iOS video recording: ${e.message}")
    }
  }

  /**
   * Detects if the iOS simulator is currently in landscape orientation by taking a quick screenshot
   * and comparing its dimensions. This is called before recording starts so we know whether to
   * rotate frames during sprite sheet generation.
   */
  private fun detectSimulatorLandscape(deviceId: String): Boolean {
    try {
      val tempFile = File.createTempFile("tb_orient_", ".png")
      try {
        val proc =
          ProcessBuilder(
              "xcrun",
              "simctl",
              "io",
              deviceId,
              "screenshot",
              "--type=png",
              tempFile.absolutePath,
            )
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(5, TimeUnit.SECONDS)
        if (finished && tempFile.exists() && tempFile.length() > 0) {
          val img = ImageIO.read(tempFile)
          return img != null && img.width > img.height
        }
      } finally {
        tempFile.delete()
      }
    } catch (e: Exception) {
      Console.log("Failed to detect simulator orientation: ${e.message}")
    }
    return false
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val proc = process ?: run {
      Console.log("iOS video capture: process is null — recording never started")
      return null
    }
    val file = videoFile ?: return null

    try {
      // xcrun simctl recordVideo stops cleanly on SIGINT
      val pid = proc.pid()
      val isAlive = proc.isAlive
      Console.log("Stopping iOS video recording (pid=$pid, alive=$isAlive)...")
      ProcessBuilder("kill", "-INT", pid.toString()).redirectErrorStream(true).start().waitFor()
      // Wait for the process to finalize the MP4
      val finished = proc.waitFor(10, TimeUnit.SECONDS)
      if (!finished) {
        Console.log("iOS recording process did not exit within 10s, force-killing")
        proc.destroyForcibly()
      } else {
        // Drain process output for diagnostics (safe since process has exited)
        val output = try { proc.inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
        Console.log("iOS recording stopped: exitCode=${proc.exitValue()}, output=$output")
      }
    } catch (e: Exception) {
      Console.log("Error stopping iOS video recording: ${e.message}")
      proc.destroyForcibly()
    }

    process = null

    if (!file.exists() || file.length() == 0L) {
      Console.log("iOS video recording produced no output: exists=${file.exists()}, length=${if (file.exists()) file.length() else -1}, path=${file.absolutePath}")
      return null
    }

    val endTimestampMs = System.currentTimeMillis()

    // Generate a sprite sheet from the video — one tall JPEG with all frames stacked vertically.
    // If ffmpeg is available, this replaces the full video with a ~3-7MB image.
    // If not, fall back to keeping the original video.
    val spriteSheet =
      VideoSpriteExtractor.generateSpriteSheet(
        file,
        fps = options.spriteFrameFps,
        frameHeight = options.spriteFrameHeight,
        jpegQuality = options.spriteJpegQuality,
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
      file = file,
      type = CaptureType.VIDEO,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }
}
