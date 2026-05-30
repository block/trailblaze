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
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.ScreenshotImageModal
import xyz.block.trailblaze.ui.devices.WebDevicesGridPage
import xyz.block.trailblaze.ui.devices.WebDevicesPage
import xyz.block.trailblaze.ui.navigation.HashWriteCommand
import xyz.block.trailblaze.ui.navigation.parseHash
import xyz.block.trailblaze.ui.navigation.HistoryMode
import xyz.block.trailblaze.ui.navigation.RouteChange
import xyz.block.trailblaze.ui.navigation.WasmRoute
import xyz.block.trailblaze.ui.navigation.decideHashWrite
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

@OptIn(ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)
fun main() {
  ComposeViewport(document.body!!) {
    // The `/devices` path serves the live device viewer rather than the session report.
    // `/devices/all` opens the multi-device live grid; bare `/devices` and any other
    // `/devices/<sub-path>` fall through to the single-device picker (which is also the
    // landing target when a grid tile is clicked).
    //
    // The comparison strips a trailing slash so `/devices/all/` resolves the same as
    // `/devices/all`. Query strings live on `window.location.search`, not on `pathname`,
    // so they're already excluded.
    //
    // Long-term: this should move to `WasmRoutes` (sealed interface) so future routes like
    // `/devices/<id>` (deep-link to a single device's detail) get type-safe parse + render.
    val pathname = window.location.pathname.trimEnd('/').ifEmpty { "/" }
    when {
      pathname == "/devices/all" -> WebDevicesGridPage()
      pathname == "/devices" || pathname.startsWith("/devices/") -> WebDevicesPage()
      else -> TrailblazeApp()
    }
  }
}

