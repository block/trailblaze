package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * A badge that displays a count in the bottom-right corner of content
 */
@Composable
fun CountBadge(
  count: Int,
  modifier: Modifier = Modifier,
) {
  if (count > 0) {
    Box(
      modifier = modifier
        .size(16.dp)
        .background(MaterialTheme.colorScheme.error, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = if (count > 9) "9+" else count.toString(),
        color = MaterialTheme.colorScheme.onError,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall.copy(
          lineHeight = 11.sp,
          lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
          )
        ),
        modifier = Modifier.offset(
          y = (-0.5).dp
        ) // Move text up slightly for better visual centering
      )
    }
  }
}

/**
 * A badge that displays session status as a small icon in a circle
 */
@Composable
fun SessionStatusIconBadge(
  status: SessionStatus,
  modifier: Modifier = Modifier,
) {
  val (icon, color) = when (status) {
    is SessionStatus.Started -> Icons.Filled.PlayArrow to Color(
      0xFF2196F3
    ) // Blue play arrow for in-progress
    is SessionStatus.Ended.Succeeded,
    is SessionStatus.Ended.SucceededWithFallback -> Icons.Filled.Check to Color(
      0xFF4CAF50
    ) // Green
    is SessionStatus.Ended.Failed,
    is SessionStatus.Ended.FailedWithFallback,
    is SessionStatus.Ended.TimeoutReached -> Icons.Filled.Close to MaterialTheme.colorScheme.error

    is SessionStatus.Ended.Cancelled -> Icons.Filled.Warning to Color(0xFFFFA726) // Orange
    is SessionStatus.Unknown -> return // Don't show badge for unknown status
  }

  Box(
    modifier = modifier
      .size(16.dp)
      .background(color, CircleShape)
      .padding(2.dp),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(12.dp)
    )
  }
}

/**
 * Wraps content with optional count and status badges
 */
@Composable
fun IconWithBadges(
  icon: @Composable () -> Unit,
  inProgressCount: Int = 0,
  firstSessionStatus: SessionStatus? = null,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    // Main icon
    icon()

    // Status badge in top-right (if status is provided and not unknown)
    if (firstSessionStatus != null && firstSessionStatus !is SessionStatus.Unknown) {
      SessionStatusIconBadge(
        status = firstSessionStatus,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = 6.dp, y = (-6).dp)
      )
    }

    // Count badge in bottom-right (if count > 0)
    if (inProgressCount > 0) {
      CountBadge(
        count = inProgressCount,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .offset(x = 6.dp, y = 6.dp)
      )
    }
  }
}
