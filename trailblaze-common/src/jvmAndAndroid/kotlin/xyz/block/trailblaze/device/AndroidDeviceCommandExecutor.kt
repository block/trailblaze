package xyz.block.trailblaze.device

import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

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
 * Android permission name format: a package-qualified identifier (e.g.
 * `android.permission.BLUETOOTH_CONNECT`, `com.example.MY_PERMISSION`). Two or more
 * segments separated by dots, each segment letters/digits/underscores, leading segment
 * starting with a letter. This matches both platform permissions and app-defined ones.
 */
private val ANDROID_PERMISSION_NAME_REGEX =
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

/**
 * Shared swallow-and-log used by both `actual` implementations of
 * [AndroidDeviceCommandExecutor.grantRuntimePermission].
 *
 * Callers pass a [block] that invokes the platform-specific `pm grant` transport. If the
 * block returns a non-blank string (treated as a stderr-like diagnostic — `pm grant` writes
 * to stderr when it can't grant), the message is logged. If the block throws, the exception
 * is logged and swallowed. Either way control returns to the caller so a permission loop
 * over a conservative superset does not abort partway when one entry isn't declared in the
 * target manifest.
 *
 * Tested directly in `GrantRuntimePermissionTest` — the swallow contract is load-bearing
 * for any caller that grants a conservative superset of permissions in a loop (one entry
 * missing from one app variant's manifest must not abort the launch) and must not silently
 * flip if a future refactor changes the underlying transport.
 */
/**
 * Validates `appId` and `permission` strings before they're interpolated into a `pm grant`
 * shell invocation. Mirrors [validateRunAsArgs] — restricting both tokens to the Android
 * package/permission grammar means neither can smuggle shell metacharacters through the
 * device's `sh`. Today's only caller passes a hardcoded const list, so this is a defensive
 * guard against a future caller forwarding less-trusted input.
 */
internal fun validateGrantRuntimePermissionArgs(appId: String, permission: String) {
  require(appId.isNotBlank()) { "appId must not be blank" }
  require(ANDROID_PACKAGE_NAME_REGEX.matches(appId)) {
    "appId must be a syntactically valid Android package name (got: '$appId')."
  }
  require(permission.isNotBlank()) { "permission must not be blank" }
  require(ANDROID_PERMISSION_NAME_REGEX.matches(permission)) {
    "permission must be a syntactically valid Android permission name (got: '$permission'). " +
      "Expected format: dot-separated identifier such as 'android.permission.BLUETOOTH_CONNECT'."
  }
}

internal fun handleGrantRuntimePermissionOutcome(
  appId: String,
  permission: String,
  block: () -> String?,
) {
  try {
    val result = block()?.trim().orEmpty()
    if (result.isNotEmpty()) {
      Console.log("[AndroidDeviceCommandExecutor] pm grant $appId $permission: $result")
    }
  } catch (e: CancellationException) {
    // Cancellation must propagate to honor structured concurrency — swallowing it here
    // would silently mask coroutine cancellation if any caller (or future refactor of
    // the underlying transport) runs inside a suspend context.
    throw e
  } catch (e: Exception) {
    Console.log("[AndroidDeviceCommandExecutor] pm grant $appId $permission failed: ${e.message}")
  }
}

/**
 * Pure parser behind the host-JVM `listInstalledAppsDetailed`: turns the output of a single
 * `adb shell dumpsys package packages` into a sorted [InstalledApp] list.
 *
 * One call yields almost the whole record — `isSystemApp` (the `SYSTEM` token in the package
 * `flags`/`pkgFlags` line), [InstalledApp.version] (`versionName`), [InstalledApp.buildNumber]
 * (`versionCode`), and [InstalledApp.installPath] (`codePath`). [InstalledApp.label] stays `null`:
 * dumpsys doesn't carry the human display name (that needs resource resolution, i.e. the on-device
 * `PackageManager` path or `aapt`).
 *
 * This is the same `dumpsys package` format [AndroidHostAdbUtils.getAppVersionInfo] already parses
 * for a single package's version — this just scans every `Package [<id>] (...)` block in one pass.
 * Each field is captured at first occurrence within a block (the package-level values precede the
 * per-user sub-block), matching `getAppVersionInfo`'s codePath-anchored read. Result is sorted by
 * id for a deterministic, diffable inventory.
 *
 * Lives at file scope (not inside an `actual`) so the host parsing is unit-testable without a
 * device, mirroring [validateRunAsArgs].
 */