@Composable
fun TrailblazeApp() {
  val navController = rememberNavController()

  val toMaestroYaml: (JsonObject) -> String = { TrailblazeYaml.jsonToYaml(it) }

  Console.log("Using data provider: $dataProvider")

  // Capture the original hash before any LaunchedEffect rewrites it. Used by the
  // single-session auto-advance below: only redirect on a "fresh" landing (no
  // explicit route), so that clicking Back to the overview is sticky.
  val initialHashWasEmpty = remember { parseRoute().isEmpty() }

  // Parse initial route from URL hash for backward compatibility with existing
  // bookmarks. Delegates to the shared [parseHash] helper so adding a new
  // route can't leave the initial parse and the hashchange listener out of
  // sync (and so dead-code parser branches surface in jvmTest).
  val initialRoute = remember { parseHash(parseRoute()) }

  // Holds the current route together with the [HistoryMode] that should apply
  // to the next URL sync. Keeping intent atomic with the route prevents a
  // separate signal flag from being consumed by an unrelated recomposition.
  // The initial mount is always REPLACE so the `/` → `/#all` rewrite doesn't
  // push a history entry; explicit user clicks below switch to PUSH.
  var routeChange by remember { mutableStateOf(RouteChange(initialRoute, HistoryMode.REPLACE)) }

  // Sync the URL hash to the current route. Home is mapped to `#all` (an
  // explicit "show the overview" marker) rather than the empty hash, so that
  // when a user on a single-session report clicks Back, the URL records the
  // intent and a reload won't re-trigger the auto-advance. The "push vs
  // replace" decision lives in [decideHashWrite] (covered by jvmTest).
  LaunchedEffect(routeChange) {
    val currentHash = window.location.hash.removePrefix("#")
    when (val command = decideHashWrite(currentHash, routeChange)) {
      is HashWriteCommand.Replace ->
        window.history.replaceState(data = null, title = "", url = "#${command.newHash}")
      is HashWriteCommand.Push -> {
        window.location.hash = command.newHash
      }
      HashWriteCommand.NoOp -> {
        // URL already at target hash (typically: hashchange listener fired
        // because the browser changed the URL first, then we caught up state).
      }
    }
  }

  // Single-session auto-advance: if the report bundle contains exactly one
  // session and the user landed on the app without an explicit route, jump
  // straight to that session's detail page. The overview remains reachable
  // via the detail page's Back button.
  LaunchedEffect(Unit) {
    if (!initialHashWasEmpty) return@LaunchedEffect
    if (routeChange.route !is WasmRoute.Home) return@LaunchedEffect
    try {
      val sessionIds = dataProvider.getSessionIdsAsync()
      if (sessionIds.size == 1 && routeChange.route is WasmRoute.Home) {
        val onlySessionId = sessionIds.first().value
        val newRoute = WasmRoute.SessionDetail(onlySessionId)
        // Auto-advance is not user-initiated — REPLACE the hash entry rather
        // than push, so browser Back exits the report instead of landing on
        // the `#all` overview we just left.
        routeChange = RouteChange(newRoute, HistoryMode.REPLACE)
        navController.navigate(newRoute) {
          launchSingleTop = true
        }
      }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // Re-raise so the LaunchedEffect cancels cleanly when this composable
      // leaves composition. Swallowing it here would keep work running after
      // teardown and break structured concurrency.
      throw e
    } catch (e: Exception) {
      Console.log("Single-session auto-advance skipped: ${e.message}")
    }
  }

  // Listen to browser hash changes (back/forward buttons, direct URL changes)
  // and update navigation accordingly
  DisposableEffect(Unit) {
    val hashChangeListener = { _: Any? ->
      val newRoute = parseHash(parseRoute())

      // Only navigate if the route actually changed. Mode is irrelevant
      // here — the URL already matches, so [decideHashWrite] returns NoOp —
      // but PUSH is the safer default if a future hashchange path ever
      // diverges the state-vs-URL.
      if (routeChange.route != newRoute) {
        routeChange = RouteChange(newRoute, HistoryMode.PUSH)
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
          routeChange = RouteChange(newRoute, HistoryMode.PUSH)
          navController.navigate(newRoute)
        },
        deleteSession = null
      )
    }

    composable<WasmRoute.SessionDetail> {
      // Read routeChange.route (not toRoute()) to avoid serialization errors
      val sessionDetail = routeChange.route as? WasmRoute.SessionDetail
        ?: WasmRoute.SessionDetail("") // Fallback shouldn't happen

      WasmSessionDetailView(
        dataProvider = dataProvider,
        toMaestroYaml = toMaestroYaml,
        sessionName = sessionDetail.sessionId,
        onBackClick = {
          routeChange = RouteChange(WasmRoute.Home, HistoryMode.PUSH)
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
        SelectableText(
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

  // Proactively preload all screenshots in the background so they're already cached
  // when the user scrolls through the timeline. Order by log timestamp so screenshots
  // earlier in the session are decompressed first — slideshow playback starts at the
  // beginning, so we want the head of the timeline ready before the tail.
  LaunchedEffect(logs) {
    if (logs.isEmpty()) return@LaunchedEffect
    val screenshotRefs = logs.asSequence()
      .sortedBy { it.timestamp }
      .mapNotNull { log -> (log as? HasScreenshot)?.screenshotFile }
      .distinct()
      .toList()
    if (screenshotRefs.isNotEmpty()) {
      preloadScreenshots(screenshotRefs)
    }
  }

  // Show UI as soon as we have data
  if (sessionInfo != null && logs.isNotEmpty()) {
    val renderStartTime = window.performance.now()
    Console.log("🎨 [${renderStartTime.toInt()}ms] Starting to render SessionDetailComposable with ${logs.size} logs")

    Box(modifier = Modifier.fillMaxSize()) {
      // Derive session metadata from logs, matching the JVM LiveSessionDetailComposable behavior
      val sessionDetail = remember(logs, sessionInfo) {
        val info = sessionInfo!!
        val overallStatus = info.latestStatus

        val firstLogWithDeviceInfo = logs.firstOrNull { log ->
          log is TrailblazeLog.TrailblazeLlmRequestLog || log is TrailblazeLog.AgentDriverLog
        }

        val (deviceName, deviceType) = when (firstLogWithDeviceInfo) {
          is TrailblazeLog.TrailblazeLlmRequestLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
          is TrailblazeLog.AgentDriverLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
          else -> null to null
        }

        val totalDurationMs = if (logs.isNotEmpty()) {
          val firstLog = logs.minByOrNull { log -> log.timestamp }
          val lastLog = logs.maxByOrNull { log -> log.timestamp }
          if (firstLog != null && lastLog != null) {
            lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
          } else null
        } else null

        SessionDetail(
          session = info,
          logs = logs,
          overallStatus = overallStatus,
          deviceName = deviceName,
          deviceType = deviceType,
          totalDurationMs = totalDurationMs,
        )
      }

      SessionDetailComposable(
        sessionDetail = sessionDetail,
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
                SelectableText(
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
          SelectableText(
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
