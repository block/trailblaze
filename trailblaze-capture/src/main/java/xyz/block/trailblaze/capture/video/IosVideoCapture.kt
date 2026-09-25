package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
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
 * simctl itself only writes H.264 in an mp4, at the simulator's full resolution. With
 * [RecordingFormat.WEBM] (the process-wide default on a host with a VP9 encoder) the recording is
 * transcoded at stop into the same VP9 WebM every other platform delivers, downscaled to the ~720px
 * short side Android records at; if that transcode fails the session keeps simctl's mp4 instead of
 * losing its video.
 *
 * ### Clock alignment
 * The recording window is on the **host** clock, like every other video recorder — see "Which
 * clock" on [CaptureArtifact]. iOS gets this for free where Android had to correct for it: the
 * simulator runs on this Mac and reads this Mac's clock, so there is no device-host skew to remove.
 * A physical-device recorder would have to revisit that.
 *
 * Clip time 0 is the moment simctl reported its **first frame**, not the moment we spawned it.
 * simctl takes a few hundred ms to attach to the display and announces "Recording started" once
 * the first frame is processed; anchoring on the spawn instead would claim the recording covers
 * time it has no footage for, and the report — which scales clip time onto this window — would
 * then place every early step ahead of the frame that actually shows it. Measured on a booted
 * simulator: 389 ms of startup, and a file whose duration came up 388 ms short of a spawn-anchored
 * window. See [SimctlRecordingStartWatcher].
 *
 * ### How closely the recording tracks the screen
 * Measured against a page painting its own millisecond clock: a frame at clip time `t` shows the
 * screen as it was **~13 ms** before `startTimestampMs + t`, with no drift across a 10 s recording
 * (the same 13 ms at 0.05 s in and at 9 s in). That is far tighter than Android's 60–100 ms,
 * because nothing leaves the host — there is no device encoder and no adb hop.
 *
 * @param format the container the recording is delivered in.
 * @param outputFileName the filename written under the session dir. Defaults to the canonical
 *   recording for [format] (`video.webm` / `video.mp4`). `BaguetteIosVideoCapture` overrides it to
 *   record a `simctl` *remainder* segment (`video.simctl.mp4`, always mp4 — the stitch re-encodes
 *   it) after a mid-session baguette feed death, so it doesn't clobber the primary baguette segment.
 */
