package xyz.block.trailblaze.report.strings

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.capture.CaptureMetadata
import xyz.block.trailblaze.capture.video.VideoStills
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.normalizedToHostClock
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.WebpEncoder
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Gives every screenshot-less capture in a session a picture: the frame the session's recording
 * shows at that capture's instant, saved beside the session's screenshots as `<captureId>.webp`.
 * It is saved the way a screenshot is: WebP, scaled to fit the same box, at the same quality
 * ([ScreenshotScalingConfig.DEFAULT]), so a frame costs the report what a screenshot would.
 *
 * A capture saves no screenshot when the run was asked for text only, when a stream-screenshot
 * driver fell back to the tree, or when the screenshot itself failed. The report used to take such
 * frames in the browser, which leaves an export or any tool reading the session dir without them;
 * a file on disk reaches all of them.
 *
 * Which frame is the browser's own rule, which was matched against real screenshots: the run
 * instant is the log's host-clock time, it lands in the recording SCALED by duration over the
 * recorder's window (a plain offset drifts by the whole difference), and the picture is the frame
 * on screen then — the last one presented at or before it, never the next. A frame whose
 * orientation disagrees with the capture's (an Android recording keeps the orientation it started
 * in) is none, since its pixels would not match the capture's boxes.
 *
 * Nothing here guesses. A capture is left without a frame when no recording covers its instant,
 * or when more than one does: capture logs don't name their device, so with two devices recording
 * at once there is no telling which screen the capture was of. The logs are never rewritten; the
 * file's name is the only link, and [fileName] is it.
 */
object CaptureVideoFrames {

  /** Where a capture's frame lives in its session dir. */
  fun fileName(captureId: String): String = "$captureId.webp"

  private val SCREENSHOT = ScreenshotScalingConfig.DEFAULT

  /**
   * Why a capture was left without a frame, for a caller that wants to say so. Every
   * screenshot-less capture ends up in exactly one bucket of [Result].
   */
  enum class Miss {
    /** Its id can't be a file name, so it has nowhere to be saved. */
    UNSAFE_ID,

    /** No recording's window holds its instant. */
    NOT_RECORDED,

    /** Two or more recordings' windows hold it, and nothing says which device it was on. */
    AMBIGUOUS_RECORDING,

    /** The recording is portrait where the capture was landscape, or the reverse. */
    ROTATED,

    /** The recording covering it could not be read, or its frame could not be decoded. */
    UNREADABLE,
  }

  /**
   * @param frames each screenshot-less capture that now has a frame file, by capture id: the ones
   *   this call wrote and the ones an earlier call already had.
   * @param written how many of [frames] this call wrote.
   */
  data class Result(
    val frames: Map<String, String>,
    val written: Int,
    val missed: Map<String, Miss>,
  )

  private val JSON = Json { ignoreUnknownKeys = true }

  // A plain file name. A capture id is opaque and a recording name is producer-written data, so
  // each is held to this before it names a file.
  private val SAFE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

  private class Want(val captureId: String, val atMs: Long, val width: Int, val height: Int)

