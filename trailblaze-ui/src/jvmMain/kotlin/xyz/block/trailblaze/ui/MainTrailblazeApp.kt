package xyz.block.trailblaze.ui

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
import kotlinx.coroutines.launch
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.TargetTestApp
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.SimpleDeviceSelector
import xyz.block.trailblaze.ui.model.NavigationTab
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import xyz.block.trailblaze.ui.yaml.DesktopYamlRunner


class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
  val targetTestApp: TargetTestApp,
  val customEnvVarNames: List<String>,
) {
  val serverStateFlow = trailblazeSavedSettingsRepo.serverStateFlow

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
    availableModelLists: Set<TrailblazeLlmModelList>,
    deviceManager: DeviceManager,
    yamlRunner: DesktopYamlRunner,
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
      val currentServerState by serverStateFlow.collectAsState()
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
        title = "🧭 Trailblaze",
        alwaysOnTop = currentServerState.appConfig.alwaysOnTop,
      ) {
        val settingsTab = TrailblazeAppTab(TrailblazeRoute.Settings, {
          LogsServerComposables.SettingsTab(
            serverState = currentServerState,
            openLogsFolder = {
              TrailblazeDesktopUtil.openInFileBrowser(logsRepo.logsDir)
            },
            updateState = { newState ->
              println("Update Server State: $newState")
              serverStateFlow.value = newState
            },
            openGoose = {
              TrailblazeDesktopUtil.openGoose()
            },
            additionalContent = {},
            environmentVariableProvider = { System.getenv(it) },
            availableModelLists = availableModelLists,
            customEnvVariableNames = customEnvVarNames
          )
        })

        val sessionsTab = TrailblazeAppTab(
          route = TrailblazeRoute.Sessions,
        ) {
          val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
          SessionsTabComposableJvm(
            logsRepo = logsRepo,
            serverState = serverState,
            updateState = { newState ->
              trailblazeSavedSettingsRepo.serverStateFlow.value = newState
            },
          )
        }

        val deviceTab = TrailblazeAppTab(TrailblazeRoute.Devices, {
          val appDeviceManager = remember { deviceManager ?: DeviceManager() }
          val deviceState by appDeviceManager.deviceStateFlow.collectAsState()

          LaunchedEffect(Unit) {
            appDeviceManager.loadDevices()
          }

          // Restore previously selected devices from saved preferences
          LaunchedEffect(deviceState.availableDevices) {
            if (deviceState.availableDevices.isNotEmpty() && deviceState.selectedDevices.isEmpty()) {
              val savedDeviceIds = currentServerState.appConfig.lastSelectedDeviceInstanceIds
              if (savedDeviceIds.isNotEmpty()) {
                appDeviceManager.selectDevicesByInstanceIds(savedDeviceIds)
              }
            }
          }

          // Save selected devices to preferences when they change
          LaunchedEffect(deviceState.selectedDevices) {
            val selectedIds = deviceState.selectedDevices.map { it.instanceId }
            if (selectedIds != currentServerState.appConfig.lastSelectedDeviceInstanceIds) {
              serverStateFlow.value = currentServerState.copy(
                appConfig = currentServerState.appConfig.copy(
                  lastSelectedDeviceInstanceIds = selectedIds
                )
              )
            }
          }

          SimpleDeviceSelector(
            availableDevices = deviceState.availableDevices,
            selectedDevices = deviceState.selectedDevices,
            isLoading = deviceState.isLoading,
            onDeviceToggled = { device ->
              appDeviceManager.toggleDevice(device)
            },
            onRefresh = {
              appDeviceManager.loadDevices()
            }
          )
        })

        val yamlTab = TrailblazeAppTab(
          route = TrailblazeRoute.YamlRoute,
        ) {
          val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
          YamlTabComposable(
            deviceManager = deviceManager,
            targetTestApp = targetTestApp,
            serverState = serverState,
            availableLlmModelLists = availableModelLists,
            updateState = { newState ->
              trailblazeSavedSettingsRepo.serverStateFlow.value = newState
            },
            yamlRunner = yamlRunner,
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

          val navigationTabs = allTabs.map { tab: TrailblazeAppTab ->
            val route: TrailblazeRoute = tab.route
            NavigationTab(
              route = route,
              content = tab.content,
              label = route.displayName,
              icon = route.icon,
              isEnabled = route.isEnabled,
            )
          }

          var currentRoute by remember {
            mutableStateOf(allTabs.first().route)
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
              val current = serverStateFlow.value
              val posX = (snapshot.position as? WindowPosition.Absolute)?.x?.value?.toInt()
              val posY = (snapshot.position as? WindowPosition.Absolute)?.y?.value?.toInt()

              serverStateFlow.value = current.copy(
                appConfig = current.appConfig.copy(
                  windowWidth = snapshot.width,
                  windowHeight = snapshot.height,
                  windowX = posX,
                  windowY = posY,
                )
              )
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
                  navigationTabs.forEach { tab ->
                    if (tab.isEnabled) {
                      val selected = currentRoute == tab.route
                      NavigationRailItem(
                        selected = selected,
                        onClick = {
                          currentRoute = tab.route
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
                  navigationTabs.find { it.route == currentRoute }?.content?.invoke()
                }
              }
            }
          }
        }
      }
    }
  }
}
