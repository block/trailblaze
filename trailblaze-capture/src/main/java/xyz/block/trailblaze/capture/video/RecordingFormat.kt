package xyz.block.trailblaze.capture.video

import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * The container a session recording is written in — one answer for every recorder on every
 * platform, decided once per process.
 *
 * [WEBM] is the recording: VP9 in a WebM, the container browsers play inline, so the HTML report
 * embeds the file and plays it as the session's timeline. Android and iOS (baguette) encode it
 * **live** from the device's H.264 stream ([WallClockMuxConsumer.Output.WebmVp9]); the iOS simctl
 * recorder and the web screencast mux encode it at stop; Playwright's own recorder already writes
 * one. [MP4] exists only for a host whose ffmpeg was built without `libvpx-vp9`: the recording is
 * then H.264 in an mp4 (`-c copy` where the source is already H.264, libx264 otherwise) and the
 * report plays that instead. Nothing else about the session changes with the format — the
 * artifact's window, the report's timeline math and the desktop app's "Watch Video" hand-off all
 * read the recording the same way.
 */
enum class RecordingFormat(
  val fileExtension: String,
  /** How the recording is published in `capture_metadata.json`. */
  val captureType: CaptureType,
) {
  WEBM("webm", CaptureType.VIDEO_WEBM),
  MP4("mp4", CaptureType.VIDEO),
  ;

  /** `video.webm` / `video.mp4` — the session's canonical recording filename. */
  val canonicalFilename: String get() = "${CaptureFilenames.VIDEO_BASENAME}.$fileExtension"

  /** The live wall-clock mux output that writes this container from a raw H.264 feed. */
  fun liveMuxOutput(): WallClockMuxConsumer.Output = when (this) {
    WEBM -> WallClockMuxConsumer.Output.WebmVp9()
    MP4 -> WallClockMuxConsumer.Output.Mp4Copy
  }

  /**
   * ffmpeg codec and container args for an encode that runs after the fact — stitching two
   * segments, muxing screencast frames, transcoding a simctl recording. Timing is left to the input
   * (`-fps_mode passthrough`) so whatever timeline the source carries survives the encode.
   */
  fun encodeArgs(): List<String> = when (this) {
    WEBM -> WallClockMuxConsumer.Output.vp9CodecArgs() + listOf("-fps_mode", "passthrough")
    MP4 -> listOf(
      "-c:v", "libx264",
      "-preset", "veryfast",
      "-crf", "23",
      "-pix_fmt", "yuv420p",
      "-movflags", "+faststart",
    )
  }

  companion object {
    /**
     * Probed once per process: [WEBM] when this host's ffmpeg can encode VP9, otherwise [MP4]. The
     * probe shells out to `ffmpeg -encoders`, so it is not repeated per session.
     */
    val preferred: RecordingFormat by lazy {
      forVp9Encoder(WallClockMuxConsumer.Output.vp9EncoderAvailable()).also {
        if (it == MP4) {
          Console.log("[RecordingFormat] ffmpeg has no libvpx-vp9 encoder; session recordings will be mp4 instead of webm")
        }
      }
    }

    /** Pure half of [preferred]: the format a host with (or without) a VP9 encoder records in. */
    fun forVp9Encoder(available: Boolean): RecordingFormat = if (available) WEBM else MP4
  }
}
