package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.inProgress
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.IconWithBadges
import xyz.block.trailblaze.ui.model.NavigationTab
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm
import xyz.block.trailblaze.ui.tabs.devices.DevicesTabComposable
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.tabs.settings.LogsServerComposables
import xyz.block.trailblaze.ui.theme.TrailblazeTheme


class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  val recordedTrailsRepo: RecordedTrailsRepo,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
  val customEnvVarNames: List<String>,
) {

  private data class WindowStateSnapshot(
    val width: Int,
    val height: Int,
    val position: WindowPosition,
  )

  fun runTrailblazeApp(
    /**
     * Custom Tabs
     */
    customTabs: List<TrailblazeAppTab>,
    /**
     * Custom tabs that need access to navigation - takes a route changer callback
     */
    customTabsWithNavigation: (routeChanger: (TrailblazeRoute) -> Unit) -> List<TrailblazeAppTab> = { emptyList() },
    availableModelLists: Set<TrailblazeLlmModelList>,
    deviceManager: TrailblazeDeviceManager,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    globalSettingsContent: @Composable ColumnScope.(serverState: TrailblazeServerState) -> Unit,
    additionalInstrumentationArgs: (suspend () -> Map<String, String>),
  ) {
    TrailblazeDesktopUtil.setAppConfigForTrailblaze()

    // Set the logs directory for the FileSystemImageLoader
    setLogsDirectory(logsRepo.logsDir)

    CoroutineScope(Dispatchers.IO).launch {
      val appConfig = trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig

      // Start Server
      val trailblazeMcpServer = trailblazeMcpServerProvider()
      trailblazeMcpServer.startSseMcpServer(
        port = appConfig.serverPort,
        wait = false,
      )

      // Auto Launch Goose if enabled
      if (appConfig.autoLaunchGoose) {
        TrailblazeDesktopUtil.openGoose()
      }
    }

    application {
      val currentServerState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
      // Initialize window state from saved config or use defaults
      val windowState = remember {
        val config = currentServerState.appConfig
        WindowState(
          width = config.windowWidth?.dp ?: 1200.dp,
          height = config.windowHeight?.dp ?: 800.dp,
          position = if (config.windowX != null && config.windowY != null) {
            WindowPosition(config.windowX.dp, config.windowY.dp)
          } else {
            WindowPosition.Aligned(Alignment.Center)
          }
        )
      }
      Window(
        state = windowState,
        onCloseRequest = ::exitApplication,
        title = "Trailblaze",
        alwaysOnTop = currentServerState.appConfig.alwaysOnTop,
      ) {
        val settingsTab = TrailblazeAppTab(TrailblazeRoute.Settings, {
          LogsServerComposables.SettingsTab(
            trailblazeSettingsRepo = trailblazeSavedSettingsRepo,
            openLogsFolder = {
              TrailblazeDesktopUtil.openInFileBrowser(logsRepo.logsDir)
            },
            openDesktopAppPreferencesFile = {
              TrailblazeDesktopUtil.openInFileBrowser(trailblazeSavedSettingsRepo.settingsFile)
            },
            openGoose = {
              TrailblazeDesktopUtil.openGoose()
            },
            additionalContent = {},
            globalSettingsContent = globalSettingsContent,
            environmentVariableProvider = { System.getenv(it) },
            availableModelLists = availableModelLists,
            customEnvVariableNames = customEnvVarNames,
          )
        })

        val sessionsTab = TrailblazeAppTab(
          route = TrailblazeRoute.Sessions,
        ) {
          val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
          SessionsTabComposableJvm(
            logsRepo = logsRepo,
            serverState = serverState,
            updateState = { newState: TrailblazeServerState ->
              trailblazeSavedSettingsRepo.updateState { newState }
            },
            deviceManager = deviceManager,
            recordedTrailsRepo = recordedTrailsRepo,
          )
        }

        // Store a reference to the route changer to be used in tabs
        var routeChanger: ((TrailblazeRoute) -> Unit)? by remember { mutableStateOf(null) }

        val deviceTab = TrailblazeAppTab(TrailblazeRoute.Devices, {
          DevicesTabComposable(
            deviceManager = deviceManager,
            trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
            onNavigateToSessions = {
              // Switch to Sessions tab when a session link is clicked
              routeChanger?.invoke(TrailblazeRoute.Sessions)
            }
          )
        })

        val yamlTab = TrailblazeAppTab(
          route = TrailblazeRoute.YamlRoute,
        ) {
          YamlTabComposable(
            deviceManager = deviceManager,
            trailblazeSettingsRepo = trailblazeSavedSettingsRepo,
            availableLlmModelLists = availableModelLists,
            yamlRunner = yamlRunner,
            additionalInstrumentationArgs = additionalInstrumentationArgs
          )
        }

        val allTabs: List<TrailblazeAppTab> = buildList {
          add(sessionsTab)
          addAll(customTabs)
          add(deviceTab)
          add(yamlTab)
          add(settingsTab)
        }

        TrailblazeTheme(themeMode = currentServerState.appConfig.themeMode) {

          // Track sessions for badge display
          var sessions by remember { mutableStateOf(emptyList<SessionInfo>()) }
          val liveSessionDataProvider = remember(logsRepo, deviceManager) {
            createLiveSessionDataProviderJvm(logsRepo, deviceManager)
          }

          // Poll for session updates every 2 seconds to update badges
          LaunchedEffect(liveSessionDataProvider) {
            while (isActive) {
              withContext(Dispatchers.IO) {
                try {
                  val sessionIds = liveSessionDataProvider.getSessionIds()
                  val loadedSessions = sessionIds.mapNotNull { sessionId ->
                    liveSessionDataProvider.getSessionInfo(sessionId)
                  }
                  sessions = loadedSessions
                } catch (e: Exception) {
                  e.printStackTrace()
                }
              }
              delay(2000)
            }
          }

          // Calculate session statistics for badges
          val inProgressCount = sessions.count { it.latestStatus.inProgress }
          val firstSessionStatus = sessions.firstOrNull()?.latestStatus

          var currentRoute by remember {
            mutableStateOf(allTabs.first().route)
          }

          // Connect the route changer to the actual currentRoute state
          routeChanger = { newRoute -> currentRoute = newRoute }

          // Add navigation-enabled custom tabs now that routeChanger is set
          val customNavTabs by remember {
            mutableStateOf(customTabsWithNavigation(routeChanger!!))
          }

          // Build final tab list with navigation tabs inserted after Sessions
          val allTabsWithNav = remember(customNavTabs) {
            buildList {
              add(sessionsTab)
              // Add navigation-enabled custom tabs (e.g., TestRail) here
              addAll(customNavTabs)
              // Then add other custom tabs (e.g., Buildkite)
              addAll(customTabs)
              add(deviceTab)
              add(yamlTab)
              add(settingsTab)
            }
          }

          val navigationTabsWithNav = allTabsWithNav.map { tab: TrailblazeAppTab ->
            val route: TrailblazeRoute = tab.route
            NavigationTab(
              route = route,
              content = tab.content,
              label = route.displayName,
              icon = if (route == TrailblazeRoute.Sessions) {
                {
                  IconWithBadges(
                    icon = route.icon, inProgressCount = inProgressCount,
                    firstSessionStatus = firstSessionStatus
                  )
                }
              } else {
                route.icon
              },
              isEnabled = route.isEnabled,
            )
          }

          var railExpanded by remember { mutableStateOf(false) }
          val snackbarHostState = remember { SnackbarHostState() }

          // Save window state changes
          LaunchedEffect(windowState) {
            snapshotFlow {
              WindowStateSnapshot(
                windowState.size.width.value.toInt(),
                windowState.size.height.value.toInt(),
                windowState.position
              )
            }.collect { snapshot ->
              val posX = (snapshot.position as? WindowPosition.Absolute)?.x?.value?.toInt()
              val posY = (snapshot.position as? WindowPosition.Absolute)?.y?.value?.toInt()

              trailblazeSavedSettingsRepo.updateAppConfig { currAppConfig ->
                currAppConfig.copy(
                  windowWidth = snapshot.width,
                  windowHeight = snapshot.height,
                  windowX = posX,
                  windowY = posY,
                )
              }
            }
          }

          Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
          ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
              Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                  IconButton(onClick = { railExpanded = !railExpanded }) {
                    Icon(
                      imageVector = if (railExpanded) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
                      contentDescription = if (railExpanded) "Collapse" else "Expand"
                    )
                  }
                  navigationTabsWithNav.forEach { tab ->
                    if (tab.isEnabled) {
                      val selected = currentRoute == tab.route
                      NavigationRailItem(
                        selected = selected,
                        onClick = {
                          // Special handling for Sessions tab: if already on Sessions and viewing a session detail,
                          // clicking Sessions again should go back to the list view
                          if (tab.route == TrailblazeRoute.Sessions && currentRoute == TrailblazeRoute.Sessions) {
                            val serverState = trailblazeSavedSettingsRepo.serverStateFlow.value
                            if (serverState.appConfig.currentSessionId != null) {
                              // Clear the current session to go back to list
                              trailblazeSavedSettingsRepo.updateState {
                                serverState.copy(
                                  appConfig = serverState.appConfig.copy(currentSessionId = null)
                                )
                              }
                            }
                          } else {
                            currentRoute = tab.route
                          }
                        },
                        icon = tab.icon,
                        label = if (railExpanded) ({ Text(tab.label) }) else null
                      )
                    }
                  }
                }
                Column(
                  modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(0.dp)
                ) {
                  navigationTabsWithNav.find { it.route == currentRoute }?.content?.invoke()
                }
              }
            }
          }
        }
      }
    }
  }
}
