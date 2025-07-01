package xyz.block.trailblaze.ui

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File

class TrailblazeSettingsRepo(
  val settingsFile: File = File("build/trailblaze-settings.json"),
) {
  fun update(trailblazeSettings: TrailblazeServerState.TrailblazeSavedSettings) {
    settingsFile.writeText(
      TrailblazeJsonInstance.encodeToString(
        TrailblazeServerState.TrailblazeSavedSettings.serializer(),
        trailblazeSettings
      )
    )
  }

  fun load(): TrailblazeServerState.TrailblazeSavedSettings = try {
    TrailblazeJsonInstance.decodeFromString(
      TrailblazeServerState.TrailblazeSavedSettings.serializer(),
      settingsFile.readText(),
    )
  } catch (e: Exception) {
    println("Error loading settings: ${e.message}")
    val newSettings = TrailblazeServerState.TrailblazeSavedSettings()
    update(trailblazeSettings = newSettings)
    newSettings
  }
}