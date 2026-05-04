package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import xyz.block.trailblaze.host.devices.PlaywrightInstallState
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.desktoputil.DesktopUtil
import xyz.block.trailblaze.ui.desktoputil.ExitApp
import xyz.block.trailblaze.ui.model.LocalRunYamlRequestFactory
import xyz.block.trailblaze.ui.model.RunYamlRequestFactory
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.devices.DevicesTabComposable
import xyz.block.trailblaze.ui.tabs.home.HomeTabComposable
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.tabs.settings.SettingsTabComposables
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.ui.tabs.mcp.McpTabComposable
import xyz.block.trailblaze.ui.tabs.recording.RecordingTabComposable
import xyz.block.trailblaze.ui.tabs.trails.TrailsBrowserTabComposable
import xyz.block.trailblaze.ui.tabs.waypoints.WaypointsTabComposable
import xyz.block.trailblaze.logs.server.McpServerDebugState
import kotlin.system.exitProcess

/**
 * Factory object for creating built-in Trailblaze tabs.
 * Allows distributions to construct the standard tabs while controlling
 * which ones to include and in what order.
 */
object TrailblazeBuiltInTabs {

  /**
   * Creates the Home tab which provides onboarding and environment status.
   */
  fun homeTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    deviceManager: TrailblazeDeviceManager,
    additionalHomeContent: @Composable ColumnScope.() -> Unit = {},
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Home,
    content = {
      HomeTabComposable(
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        deviceManager = deviceManager,
        additionalHomeContent = additionalHomeContent,
      )
    }
  )

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
    deviceManager: TrailblazeDeviceManager,
    currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgs: suspend () -> Map<String, String>,
    onNavigateToSessions: ((String) -> Unit)? = null,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Trails,
    content = {
      val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
      val effectiveTrailsDir = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)

      CompositionLocalProvider(
        LocalRunYamlRequestFactory provides RunYamlRequestFactory(
          appConfig = serverState.appConfig,
          llmModel = currentTrailblazeLlmModelProvider(),
        )
      ) {
        TrailsBrowserTabComposable(
          trailsDirectoryPath = effectiveTrailsDir,
          deviceManager = deviceManager,
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          yamlRunner = yamlRunner,
          additionalInstrumentationArgs = additionalInstrumentationArgs,
          onChangeDirectory = { newPath ->
            trailblazeSettingsRepo.updateAppConfig { it.copy(trailsDirectory = newPath) }
          }
        )
      }
    }
  )

  /**
   * Creates the Waypoints tab which browses [WaypointDefinition]s discovered from the
   * configured trails directory plus pack-bundled framework waypoints. Same loader used
   * by the `trailblaze waypoint` CLI.
   */
  fun waypointsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    deviceManager: TrailblazeDeviceManager,
    logsRepo: LogsRepo,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Waypoints,
    content = {
      val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
      val effectiveTrailsDir = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)
      WaypointsTabComposable(
        initialRootPath = effectiveTrailsDir,
        logsRepo = logsRepo,
        availableTargets = deviceManager.availableAppTargets,
        appIconProvider = deviceManager.appIconProvider,
        onChangeDirectory = { newPath ->
          trailblazeSettingsRepo.updateAppConfig { it.copy(trailsDirectory = newPath) }
        },
      )
    },
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
      val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()

      CompositionLocalProvider(
        LocalRunYamlRequestFactory provides RunYamlRequestFactory(
          appConfig = serverState.appConfig,
          llmModel = currentTrailblazeLlmModelProvider(),
        )
      ) {
        YamlTabComposable(
          deviceManager = deviceManager,
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          yamlRunner = yamlRunner,
          additionalInstrumentationArgs = additionalInstrumentationArgs,
        )
      }
    }
  )

  /**
   * Creates the Record tab for interactive test recording.
   * Streams a live device preview and captures user interactions as trail YAML.
   */
  fun recordTab(
    deviceManager: TrailblazeDeviceManager,
    currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
    llmTokenProvider: TrailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
    onSaveTrail: (String) -> Unit = {},
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Record,
    content = {
      RecordingTabComposable(
        deviceManager = deviceManager,
        currentTrailblazeLlmModelProvider = currentTrailblazeLlmModelProvider,
        llmTokenProvider = llmTokenProvider,
        onSaveTrail = onSaveTrail,
      )
    }
  )

  /**
   * Creates the MCP tab which shows server status, active sessions,
   * and setup instructions for MCP clients like Claude Code and Goose.
   */
  fun mcpTab(
    mcpServerDebugStateFlow: StateFlow<McpServerDebugState>,
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    recommendTrailblazeAsAgent: Boolean = false,
  ): TrailblazeAppTab = TrailblazeAppTab(
    route = TrailblazeRoute.Mcp,
    content = {
      McpTabComposable(
        mcpServerDebugStateFlow = mcpServerDebugStateFlow,
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        recommendTrailblazeAsAgent = recommendTrailblazeAsAgent,
      )
    }
  )

  /**
   * Creates the Settings tab for app configuration.
   *
   * @param isProviderLocked Whether the LLM provider dropdown starts locked (disabled).
   *   When true, a lock icon button is shown next to the dropdown. Clicking it shows a warning
   *   dialog; accepting the warning unlocks the dropdown. Clicking again re-locks it.
   *   Defaults to false (unlocked) for open source builds.
   */
  fun settingsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    logsRepo: LogsRepo,
    globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit,
    availableModelLists: Set<TrailblazeLlmModelList>,
    customEnvVarNames: List<String>,
    openGoose: () -> Unit = { TrailblazeDesktopUtil.openGoose(port = trailblazeSettingsRepo.portManager.httpPort) },
    isProviderLocked: Boolean = false,
    playwrightInstallState: StateFlow<PlaywrightInstallState>? = null,
    onInstallPlaywright: (() -> Unit)? = null,
    onTestLlmConnection: (suspend (TrailblazeLlmModel) -> Result<String>)? = null,
  ): TrailblazeAppTab {
    val shellProfile = DesktopUtil.getShellProfileFile()
    return TrailblazeAppTab(
      route = TrailblazeRoute.Settings,
      content = {
        SettingsTabComposables.SettingsTab(
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          openLogsFolder = { TrailblazeDesktopUtil.openInFileBrowser(logsRepo.logsDir) },
          openDesktopAppPreferencesFile = { TrailblazeDesktopUtil.openInFileBrowser(trailblazeSettingsRepo.settingsFile) },
          openGoose = openGoose,
          additionalContent = {},
          globalSettingsContent = globalSettingsContent,
          environmentVariableProvider = { System.getenv(it) },
          availableModelLists = availableModelLists,
          customEnvVariableNames = customEnvVarNames,
          openShellProfile = shellProfile?.let { { TrailblazeDesktopUtil.openInFileBrowser(it) } },
          shellProfileName = shellProfile?.name,
          onQuitApp = { ExitApp.quit() },
          isProviderLocked = isProviderLocked,
          playwrightInstallState = playwrightInstallState,
          onInstallPlaywright = onInstallPlaywright,
          onTestLlmConnection = onTestLlmConnection,
        )
      }
    )
  }
}
