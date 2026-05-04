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

/**
 * Android package-name format: at least two segments, each starting with a letter and
 * containing only letters/digits/underscores, separated by dots.
 *
 * Used to validate `appId` arguments for [AndroidDeviceCommandExecutor.executeShellCommandAs]
 * before they are interpolated into a shell command. Restricting to the package-name grammar
 * means an `appId` cannot smuggle shell metacharacters (spaces, `;`, `&`, `|`, quotes,
 * backticks) through the `run-as` invocation, so we don't need general shell escaping for
 * that token.
 */
private val ANDROID_PACKAGE_NAME_REGEX =
  Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

/**
 * Shared validator used by both `actual` implementations of
 * [AndroidDeviceCommandExecutor.executeShellCommandAs]. The name reflects the underlying
 * `run-as` shell command being invoked, while the public API is named for what it does
 * (executes a shell command as a specific app's UID).
 *
 * Lives here (rather than in each `actual`) so the contract is enforced identically on host
 * and on-device, and so the rationale for each rule travels with one canonical definition.
 *
 * @throws IllegalArgumentException if [appId] is not a syntactically valid Android package
 *   name, or if [command] is blank. The blank-command check matters because `run-as <pkg>`
 *   with no command drops into an interactive shell, which hangs non-interactive callers
 *   like our `executeShellCommand` transport.
 */
internal fun validateRunAsArgs(appId: String, command: String) {
  require(appId.isNotBlank()) { "appId must not be blank" }
  require(ANDROID_PACKAGE_NAME_REGEX.matches(appId)) {
    "appId must be a syntactically valid Android package name (got: '$appId'). " +
      "Expected format: 'com.example.app' — letters/digits/underscores in dot-separated " +
      "segments, each segment starting with a letter."
  }
  require(command.isNotBlank()) {
    "command must not be blank — `run-as <pkg>` without a command drops into an " +
      "interactive shell, which hangs non-interactive callers."
  }
}

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
   * Executes a shell command on the device as the specified app's UID via `run-as`.
   *
   * **Critical requirement: the target app's APK must be marked `android:debuggable="true"`** in
   * its manifest. `run-as` is gated on debuggable APKs by the Android platform — it fails with
   * `run-as: package not debuggable` for release builds. Root is **not** required (and is
   * irrelevant — `run-as` and root are independent privilege mechanisms).
   *
   * ### Why this exists as a first-class method
   *
   * `run-as` is the only standard, supported way for an external process — including a
   * Trailblaze test APK that is **not** signed with the target app's key and **not** running
   * inside the target app's process — to read or write files in another app's
   * `/data/data/<package>/` private directory. This is critical for test setup that needs to
   * seed SharedPreferences, databases, or other private files in an app we cannot modify
   * (the app APK is whatever the team ships; only the test APK is under our control).
   *
   * Without this method documented on the interface, this knowledge is easy to lose and
   * easy to reinvent badly (e.g. attempting to MITM the network, running emulators with
   * `adb root`, or asking app teams to add debug-only ContentProviders).
   *
   * ### Where it works
   *
   * - **Emulators**: works regardless of `adb root` state (rooted or not).
   * - **Physical devices**: works as long as the target APK is debuggable. Production builds
   *   from the Play Store are not debuggable; debug variants installed via `adb install` or
   *   sideload typically are.
   * - **Hosted CI runners**: works in any environment that accepts a debuggable test+app APK
   *   pair (Firebase Test Lab, Sauce Labs, and similar device-cloud services).
   *
   * ### How both implementations satisfy the privilege requirement
   *
   * - **Host JVM driver**: routes through `adb shell`, which executes commands as the
   *   `shell` user (UID 2000).
   * - **On-device instrumentation**: routes through [UiDevice.executeShellCommand], which
   *   uses `UiAutomation.executeShellCommand` — a privileged system bridge that **also**
   *   executes commands as the `shell` user, regardless of the calling test process's UID
   *   or signing identity. This is the property that makes `run-as` work for Trailblaze
   *   even though our test APK runs in its own process, separate from the app under test.
   *
   * ### Common uses
   *
   * - Seeding SharedPreferences XML files in `/data/data/<pkg>/shared_prefs/` for test setup
   * - Reading app state (databases, files) for diagnostics during a test
   * - Cleaning up state between tests without going through the app's UI
   *
   * ### Naming
   *
   * Named `executeShellCommandAs` to mirror the sibling [executeShellCommand] method on this
   * interface, with the `As` suffix indicating the privileged identity switch via `run-as`.
   * The underlying mechanism is the platform `run-as` shell command, but exposing that detail
   * in the method name would diverge from the existing `execute*` naming on this class.
   *
   * ### Example
   *
   * ```
   * executeShellCommandAs(
   *   appId = "com.example.app",
   *   command = "sh -c 'mkdir -p /data/data/com.example.app/shared_prefs && " +
   *     "cp /sdcard/seed_prefs.xml /data/data/com.example.app/shared_prefs/debug.xml'",
   * )
   * ```
   *
   * @param appId the target app's package name (e.g. `"com.example.app"`).
   *   Must be a syntactically valid Android package name (letters/digits/underscores in
   *   dot-separated segments, each segment starting with a letter). Validated to prevent
   *   shell-metacharacter injection through this token.
   * @param command the shell command to execute as the target app's UID. **Must be non-blank** —
   *   `run-as <pkg>` with no command drops into an interactive shell, which hangs
   *   non-interactive callers like the underlying [executeShellCommand] transport.
   *   Multi-step commands should be wrapped in `sh -c '...'` (see example above) so quoting
   *   and `&&` survive the round-trip.
   * @return stdout from the executed command.
   * @throws IllegalArgumentException if [appId] is not a valid Android package name, or if
   *   [command] is blank. See [validateRunAsArgs] for the validation rationale.
   * @see executeShellCommand
   * @see <a href="https://developer.android.com/studio/command-line/adb#runas">adb run-as docs</a>
   */
  fun executeShellCommandAs(appId: String, command: String): String

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
