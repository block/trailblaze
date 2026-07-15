package xyz.block.trailblaze.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hiking
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.logs.server.McpServerDebugState
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
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
import xyz.block.trailblaze.ui.tabs.devdebug.DevDebugWindow
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.cli.TrailblazeExitCode
import kotlin.system.exitProcess
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.target.LiveWindowComposeTarget
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.canRunDesktopGui
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Window


/**
 * When true, always show the main window on macOS instead of starting minimized to tray.
 * This improves discoverability of the app vs being hidden in the menu bar.
 *
 * Note: When --headless is passed (e.g. via `trailblaze mcp`), the window is always hidden
 * regardless of this flag — headless mode only starts the daemon/server with a tray icon.
 */
private const val ALWAYS_SHOW_WINDOW = true

private data class WindowStateSnapshot(
  val width: Int,
  val height: Int,
  val position: WindowPosition,
)

class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
) {

  fun runTrailblazeApp(
    /**
     * Complete list of tabs to display in the app, in order.
     * The caller provides the full ordered list including built-in routes.
     * Custom tabs can access navigation via LocalNavController.current.
     */
    allTabs: () -> List<TrailblazeAppTab>,
    deviceManager: TrailblazeDeviceManager,
    /**
     * If true, starts in headless mode with only the menu bar tray icon visible.
     * The window can be shown later via the tray menu.
     */
    headless: Boolean = false,
    /**
     * Optional extra Ktor routes registered on the daemon alongside the built-in
     * ones. Lets a downstream desktop edition add its own daemon endpoints without
     * trailblaze-host — the lower layer — having to depend on them. OSS callers
     * leave this null.
     */
    extraDaemonRoutes: (io.ktor.server.routing.Routing.() -> Unit)? = null,
    /**
     * True when the daemon HTTP server for this port is already running and owned elsewhere —
     * either another process this instance deliberately attaches to (GUI alongside a window-less
     * `trailblaze mcp` daemon) or in-process before the UI starts (`trailblaze mcp` legacy STDIO
     * mode). When false, this process MUST win the port bind or exit: a failed bind means another
     * daemon owns the port, and lingering would leave a duplicate tray icon advertising a port
     * this process doesn't serve.
     */
    daemonAlreadyRunning: Boolean = false,
  ) {
    val initiallyVisible = !headless && ALWAYS_SHOW_WINDOW
    // The agent-app property is read at AWT initialization, and the Taskbar call below is the
    // first AWT touch — this is the last moment the headed/background decision can take effect.
    if (initiallyVisible) {
      // Deliberately headed launch (`trailblaze app` without --headless): clear the CLI-wide
      // agent-app default (set in TrailblazeCli.run) so the window can take focus normally.
      System.clearProperty(TrailblazeDesktopUtil.AWT_AGENT_APP_PROPERTY)
    } else {
      // Hidden-window launch: start as a macOS agent app (LSUIElement). Without it, AWT
      // initializes as a regular GUI app and macOS activates it — every background daemon
      // launch steals keyboard focus from whatever the user is typing, and the ACCESSORY
      // policy flip below lands too late to prevent it. The tray icon is unaffected, and
      // setDockIconVisible(true) still promotes the process to a regular Dock app when the
      // window is first shown. (Redundant with TrailblazeCli.run's default — every known
      // entry point routes through it — kept as defense-in-depth for a future one that
      // constructs the app directly.)
      System.setProperty(TrailblazeDesktopUtil.AWT_AGENT_APP_PROPERTY, "true")
    }
    TrailblazeDesktopUtil.setAppConfigForTrailblaze()
    // Apply this immediately after configuring the Taskbar so a headless daemon never lingers
    // in the Dock while the rest of the application initializes.
    TrailblazeDesktopUtil.setDockIconVisible(initiallyVisible)

    // Set the logs directory for the FileSystemImageLoader
    setLogsDirectory(logsRepo.logsDir)

    // Get the MCP server instance (we'll set the callback after Compose state is ready)
    val trailblazeMcpServer = trailblazeMcpServerProvider()

    // Shared session state for the HTTP device API — tracks live DeviceScreenStream instances
    // established via ConnectToDeviceRequest so screen-poll and interaction handlers can reach
    // them. One instance per daemon lifetime; the map is thread-safe internally.
    val hostDeviceSessionManager =
      xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager()

    if (!canRunDesktopGui()) {
      // No display available (non-macOS or headless environment): start only the
      // MCP server without any Compose Desktop UI.
      // On macOS, we always start Compose Desktop so the system tray icon is
      // available — the `headless` flag just controls initial window visibility.
      Console.log("Starting Trailblaze in headless mode (server only, no GUI)...")
      val portManager = trailblazeSavedSettingsRepo.portManager
      // Advertise the live daemon URL to subprocess MCP plumbing BEFORE the server starts
      // (in `wait = true` mode the call below blocks forever — anything after it never runs).
      // Without this, `JsScriptingCallbackBaseUrl.get()` returns null in the headless path,
      // `McpSubprocessRuntimeLauncher` skips the callback-context wiring, and scripted tools
      // see `_meta.trailblaze`-less envelopes → `ctx === undefined` in handlers. The
      // `applyPortOverrides`/`ensureServerRunning` paths in `TrailblazeDesktopApp.kt` already
      // do this for the GUI app; mirroring here closes the gap for `trailblaze app --headless`.
      xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.set(portManager.serverUrl)
      // With no GUI to attach, a process that isn't going to own the server has nothing to do.
      if (daemonAlreadyRunning && DaemonClient(port = portManager.httpPort).use { it.isRunningBlocking() }) {
        Console.info("Trailblaze daemon is already running on port ${portManager.httpPort} — nothing to do.")
        return
      }
      try {
        trailblazeMcpServer.startStreamableHttpMcpServer(
          port = portManager.httpPort,
          httpsPort = portManager.httpsPort,
          wait = true,
          additionalRouteRegistration = {
            allRouteRegistrations(trailblazeSavedSettingsRepo, deviceManager, hostDeviceSessionManager).invoke(this)
            extraDaemonRoutes?.invoke(this)
          },
        )
      } catch (e: Exception) {
        exitOnPortBindFailure(port = portManager.httpPort, headless = true, cause = e)
      }
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      // Start Server — use portManager so runtime CLI overrides are respected.
      // Skipped only when the caller explicitly attaches to an already-running daemon;
      // otherwise this process must win the port bind or exit (see exitOnPortBindFailure).
      val portManager = trailblazeSavedSettingsRepo.portManager
      // Re-verify the attach target is still alive: the flag was computed at CLI startup, and a
      // daemon that died in between would otherwise leave this process as a server-less tray
      // icon — the exact zombie the bind arbiter exists to prevent. A dead target means this
      // process should take over the port instead.
      val attachToLiveDaemon = daemonAlreadyRunning &&
        DaemonClient(port = portManager.httpPort).use { it.isRunning() }
      if (!attachToLiveDaemon) {
        // Same callback-URL wiring as the headless branch above — without this, the GUI
        // path's first-launch case (no prior daemon) drops `_meta.trailblaze` envelopes
        // on every subprocess scripted-tool dispatch.
        xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.set(portManager.serverUrl)
        try {
          trailblazeMcpServer.startStreamableHttpMcpServer(
            port = portManager.httpPort,
            httpsPort = portManager.httpsPort,
            wait = false,
            additionalRouteRegistration = {
              allRouteRegistrations(trailblazeSavedSettingsRepo, deviceManager, hostDeviceSessionManager).invoke(this)
              extraDaemonRoutes?.invoke(this)
            },
          )
        } catch (e: Exception) {
          exitOnPortBindFailure(port = portManager.httpPort, headless = headless, cause = e)
        }
      }

      // Auto Launch Goose if enabled
      val appConfig = trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig
      if (appConfig.autoLaunchGoose) {
        TrailblazeDesktopUtil.openGoose(port = portManager.httpPort)
      }
    }

    application {
      val currentServerState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
      
      // Window visibility state - starts hidden in headless mode, visible otherwise.
      var windowVisible by remember { mutableStateOf(initiallyVisible) }

      // On macOS, a hidden window makes Trailblaze an accessory app (tray icon only). Restoring
      // the regular policy when the window is shown also restores its Dock/app-switcher icon.
      LaunchedEffect(windowVisible) {
        TrailblazeDesktopUtil.setDockIconVisible(windowVisible)
      }
      
      // Track the AWT window for bringing to front
      var awtWindow by remember { mutableStateOf<Window?>(null) }
      
      // Wire up CLI callbacks for integration
      LaunchedEffect(Unit) {
        trailblazeMcpServer.onShowWindowRequest = {
          windowVisible = true
          // Bring window to front on macOS
          awtWindow?.let { window ->
            window.toFront()
            window.requestFocus()
          }
        }
        trailblazeMcpServer.onShutdownRequest = {
          exitApplication()
        }
      }
      
      // Tray state for menu bar icon
      val trayState = rememberTrayState()
      // Use Material Hiking icon with white tint for macOS menu bar
      val trayIcon = rememberTrayIcon()
      
      // Menu bar tray icon - always visible while app is running
      Tray(
        state = trayState,
        icon = trayIcon,
        tooltip = "Trailblaze",
        onAction = { windowVisible = true }, // Double-click opens window
        menu = {
          Item(
            text = if (windowVisible) "Hide Window" else "Show Window",
            onClick = { windowVisible = !windowVisible }
          )
          Item(
            text = "Show Trail Runner",
            onClick = {
              TrailblazeDesktopUtil.openInDefaultBrowser(
                "${trailblazeSavedSettingsRepo.portManager.serverUrl}/trailrunner/"
              )
            }
          )
          Item(
            text = "View Logs",
            onClick = {
              val desktopLogsDir = TrailblazeDesktopUtil.getDesktopLogsDirectory()
              if (desktopLogsDir.exists()) {
                Desktop.getDesktop().open(desktopLogsDir)
              }
            }
          )
          Separator()
          Item(
            text = "Quit Trailblaze",
            onClick = ::exitApplication
          )
          Separator()
          // Version + port info at the bottom (grayed out, like Cyprus app)
          Item(
            text = "${TrailblazeVersion.displayVersion} (port ${trailblazeSavedSettingsRepo.portManager.httpPort})",
            enabled = false,
            onClick = {}
          )
        }
      )
      
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
      
      // Main window - visible state controlled by tray
      if (windowVisible) {
        Window(
          state = windowState,
          onCloseRequest = { windowVisible = false }, // Hide instead of quit
          title = "Trailblaze",
          alwaysOnTop = currentServerState.appConfig.alwaysOnTop,
        ) {
        // Capture the AWT window reference for bringing to front
        LaunchedEffect(Unit) {
          awtWindow = window
          // Bring to front when window becomes visible
          window.toFront()
          window.requestFocus()
        }

        // Self-test server: expose the live window as a Compose RPC test target
        if (currentServerState.appConfig.enableSelfTestServer) {
          LaunchedEffect(Unit) {
            val composeWindow = window
            val target = LiveWindowComposeTarget(composeWindow)
            val server = ComposeRpcServer(target, port = ComposeRpcServer.COMPOSE_DEFAULT_PORT)
            server.start(wait = false)
            try {
              kotlinx.coroutines.awaitCancellation()
            } finally {
              server.stop()
            }
          }
        }
        
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
            mcpServerDebugStateFlow = trailblazeMcpServer.mcpServerDebugStateFlow,
          )
        }
      }
      } // end if (windowVisible)
    }
  }
}

