package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.devices.DevicesTabComposable
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.tabs.settings.SettingsTabComposables
import xyz.block.trailblaze.ui.tabs.trails.TrailsBrowserTabComposable

/**
 * Factory object for creating built-in Trailblaze tabs.
 * Allows distributions to construct the standard tabs while controlling
 * which ones to include and in what order.
 */
object TrailblazeBuiltInTabs {

  /**
   * Creates the Sessions tab which displays session history and details.
   */
  fun sessionsTab(
    logsRepo: LogsRepo,
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    deviceManager: TrailblazeDeviceManager,
    recordedTrailsRepo: RecordedTrailsRepo,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Sessions,
    content = {
      val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
      SessionsTabComposableJvm(
        logsRepo = logsRepo,
        serverState = serverState,
        updateState = { newState: TrailblazeServerState ->
          trailblazeSettingsRepo.updateState { newState }
        },
        deviceManager = deviceManager,
        recordedTrailsRepo = recordedTrailsRepo,
      )
    }
  )

  /**
   * Creates the Trails tab which allows browsing recorded trails.
   */
  fun trailsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Trails,
    content = {
      val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
      val effectiveTrailsDir = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)
      TrailsBrowserTabComposable(
        trailsDirectoryPath = effectiveTrailsDir,
        onChangeDirectory = { newPath ->
          trailblazeSettingsRepo.updateAppConfig { it.copy(trailsDirectory = newPath) }
        }
      )
    }
  )

  /**
   * Creates the Devices tab which shows connected devices.
   */
  fun devicesTab(
    deviceManager: TrailblazeDeviceManager,
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Devices,
    content = {
      DevicesTabComposable(
        deviceManager = deviceManager,
        trailblazeSavedSettingsRepo = trailblazeSettingsRepo,
      )
    }
  )

  /**
   * Creates the YAML tab for running YAML test definitions.
   */
  fun yamlTab(
    deviceManager: TrailblazeDeviceManager,
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgs: suspend () -> Map<String, String>,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.YamlRoute,
    content = {
      YamlTabComposable(
        deviceManager = deviceManager,
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        currentTrailblazeLlmModelProvider = currentTrailblazeLlmModelProvider,
        yamlRunner = yamlRunner,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
      )
    }
  )

  /**
   * Creates the Settings tab for app configuration.
   */
  fun settingsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    logsRepo: LogsRepo,
    globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit,
    availableModelLists: Set<TrailblazeLlmModelList>,
    customEnvVarNames: List<String>,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Settings,
    content = {
      SettingsTabComposables.SettingsTab(
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        openLogsFolder = { TrailblazeDesktopUtil.openInFileBrowser(logsRepo.logsDir) },
        openDesktopAppPreferencesFile = { TrailblazeDesktopUtil.openInFileBrowser(trailblazeSettingsRepo.settingsFile) },
        openGoose = { TrailblazeDesktopUtil.openGoose() },
        additionalContent = {},
        globalSettingsContent = globalSettingsContent,
        environmentVariableProvider = { System.getenv(it) },
        availableModelLists = availableModelLists,
        customEnvVariableNames = customEnvVarNames,
      )
    }
  )
}
