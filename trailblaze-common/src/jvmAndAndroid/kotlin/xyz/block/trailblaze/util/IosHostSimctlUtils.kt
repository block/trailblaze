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
}