  /** Writes the missing frames for [logs] into [sessionDir], which holds the session's recording. */
  fun fill(
    sessionDir: File,
    logs: List<TrailblazeLog>,
    ffprobeBinary: String = "ffprobe",
    ffmpegBinary: String = "ffmpeg",
  ): Result {
    val wants = logs.normalizedToHostClock()
      .mapNotNull { it.screenshotlessCapture() }
      .distinctBy { it.captureId }
    if (wants.isEmpty()) return Result(emptyMap(), 0, emptyMap())

    val frames = mutableMapOf<String, String>()
    val missed = mutableMapOf<String, Miss>()
    val pending = mutableListOf<Want>()
    for (want in wants) {
      when {
        !SAFE_NAME.matches(want.captureId) -> missed[want.captureId] = Miss.UNSAFE_ID
        File(sessionDir, fileName(want.captureId)).isFile -> frames[want.captureId] = fileName(want.captureId)
        else -> pending += want
      }
    }
    if (pending.isEmpty()) return Result(frames, 0, missed)

    val recordings = recordingsIn(sessionDir)
    // Each recording's first file that probes, or null when none does: a webm that can't be read
    // falls back to the same recording's mp4. Probed once, and only when a capture needs it.
    val opened = mutableMapOf<Recording, Opened?>()
    fun open(recording: Recording): Opened? = if (recording in opened) {
      opened[recording]
    } else {
      recording.files.firstNotNullOfOrNull { file ->
        VideoStills.probe(file, ffprobeBinary)?.let { Opened(file, it) }
      }.also { opened[recording] = it }
    }
    // Where a recording's window ends on the run's clock. With no end stamp the report plays it in
    // real time from its start, so it ends as long after its start as the file runs.
    fun endOf(recording: Recording): Long = recording.endMs?.takeIf { it > recording.startMs }
      ?: open(recording)?.let { recording.startMs + (it.probed.durationSec * 1000).toLong() }
      ?: recording.startMs
    val byRecording = mutableMapOf<Recording, MutableList<Want>>()
    for (want in pending) {
      val covering = recordings.filter { want.atMs >= it.startMs && want.atMs <= endOf(it) }
      when (covering.size) {
        0 -> missed[want.captureId] = Miss.NOT_RECORDED
        1 -> byRecording.getOrPut(covering.single()) { mutableListOf() } += want
        else -> missed[want.captureId] = Miss.AMBIGUOUS_RECORDING
      }
    }

    var written = 0
    for ((recording, group) in byRecording) {
      val readable = open(recording)
      if (readable == null) {
        group.forEach { missed[it.captureId] = Miss.UNREADABLE }
        continue
      }
      val (file, probed) = readable
      // Each capture's frame, by that frame's pts. Captures on one unchanged screen share a frame,
      // which is decoded once and saved under each of their names.
      val byPts = mutableMapOf<Long, MutableList<Want>>()
      for (want in group) {
        val pts = frameFor(recording, probed, want.atMs)
        when {
          pts == null -> missed[want.captureId] = Miss.NOT_RECORDED
          turned(want, probed) -> missed[want.captureId] = Miss.ROTATED
          else -> byPts.getOrPut(pts) { mutableListOf() } += want
        }
      }
      if (byPts.isEmpty()) continue
      // Decoded beside the session's files, so moving one into place is a rename on one disk.
      val workDir = File(sessionDir, ".video-frames-${UUID.randomUUID()}")
      try {
        val targets = byPts.mapValues { (pts, _) -> File(workDir, "pts-$pts.png") }
        val size = SCREENSHOT.computeScaledDimensions(probed.width, probed.height)
          .takeIf { it != (probed.width to probed.height) }
        val decoded = VideoStills.extract(file, targets, workDir, ffmpegBinary, size)
        for ((pts, sharing) in byPts) {
          val webp = if (pts in decoded) webpOf(targets.getValue(pts)) else null
          for (want in sharing) {
            val name = fileName(want.captureId)
            val saved = webp != null && runCatching { File(workDir, "$name.part").apply { writeBytes(webp) } }
              .map { it.renameTo(File(sessionDir, name)) }
              .getOrDefault(false)
            if (saved) {
              frames[want.captureId] = name
              written++
            } else {
              missed[want.captureId] = Miss.UNREADABLE
            }
          }
        }
      } finally {
        workDir.deleteRecursively()
      }
    }
    return Result(frames, written, missed)
  }

  /**
   * [png] as a WebP at the screenshot quality, or null when it can't be read or encoded. Encoded
   * here, as the host's screenshots are, because ffmpeg builds commonly ship without a WebP encoder.
   */
  private fun webpOf(png: File): ByteArray? = try {
    val image = ImageIO.read(png) ?: error("not a readable image")
    WebpEncoder.encode(image, SCREENSHOT.compressionQuality)
  } catch (e: Throwable) {
    // Throwable: an encoder that can't load its native library throws an Error, and that is the one
    // failure worth a line in the log, since it takes every frame with it.
    Console.log("[CaptureVideoFrames] could not encode ${png.name} as WebP: ${e::class.simpleName}: ${e.message}")
    null
  }

