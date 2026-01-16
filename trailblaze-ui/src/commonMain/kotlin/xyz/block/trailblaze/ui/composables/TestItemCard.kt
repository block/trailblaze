package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import xyz.block.trailblaze.ui.icons.BootstrapRecordCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared card component for displaying test items (trails or test cases).
 * Used by both TrailsBrowser and TestRail views for consistent UX.
 *
 * @param title The title of the test item (optional - if null, id becomes primary)
 * @param id The unique identifier / path of the test item
 * @param recordings List of recording display info for showing platform chips
 * @param isSelected Whether this card is currently selected (for visual highlight)
 * @param onClick Callback when the card is clicked
 * @param hasStepConflict Whether the recorded trails have conflicting natural language steps
 * @param leadingContent Optional composable content shown before the title
 * @param trailingContent Optional composable content shown after the title (e.g., priority chip)
 * @param modifier Modifier for the card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestItemCard(
  title: String?,
  id: String,
  recordings: List<RecordingDisplayInfo>,
  isSelected: Boolean = false,
  onClick: () -> Unit,
  hasStepConflict: Boolean = false,
  leadingContent: @Composable (() -> Unit)? = null,
  trailingContent: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Card(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surface
      }
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (isSelected) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outlineVariant
      }
    ),
    elevation = CardDefaults.cardElevation(
      defaultElevation = if (isSelected) 4.dp else 1.dp
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // Optional leading content (e.g., priority chip)
        leadingContent?.invoke()

        Column(Modifier.weight(1f)) {
          // Title (if available)
          if (title != null) {
            Text(
              text = title,
              style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold
              ),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
          }

          // ID / Path with folder icon
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Filled.Folder,
              contentDescription = null,
              modifier = Modifier.size(if (title != null) 14.dp else 18.dp),
              tint = if (title != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
              } else {
                MaterialTheme.colorScheme.primary
              }
            )
            Spacer(modifier = Modifier.width(if (title != null) 4.dp else 8.dp))
            Text(
              text = id,
              style = if (title != null) {
                MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace
                )
              } else {
                MaterialTheme.typography.bodyMedium.copy(
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Medium
                )
              },
              color = if (title != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
              } else {
                MaterialTheme.colorScheme.onSurface
              },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        // Optional trailing content (e.g., priority chip)
        trailingContent?.invoke()
      }

      // Recorded trails section - grouped in a subtle container
      if (recordings.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
          modifier = Modifier
            .background(
              color = if (hasStepConflict) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
              },
              shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            // Recording icon to indicate these are recorded trails
            Icon(
              imageVector = BootstrapRecordCircle,
              contentDescription = "Recorded trails",
              modifier = Modifier.size(14.dp),
              tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
            PlatformRecordingsChips(
              recordings = recordings,
            )

            // Conflict indicator
            if (hasStepConflict) {
              Spacer(modifier = Modifier.width(4.dp))
              TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                  PlainTooltip {
                    Text("Recorded trails have different steps - click to resolve")
                  }
                },
                state = rememberTooltipState()
              ) {
                Box(
                  modifier = Modifier
                    .background(
                      color = MaterialTheme.colorScheme.error,
                      shape = CircleShape
                    )
                    .padding(4.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Step conflict between recordings",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onError
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
