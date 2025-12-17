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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.inProgress
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.IconWithBadges
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.NavigationTab
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.devices.DevicesTabComposable
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.tabs.settings.SettingsTabComposables
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
     * Custom tabs that can access navigation via LocalNavController.current
     */
    customTabs: () -> List<TrailblazeAppTab>,
    availableModelLists: Set<TrailblazeLlmModelList>,
    currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
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
        // Create NavController for type-safe navigation
        val navController = rememberNavController()

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

        // Provide NavController to the entire composition tree for easy access
        CompositionLocalProvider(LocalNavController provides navController) {
          TrailblazeAppContent(
            customTabs = customTabs,
            sessions = sessions,
            inProgressCount = inProgressCount,
            firstSessionStatus = firstSessionStatus,
            currentServerState = currentServerState,
            trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
            windowState = windowState,
            logsRepo = logsRepo,
            deviceManager = deviceManager,
            recordedTrailsRepo = recordedTrailsRepo,
            currentTrailblazeLlmModelProvider = currentTrailblazeLlmModelProvider,
            yamlRunner = yamlRunner,
            additionalInstrumentationArgs = additionalInstrumentationArgs,
            globalSettingsContent = globalSettingsContent,
            availableModelLists = availableModelLists,
            customEnvVarNames = customEnvVarNames,
          )
        }
      }
    }
  }

  @Composable
  private fun TrailblazeAppContent(
    customTabs: () -> List<TrailblazeAppTab>,
    sessions: List<SessionInfo>,
    inProgressCount: Int,
    firstSessionStatus: xyz.block.trailblaze.logs.model.SessionStatus?,
    currentServerState: TrailblazeServerState,
    trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
    windowState: WindowState,
    logsRepo: LogsRepo,
    deviceManager: TrailblazeDeviceManager,
    recordedTrailsRepo: RecordedTrailsRepo,
    currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgs: (suspend () -> Map<String, String>),
    globalSettingsContent: @Composable ColumnScope.(serverState: TrailblazeServerState) -> Unit,
    availableModelLists: Set<TrailblazeLlmModelList>,
    customEnvVarNames: List<String>,
  ) {
    val navController = LocalNavController.current

    // Custom tabs can now access navigation via LocalNavController.current
    val customNavTabs = remember { customTabs() }

    // Build final tab list with navigation tabs inserted after Sessions
    val allRoutes = remember(customNavTabs) {
      buildList {
        add(TrailblazeRoute.Sessions)
        addAll(customNavTabs.map { it.route })
        add(TrailblazeRoute.Devices)
        add(TrailblazeRoute.YamlRoute)
        add(TrailblazeRoute.Settings)
      }
    }

    val navigationTabsWithNav = allRoutes.map { route: TrailblazeRoute ->
      NavigationTab(
        route = route,
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

    TrailblazeTheme(themeMode = currentServerState.appConfig.themeMode) {
          var railExpanded by remember { mutableStateOf(false) }
          val snackbarHostState = remember { SnackbarHostState() }

          // Get current route from nav controller
          val navBackStackEntry by navController.currentBackStackEntryAsState()
          val currentRouteString = navBackStackEntry?.destination?.route

          // Helper to check if a tab is selected
          fun isRouteSelected(tabRoute: TrailblazeRoute): Boolean {
            // All routes use type-safe navigation, so we check against qualified name
            return currentRouteString == tabRoute::class.qualifiedName
          }

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
                      // Check if this tab's route matches the current route
                      val selected = isRouteSelected(tab.route)
                      NavigationRailItem(
                        selected = selected,
                        onClick = {
                          // Special handling for Sessions tab: if already on Sessions and viewing a session detail,
                          // clicking Sessions again should go back to the list view
                          if (tab.route == TrailblazeRoute.Sessions && selected) {
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
                            navController.navigateToRoute(tab.route)
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
                  // NavHost with type-safe navigation
                  NavHost(
                    navController = navController,
                    startDestination = TrailblazeRoute.Sessions,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }
                  ) {
                    composable<TrailblazeRoute.Sessions> {
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

                    // Register custom tabs from external modules
                    // Since external routes aren't known at compile time in the core framework,
                    // we register them using a string-based route derived from their class name
                    customNavTabs.forEach { customTab ->
                      val routeString = customTab.route::class.qualifiedName ?: customTab.route.toString()
                      composable(routeString) {
                        customTab.content()
                      }
                    }

                    composable<TrailblazeRoute.Devices> {
                      DevicesTabComposable(
                        deviceManager = deviceManager,
                        trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
                      )
                    }

                    composable<TrailblazeRoute.YamlRoute> {
                      YamlTabComposable(
                        deviceManager = deviceManager,
                        trailblazeSettingsRepo = trailblazeSavedSettingsRepo,
                        currentTrailblazeLlmModelProvider = currentTrailblazeLlmModelProvider,
                        yamlRunner = yamlRunner,
                        additionalInstrumentationArgs = additionalInstrumentationArgs
                      )
                    }

                    composable<TrailblazeRoute.Settings> {
                      SettingsTabComposables.SettingsTab(
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
                    }
                  }
                }
              }
            }
          }
        }
      }
}
