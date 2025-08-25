package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.InspectViewHierarchyScreenComposable
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.group.LogDetailsDialog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail


@Composable
fun LiveSessionDetailComposable(
  sessionDataProvider: LiveSessionDataProvider,
  session: SessionInfo,
  toMaestroYaml: (JsonObject) -> String,
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit,
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  // Modal state at the TOP level - this is the root
  var showDetailsDialog by remember { mutableStateOf(false) }
  var showInspectUIDialog by remember { mutableStateOf(false) }
  var currentLog by remember { mutableStateOf<TrailblazeLog?>(null) }
  var currentLlmLog by remember { mutableStateOf<TrailblazeLog.TrailblazeLlmRequestLog?>(null) }

  var logs by remember(session.sessionId) {
    mutableStateOf(sessionDataProvider.getLogsForSession(session.sessionId))
  }

  DisposableEffect(sessionDataProvider, session.sessionId) {
    val listener = object : TrailblazeSessionListener {
      override val trailblazeSessionId: String = session.sessionId

      override fun onSessionStarted() {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }

      override fun onUpdate(message: String) {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }

      override fun onSessionEnded() {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }
    }

    sessionDataProvider.startWatchingTrailblazeSession(listener)

    onDispose {
      sessionDataProvider.stopWatching(session.sessionId)
    }
  }

  val sessionDetail = remember(logs) {
    val overallStatus = logs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .lastOrNull()?.sessionStatus

    val inProgress = overallStatus == null || overallStatus is SessionStatus.Started

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

  val handleShowInspectUI: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = { log ->
    currentLlmLog = log
    showInspectUIDialog = true
  }

  // Root Box that contains everything - main content AND modals
  Box(modifier = Modifier.fillMaxSize()) {
    // Main content
    SessionDetailComposable(
      details = sessionDetail,
      toMaestroYaml = toMaestroYaml,
      generateRecordingYaml = generateRecordingYaml,
      onBackClick = onBackClick,
      imageLoader = imageLoader,
      onShowDetails = handleShowDetails,
      onShowInspectUI = handleShowInspectUI
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

    if (showInspectUIDialog && currentLlmLog != null) {
      FullScreenModalOverlay(
        onDismiss = {
          showInspectUIDialog = false
          currentLlmLog = null
        }
      ) {
        Column(
          modifier = Modifier.fillMaxSize()
        ) {
          // Header with close button
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "UI Inspector",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold
            )
            Button(
              onClick = {
                showInspectUIDialog = false
                currentLlmLog = null
              }
            ) {
              Text("Close")
            }
          }

          // Inspector content
          InspectViewHierarchyScreenComposable(
            sessionId = session.sessionId,
            viewHierarchy = currentLlmLog!!.viewHierarchy,
            imageUrl = currentLlmLog!!.screenshotFile,
            deviceWidth = currentLlmLog!!.deviceWidth,
            deviceHeight = currentLlmLog!!.deviceHeight,
          )
        }
      }
    }
  }
}
