package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs

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
    if (!isMacOs()) return

    // Clean up any stale recording from a previous session that wasn't stopped cleanly.
    // Without this, xcrun fails with "Host recording is already in progress".
    stopStaleRecording(deviceId)

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

      // Verify the recording actually started. xcrun exits immediately with an error
      // if recording can't start (e.g., "Host recording is already in progress").
      // simctl writes "Recording started" to stderr once the first frame is processed.
      Thread.sleep(RECORDING_START_VERIFY_MS)
      if (process?.isAlive != true) {
        val errorOutput =
          try {
            process?.inputStream?.bufferedReader()?.readText()?.trim() ?: ""
          } catch (_: Exception) {
            ""
          }
        Console.log(
          "iOS video recording failed to start: exitCode=${process?.exitValue()}, output=$errorOutput"
        )
        process = null
      } else {
        Console.log("iOS video recording process started (pid=${process?.pid()})")
      }
    } catch (e: Exception) {
      Console.log("Failed to start iOS video recording: ${e.message}")
    }
  }

  /**
   * Attempts to stop any stale recording on this simulator from a previous session. This can happen
   * when a previous recording process was killed without clean SIGINT shutdown (e.g.,
   * destroyForcibly on cancellation), leaving the simulator's internal recording lock held.
   */
  private fun stopStaleRecording(deviceId: String) {
    try {
      val pgrep =
        ProcessBuilder("pgrep", "-f", "simctl io $deviceId recordVideo")
          .redirectErrorStream(true)
          .start()
      val pids = pgrep.inputStream.bufferedReader().readText().trim()
      pgrep.waitFor(5, TimeUnit.SECONDS)

      if (pids.isNotBlank()) {
        for (pid in pids.lines().filter { it.isNotBlank() }) {
          try {
            Console.log("Sending SIGINT to stale recording process $pid")
            ProcessBuilder("kill", "-INT", pid.trim())
              .redirectErrorStream(true)
              .start()
              .waitFor(5, TimeUnit.SECONDS)
          } catch (_: Exception) {}
        }
        // Wait for the simulator to release the recording lock
        Thread.sleep(STALE_CLEANUP_WAIT_MS)
        Console.log("Cleaned up stale recording process(es)")
      }
    } catch (_: Exception) {}
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

  companion object {
    /** Time to wait after starting xcrun to verify the process is still alive. */
    private const val RECORDING_START_VERIFY_MS = 1000L
    /** Time to wait after killing stale processes for the simulator to release its lock. */
    private const val STALE_CLEANUP_WAIT_MS = 1000L
    /** Seconds to wait for xcrun to finalize the MP4 after SIGINT. */
    private const val STOP_TIMEOUT_SECONDS = 10L
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
      // Wait for the process to finalize the MP4.
      // Avoid destroyForcibly() — force-killing leaves the simulator's internal
      // recording lock held, causing all subsequent recordings to fail with
      // "Host recording is already in progress". A second SIGINT is safe.
      val finished = proc.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) {
        Console.log("iOS recording process did not exit within ${STOP_TIMEOUT_SECONDS}s, sending second SIGINT")
        ProcessBuilder("kill", "-INT", pid.toString()).redirectErrorStream(true).start().waitFor()
        val finishedRetry = proc.waitFor(5, TimeUnit.SECONDS)
        if (!finishedRetry) {
          Console.log("iOS recording process still alive after second SIGINT, force-killing (may leave stale lock)")
          proc.destroyForcibly()
        }
      } else {
        // Drain process output for diagnostics (safe since process has exited)
        val output = try { proc.inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
        Console.log("iOS recording stopped: exitCode=${proc.exitValue()}, output=$output")
      }
    } catch (e: Exception) {
      Console.log("Error stopping iOS video recording: ${e.message}")
      // Send SIGINT rather than destroyForcibly to give the simulator a chance to
      // release the recording lock cleanly.
      try {
        ProcessBuilder("kill", "-INT", proc.pid().toString())
          .redirectErrorStream(true)
          .start()
          .waitFor(5, TimeUnit.SECONDS)
      } catch (_: Exception) {
        proc.destroyForcibly()
      }
    }

    process = null

    if (!file.exists() || file.length() == 0L) {
      Console.log("iOS video recording produced no output: exists=${file.exists()}, length=${if (file.exists()) file.length() else -1}, path=${file.absolutePath}")
      return null
    }

    val endTimestampMs = System.currentTimeMillis()

    // Generate a WebP sprite sheet from the video.
    // If ffmpeg is available, this replaces the full video with a compact sprite image.
    // If not, fall back to keeping the original video.
    val spriteSheet =
      VideoSpriteExtractor.generateSpriteSheet(
        file,
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
      file = file,
      type = CaptureType.VIDEO,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }
}
