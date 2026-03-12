package xyz.block.trailblaze.devices

/**
 * Base marker interface for device drivers.
 * All device interaction backends must implement this interface.
 */
interface DeviceDriver {
  /** The unique name of this driver. */
  val driverName: String

  /** Whether the device is currently connected and available. */
  val isConnected: Boolean
}

/**
 * Data class representing the result of an ADB command execution.
 */
data class AdbCommandResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
) {
  /** Whether the command executed successfully (exit code 0). */
  val success: Boolean
    get() = exitCode == 0
}

/**
 * Interface for device drivers that support ADB (Android Debug Bridge) commands.
 * Provides methods for interacting with Android devices via shell commands and gestures.
 */
interface AdbDeviceDriver : DeviceDriver {
  /** Executes a shell command on the device. */
  fun shell(command: String): AdbCommandResult

  /** Taps the device screen at the specified coordinates. */
  fun tap(x: Int, y: Int): AdbCommandResult

  /** Long-presses the device screen at the specified coordinates. */
  fun longPress(x: Int, y: Int): AdbCommandResult

  /**
   * Swipes across the device screen.
   *
   * @param startX Starting X coordinate
   * @param startY Starting Y coordinate
   * @param endX Ending X coordinate
   * @param endY Ending Y coordinate
   * @param durationMs Duration of the swipe in milliseconds (default 300)
   */
  fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Int = 300,
  ): AdbCommandResult

  /** Inputs text on the device. */
  fun inputText(text: String): AdbCommandResult

  /** Presses a key with the specified key code. */
  fun pressKey(keyCode: Int): AdbCommandResult

  /** Takes a screenshot of the device screen. Returns null if unsuccessful. */
  fun takeScreenshot(): ByteArray?

  /** Dumps the view hierarchy of the current screen. Returns null if unsuccessful. */
  fun dumpViewHierarchy(): String?

  /**
   * Launches an app on the device.
   *
   * @param packageId The package ID (e.g., "com.example.app")
   * @param activity Optional activity name (default null)
   */
  fun launchApp(packageId: String, activity: String? = null): AdbCommandResult

  /** Clears all data for the specified app. */
  fun clearAppData(packageId: String): AdbCommandResult

  /** Gets the current activity name. Returns null if unable to determine. */
  fun getCurrentActivity(): String?

  /** Gets the current package name. Returns null if unable to determine. */
  fun getCurrentPackage(): String?
}
