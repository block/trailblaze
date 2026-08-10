package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.CoreSimulatorTempFiles
import xyz.block.trailblaze.util.isMacOs

/**
 * Captures iOS Simulator screen video using `xcrun simctl io recordVideo`.
 *
 * Unlike Android's `adb screenrecord`, the simulator has no time limit so no segment chaining is
 * needed. The recording is stopped by sending SIGINT to the process.
 *
 * @param outputFileName the mp4 filename written under the session dir. Defaults to
 *   [CaptureFilenames.VIDEO] (the report's canonical session video). `BaguetteIosVideoCapture`
 *   overrides it to record a `simctl` *remainder* segment (`video.simctl.mp4`) after a mid-session
 *   baguette feed death, so it doesn't clobber the primary baguette segment.
 * @param extractSprite when true (default) `stop` runs [VideoSpriteExtractor] and returns a
 *   `VIDEO_FRAMES` sprite sheet as today. When false it returns the raw mp4 (`VIDEO`) untouched —
 *   `BaguetteIosVideoCapture` needs the raw file to stitch, and sprite-ifies the stitched result
 *   once at the end.
 */
class IosVideoCapture(
  private val outputFileName: String = CaptureFilenames.VIDEO,
  private val extractSprite: Boolean = true,
) : CaptureStream {
  override val type = CaptureType.VIDEO

  private var process: Process? = null
  private var videoFile: File? = null
  private var recordingFile: File? = null
  private var startTimestampMs: Long = 0
  private var isLandscape: Boolean = false

  /** Accumulated (merged) recorder output, filled by the drain thread; read by start-verify/stop. */
  private val processOutput = StringBuilder()

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    if (!isMacOs()) return

    // Clean up any stale recording from a previous session that wasn't stopped cleanly.
    // Without this, xcrun fails with "Host recording is already in progress".
    stopStaleRecording(deviceId)

    val output = File(sessionDir, outputFileName)
    this.videoFile = output
    // Record into a temp file on the boot volume, moved onto [output] at stop. The mp4 is
    // written by CoreSimulator's SimRender process — not this JVM or its simctl child — and
    // SimRender can't write non-boot volumes (e.g. a CI workspace on /Volumes/...); see
    // [CoreSimulatorTempFiles]. The boot-volume guarantee comes from that helper (TMPDIR env,
    // never java.io.tmpdir — embedders redirect the latter back onto the forbidden volume via
    // -Djava.io.tmpdir), and the move at stop runs in this JVM, which owns the session dir.
    // The "video_" prefix keeps [staleRecordingPgrepPattern] matching a crashed temp recorder.
    val recordingTarget = createRecordingTempFile()
    this.recordingFile = recordingTarget

    // Detect simulator orientation before recording starts so we can rotate
    // video frames during sprite sheet generation if needed. iOS simulator
    // recordVideo captures the native portrait pixel buffer, but screenshots
    // are rotated to match the device orientation — this bridges that gap.
    // Only the sprite path reads isLandscape; the raw-remainder mode (extractSprite=false, used by
    // BaguetteIosVideoCapture) never does, so skip the seconds-long screenshot probe there. That
    // matters because the remainder recorder starts from BaguetteIosVideoCapture's feed-death handler
    // while it holds the capture lock — the probe would stall a concurrent stop() for its duration.
    isLandscape = if (extractSprite) detectSimulatorLandscape(deviceId) else false

    try {
      Console.log(
        "Starting iOS video recording: device=$deviceId output=${output.absolutePath} " +
          "(recording via ${recordingTarget.absolutePath}) landscape=$isLandscape"
      )
      // Stamp the recording start as close to the spawn as possible — AFTER the seconds-long
      // orientation probe above. The artifact's start/end window feeds VideoSpriteExtractor's
      // duration sanity-check (`expectedDurationMs`); stamping before the probe inflated the
      // window past the mismatch tolerance, which triggered a constant-rate re-stamp that
      // smears this recorder's healthy variable-frame-rate timestamps across the timeline.
      this.startTimestampMs = System.currentTimeMillis()
      process =
        ProcessBuilder(
            "xcrun",
            "simctl",
            "io",
            deviceId,
            "recordVideo",
            "--codec=h264",
            "--force",
            recordingTarget.absolutePath,
          )
          .redirectErrorStream(true)
          .start()

      // Drain the merged output on a daemon thread for the whole recording so a chatty simctl can't
      // fill the ~64KB OS pipe buffer and stall/wedge the recorder on a long session (mirrors
      // WallClockMp4MuxConsumer's stderr drainer). start-verify and stop read the accumulated buffer
      // rather than the live stream.
      process?.let { drainProcessOutput(it) }

      // Verify the recording actually started. xcrun exits immediately with an error
      // if recording can't start (e.g., "Host recording is already in progress").
      // simctl writes "Recording started" to stderr once the first frame is processed.
      Thread.sleep(RECORDING_START_VERIFY_MS)
      if (process?.isAlive != true) {
        val errorOutput = drainedOutput()
        Console.log(
          "iOS video recording failed to start: exitCode=${process?.exitValue()}, output=$errorOutput"
        )
        process = null
        recordingTarget.delete()
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
   *
   * Only Trailblaze's own recorders are targeted: the pattern matches any Trailblaze video file
   * (`.../video*.mp4` — `video.mp4`, `video.simctl.mp4`), not just this instance's `outputFileName`,
   * so a crashed complementary recorder is cleaned too. This matters because Trailblaze runs two
   * simctl video names on iOS — the whole-session `video.mp4` and the mid-session remainder
   * `video.simctl.mp4` (see `BaguetteIosVideoCapture`) — and the simulator has a single recording
   * lock: a stale process of *either* name blocks a fresh recording of *either* name, so matching
   * only this instance's name would leave the other's lock held. A deliberate recording started by
   * someone else (e.g. a CI shard's `simctl io ... recordVideo logs/simulator_recording.mp4`) still
   * doesn't match (its basename isn't `video*.mp4`) and is left alone; when it holds the device our
   * own start fails fast with "Host recording is already in progress" and the session falls back to
   * the screenshot timeline instead of killing theirs.
   */
  private fun stopStaleRecording(deviceId: String) {
    try {
      val pgrep =
        ProcessBuilder("pgrep", "-f", staleRecordingPgrepPattern(deviceId))
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
      // Written by CoreSimulator, not this JVM — must be boot-volume (see CoreSimulatorTempFiles).
      val tempFile = CoreSimulatorTempFiles.createTempFile("tb_orient_", ".png")
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

  /** Drains the merged output of [proc] into [processOutput] on a daemon thread until EOF. */
  private fun drainProcessOutput(proc: Process) {
    Thread(
      {
        try {
          proc.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
              synchronized(processOutput) {
                processOutput.appendLine(line)
                // Bound the buffer: only a short tail is ever read for a diagnostic line, but a long
                // session's simctl output would otherwise grow it without limit. Keep the last chunk.
                if (processOutput.length > MAX_PROCESS_OUTPUT_CHARS) {
                  processOutput.delete(0, processOutput.length - MAX_PROCESS_OUTPUT_CHARS)
                }
              }
            }
          }
        } catch (_: Exception) {
          // Expected when the process is force-killed mid-read.
        }
      },
      "ios-video-recording-drain",
    ).apply {
      isDaemon = true
      start()
    }
  }

  /** Snapshot of the accumulated recorder output for a diagnostic log line. */
  private fun drainedOutput(): String = synchronized(processOutput) { processOutput.toString().trim() }

  companion object {
    /**
     * `pgrep -f` pattern matching any Trailblaze `simctl recordVideo` for [deviceId] — any
     * `.../video<anything>.mp4` basename, so both `video.mp4` and the `video.simctl.mp4` remainder
     * match, while a non-Trailblaze basename (e.g. a CI shard's `logs/simulator_recording.mp4`)
     * doesn't. `[^/]*` keeps the wildcard within a single path component. Extracted (with
     * [matchesStaleTrailblazeRecording]) so the discrimination is unit-testable without pgrep.
     */
    internal fun staleRecordingPgrepPattern(deviceId: String): String =
      "simctl io $deviceId recordVideo .*/video[^/]*\\.mp4"

    /**
     * The recording temp target — boot-volume via [CoreSimulatorTempFiles] because SimRender is
     * the writer (see the comment in [start]). Extracted so a unit test can pin that the created
     * path still matches [staleRecordingPgrepPattern].
     */
    internal fun createRecordingTempFile(): File =
      // deleteOnExit is the SIGTERM-timeout safety net: every in-JVM path already deletes the
      // temp recording, but a JVM killed mid-session would otherwise leak it into the
      // host-global temp dir (which, unlike the old workspace tmp, CI teardown never wipes).
      CoreSimulatorTempFiles.createTempFile("video_", ".mp4").apply { deleteOnExit() }

    /** True when [commandLine] would be matched by [staleRecordingPgrepPattern] (pgrep -f is unanchored). */
    internal fun matchesStaleTrailblazeRecording(deviceId: String, commandLine: String): Boolean =
      Regex(staleRecordingPgrepPattern(deviceId)).containsMatchIn(commandLine)

    /** Cap on the retained recorder-output tail — enough for a diagnostic line, bounded for a long session. */
    private const val MAX_PROCESS_OUTPUT_CHARS = 8192

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
      recordingFile?.delete()
      return null
    }
    val file = videoFile ?: return null
    val recording = recordingFile ?: return null

    // Capture the recording-end wall-clock BEFORE the SIGINT + waitFor block — the simulator
    // can take seconds to flush its moov atom after SIGINT, and the artifact's endTimestampMs
    // should reflect "when the user stopped recording" not "when xcrun finalized the file"
    // (parallel to the Android `cons.stop()` reordering — both fix wall-clock skew the report
    // viewer would otherwise inherit through `expectedDurationMs`).
    val endTimestampMs = System.currentTimeMillis()

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
        Console.log("iOS recording stopped: exitCode=${proc.exitValue()}, output=${drainedOutput()}")
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

    // Move the finalized temp recording onto the session-dir target (see start() for why the
    // recording lands in the temp dir first). This JVM owns the session dir, so the move
    // succeeds even where SimRender couldn't write the recording there directly.
    if (recording.exists() && recording.length() > 0L) {
      try {
        Files.move(recording.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
      } catch (e: Exception) {
        Console.log("Failed to move iOS recording into session dir: ${e.message}")
      }
    }
    recording.delete()

    if (!file.exists() || file.length() == 0L) {
      Console.log("iOS video recording produced no output: exists=${file.exists()}, length=${if (file.exists()) file.length() else -1}, path=${file.absolutePath}")
      return null
    }

    // Raw-mp4 mode: hand back the untouched recording. BaguetteIosVideoCapture uses this for a
    // simctl *remainder* segment it will stitch and sprite-ify itself.
    if (!extractSprite) {
      return CaptureArtifact(
        file = file,
        type = CaptureType.VIDEO,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }

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
        // The simulator's recordVideo writes a properly-timestamped but variable-frame-rate
        // mp4 — a run that ends on a static screen reports a container that stops at the last
        // change, and that static tail can span most of the session. The trusted flag + the
        // wall-clock window let the extractor tail-pad that shortfall (cloning the last frame,
        // keeping the healthy native timestamps) instead of guessing a constant frame rate.
        expectedDurationMs = endTimestampMs - startTimestampMs,
        vfrTimestampsTrusted = true,
      )
    if (spriteSheet != null) {
      return CaptureArtifact(
        file = spriteSheet,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }

    // If the sprite extractor flagged the mp4 as broken-beyond-recovery (a truncated mp4
    // or bad moov atom would be the iOS analogue of the Android raw-H.264-wrap pathology),
    // skip the VIDEO fallback so report-generation doesn't re-process the same broken file.
    if (VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(file.parentFile, "IosVideoCapture")) {
      return null
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
