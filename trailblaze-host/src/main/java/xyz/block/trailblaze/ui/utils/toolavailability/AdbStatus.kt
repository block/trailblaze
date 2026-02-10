package xyz.block.trailblaze.ui.utils.toolavailability

/** Describes the availability status of ADB on this machine. */
sealed class AdbStatus {
  /** ADB is on PATH and ready to use. */
  data object Available : AdbStatus()

  /**
   * ADB was found inside an Android SDK installation, but is not on the shell PATH.
   *
   * @param sdkPath The root of the Android SDK that contains the `platform-tools/adb` binary.
   * @param adbPath The full path to the `adb` binary found.
   */
  data class SdkFoundNotOnPath(val sdkPath: String, val adbPath: String) : AdbStatus()

  /** ADB could not be found anywhere on this machine. */
  data object NotInstalled : AdbStatus()
}
