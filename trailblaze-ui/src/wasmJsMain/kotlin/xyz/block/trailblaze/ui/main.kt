package xyz.block.trailblaze.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.composables.ScreenshotImageModal
import xyz.block.trailblaze.ui.navigation.WasmRoute
import xyz.block.trailblaze.ui.tabs.session.SessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.group.ChatHistoryDialog
import xyz.block.trailblaze.ui.tabs.session.group.LogDetailsDialog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.tabs.testresults.TestResultsComposable
import xyz.block.trailblaze.ui.InspectTrailblazeNodeSelectorHelper
import xyz.block.trailblaze.util.Console

// Central data provider instance
private val dataProvider: TrailblazeLogsDataProvider = InlinedDataLoader

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    TrailblazeApp()
  }
}

@Composable
fun TrailblazeApp() {
  val navController = rememberNavController()

  val toMaestroYaml: (JsonObject) -> String = { it.toString() }

  Console.log("Using data provider: $dataProvider")

  // Parse initial route from URL hash for backward compatibility with existing bookmarks
  val initialRoute = remember {
    val hash = parseRoute()
    when {
      hash.startsWith("session/") -> {
        val sessionId = hash.removePrefix("session/")
        WasmRoute.SessionDetail(sessionId)
      }
      else -> WasmRoute.Home
    }
  }

  // Track current route explicitly to avoid serialization issues with toRoute()
  var currentRoute by remember { mutableStateOf<WasmRoute>(initialRoute) }

  // Update browser URL hash when route changes
  LaunchedEffect(currentRoute) {
    val newHash = when (currentRoute) {
      is WasmRoute.Home -> ""
      is WasmRoute.SessionDetail -> "session/${(currentRoute as WasmRoute.SessionDetail).sessionId}"
    }

    val currentHash = window.location.hash.removePrefix("#")
    if (currentHash != newHash) {
      window.location.hash = newHash
    }
  }

  // Listen to browser hash changes (back/forward buttons, direct URL changes)
  // and update navigation accordingly
  DisposableEffect(Unit) {
    val hashChangeListener = { _: Any? ->
      val hash = parseRoute()
      val newRoute = when {
        hash.startsWith("session/") -> {
          val sessionId = hash.removePrefix("session/")
          WasmRoute.SessionDetail(sessionId)
        }

        else -> WasmRoute.Home
      }

      // Only navigate if the route actually changed
      if (currentRoute != newRoute) {
        currentRoute = newRoute
        navController.navigate(newRoute) {
          launchSingleTop = true
        }
      }
    }

    window.addEventListener("hashchange", hashChangeListener)

    onDispose {
      window.removeEventListener("hashchange", hashChangeListener)
    }
  }

  NavHost(
    navController = navController,
    startDestination = initialRoute,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None }
  ) {
    composable<WasmRoute.Home> {
      SessionListViewLoader(
        dataProvider = dataProvider,
        onSessionClick = { session ->
          val newRoute = WasmRoute.SessionDetail(session.sessionId.value)
          currentRoute = newRoute
          navController.navigate(newRoute)
        },
        deleteSession = null
      )
    }

    composable<WasmRoute.SessionDetail> {
      // Use currentRoute instead of toRoute() to avoid serialization errors
      val sessionDetail = currentRoute as? WasmRoute.SessionDetail
        ?: WasmRoute.SessionDetail("") // Fallback shouldn't happen

      WasmSessionDetailView(
        dataProvider = dataProvider,
        toMaestroYaml = toMaestroYaml,
        sessionName = sessionDetail.sessionId,
        onBackClick = {
          currentRoute = WasmRoute.Home
          navController.navigate(WasmRoute.Home) {
            popUpTo(WasmRoute.Home) {
              inclusive = false
            }
          }
        }
      )
    }
  }
}

/**
 * Parse the current route from browser URL hash.
 * Supports backward compatibility with existing hash-based URLs.
 */
