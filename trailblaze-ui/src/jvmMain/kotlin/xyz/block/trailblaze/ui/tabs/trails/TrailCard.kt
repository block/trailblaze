package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.ui.composables.PriorityChip
import xyz.block.trailblaze.ui.composables.RecordingDisplayInfo
import xyz.block.trailblaze.ui.composables.TestItemCard

/**
 * A card displaying a trail with its variants.
 * Uses the shared TestItemCard for consistent UX with test-case views.
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
      .flatMap { variant ->
        val sourceType = variant.config?.source?.type
        if (variant.isUnified) {
          // A unified single-file trail carries every device in one file, so its filename encodes
          // no classifier. Build one chip per declared classifier (recordings + config.devices),
          // splitting `<platform>-<device>...` into its segments so PlatformRecordingsChips groups
          // them by platform (e.g. "android", "android-tablet" → "Android: Tablet").
          //
          // Split on EVERY '-' (first segment = platform, the rest = classifiers), matching the
          // legacy filename split, so a multi-segment classifier like "ios-iphone-portrait" yields
          // platform "ios" + classifiers ["iphone", "portrait"] rather than collapsing the tail.
          // This is display-string splitting, NOT the platform enum fold in
          // UnifiedTrailTargets.platformFor: it keeps the raw prefix as a chip label so a
          // non-platform classifier (e.g. "kiosk-a") still renders, where platformFor drops it.
          variant.classifierNames.sorted().map { classifier ->
            val segments = classifier.split("-")
            RecordingDisplayInfo(
              platform = segments.firstOrNull()?.let { TrailblazeDeviceClassifier(it) },
              classifiers = segments.drop(1).map { TrailblazeDeviceClassifier(it) },
              sourceType = sourceType,
            )
          }
        } else {
          listOf(
            RecordingDisplayInfo(
              platform = variant.classifiers.firstOrNull(),
              classifiers = variant.classifiers.drop(1),
              sourceType = sourceType,
            ),
          )
        }
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
