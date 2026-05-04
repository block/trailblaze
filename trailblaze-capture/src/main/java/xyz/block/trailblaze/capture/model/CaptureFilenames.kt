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

  /** Canonical raw video filename for screen-recording capture. */
  const val VIDEO = "video.mp4"

  /** File extension for screen-recording video output. */
  const val VIDEO_EXTENSION = ".mp4"
}
