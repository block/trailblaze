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
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.composables.ScreenshotImageModal
import xyz.block.trailblaze.ui.tabs.session.SessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.group.ChatHistoryDialog
import xyz.block.trailblaze.ui.tabs.session.group.LogDetailsDialog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail

// Central data provider instance
private val dataProvider: TrailblazeLogsDataProvider = InlinedDataLoader
//private val dataProvider: TrailblazeLogsDataProvider = NetworkTrailblazeLogsDataProvider

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    TrailblazeApp()
  }
}

@Composable
fun TrailblazeApp() {
  var currentRoute by remember { mutableStateOf(parseRoute()) }

  // Listen for hash changes
  window.addEventListener("hashchange", {
    currentRoute = parseRoute()
  })

  val toMaestroYaml: (JsonObject) -> String = { it.toString() }
  val toTrailblazeYaml: suspend (SessionInfo) -> String = { sessionInfo ->
    dataProvider.getSessionRecordingYaml(sessionInfo.sessionId)
  }


  println("Using data provider: $dataProvider")

  when {
    currentRoute.startsWith("session/") -> {
      val sessionName = currentRoute.removePrefix("session/")
      WasmSessionDetailView(
        dataProvider = dataProvider,
        toMaestroYaml = toMaestroYaml,
        sessionName = sessionName,
        onBackClick = {
          navigateToHome()
          currentRoute = ""
        }
      )
    }

    else -> {
      println("SessionListView with dataProvider: $dataProvider")
      SessionListView(
        dataProvider = dataProvider,
        onSessionClick = { session ->
          navigateToSession(session.sessionId)
          currentRoute = "session/${session.sessionId}"
        },
        deleteSession = null
      )
    }
  }
}

fun parseRoute(): String {
  val hash = window.location.hash
  return if (hash.startsWith("#")) hash.substring(1) else ""
}

fun navigateToHome() {
  window.location.hash = ""
}

fun navigateToSession(sessionName: String) {
  window.location.hash = "session/$sessionName"
}

@Composable
fun SessionListView(
  dataProvider: TrailblazeLogsDataProvider,
  onSessionClick: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)? = null,
) {
  var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    try {
      isLoading = true
      errorMessage = null

      val startTime = window.performance.now()
      println("📋 [${startTime.toInt()}ms] Loading session list...")

      val sessionNames = dataProvider.getSessionIdsAsync()
      val namesEndTime = window.performance.now()
      println("📝 [${namesEndTime.toInt()}ms] Got ${sessionNames.size} session IDs in ${(namesEndTime - startTime).toInt()}ms")

      val infoStartTime = window.performance.now()
      sessions = sessionNames.mapNotNull { sessionName ->
        dataProvider.getSessionInfoAsync(sessionName)
      }
      val infoEndTime = window.performance.now()
      println("✅ [${infoEndTime.toInt()}ms] Loaded ${sessions.size} session infos in ${(infoEndTime - infoStartTime).toInt()}ms")
      println("🎉 [${infoEndTime.toInt()}ms] Total session list load time: ${(infoEndTime - startTime).toInt()}ms")
    } catch (e: Exception) {
      errorMessage = "Failed to load sessions: ${e.message}"
      println("❌ Error loading sessions: ${e.message}")
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
  } else {
    SessionListComposable(
      sessions = sessions,
      sessionClicked = onSessionClick,
      deleteSession = deleteSession,
      clearAllLogs = null,
      openLogsFolder = null,
    )
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
  // Recording YAML state (pre-loaded from chunks)
  var recordingYaml by remember(sessionName) { mutableStateOf<String?>(null) }

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
    mutableStateOf<xyz.block.trailblaze.api.MaestroDriverActionType?>(
      null
    )
  }

  LaunchedEffect(sessionName) {
    try {
      isLoading = true
      errorMessage = null

      val startTime = window.performance.now()
      println("📥 [${startTime.toInt()}ms] Starting to load session: $sessionName")

      // Load session info first (from lightweight map, should be fast)
      val infoStartTime = window.performance.now()
      sessionInfo = dataProvider.getSessionInfoAsync(sessionName)
      val infoEndTime = window.performance.now()
      println("📋 [${infoEndTime.toInt()}ms] Session info loaded in ${(infoEndTime - infoStartTime).toInt()}ms")

      // Now load logs (this is the heavy operation)
      val logsStartTime = window.performance.now()
      val fetchedLogs: List<TrailblazeLog> = dataProvider.getLogsForSessionAsync(sessionName)
      val logsEndTime = window.performance.now()
      println("📦 [${logsEndTime.toInt()}ms] Fetched ${fetchedLogs.size} logs in ${(logsEndTime - logsStartTime).toInt()}ms")

      // Don't resolve screenshots immediately - they'll be loaded on-demand when rendered
      // This significantly speeds up initial page load
      logs = fetchedLogs

      // Load pre-generated recording YAML from chunks
      val yamlStartTime = window.performance.now()
      try {
        recordingYaml = dataProvider.getSessionRecordingYaml(sessionName)
        val yamlEndTime = window.performance.now()
        println("📝 [${yamlEndTime.toInt()}ms] Recording YAML loaded in ${(yamlEndTime - yamlStartTime).toInt()}ms")
      } catch (e: Exception) {
        println("⚠️ Failed to load recording YAML: ${e.message}")
        recordingYaml =
          "# Error loading YAML: ${e.message}\n# YAML is pre-generated on the JVM and should be available in chunks."
      }

      val totalTime = window.performance.now() - startTime
      println(
        "✅ [${
          window.performance.now().toInt()
        }ms] Total loading time: ${totalTime.toInt()}ms"
      )
    } catch (e: Exception) {
      errorMessage = "Failed to load logs: ${e.message}"
      println("❌ Error loading session: ${e.message}")
      e.printStackTrace()
    } finally {
      isLoading = false
    }
  }

  // Show UI as soon as we have data
  if (sessionInfo != null && logs.isNotEmpty()) {
    val renderStartTime = window.performance.now()
    println("🎨 [${renderStartTime.toInt()}ms] Starting to render SessionDetailComposable with ${logs.size} logs")

    Box(modifier = Modifier.fillMaxSize()) {
      SessionDetailComposable(
        sessionDetail = SessionDetail(
          session = sessionInfo!!,
          logs = logs,
        ),
        toMaestroYaml = toMaestroYaml,
        onBackClick = onBackClick,
        generateRecordingYaml = {
          // Return the pre-loaded YAML from chunks (already loaded in LaunchedEffect above)
          recordingYaml ?: "# YAML not loaded yet..."
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
          LogDetailsDialog(
            log = currentLog!!,
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

          is TrailblazeLog.MaestroDriverLog -> {
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
              var deviceWidth = 0
              var deviceHeight = 0

              when (inspectorLog) {
                is TrailblazeLog.TrailblazeLlmRequestLog -> {
                  viewHierarchy = inspectorLog.viewHierarchy
                  deviceWidth = inspectorLog.deviceWidth
                  deviceHeight = inspectorLog.deviceHeight
                }

                is TrailblazeLog.MaestroDriverLog -> {
                  viewHierarchy = inspectorLog.viewHierarchy
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

                InspectViewHierarchyScreenComposable(
                  sessionId = sessionName,
                  viewHierarchy = viewHierarchy,
                  viewHierarchyFiltered = viewHierarchyFiltered,
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
                  }
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
