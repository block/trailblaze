package xyz.block.trailblaze.device

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Expected class for executing device commands on Android devices.
 * Implementations may use ADB (from host JVM) or direct Android APIs (from on-device Android).
 */
/**
 * AppOps operation name for the MANAGE_EXTERNAL_STORAGE permission (API 30+).
 * Equivalent to [android.app.AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE], which is not
 * available on JVM.
 */
const val APPOPS_MANAGE_EXTERNAL_STORAGE = "MANAGE_EXTERNAL_STORAGE"

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
   * Grants an AppOps permission to the specified app via `appops set <appId> <permission> allow`.
   *
   * Requires ADB shell access or an instrumentation test context (which runs as the `shell` user
   * via [UiDevice.executeShellCommand]). Does **not** require root. Will fail with a permission
   * denial if called from a regular (non-privileged) app process.
   *
   * This only works for AppOps operations, **not** standard runtime permissions (which require
   * `pm grant`). Common supported operations include:
   * - `MANAGE_EXTERNAL_STORAGE` — broad file access (API 30+)
   * - `REQUEST_INSTALL_PACKAGES` — install unknown apps
   * - `SYSTEM_ALERT_WINDOW` — draw overlays
   * - `WRITE_SETTINGS` — modify system settings
   * - `ACCESS_NOTIFICATIONS` — read notifications
   * - `PICTURE_IN_PICTURE` — picture-in-picture mode
   *
   * @param appId the application package name
   * @param permission the AppOps operation name (e.g. [APPOPS_MANAGE_EXTERNAL_STORAGE])
   * @see <a href="https://developer.android.com/reference/android/app/AppOpsManager">AppOpsManager</a>
   */
  fun grantAppOpsPermission(appId: String, permission: String)

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

  /**
   * Adds a contact to the device's contacts provider.
   * On Android, this uses ContentProviderOperation batch insert.
   * On JVM, this uses `adb shell content insert` commands.
   */
  fun addContact(contact: DeviceContact)

  /**
   * Inserts an SMS message into the device's inbox, simulating a received message.
   * On Android, this inserts via the Telephony SMS content provider.
   * On JVM, this uses `adb shell content insert` commands.
   */
  fun insertSmsIntoInbox(message: DeviceSmsMessage)

  /**
   * Sets the device clipboard to the specified text.
   * On Android, this uses ClipboardManager directly.
   * On JVM, this uses `adb shell` commands.
   */
  fun setClipboard(text: String)

  /**
   * Copies a test resource (from the test APK assets on Android, or classpath on JVM)
   * to a specified path on the device.
   *
   * @param resourcePath Path to the resource (e.g., 'benchmarks/audio/song1.mp3')
   * @param devicePath Absolute destination path on the device
   */
  fun copyTestResourceToDevice(resourcePath: String, devicePath: String)

  /**
   * Waits until the specified app is in the foreground.
   * Polls at [checkIntervalMs] intervals up to [maxWaitMs].
   *
   * @return true if the app reached the foreground within the timeout, false otherwise
   */
  fun waitUntilAppInForeground(
    appId: String,
    maxWaitMs: Long = 30_000,
    checkIntervalMs: Long = 200,
  ): Boolean
}
