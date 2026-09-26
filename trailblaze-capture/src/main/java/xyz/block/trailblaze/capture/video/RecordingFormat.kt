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
 * one. [MP4] exists only for a host whose ffmpeg was built without `libvpx-vp9`, or whose ffmpeg
 * loses a live recording's timing when it re-encodes (see [forLiveMux]): the recording is
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
  val canonicalFilename: String get() = filename(CaptureFilenames.VIDEO_BASENAME)

  /** A recording in this container under [basename] — `video-buyer.webm` for a companion device. */
  fun filename(basename: String): String = "$basename.$fileExtension"

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

  /**
   * [encodeArgs] for the web screencast recording ([WebScreencastVideoCapture]). A browser page is
   * mostly small text on flat color, which the shared setting smears. This one spends the bits
   * [quality] asks for and turns on libvpx's screen-content mode, which came out both sharper and
   * smaller than the default mode at the same CRF. Web-only on purpose: Android and iOS keep the
   * shared setting, so their recordings don't grow.
   *
   * The deadline stays realtime, so the stop-time encode costs what it did before: about 14
   * CPU-seconds, 3 s wall, for a 6-minute session on a laptop.
   */
  fun webScreencastEncodeArgs(quality: WebVideoQuality = WebVideoQuality.STANDARD): List<String> = when (this) {
    WEBM -> WallClockMuxConsumer.Output.vp9CodecArgs(crf = quality.crf) +
      listOf("-tune-content", "screen", "-fps_mode", "passthrough")
    MP4 -> encodeArgs()
  }

  companion object {
    /**
     * Probed once per process: [WEBM] when this host's ffmpeg can encode VP9 and keeps a live
     * recording's timing through that encode, otherwise [MP4]. The probe shells out to
     * `ffmpeg -encoders`, so it is not repeated per session.
     */
    val preferred: RecordingFormat by lazy {
      val support = WallClockMuxConsumer.Output.probeLiveMuxSupport()
      forLiveMux(support).also {
        when {
          !support.vp9Encoder ->
            Console.log("[RecordingFormat] ffmpeg has no libvpx-vp9 encoder; session recordings will be mp4 instead of webm")
          !support.reencodeKeepsWallClock ->
            Console.error(
              "[RecordingFormat] WARNING: ffmpeg ${support.ffmpegVersion} loses a live recording's timing when " +
                "it re-encodes to webm (FFmpeg ticket #11268, fixed in 6.1.3, 7.0.3, 7.1.2 and 8.0). Session " +
                "recordings will be mp4, which keeps the timing. Install a newer ffmpeg to record webm.",
            )
        }
      }
    }

    /** Pure half of [preferred]: the format a host with (or without) a VP9 encoder records in. */
    fun forVp9Encoder(available: Boolean): RecordingFormat = if (available) WEBM else MP4

    /**
     * Pure half of [preferred]: [WEBM] only where the live VP9 encode is both possible and keeps
     * wall-clock time. Where it wouldn't, [MP4]'s stream copy never decodes, so the recording keeps
     * the arrival times the webm encode would have thrown away.
     */
    fun forLiveMux(support: WallClockMuxConsumer.Output.LiveMuxSupport): RecordingFormat =
      forVp9Encoder(support.vp9Encoder && support.reencodeKeepsWallClock)
  }
}
