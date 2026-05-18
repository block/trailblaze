package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * `CaptureStream` for Playwright-driven web sessions. Mirrors what
 * [AndroidVideoCapture] does for Android: produces a `video.mp4` artifact (plus an
 * optional sprite sheet) that the report viewer can play back.
 *
 * Unlike Android, the recording is owned by Playwright itself — `Browser.newContext()`
 * is what enables it, and `BrowserContext.close()` is what flushes the resulting WebM
 * to disk. This stream coordinates with the Playwright manager through
 * [PlaywrightVideoRecordDir]:
 *  - [start] publishes the per-session temp directory; the manager picks it up at
 *    `createFreshContextAndPage()` and configures `setRecordVideoDir`.
 *  - [stop] asks the manager (via a registered finalizer) to close the active context
 *    so any in-flight `.webm` is flushed, then transcodes the result to `.mp4` with
 *    `ffmpeg -c:v libx264`. WebM→MP4 is a re-encode (codec mismatch) so this is
 *    slower than the Android `-c copy` path, but the output size is small (Playwright
 *    records VP8/VP9 already-compressed) so a `veryfast` preset finishes in well
 *    under a second for typical trail runs.
 *
 * If `ffmpeg` is unavailable or the transcode fails, the original WebM is returned as
 * the artifact so the run still has *some* video — downstream viewers that don't
 * understand WebM will degrade gracefully (no thumbnail, but the link still resolves).
 */
class PlaywrightVideoCapture : CaptureStream {
  override val type = CaptureType.VIDEO

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var startTimestampMs: Long = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.startTimestampMs = System.currentTimeMillis()
    sessionDir.mkdirs()
    PlaywrightVideoRecordDir.setRecordDir(deviceId, sessionDir)
    Console.log("[PlaywrightVideoCapture] published recordVideoDir=${sessionDir.absolutePath} for deviceId=$deviceId")
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val dev = deviceId ?: return null
    val dir = sessionDir ?: return null
    val endTimestampMs = System.currentTimeMillis()

    // Ask the Playwright manager — if still alive — to close its BrowserContext so the
    // .webm is flushed. No-op when the manager has already torn itself down (the common
    // case in CI runs, where playwrightTest.close() ran before this method was called).
    PlaywrightVideoRecordDir.runFinalizer(dev)
    PlaywrightVideoRecordDir.clearRecordDir(dev)

    val webm = findLatestWebm(dir)
    if (webm == null) {
      Console.log("[PlaywrightVideoCapture] no .webm found in ${dir.absolutePath}")
      return null
    }

    val mp4 = File(dir, "video.mp4")
    val mp4Result = transcodeWebmToMp4(webm, mp4)
    val finalFile = mp4Result ?: webm
    if (mp4Result != null) {
      // Free the WebM now that the MP4 is the canonical artifact.
      runCatching { webm.delete() }
    }

    val spriteSheet = VideoSpriteExtractor.generateSpriteSheet(
      finalFile,
      fps = options.spriteFrameFps,
      frameHeight = options.spriteFrameHeight,
      webpQuality = options.spriteQuality,
      isLandscape = true,
    )
    if (spriteSheet != null) {
      return CaptureArtifact(
        file = spriteSheet,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }

    return CaptureArtifact(
      file = finalFile,
      type = CaptureType.VIDEO,
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

  private fun transcodeWebmToMp4(input: File, output: File): File? {
    if (input.length() == 0L) return null
    return try {
      val pb = ProcessBuilder(
        FFMPEG_BINARY,
        "-y",
        "-i", input.absolutePath,
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        "-movflags", "+faststart",
        output.absolutePath,
      ).redirectErrorStream(true)
      val process = pb.start()
      // Drain stdout on a background thread — `readText()` blocks until EOF, so doing
      // it inline would deadlock a hung ffmpeg and make the `waitFor` timeout below
      // unreachable. Daemon thread so a stuck reader never holds the JVM alive.
      val drained = StringBuilder()
      val drainThread = Thread {
        process.inputStream.bufferedReader().use { reader ->
          reader.forEachLine { drained.appendLine(it) }
        }
      }.apply { isDaemon = true; start() }
      val finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        drainThread.join(1_000)
        Console.log("[PlaywrightVideoCapture] ffmpeg transcode timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
        return null
      }
      drainThread.join(1_000)
      if (process.exitValue() != 0 || output.length() == 0L) {
        Console.log("[PlaywrightVideoCapture] ffmpeg transcode failed: exit=${process.exitValue()}\n$drained")
        return null
      }
      output
    } catch (e: Exception) {
      Console.log("[PlaywrightVideoCapture] ffmpeg not available for transcode: ${e.message}")
      null
    }
  }

  companion object {
    private const val FFMPEG_BINARY = "ffmpeg"
    private const val FFMPEG_TIMEOUT_SECONDS = 60L
  }
}
