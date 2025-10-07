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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.StatusBadge
import xyz.block.trailblaze.ui.composables.getIcon
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.icons.BrowserChrome

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

@Composable
fun SessionListComposable(
  sessions: List<SessionInfo>,
  sessionClicked: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)?,
  clearAllLogs: (() -> Unit)?,
) {
  // Filter states
  var selectedPriorities by remember { mutableStateOf(setOf<String>()) }
  var selectedStatuses by remember { mutableStateOf(setOf<String>()) }
  var selectedPlatforms by remember { mutableStateOf(setOf<String>()) }
  var selectedClassifiers by remember { mutableStateOf(setOf<String>()) }
  var showFilters by remember { mutableStateOf(false) }
  var searchKeyword by remember { mutableStateOf("") }

  // Collect all unique values for filters
  val allPriorities = sessions.mapNotNull { it.trailConfig?.priority }.distinct().sorted()
  val allStatuses = listOf("In Progress", "Succeeded", "Failed")
  val allPlatforms =
    sessions.mapNotNull { it.trailblazeDeviceInfo?.platform?.name }.distinct().sorted()
  val allClassifiers =
    sessions.flatMap { it.trailblazeDeviceInfo?.classifiers ?: emptyList() }.distinct().sorted()

  // Filter sessions
  val filteredSessions = sessions.filter { session ->
    val priorityMatch = selectedPriorities.isEmpty() ||
      session.trailConfig?.priority?.let { it in selectedPriorities } == true ||
      (session.trailConfig?.priority == null && selectedPriorities.isEmpty())

    val statusMatch = selectedStatuses.isEmpty() || when {
      session.latestStatus is SessionStatus.Started && "In Progress" in selectedStatuses -> true
      session.latestStatus is SessionStatus.Ended.Succeeded && "Succeeded" in selectedStatuses -> true
      session.latestStatus is SessionStatus.Ended.Failed && "Failed" in selectedStatuses -> true
      else -> false
    }

    val platformMatch = selectedPlatforms.isEmpty() ||
      session.trailblazeDeviceInfo?.platform?.name?.let { it in selectedPlatforms } == true

    val classifierMatch = selectedClassifiers.isEmpty() ||
      session.trailblazeDeviceInfo?.classifiers?.any { it in selectedClassifiers } == true

    // Keyword search - match against display name and description (case-insensitive)
    val keywordMatch = searchKeyword.isEmpty() ||
      session.displayName.contains(searchKeyword, ignoreCase = true) ||
      (session.trailConfig?.description?.contains(searchKeyword, ignoreCase = true) == true) ||
      (session.trailConfig?.id?.contains(searchKeyword, ignoreCase = true) == true) ||
      (session.testClass?.contains(searchKeyword, ignoreCase = true) == true) ||
      (session.testName?.contains(searchKeyword, ignoreCase = true) == true)

    priorityMatch && statusMatch && platformMatch && classifierMatch && keywordMatch
  }

  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SelectableText(
        "List of Trailblaze Sessions",
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
              text = { Text("Clear All Logs") },
              enabled = clearAllLogs != null,
              onClick = {
                clearAllLogs?.invoke()
                expanded = false
              }
            )
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
                label = { Text(status) },
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
              allClassifiers.forEach { classifier ->
                FilterChip(
                  onClick = {
                    selectedClassifiers = if (classifier in selectedClassifiers) {
                      selectedClassifiers - classifier
                    } else {
                      selectedClassifiers + classifier
                    }
                  },
                  label = { Text(classifier) },
                  selected = classifier in selectedClassifiers,
                  modifier = Modifier.padding(end = 4.dp)
                )
              }
            }
          }

          // Clear filters button
          if (selectedPriorities.isNotEmpty() || selectedStatuses.isNotEmpty() || selectedPlatforms.isNotEmpty() || selectedClassifiers.isNotEmpty() || searchKeyword.isNotEmpty()) {
            OutlinedButton(
              onClick = {
                selectedPriorities = emptySet()
                selectedStatuses = emptySet()
                selectedPlatforms = emptySet()
                selectedClassifiers = emptySet()
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

                    // Device info after metadata
                    session.trailblazeDeviceInfo?.let { trailblazeDeviceInfo ->
                      AssistChip(
                        onClick = { },
                        colors = getDeviceChipColors(),
                        label = {
                          Icon(
                            imageVector = when (trailblazeDeviceInfo.platform) {
                              TrailblazeDevicePlatform.ANDROID -> Android
                              TrailblazeDevicePlatform.IOS -> Apple
                              TrailblazeDevicePlatform.WEB -> BrowserChrome
                            },
                            contentDescription = "Device Platform",
                            modifier = Modifier.size(12.dp)
                          )
                          Spacer(modifier = Modifier.width(4.dp))
                          Text(
                            text = trailblazeDeviceInfo.platform.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall
                          )
                        },
                        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                      )

                      trailblazeDeviceInfo.classifiers.forEach { classifier ->
                        AssistChip(
                          onClick = { },
                          colors = getDeviceChipColors(),
                          label = {
                            Text(
                              text = classifier.lowercase(),
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
                            Icons.Default.Delete, contentDescription = "Delete Session"
                          )
                        },
                        text = { Text("Delete Session") },
                        enabled = deleteSession != null,
                        onClick = {
                          deleteSession?.invoke(session)
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