internal fun parseInstalledAppsFromDumpsys(dumpsysOutput: String): List<InstalledApp> {
  val headerRegex = Regex("""^\s*Package \[([^]]+)]""")
  val codePathRegex = Regex("""^\s*codePath=(.+)$""")
  val versionCodeRegex = Regex("""versionCode=(\d+)""")
  val versionNameRegex = Regex("""^\s*versionName=(.+)$""")
  val flagsRegex = Regex("""^\s*(?:pkgFlags|flags)=\[(.*)]""")
  // Standalone SYSTEM token. `\b` won't match inside UPDATED_SYSTEM_APP (the underscore is a word
  // char, so there's no boundary), so this is FLAG_SYSTEM specifically, not any *_SYSTEM_* flag.
  val systemTokenRegex = Regex("""\bSYSTEM\b""")

  val apps = mutableListOf<InstalledApp>()
  var appId: String? = null
  var codePath: String? = null
  var versionName: String? = null
  var versionCode: String? = null
  var isSystem = false

  fun flush() {
    val id = appId ?: return
    apps += InstalledApp(
      appId = id,
      isSystemApp = isSystem,
      // dumpsys carries no human display name — that's the on-device PackageManager path's job.
      label = null,
      version = versionName,
      buildNumber = versionCode,
      installPath = codePath,
    )
  }

  for (line in dumpsysOutput.lines()) {
    val header = headerRegex.find(line)
    if (header != null) {
      flush()
      appId = header.groupValues[1]
      codePath = null
      versionName = null
      versionCode = null
      isSystem = false
      continue
    }
    if (appId == null) continue
    // dumpsys prints absent values as the literal `null` (e.g. `versionName=null` for an app with
    // no version) — normalize that to an actual null so we omit the field rather than emit "null".
    fun String.orNullLiteral(): String? = trim().takeIf { it.isNotEmpty() && it != "null" }
    if (codePath == null) codePathRegex.find(line)?.let { codePath = it.groupValues[1].orNullLiteral() }
    if (versionCode == null) versionCodeRegex.find(line)?.let { versionCode = it.groupValues[1] }
    if (versionName == null) versionNameRegex.find(line)?.let { versionName = it.groupValues[1].orNullLiteral() }
    if (!isSystem) {
      flagsRegex.find(line)?.let { isSystem = systemTokenRegex.containsMatchIn(it.groupValues[1]) }
    }
  }
  flush()
  return apps.sortedBy { it.appId }
}

