package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.computeGroupedStats
import xyz.block.trailblaze.logs.model.groupByTest
import xyz.block.trailblaze.ui.composables.FallbackChip
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.composables.PriorityChip
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.StatusBadge
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.icons.BootstrapRecordCircle
import xyz.block.trailblaze.ui.icons.BrowserChrome

// Enum for session status filter options
enum class SessionStatusFilter(val displayName: String) {
  IN_PROGRESS("In Progress"),
  SUCCEEDED("Succeeded"),
  SUCCEEDED_FALLBACK("Succeeded (Fallback)"),
  FAILED("Failed"),
  FAILED_FALLBACK("Failed (Fallback)"),
  TIMEOUT("Timeout"),
  MAX_CALLS_LIMIT("Max Calls Limit");

  fun matches(status: SessionStatus): Boolean = when (this) {
    IN_PROGRESS -> status is SessionStatus.Started
    SUCCEEDED -> status is SessionStatus.Ended.Succeeded
    SUCCEEDED_FALLBACK -> status is SessionStatus.Ended.SucceededWithFallback
    FAILED -> status is SessionStatus.Ended.Failed
    FAILED_FALLBACK -> status is SessionStatus.Ended.FailedWithFallback
    TIMEOUT -> status is SessionStatus.Ended.TimeoutReached
    MAX_CALLS_LIMIT -> status is SessionStatus.Ended.MaxCallsLimitReached
  }
}

/** Returns the accent color for a session status (used for the left edge indicator). */
@Composable
private fun statusAccentColor(status: SessionStatus): Color = when (status) {
  is SessionStatus.Started -> Color(0xFFFFC107)
  is SessionStatus.Ended.Succeeded -> Color(0xFF4CAF50)
  is SessionStatus.Ended.SucceededWithFallback -> Color(0xFF26A69A)
  is SessionStatus.Ended.Failed -> Color(0xFFE53935)
  is SessionStatus.Ended.FailedWithFallback -> Color(0xFF7B1FA2)
  is SessionStatus.Ended.TimeoutReached -> Color(0xFFFF9800)
  is SessionStatus.Ended.MaxCallsLimitReached -> Color(0xFFE53935)
  is SessionStatus.Ended.Cancelled -> Color(0xFFFF9800)
  is SessionStatus.Unknown -> MaterialTheme.colorScheme.outlineVariant
}

/** Formats a date relative to today. */
private fun formatRelativeDate(date: LocalDate, today: LocalDate): String {
  val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)
  return when (date) {
    today -> "Today"
    yesterday -> "Yesterday"
    else -> {
      val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
      "$month ${date.dayOfMonth}, ${date.year}"
    }
  }
}

/** Aggregated stats computed from a list of sessions (deduplicated by test name). */
private data class SessionStats(
  val total: Int,
  val uniqueTests: Int,
  val passed: Int,
  val failed: Int,
  val fallback: Int,
  val retried: Int,
  val inProgress: Int,
  val timeout: Int,
  val maxCalls: Int,
  val cancelled: Int,
  val avgDurationMs: Long,
  val totalLlmCostUsd: Double,
  val totalLlmRequests: Int,
) {
  val completed get() = passed + failed + timeout + maxCalls
  val passRate: Float
    get() = if (completed > 0) passed.toFloat() / completed else 0f
}

private fun computeStats(sessions: List<SessionInfo>): SessionStats {
  val groups = sessions.groupByTest()
  val groupedStats = groups.computeGroupedStats()

  var cancelled = 0
  var totalDuration = 0L
  var durationCount = 0
  var totalLlmCost = 0.0
  var totalLlmReqs = 0

  // Compute cost/duration from the best result per group
  for (group in groups) {
    val session = group.best
    if (session.latestStatus is SessionStatus.Ended.Cancelled) cancelled++
    if (session.durationMs > 0) {
      totalDuration += session.durationMs
      durationCount++
    }
    session.llmUsageSummary?.let {
      totalLlmCost += it.totalCostInUsDollars
      totalLlmReqs += it.totalRequestCount
    }
  }

  return SessionStats(
    total = sessions.size,
    uniqueTests = groupedStats.uniqueTests,
    passed = groupedStats.passed,
    failed = groupedStats.failed,
    fallback = groupedStats.fallback,
    retried = groupedStats.retried,
    inProgress = groupedStats.inProgress,
    timeout = groupedStats.timeout,
    maxCalls = groupedStats.maxCalls,
    cancelled = cancelled,
    avgDurationMs = if (durationCount > 0) totalDuration / durationCount else 0L,
    totalLlmCostUsd = totalLlmCost,
    totalLlmRequests = totalLlmReqs,
  )
}

