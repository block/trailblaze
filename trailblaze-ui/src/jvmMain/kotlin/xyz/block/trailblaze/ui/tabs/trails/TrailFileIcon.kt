package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.recordings.ExistingTrail

/**
 * Icon for a trail file with optional badges.
 *
 * @param existingTrail The trail file information
 * @param modifier Modifier for the icon
 */
@Composable
fun TrailFileIcon(
  existingTrail: ExistingTrail,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    // Base icon
    Icon(
      imageVector = Icons.Filled.Description,
      contentDescription = "Trail file",
      tint = when {
        existingTrail.isDefaultTrailFile -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
      },
      modifier = Modifier.size(20.dp)
    )
  }
}
