package xyz.block.trailblaze.ui.tabs.session

import LlmUsageComposable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells.Adaptive
import androidx.compose.foundation.lazy.grid.GridCells.Fixed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.inProgress
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.getPlatform
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.session.group.LogGroupRow
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail
import xyz.block.trailblaze.ui.theme.LocalFontScale
import xyz.block.trailblaze.ui.utils.LogUtils
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
  onShowInspectUI: (TrailblazeLog) -> Unit = {},
  onShowChatHistory: (TrailblazeLog.TrailblazeLlmRequestLog) -> Unit = {},
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: xyz.block.trailblaze.api.MaestroDriverActionType?) -> Unit = { _, _, _, _, _, _ -> },
  // Session control
  onCancelSession: () -> Unit = {},
  onDeleteSession: () -> Unit = {},
  onOpenLogsFolder: () -> Unit = {},
  onExportSession: () -> Unit = {},
  isCancelling: Boolean = false,
  // Persistent UI state
  initialZoomOffset: Int = 0,
  initialFontScale: Float = 1f,
  initialViewMode: SessionViewMode = SessionViewMode.List,
  onZoomOffsetChanged: (Int) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onViewModeChanged: (SessionViewMode) -> Unit = {},
  // Platform-specific: Open log file in Finder/Explorer
  onOpenInFinder: ((TrailblazeLog) -> Unit)? = null,
  // Platform-specific: Reveal recording file in Finder/Explorer
  onRevealRecordingInFinder: ((String) -> Unit)? = null,
  // Recordings
  recordedTrailsRepo: RecordedTrailsRepo? = null,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (sessionDetail.session.latestStatus.inProgress) {
            Button(onClick = onCancelSession, enabled = !isCancelling) {
              if (isCancelling) {
                Text("Cancelling...")
              } else {
                Text("Cancel Session")
              }
            }
          } else {
            var showDeleteConfirmation by remember { mutableStateOf(false) }
            var showMoreMenu by remember { mutableStateOf(false) }
            Box {
              IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                Icon(
                  imageVector = Icons.Default.MoreVert,
                  contentDescription = "More options",
                  modifier = Modifier.size(18.dp)
                )
              }
              DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
              ) {
                DropdownMenuItem(
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.Folder,
                      contentDescription = "Open Logs Folder"
                    )
                  },
                  text = { Text("Open Logs Folder") },
                  onClick = {
                    onOpenLogsFolder()
                    showMoreMenu = false
                  }
                )
                DropdownMenuItem(
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.Save,
                      contentDescription = "Export Session"
                    )
                  },
                  text = { Text("Export Session") },
                  onClick = {
                    onExportSession()
                    showMoreMenu = false
                  }
                )
                DropdownMenuItem(
                  leadingIcon = {
                    Icon(
                      imageVector = Icons.Default.Delete,
                      contentDescription = "Delete Session"
                    )
                  },
                  text = { Text("Delete Session") },
                  onClick = {
                    showDeleteConfirmation = true
                    showMoreMenu = false
                  }
                )
              }
            }
            if (showDeleteConfirmation) {
              androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text("Delete Session?") },
                text = {
                  Text(
                    "Are you sure you want to delete this session? This action cannot be undone."
                  )
                },
                confirmButton = {
                  androidx.compose.material3.TextButton(
                    onClick = {
                      showDeleteConfirmation = false
                      onDeleteSession()
                    }
                  ) {
                    Text("Delete")
                  }
                },
                dismissButton = {
                  androidx.compose.material3.TextButton(
                    onClick = { showDeleteConfirmation = false }
                  ) {
                    Text("Cancel")
                  }
                }
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      SelectableText(
        text = "No logs available for Session \"${sessionDetail.session.sessionId}\"",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )

      Spacer(modifier = Modifier.height(16.dp))

      Row {
        Button(onClick = onOpenLogsFolder) {
          Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = "Open Logs Folder",
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Open")
        }
      }
    }
  } else {
    val gridState = rememberLazyGridState()
    var viewMode by remember { mutableStateOf(initialViewMode) }
    var alwaysAtBottom by remember { mutableStateOf(sessionDetail.session.latestStatus.inProgress) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

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
    // Refresh if logs change or session status changes
    LaunchedEffect(sessionDetail.logs, sessionDetail.session.latestStatus.inProgress) {
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

    val focusRequester = remember { FocusRequester() }
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .focusRequester(focusRequester)
        .focusTarget()
    ) {
      LaunchedEffect(Unit) {
        focusRequester.requestFocus()
      }
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

      Box(modifier = Modifier.fillMaxSize()) {
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
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(end = 8.dp)
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                  ) {
                    if (getPlatform() == Platform.JVM) {
                      // Auto-scroll toggle - always visible for all view modes
                      Checkbox(
                        checked = alwaysAtBottom,
                        onCheckedChange = { alwaysAtBottom = it }
                      )
                      Icon(
                        imageVector = Icons.Default.MoveDown,
                        contentDescription = "Toggle auto-scroll to bottom",
                        modifier = Modifier.size(18.dp)
                      )
                      Text(
                        text = "Auto-scroll",
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium
                      )
                    }
                  }
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
                // Cancel Session button for active sessions
                if (sessionDetail.session.latestStatus.inProgress) {
                  Spacer(modifier = Modifier.width(16.dp))
                  Button(
                    onClick = onCancelSession,
                    enabled = !isCancelling,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error
                    )
                  ) {
                    if (isCancelling) {
                      Text("Cancelling...")
                    } else {
                      Text("Cancel Session")
                    }
                  }
                } else {
                  var showDeleteConfirmation by remember { mutableStateOf(false) }
                  var showMoreMenu by remember { mutableStateOf(false) }
                  Box {
                    IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                      Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        modifier = Modifier.size(18.dp)
                      )
                    }
                    DropdownMenu(
                      expanded = showMoreMenu,
                      onDismissRequest = { showMoreMenu = false }
                    ) {
                      DropdownMenuItem(
                        leadingIcon = {
                          Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Open Logs Folder"
                          )
                        },
                        text = { Text("Open Logs Folder") },
                        onClick = {
                          onOpenLogsFolder()
                          showMoreMenu = false
                        }
                      )
                      DropdownMenuItem(
                        leadingIcon = {
                          Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Export Session"
                          )
                        },
                        text = { Text("Export Session") },
                        onClick = {
                          onExportSession()
                          showMoreMenu = false
                        }
                      )
                      DropdownMenuItem(
                        leadingIcon = {
                          Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Session"
                          )
                        },
                        text = { Text("Delete Session") },
                        onClick = {
                          showDeleteConfirmation = true
                          showMoreMenu = false
                        }
                      )
                    }
                  }
                  if (showDeleteConfirmation) {
                    androidx.compose.material3.AlertDialog(
                      onDismissRequest = { showDeleteConfirmation = false },
                      title = { Text("Delete Session?") },
                      text = {
                        Text(
                          "Are you sure you want to delete this session? This action cannot be undone."
                        )
                      },
                      confirmButton = {
                        androidx.compose.material3.TextButton(
                          onClick = {
                            showDeleteConfirmation = false
                            onDeleteSession()
                          }
                        ) {
                          Text("Delete")
                        }
                      },
                      dismissButton = {
                        androidx.compose.material3.TextButton(
                          onClick = { showDeleteConfirmation = false }
                        ) {
                          Text("Cancel")
                        }
                      }
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
              sessionInfo = sessionDetail.session,
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
                        showInspectUI = { onShowInspectUI(log) },
                        showChatHistory = {
                          if (log is TrailblazeLog.TrailblazeLlmRequestLog) onShowChatHistory(
                            log
                          )
                        },
                        onShowScreenshotModal = onShowScreenshotModal,
                        onOpenInFinder = { onOpenInFinder?.invoke(log) }
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
                                showDetails = { onShowDetails(groupedLog.log) },
                                cardSize = cardSize,
                                showInspectUI = when (groupedLog.log) {
                                  is TrailblazeLog.TrailblazeLlmRequestLog -> {
                                    { onShowInspectUI(groupedLog.log) }
                                  }

                                  is TrailblazeLog.MaestroDriverLog -> {
                                    if (groupedLog.log.viewHierarchy != null) {
                                      { onShowInspectUI(groupedLog.log) }
                                    } else null
                                  }

                                  else -> null
                                },
                                showChatHistory = when (groupedLog.log) {
                                  is TrailblazeLog.TrailblazeLlmRequestLog -> {
                                    { onShowChatHistory(groupedLog.log) }
                                  }

                                  else -> null
                                },
                                onShowScreenshotModal = onShowScreenshotModal,
                                onOpenInFinder = if (onOpenInFinder != null) {
                                  { onOpenInFinder.invoke(groupedLog.log) }
                                } else null
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
                                showInspectUI = { log -> onShowInspectUI(log) },
                                showChatHistory = { log ->
                                  if (log is TrailblazeLog.TrailblazeLlmRequestLog) onShowChatHistory(
                                    log
                                  )
                                },
                                onShowScreenshotModal = onShowScreenshotModal,
                                onOpenInFinder = onOpenInFinder
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
                    val recordingScrollState = rememberScrollState()
                    Column(
                      verticalArrangement = Arrangement.spacedBy(12.dp),
                      modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(recordingScrollState)
                        .padding(end = 12.dp)
                    ) {
                      Row(

                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                      ) {
                        val clipboardManager = LocalClipboardManager.current
                        val coroutineScope = rememberCoroutineScope()
                        var saveResult by remember { mutableStateOf<Result<String>?>(null) }

                        // Load existing recordings initially
                        var existingRecordings by remember { mutableStateOf<List<String>>(emptyList()) }
                        LaunchedEffect(recordedTrailsRepo, sessionDetail.session.sessionId) {
                          if (recordedTrailsRepo != null) {
                            withContext(Dispatchers.Default) {
                              existingRecordings = recordedTrailsRepo.getExistingTrails(sessionDetail.session)
                            }
                          }
                        }

                        // Watch for file changes - only active when Recording tab is visible
                        if (onRevealRecordingInFinder != null && recordedTrailsRepo != null) {
                          val directoryPath = remember(recordedTrailsRepo, sessionDetail.session.sessionId) {
                            recordedTrailsRepo.getWatchDirectoryForSession(sessionDetail.session)
                          }

                          if (directoryPath != null) {
                            val fileChanges = remember(directoryPath) {
                              recordedTrailsRepo.watchDirectory(directoryPath)
                            }

                            if (fileChanges != null) {
                              // Collect file changes as state - automatically triggers recomposition
                              val latestChange by fileChanges.collectAsState(initial = null)

                              // Reload recordings whenever a file change is detected
                              LaunchedEffect(latestChange) {
                                if (latestChange != null) {
                                  println("Detected ${latestChange?.changeType?.name} for session recording: ${latestChange?.filePath}")
                                  withContext(Dispatchers.Default) {
                                    existingRecordings = recordedTrailsRepo.getExistingTrails(sessionDetail.session)
                                  }
                                }
                              }
                            }
                          }
                        }
                        Column {
                          Column(
                            modifier = Modifier
                              .fillMaxWidth()
                              .wrapContentHeight()
                              .padding(12.dp)
                          ) {
                            Text(
                              text = "Recorded Trail",
                              style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = MaterialTheme.typography.headlineSmall.fontSize * fontSizeScale
                              ),
                              fontWeight = FontWeight.Bold,
                              modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                              modifier = Modifier.padding(bottom = 16.dp)
                                .fillMaxWidth()
                                .wrapContentHeight()
                            ) {
                              Button(
                                onClick = {
                                  clipboardManager.setText(AnnotatedString(recordingYamlCache ?: ""))
                                }
                              ) {
                                Text("Copy Yaml")
                              }

                              // Show Save Recording button if recordingsRepo is provided
                              if (recordedTrailsRepo != null) {
                                // Calculate preview filename based on trail config
                                val trailConfig = sessionDetail.session.trailConfig
                                val platform =
                                  sessionDetail.session.trailblazeDeviceInfo?.trailblazeDriverType?.platform?.name?.lowercase()
                                val classifiers =
                                  sessionDetail.session.trailblazeDeviceInfo?.classifiers ?: listOf()
                                val baseFileNameWithPath = trailConfig?.id ?: sessionDetail.session.sessionId
                                // Extract just the filename part (everything after the last slash)
                                val baseFileName = baseFileNameWithPath.substringAfterLast('/').substringAfterLast('\\')

                                // Check if platform + first classifier are available
                                if (platform != null && classifiers.isNotEmpty()) {
                                  val firstClassifier = classifiers.first()
                                  val suffix = "-$platform-$firstClassifier"
                                  val expectedFileName = "$baseFileName$suffix.trail.yaml"

                                  // Check if file already exists
                                  val fileExists = existingRecordings.any { filePath ->
                                    val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                                    fileName == expectedFileName
                                  }

                                  Button(
                                    onClick = {
                                      coroutineScope.launch {
                                        val yaml = recordingYamlCache ?: ""
                                        saveResult = recordedTrailsRepo.saveRecording(
                                          yaml,
                                          sessionDetail.session,
                                          includePlatform = true,
                                          numClassifiers = 1
                                        )
                                        // Refresh existing recordings after save
                                        withContext(Dispatchers.Default) {
                                          existingRecordings =
                                            recordedTrailsRepo.getExistingTrails(sessionDetail.session)
                                        }
                                      }
                                    }
                                  ) {
                                    Text(if (fileExists) "Update Recording" else "Save Recording")
                                  }
                                }
                              }
                            }

                            // Show existing recordings info
                            if (recordedTrailsRepo != null && existingRecordings.isNotEmpty()) {
                              Box(
                                modifier = Modifier
                                  .fillMaxWidth()
                                  .background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                  )
                                  .padding(12.dp)
                              ) {
                                Column {
                                  Text(
                                    text = "📁 Existing Recordings (${existingRecordings.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                  )
                                  Spacer(modifier = Modifier.height(8.dp))
                                  existingRecordings.forEach { filePath ->
                                    // Extract filename from path (works on both Windows and Unix)
                                    val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
                                    Row(
                                      verticalAlignment = Alignment.CenterVertically,
                                      horizontalArrangement = Arrangement.SpaceBetween,
                                      modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                      SelectableText(
                                        text = "• $fileName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.weight(1f)
                                      )

                                      // Show "Show on Disk" button only on JVM platform
                                      if (getPlatform() == Platform.JVM && onRevealRecordingInFinder != null) {
                                        androidx.compose.material3.TextButton(
                                          onClick = { onRevealRecordingInFinder.invoke(filePath) },
                                          contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 2.dp
                                          )
                                        ) {
                                          Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = "Show on Disk",
                                            modifier = Modifier.size(14.dp)
                                          )
                                          Spacer(modifier = Modifier.width(4.dp))
                                          Text(
                                            "Show on Disk",
                                            style = MaterialTheme.typography.labelSmall
                                          )
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                              Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Show save result feedback
                            saveResult?.let { result ->
                              Row {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                  modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                      if (result.isSuccess) {
                                        MaterialTheme.colorScheme.primaryContainer
                                      } else {
                                        MaterialTheme.colorScheme.errorContainer
                                      },
                                      shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                                ) {
                                  Column {
                                    Text(
                                      text = if (result.isSuccess) "✓ Recording Saved" else "✗ Save Failed",
                                      style = MaterialTheme.typography.titleSmall,
                                      fontWeight = FontWeight.Bold,
                                      color = if (result.isSuccess) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                      } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                      }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SelectableText(
                                      text = result.getOrNull() ?: result.exceptionOrNull()?.message ?: "Unknown error",
                                      style = MaterialTheme.typography.bodySmall,
                                      color = if (result.isSuccess) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                      } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                      }
                                    )
                                  }
                                }
                              }
                            }
                          }

                          // Show warning if session is still in progress
                          if (sessionDetail.session.latestStatus.inProgress) {
                            Box(
                              modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                  MaterialTheme.colorScheme.tertiaryContainer,
                                  shape = RoundedCornerShape(8.dp)
                                )
                                .padding(16.dp)
                                .padding(bottom = 16.dp)
                            ) {
                              Column {
                                Text(
                                  text = "⚠️  Session In Progress",
                                  style = MaterialTheme.typography.titleMedium,
                                  fontWeight = FontWeight.Bold,
                                  color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                  text = "This recording is incomplete and will update automatically as new logs arrive.",
                                  style = MaterialTheme.typography.bodyMedium,
                                  color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                              }
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
        // VerticalScrollbar always visible, mapped to gridState
        VerticalScrollbar(
          adapter = rememberScrollbarAdapter(gridState),
          modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(end = 2.dp)
        )
      }
    }
  }
}
