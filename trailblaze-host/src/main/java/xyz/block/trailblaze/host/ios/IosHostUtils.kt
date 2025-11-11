package xyz.block.trailblaze.host.ios

import util.LocalSimulatorUtils
import xyz.block.trailblaze.util.CommandProcessResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

object IosHostUtils {

  fun killAppOnSimulator(deviceId: String?, appId: String) {
    LocalSimulatorUtils.terminate(
      deviceId = deviceId ?: "booted",
      bundleId = appId,
    )
  }

  fun getInstalledAppIds(deviceId: String?): Set<String> {
    val output: CommandProcessResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "listapps",
        deviceId ?: "booted",
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
}
