package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import java.io.File

@Serializable
object SessionsRoute

@Serializable
object TestRailRoute

@Serializable
object SettingsRoute

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
    customTabs: List<Pair<String, @Composable () -> Unit>>,
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

      // Wait for the server to start
      delay(1000)

      // Auto Launch Browser if enabled
      if (appConfig.autoLaunchBrowser) {
        TrailblazeDesktopUtil.openInDefaultBrowser(trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.serverUrl)
      }

      // Auto Launch Goose if enabled
      if (appConfig.autoLaunchGoose) {
        TrailblazeDesktopUtil.openGoose()
      }
    }

    application {
      Window(
        onCloseRequest = ::exitApplication,
        title = "🧭 Trailblaze",
      ) {
        val currentServerState by serverStateFlow.collectAsState()

        TrailblazeTheme(themeMode = currentServerState.appConfig.themeMode) {
          val allTabs = customTabs + Pair<String, @Composable () -> Unit>("Settings", {
            LogsServerComposables.Settings(
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
              envOpenAiApiKey = System.getenv("OPENAI_API_KEY"),
              envDatabricksToken = System.getenv("DATABRICKS_TOKEN"),
            )
          })

          // Map provided tabs to typed routes by name
          val sessionsContent = allTabs.firstOrNull { it.first.equals("sessions", ignoreCase = true) }?.second
          // Gate TestRail feature on required environment variables
          val hasTestRailCreds =
            (System.getenv("TEST_RAIL_EMAIL")?.isNotBlank() == true) &&
                (System.getenv("TEST_RAIL_API_KEY")?.isNotBlank() == true)
          val testRailContent = if (hasTestRailCreds) {
            allTabs.firstOrNull {
              it.first.contains("testrail", ignoreCase = true) || it.first.contains(
                "test rail",
                ignoreCase = true
              )
            }?.second
          } else null
          val settingsContent = allTabs.firstOrNull { it.first.equals("settings", ignoreCase = true) }?.second

          var currentRoute by remember {
            mutableStateOf<Any>(
              when {
                sessionsContent != null -> SessionsRoute
                testRailContent != null -> TestRailRoute
                else -> SettingsRoute
              }
            )
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
                if (sessionsContent != null) {
                  val selected = currentRoute is SessionsRoute
                  NavigationRailItem(
                    selected = selected,
                    onClick = { currentRoute = SessionsRoute },
                    icon = { Icon(Icons.Filled.List, contentDescription = "Sessions") },
                    label = if (railExpanded) ({ Text("Sessions") }) else null
                  )
                }
                if (testRailContent != null) {
                  val selected = currentRoute is TestRailRoute
                  NavigationRailItem(
                    selected = selected,
                    onClick = { currentRoute = TestRailRoute },
                    icon = { Icon(Icons.Filled.Search, contentDescription = "TestRail") },
                    label = if (railExpanded) ({ Text("TestRail") }) else null
                  )
                }
                run {
                  val selected = currentRoute is SettingsRoute
                  NavigationRailItem(
                    selected = selected,
                    onClick = { currentRoute = SettingsRoute },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = if (railExpanded) ({ Text("Settings") }) else null
                  )
                }
              }
              Column(
                modifier = Modifier
                  .weight(1f)
                  .fillMaxSize()
                  .padding(0.dp)
              ) {
                when (currentRoute) {
                  is SessionsRoute -> sessionsContent?.invoke()
                  is TestRailRoute -> testRailContent?.invoke()
                  is SettingsRoute -> (settingsContent ?: run {
                    @Composable
                    fun DefaultSettings() {
                      LogsServerComposables.Settings(
                        serverState = currentServerState,
                        openLogsFolder = { TrailblazeDesktopUtil.openInFileBrowser(logsDir) },
                        updateState = { newState -> serverStateFlow.value = newState },
                        openGoose = { TrailblazeDesktopUtil.openGoose() },
                        openUrlInBrowser = { TrailblazeDesktopUtil.openInDefaultBrowser(currentServerState.appConfig.serverUrl) },
                        additionalContent = {},
                        envOpenAiApiKey = System.getenv("OPENAI_API_KEY"),
                        envDatabricksToken = System.getenv("DATABRICKS_TOKEN"),
                      )
                    }
                    @Composable { DefaultSettings() }
                  }).invoke()

                  else -> {}
                }
              }
            }
          }
        }
      }
    }
  }
}
