package xyz.block.trailblaze.util

import java.io.File
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

object IosHostSimctlUtils {

  /**
   * Lists the UDIDs of currently booted iOS simulators via `xcrun simctl list devices booted`.
   * Returns empty on non-macOS hosts or when none are booted.
   *
   * Exists so callers can iterate real device ids instead of passing the `booted` alias directly
   * to a `simctl` subcommand — with more than one simulator booted, `booted` is ambiguous for some
   * subcommands, where enumerating and trying each real UDID is unambiguous regardless.
   */
  fun listBootedDeviceIds(): List<String> {
    if (!isMacOs()) return emptyList()
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "list", "devices", "booted"),
    ).runProcess {}
    val udidRegex = Regex("\\(([0-9A-Fa-f-]{36})\\)\\s*\\(Booted\\)")
    return output.outputLines.mapNotNull { udidRegex.find(it)?.groupValues?.get(1) }
  }

  /**
   * Resolves the on-disk `.app` bundle directory for [appId] on [deviceId] via
   * `xcrun simctl get_app_container <deviceId> <appId> app` — the app's installed bundle itself,
   * as opposed to [clearAppDataContainer]'s `data` container. Returns null on non-macOS hosts, or
   * when the app isn't installed on that device (non-zero exit / no output).
   */
  fun getAppBundlePath(deviceId: String, appId: String): File? {
    if (!isMacOs()) return null
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "get_app_container", deviceId, appId, "app"),
    ).runProcess {}
    if (output.exitCode != 0) return null
    val path = output.outputLines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return File(path).takeIf { it.isDirectory }
  }

  fun clearAppDataContainer(deviceId: String, appId: String) {
    if (!isMacOs()) return
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "get_app_container", deviceId, appId, "data"),
    ).runProcess {}

    output.outputLines.firstOrNull()?.let { dataContainerPath ->
      val dataContainerDirectory = File(dataContainerPath)
      if (dataContainerDirectory.exists() && dataContainerDirectory.isDirectory) {
        Console.log("Clearing $appId data container under $dataContainerPath")
        dataContainerDirectory.listFiles()?.forEach { it.deleteRecursively() }
      }
    }
  }

  /**
   * Sets the iOS simulator's pasteboard (clipboard) to the given text via
   * `xcrun simctl pbcopy <deviceId>`, which reads the new pasteboard content from
   * stdin. (The `pasteboard` subcommand does not exist in current Xcode versions;
   * `pbcopy`/`pbpaste`/`pbsync` are the supported pasteboard subcommands.) Used by
   * the cross-platform `mobile_setClipboard` tool's iOS branch.
   *
   * Both stdout and stderr are inherited from the parent process so we don't have to
   * drain them on this thread — pre-redirecting either to PIPE without consuming the
   * stream risks deadlocking the child once the OS pipe buffer fills, even if
   * `pbcopy` is silent under normal conditions.
   */
  fun setPasteboard(deviceId: String, text: String) {
    if (!isMacOs()) error("setPasteboard is only supported on macOS hosts")
    val process = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "pbcopy", deviceId),
    ).redirectOutput(ProcessBuilder.Redirect.INHERIT)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
    process.outputStream.use { it.write(text.toByteArray()) }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      error("xcrun simctl pbcopy failed with exit code $exitCode for device $deviceId")
    }
  }

  /**
   * Reads the iOS simulator's current pasteboard (clipboard) content via
   * `xcrun simctl pbpaste <deviceId>`. Counterpart to [setPasteboard]. Used by the iOS branch
   * of the cross-platform `mobile_pasteClipboard` tool to read the value `mobile_setClipboard`
   * wrote, so it can be typed directly (mirroring the Android branch's `InputTextCommand`
   * approach) instead of relying on Maestro's iOS `PasteTextCommand` (long-press + tap "Paste"
   * on the system edit menu), which does not reliably complete in simulator/CI environments.
   */
  fun getPasteboard(deviceId: String): String {
    if (!isMacOs()) error("getPasteboard is only supported on macOS hosts")
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "pbpaste", deviceId),
    ).runProcess {}
    if (output.exitCode != 0) {
      error("xcrun simctl pbpaste failed with exit code ${output.exitCode} for device $deviceId")
    }
    return output.fullOutput
  }

  /**
   * Lists the bundle identifiers of the apps installed on the given iOS simulator via
   * `xcrun simctl listapps <deviceId>`. Backs the iOS branch of the cross-platform
   * `mobile_listInstalledApps` tool.
   *
   * Returns an empty list on non-macOS hosts (iOS simulators only exist on a Mac) so callers
   * on Linux/Windows get a benign empty inventory rather than a process-spawn failure — the
   * same shape `IosHostUtils.getInstalledAppIds` has always returned off-Mac.
   */
  fun listInstalledAppIds(deviceId: String): List<String> {
    if (!isMacOs()) return emptyList()
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "listapps", deviceId),
    ).runProcess {}
    return parseInstalledAppIdsFromListApps(output.outputLines)
  }

  /**
   * Parses bundle identifiers from `xcrun simctl listapps` output lines.
   *
   * The output is a plist-style format where each app's block opens with a header line:
   * ```
   *     "com.example.app" =     {
   * ```
   *
   * Extracts the quoted bundle identifiers from those header lines, filtering out app group
   * identifiers (those starting with `group.`) and blank entries.
   *
   * The value must be the opening brace `{` — i.e. only a block-opening header counts, never an
   * arbitrary `"key" = "value";` line. Without that anchor, nested `GroupContainers` entries (e.g.
   * `"243LU875E5.groups.com.apple.podcasts" = "file://…";`) would be mis-parsed as installed apps.
   * This matches the sibling [IosHostUtils.parseInstalledAppsWithDisplayNames] bundle-id regex.
   *
   * `internal` rather than public: the only legitimate caller is [listInstalledAppIds] in this
   * same object; visibility is widened just enough for the same-module parser unit test.
   */
  internal fun parseInstalledAppIdsFromListApps(outputLines: List<String>): List<String> {
    // Handles variable leading whitespace, a quoted bundle id, and variable spacing around the
    // `=` before the opening brace that starts the app's dictionary block.
    val regex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*\\{")
    return outputLines
      .mapNotNull { line -> regex.matchEntire(line)?.groupValues?.get(1) }
      .filter { it.isNotBlank() && !it.startsWith("group.") }
      .distinct()
  }

  /**
   * Lists the installed apps on the given iOS simulator with structured per-app metadata
   * ([InstalledApp.appId], [InstalledApp.label], [InstalledApp.type], [InstalledApp.version]) via
   * `xcrun simctl listapps <deviceId>`. Backs the iOS branch of `mobile_listInstalledApps` when
   * called with `detailed = true`.
   *
   * iOS gets every field for free: a single `listapps` invocation already reports
   * `ApplicationType`, the display name, and the version, so unlike the Android adb path there's no
   * label/version gap here.
   *
   * Returns an empty list on non-macOS hosts (iOS simulators only exist on a Mac), matching
   * [listInstalledAppIds].
   */
  fun listInstalledAppsDetailed(deviceId: String): List<InstalledApp> {
    if (!isMacOs()) return emptyList()
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("xcrun", "simctl", "listapps", deviceId),
    ).runProcess {}
    return parseInstalledAppsFromListApps(output.outputLines)
  }

  /**
   * Parses structured [InstalledApp] metadata from `xcrun simctl listapps` output lines.
   *
   * Each app's block opens with a header line — `"<bundleId>" = {` — under which the relevant
   * fields appear:
   * ```
   *     "com.apple.Preferences" =     {
   *         ApplicationType = System;
   *         CFBundleDisplayName = Settings;
   *         CFBundleName = Settings;
   *         CFBundleShortVersionString = "1.0";
   *         CFBundleVersion = 1;
   *         ...
   *     };
   * ```
   *
   * Mapping:
   * - [InstalledApp.appId] ← the quoted bundle id in the header line
   * - [InstalledApp.isSystemApp] ← `ApplicationType` (`System` → `true`; `User` and anything else /
   *   absent → `false`, so an unrecognized type is never guessed into the system bucket)
   * - [InstalledApp.label] ← `CFBundleDisplayName`, falling back to `CFBundleName`
   * - [InstalledApp.version] ← `CFBundleShortVersionString` (the user-visible version), falling
   *   back to `CFBundleVersion`
   * - [InstalledApp.buildNumber] ← `CFBundleVersion` (the machine build number)
   * - [InstalledApp.installPath] ← `Path` (the `.app` bundle path)
   *
   * Like [parseInstalledAppIdsFromListApps], only the block-opening header (`= {`) is treated as an
   * app, so nested `GroupContainers` entries (`"<id>" = "file://…";`) and `group.*` ids are never
   * mistaken for installed apps. Field lines are only attributed to the most recent app header, and
   * fields are read with last-write-wins (the rare repeated key keeps the latest value).
   *
   * `internal` (widened just enough for the same-module parser unit test): the only production
   * caller is [listInstalledAppsDetailed].
   */
  internal fun parseInstalledAppsFromListApps(outputLines: List<String>): List<InstalledApp> {
    val headerRegex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*\\{")
    // Field lines look like `    Key = Value;` or `    Key = "Value";` — capture the unquoted body.
    // Stops at the first `"` or `;`, so a value containing an escaped quote (`= "a \"b\" c";`) would
    // truncate — not seen in real `simctl listapps` output (display names aren't quote-escaped there),
    // so this is an accepted limitation rather than full plist-string parsing.
    fun fieldRegex(key: String) = Regex("^\\s+$key\\s*=\\s*\"?([^;\"]*)\"?\\s*;")
    val applicationTypeRegex = fieldRegex("ApplicationType")
    val displayNameRegex = fieldRegex("CFBundleDisplayName")
    val bundleNameRegex = fieldRegex("CFBundleName")
    val shortVersionRegex = fieldRegex("CFBundleShortVersionString")
    val bundleVersionRegex = fieldRegex("CFBundleVersion")
    val pathRegex = fieldRegex("Path")

    // Preserve first-seen header order; ignore `group.*` headers like the id-only parser.
    val ordered = LinkedHashMap<String, MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null

    for (line in outputLines) {
      val header = headerRegex.matchEntire(line)?.groupValues?.get(1)
      if (header != null) {
        if (header.isNotBlank() && !header.startsWith("group.")) {
          current = ordered.getOrPut(header) { mutableMapOf() }
        } else {
          current = null
        }
        continue
      }
      val fields = current ?: continue
      applicationTypeRegex.matchEntire(line)?.let { fields["type"] = it.groupValues[1].trim() }
      displayNameRegex.matchEntire(line)?.let { fields["displayName"] = it.groupValues[1].trim() }
      bundleNameRegex.matchEntire(line)?.let { fields["bundleName"] = it.groupValues[1].trim() }
      shortVersionRegex.matchEntire(line)?.let { fields["shortVersion"] = it.groupValues[1].trim() }
      bundleVersionRegex.matchEntire(line)?.let { fields["bundleVersion"] = it.groupValues[1].trim() }
      pathRegex.matchEntire(line)?.let { fields["path"] = it.groupValues[1].trim() }
    }

    return ordered.map { (appId, fields) ->
      val isSystemApp = when (fields["type"]) {
        "System" -> true
        // `User` and the essentially-never non-standard ApplicationType both map to false — we
        // don't guess an unrecognized type into the system bucket.
        else -> false
      }
      val label = (fields["displayName"] ?: fields["bundleName"])?.takeIf { it.isNotBlank() }
      val version = (fields["shortVersion"] ?: fields["bundleVersion"])?.takeIf { it.isNotBlank() }
      val buildNumber = fields["bundleVersion"]?.takeIf { it.isNotBlank() }
      val installPath = fields["path"]?.takeIf { it.isNotBlank() }
      InstalledApp(
        appId = appId,
        isSystemApp = isSystemApp,
        label = label,
        version = version,
        buildNumber = buildNumber,
        installPath = installPath,
      )
    }
  }
}
