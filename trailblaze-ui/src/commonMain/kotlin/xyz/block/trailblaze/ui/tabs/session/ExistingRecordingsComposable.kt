package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.getIcon
import xyz.block.trailblaze.ui.getPlatform
import xyz.block.trailblaze.ui.recordings.ExistingTrail

/**
 * Displays a list of existing trail recordings grouped by subdirectory.
 *
 * Shows recordings organized by their subdirectory (e.g., "handwritten", "generated"),
 * with the primary directory labeled and shadowed trails indicated.
 *
 * @param existingRecordings List of existing trail files to display
 * @param onRevealRecordingInFinder Optional callback to reveal a recording file in the system file explorer
 * @param modifier Modifier for the container
 */
@Composable
fun ExistingRecordingsSection(
  existingRecordings: List<ExistingTrail>,
  onRevealRecordingInFinder: ((String) -> Unit)?,
  modifier: Modifier = Modifier
) {
  if (existingRecordings.isEmpty()) return

  Box(
    modifier = modifier
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

      // Group recordings by subdirectory (preserving order from search)
      val groupedRecordings = existingRecordings.groupBy { it.subdirectory }
      val subdirectoryOrder = existingRecordings.mapNotNull { it.subdirectory }.distinct()

      // Show recordings at root first (if any), then by subdirectory order
      val orderedGroups = buildList {
        groupedRecordings[null]?.let { add(null to it) }
        subdirectoryOrder.forEach { subdir ->
          groupedRecordings[subdir]?.let { add(subdir to it) }
        }
      }

      orderedGroups.forEachIndexed { groupIndex, (subdirectory, trails) ->
        // Show subdirectory header if there are multiple groups or if subdirectory is set
        if (orderedGroups.size > 1 || subdirectory != null) {
          if (groupIndex > 0) {
            Spacer(modifier = Modifier.height(12.dp))
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
          ) {
            Text(
              text = "📂 ${subdirectory ?: "root"}",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            if (groupIndex == 0 && subdirectory != null) {
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "(primary)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }
        }

        trails.forEach { trail ->
          ExistingRecordingItem(
            trail = trail,
            onRevealInFinder = onRevealRecordingInFinder
          )
        }
      }
    }
  }
}

/**
 * Displays a single existing trail recording item.
 *
 * Shows the platform icon, filename/classifier, and optional "Show on Disk" button.
 * Shadowed trails (overridden by higher-priority versions) are displayed with reduced opacity
 * and an indicator message.
 *
 * @param trail The trail file to display
 * @param onRevealInFinder Optional callback to reveal the file in the system file explorer
 */
@Composable
private fun ExistingRecordingItem(
  trail: ExistingTrail,
  onRevealInFinder: ((String) -> Unit)?
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      val devicePlatform = TrailblazeDevicePlatform.entries.firstOrNull {
        it.name.equals(trail.platform, ignoreCase = true)
      }

      // Platform indicator icon
      when (devicePlatform) {
        TrailblazeDevicePlatform.IOS -> Icon(
          imageVector = devicePlatform.getIcon(),
          contentDescription = devicePlatform.displayName,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer
        )

        TrailblazeDevicePlatform.ANDROID -> Icon(
          imageVector = devicePlatform.getIcon(),
          contentDescription = devicePlatform.displayName,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer
        )

        else -> Icon(
          imageVector = if (trail.isDefaultTrailFile) Icons.Default.Description else Icons.Default.InsertDriveFile,
          contentDescription = if (trail.isDefaultTrailFile) "Prompts" else "File",
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
      }
      Spacer(modifier = Modifier.width(8.dp))

      // Filename with classifier info
      val displayName = if (trail.isDefaultTrailFile) {
        trail.fileName
      } else {
        val classifier = trail.classifier
        if (classifier != null) {
          "${trail.platform ?: "unknown"}-$classifier"
        } else {
          trail.platform ?: trail.fileName
        }
      }
      Column {
        SelectableText(
          text = displayName,
          style = MaterialTheme.typography.bodySmall,
          color = if (trail.isShadowed) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
          } else {
            MaterialTheme.colorScheme.onSecondaryContainer
          },
        )
        if (trail.isShadowed) {
          Text(
            text = "Overridden by ${trail.shadowedBy ?: "higher priority"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
          )
        }
      }
    }

    // Show "Show on Disk" button only on JVM platform
    if (getPlatform() == Platform.JVM && onRevealInFinder != null) {
      androidx.compose.material3.TextButton(
        onClick = { onRevealInFinder.invoke(trail.absolutePath) },
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
