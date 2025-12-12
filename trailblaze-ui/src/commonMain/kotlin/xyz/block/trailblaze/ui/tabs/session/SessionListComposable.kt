package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
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

// Color scheme for different chip types
@Composable
fun getIdChipColors() = AssistChipDefaults.assistChipColors(
  containerColor = MaterialTheme.colorScheme.primaryContainer,
  labelColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
fun getPriorityChipColors() = AssistChipDefaults.assistChipColors(
  containerColor = MaterialTheme.colorScheme.secondaryContainer,
  labelColor = MaterialTheme.colorScheme.onSecondaryContainer
)

@Composable
fun getDeviceChipColors() = AssistChipDefaults.assistChipColors(
  containerColor = MaterialTheme.colorScheme.surfaceVariant,
  labelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

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
  importedSessionIds: Set<String> = emptySet(),
) {
  // Filter states
  var selectedPriorities by remember { mutableStateOf(setOf<String>()) }
  var selectedStatuses by remember { mutableStateOf(setOf<SessionStatusFilter>()) }
  var selectedPlatforms by remember { mutableStateOf(setOf<String>()) }
  var selectedClassifiers by remember { mutableStateOf(setOf<TrailblazeDeviceClassifier>()) }
  var recordedFilter by remember { mutableStateOf<Boolean?>(null) } // null = show all, true = recorded only, false = not recorded only
  var showFilters by remember { mutableStateOf(false) }
  var searchKeyword by remember { mutableStateOf("") }
  var showTestResults by remember { mutableStateOf(false) }

  // Collect all unique values for filters
  val allPriorities = sessions.mapNotNull { it.trailConfig?.priority }.distinct().sorted()
  val allStatuses = SessionStatusFilter.entries
  val allPlatforms =
    sessions.mapNotNull { it.trailblazeDeviceInfo?.platform?.name }.distinct().sorted()
  val allClassifiers =
    sessions.flatMap { it.trailblazeDeviceInfo?.classifiers ?: emptyList() }.distinct().map { it.classifier }

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

    // Keyword search - match against display name and description (case-insensitive)
    val keywordMatch = searchKeyword.isEmpty() ||
        session.displayName.contains(searchKeyword, ignoreCase = true) ||
        session.trailConfig?.description?.contains(searchKeyword, ignoreCase = true) == true ||
        session.trailConfig?.id?.contains(searchKeyword, ignoreCase = true) == true ||
        session.testClass?.contains(searchKeyword, ignoreCase = true) == true ||
        session.testName?.contains(searchKeyword, ignoreCase = true) == true ||
        session.trailConfig?.title?.contains(searchKeyword, ignoreCase = true) == true

    priorityMatch && statusMatch && platformMatch && classifierMatch && recordedMatch && keywordMatch
  }

  // Wrap content in a key that changes with the tick to ensure subtree recomposes periodically
  Box {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SelectableText(
          "Trailblaze Sessions",
          modifier = Modifier.padding(8.dp),
          style = MaterialTheme.typography.headlineSmall,
        )

        Row {
          var expanded by remember { mutableStateOf(false) }
          Box {
            IconButton(onClick = { expanded = true }) {
              Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More Options"
              )
            }
            DropdownMenu(
              expanded = expanded,
              onDismissRequest = { expanded = false },
            ) {
              DropdownMenuItem(
                leadingIcon = {
                  Icon(
                    Icons.Default.Assessment, contentDescription = "Show Test Results"
                  )
                },
                text = { Text("Show Test Results") },
                onClick = {
                  showTestResults = true
                  expanded = false
                }
              )
              DropdownMenuItem(
                leadingIcon = {
                  Icon(
                    Icons.Default.Folder, contentDescription = "Open Logs Folder"
                  )
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
                  Icon(
                    Icons.Default.DeleteSweep, contentDescription = "Clear All Logs"
                  )
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
                    Icon(
                      Icons.Default.Upload, contentDescription = "Import Session"
                    )
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

      // Search bar and Filters button
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = searchKeyword,
          onValueChange = { searchKeyword = it },
          label = { Text("Search sessions...") },
          placeholder = { Text("Filter by name or description") },
          modifier = Modifier.weight(1f),
          singleLine = true
        )

        OutlinedButton(
          onClick = { showFilters = !showFilters }
        ) {
          Icon(
            Icons.Default.FilterList, contentDescription = "Filters",
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Filters")
        }
      }

      // Filter section
      if (showFilters) {
        Card(
          modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))

            // Priority filters
            if (allPriorities.isNotEmpty()) {
              Text("Priority", style = MaterialTheme.typography.labelLarge)
              Row(modifier = Modifier.padding(vertical = 8.dp)) {
                allPriorities.forEach { priority ->
                  FilterChip(
                    onClick = {
                      selectedPriorities = if (priority in selectedPriorities) {
                        selectedPriorities - priority
                      } else {
                        selectedPriorities + priority
                      }
                    },
                    label = { Text(priority) },
                    selected = priority in selectedPriorities,
                    modifier = Modifier.padding(end = 4.dp)
                  )
                }
              }
            }

            // Status filters
            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
              allStatuses.forEach { status ->
                FilterChip(
                  onClick = {
                    selectedStatuses = if (status in selectedStatuses) {
                      selectedStatuses - status
                    } else {
                      selectedStatuses + status
                    }
                  },
                  label = { Text(status.displayName) },
                  selected = status in selectedStatuses,
                  modifier = Modifier.padding(end = 4.dp)
                )
              }
            }

            // Platform filters
            if (allPlatforms.isNotEmpty()) {
              Text("Platform", style = MaterialTheme.typography.labelLarge)
              Row(modifier = Modifier.padding(vertical = 8.dp)) {
                allPlatforms.forEach { platform ->
                  FilterChip(
                    onClick = {
                      selectedPlatforms = if (platform in selectedPlatforms) {
                        selectedPlatforms - platform
                      } else {
                        selectedPlatforms + platform
                      }
                    },
                    label = { Text(platform) },
                    selected = platform in selectedPlatforms,
                    modifier = Modifier.padding(end = 4.dp)
                  )
                }
              }
            }

            // Classifier filters
            if (allClassifiers.isNotEmpty()) {
              Text("Classifiers", style = MaterialTheme.typography.labelLarge)
              Row(modifier = Modifier.padding(vertical = 8.dp)) {
                allClassifiers.map { TrailblazeDeviceClassifier(it) }.forEach { classifier ->
                  FilterChip(
                    onClick = {
                      selectedClassifiers = if (classifier in selectedClassifiers) {
                        selectedClassifiers - classifier
                      } else {
                        selectedClassifiers + classifier
                      }
                    },
                    label = { Text(classifier.classifier) },
                    selected = classifier in selectedClassifiers,
                    modifier = Modifier.padding(end = 4.dp)
                  )
                }
              }
            }

            // Recorded Trail filter
            Text("Uses Recorded Steps", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
              FilterChip(
                onClick = {
                  recordedFilter = if (recordedFilter == true) null else true
                },
                label = { Text("Yes") },
                selected = recordedFilter == true,
                modifier = Modifier.padding(end = 4.dp)
              )
              FilterChip(
                onClick = {
                  recordedFilter = if (recordedFilter == false) null else false
                },
                label = { Text("No") },
                selected = recordedFilter == false,
                modifier = Modifier.padding(end = 4.dp)
              )
            }

            // Clear filters button
            if (selectedPriorities.isNotEmpty() || selectedStatuses.isNotEmpty() || selectedPlatforms.isNotEmpty() || selectedClassifiers.isNotEmpty() || recordedFilter != null || searchKeyword.isNotEmpty()) {
              OutlinedButton(
                onClick = {
                  selectedPriorities = emptySet()
                  selectedStatuses = emptySet()
                  selectedPlatforms = emptySet()
                  selectedClassifiers = emptySet()
                  recordedFilter = null
                  searchKeyword = ""
                },
                modifier = Modifier.padding(top = 8.dp)
              ) {
                Text("Clear All Filters")
              }
            }
          }
        }
      }

      val groupedSessions: Map<LocalDate, List<SessionInfo>> = filteredSessions.groupBy {
        it.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
      }

      LazyColumn(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
      ) {
        groupedSessions.toList().sortedByDescending { it.first }.forEach { (date, sessionsForDay) ->
          item {
            Text(
              text = date.toString(), // Consider a more friendly format
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }

          items(sessionsForDay.sortedByDescending { it.timestamp }) { session: SessionInfo ->
            Card(
              modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
              onClick = {
                sessionClicked(session)
              },
            ) {
              Column {
                Row(
                  modifier = Modifier.padding(8.dp).fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.Top,
                ) {
                  val time = session.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).time
                  Column(modifier = Modifier.weight(1f)) {
                    // First row: Time and test class/method name
                    val timeString = "${time.hour.toString().padStart(2, '0')}:${
                      time.minute.toString()
                        .padStart(2, '0')
                    }"

                    SelectableText(
                      text = "$timeString - ${
                        session.testClass?.substringAfterLast(
                          "."
                        ) ?: ""
                      }${if (session.testName != null) "::${session.testName}" else ""}",
                      modifier = Modifier.padding(bottom = 2.dp),
                    )

                    // Second row: Title (if available)
                    session.trailConfig?.title?.let { title ->
                      SelectableText(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                      )
                    }

                    // Combined device info and metadata row
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.Start
                    ) {
                      // Metadata (ID, priority) first
                      session.trailConfig?.let { metadata ->
                        metadata.id?.let { id ->
                          AssistChip(
                            onClick = { },
                            colors = getIdChipColors(),
                            label = {
                              Text(
                                text = "ID: $id",
                                style = MaterialTheme.typography.labelSmall
                              )
                            },
                            modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                          )
                        }

                        metadata.priority?.let { priority ->
                          AssistChip(
                            onClick = { },
                            colors = getPriorityChipColors(),
                            label = {
                              Text(
                                text = "P: $priority",
                                style = MaterialTheme.typography.labelSmall
                              )
                            },
                            modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                          )
                        }
                      }

                      // Show imported badge if session was imported
                      if (session.sessionId in importedSessionIds) {
                        AssistChip(
                          onClick = { },
                          colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                          ),
                          label = {
                            Text(
                              text = "IMPORTED",
                              style = MaterialTheme.typography.labelSmall
                            )
                          },
                          modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                        )
                      }

                      if (session.hasRecordedSteps) {
                        TooltipBox(
                          positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                          tooltip = {
                            session.trailFilePath?.let {
                              PlainTooltip {
                                Text(it)
                              }
                            }
                          },
                          state = rememberTooltipState(isPersistent = true)
                        ) {
                          val clipboardManager = LocalClipboardManager.current
                          AssistChip(
                            onClick = {
                              session.trailFilePath?.let { clipboardManager.setText(AnnotatedString(it)) }
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

                        Spacer(modifier = Modifier.width(4.dp))
                      }

                      // Device info after metadata
                      session.trailblazeDeviceInfo?.let { trailblazeDeviceInfo ->
                        trailblazeDeviceInfo.classifiers.forEach { classifier: TrailblazeDeviceClassifier ->
                          val imageForClassifier = when (classifier.classifier) {
                            TrailblazeDevicePlatform.ANDROID.name.lowercase() -> Android
                            TrailblazeDevicePlatform.IOS.name.lowercase() -> Apple
                            TrailblazeDevicePlatform.WEB.name.lowercase() -> BrowserChrome
                            else -> null
                          }
                          AssistChip(
                            onClick = { },
                            colors = getDeviceChipColors(),
                            label = {
                              imageForClassifier?.let {
                                Icon(
                                  imageVector = imageForClassifier,
                                  contentDescription = "Device Platform",
                                  modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                              }
                              Text(
                                text = classifier.classifier.lowercase(),
                                style = MaterialTheme.typography.labelSmall
                              )
                            },
                            modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                          )
                        }
                      }
                    }
                  }

                  // Status and menu on the right
                  Row(
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    StatusBadge(
                      status = session.latestStatus
                    )

                    // Only show menu if at least one action is available
                    if (deleteSession != null || openLogsFolder != null) {
                      var sessionListItemDropdownShowing by remember { mutableStateOf(false) }
                      Box {
                        IconButton(onClick = { sessionListItemDropdownShowing = true }) {
                          Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More Options"
                          )
                        }
                        DropdownMenu(
                          expanded = sessionListItemDropdownShowing,
                          onDismissRequest = { sessionListItemDropdownShowing = false },
                        ) {
                          DropdownMenuItem(
                            leadingIcon = {
                              Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open Session"
                              )
                            },
                            text = { Text("Open Session") },
                            onClick = {
                              sessionClicked(session)
                              sessionListItemDropdownShowing = false
                            }
                          )
                          if (openLogsFolder != null) {
                            DropdownMenuItem(
                              leadingIcon = {
                                Icon(
                                  Icons.Default.Folder, contentDescription = "Open Logs Folder"
                                )
                              },
                              text = { Text("Open Logs Folder") },
                              onClick = {
                                openLogsFolder.invoke(session)
                                sessionListItemDropdownShowing = false
                              }
                            )
                          }
                          if (deleteSession != null) {
                            DropdownMenuItem(
                              leadingIcon = {
                                Icon(
                                  Icons.Default.Delete, contentDescription = "Delete Session"
                                )
                              },
                              text = { Text("Delete Session") },
                              onClick = {
                                deleteSession.invoke(session)
                                sessionListItemDropdownShowing = false
                              }
                            )
                          }
                          if (onExportSession != null) {
                            DropdownMenuItem(
                              leadingIcon = {
                                Icon(
                                  Icons.Default.Save, contentDescription = "Export Session"
                                )
                              },
                              text = { Text("Export Session") },
                              onClick = {
                                onExportSession.invoke(session)
                                sessionListItemDropdownShowing = false
                              }
                            )
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
      }
    }

    // Show test results in a modal overlay when requested
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
            Text(
              "Test Results",
              style = MaterialTheme.typography.headlineMedium
            )
            IconButton(onClick = { showTestResults = false }) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close"
              )
            }
          }
          Spacer(modifier = Modifier.height(16.dp))
          testResultsSummaryView()
        }
      }
    }
  }
}
