package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.yaml.TrailSourceType

/**
 * Common data class representing a recording's platform and classifier information.
 * Used to share UI components between TrailCard and TestRailCard.
 */
data class RecordingDisplayInfo(
  /**
   * The platform classifier (e.g., "android", "ios")
   */
  val platform: TrailblazeDeviceClassifier?,

  /**
   * Additional device classifiers (e.g., ["phone"], ["iphone", "26", "portrait"])
   */
  val classifiers: List<TrailblazeDeviceClassifier> = emptyList(),

  /**
   * The source type of the recording (handwritten vs generated).
   */
  val sourceType: TrailSourceType? = null,
)

/**
 * Displays recording chips grouped by platform in vertically-aligned columns.
 * Each platform always appears in the same horizontal position for easy scanning.
 * Shows chips like "Android: Phone, Tablet" and "iOS: iPhone, iPad"
 *
 * @param recordings List of recordings to display
 * @param platformOrder Optional list of platforms to display in order. If provided, chips are
 *   displayed in fixed columns matching this order for vertical alignment across rows.
 *   If null, falls back to flowing layout sorted by platform priority.
 * @param modifier Modifier for the container
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformRecordingsChips(
  recordings: List<RecordingDisplayInfo>,
  platformOrder: List<String>? = null,
  modifier: Modifier = Modifier,
) {
  if (recordings.isEmpty()) return

  // Group recordings by platform
  val groupedByPlatform = recordings.groupBy { it.platform?.classifier?.lowercase() ?: "other" }

  if (platformOrder != null) {
    // Fixed-column layout for vertical alignment
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = modifier
    ) {
      platformOrder.forEach { platformKey ->
        val platformRecordings = groupedByPlatform[platformKey]
        if (platformRecordings != null) {
          PlatformGroupChip(
            platformKey = platformKey,
            recordings = platformRecordings,
          )
        }
        // Note: We don't add empty placeholders - chips just appear in their column position
        // when present, creating natural vertical alignment
      }
      // Show any "other" platforms not in the standard order
      groupedByPlatform.entries
        .filter { (key, _) -> key !in platformOrder }
        .forEach { (platformKey, platformRecordings) ->
          PlatformGroupChip(
            platformKey = platformKey,
            recordings = platformRecordings,
          )
        }
    }
  } else {
    // Flowing layout (original behavior) - sorted by platform priority
    val sortedPlatforms = groupedByPlatform.entries
      .sortedBy { (platformKey, _) ->
        when (platformKey) {
          "android" -> "1_android"
          "ios" -> "2_ios"
          "web" -> "3_web"
          else -> "4_$platformKey"
        }
      }

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      modifier = modifier
    ) {
      sortedPlatforms.forEach { (platformKey, platformRecordings) ->
        PlatformGroupChip(
          platformKey = platformKey,
          recordings = platformRecordings,
        )
      }
    }
  }
}

/**
 * A chip displaying a platform group with its device types.
 * Shows the platform icon and name, followed by the device types within that platform.
 * e.g., "Android: Phone, Tablet"
 *
 * Platform icons provide visual distinction between different platforms.
 */
@Composable
fun PlatformGroupChip(
  platformKey: String,
  recordings: List<RecordingDisplayInfo>,
  modifier: Modifier = Modifier,
) {
  // Get the platform icon painter
  val deviceClassifierIconProvider = LocalDeviceClassifierIcons.current
  val platform = TrailblazeDevicePlatform.entries.find {
    it.name.equals(platformKey, ignoreCase = true)
  }
  val platformIconPainter: Painter = platform?.let { rememberVectorPainter(it.getIcon()) }
    ?: deviceClassifierIconProvider.getPainter(TrailblazeDeviceClassifier(platformKey))
    ?: rememberVectorPainter(Icons.Filled.Description)

  // Use neutral theme colors - icons provide platform distinction
  val containerColor = MaterialTheme.colorScheme.surfaceVariant
  val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

  // Get unique device types (classifiers)
  val deviceTypes = recordings.flatMap { recording ->
    recording.classifiers.map { classifier ->
      DeviceTypeDisplayInfo(
        label = classifier.classifier.classifierDisplayName(),
        sourceType = recording.sourceType
      )
    }
  }.distinctBy { it.label }

  AssistChip(
    onClick = { },
    modifier = modifier,
    colors = AssistChipDefaults.assistChipColors(
      containerColor = containerColor,
      labelColor = contentColor,
      leadingIconContentColor = contentColor
    ),
    leadingIcon = {
      Image(
        painter = platformIconPainter,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        colorFilter = ColorFilter.tint(contentColor)
      )
    },
    label = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        // Platform name
        Text(
          text = platformKey.classifierDisplayName(),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Medium
        )

        // Device types if any
        if (deviceTypes.isNotEmpty()) {
          Text(
            text = ":",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.6f)
          )
          deviceTypes.forEachIndexed { index, deviceType ->
            Text(
              text = deviceType.label,
              style = MaterialTheme.typography.labelSmall,
              color = contentColor.copy(alpha = 0.85f)
            )
            // Show source type icon for this device type
            deviceType.sourceType?.getIcon()?.let { sourceIcon ->
              Icon(
                imageVector = sourceIcon,
                contentDescription = deviceType.sourceType.name,
                modifier = Modifier.size(12.dp),
                tint = contentColor.copy(alpha = 0.7f)
              )
            }
            // Separator between device types (but not after the last one)
            if (index < deviceTypes.size - 1) {
              Text(
                text = ",",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f)
              )
            }
          }
        } else {
          // Just show source type icons if no device types
          recordings.firstOrNull()?.sourceType?.getIcon()?.let { sourceIcon ->
            Icon(
              imageVector = sourceIcon,
              contentDescription = null,
              modifier = Modifier.size(12.dp),
              tint = contentColor.copy(alpha = 0.7f)
            )
          }
        }
      }
    }
  )
}

/**
 * Helper data class for device type display info.
 */
private data class DeviceTypeDisplayInfo(
  val label: String,
  val sourceType: TrailSourceType?,
)

/**
 * Converts a classifier string to display name.
 */
fun String.classifierDisplayName(): String = this.lowercase()
