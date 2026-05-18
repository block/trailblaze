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
}
