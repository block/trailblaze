package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import xyz.block.trailblaze.ui.composables.PriorityChip
import xyz.block.trailblaze.ui.composables.RecordingDisplayInfo
import xyz.block.trailblaze.ui.composables.TestItemCard

/**
 * A card displaying a trail with its variants.
 * Uses the shared TestItemCard for consistent UX with TestRail views.
 *
 * @param trail The trail to display
 * @param isSelected Whether this trail is currently selected
 * @param onClick Callback when the card is clicked
 * @param modifier Modifier for the card
 */
@Composable
fun TrailCard(
  trail: Trail,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Convert trail variants to RecordingDisplayInfo for the shared card
  val recordings = remember(trail.variants) {
    trail.variants
      .filter { !it.isDefault } // Exclude default variant
      .map { variant ->
        val platform = variant.classifiers.firstOrNull()
        val classifiers = variant.classifiers.drop(1)
        RecordingDisplayInfo(
          platform = platform,
          classifiers = classifiers,
          sourceType = variant.config?.source?.type
        )
      }
  }

  // Check for conflicts between trail variants' natural language steps
  val hasStepConflict = remember(trail.variants) {
    trail.hasStepConflict
  }

  TestItemCard(
    title = trail.title,
    id = trail.id,
    recordings = recordings,
    isSelected = isSelected,
    onClick = onClick,
    hasStepConflict = hasStepConflict,
    trailingContent = trail.priority?.let { priority ->
      { PriorityChip(priorityShortName = priority) }
    },
    modifier = modifier,
  )
}
