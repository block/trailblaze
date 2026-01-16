package xyz.block.trailblaze.ui.composables

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Chip component for displaying priority levels with color-coded styling.
 * Used by both TrailsBrowser and TestRail views for consistent UX.
 *
 * @param priorityShortName The priority identifier (e.g., "P0", "P1", "P2", "P3", "P4", "Smoke")
 */
@Composable
fun PriorityChip(priorityShortName: String) {
  val chipColors = when (priorityShortName) {
    "P0" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.errorContainer,
      labelColor = MaterialTheme.colorScheme.onErrorContainer
    )

    "P1" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      labelColor = MaterialTheme.colorScheme.onTertiaryContainer
    )

    "P2" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      labelColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

    "P3" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      labelColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    "P4" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    "Smoke" -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      labelColor = MaterialTheme.colorScheme.onTertiaryContainer
    )

    else -> AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }

  AssistChip(
    onClick = { },
    label = {
      Text(
        text = priorityShortName,
        style = MaterialTheme.typography.labelSmall
      )
    },
    colors = chipColors
  )
}