private fun formatDuration(ms: Long): String {
  if (ms <= 0) return "--"
  val totalSeconds = ms / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatCost(usd: Double): String = when {
  usd <= 0.0 -> "--"
  usd < 0.01 -> "<$0.01"
  else -> "$${roundTo2(usd)}"
}

private fun roundTo2(value: Double): String {
  val rounded = kotlin.math.round(value * 100) / 100.0
  val str = rounded.toString()
  val dotIndex = str.indexOf('.')
  return if (dotIndex < 0) "$str.00"
  else {
    val decimals = str.length - dotIndex - 1
    when {
      decimals == 0 -> "${str}00"
      decimals == 1 -> "${str}0"
      else -> str.substring(0, dotIndex + 3)
    }
  }
}

private fun roundTo1(value: Double): String {
  val rounded = kotlin.math.round(value * 10) / 10.0
  val str = rounded.toString()
  val dotIndex = str.indexOf('.')
  return if (dotIndex < 0) "$str.0"
  else str.substring(0, minOf(str.length, dotIndex + 2))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListComposable(
  sessions: List<SessionInfo>,
  testResultsSummaryView: @Composable () -> Unit,
  sessionClicked: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)?,
  clearAllLogs: (() -> Unit)?,
  onRefresh: (() -> Unit)? = null,
  refreshIntervalMs: Long = 2_000,
  openLogsFolder: ((SessionInfo) -> Unit)? = null,
  openLogsFolderRoot: (() -> Unit)? = null,
  onExportSession: ((SessionInfo) -> Unit)? = null,
  onImportSession: ((Any) -> Unit)? = null,
  importedSessionIds: Set<SessionId> = emptySet(),
  emptyStateContent: (@Composable () -> Unit)? = null,
) {
  // Filter states
  var selectedPriorities by remember { mutableStateOf(setOf<String>()) }
  var selectedStatuses by remember { mutableStateOf(setOf<SessionStatusFilter>()) }
  var selectedPlatforms by remember { mutableStateOf(setOf<String>()) }
  var selectedClassifiers by remember { mutableStateOf(setOf<TrailblazeDeviceClassifier>()) }
  var recordedFilter by remember { mutableStateOf<Boolean?>(null) }
  var showAdvancedFilters by remember { mutableStateOf(false) }
  var searchKeyword by remember { mutableStateOf("") }
  var showTestResults by remember { mutableStateOf(false) }

  // Collect all unique values for filters
  val allPriorities = sessions.mapNotNull { it.trailConfig?.priority }.distinct().sorted()
  val allStatuses = SessionStatusFilter.entries
  val allPlatforms =
    sessions.mapNotNull { it.trailblazeDeviceInfo?.platform?.name }.distinct().sorted()
  val allClassifiers =
    sessions.flatMap { it.trailblazeDeviceInfo?.classifiers ?: emptyList() }.distinct()
      .map { it.classifier }

  val hasActiveFilters = selectedPriorities.isNotEmpty() ||
      selectedStatuses.isNotEmpty() ||
      selectedPlatforms.isNotEmpty() ||
      selectedClassifiers.isNotEmpty() ||
      recordedFilter != null ||
      searchKeyword.isNotEmpty()

  // Filter sessions
  val filteredSessions = sessions.filter { session ->
    val priorityMatch = selectedPriorities.isEmpty() ||
        session.trailConfig?.priority?.let { it in selectedPriorities } == true ||
        (session.trailConfig?.priority == null && selectedPriorities.isEmpty())

    val statusMatch = if (selectedStatuses.isEmpty()) {
      true
    } else {
      selectedStatuses.any { it.matches(session.latestStatus) }
    }

    val platformMatch = selectedPlatforms.isEmpty() ||
        session.trailblazeDeviceInfo?.platform?.name?.let { it in selectedPlatforms } == true

    val classifierMatch = selectedClassifiers.isEmpty() ||
        session.trailblazeDeviceInfo?.classifiers?.any { it in selectedClassifiers } == true

    val recordedMatch = recordedFilter == null || session.hasRecordedSteps == recordedFilter

    val keywordMatch = searchKeyword.isEmpty() ||
        session.displayName.contains(searchKeyword, ignoreCase = true) ||
        session.trailConfig?.description?.contains(searchKeyword, ignoreCase = true) == true ||
        session.trailConfig?.id?.contains(searchKeyword, ignoreCase = true) == true ||
        session.testClass?.contains(searchKeyword, ignoreCase = true) == true ||
        session.testName?.contains(searchKeyword, ignoreCase = true) == true ||
        session.trailConfig?.title?.contains(searchKeyword, ignoreCase = true) == true

    priorityMatch && statusMatch && platformMatch && classifierMatch && recordedMatch && keywordMatch
  }

  val stats = remember(sessions) { computeStats(sessions) }

  Box {
    Column {
      // --- Header ---
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SelectableText(
          "Trailblaze Sessions",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
        )
        Row {
          var expanded by remember { mutableStateOf(false) }
          Box {
            IconButton(onClick = { expanded = true }) {
              Icon(Icons.Filled.MoreVert, contentDescription = "More Options")
            }
            DropdownMenu(
              expanded = expanded,
              onDismissRequest = { expanded = false },
            ) {
              DropdownMenuItem(
                leadingIcon = {
                  Icon(Icons.Default.Assessment, contentDescription = null)
                },
                text = { Text("Show Test Results") },
                onClick = {
                  showTestResults = true
                  expanded = false
                }
              )
              DropdownMenuItem(
                leadingIcon = {
                  Icon(Icons.Default.Folder, contentDescription = null)
                },
                text = { Text("Open Logs Folder") },
                enabled = openLogsFolderRoot != null,
                onClick = {
                  openLogsFolderRoot?.invoke()
                  expanded = false
                }
              )
              DropdownMenuItem(
                leadingIcon = {
                  Icon(Icons.Default.DeleteSweep, contentDescription = null)
                },
                text = { Text("Clear All Logs") },
                enabled = clearAllLogs != null,
                onClick = {
                  clearAllLogs?.invoke()
                  expanded = false
                }
              )
              if (onImportSession != null) {
                DropdownMenuItem(
                  leadingIcon = {
                    Icon(Icons.Default.Upload, contentDescription = null)
                  },
                  text = { Text("Import Session") },
                  onClick = {
                    onImportSession.invoke(Unit)
                    expanded = false
                  }
                )
              }
            }
          }
        }
      }

      if (sessions.isEmpty()) {
        // --- Empty state ---
        Column(
          modifier = Modifier.fillMaxWidth().padding(48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
          )
          Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "Run a trail to see session results here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
          )
          if (emptyStateContent != null) {
            Spacer(modifier = Modifier.height(4.dp))
            emptyStateContent()
          }
        }
      } else {
        val today = remember {
          Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        }

        LazyColumn(
          modifier = Modifier.padding(horizontal = 12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          // === Stats Dashboard ===
          item {
            StatsDashboard(
              stats = stats,
              selectedStatuses = selectedStatuses,
              onStatusToggle = { filter ->
                selectedStatuses = if (filter in selectedStatuses) {
                  selectedStatuses - filter
                } else {
                  selectedStatuses + filter
                }
              },
            )
          }

          // === Platform breakdown (if multiple platforms) ===
          if (allPlatforms.size > 1 || allClassifiers.size > 1) {
            item {
              PlatformBreakdown(
                sessions = sessions,
                selectedPlatforms = selectedPlatforms,
                onPlatformToggle = { platform ->
                  selectedPlatforms = if (platform in selectedPlatforms) {
                    selectedPlatforms - platform
                  } else {
                    selectedPlatforms + platform
                  }
                },
              )
            }
          }

          // === Search + filters ===
          item {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = searchKeyword,
                onValueChange = { searchKeyword = it },
                label = { Text("Search sessions...") },
                placeholder = { Text("Filter by name, description, or ID") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
              )

              OutlinedButton(
                onClick = { showAdvancedFilters = !showAdvancedFilters },
                shape = RoundedCornerShape(12.dp),
              ) {
                Icon(
                  Icons.Default.FilterList, contentDescription = "Filters",
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showAdvancedFilters) "Hide" else "More")
                if (hasActiveFilters) {
                  Spacer(modifier = Modifier.width(4.dp))
                  Box(
                    modifier = Modifier.size(8.dp).background(
                      MaterialTheme.colorScheme.primary,
                      CircleShape
                    )
                  )
                }
              }
            }
          }

          // === Advanced filter panel ===
          item {
            AnimatedVisibility(
              visible = showAdvancedFilters,
              enter = expandVertically() + fadeIn(),
              exit = shrinkVertically() + fadeOut(),
            ) {
              AdvancedFilterPanel(
                allPriorities = allPriorities,
                selectedPriorities = selectedPriorities,
                onPriorityToggle = { p ->
                  selectedPriorities = if (p in selectedPriorities) selectedPriorities - p else selectedPriorities + p
                },
                allStatuses = allStatuses,
                selectedStatuses = selectedStatuses,
                onStatusToggle = { s ->
                  selectedStatuses = if (s in selectedStatuses) selectedStatuses - s else selectedStatuses + s
                },
                allPlatforms = allPlatforms,
                selectedPlatforms = selectedPlatforms,
                onPlatformToggle = { p ->
                  selectedPlatforms = if (p in selectedPlatforms) selectedPlatforms - p else selectedPlatforms + p
                },
                allClassifiers = allClassifiers,
                selectedClassifiers = selectedClassifiers,
                onClassifierToggle = { c ->
                  selectedClassifiers = if (c in selectedClassifiers) selectedClassifiers - c else selectedClassifiers + c
                },
                recordedFilter = recordedFilter,
                onRecordedToggle = { value ->
                  recordedFilter = if (recordedFilter == value) null else value
                },
                hasActiveFilters = hasActiveFilters,
                onClearAll = {
                  selectedPriorities = emptySet()
                  selectedStatuses = emptySet()
                  selectedPlatforms = emptySet()
                  selectedClassifiers = emptySet()
                  recordedFilter = null
                  searchKeyword = ""
                },
              )
            }
          }

          // === "No matching sessions" when filters exclude everything ===
          if (filteredSessions.isEmpty()) {
            item {
              Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.SearchOff,
                  contentDescription = null,
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                  text = "No matching sessions",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectableText(
                  text = "${sessions.size} sessions hidden by current filters",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                OutlinedButton(
                  onClick = {
                    selectedPriorities = emptySet()
                    selectedStatuses = emptySet()
                    selectedPlatforms = emptySet()
                    selectedClassifiers = emptySet()
                    recordedFilter = null
                    searchKeyword = ""
                  },
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text("Clear Filters")
                }
              }
            }
          }

          // === Session list grouped by date ===
          val groupedSessions: Map<LocalDate, List<SessionInfo>> = filteredSessions.groupBy {
            it.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
          }

          groupedSessions.toList().sortedByDescending { it.first }
            .forEach { (date, sessionsForDay) ->
              item {
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = formatRelativeDate(date, today),
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.SemiBold,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Mini pass/fail for the day
                    val dayStats = computeStats(sessionsForDay)
                    if (dayStats.completed > 0) {
                      Text(
                        text = "${dayStats.passed}/${dayStats.completed}",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                          if (dayStats.passRate >= 0.5f) Color(0xFF4CAF50) else Color(0xFFE53935),
                      )
                    } else {
                      Text(
                        text = "${sessionsForDay.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                      )
                    }
                    HorizontalDivider(
                      modifier = Modifier.weight(1f),
                      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                  }
                }
                Spacer(modifier = Modifier.height(4.dp))
              }

              items(sessionsForDay.sortedByDescending { it.timestamp }) { session: SessionInfo ->
                SessionCard(
                  session = session,
                  importedSessionIds = importedSessionIds,
                  sessionClicked = sessionClicked,
                  deleteSession = deleteSession,
                  openLogsFolder = openLogsFolder,
                  onExportSession = onExportSession,
                )
              }
            }

          item { Spacer(modifier = Modifier.height(16.dp)) }
        }
      }
    }

    // Test results modal
    if (showTestResults) {
      FullScreenModalOverlay(
        onDismiss = { showTestResults = false }
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Test Results", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { showTestResults = false }) {
              Icon(Icons.Default.Close, contentDescription = "Close")
            }
          }
          Spacer(modifier = Modifier.height(16.dp))
          testResultsSummaryView()
        }
      }
    }
  }
}

