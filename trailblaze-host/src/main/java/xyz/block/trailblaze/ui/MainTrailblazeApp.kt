package xyz.block.trailblaze.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DeviceStatusPanel
import xyz.block.trailblaze.ui.composables.IconWithBadges
import xyz.block.trailblaze.ui.composables.LocalDeviceClassifierIcons
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.NavigationTab
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.devdebug.DevDebugWindow
import xyz.block.trailblaze.ui.theme.TrailblazeTheme

class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  val recordedTrailsRepo: RecordedTrailsRepo,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
) {

  private data class WindowStateSnapshot(
    val width: Int,
    val height: Int,
    val position: WindowPosition,
  )

  fun runTrailblazeApp(
    /**
     * Complete list of tabs to display in the app, in order.
     * The caller provides the full ordered list including built-in routes.
     * Custom tabs can access navigation via LocalNavController.current.
     */
    allTabs: () -> List<TrailblazeAppTab>,
    deviceManager: TrailblazeDeviceManager,
  ) {
    TrailblazeDesktopUtil.setAppConfigForTrailblaze()

    // Set the logs directory for the FileSystemImageLoader
    setLogsDirectory(logsRepo.logsDir)

    CoroutineScope(Dispatchers.IO).launch {
      val appConfig = trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig

      // Start Server
      val trailblazeMcpServer = trailblazeMcpServerProvider()
      trailblazeMcpServer.startStreamableHttpMcpServer(
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
        val windowX = config.windowX
        val windowY = config.windowY
        WindowState(
          width = config.windowWidth?.dp ?: 1200.dp,
          height = config.windowHeight?.dp ?: 800.dp,
          position = if (windowX != null && windowY != null) {
            WindowPosition(windowX.dp, windowY.dp)
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

        // Provide NavController and device classifier icons to the entire composition tree
        CompositionLocalProvider(
          LocalNavController provides navController,
          LocalDeviceClassifierIcons provides deviceManager.deviceClassifierIconProvider,
        ) {
          TrailblazeAppContent(
            allTabs = allTabs,
            inProgressCount = inProgressCount,
            firstSessionStatus = firstSessionStatus,
            currentServerState = currentServerState,
            trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
            windowState = windowState,
            deviceManager = deviceManager,
          )
        }
      }
    }
  }

  @Composable
  private fun TrailblazeAppContent(
    allTabs: () -> List<TrailblazeAppTab>,
    inProgressCount: Int,
    firstSessionStatus: xyz.block.trailblaze.logs.model.SessionStatus?,
    currentServerState: TrailblazeServerState,
    trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
    windowState: WindowState,
    deviceManager: TrailblazeDeviceManager,
  ) {

    val navController = LocalNavController.current

    // Get all tabs that should be registered in NavHost (stable, computed once)
    // This ensures NavHost doesn't rebuild when visibility settings change
    val allRegisteredTabs = remember { allTabs() }

    // Get visible tabs for the navigation rail (reactive to showTrailsTab)
    // This filters which tabs appear in the UI without rebuilding NavHost
    val visibleTabs = remember(currentServerState.appConfig.showTrailsTab) {
      allTabs().filter { tab ->
        // All tabs are visible unless it's Trails and showTrailsTab is false
        tab.route != TrailblazeRoute.Trails || currentServerState.appConfig.showTrailsTab
      }
    }

    // Build navigation tabs from the visible list
    val navigationTabsWithNav = visibleTabs.map { tab ->
      val route = tab.route
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

      // Save the current route whenever navigation changes
      LaunchedEffect(currentRouteString) {
        if (currentRouteString != null) {
          trailblazeSavedSettingsRepo.updateAppConfig { currAppConfig ->
            currAppConfig.copy(lastRoute = currentRouteString)
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
              // NavHost - all tabs are registered here (stable, doesn't change with visibility settings)
              // Use the saved lastRoute if valid, otherwise fall back to the first tab (defaults to Sessions)
              val validRoutes = allRegisteredTabs.map { it.route::class.qualifiedName }.toSet()
              val defaultRoute = allRegisteredTabs.firstOrNull()?.let { it.route::class.qualifiedName }
                ?: TrailblazeRoute.Sessions::class.qualifiedName!!
              val savedRoute = currentServerState.appConfig.lastRoute
              val startRoute = if (savedRoute != null && savedRoute in validRoutes) savedRoute else defaultRoute

              NavHost(
                navController = navController,
                startDestination = startRoute,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None }
              ) {
                // Register all tabs (including hidden ones) so navigation always works
                // Visibility is controlled by the navigation rail, not by route registration
                allRegisteredTabs.forEach { tab ->
                  val routeString = tab.route::class.qualifiedName ?: tab.route.toString()
                  composable(routeString) {
                    tab.content()
                  }
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