fun parseRoute(): String = window.location.hash.removePrefix("#")

/**
 * Loader composable for WASM that fetches lightweight session info
 * and passes it to SessionListView. Does NOT load full session logs
 * upfront - those are loaded on-demand when viewing a session detail.
 */
@Composable
fun SessionListViewLoader(
  dataProvider: TrailblazeLogsDataProvider,
  onSessionClick: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)? = null,
) {
  var sessions by remember { mutableStateOf<List<SessionInfo>?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    try {
      isLoading = true
      errorMessage = null

      val startTime = window.performance.now()
      Console.log("Loading session list...")

      val sessionIds = dataProvider.getSessionIdsAsync()
      val namesEndTime = window.performance.now()
      Console.log("Got ${sessionIds.size} session IDs in ${(namesEndTime - startTime).toInt()}ms")

      val infoStartTime = window.performance.now()
      val sessionInfos = sessionIds.mapNotNull { sessionId ->
        dataProvider.getSessionInfoAsync(sessionId)
      }.sortedByDescending { it.timestamp }
      sessions = sessionInfos

      val infoEndTime = window.performance.now()
      Console.log("Loaded ${sessionInfos.size} session infos in ${(infoEndTime - infoStartTime).toInt()}ms")
      Console.log("Total session list load time: ${(infoEndTime - startTime).toInt()}ms")
    } catch (e: Exception) {
      errorMessage = "Failed to load sessions: ${e.message}"
      Console.log("Error loading sessions: ${e.message}")
      sessions = emptyList()
    } finally {
      isLoading = false
    }
  }

  if (isLoading) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = "Loading sessions...",
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.padding(16.dp)
        )
      }
    }
  } else if (errorMessage != null) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Error loading sessions",
          style = MaterialTheme.typography.headlineSmall
        )
        Text(
          text = errorMessage!!,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp)
        )
      }
    }
  } else if (sessions != null) {
    // Use the common composable with the loaded data
    Column(
      modifier = Modifier.fillMaxSize()
    ) {
      SessionListComposable(
        sessions = sessions!!,
        sessionClicked = onSessionClick,
        deleteSession = deleteSession,
        clearAllLogs = null,
        openLogsFolder = null,
        testResultsSummaryView = { TestResultsComposable(sessions = sessions!!) },
      )
    }
  }
}

