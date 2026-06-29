package xyz.block.trailblaze.util

import java.io.File
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

object IosHostSimctlUtils {

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
}
