package xyz.block.trailblaze.device

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Expected class for executing device commands on Android devices.
 * Implementations may use ADB (from host JVM) or direct Android APIs (from on-device Android).
 */
expect class AndroidDeviceCommandExecutor(
  deviceId: TrailblazeDeviceId,
) {
  val deviceId: TrailblazeDeviceId

  /**
   * Sends a broadcast intent to the Android device.
   */
  fun sendBroadcast(intent: BroadcastIntent)

  /**
   * Executes a shell command on the Android device.
   */
  fun executeShellCommand(command: String): String

  /**
   * Force stops the specified app.
   */
  fun forceStopApp(appId: String)

  /**
   * Clears app data for the specified package.
   */
  fun clearAppData(appId: String)

  /**
   * Checks if the specified app is running.
   */
  fun isAppRunning(appId: String): Boolean

  /**
   * Writes a file to the device's public Downloads directory.
   * On Android, this uses MediaStore/ContentResolver for Q+ compatibility.
   * On JVM, this uses adb to push the file.
   *
   * @param fileName The file name (not a full path) to write in the Downloads folder
   * @param content The file content as a byte array
   */
  fun writeFileToDownloads(fileName: String, content: ByteArray)

  /**
   * Deletes a file from the device's public Downloads directory if it exists.
   *
   * @param fileName The file name (not a full path) to delete from the Downloads folder
   */
  fun deleteFileFromDownloads(fileName: String)

  /**
   * Return the installed apps on a device
   */
  fun listInstalledApps(): List<String>
}
