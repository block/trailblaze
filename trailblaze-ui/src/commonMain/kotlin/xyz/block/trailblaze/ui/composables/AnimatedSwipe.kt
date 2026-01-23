package xyz.block.trailblaze.ui.composables

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated swipe annotation that shows an arrow moving from start to end
 */
@Composable
fun AnimatedSwipeAnnotation(
  startX: Int,
  startY: Int,
  endX: Int,
  endY: Int,
  deviceWidth: Int,
  deviceHeight: Int,
  maxWidth: Dp,
  maxHeight: Dp,
  durationMs: Long,
  direction: String,
  isHovered: Boolean,
) {
  // Convert device coordinates to display coordinates using the same ratio logic as click coordinates
  val startXRatio = startX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
  val startYRatio = startY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
  val endXRatio = endX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
  val endYRatio = endY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()

  // Calculate display positions
  val startXDp = maxWidth * startXRatio
  val startYDp = maxHeight * startYRatio
  val endXDp = maxWidth * endXRatio
  val endYDp = maxHeight * endYRatio

  val arrowSize = 28.dp
  val backgroundSize = 40.dp

  // Determine arrow icon based on direction
  val arrowIcon = swipeDirectionIcon(direction)

  val progress by animateFloatAsState(
    targetValue = if (isHovered) 1f else 0f,
    animationSpec = if (isHovered) {
      tween(
        durationMillis = durationMs.toInt(),
        easing = LinearEasing
      )
    } else {
      // Snap instantly when mouse leaves
      tween(durationMillis = 0)
    },
    label = "swipe_progress"
  )

  // Calculate current position along the path
  val currentX = startXDp + (endXDp - startXDp) * progress
  val currentY = startYDp + (endYDp - startYDp) * progress

  Box(
    modifier = Modifier
      .size(backgroundSize)
      .offset(
        x = currentX - (backgroundSize / 2),
        y = currentY - (backgroundSize / 2)
      )
      .border(
        2.dp, Color.Blue.copy(alpha = 0.6f), shape = CircleShape
      )
  ) {
    Icon(
      imageVector = arrowIcon,
      contentDescription = "Swipe $direction",
      modifier = Modifier
        .size(arrowSize)
        .align(Alignment.Center),
      tint = Color.Blue.copy(alpha = 0.7f)
    )
  }
}