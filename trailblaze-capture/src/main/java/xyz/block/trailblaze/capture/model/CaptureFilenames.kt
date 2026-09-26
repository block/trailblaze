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

  /**
   * Basename of a companion device's recording in a multi-device session: `video-<name>`, for the
   * name the trail's configuration gave the device (`video-buyer`). The start device keeps the
   * plain [VIDEO_BASENAME], so every reader that knows only one recording per session — the desktop
   * app's "Watch Video", the zip loader, the test-farm slicer — still finds the display the trail
   * started on, and the companions' files are additions rather than a rename.
   *
   * The name is operator text from YAML, so it is reduced to `[A-Za-z0-9._-]` before it becomes a
   * path segment; the original name travels in `capture_metadata.json` next to the filename.
   * Reduction can give two names one basename (`kiosk/front`, `kiosk:front`), and a name bound
   * again records a second file, so this is only the wanted basename: the session's capture
   * suffixes `-2`, `-3`, ... onto one already taken. Readers map a file to its device by the
   * metadata's `deviceName`, never by parsing the filename.
   */
  fun companionVideoBasename(deviceName: String): String =
    "$VIDEO_BASENAME-${deviceName.replace(UNSAFE_FILENAME_CHARS, "_").ifEmpty { "device" }}"

  /**
   * The scratch directory a frame-sampling recorder keeps its frames in until it muxes them —
   * [prefix] for the start device's recording, suffixed with [videoBasename] for a companion's, so
   * two recordings in one session directory never share (or clean up) each other's frames.
   */
  fun framesScratchDir(prefix: String, videoBasename: String): String =
    if (videoBasename == VIDEO_BASENAME) prefix else "$prefix-$videoBasename"

  private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
}