class IosVideoCapture(
  private val format: RecordingFormat = RecordingFormat.preferred,
  private val outputFileName: String = format.canonicalFilename,
  /** Test seam: swap the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
  /** Test seam: the host clock this recorder bookends its window on. */
  private val nowMs: () -> Long = System::currentTimeMillis,
  /** Test seam: [RecordingTailHold.holdToWindow], which runs ffprobe and ffmpeg. */
  private val holdToWindow: (File, Long) -> Boolean = { file, windowMs ->
    RecordingTailHold.holdToWindow(file, windowMs) { f, untilMs ->
      RecordingTailHold.holdLastFrame(f, untilMs, ffmpegBinary = ffmpegBinary)
    }
  },
) : CaptureStream {
  override val type: CaptureType get() = format.captureType

  private var process: Process? = null
  private var videoFile: File? = null
  private var recordingFile: File? = null

  /** When we spawned simctl — the fallback anchor if it never announces its first frame. */
  private var spawnedAtMs: Long = 0

  /** Reads clip-time zero off the recorder's own output; see "Clock alignment" in the class doc. */
  private val recordingStart = SimctlRecordingStartWatcher(nowMs)

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

    try {
      Console.log(
        "Starting iOS video recording: device=$deviceId output=${output.absolutePath} " +
          "(recording via ${recordingTarget.absolutePath})"
      )
      // Only the fallback anchor. The real clip-time zero comes from simctl announcing its first
      // frame, which the drain thread below stamps — see "Clock alignment" in the class doc.
      this.spawnedAtMs = nowMs()
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
      // WallClockMuxConsumer's stderr drainer). start-verify and stop read the accumulated buffer
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

  /** Drains the merged output of [proc] into [processOutput] on a daemon thread until EOF. */
  private fun drainProcessOutput(proc: Process) {
    Thread(
      {
        try {
          proc.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
              // Stamped here, on the reading thread, so clip-time zero is when simctl said it had a
              // frame — not when stop() got around to looking.
              recordingStart.observe(line)
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

    /**
     * Cap on the stop-time WebM transcode, scaled to the recording it has to encode rather than a
     * flat ceiling. The encode runs at the realtime deadline over a downscaled frame, so it beats
     * the recording's own length comfortably; [TRANSCODE_TIMEOUT_REALTIME_MULTIPLE] is the slack
     * for a loaded box.
     *
     * Scaling matters in both directions. The typical session is a few minutes, and a 30-second
     * recording that has not encoded in a minute is wedged — waiting ten more stalls a CI step's
     * teardown for nothing. A long session is the opposite problem: a flat cap below the
     * recording's own length throws away an encode that was going to succeed, so the ceiling sits
     * above any recording this scaling admits. A wedged ffmpeg is destroyed and the session keeps
     * simctl's mp4 either way, so the cost of the cap is bytes, not video.
     */
    private const val TRANSCODE_TIMEOUT_MIN_SECONDS = 60L
    private const val TRANSCODE_TIMEOUT_MAX_SECONDS = 1_800L
    private const val TRANSCODE_TIMEOUT_REALTIME_MULTIPLE = 2

    /**
     * The end of the artifact window, given the instant [stampedEndMs] the stop path recorded and
     * the clip-time zero [startMs] it resolved.
     *
     * A window may not run backwards, and this one can: clip-time zero is written by the drain
     * thread when simctl announces its first frame (~389 ms after spawn), so a session stopped
     * inside that startup can resolve a start LATER than the end already stamped. The normal path
     * re-stamps the end after the stop signal lands and overtakes it; the path where the kill
     * throws does not. A negative window would make the report scale clip time by a negative
     * factor, putting every step off the recording — an empty window puts them all at its start,
     * which for a recording this short is where they were.
     */
    internal fun recordingWindowEndMs(stampedEndMs: Long, startMs: Long): Long =
      stampedEndMs.coerceAtLeast(startMs)

    /** The transcode bound for a recording of [recordedMs], clamped to the range above. */
    internal fun transcodeTimeoutSeconds(recordedMs: Long): Long =
      ((recordedMs / 1000) * TRANSCODE_TIMEOUT_REALTIME_MULTIPLE)
        .coerceIn(TRANSCODE_TIMEOUT_MIN_SECONDS, TRANSCODE_TIMEOUT_MAX_SECONDS)

    /**
     * Scale the simulator's native frame (1179×2556 on a current iPhone) to a 720px short side in
     * either orientation, never upscaling — the same recording size Android uses, and a fraction of
     * the bytes the report would otherwise embed. `-2` keeps the aspect ratio at an even dimension.
     */
    internal const val DOWNSCALE_FILTER = "scale='if(gt(iw,ih),-2,min(iw,720))':'if(gt(iw,ih),min(ih,720),-2)'"

    /**
     * Whether a finished transcode produced a recording worth delivering — and, when it did not,
     * removes [target] so nothing downstream finds it.
     *
     * [exitCode] is null when ffmpeg could not be started at all or was destroyed on its timeout.
     * That is the case this cleanup exists for: a killed ffmpeg leaves exactly what it had written
     * so far, the caller then delivers simctl's mp4 under the mp4 name instead, and CI uploads
     * recordings by extension — so a partial `video.webm` left beside the mp4 is published as a
     * session recording that no player can open.
     */
    internal fun transcodeDelivered(target: File, exitCode: Int?): Boolean {
      val delivered = exitCode == 0 && target.exists() && target.length() > 0L
      if (!delivered) runCatching { target.delete() }
      return delivered
    }
  }

  /**
   * Re-encodes simctl's mp4 into the VP9 WebM the report plays. Timing is passthrough so simctl's
   * variable-rate timeline survives, on a millisecond encoder time base so it is not snapped to the
   * 25 fps grid the encoder would otherwise derive; the artifact window is still the
   * recorder-observed bookends.
   */
  private fun transcodeToWebm(source: File, target: File, recordedMs: Long): Boolean {
    val timeoutSeconds = transcodeTimeoutSeconds(recordedMs)
    val result = runSubprocessWithTimeout(
      command = listOf(ffmpegBinary, "-y", "-i", source.absolutePath, "-an", "-vf", DOWNSCALE_FILTER) +
        RecordingFormat.WEBM.encodeArgs() + RecordingTailHold.ENCODER_TIME_BASE + target.absolutePath,
      timeoutSeconds = timeoutSeconds,
    )
    if (!transcodeDelivered(target, result?.exitCode)) {
      if (result == null) {
        Console.log("iOS video: WebM transcode could not run or timed out after ${timeoutSeconds}s")
      } else {
        Console.log(
          "iOS video: WebM transcode failed (exit=${result.exitCode}): " +
            sanitizeSubprocessOutputForLog(result.output),
        )
      }
      return false
    }
    return true
  }

  /**
   * simctl's stream is variable-rate and damage-driven: a run that ends on a still screen yields a
   * container that stops at the last change (4.4 s of a 27.8 s session in one CI run, 70 ms for
   * a session that never moved). The report scales clip time onto the window, so a short file
   * stretches every step off its frame; holding the last frame to the window is what the screen did.
   * A hold that fails keeps the transcode as it was — misplaced steps, but still a recording.
   */
  private fun holdStillTail(file: File, windowMs: Long, recorderAlive: Boolean) {
    if (!recorderAlive) {
      Console.log("iOS video: simctl exited before stop; not holding ${file.name}'s last frame")
      return
    }
    if (!holdToWindow(file, windowMs)) {
      Console.log("iOS video: ${file.name} could not be held to its ${windowMs}ms window")
    }
  }

  /** Moves the finalized temp recording onto [target]; null when the move fails. */
  private fun moveInto(recording: File, target: File): File? = try {
    Files.move(recording.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    target
  } catch (e: Exception) {
    Console.log("Failed to move iOS recording into session dir: ${e.message}")
    null
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val proc = process ?: run {
      Console.log("iOS video capture: process is null — recording never started")
      recordingFile?.delete()
      return null
    }
    val file = videoFile ?: return null
    val recording = recordingFile ?: return null

    // The end of the footage is when simctl STOPS capturing, which is when the signal reaches it —
    // not when we decided to stop, and not when xcrun finishes writing (the simulator can take
    // seconds to flush its moov atom, and counting that would stretch the window past the last
    // frame). Stamped here as a floor in case the stop path below throws, then corrected to the
    // delivery instant once the signal has actually landed.
    var endTimestampMs = nowMs()

    // Clip-time zero: when simctl said it had a frame, falling back to the spawn only if it never
    // said so (an Xcode that doesn't print the line). See "Clock alignment" in the class doc.
    val startTimestampMs = recordingStart.clipZeroMs(spawnedAtMs)
    // Whether simctl was still recording when the stop came. One that died on its own (a simulator
    // crash) stopped its file where the evidence ends, so that file is never held to the window.
    var recorderAlive = true

    try {
      // xcrun simctl recordVideo stops cleanly on SIGINT
      val pid = proc.pid()
      recorderAlive = proc.isAlive
      Console.log("Stopping iOS video recording (pid=$pid, alive=$recorderAlive)...")
      ProcessBuilder("kill", "-INT", pid.toString()).redirectErrorStream(true).start().waitFor()
      // Spawning `kill` is itself a process launch — measured at ~200 ms on a warm Mac — and simctl
      // keeps capturing for every one of those milliseconds. Re-stamping after delivery stops the
      // window from cutting off footage the file actually contains.
      endTimestampMs = nowMs()
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

    // Never let the window run backwards. `startTimestampMs` comes off the drain thread, which can
    // land the announcement AFTER the floor above was stamped — a stop inside simctl's ~389 ms
    // startup. The re-stamp after the signal normally overtakes it, but the catch path above leaves
    // the floor standing, and a negative window makes the report scale clip time by a negative
    // factor: every step lands off the recording entirely.
    endTimestampMs = recordingWindowEndMs(endTimestampMs, startTimestampMs)

    process = null

    // Deliver the finalized temp recording into the session dir (see start() for why the
    // recording lands in the temp dir first). This JVM owns the session dir, so the move
    // succeeds even where SimRender couldn't write the recording there directly.
    var delivered: File? = null
    var deliveredFormat = format
    if (recording.exists() && recording.length() > 0L) {
      if (format == RecordingFormat.WEBM) {
        if (transcodeToWebm(recording, file, endTimestampMs - startTimestampMs)) {
          delivered = file
          holdStillTail(file, endTimestampMs - startTimestampMs, recorderAlive)
        } else {
          // A video problem must never cost the session its video: deliver simctl's own mp4 under
          // the mp4 name and publish it as such — the report plays that too.
          val mp4 = File(file.parentFile, "${file.nameWithoutExtension}.mp4")
          Console.log("iOS video: delivering simctl's mp4 as ${mp4.name} instead of ${file.name}")
          delivered = moveInto(recording, mp4)
          deliveredFormat = RecordingFormat.MP4
        }
      } else {
        delivered = moveInto(recording, file)
        // simctl's mp4 is just as damage-driven as the transcode made from it.
        if (delivered != null) holdStillTail(delivered, endTimestampMs - startTimestampMs, recorderAlive)
      }
    }
    recording.delete()

    if (delivered == null || !delivered.exists() || delivered.length() == 0L) {
      Console.log(
        "iOS video recording produced no output: exists=${delivered?.exists()}, " +
          "length=${delivered?.takeIf { it.exists() }?.length() ?: -1}, path=${(delivered ?: file).absolutePath}",
      )
      return null
    }

    return CaptureArtifact(
      file = delivered,
      type = deliveredFormat.captureType,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }
}
