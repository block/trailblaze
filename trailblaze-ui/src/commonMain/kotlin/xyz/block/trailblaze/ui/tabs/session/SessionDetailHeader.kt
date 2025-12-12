package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.getPlatform

@Composable
internal fun SessionDetailHeader(
  onBackClick: () -> Unit,
  viewMode: SessionViewMode,
  onViewModeChanged: (SessionViewMode) -> Unit,
  alwaysAtBottom: Boolean,
  onAlwaysAtBottomChanged: (Boolean) -> Unit,
  isSessionInProgress: Boolean,
  isCancelling: Boolean,
  onCancelSession: () -> Unit,
  onOpenLogsFolder: () -> Unit,
  onExportSession: () -> Unit,
  onDeleteSession: () -> Unit,
  // Zoom and font controls
  zoomOffset: Int,
  onZoomOffsetChanged: (Int) -> Unit,
  fontSizeScale: Float,
  onFontScaleChanged: (Float) -> Unit,
  cardsPerRow: Int,
  maxCards: Int,
) {
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
              onCheckedChange = onAlwaysAtBottomChanged
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
          onViewModeChanged(SessionViewMode.List)
        }) {
          Text(
            text = "List",
            color = if (viewMode == SessionViewMode.List) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          )
        }
        TextButton(onClick = {
          onViewModeChanged(SessionViewMode.Grid)
        }) {
          Text(
            text = "Grid",
            color = if (viewMode == SessionViewMode.Grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          )
        }
        TextButton(onClick = {
          onViewModeChanged(SessionViewMode.LlmUsage)
        }) {
          Text(
            text = "LLM Usage",
            color = if (viewMode == SessionViewMode.LlmUsage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          )
        }
        TextButton(onClick = {
          onViewModeChanged(SessionViewMode.Recording)
        }) {
          Text(
            text = "Recording",
            color = if (viewMode == SessionViewMode.Recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          )
        }
      }
      // Cancel Session button for active sessions
      if (isSessionInProgress) {
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
          AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Session?") },
            text = {
              Text(
                "Are you sure you want to delete this session? This action cannot be undone."
              )
            },
            confirmButton = {
              TextButton(
                onClick = {
                  showDeleteConfirmation = false
                  onDeleteSession()
                }
              ) {
                Text("Delete")
              }
            },
            dismissButton = {
              TextButton(
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
              onZoomOffsetChanged(zoomOffset + 1)
            },
            enabled = cardsPerRow < maxCards
          ) {
            Icon(
              Icons.Outlined.ZoomOut, contentDescription = "Smaller cards (more per row)"
            )
          }
          IconButton(
            onClick = {
              onZoomOffsetChanged(zoomOffset - 1)
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
              onFontScaleChanged((fontSizeScale - 0.1f).coerceAtLeast(0.5f))
            },
            enabled = fontSizeScale > 0.5f
          ) {
            Icon(Icons.Outlined.TextDecrease, contentDescription = "Decrease font size")
          }
          IconButton(
            onClick = {
              onFontScaleChanged((fontSizeScale + 0.1f).coerceAtMost(2f))
            },
            enabled = fontSizeScale < 2f
          ) {
            Icon(Icons.Outlined.TextIncrease, contentDescription = "Increase font size")
          }
        }
      }
    }
  }
}