expect class AndroidDeviceCommandExecutor(
  deviceId: TrailblazeDeviceId,
) {
  val deviceId: TrailblazeDeviceId

  /**
   * Whether [executeShellCommand] runs its argument through a POSIX shell interpreter
   * (`sh -c`) on the device side.
   *
   * This differs by transport, and it is the property a caller must consult before deciding
   * whether to shell-quote/escape a command or wrap it with shell syntax (pipes, `;`, `$?`,
   * globbing):
   *
   *  - **Host (JVM actual): `true`.** Commands travel over the dadb wire to `adbd`, which runs
   *    them via `sh -c`. Shell metacharacters and quoting are honored, so escaping is required
   *    for safety and an appended `$?` exit sentinel can recover the exit code.
   *  - **On-device (Android actual): `false`.** [executeShellCommand] routes through
   *    [android.app.UiAutomationConnection.executeShellCommand], which hands the string to
   *    [Runtime.exec] — it splits on whitespace and execs the tokens **directly, with no shell
   *    interpreter**. Shell-quoting a token (e.g. `'su'`) embeds the quotes as literal characters
   *    in the program name (→ `Cannot run program "'su'"`), and a `$?` exit sentinel is exec'd
   *    as literal arguments rather than evaluated. On this transport, callers must pass argv
   *    tokens unescaped via [executeShellCommandArgs] and cannot rely on a shell for exit codes.
   *
   * Surfacing this as a property (rather than baking the assumption into each caller) keeps the
   * transport contract explicit: [xyz.block.trailblaze.mobile.tools.AdbShellTrailblazeTool] reads
   * it to pick the shell-string path vs. the raw-argv path so the same tool works correctly on
   * both the daemon JVM and the on-device QuickJS dispatch.
   */
  val usesShellInterpreter: Boolean

  /**
   * Sends a broadcast intent to the Android device.
   */
  fun sendBroadcast(intent: BroadcastIntent)

  /**
   * Executes a shell command on the Android device.
   */
  fun executeShellCommand(command: String): String

  /**
   * Executes a shell command on the device, treating each element of [args] as a distinct
   * argument token.
   *
   * Unlike [executeShellCommand], this method does not split on spaces on the host side. On the
   * JVM actual, each token is forwarded individually to the underlying transport (dadb) which joins
   * them with spaces before sending to the device shell. On the Android actual, tokens are joined
   * with spaces and passed directly to [AdbCommandUtil.execShellCommand] — no shell escaping is
   * applied, because [android.app.UiAutomationConnection.executeShellCommand] routes through
   * [Runtime.exec], which splits on whitespace and execs directly without a shell interpreter.
   * Applying shell quoting (e.g. single-quoting each token) would embed the quotes as literal
   * characters in the program name, causing "No such file or directory".
   *
   * **Platform limitation:** individual arguments must not contain whitespace on Android.
   * [Runtime.exec] re-splits the joined string on whitespace, so a single arg with an embedded
   * space would silently become two tokens. The Android actual enforces this with a
   * [require] check that throws [IllegalArgumentException] immediately rather than letting the
   * command misbehave silently. Arguments for the current callers (`su`, `root`, `pm`, package
   * names) never contain whitespace, so this is not a practical restriction for the existing
   * use-cases.
   *
   * Do **not** pre-escape the individual arguments.
   *
   * @return stdout from the executed command.
   * @throws IllegalArgumentException if any argument contains whitespace (Android actual only).
   */
  fun executeShellCommandArgs(vararg args: String): String

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
   * Grants a standard Android runtime permission to the specified app via `pm grant`.
   *
   * Use this for `protectionLevel="dangerous"` permissions like `BLUETOOTH_CONNECT`,
   * `POST_NOTIFICATIONS`, `ACCESS_FINE_LOCATION`, `CAMERA`, etc. — the kind that normally
   * prompt the user with a runtime dialog on first request. Granting before launch
   * suppresses the dialog entirely, so trails don't have to model the OS overlay.
   *
   * **Distinction from [grantAppOpsPermission]:** AppOps covers a separate class of
   * privileged operations (broad file access, system alert windows, install unknown apps)
   * controlled by `appops set`, not `pm grant`. Both are needed in different cases —
   * runtime perms (this method) gate user-facing dangerous APIs, AppOps gate
   * platform-privileged operations.
   *
   * **Failure handling:** the underlying `pm grant` writes a stderr line and exits non-zero
   * when the target package doesn't declare the permission in its manifest, when the
   * permission isn't a changeable runtime permission, or when the package isn't installed.
   * Implementations tolerate that — they log the stderr (so a regression is debuggable)
   * but do not throw. Callers can grant a conservative superset without each variant of
   * the target app having to declare every entry.
   *
   * Requires `adb shell` privilege (UID 2000) — works through the host adb path or
   * on-device instrumentation's UiAutomation. Does **not** require root.
   *
   * Android-only — the iOS permission model is interaction-driven rather than
   * pre-grantable, so there is no cross-platform analog on the iOS driver path.
   *
   * @param appId the application package name
   * @param permission the fully-qualified Android permission (e.g. `"android.permission.BLUETOOTH_CONNECT"`)
   * @see grantAppOpsPermission
   * @see <a href="https://developer.android.com/studio/command-line/adb#pm">adb pm docs</a>
   */
  fun grantRuntimePermission(appId: String, permission: String)

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
   * Writes an image into the device's public Pictures directory and registers it as a MediaStore
   * Images row, so a gallery / photo picker finds it under scoped storage.
   * On-device this does a `MediaStore.Images` `ContentResolver.insert` (scoped-storage-safe,
   * auto-registered); from the host it `adb push`es the bytes then triggers a MediaStore `scan_file`.
   *
   * @param fileName The file name (not a full path) to write in the Pictures folder
   * @param content The image content as a byte array
   * @param mimeType The image MIME type (defaults to `image/png`)
   */
  fun writeFileToImages(fileName: String, content: ByteArray, mimeType: String = "image/png")

  /**
   * Deletes a file from the device's public Downloads directory if it exists.
   *
   * @param fileName The file name (not a full path) to delete from the Downloads folder
   */
  fun deleteFileFromDownloads(fileName: String)

  /**
   * Writes raw bytes to an arbitrary absolute path on the device, creating parent directories
   * as needed and overwriting any existing file.
   *
   * This is the low-level byte-transfer primitive: it moves a file **body** onto the device
   * without going through the device shell's stdin, which is the one thing
   * [executeShellCommand]-style composition can't do reliably:
   *  - **Host JVM**: `adb push` (sync protocol). Bypasses the shell service entirely, so it isn't
   *    subject to the stdin/EXIT-packet hang that piping a body through `adb shell` hits.
   *  - **On-device**: a direct `java.io.File` write for app-writable paths, falling back to a
   *    temp-file + `cp` for paths that need the `shell` UID (e.g. public storage on scoped-storage
   *    devices). Neither path passes the body as a shell argument, so there is no `ARG_MAX` ceiling
   *    the way a `base64 -d` argv would have.
   *
   * Filesystem-only: this does **not** register the file with MediaStore (a plain path write isn't
   * MediaStore-indexed on scoped storage). If a consumer must find the file via a MediaStore query,
   * register it separately (e.g. `cmd media_scanner scan <path>` / `content insert`); for the
   * public Downloads MediaStore collection specifically, use [writeFileToDownloads] instead.
   *
   * @param devicePath Absolute destination path on the device (e.g. `/storage/emulated/0/Download/foo.bin`).
   * @param content The file content as a byte array.
   */
  fun writeFileToDevice(devicePath: String, content: ByteArray)

  /**
   * Return the installed apps on a device
   */
  fun listInstalledApps(): List<String>

  /**
   * Returns the installed apps on a device with structured per-app metadata, the richer
   * counterpart to [listInstalledApps] (which returns ids only).
   *
   * Field coverage by actual:
   * - **Host JVM** (adb): a single `dumpsys package packages` call yields `isSystemApp`, version
   *   ([InstalledApp.version]), build number ([InstalledApp.buildNumber]), and install path
   *   ([InstalledApp.installPath]) — see [parseInstalledAppsFromDumpsys]. Only [InstalledApp.label]
   *   is `null` here: adb has no cheap label lookup (it would require `aapt dump badging` on a
   *   pulled APK). [includeLabelsAndVersions] has no effect on host — dumpsys returns the versions
   *   regardless, and the label is unavailable either way.
   * - **On-device** (`PackageManager`): a single `getInstalledPackages(0)` call carries the version
   *   and build number plus each app's `applicationInfo` (`FLAG_SYSTEM` and `sourceDir`), so
   *   `isSystemApp` / `installPath` are always populated and there's no per-app Binder fan-out. When
   *   [includeLabelsAndVersions] is `true` it additionally resolves the display name via
   *   `getApplicationLabel` (a local resource read, the one genuinely per-app cost).
   *
   * @param includeLabelsAndVersions when `true`, the on-device actual additionally resolves
   *   [InstalledApp.label] / [InstalledApp.version] / [InstalledApp.buildNumber] (the per-app
   *   lookups). When `false`, on-device returns only [InstalledApp.appId] / [InstalledApp.isSystemApp]
   *   / [InstalledApp.installPath] (the free fields). The host actual ignores it (dumpsys is one
   *   all-in-one call).
   */
  fun listInstalledAppsDetailed(includeLabelsAndVersions: Boolean): List<InstalledApp>

  /**
   * Disables a package for the current user via `pm disable-user`.
   *
   * Useful for preventing an authenticator or system service from running during a test so the
   * app under test cannot silently auto-authenticate via AccountManager. Requires shell user
   * privileges (available via ADB or UiAutomation.executeShellCommand in instrumentation tests).
   *
   * Use [enablePackageForUser] to restore the package after the test action completes.
   *
   * @param packageId the package to disable
   */
  fun disablePackageForUser(packageId: String)

  /**
   * Re-enables a package for the current user via `pm enable`.
   *
   * Counterpart to [disablePackageForUser]. Safe to call even if the package is already enabled.
   *
   * @param packageId the package to enable
   */
  fun enablePackageForUser(packageId: String)

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
   * Returns the last text that was passed to [setClipboard] from this executor,
   * or an empty string if [setClipboard] has not been called.
   *
   * Used by `mobile_pasteClipboard` to drive the paste-into-focused-field flow on
   * Android. We deliberately don't read `ClipboardManager.getPrimaryClip` directly:
   * Android 10+ restricts that to the currently-focused app, and instrumentation
   * runs in a separate process from the app under test — so a direct read after
   * our own [setClipboard] would return null/empty. Caching the value here keeps
   * the round trip deterministic regardless of who has focus.
   */
  fun getClipboard(): String

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

/**
 * Raw-argv plan for [AndroidDeviceCommandExecutor.writeFileToDevice]'s on-device fallback:
 * `mkdir -p <parent>`, then `cp <staging> <dest>`. Deliberately NO `shellEscape()` — the
 * on-device transport (UiAutomation → `Runtime.exec`) has no shell, so quoted tokens pass
 * literally and `cp` fails silently. Same rule as `android_adbShell`'s argv dispatch
 * (devlog 2026-06-30).
 */
internal fun buildShellCpFallbackCommands(stagingPath: String, devicePath: String): List<List<String>> {
  val parent = devicePath.substringBeforeLast('/', missingDelimiterValue = "")
  return buildList {
    if (parent.isNotEmpty()) add(listOf("mkdir", "-p", parent))
    add(listOf("cp", stagingPath, devicePath))
  }
}