@Composable
fun WasmSessionDetailView(
  dataProvider: TrailblazeLogsDataProvider,
  toMaestroYaml: (JsonObject) -> String,
  sessionName: String,
  onBackClick: () -> Unit,
) {
  // Key all state by sessionName to ensure it resets when navigating between sessions
  var logs by remember(sessionName) { mutableStateOf<List<TrailblazeLog>>(emptyList()) }
  var isLoading by remember(sessionName) { mutableStateOf(true) }
  var errorMessage by remember(sessionName) { mutableStateOf<String?>(null) }
  var sessionInfo by remember(sessionName) { mutableStateOf<SessionInfo?>(null) }

  // YAML instance for recording generation (no tool serializers needed — tools are OtherTrailblazeTool in Wasm)
  val trailblazeYaml = remember { TrailblazeYaml() }

  // Modal state
  var showDetailsDialog by remember { mutableStateOf(false) }
  var showInspectUIDialog by remember { mutableStateOf(false) }
  var showChatHistoryDialog by remember { mutableStateOf(false) }
  var currentLog by remember { mutableStateOf<TrailblazeLog?>(null) }
  var currentInspectorLog by remember { mutableStateOf<TrailblazeLog?>(null) }
  var currentChatHistoryLog by remember { mutableStateOf<TrailblazeLog.TrailblazeLlmRequestLog?>(null) }

  // Screenshot modal state
  var showScreenshotModal by remember { mutableStateOf(false) }
  var modalImageModel by remember { mutableStateOf<Any?>(null) }
  var modalDeviceWidth by remember { mutableStateOf(0) }
  var modalDeviceHeight by remember { mutableStateOf(0) }
  var modalClickX by remember { mutableStateOf<Int?>(null) }
  var modalClickY by remember { mutableStateOf<Int?>(null) }
  var modalAction by remember {
    mutableStateOf<xyz.block.trailblaze.api.AgentDriverAction?>(
      null
    )
  }

  LaunchedEffect(sessionName) {
    try {
      isLoading = true
      errorMessage = null

      val startTime = window.performance.now()
      Console.log("📥 [${startTime.toInt()}ms] Starting to load session: $sessionName")

      // Load session info first (from lightweight map, should be fast)
      val infoStartTime = window.performance.now()
      val sessionId = xyz.block.trailblaze.logs.model.SessionId(sessionName)
      sessionInfo = dataProvider.getSessionInfoAsync(sessionId)
      val infoEndTime = window.performance.now()
      Console.log("📋 [${infoEndTime.toInt()}ms] Session info loaded in ${(infoEndTime - infoStartTime).toInt()}ms")

      // Now load logs (this is the heavy operation)
      val logsStartTime = window.performance.now()
      val fetchedLogs: List<TrailblazeLog> = dataProvider.getLogsForSessionAsync(sessionId)
      val logsEndTime = window.performance.now()
      Console.log("📦 [${logsEndTime.toInt()}ms] Fetched ${fetchedLogs.size} logs in ${(logsEndTime - logsStartTime).toInt()}ms")

      logs = fetchedLogs

      val totalTime = window.performance.now() - startTime
      Console.log(
        "✅ [${
          window.performance.now().toInt()
        }ms] Total loading time: ${totalTime.toInt()}ms"
      )
    } catch (e: Exception) {
      errorMessage = "Failed to load logs: ${e.message}"
      Console.log("❌ Error loading session: ${e.message}")
      e.printStackTrace()
    } finally {
      isLoading = false
    }
  }

  // Proactively preload all screenshots in the background so they're
  // already cached when the user scrolls through the timeline.
  LaunchedEffect(logs) {
    if (logs.isEmpty()) return@LaunchedEffect
    val screenshotRefs = logs.mapNotNull { log ->
      (log as? HasScreenshot)?.screenshotFile
    }.distinct()
    if (screenshotRefs.isNotEmpty()) {
      preloadScreenshots(screenshotRefs)
    }
  }

  // Show UI as soon as we have data
  if (sessionInfo != null && logs.isNotEmpty()) {
    val renderStartTime = window.performance.now()
    Console.log("🎨 [${renderStartTime.toInt()}ms] Starting to render SessionDetailComposable with ${logs.size} logs")

    Box(modifier = Modifier.fillMaxSize()) {
      SessionDetailComposable(
        sessionDetail = SessionDetail(
          session = sessionInfo!!,
          logs = logs,
        ),
        toMaestroYaml = toMaestroYaml,
        onBackClick = onBackClick,
        generateRecordingYaml = {
          // Generate recording YAML on-the-fly from logs (no JVM pre-generation needed)
          logs.generateRecordedYaml(
            trailblazeYaml = trailblazeYaml,
            sessionTrailConfig = sessionInfo?.trailConfig,
          )
        },
        onShowDetails = { log ->
          currentLog = log
          showDetailsDialog = true
        },
        onShowInspectUI = { log ->
          currentInspectorLog = log
          showInspectUIDialog = true
        },
        onShowChatHistory = { log ->
          currentChatHistoryLog = log
          showChatHistoryDialog = true
        },
        onShowScreenshotModal = { imageModel, deviceWidth, deviceHeight, clickX, clickY, action ->
          modalImageModel = imageModel
          modalDeviceWidth = deviceWidth
          modalDeviceHeight = deviceHeight
          modalClickX = clickX
          modalClickY = clickY
          modalAction = action
          showScreenshotModal = true
        },
        onDeleteSession = {
          // No-op for WASM - read-only static version
        },
        onOpenLogsFolder = {
          // No-op for WASM - can't open file system folders in browser
        },
      )

      // Modal dialogs
      if (showDetailsDialog && currentLog != null) {
        FullScreenModalOverlay(
          onDismiss = {
            showDetailsDialog = false
            currentLog = null
          }
        ) {
          val imageLoader = remember { xyz.block.trailblaze.ui.images.NetworkImageLoader() }
          LogDetailsDialog(
            log = currentLog!!,
            sessionId = sessionName,
            imageLoader = imageLoader,
            onShowScreenshotModal = { imageModel, deviceWidth, deviceHeight, clickX, clickY, action ->
              modalImageModel = imageModel
              modalDeviceWidth = deviceWidth
              modalDeviceHeight = deviceHeight
              modalClickX = clickX
              modalClickY = clickY
              modalAction = action
              showScreenshotModal = true
            },
            showInspectUI = if (currentLog is TrailblazeLog.TrailblazeLlmRequestLog || currentLog is TrailblazeLog.AgentDriverLog || currentLog is TrailblazeLog.TrailblazeSnapshotLog) {
              {
                currentInspectorLog = currentLog
                showInspectUIDialog = true
              }
            } else null,
            onDismiss = {
              showDetailsDialog = false
              currentLog = null
            }
          )
        }
      }

      if (showInspectUIDialog && currentInspectorLog != null) {
        // Pre-resolve the image BEFORE showing the modal
        val inspectorLog = currentInspectorLog
        var imageUrl: String? = null

        when (inspectorLog) {
          is TrailblazeLog.TrailblazeLlmRequestLog -> {
            imageUrl = inspectorLog.screenshotFile
          }

          is TrailblazeLog.AgentDriverLog -> {
            imageUrl = inspectorLog.screenshotFile
          }

          is TrailblazeLog.TrailblazeSnapshotLog -> {
            imageUrl = inspectorLog.screenshotFile
          }

          else -> {}
        }

        // Pre-load the image before showing the inspector
        val imageLoader = remember { xyz.block.trailblaze.ui.images.NetworkImageLoader() }
        val preloadedImageModel = xyz.block.trailblaze.ui.resolveImageModel(sessionName, imageUrl, imageLoader)

        // Only show the modal once the image is loaded
        if (preloadedImageModel != null) {
          FullScreenModalOverlay(
            onDismiss = {
              showInspectUIDialog = false
              currentInspectorLog = null
            }
          ) {
            // State for UI Inspector controls
            var showRawJson by remember { mutableStateOf(false) }
            var fontScale by remember { mutableStateOf(1f) }

            // Inspector content
            if (inspectorLog != null) {
              var viewHierarchy: xyz.block.trailblaze.api.ViewHierarchyTreeNode? = null
              var trailblazeNodeTree: xyz.block.trailblaze.api.TrailblazeNode? = null
              var deviceWidth = 0
              var deviceHeight = 0

              when (inspectorLog) {
                is TrailblazeLog.TrailblazeLlmRequestLog -> {
                  viewHierarchy = inspectorLog.viewHierarchy
                  trailblazeNodeTree = inspectorLog.trailblazeNodeTree
                  deviceWidth = inspectorLog.deviceWidth
                  deviceHeight = inspectorLog.deviceHeight
                }

                is TrailblazeLog.AgentDriverLog -> {
                  viewHierarchy = inspectorLog.viewHierarchy
                  trailblazeNodeTree = inspectorLog.trailblazeNodeTree
                  deviceWidth = inspectorLog.deviceWidth
                  deviceHeight = inspectorLog.deviceHeight
                }

                is TrailblazeLog.TrailblazeSnapshotLog -> {
                  viewHierarchy = inspectorLog.viewHierarchy
                  trailblazeNodeTree = inspectorLog.trailblazeNodeTree
                  deviceWidth = inspectorLog.deviceWidth
                  deviceHeight = inspectorLog.deviceHeight
                }

                else -> {
                  // Other log types don't have view hierarchy data
                }
              }

              if (viewHierarchy != null) {
                // Extract filtered hierarchy if available
                val viewHierarchyFiltered = when (inspectorLog) {
                  is TrailblazeLog.TrailblazeLlmRequestLog -> inspectorLog.viewHierarchyFiltered
                  else -> null
                }

                // Create a custom image loader that ALWAYS returns the pre-loaded image
                val staticImageLoader = remember(preloadedImageModel) {
                  object : xyz.block.trailblaze.ui.images.ImageLoader {
                    override fun getImageModel(sessionId: String, screenshotFile: String?): Any? {
                      return preloadedImageModel
                    }
                  }
                }

                // Compute TrailblazeNode selectors directly — all deps are in commonMain
                val computeTrailblazeNodeSelectors = remember(trailblazeNodeTree) {
                  trailblazeNodeTree?.let {
                    InspectTrailblazeNodeSelectorHelper.createSelectorComputeFunction(root = it)
                  }
                }

                InspectViewHierarchyScreenComposable(
                  sessionId = sessionName,
                  viewHierarchy = viewHierarchy,
                  viewHierarchyFiltered = viewHierarchyFiltered,
                  trailblazeNodeTree = trailblazeNodeTree,
                  imageUrl = imageUrl,
                  deviceWidth = deviceWidth,
                  deviceHeight = deviceHeight,
                  imageLoader = staticImageLoader,
                  showRawJson = showRawJson,
                  fontScale = fontScale,
                  onShowRawJsonChanged = { showRawJson = it },
                  onFontScaleChanged = { fontScale = it },
                  onClose = {
                    showInspectUIDialog = false
                    currentInspectorLog = null
                  },
                  computeTrailblazeNodeSelectorOptions = computeTrailblazeNodeSelectors,
                )
              } else {
                Text(
                  text = "No view hierarchy data available for this log",
                  modifier = Modifier.padding(16.dp),
                  style = MaterialTheme.typography.bodyLarge,
                )
              }
            }
          }
        } else {
          // Show loading overlay while image is being resolved
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
          ) {
            Card(
              colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
              )
            ) {
              Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
              ) {
                Text(
                  text = "Loading UI Inspector...",
                  style = MaterialTheme.typography.headlineSmall
                )
              }
            }
          }
        }
      }

      if (showChatHistoryDialog && currentChatHistoryLog != null) {
        FullScreenModalOverlay(
          onDismiss = {
            showChatHistoryDialog = false
            currentChatHistoryLog = null
          }
        ) {
          ChatHistoryDialog(
            log = currentChatHistoryLog!!,
            onDismiss = {
              showChatHistoryDialog = false
              currentChatHistoryLog = null
            }
          )
        }
      }

      // Screenshot modal
      if (showScreenshotModal && modalImageModel != null) {
        ScreenshotImageModal(
          imageModel = modalImageModel!!,
          deviceWidth = modalDeviceWidth,
          deviceHeight = modalDeviceHeight,
          clickX = modalClickX,
          clickY = modalClickY,
          action = modalAction,
          onDismiss = {
            showScreenshotModal = false
            modalImageModel = null
            modalClickX = null
            modalClickY = null
            modalAction = null
          }
        )
      }
    }
  } else if (isLoading) {
    // Show a loading indicator while data is being loaded
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = "Loading session...",
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.padding(16.dp)
        )
      }
    }
  } else {
    // Handle case where sessionInfo could not be determined (e.g. no logs)
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Failed to load session",
          style = MaterialTheme.typography.headlineSmall
        )
        if (errorMessage != null) {
          Text(
            text = errorMessage!!,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp)
          )
        }
        Button(
          onClick = onBackClick,
          modifier = Modifier.padding(top = 16.dp)
        ) {
          Text("Go Back")
        }
      }
    }
  }
}
