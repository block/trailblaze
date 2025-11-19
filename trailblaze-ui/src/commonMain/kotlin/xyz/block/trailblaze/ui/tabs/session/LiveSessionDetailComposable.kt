package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.InspectViewHierarchyScreenComposable
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.composables.ScreenshotImageModal
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.group.ChatHistoryDialog
import xyz.block.trailblaze.ui.tabs.session.group.LogDetailsDialog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Composable
fun LiveSessionDetailComposable(
  sessionDataProvider: LiveSessionDataProvider,
  session: SessionInfo,
  toMaestroYaml: (JsonObject) -> String,
  toTrailblazeYaml: (toolName: String, trailblazeTool: TrailblazeTool) -> String,
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit,
  imageLoader: ImageLoader = NetworkImageLoader(),
  onDeleteSession: () -> Unit = {},
  onOpenLogsFolder: () -> Unit = {},
  onExportSession: () -> Unit = {},
  // Persistent UI state
  initialZoomOffset: Int = 0,
  initialFontScale: Float = 1f,
  initialViewMode: SessionViewMode = SessionViewMode.List,
  onZoomOffsetChanged: (Int) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onViewModeChanged: (SessionViewMode) -> Unit = {},
  // UI Inspector settings
  initialInspectorScreenshotWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH,
  initialInspectorDetailsWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_DETAILS_WIDTH,
  initialInspectorHierarchyWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_HIERARCHY_WIDTH,
  initialInspectorFontScale: Float = TrailblazeServerState.DEFAULT_UI_INSPECTOR_FONT_SCALE,
  onInspectorScreenshotWidthChanged: (Int) -> Unit = {},
  onInspectorDetailsWidthChanged: (Int) -> Unit = {},
  onInspectorHierarchyWidthChanged: (Int) -> Unit = {},
  onInspectorFontScaleChanged: (Float) -> Unit = {},
  // Platform-specific: Open log file in Finder/Explorer
  onOpenInFinder: ((TrailblazeLog) -> Unit)? = null,
) {
  // Modal state at the TOP level - this is the root
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

  // Cancellation state
  var isCancelling by remember { mutableStateOf(false) }
  var showCancelConfirmation by remember { mutableStateOf(false) }
  var cancellationError by remember { mutableStateOf<String?>(null) }

  var logs by remember(session.sessionId) {
    mutableStateOf(emptyList<TrailblazeLog>())
  }

  // Load initial logs asynchronously
  LaunchedEffect(session.sessionId) {
    withContext(Dispatchers.Default) {
      logs = sessionDataProvider.getLogsForSession(session.sessionId)
    }
  }

  DisposableEffect(sessionDataProvider, session.sessionId) {
    val listener = object : TrailblazeSessionListener {
      override val trailblazeSessionId: String = session.sessionId

      override fun onSessionStarted() {
        CoroutineScope(Dispatchers.Default).launch {
          val newLogs = sessionDataProvider.getLogsForSession(session.sessionId)
          logs = newLogs
        }
      }

      override fun onUpdate(message: String) {
        CoroutineScope(Dispatchers.Default).launch {
          val newLogs = sessionDataProvider.getLogsForSession(session.sessionId)
          logs = newLogs
        }
      }

      override fun onSessionEnded() {
        CoroutineScope(Dispatchers.Default).launch {
          val newLogs = sessionDataProvider.getLogsForSession(session.sessionId)
          logs = newLogs
          isCancelling = false  // Reset cancelling state when session ends
        }
      }
    }

    sessionDataProvider.startWatchingTrailblazeSession(listener)

    onDispose {
      sessionDataProvider.stopWatching(session.sessionId)
    }
  }

  val sessionDetail = remember(logs, session) {
    // Use the session's latestStatus which already includes timeout detection from getSessionInfo()
    val overallStatus = session.latestStatus
    val inProgress = overallStatus is SessionStatus.Started

    val firstLogWithDeviceInfo = logs.firstOrNull { log ->
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> true
        is TrailblazeLog.MaestroDriverLog -> true
        else -> false
      }
    }

    val (deviceName, deviceType) = when (firstLogWithDeviceInfo) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
      is TrailblazeLog.MaestroDriverLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
      else -> null to null
    }

    val totalDurationMs = if (logs.isNotEmpty()) {
      val firstLog = logs.minByOrNull { it.timestamp }
      val lastLog = logs.maxByOrNull { it.timestamp }
      if (firstLog != null && lastLog != null) {
        lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
      } else null
    } else null

    SessionDetail(
      session = session,
      logs = logs,
      overallStatus = overallStatus,
      deviceName = deviceName,
      deviceType = deviceType,
      totalDurationMs = totalDurationMs
    )
  }

  // Create explicit callback functions to avoid lambda capture issues in WASM
  val handleShowDetails: (TrailblazeLog) -> Unit = { log ->
    currentLog = log
    showDetailsDialog = true
  }

  val handleShowInspectUI: (TrailblazeLog) -> Unit = { log ->
    currentInspectorLog = log
    showInspectUIDialog = true
  }

  val handleShowChatHistory: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = { log ->
    currentChatHistoryLog = log
    showChatHistoryDialog = true
  }

  val handleShowScreenshotModal: (Any?, Int, Int, Int?, Int?, xyz.block.trailblaze.api.MaestroDriverActionType?) -> Unit =
    { imageModel, deviceWidth, deviceHeight, clickX, clickY, action ->
      modalImageModel = imageModel
      modalDeviceWidth = deviceWidth
      modalDeviceHeight = deviceHeight
      modalClickX = clickX
      modalClickY = clickY
      modalAction = action
      showScreenshotModal = true
    }

  val onCancelSession: () -> Unit = {
    showCancelConfirmation = true
  }

  val handleConfirmCancel: () -> Unit = {
    showCancelConfirmation = false
    isCancelling = true
    cancellationError = null

    CoroutineScope(Dispatchers.Default).launch {
      try {
        val result = sessionDataProvider.cancelSession(session.sessionId)
        if (!result) {
          cancellationError = "Failed to cancel session. Check console for details."
        }
        // Session end will be detected by the listener and UI will update
      } catch (e: Exception) {
        cancellationError = "Error cancelling session: ${e.message}"
        isCancelling = false
      }
    }
  }

  // Root Box that contains everything - main content AND modals
  val focusRequester = remember { FocusRequester() }

  // Request focus whenever modals close
  LaunchedEffect(
    showDetailsDialog, showInspectUIDialog, showChatHistoryDialog, showScreenshotModal,
    showCancelConfirmation
  ) {
    // If all modals are closed, request focus
    if (!showDetailsDialog && !showInspectUIDialog && !showChatHistoryDialog && !showScreenshotModal && !showCancelConfirmation) {
      focusRequester.requestFocus()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .focusRequester(focusRequester)
      .focusTarget()
      .onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
          // Check if any modal is open and close it first
          when {
            showDetailsDialog -> {
              showDetailsDialog = false
              currentLog = null
              true
            }

            showInspectUIDialog -> {
              showInspectUIDialog = false
              currentInspectorLog = null
              true
            }

            showChatHistoryDialog -> {
              showChatHistoryDialog = false
              currentChatHistoryLog = null
              true
            }

            showScreenshotModal -> {
              showScreenshotModal = false
              modalImageModel = null
              modalClickX = null
              modalClickY = null
              modalAction = null
              true
            }

            showCancelConfirmation -> {
              showCancelConfirmation = false
              true
            }

            else -> {
              // No modals open, go back to session list
              onBackClick()
              true
            }
          }
        } else {
          false
        }
      }
  ) {
    // Main content
    SessionDetailComposable(
      sessionDetail = sessionDetail,
      toMaestroYaml = toMaestroYaml,
      toTrailblazeYaml = toTrailblazeYaml,
      generateRecordingYaml = generateRecordingYaml,
      onBackClick = onBackClick,
      imageLoader = imageLoader,
      onShowDetails = handleShowDetails,
      onShowInspectUI = handleShowInspectUI,
      onShowChatHistory = handleShowChatHistory,
      onShowScreenshotModal = handleShowScreenshotModal,
      initialZoomOffset = initialZoomOffset,
      initialFontScale = initialFontScale,
      initialViewMode = initialViewMode,
      onZoomOffsetChanged = onZoomOffsetChanged,
      onFontScaleChanged = onFontScaleChanged,
      onViewModeChanged = onViewModeChanged,
      onCancelSession = onCancelSession,
      onDeleteSession = onDeleteSession,
      isCancelling = isCancelling,
      onOpenLogsFolder = onOpenLogsFolder,
      onExportSession = onExportSession,
      onOpenInFinder = onOpenInFinder,
    )

    // Modal dialogs as separate children with high zIndex
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
      FullScreenModalOverlay(
        onDismiss = {
          showInspectUIDialog = false
          currentInspectorLog = null
        }
      ) {
        val inspectorLog = currentInspectorLog
        if (inspectorLog != null) {
          var viewHierarchy: xyz.block.trailblaze.api.ViewHierarchyTreeNode? = null
          var viewHierarchyFiltered: xyz.block.trailblaze.api.ViewHierarchyTreeNode? = null
          var imageUrl: String? = null
          var deviceWidth = 0
          var deviceHeight = 0

          when (inspectorLog) {
            is TrailblazeLog.TrailblazeLlmRequestLog -> {
              viewHierarchy = inspectorLog.viewHierarchy
              viewHierarchyFiltered = inspectorLog.viewHierarchyFiltered
              imageUrl = inspectorLog.screenshotFile
              deviceWidth = inspectorLog.deviceWidth
              deviceHeight = inspectorLog.deviceHeight
            }
            is TrailblazeLog.MaestroDriverLog -> {
              viewHierarchy = inspectorLog.viewHierarchy
              imageUrl = inspectorLog.screenshotFile
              deviceWidth = inspectorLog.deviceWidth
              deviceHeight = inspectorLog.deviceHeight
            }

            else -> {
              // Other log types don't have view hierarchy data
            }
          }

          if (viewHierarchy != null) {
            var showRawJson by remember { mutableStateOf(false) }
            var fontScale by remember { mutableStateOf(initialInspectorFontScale) }

            InspectViewHierarchyScreenComposable(
              sessionId = session.sessionId,
              viewHierarchy = viewHierarchy,
              viewHierarchyFiltered = viewHierarchyFiltered,
              imageUrl = imageUrl,
              deviceWidth = deviceWidth,
              deviceHeight = deviceHeight,
              imageLoader = imageLoader,
              initialScreenshotWidth = initialInspectorScreenshotWidth,
              initialDetailsWidth = initialInspectorDetailsWidth,
              initialHierarchyWidth = initialInspectorHierarchyWidth,
              showRawJson = showRawJson,
              fontScale = fontScale,
              onScreenshotWidthChanged = onInspectorScreenshotWidthChanged,
              onDetailsWidthChanged = onInspectorDetailsWidthChanged,
              onHierarchyWidthChanged = onInspectorHierarchyWidthChanged,
              onFontScaleChanged = { newScale ->
                fontScale = newScale
                onInspectorFontScaleChanged(newScale)
              },
              onShowRawJsonChanged = { showRawJson = it },
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

    if (showCancelConfirmation) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
          ) {
            showCancelConfirmation = false
          },
        contentAlignment = Alignment.Center
      ) {
        androidx.compose.material3.Card(
          modifier = Modifier
            .width(400.dp)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
            ) {
              // Prevent clicks from propagating to the background
            },
          colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
          ),
          elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 8.dp
          ),
          shape = RoundedCornerShape(16.dp)
        ) {
          Column(
            modifier = Modifier
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            Text(
              text = "Cancel Session?",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold
            )

            Text(
              text = "Are you sure you want to cancel this active session? All ongoing requests will be stopped.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Button(
                onClick = {
                  showCancelConfirmation = false
                },
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
              ) {
                Text("Keep Running")
              }

              Button(
                onClick = handleConfirmCancel,
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = !isCancelling
              ) {
                Text(if (isCancelling) "Cancelling..." else "Yes, Cancel")
              }
            }

            if (cancellationError != null) {
              Text(
                text = "⚠️ $cancellationError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
              )
            }
          }
        }
      }
    }
  }
}
