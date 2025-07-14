package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File

class TrailblazeSettingsRepo(
  private val settingsFile: File = File("build/trailblaze-settings.json"),
  private val initialConfig: TrailblazeServerState.SavedTrailblazeAppConfig,
) {

  fun saveConfig(trailblazeSettings: TrailblazeServerState.SavedTrailblazeAppConfig) {
    println(
      "Saving Settings to: ${settingsFile.absolutePath}\n ${
        TrailblazeJsonInstance.encodeToString(
          trailblazeSettings,
        )
      }",
    )
    settingsFile.writeText(
      TrailblazeJsonInstance.encodeToString(
        TrailblazeServerState.SavedTrailblazeAppConfig.serializer(),
        trailblazeSettings,
      ),
    )
  }

  fun load(
    initialConfig: TrailblazeServerState.SavedTrailblazeAppConfig,
  ): TrailblazeServerState.SavedTrailblazeAppConfig = try {
    println("Loading Settings from: ${settingsFile.absolutePath}")
    TrailblazeJsonInstance.decodeFromString(
      TrailblazeServerState.SavedTrailblazeAppConfig.serializer(),
      settingsFile.readText(),
    )
  } catch (e: Exception) {
    println("Error loading settings, using default: ${e.message}")
    initialConfig.also {
      saveConfig(initialConfig)
    }
  }.also {
    println("Loaded settings: $it")
  }

  val serverStateFlow = MutableStateFlow(
    TrailblazeServerState(
      appConfig = load(initialConfig),
    ),
  ).also { serverStateFlow ->
    CoroutineScope(Dispatchers.IO).launch {
      serverStateFlow
        .distinctUntilChangedBy { newState -> newState }
        .collect { newState ->
          println("Trailblaze Server State Updated: $newState")
          saveConfig(newState.appConfig)
        }
    }
  }
}