// ─── Stats Dashboard ───────────────────────────────────────────────────────────

/** Hero metrics row: pass rate, average duration, total count. */
@Composable
private fun StatsDashboard(
  stats: SessionStats,
  selectedStatuses: Set<SessionStatusFilter>,
  onStatusToggle: (SessionStatusFilter) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    shape = RoundedCornerShape(16.dp),
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // --- Hero metrics ---
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        HeroMetric(
          label = "Pass Rate",
          value = if (stats.completed > 0) "${(stats.passRate * 100).toInt()}%" else "--",
          color = when {
            stats.completed == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
            stats.passRate >= 0.8f -> Color(0xFF4CAF50)
            stats.passRate >= 0.5f -> Color(0xFFFFC107)
            else -> Color(0xFFE53935)
          },
          modifier = Modifier.weight(1f),
        )
        // Vertical divider
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )
        HeroMetric(
          label = "Avg Duration",
          value = formatDuration(stats.avgDurationMs),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )
        HeroMetric(
          label = if (stats.total != stats.uniqueTests) "Tests (${stats.total} sessions)" else "Sessions",
          value = "${stats.uniqueTests}",
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        if (stats.totalLlmRequests > 0) {
          Box(
            modifier = Modifier
              .width(1.dp)
              .height(48.dp)
              .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          )
          HeroMetric(
            label = "LLM Cost",
            value = formatCost(stats.totalLlmCostUsd),
            color = Color(0xFF7C4DFF),
            modifier = Modifier.weight(1f),
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // --- Status breakdown: clickable mini cards ───
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (stats.passed > 0 || SessionStatusFilter.SUCCEEDED in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.Check,
            count = stats.passed,
            label = "Passed",
            color = Color(0xFF4CAF50),
            selected = SessionStatusFilter.SUCCEEDED in selectedStatuses ||
                SessionStatusFilter.SUCCEEDED_FALLBACK in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.SUCCEEDED) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.failed > 0 || SessionStatusFilter.FAILED in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.Close,
            count = stats.failed,
            label = "Failed",
            color = Color(0xFFE53935),
            selected = SessionStatusFilter.FAILED in selectedStatuses ||
                SessionStatusFilter.FAILED_FALLBACK in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.FAILED) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.inProgress > 0 || SessionStatusFilter.IN_PROGRESS in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.PlayArrow,
            count = stats.inProgress,
            label = "Active",
            color = Color(0xFF2196F3),
            selected = SessionStatusFilter.IN_PROGRESS in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.IN_PROGRESS) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.timeout > 0 || SessionStatusFilter.TIMEOUT in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.Timer,
            count = stats.timeout,
            label = "Timeout",
            color = Color(0xFFFF9800),
            selected = SessionStatusFilter.TIMEOUT in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.TIMEOUT) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.maxCalls > 0 || SessionStatusFilter.MAX_CALLS_LIMIT in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.Block,
            count = stats.maxCalls,
            label = "Limit",
            color = Color(0xFFE53935),
            selected = SessionStatusFilter.MAX_CALLS_LIMIT in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.MAX_CALLS_LIMIT) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.fallback > 0 || SessionStatusFilter.SUCCEEDED_FALLBACK in selectedStatuses) {
          StatusPill(
            icon = Icons.Filled.SmartToy,
            count = stats.fallback,
            label = "Fallback",
            color = Color(0xFF26A69A),
            selected = SessionStatusFilter.SUCCEEDED_FALLBACK in selectedStatuses ||
                SessionStatusFilter.FAILED_FALLBACK in selectedStatuses,
            onClick = { onStatusToggle(SessionStatusFilter.SUCCEEDED_FALLBACK) },
            modifier = Modifier.weight(1f),
          )
        }
        if (stats.retried > 0) {
          // Informational indicator (not a filter toggle)
          Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant,
            ),
          ) {
            Column(
              modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  Icons.Filled.Schedule,
                  contentDescription = null,
                  modifier = Modifier.size(14.dp),
                  tint = Color(0xFF7C4DFF),
                )
                Text(
                  "${stats.retried}",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF7C4DFF),
                )
              }
              Text(
                "Retried",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HeroMetric(
  label: String,
  value: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    SelectableText(
      text = value,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      color = color,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
  }
}

/** A tappable status pill that doubles as a quick filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPill(
  icon: ImageVector,
  count: Int,
  label: String,
  color: Color,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bgColor = if (selected) color.copy(alpha = 0.15f) else Color.Transparent
  val borderColor = if (selected) color else MaterialTheme.colorScheme.outlineVariant

  Surface(
    modifier = modifier,
    onClick = onClick,
    shape = RoundedCornerShape(10.dp),
    color = bgColor,
    border = CardDefaults.outlinedCardBorder().let {
      androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    },
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = color,
        )
        SelectableText(
          text = "$count",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = color,
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      )
    }
  }
}

// ─── Platform Breakdown ────────────────────────────────────────────────────────

/** Shows device platform distribution as clickable chips with session counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformBreakdown(
  sessions: List<SessionInfo>,
  selectedPlatforms: Set<String>,
  onPlatformToggle: (String) -> Unit,
) {
  val platformCounts = sessions
    .mapNotNull { it.trailblazeDeviceInfo?.platform?.name }
    .groupingBy { it }
    .eachCount()
    .toList()
    .sortedByDescending { it.second }

  if (platformCounts.isEmpty()) return

  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Devices",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
    platformCounts.forEach { (platform, count) ->
      val platformIcon = when (platform.lowercase()) {
        TrailblazeDevicePlatform.ANDROID.name.lowercase() -> Android
        TrailblazeDevicePlatform.IOS.name.lowercase() -> Apple
        TrailblazeDevicePlatform.WEB.name.lowercase() -> BrowserChrome
        else -> null
      }
      FilterChip(
        onClick = { onPlatformToggle(platform) },
        selected = platform in selectedPlatforms,
        label = {
          platformIcon?.let {
            Icon(
              imageVector = it,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
          }
          Text(
            "${platform.lowercase()} ($count)",
            style = MaterialTheme.typography.labelSmall,
          )
        },
        shape = RoundedCornerShape(8.dp),
      )
    }
  }
}

// ─── Advanced Filter Panel ─────────────────────────────────────────────────────

@Composable
private fun AdvancedFilterPanel(
  allPriorities: List<String>,
  selectedPriorities: Set<String>,
  onPriorityToggle: (String) -> Unit,
  allStatuses: List<SessionStatusFilter>,
  selectedStatuses: Set<SessionStatusFilter>,
  onStatusToggle: (SessionStatusFilter) -> Unit,
  allPlatforms: List<String>,
  selectedPlatforms: Set<String>,
  onPlatformToggle: (String) -> Unit,
  allClassifiers: List<String>,
  selectedClassifiers: Set<TrailblazeDeviceClassifier>,
  onClassifierToggle: (TrailblazeDeviceClassifier) -> Unit,
  recordedFilter: Boolean?,
  onRecordedToggle: (Boolean) -> Unit,
  hasActiveFilters: Boolean,
  onClearAll: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    shape = RoundedCornerShape(12.dp),
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Advanced Filters", style = MaterialTheme.typography.titleMedium)
        if (hasActiveFilters) {
          OutlinedButton(
            onClick = onClearAll,
            shape = RoundedCornerShape(8.dp),
          ) {
            Text("Clear All", style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (allPriorities.isNotEmpty()) {
        FilterSection("Priority") {
          allPriorities.forEach { priority ->
            FilterChip(
              onClick = { onPriorityToggle(priority) },
              label = { Text(priority) },
              selected = priority in selectedPriorities,
            )
          }
        }
      }

      FilterSection("Status") {
        allStatuses.forEach { status ->
          FilterChip(
            onClick = { onStatusToggle(status) },
            label = { Text(status.displayName) },
            selected = status in selectedStatuses,
          )
        }
      }

      if (allPlatforms.isNotEmpty()) {
        FilterSection("Platform") {
          allPlatforms.forEach { platform ->
            FilterChip(
              onClick = { onPlatformToggle(platform) },
              label = { Text(platform) },
              selected = platform in selectedPlatforms,
            )
          }
        }
      }

      if (allClassifiers.isNotEmpty()) {
        FilterSection("Classifiers") {
          allClassifiers.map { TrailblazeDeviceClassifier(it) }.forEach { classifier ->
            FilterChip(
              onClick = { onClassifierToggle(classifier) },
              label = { Text(classifier.classifier) },
              selected = classifier in selectedClassifiers,
            )
          }
        }
      }

      FilterSection("Uses Recorded Steps") {
        FilterChip(
          onClick = { onRecordedToggle(true) },
          label = { Text("Yes") },
          selected = recordedFilter == true,
        )
        FilterChip(
          onClick = { onRecordedToggle(false) },
          label = { Text("No") },
          selected = recordedFilter == false,
        )
      }
    }
  }
}

@Composable
private fun FilterSection(
  title: String,
  content: @Composable () -> Unit,
) {
  Text(
    title,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  FlowRow(
    modifier = Modifier.padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    content()
  }
}

// ─── Session Card ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
  session: SessionInfo,
  importedSessionIds: Set<SessionId>,
  sessionClicked: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)?,
  openLogsFolder: ((SessionInfo) -> Unit)?,
  onExportSession: ((SessionInfo) -> Unit)?,
) {
  val accentColor = statusAccentColor(session.latestStatus)
  val time = session.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).time
  val timeString = "${time.hour.toString().padStart(2, '0')}:${
    time.minute.toString().padStart(2, '0')
  }"

  Card(
    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    onClick = { sessionClicked(session) },
    shape = RoundedCornerShape(12.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
      // Status accent bar
      Box(
        modifier = Modifier
          .width(4.dp)
          .fillMaxHeight()
          .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
          .background(accentColor)
      )

      Column(modifier = Modifier.weight(1f).padding(12.dp)) {
        // --- Top row: title/test info + status + actions ---
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          SelectionContainer {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = session.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
              )

              val testIdentifier = buildString {
                session.testClass?.substringAfterLast(".")?.let { append(it) }
                session.testName?.let { append("::$it") }
              }
              if (testIdentifier.isNotEmpty() && session.trailConfig?.title != null) {
                Text(
                  text = testIdentifier,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  modifier = Modifier.padding(top = 2.dp),
                )
              }
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (session.latestStatus is SessionStatus.Ended.SucceededWithFallback ||
                session.latestStatus is SessionStatus.Ended.FailedWithFallback) {
              FallbackChip()
            }
            StatusBadge(status = session.latestStatus)

            if (deleteSession != null || openLogsFolder != null || onExportSession != null) {
              var menuExpanded by remember { mutableStateOf(false) }
              Box {
                IconButton(
                  onClick = { menuExpanded = true },
                  modifier = Modifier.size(32.dp),
                ) {
                  Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More Options",
                    modifier = Modifier.size(18.dp),
                  )
                }
                DropdownMenu(
                  expanded = menuExpanded,
                  onDismissRequest = { menuExpanded = false },
                ) {
                  DropdownMenuItem(
                    leadingIcon = {
                      Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    },
                    text = { Text("Open Session") },
                    onClick = {
                      sessionClicked(session)
                      menuExpanded = false
                    }
                  )
                  if (openLogsFolder != null) {
                    DropdownMenuItem(
                      leadingIcon = {
                        Icon(Icons.Default.Folder, contentDescription = null)
                      },
                      text = { Text("Open Logs Folder") },
                      onClick = {
                        openLogsFolder.invoke(session)
                        menuExpanded = false
                      }
                    )
                  }
                  if (deleteSession != null) {
                    DropdownMenuItem(
                      leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                      },
                      text = { Text("Delete Session") },
                      onClick = {
                        deleteSession.invoke(session)
                        menuExpanded = false
                      }
                    )
                  }
                  if (onExportSession != null) {
                    DropdownMenuItem(
                      leadingIcon = {
                        Icon(Icons.Default.Save, contentDescription = null)
                      },
                      text = { Text("Export Session") },
                      onClick = {
                        onExportSession.invoke(session)
                        menuExpanded = false
                      }
                    )
                  }
                }
              }
            }
          }
        }

        // --- Time, duration, trail ID row ---
        SelectionContainer {
          Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
              )
              Text(
                text = timeString,
                style =
                  MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }

            if (session.latestStatus !is SessionStatus.Started && session.durationMs > 0) {
              Text(
                text = formatDuration(session.durationMs),
                style =
                  MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }

            session.trailConfig?.id?.let { id ->
              Text(
                text = id,
                style =
                  MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              )
            }
          }
        }

        // --- LLM usage inline summary ---
        session.llmUsageSummary?.let { llm ->
          LlmUsageSummaryRow(llm)
        }

        // --- Metadata chips ---
        val hasChips = session.trailConfig?.priority != null ||
            session.sessionId in importedSessionIds ||
            session.hasRecordedSteps ||
            session.trailblazeDeviceInfo != null

        if (hasChips) {
          FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            session.trailConfig?.priority?.let { priority ->
              PriorityChip(priorityShortName = priority)
            }

            if (session.sessionId in importedSessionIds) {
              AssistChip(
                onClick = { },
                colors = AssistChipDefaults.assistChipColors(
                  containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                label = {
                  Text(text = "IMPORTED", style = MaterialTheme.typography.labelSmall)
                },
              )
            }

            if (session.hasRecordedSteps) {
              TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                  session.trailFilePath?.let { PlainTooltip { Text(it) } }
                },
                state = rememberTooltipState(isPersistent = true)
              ) {
                val clipboardManager = LocalClipboardManager.current
                AssistChip(
                  onClick = {
                    session.trailFilePath?.let {
                      clipboardManager.setText(AnnotatedString(it))
                    }
                  },
                  label = {
                    Icon(
                      imageVector = BootstrapRecordCircle,
                      contentDescription = session.trailFilePath ?: "Recording",
                      modifier = Modifier.size(12.dp),
                      tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = "Recording",
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                )
              }
            }

            session.trailblazeDeviceInfo?.let { deviceInfo ->
              deviceInfo.classifiers.forEach { classifier: TrailblazeDeviceClassifier ->
                val platformIcon = when (classifier.classifier) {
                  TrailblazeDevicePlatform.ANDROID.name.lowercase() -> Android
                  TrailblazeDevicePlatform.IOS.name.lowercase() -> Apple
                  TrailblazeDevicePlatform.WEB.name.lowercase() -> BrowserChrome
                  else -> null
                }
                AssistChip(
                  onClick = { },
                  colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                  ),
                  label = {
                    platformIcon?.let {
                      Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                      text = classifier.classifier.lowercase(),
                      style = MaterialTheme.typography.labelSmall
                    )
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

// ─── LLM Usage Summary Row ─────────────────────────────────────────────────────

private val LlmAccentColor = Color(0xFF7C4DFF) // Deep purple for AI/LLM

private fun formatTokenCount(tokens: Long): String = when {
  tokens >= 1_000_000 -> "${roundTo1(tokens / 1_000_000.0)}M"
  tokens >= 1_000 -> "${roundTo1(tokens / 1_000.0)}K"
  else -> "$tokens"
}

/** Compact inline summary of LLM usage for a session card. */
@Composable
private fun LlmUsageSummaryRow(llm: LlmSessionUsageAndCost) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    shape = RoundedCornerShape(8.dp),
    color = LlmAccentColor.copy(alpha = 0.06f),
  ) {
    SelectionContainer {
      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        // Row 1: Model name + provider
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Default.SmartToy,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
              tint = LlmAccentColor,
            )
            Text(
              text =
                llm.llmModel.modelId.substringAfterLast("/").substringBefore("-202"),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = LlmAccentColor,
              maxLines = 1,
            )
          }
          Text(
            text = llm.llmModel.trailblazeLlmProvider.display,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 2: Key metrics
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          LlmMetricLabel(label = "Calls", value = "${llm.totalRequestCount}")
          LlmMetricLabel(label = "Cost", value = formatCost(llm.totalCostInUsDollars))
          LlmMetricLabel(
            label = "Tokens",
            value =
              "${formatTokenCount(llm.totalInputTokens)} in / ${formatTokenCount(llm.totalOutputTokens)} out",
          )
          if (llm.averageDurationMillis > 0) {
            LlmMetricLabel(
              label = "Avg",
              value = "${roundTo1(llm.averageDurationMillis / 1000.0)}s",
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LlmMetricLabel(label: String, value: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
  }
}
