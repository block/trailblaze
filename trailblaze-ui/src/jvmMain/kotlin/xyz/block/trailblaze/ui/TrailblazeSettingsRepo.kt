package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode
import java.io.File

class TrailblazeSettingsRepo(
  val settingsFile: File = File("build/${TrailblazeDesktopUtil.SETTINGS_FILENAME}"),
  initialConfig: SavedTrailblazeAppConfig,
  private val defaultHostAppTarget: TrailblazeHostAppTarget,
  private val allTargetApps: () -> Set<TrailblazeHostAppTarget>,
  private val supportedDriverTypes: Set<TrailblazeDriverType>,
) {
  private val trailblazeJson: Json = TrailblazeJson.defaultWithoutToolsInstance

  fun saveConfig(trailblazeSettings: SavedTrailblazeAppConfig) {
    println(
      "Saving Settings to: ${settingsFile.absolutePath}\n ${
        trailblazeJson.encodeToString(
          trailblazeSettings,
        )
      }",
    )
    settingsFile.writeText(
      trailblazeJson.encodeToString(
        SavedTrailblazeAppConfig.serializer(),
        trailblazeSettings,
      ),
    )
  }

  fun load(
    initialConfig: SavedTrailblazeAppConfig,
  ): SavedTrailblazeAppConfig = try {
    println("Loading Settings from: ${settingsFile.absolutePath}")
    trailblazeJson.decodeFromString(
      SavedTrailblazeAppConfig.serializer(),
      settingsFile.readText(),
    ).copy(
      // Clear session-specific state on app restart
      currentSessionId = null,
      currentSessionViewMode = SessionViewMode.DEFAULT_VIEW_MODE,
      lastSelectedTestRailTestCaseId = null,
    )
  } catch (e: Exception) {
    println("Error loading settings, using default: ${e.message}")
    initialConfig.also {
      saveConfig(initialConfig)
    }
  }.also {
    println("Loaded settings: $it")
  }

  fun updateState(stateUpdater: (TrailblazeServerState) -> TrailblazeServerState) {
    serverStateFlow.value = stateUpdater(serverStateFlow.value)
  }

  fun updateAppConfig(appConfigUpdater: (SavedTrailblazeAppConfig) -> SavedTrailblazeAppConfig) {
    updateState { currentState: TrailblazeServerState ->
      currentState.copy(appConfig = appConfigUpdater(currentState.appConfig))
    }
  }

  fun targetAppSelected(targetApp: TrailblazeHostAppTarget) {
    updateAppConfig { appConfig: SavedTrailblazeAppConfig ->
      appConfig.copy(selectedTargetAppName = targetApp.name)
    }
  }

  fun getCurrentTrailsDir(): File {
    return File(serverStateFlow.value.appConfig.trailsDirectory ?: ".")
  }

  fun getAllSupportedDriverTypes(): Set<TrailblazeDriverType> {
    return supportedDriverTypes
  }

  /**
   * Get the map of enabled platforms to their selected driver types
   */
  fun getEnabledDriverTypesMap(): Map<TrailblazeDevicePlatform, TrailblazeDriverType> {
    return serverStateFlow.value.appConfig.selectedTrailblazeDriverTypes
  }

  /**
   * Get the set of currently enabled driver types (values from the map)
   */
  fun getEnabledDriverTypes(): Set<TrailblazeDriverType> {
    return serverStateFlow.value.appConfig.selectedTrailblazeDriverTypes.values.toSet()
  }

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? {
    return allTargetApps()
      .filter { it != defaultHostAppTarget }
      .firstOrNull { appTarget ->
        appTarget.name == serverStateFlow.value.appConfig.selectedTargetAppName
      }
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
