package xyz.block.trailblaze.adb

/**
 * Multiplatform abstraction for executing ADB shell commands.
 * 
 * This interface provides a unified way to execute shell commands on Android devices,
 * with platform-specific implementations:
 * - **Android/Instrumentation**: Uses UiDevice.executeShellCommand() to run commands on the device
 * - **JVM/Host**: Uses dadb library (via HostAdbExecutor) to run commands from the host machine
 * 
 * Example usage:
 * ```kotlin
 * val adb = TrailblazeAdb.instance
 * val output = adb.shell("pm list packages")
 * ```
 */
expect object TrailblazeAdb {
  /**
   * Execute a shell command on the Android device.
   * 
   * @param command The shell command to execute (e.g., "pm list packages", "am force-stop com.app")
   * @return The command output as a string
   */
  fun shell(command: String): String

  /**
   * Check if an app is currently running.
   * 
   * @param packageName The package name to check
   * @return true if the app has an active process
   */
  fun isAppRunning(packageName: String): Boolean

  /**
   * Force stop an app.
   * 
   * @param packageName The package name to force stop
   */
  fun forceStopApp(packageName: String)

  /**
   * Clear app data.
   * 
   * @param packageName The package name to clear data for
   */
  fun clearAppData(packageName: String)

  /**
   * Grant a permission to an app.
   * 
   * @param packageName The package name to grant permission to
   * @param permission The permission to grant (e.g., "android.permission.CAMERA")
   */
  fun grantPermission(packageName: String, permission: String)

  /**
   * List all installed packages on the device.
   * 
   * @return List of package names
   */
  fun listInstalledPackages(): List<String>

  /**
   * Get the device serial number.
   * 
   * @return The device serial number
   */
  fun getSerialNumber(): String
}
