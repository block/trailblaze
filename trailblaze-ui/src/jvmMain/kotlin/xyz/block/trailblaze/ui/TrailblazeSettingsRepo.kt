package xyz.block.trailblaze.ui

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File
import kotlin.reflect.KClass

class TrailblazeSettingsRepo(
  private val settingsFile: File = File("build/trailblaze-settings.json"),
  private val initialConfig: TrailblazeServerState.SavedTrailblazeAppConfig,
  private val allToolClasses: Map<ToolDescriptor, KClass<out TrailblazeTool>>,
) {
  private val trailblazeJson: Json = TrailblazeJson.createTrailblazeJsonInstance(allToolClasses)

  fun saveConfig(trailblazeSettings: TrailblazeServerState.SavedTrailblazeAppConfig) {
    println(
      "Saving Settings to: ${settingsFile.absolutePath}\n ${
        trailblazeJson.encodeToString(
          trailblazeSettings,
        )
      }",
    )
    settingsFile.writeText(
      trailblazeJson.encodeToString(
        TrailblazeServerState.SavedTrailblazeAppConfig.serializer(),
        trailblazeSettings,
      ),
    )
  }

  fun load(
    initialConfig: TrailblazeServerState.SavedTrailblazeAppConfig,
  ): TrailblazeServerState.SavedTrailblazeAppConfig = try {
    println("Loading Settings from: ${settingsFile.absolutePath}")
    trailblazeJson.decodeFromString(
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
