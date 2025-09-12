package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.icons.BrowserChrome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.StatusBadge

@Composable
fun SessionListComposable(
  sessions: List<SessionInfo>,
  sessionClicked: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)?,
  clearAllLogs: (() -> Unit)?,
) {
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

    val groupedSessions: Map<LocalDate, List<SessionInfo>> = sessions.groupBy {
      it.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    LazyColumn(
      modifier = Modifier.padding(start = 8.dp, end = 8.dp),
    ) {
      groupedSessions.forEach { (date, sessionsForDay) ->
        item {
          Text(
            text = date.toString(), // Consider a more friendly format
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp)
          )
        }

        items(sessionsForDay) { session: SessionInfo ->
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
                verticalAlignment = Alignment.CenterVertically,
              ) {
                val time = session.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).time
                Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                  Row {

                    SelectableText(
                      text = "${time.hour}:${time.minute.toString().padStart(2, '0')} - ${session.displayName}",
                      modifier = Modifier.padding(8.dp),
                    )
                  }
                  Row(modifier = Modifier) {
                    session.trailblazeDeviceInfo?.let { trailblazeDeviceInfo ->
                      AssistChip(
                        onClick = { },
                        label = {
                          Icon(
                            imageVector = when (trailblazeDeviceInfo.platform) {
                              TrailblazeDevicePlatform.ANDROID -> Android
                              TrailblazeDevicePlatform.IOS -> Apple
                              TrailblazeDevicePlatform.WEB -> BrowserChrome
                            },
                            contentDescription = "Device Platform",
                          )
                          Spacer(modifier = Modifier.size(8.dp))
                          Text(
                            text = trailblazeDeviceInfo.platform.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall
                          )
                        },
                      )
                      Spacer(modifier = Modifier.padding(4.dp))

                      trailblazeDeviceInfo.classifiers.forEach { classifier ->
                        AssistChip(
                          onClick = { },
                          label = {
                            Text(
                              text = classifier.lowercase(),
                              style = MaterialTheme.typography.labelSmall
                            )
                          },
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                      }
//                      trailblazeDeviceInfo.orientation?.let { orientation ->
//                        AssistChip(
//                          onClick = { },
//                          label = {
//                            Text(
//                              text = orientation.name.lowercase(),
//                              style = MaterialTheme.typography.labelSmall
//                            )
//                          },
//                        )
//                        Spacer(modifier = Modifier.padding(4.dp))
//                      }
//
//                      trailblazeDeviceInfo.locale?.let { localeTag ->
//                        AssistChip(
//                          onClick = { },
//                          label = {
//                            Text(
//                              text = localeTag,
//                              style = MaterialTheme.typography.labelSmall
//                            )
//                          },
//                        )
//                        Spacer(modifier = Modifier.padding(4.dp))
//                      }
                    }
                  }
                }

                Box(
                  modifier = Modifier
                    .weight(1f, false)
                ) {
                  Row(
                    modifier = Modifier
                      .align(Alignment.CenterEnd)
                  ) {
                    StatusBadge(
                      modifier = Modifier
                        .weight(1f, false),
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
                          leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete Session") },
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
}