  /**
   * Seconds into [probed] that run instant [atMs] falls at, as the report's `videoClipTimeAt` has
   * it (a recording with no stamped window plays in real time), then the frame on screen there. The
   * recording's last instant still has a frame; its duration is already past it.
   */
  private fun frameFor(recording: Recording, probed: VideoStills.Recording, atMs: Long): Long? {
    val windowMs = (recording.endMs ?: recording.startMs) - recording.startMs
    val scale = if (windowMs > 0) probed.durationSec * 1000.0 / windowMs else 1.0
    val atSec = (atMs - recording.startMs) * scale / 1000.0
    if (atSec < 0 || atSec > probed.durationSec) return null
    return probed.frameAt(minOf(atSec, maxOf(0.0, probed.durationSec - 0.001)))
  }

  /** The report's `turnedFrom`: square or unknown sizes never count as turned. */
  private fun turned(want: Want, probed: VideoStills.Recording): Boolean {
    val w = want.width
    val h = want.height
    val vw = probed.width
    val vh = probed.height
    if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0 || w == h || vw == vh) return false
    return (w > h) != (vw > vh)
  }

  private fun TrailblazeLog.screenshotlessCapture(): Want? = when (this) {
    is TrailblazeLog.AgentDriverLog -> captureId?.takeIf { screenshotFile.isNullOrBlank() }
      ?.let { Want(it, timestamp.toEpochMilliseconds(), deviceWidth, deviceHeight) }
    is TrailblazeLog.TrailblazeLlmRequestLog -> captureId?.takeIf { screenshotFile.isNullOrBlank() }
      ?.let { Want(it, timestamp.toEpochMilliseconds(), deviceWidth, deviceHeight) }
    else -> null
  }

  /**
   * One recording on the session's clock: the window its recorder stamped, and its files in the
   * order to try them. [endMs] is null when the recorder stamped no end.
   */
  internal data class Recording(val files: List<File>, val startMs: Long, val endMs: Long?)

  private data class Opened(val file: File, val probed: VideoStills.Recording)

  /**
   * The session's recordings as the report reads them (`readVideo`): one per device and file stem,
   * the webm tried before the mp4, each a file that exists inside [sessionDir] under a plain name.
   */
  internal fun recordingsIn(sessionDir: File): List<Recording> {
    val metadataFile = File(sessionDir, CaptureMetadata.FILENAME)
    if (!metadataFile.isFile) return emptyList()
    val metadata = runCatching {
      JSON.decodeFromString(CaptureMetadata.serializer(), metadataFile.readText())
    }.getOrNull() ?: return emptyList()
    val root = runCatching { sessionDir.canonicalFile }.getOrNull() ?: return emptyList()
    return metadata.artifacts
      .filter { it.type == "VIDEO_WEBM" || it.type == "VIDEO" }
      .groupBy { (it.deviceName ?: "") to it.filename.substringBeforeLast('.') }
      .values
      .mapNotNull { group ->
        val ordered = group.filter { it.type == "VIDEO_WEBM" } + group.filter { it.type != "VIDEO_WEBM" }
        val usable = ordered.filter { entry ->
          val name = entry.filename
          val file = File(sessionDir, name)
          val inside = runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)
          val playable = name.substringAfterLast('.', "").lowercase() in PLAYABLE
          SAFE_NAME.matches(name) && playable && inside && file.isFile
        }
        // The window is the first usable entry's, as the report takes it with the file it plays.
        usable.firstOrNull()?.let { first ->
          Recording(usable.map { File(sessionDir, it.filename) }, first.startTimestampMs, first.endTimestampMs)
        }
      }
  }

  private val PLAYABLE = setOf("webm", "mp4")
}