@Composable
fun TrailblazeAppContent(
  allTabs: () -> List<TrailblazeAppTab>,
  inProgressCount: Int,
  firstSessionStatus: xyz.block.trailblaze.logs.model.SessionStatus?,
  currentServerState: TrailblazeServerState,
  trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  windowState: WindowState,
  deviceManager: TrailblazeDeviceManager,
  mcpServerDebugStateFlow: StateFlow<McpServerDebugState>,
) {

  val navController = LocalNavController.current

  // Get all tabs that should be registered in NavHost (stable, computed once)
  // This ensures NavHost doesn't rebuild when visibility settings change
  val allRegisteredTabs = remember { allTabs() }

  // Get visible tabs for the navigation rail (reactive to tab visibility settings)
  // This filters which tabs appear in the UI without rebuilding NavHost
  val visibleTabs = remember(
    currentServerState.appConfig.showTrailsTab,
    currentServerState.appConfig.showDevicesTab,
    currentServerState.appConfig.showWaypointsTab,
  ) {
    allTabs().filter { tab ->
      when (tab.route) {
        TrailblazeRoute.Trails -> currentServerState.appConfig.showTrailsTab
        TrailblazeRoute.Devices -> currentServerState.appConfig.showDevicesTab
        TrailblazeRoute.Waypoints -> currentServerState.appConfig.showWaypointsTab
        else -> true
      }
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
        mcpServerDebugStateFlow = mcpServerDebugStateFlow,
        onCloseRequest = {
          trailblazeSavedSettingsRepo.updateAppConfig {
            it.copy(showDebugPopOutWindow = false)
          }
        },
      )
    }

    var railExpanded by remember { mutableStateOf(currentServerState.appConfig.navRailExpanded) }
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
            IconButton(onClick = {
              railExpanded = !railExpanded
              trailblazeSavedSettingsRepo.updateAppConfig {
                it.copy(navRailExpanded = railExpanded)
              }
            }) {
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

        // Device status panel - collapsible in bottom-right corner (hidden by default)
        if (currentServerState.appConfig.showDeviceStatusPanel) {
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

/**
 * Creates a tray icon painter using the Material Hiking icon.
 * This is used for the macOS menu bar.
 *
 * The icon is rendered in white so it is visible against the dark macOS menu bar.
 */
@Composable
private fun rememberTrayIcon(): Painter {
  val icon = Icons.Filled.Hiking
  return rememberVectorPainter(
    defaultWidth = icon.defaultWidth,
    defaultHeight = icon.defaultHeight,
    viewportWidth = icon.viewportWidth,
    viewportHeight = icon.viewportHeight,
    name = icon.name,
    tintColor = Color.White,
    autoMirror = icon.autoMirror,
  ) { _, _ ->
    RenderVectorGroup(group = icon.root)
  }
}

/**
 * Terminal handler for a daemon-port bind failure — never returns.
 *
 * The bind is the atomic arbiter for "one daemon (and one tray icon) per port". Waits briefly for
 * the rival to answer (it may have bound the socket before its routes are up), then exits: cleanly
 * as a duplicate when a rival daemon is healthy, or as an infra failure when nothing owns the port.
 *
 * Runs on the server-start coroutine while `application {}` renders concurrently, so a losing
 * non-headless instance may show its window/tray for the few seconds this takes before exiting —
 * an accepted trade-off (the alternative was the permanent server-less tray zombie).
 */
private fun exitOnPortBindFailure(port: Int, headless: Boolean, cause: Exception): Nothing {
  val causeText = cause.message ?: cause.toString()
  DaemonClient(port = port).use { daemon ->
    val rivalDaemonIsRunning = daemon.waitForDaemon(maxWaitMs = RIVAL_DAEMON_WAIT_MS)
    when (val action = classifyPortBindFailure(rivalDaemonIsRunning, headless)) {
      is PortBindFailureAction.ExitAsDuplicate -> {
        // info (not log): the exit reason must survive CLI quiet mode — this is the only
        // record of why this instance vanished.
        Console.info(
          "Trailblaze is already running on port $port — exiting this duplicate instance ($causeText).",
        )
        if (action.requestShowWindow) {
          requestWinnerShowWindow(daemon)
        }
        exitProcess(0)
      }
      PortBindFailureAction.ExitAsStartupFailure -> {
        Console.error("Failed to start the Trailblaze server on port $port: $causeText")
        exitProcess(TrailblazeExitCode.INFRA_FAILED.code)
      }
    }
  }
  throw IllegalStateException("unreachable", cause)
}

/**
 * Best-effort window handoff to the winning daemon. The winner answers `/ping` before its
 * Compose UI installs the show-window callback, and the endpoint reports `success = false`
 * until it does — so retry briefly instead of treating one early no-op as a completed handoff.
 */
private fun requestWinnerShowWindow(daemon: DaemonClient, maxWaitMs: Long = WINNER_SHOW_WINDOW_WAIT_MS) {
  val deadline = System.currentTimeMillis() + maxWaitMs
  while (System.currentTimeMillis() < deadline) {
    val shown = runCatching { daemon.showWindowBlocking().success }.getOrDefault(false)
    if (shown) return
    Thread.sleep(WINNER_SHOW_WINDOW_POLL_MS)
  }
  Console.info("Could not hand off to the running Trailblaze's window — it may be headless. Run `trailblaze app` again to show it.")
}

/**
 * Combines all additional Ktor route registrations for the daemon:
 *  - Waypoint graph endpoints (`/waypoints/graph`, `/waypoints/graph.json`)
 *  - Device API RPC endpoints (`/rpc/GetConnectedDevicesRequest`, etc.)
 *  - Device reachability health signal (`/health/device`)
 */
private fun allRouteRegistrations(
  settingsRepo: TrailblazeSettingsRepo,
  deviceManager: TrailblazeDeviceManager,
  sessionManager: xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager,
): (io.ktor.server.routing.Routing.() -> Unit) = {
  xyz.block.trailblaze.graph.WaypointGraphEndpoint.register(
    routing = this,
    defaultRootProvider = {
      val appConfig = settingsRepo.serverStateFlow.value.appConfig
      java.io.File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(appConfig))
    },
  )
  xyz.block.trailblaze.host.recording.rpc.DeviceApiEndpoint.register(
    routing = this,
    deviceManager = deviceManager,
    sessionManager = sessionManager,
  )
  xyz.block.trailblaze.host.recording.rpc.DevicesPageEndpoint.register(routing = this)
  xyz.block.trailblaze.health.DeviceHealthEndpoint.register(routing = this)
}
