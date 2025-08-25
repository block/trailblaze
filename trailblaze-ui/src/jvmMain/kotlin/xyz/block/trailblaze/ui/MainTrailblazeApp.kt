package xyz.block.trailblaze.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.ui.model.NavigationTab
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import java.io.File


class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsDir: File,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
) {
  val serverStateFlow = trailblazeSavedSettingsRepo.serverStateFlow

  fun runTrailblazeApp(
    /**
     * Custom Tabs
     */
    customTabs: List<TrailblazeAppTab>,
    availableModelLists: Set<TrailblazeLlmModelList>,
  ) {
    TrailblazeDesktopUtil.setAppConfigForTrailblaze()

    // Set the logs directory for the FileSystemImageLoader
    setLogsDirectory(logsDir)

    // Override the default TrailblazeJson instance to use the built-in tools
    TrailblazeJson.defaultWithoutToolsInstance = TrailblazeJson.createTrailblazeJsonInstance(
      TrailblazeToolSet.AllBuiltInTrailblazeToolsByKoogToolDescriptor,
    )

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
      Window(
        onCloseRequest = ::exitApplication,
        title = "🧭 Trailblaze",
        alwaysOnTop = currentServerState.appConfig.alwaysOnTop,
      ) {

        val settingsTab = TrailblazeAppTab(TrailblazeRoute.Settings, {
          LogsServerComposables.SettingsTab(
            serverState = currentServerState,
            openLogsFolder = {
              TrailblazeDesktopUtil.openInFileBrowser(logsDir)
            },
            updateState = { newState ->
              println("Update Server State: $newState")
              serverStateFlow.value = newState
            },
            openGoose = {
              TrailblazeDesktopUtil.openGoose()
            },
            openUrlInBrowser = {
              TrailblazeDesktopUtil.openInDefaultBrowser(currentServerState.appConfig.serverUrl)
            },
            additionalContent = {},
            environmentVariableProvider = { System.getenv(it) },
            availableModelLists = availableModelLists,
          )
        })

        val allTabs = customTabs + settingsTab

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

          Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
          ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
                      onClick = { currentRoute = tab.route },
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
