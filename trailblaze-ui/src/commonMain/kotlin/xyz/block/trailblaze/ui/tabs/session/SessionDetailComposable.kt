package xyz.block.trailblaze.ui.tabs.session

import LlmUsageComposable
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
import androidx.compose.foundation.lazy.grid.GridCells.Adaptive
import androidx.compose.foundation.lazy.grid.GridCells.Fixed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.inProgress
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.group.LogGroupRow
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.utils.LogUtils
import xyz.block.trailblaze.ui.theme.LocalFontScale
import kotlin.math.pow
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun SessionDetailComposable(
  sessionDetail: SessionDetail,
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
  onShowChatHistory: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = {},
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?) -> Unit = { _, _, _, _, _ -> },
  // Persistent UI state
  initialZoomOffset: Int = 0,
  initialFontScale: Float = 1f,
  initialViewMode: SessionViewMode = SessionViewMode.List,
  onZoomOffsetChanged: (Int) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onViewModeChanged: (SessionViewMode) -> Unit = {},
) {
  if (sessionDetail.logs.isEmpty()) {
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
        text = "No logs available for Session \"${sessionDetail.session.sessionId}\"",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  } else {
    val gridState = rememberLazyGridState()
    var viewMode by remember { mutableStateOf(initialViewMode) }
    var alwaysAtBottom by remember { mutableStateOf(sessionDetail.session.latestStatus.inProgress) }

    // Pre-compute heavy operations in background threads and cache results
    var groupedLogs by remember { mutableStateOf<List<GroupedLog>>(emptyList()) }
    var llmUsageSummary by remember { mutableStateOf<LlmSessionUsageAndCost?>(null) }
    var recordingYamlCache by remember { mutableStateOf<String?>(null) }
    var isLoadingGroupedLogs by remember { mutableStateOf(true) }
    var isLoadingRecordingYaml by remember { mutableStateOf(true) }

    // Background computation for grouped logs (used in List view)
    LaunchedEffect(sessionDetail.logs) {
      isLoadingGroupedLogs = true
      withContext(Dispatchers.Default) {
        val computedGroupedLogs = LogUtils.groupLogsByLlmResponseId(sessionDetail.logs)
        groupedLogs = computedGroupedLogs
        isLoadingGroupedLogs = false
      }
    }

    // Background computation for LLM usage summary (used in LlmUsage view)
    LaunchedEffect(sessionDetail.logs) {
      withContext(Dispatchers.Default) {
        val computedUsage = sessionDetail.logs.computeUsageSummary()
        llmUsageSummary = computedUsage
      }
    }

    // Background computation for recording YAML (used in Recording view)
    LaunchedEffect(sessionDetail.logs) {
      isLoadingRecordingYaml = true
      withContext(Dispatchers.Default) {
        val computedYaml = generateRecordingYaml()
        recordingYamlCache = computedYaml
        isLoadingRecordingYaml = false
      }
    }

    LaunchedEffect(alwaysAtBottom, sessionDetail.logs) {
      if (alwaysAtBottom) {
        gridState.animateScrollToItem(sessionDetail.logs.lastIndex)
      }
    }

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
      var zoomOffset by remember { mutableStateOf(initialZoomOffset) }
      var lastOptimalCount by remember { mutableStateOf(optimalCardsPerRow) }
      var fontSizeScale by remember { mutableStateOf(initialFontScale) }

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
        CompositionLocalProvider(LocalFontScale provides fontSizeScale) {
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
                if (sessionDetail.session.latestStatus.inProgress) {
                  IconToggleButton(
                    checked = alwaysAtBottom, onCheckedChange = { alwaysAtBottom = it }) {
                    Icon(
                      imageVector = Icons.Default.MoveDown,
                      contentDescription = "Toggle always at bottom",
                      modifier = Modifier.size(24.dp)
                    )
                  }
                }
                // Checkbox(checked = alwaysAtBottom, onCheckedChange = { alwaysAtBottom = it }, enabled = details.session.latestStatus.inProgress)
                TextButton(onClick = {
                  viewMode = SessionViewMode.List
                  onViewModeChanged(SessionViewMode.List)
                }) {
                  Text(
                    text = "List",
                    color = if (viewMode == SessionViewMode.List) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                  )
                }
                TextButton(onClick = {
                  viewMode = SessionViewMode.Grid
                  onViewModeChanged(SessionViewMode.Grid)
                }) {
                  Text(
                    text = "Grid",
                    color = if (viewMode == SessionViewMode.Grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                  )
                }
                TextButton(onClick = {
                  viewMode = SessionViewMode.LlmUsage
                  onViewModeChanged(SessionViewMode.LlmUsage)
                }) {
                  Text(
                    text = "LLM Usage",
                    color = if (viewMode == SessionViewMode.LlmUsage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                  )
                }
                TextButton(onClick = {
                  viewMode = SessionViewMode.Recording
                  onViewModeChanged(SessionViewMode.Recording)
                }) {
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
                      shape = RoundedCornerShape(8.dp)
                    )
                    .padding(2.dp)
                ) {
                  IconButton(
                    onClick = {
                      zoomOffset++
                      onZoomOffsetChanged(zoomOffset)
                    },
                    enabled = cardsPerRow < maxCards
                  ) {
                    Icon(
                      Icons.Outlined.ZoomOut, contentDescription = "Smaller cards (more per row)"
                    )
                  }
                  IconButton(
                    onClick = {
                      zoomOffset--
                      onZoomOffsetChanged(zoomOffset)
                    },
                    enabled = cardsPerRow > 1
                  ) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = "Larger cards (fewer per row)")
                  }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .background(
                      MaterialTheme.colorScheme.surface,
                      shape = RoundedCornerShape(8.dp)
                    )
                    .padding(2.dp)
                ) {
                  IconButton(
                    onClick = {
                      fontSizeScale = (fontSizeScale - 0.1f).coerceAtLeast(0.5f)
                      onFontScaleChanged(fontSizeScale)
                    },
                    enabled = fontSizeScale > 0.5f
                  ) {
                    Icon(Icons.Outlined.TextDecrease, contentDescription = "Decrease font size")
                  }
                  IconButton(
                    onClick = {
                      fontSizeScale = (fontSizeScale + 0.1f).coerceAtMost(2f)
                      onFontScaleChanged(fontSizeScale)
                    },
                    enabled = fontSizeScale < 2f
                  ) {
                    Icon(Icons.Outlined.TextIncrease, contentDescription = "Increase font size")
                  }
                }
              }
            }
          }

          // Test Information Section
          val testTitle = sessionDetail.session.trailConfig?.title
          val testDescription = sessionDetail.session.trailConfig?.description
          val testClass = sessionDetail.session.testClass
          val testName = sessionDetail.session.testName

          if (testTitle != null || testDescription != null || testClass != null || testName != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp)
            ) {
              if (testTitle != null) {
                SelectableText(
                  text = testTitle,
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
              }

              if (testDescription != null) {
                SelectableText(
                  text = testDescription,
                  style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
              }

              if (testClass != null && testName != null) {
                SelectableText(
                  text = "$testClass::$testName",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          // Session summary item
          SessionSummaryRow(
            status = sessionDetail.overallStatus,
            deviceName = sessionDetail.deviceName,
            deviceType = sessionDetail.deviceType,
            totalDurationMs = if (sessionDetail.logs.isNotEmpty()) {
              val firstLog = sessionDetail.logs.minByOrNull { it.timestamp }
              val lastLog = sessionDetail.logs.maxByOrNull { it.timestamp }
              if (firstLog != null && lastLog != null) {
                lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
              } else null
            } else null,
            trailConfig = sessionDetail.session.trailConfig,
          )

          // Spacer item
          Spacer(modifier = Modifier.height(16.dp))

          Column(
            modifier = Modifier.fillMaxSize()
          ) {
            when (viewMode) {
              SessionViewMode.Grid -> {
                LazyVerticalGrid(
                  columns = Adaptive(minSize = cardSize),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                  state = gridState,
                  modifier = Modifier.fillMaxSize()
                ) {
                  // Log items
                  gridItems(sessionDetail.logs) { log ->
                    LogCard(
                      log = log,
                      sessionId = sessionDetail.session.sessionId,
                      sessionStartTime = sessionDetail.session.timestamp,
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
                      showChatHistory = {
                        if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                          onShowChatHistory(log)
                        }
                      },
                      onShowScreenshotModal = onShowScreenshotModal
                    )
                  }
                }
              }

              SessionViewMode.List -> {
                if (isLoadingGroupedLogs) {
                  Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                  ) {
                    Text(text = "Loading...")
                  }
                } else {
                  LazyVerticalGrid(
                    columns = Fixed(1),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize()
                  ) {
                    gridItems(groupedLogs) { groupedLog ->
                      Column {
                        when (groupedLog) {
                          is GroupedLog.Single -> {
                            LogListRow(
                              log = groupedLog.log,
                              sessionId = sessionDetail.session.sessionId,
                              sessionStartTime = sessionDetail.session.timestamp,
                              imageLoader = imageLoader,
                              showDetails = { onShowDetails(groupedLog.log) }
                            )
                          }

                          is GroupedLog.Group -> {
                            LogGroupRow(
                              group = groupedLog,
                              sessionId = sessionDetail.session.sessionId,
                              sessionStartTime = sessionDetail.session.timestamp,
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
                              showChatHistory = { log ->
                                if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
                                  onShowChatHistory(log)
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
              }

              SessionViewMode.Recording -> {
                if (isLoadingRecordingYaml) {
                  Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                  ) {
                    Text(text = "Loading...")
                  }
                } else {
                  LazyVerticalGrid(
                    columns = Fixed(1),
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
                            style = MaterialTheme.typography.headlineSmall.copy(
                              fontSize = MaterialTheme.typography.headlineSmall.fontSize * fontSizeScale
                            ),
                            fontWeight = FontWeight.Bold,
                          )
                          val clipboardManager = LocalClipboardManager.current
                          Button(
                            onClick = {
                              clipboardManager.setText(AnnotatedString(recordingYamlCache ?: ""))
                            }
                          ) {
                            Text("Copy Yaml")
                          }
                        }
                        CodeBlock(
                          text = recordingYamlCache ?: "",
                          textStyle = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * fontSizeScale
                          )
                        )
                      }
                    }
                  }
                }
              }

              SessionViewMode.LlmUsage -> {
                LlmUsageComposable(llmUsageSummary, gridState)
              }
            }
          }
        }
      }
    }
  }
}
