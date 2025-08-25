package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.StatusBadge
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.group.LogGroupRow
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.utils.LogUtils
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun SessionDetailComposable(
  details: SessionDetail,
  toMaestroYaml: (JsonObject) -> String = { it.toString() },
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit = {},
  imageLoader: ImageLoader = NetworkImageLoader(),
  // Modal callbacks
  onShowDetails: (TrailblazeLog) -> Unit = {},
  onShowInspectUI: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = {},
) {
  if (details.logs.isEmpty()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
      // Header with title and action buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = onBackClick) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = "Back to sessions",
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          SelectableText(
            text = "Trailblaze Logs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      SelectableText(
        text = "No logs available for Session \"${details.session.sessionId}\"",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  } else {
    val gridState = rememberLazyGridState()
    val sizeOptions = listOf(140.dp, 200.dp, 260.dp, 320.dp, 400.dp, 480.dp)
    var sizeIndex by remember { mutableStateOf(1) }
    var viewMode by remember { mutableStateOf(SessionViewMode.Grid) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      // Header item
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = onBackClick) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = "Back to sessions",
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          SelectableText(
            text = "Trailblaze Logs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          // View mode toggle
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewMode = SessionViewMode.Grid }) {
              Text(
                text = "Grid",
                color = if (viewMode == SessionViewMode.Grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
            TextButton(onClick = { viewMode = SessionViewMode.List }) {
              Text(
                text = "List",
                color = if (viewMode == SessionViewMode.List) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
            TextButton(onClick = { viewMode = SessionViewMode.Recording }) {
              Text(
                text = "Recording",
                color = if (viewMode == SessionViewMode.Recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
          }
          if (viewMode == SessionViewMode.Grid) {
            Spacer(modifier = Modifier.width(16.dp))
            SelectableText(
              text = "Size",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
              value = sizeIndex.toFloat(),
              onValueChange = { v -> sizeIndex = v.roundToInt().coerceIn(0, sizeOptions.lastIndex) },
              valueRange = 0f..sizeOptions.lastIndex.toFloat(),
              steps = sizeOptions.size - 2,
              modifier = Modifier.width(200.dp)
            )
          }
        }
      }

      // Session summary item
      SessionSummaryRow(
        status = details.overallStatus,
        deviceName = details.deviceName,
        deviceType = details.deviceType,
        totalDurationMs = if (details.logs.isNotEmpty()) {
          val firstLog = details.logs.minByOrNull { it.timestamp }
          val lastLog = details.logs.maxByOrNull { it.timestamp }
          if (firstLog != null && lastLog != null) {
            lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
          } else null
        } else null
      )

      // Spacer item
      Spacer(modifier = Modifier.height(16.dp))

      Column(
        modifier = Modifier.fillMaxSize()
      ) {
        when (viewMode) {
          SessionViewMode.Grid -> {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = sizeOptions[sizeIndex]),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              // Log items
              gridItems(details.logs) { log ->
                LogCard(
                  log = log,
                  sessionId = details.session.sessionId,
                  toMaestroYaml = toMaestroYaml,
                  imageLoader = imageLoader,
                  showDetails = { onShowDetails(log) },
                  showInspectUI = {
                    if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                      onShowInspectUI(log)
                    }
                  }
                )
              }
            }
          }

          SessionViewMode.List -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(1),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(0.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              val groupedLogs = LogUtils.groupLogsByLlmResponseId(details.logs)
              gridItems(groupedLogs) { groupedLog ->
                Column {
                  when (groupedLog) {
                    is GroupedLog.Single -> {
                      LogListRow(
                        log = groupedLog.log,
                        sessionId = details.session.sessionId,
                        sessionStartTime = details.session.timestamp,
                        imageLoader = imageLoader,
                        showDetails = { onShowDetails(groupedLog.log) }
                      )
                    }

                    is GroupedLog.Group -> {
                      LogGroupRow(
                        group = groupedLog,
                        sessionId = details.session.sessionId,
                        sessionStartTime = details.session.timestamp,
                        imageLoader = imageLoader,
                        showDetails = { log -> onShowDetails(log) },
                        showInspectUI = { log ->
                          if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                            onShowInspectUI(log)
                          }
                        }
                      )
                    }
                  }
                  if (groupedLog != groupedLogs.last()) {
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f))
                    )
                  }
                }
              }
            }
          }

          SessionViewMode.Recording -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(1),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              item {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Text(
                      text = "Session Recording",
                      style = MaterialTheme.typography.headlineSmall,
                      fontWeight = FontWeight.Bold,
                    )
                    val clipboardManager = LocalClipboardManager.current
                    Button(
                      onClick = {
                        clipboardManager.setText(AnnotatedString(generateRecordingYaml()))
                      }
                    ) {
                      Text("Copy Yaml")
                    }
                  }
                  CodeBlock(text = generateRecordingYaml())
                }
              }
            }
          }
        }
      }
    }
  }
}
