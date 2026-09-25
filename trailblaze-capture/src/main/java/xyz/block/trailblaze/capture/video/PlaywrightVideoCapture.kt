package xyz.block.trailblaze.capture.video

import java.io.File
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * `CaptureStream` for Playwright-driven web sessions. Mirrors what [AndroidVideoCapture] does for
 * Android: produces the session recording the report plays back.
 *
 * Unlike Android, the recording is owned by Playwright itself — `Browser.newContext()`
 * is what enables it, and `BrowserContext.close()` is what flushes the resulting WebM
 * to disk. This stream coordinates with the Playwright manager through
 * [PlaywrightVideoRecordDir]:
 *  - [start] publishes the per-session temp directory; the manager picks it up at
 *    `createFreshContextAndPage()` and configures `setRecordVideoDir`.
 *  - [stop] asks the manager (via a registered finalizer) to close the active context
 *    so any in-flight `.webm` is flushed, then delivers it.
 *
 * Playwright already records a WebM (VP8), which is the container every other platform's recording
 * is in, so with [RecordingFormat.WEBM] — the default here on every host, since no encoder is
 * involved — the file is delivered untouched as `video.webm`. [RecordingFormat.MP4] transcodes it
 * to H.264 with `ffmpeg -c:v libx264` for the one consumer that needs an mp4, `trailblaze report
 * --video`; if that transcode fails the WebM is delivered instead so the run still has its video.
 *
 * ### Where this recorder's window comes from
 * Playwright owns the recording — it starts filming when the *page* is created, long after [start]
 * (which only publishes the directory the manager will use), and finalizes the file at context
 * close. So neither bookend can be read off the calls here:
 *  - **Clip time zero** is reported by the manager as it creates the page
 *    ([PlaywrightVideoRecordDir.markRecordingStarted]). Measured on a cold browser, [start] ran
 *    **6.3 s** before the first frame; from the page-creation instant the residual is ~100 ms, which
 *    is the browser's own first-paint latency and is not observable from the API.
 *  - **The end** spans the file rather than the stop call, because Playwright writes a stable tail
 *    of duplicate frames while finalizing — ~910 ms, the same across runs, with or without closing
 *    the page first. The report *scales* clip time onto the window, so a window shorter than the
 *    file turns that tail into a proportional error (~500 ms by mid-session on a 10 s recording).
 *    Taking the duration from the file makes the scale exactly 1.
 *
 * Both fall back to the [start]/[stop] instants if the manager never reported (a browser that never
 * recorded) or the duration can't be probed. A slightly wrong window still lets the report place
 * steps; no window at all makes it drop the recording.
 */
class PlaywrightVideoCapture(
  private val format: RecordingFormat = RecordingFormat.WEBM,
  /** Test seam: the host clock this recorder falls back to when the manager reported nothing. */
  private val nowMs: () -> Long = System::currentTimeMillis,
  /** Test seam: how the delivered recording's real duration is read. */
  private val durationProbeMs: (File) -> Long? = { VideoDuration.probeMs(it) },
) : CaptureStream {
  override val type: CaptureType get() = format.captureType

  private var sessionDir: File? = null
  private var deviceId: String? = null

  /** Fallback anchor only — the manager reports the real one. See the class doc. */
  private var startedPublishingAtMs: Long = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.startedPublishingAtMs = nowMs()
    sessionDir.mkdirs()
    PlaywrightVideoRecordDir.setRecordDir(deviceId, sessionDir)
    Console.log("[PlaywrightVideoCapture] published recordVideoDir=${sessionDir.absolutePath} for deviceId=$deviceId")
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val dev = deviceId ?: return null
    val dir = sessionDir ?: return null
    val stoppedAtMs = nowMs()

    // Ask the Playwright manager — if still alive — to close its BrowserContext so the
    // .webm is flushed. No-op when the manager has already torn itself down (the common
    // case in CI runs, where playwrightTest.close() ran before this method was called).
    PlaywrightVideoRecordDir.runFinalizer(dev)
    val startTimestampMs = PlaywrightVideoRecordDir.recordingStartedAtMs(dev) ?: startedPublishingAtMs
    PlaywrightVideoRecordDir.clearRecordingStarted(dev)
    PlaywrightVideoRecordDir.clearRecordDir(dev)

    val webm = findLatestWebm(dir)
    if (webm == null) {
      Console.log("[PlaywrightVideoCapture] no .webm found in ${dir.absolutePath}")
      return null
    }

    val target = File(dir, format.canonicalFilename)
    val (finalFile, finalFormat) = when (format) {
      RecordingFormat.WEBM ->
        (if (webm == target) webm else moveOnto(webm, target) ?: webm) to RecordingFormat.WEBM
      RecordingFormat.MP4 -> {
        val mp4 = transcodeWebmToMp4(webm, target)
        if (mp4 != null) {
          // Free the WebM now that the MP4 is the artifact.
          runCatching { webm.delete() }
          mp4 to RecordingFormat.MP4
        } else {
          webm to RecordingFormat.WEBM
        }
      }
    }

    // Span the window across the file, so the report's clip-time scale is exactly 1 and the
    // finalization tail stops displacing every step. Falls back to the stop instant when the
    // duration can't be read — see the class doc.
    val endTimestampMs = durationProbeMs(finalFile)
      ?.let { startTimestampMs + it }
      ?: stoppedAtMs

    return CaptureArtifact(
      file = finalFile,
      type = finalFormat.captureType,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /**
   * Playwright writes each context's video as `<random>.webm` in the configured dir.
   * For a Trailblaze run there's typically exactly one — but in the kept-alive case
   * `resetSession()` may have produced more than one. We pick the most recently
   * modified file as the canonical artifact.
   */
  private fun findLatestWebm(dir: File): File? =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".webm") }
      ?.maxByOrNull { it.lastModified() }

  /** Renames (or copies) Playwright's randomly named recording onto the canonical name; null when neither works. */
  private fun moveOnto(source: File, target: File): File? {
    runCatching { target.delete() }
    if (source.renameTo(target)) return target
    return runCatching {
      source.copyTo(target, overwrite = true)
      source.delete()
      target
    }.getOrElse {
      Console.log("[PlaywrightVideoCapture] could not place ${source.name} at ${target.name}: ${it.message}")
      null
    }
  }

  private fun transcodeWebmToMp4(input: File, output: File): File? {
    if (input.length() == 0L) return null
    // Routed through the shared subprocess helper so the daemon-drain + timeout +
    // destroyForcibly pattern lives in exactly one place — reviewer feedback on PR #3087 caught
    // divergent variants of the same pattern slipping in.
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(FFMPEG_BINARY, "-y", "-i", input.absolutePath, "-an") +
            RecordingFormat.MP4.encodeArgs() + output.absolutePath,
        timeoutSeconds = FFMPEG_TIMEOUT_SECONDS,
      )
    if (result == null) {
      Console.log("[PlaywrightVideoCapture] ffmpeg transcode could not be run or timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
      return null
    }
    if (result.exitCode != 0 || output.length() == 0L) {
      Console.log(
        "[PlaywrightVideoCapture] ffmpeg transcode failed: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output),
      )
      return null
    }
    return output
  }

  companion object {
    private const val FFMPEG_BINARY = "ffmpeg"
    private const val FFMPEG_TIMEOUT_SECONDS = 60L
  }
}
