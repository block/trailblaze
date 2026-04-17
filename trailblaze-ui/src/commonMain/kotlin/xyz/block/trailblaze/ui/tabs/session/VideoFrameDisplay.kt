package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.composables.ScreenshotAnnotation

/**
 * Displays a video frame bitmap with an optional action overlay (tap point, swipe, etc.).
 *
 * Handles: frame display, loading placeholder, and aspect-ratio-correct action
 * annotation positioning. Used by both SessionTimelineView and SessionCombinedView.
 */
@Composable
internal fun VideoFrameWithOverlay(
  currentFrame: ImageBitmap?,
  activeDriverLog: TrailblazeLog.AgentDriverLog?,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    if (currentFrame != null) {
      Image(
        bitmap = currentFrame,
        contentDescription = "Video frame",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    } else {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(Color.Black, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Loading frame...",
          color = Color.White.copy(alpha = 0.5f),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    // Action overlay positioned to match the video frame aspect ratio
    if (activeDriverLog != null) {
      ActionOverlay(
        action = activeDriverLog.action,
        deviceWidth = activeDriverLog.deviceWidth,
        deviceHeight = activeDriverLog.deviceHeight,
        containerWidth = maxWidth,
        containerHeight = maxHeight,
      )
    }
  }
}

/**
 * Draws a [ScreenshotAnnotation] overlay positioned correctly within a container, accounting for
 * the aspect ratio difference between the device screen and the container.
 */
@Composable
internal fun ActionOverlay(
  action: AgentDriverAction?,
  deviceWidth: Int,
  deviceHeight: Int,
  containerWidth: Dp,
  containerHeight: Dp,
) {
  if (action == null) return
  val dw = deviceWidth.toFloat()
  val dh = deviceHeight.toFloat()
  val videoAspect = if (dh > 0) dw / dh else 1f
  val containerAspect = containerWidth / containerHeight
  val renderedWidth =
    if (videoAspect > containerAspect) containerWidth else containerHeight * videoAspect
  val renderedHeight =
    if (videoAspect > containerAspect) containerWidth / videoAspect else containerHeight
  val offsetX = (containerWidth - renderedWidth) / 2
  val offsetY = (containerHeight - renderedHeight) / 2

  Box(
    modifier =
      Modifier.size(renderedWidth, renderedHeight)
        .offset(x = offsetX, y = offsetY),
  ) {
    val clickCoords = action as? HasClickCoordinates
    if (clickCoords != null && dw > 0 && dh > 0) {
      val xRatio = clickCoords.x.coerceAtLeast(0).toFloat() / dw
      val yRatio = clickCoords.y.coerceAtLeast(0).toFloat() / dh
      ScreenshotAnnotation(
        centerX = renderedWidth * xRatio,
        centerY = renderedHeight * yRatio,
        maxWidth = renderedWidth,
        maxHeight = renderedHeight,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        action = action,
      )
    } else if (action is AgentDriverAction.Swipe) {
      ScreenshotAnnotation(
        centerX = renderedWidth / 2,
        centerY = renderedHeight / 2,
        maxWidth = renderedWidth,
        maxHeight = renderedHeight,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        action = action,
      )
    }
  }
}

/**
 * Compute the rendered dimensions for an image fitted into a container, preserving aspect ratio.
 * Returns (renderedWidth, renderedHeight).
 */
internal fun computeFitDimensions(
  imageAspect: Float,
  containerWidth: Dp,
  containerHeight: Dp,
): Pair<Dp, Dp> {
  val containerAspect = containerWidth / containerHeight
  val renderedWidth =
    if (imageAspect > containerAspect) containerWidth else containerHeight * imageAspect
  val renderedHeight =
    if (imageAspect > containerAspect) containerWidth / imageAspect else containerHeight
  return renderedWidth to renderedHeight
}
