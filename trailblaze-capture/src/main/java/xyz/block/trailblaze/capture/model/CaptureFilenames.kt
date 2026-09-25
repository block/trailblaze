package xyz.block.trailblaze.capture.model

/**
 * Canonical filenames written to the session directory by capture streams.
 *
 * Centralized so each platform's capture stream (Android logcat, iOS Simulator log stream,
 * future web/console capture, etc.) emits the same filename regardless of source. Downstream
 * detectors (`LogcatParser.findDeviceLogFile`, the file watcher, etc.) match against these
 * constants so adding or retiring a recognized filename happens in exactly one place.
 */
object CaptureFilenames {
  /** The single canonical device-log filename. Used for Android logcat AND iOS log stream. */
  const val DEVICE_LOG = "device.log"

  /** Legacy filename — older session folders may still contain this; recognized in detection. */
  const val LEGACY_LOGCAT_TXT = "logcat.txt"

  /** Legacy filename — older iOS captures wrote to this instead of `device.log`. */
  const val LEGACY_SYSTEM_LOG_TXT = "system_log.txt"

  /**
   * Basename of the session recording; the extension comes from the format it was written in
   * (`RecordingFormat.canonicalFilename`): `video.webm`, or `video.mp4` on a host without a VP9
   * encoder.
   */
  const val VIDEO_BASENAME = "video"

  /** The session recording as every host with a VP9 encoder writes it. */
  const val VIDEO_WEBM = "video.webm"

  /** The session recording on a host whose ffmpeg cannot encode VP9, and in sessions captured before WebM. */
  const val VIDEO = "video.mp4"

  /** File extensions a session recording can carry. */
  const val VIDEO_WEBM_EXTENSION = ".webm"
  const val VIDEO_EXTENSION = ".mp4"
}
