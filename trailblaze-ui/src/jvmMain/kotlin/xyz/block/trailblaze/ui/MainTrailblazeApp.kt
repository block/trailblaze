package xyz.block.trailblaze.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DeviceStatusPanel
import xyz.block.trailblaze.ui.composables.IconWithBadges
import xyz.block.trailblaze.ui.model.*
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.devdebug.DevDebugWindow
import xyz.block.trailblaze.ui.tabs.devices.DevicesTabComposable
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.tabs.settings.SettingsTabComposables
import xyz.block.trailblaze.ui.theme.TrailblazeTheme

class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  private val yamlRunner: (DesktopAppRunYamlParams) -> Unit,
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
        val liveSessionDataProvider = remember(logsRepo, deviceManager) {
          createLiveSessionDataProviderJvm(logsRepo, deviceManager)
        }

        // Collect sessions reactively from the Flow - automatic updates!
        val sessions by liveSessionDataProvider.getSessionsFlow().collectAsState()

        // Use filtered flows for badge counts - much simpler!
        val inProgressCount by logsRepo.activeSessionCountFlow.collectAsState()
        val firstSessionStatus = sessions.firstOrNull()?.latestStatus

        // Provide NavController to the entire composition tree for easy access
        CompositionLocalProvider(LocalNavController provides navController) {
          TrailblazeAppContent(
            customTabs = customTabs,
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

    // Load devices on app startup to populate the device status panel
    LaunchedEffect(Unit) {
      deviceManager.loadDevices()
    }

    TrailblazeTheme(themeMode = currentServerState.appConfig.themeMode) {
      // Show pop-out window based on config value (reactive)
      if (currentServerState.appConfig.showDebugPopOutWindow) {
        DevDebugWindow(
          deviceManager = deviceManager,
          onCloseRequest = {
            trailblazeSavedSettingsRepo.updateAppConfig {
              it.copy(showDebugPopOutWindow = false)
            }
          }
        )
      }

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

          // Device status panel - collapsible in bottom-right corner
          DeviceStatusPanel(
            deviceManager = deviceManager,
            onSessionClick = { sessionId ->
              // Navigate to the session details
              trailblazeSavedSettingsRepo.updateState { state ->
                state.copy(
                  appConfig = state.appConfig.copy(currentSessionId = sessionId.value)
                )
              }
              navController.navigateToRoute(TrailblazeRoute.Sessions)
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(16.dp)
          )
        }
      }
    }
  }
}
