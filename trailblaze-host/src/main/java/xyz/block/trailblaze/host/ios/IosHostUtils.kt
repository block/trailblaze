package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.util.CommandProcessResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object IosHostUtils {

  fun killAppOnSimulator(deviceId: String, appId: String) {
    TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "terminate",
        deviceId,
        appId,
      ),
    ).runProcess {}
  }

  fun getInstalledAppIds(deviceId: String): Set<String> {
    val output: CommandProcessResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "listapps",
        deviceId,
      ),
    ).runProcess {}

    // Parse the output to extract bundle identifiers
    // The output is a plist-style format where each app is on a line like:
    //     "com.example.app" =     {
    // We want to extract just the bundle identifiers (the quoted strings)
    val installedAppIds = output.outputLines
      .mapNotNull { line ->
        // More flexible regex that handles:
        // - Variable whitespace at the beginning (one or more spaces/tabs)
        // - Quoted bundle identifier
        // - Variable whitespace around the equals sign
        // - Opening brace or other content after equals
        val regex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*.*")
        regex.matchEntire(line)?.groupValues?.get(1)
      }
      .filter { it.isNotBlank() }
    return installedAppIds
      .filter { !it.startsWith("group.") }
      .toSet()
  }

  fun clearAppDataContainer(deviceId: String, appId: String) {
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "get_app_container",
        deviceId,
        appId,
        "data",
      ),
    ).runProcess {}

    output.outputLines.firstOrNull()?.let { dataContainerPath ->
      val dataContainerDirectory = File(dataContainerPath)
      if (dataContainerDirectory.exists() && dataContainerDirectory.isDirectory) {
        println("Clearing $appId data container under $dataContainerPath")
        dataContainerDirectory.listFiles()?.forEach { it.deleteRecursively() }
      }
    }
  }
}