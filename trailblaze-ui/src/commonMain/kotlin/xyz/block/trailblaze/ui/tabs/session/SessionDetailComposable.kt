package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.group.LogGroupRow
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.utils.LogUtils
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun SessionDetailComposable(
  details: SessionDetail,
  toMaestroYaml: (JsonObject) -> String = { it.toString() },
  toTrailblazeYaml: (toolName: String, trailblazeTool: TrailblazeTool) -> String = { toolName, trailblazeTool ->
    buildString {
      appendLine(toolName)
      appendLine(trailblazeTool)
    }
  },
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit = {},
  imageLoader: ImageLoader = NetworkImageLoader(),
  // Modal callbacks
  onShowDetails: (TrailblazeLog) -> Unit = {},
  onShowInspectUI: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = {},
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?) -> Unit = { _, _, _, _, _ -> },
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
    var viewMode by remember { mutableStateOf(SessionViewMode.List) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      // Use the actual spacing from LazyVerticalGrid (12.dp)
      val gridSpacing = 12.dp
      val minCardWidth = 160.dp
      val targetCardWidth = 180.dp // Target card width for auto-sizing (less aggressive)

      fun calculateListViewAvailableWidth(): Dp {
        val maxIndentPadding = 32.dp // Worst case: 2 levels * 16.dp
        val groupRowPadding = 24.dp // LogGroupRow Column: start(12dp) + end(12dp)
        val flowRowEndPadding = 8.dp // FlowRow extra end padding
        return maxWidth - maxIndentPadding - groupRowPadding - flowRowEndPadding
      }

      // Calculate optimal cards per row based on target width
      val optimalCardsPerRow = if (viewMode == SessionViewMode.List) {
        // For List view, account for LogGroupRow padding and FlowRow constraints (worst case)
        val flowRowSpacing = 8.dp // FlowRow horizontal spacing between items
        val availableWidth = calculateListViewAvailableWidth()
        ((availableWidth + flowRowSpacing) / (targetCardWidth + flowRowSpacing)).toInt().coerceAtLeast(1)
      } else {
        // Grid view calculation
        ((maxWidth + gridSpacing) / (targetCardWidth + gridSpacing)).toInt().coerceAtLeast(1)
      }

      // Calculate max cards that can fit with minimum width
      val maxCards = if (viewMode == SessionViewMode.List) {
        val flowRowSpacing = 8.dp
        val availableWidth = calculateListViewAvailableWidth()
        ((availableWidth + flowRowSpacing) / (minCardWidth + flowRowSpacing)).toInt().coerceAtLeast(1)
      } else {
        ((maxWidth + gridSpacing) / (minCardWidth + gridSpacing)).toInt().coerceAtLeast(1)
      }

      // Manual zoom offset - positive means more cards, negative means fewer cards
      var zoomOffset by remember { mutableStateOf(0) }
      var lastOptimalCount by remember { mutableStateOf(optimalCardsPerRow) }

      // Only apply smoothing for automatic changes (window resize), not manual zoom
      val autoCardsPerRow = if (optimalCardsPerRow < lastOptimalCount) {
        // Window got narrower - check if we can keep current count without making cards too small
        val currentCount = lastOptimalCount + zoomOffset
        val wouldBeCardSize = if (viewMode == SessionViewMode.List) {
          val flowRowSpacing = 8.dp
          val availableWidth = calculateListViewAvailableWidth()
          (availableWidth - (flowRowSpacing * (currentCount - 1))) / currentCount
        } else {
          (maxWidth - (gridSpacing * (currentCount - 1))) / currentCount
        }
        // Keep current count if cards are still reasonably sized
        if (wouldBeCardSize >= minCardWidth && currentCount <= maxCards) {
          currentCount
        } else {
          optimalCardsPerRow + zoomOffset
        }
      } else {
        // Window stayed same or got wider - use optimal + zoom
        lastOptimalCount = optimalCardsPerRow
        optimalCardsPerRow + zoomOffset
      }

      // Calculate actual cards per row, clamped to valid range  
      val cardsPerRow = autoCardsPerRow.coerceIn(1, maxCards)

      // Calculate actual card size based on selected cards per row
      val cardSize = if (viewMode == SessionViewMode.List) {
        // In List view, account for LogGroupRow padding and FlowRow spacing (worst case)
        val flowRowSpacing = 8.dp // FlowRow horizontal spacing between items
        val availableWidth = calculateListViewAvailableWidth()
        (availableWidth - (flowRowSpacing * (cardsPerRow - 1))) / cardsPerRow
      } else {
        (maxWidth - (gridSpacing * (cardsPerRow - 1))) / cardsPerRow
      }

      Column(modifier = Modifier.fillMaxSize()) {
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
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
              TextButton(onClick = { viewMode = SessionViewMode.List }) {
                Text(
                  text = "List",
                  color = if (viewMode == SessionViewMode.List) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
              }
              TextButton(onClick = { viewMode = SessionViewMode.Grid }) {
                Text(
                  text = "Grid",
                  color = if (viewMode == SessionViewMode.Grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
              }
              TextButton(onClick = { viewMode = SessionViewMode.Recording }) {
                Text(
                  text = "Recording",
                  color = if (viewMode == SessionViewMode.Recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
              }
            }
            if (viewMode == SessionViewMode.Grid || viewMode == SessionViewMode.List) {
              Spacer(modifier = Modifier.width(16.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .background(
                    MaterialTheme.colorScheme.surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                  )
                  .padding(2.dp)
              ) {
                IconButton(
                  onClick = { zoomOffset++ },
                  enabled = cardsPerRow < maxCards
                ) {
                  Icon(Icons.Outlined.ZoomOut, contentDescription = "Increase cards per row")
                }
                IconButton(
                  onClick = { zoomOffset-- },
                  enabled = cardsPerRow > 1
                ) {
                  Icon(Icons.Outlined.ZoomIn, contentDescription = "Decrease cards per row")
                }
              }
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
                columns = GridCells.Adaptive(minSize = cardSize),
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
                    sessionStartTime = details.session.timestamp,
                    toMaestroYaml = toMaestroYaml,
                    toTrailblazeYaml = toTrailblazeYaml,
                    imageLoader = imageLoader,
                    cardSize = cardSize,
                    showDetails = { onShowDetails(log) },
                    showInspectUI = {
                      if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                        onShowInspectUI(log)
                      }
                    },
                    onShowScreenshotModal = onShowScreenshotModal
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
                          toMaestroYaml = toMaestroYaml,
                          toTrailblazeYaml = toTrailblazeYaml,
                          imageLoader = imageLoader,
                          cardSize = cardSize,
                          showDetails = { log -> onShowDetails(log) },
                          showInspectUI = { log ->
                            if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                              onShowInspectUI(log)
                            }
                          },
                          onShowScreenshotModal = onShowScreenshotModal
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
}
