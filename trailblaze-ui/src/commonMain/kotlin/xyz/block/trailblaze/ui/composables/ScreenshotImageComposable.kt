package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.images.rememberScreenshotLoadState
import xyz.block.trailblaze.ui.images.ScreenshotDiagnostics

/**
 * Shared composable for rendering screenshot annotations consistently
 */
@Composable
fun ScreenshotAnnotation(
  centerX: Dp,
  centerY: Dp,
  maxWidth: Dp,
  maxHeight: Dp,
  deviceWidth: Int,
  deviceHeight: Int,
  action: AgentDriverAction? = null,
  isHovered: Boolean = false,
) {
  if (action is AgentDriverAction.AssertCondition) {
    val checkSize = 20.dp
    val backgroundSize = 28.dp

    // Handle failed assertions
    if (!action.succeeded) {
      // Red border around the entire screen for failed assertions
      Box(
        modifier = Modifier
          .fillMaxSize()
          .border(
            4.dp, Color.Red.copy(alpha = 0.7f), shape = RoundedCornerShape(0.dp)
          )
      )

      // Text label for failed assertion - positioned in center
      if (action.textToDisplay != null) {
        Box(
          modifier = Modifier
            .fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Box(
            modifier = Modifier
              .border(2.dp, Color.Red.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp)
          ) {
            Text(
              text = "Expected \"${action.textToDisplay}\" not found",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = Color.Red.copy(alpha = 0.9f),
              textAlign = TextAlign.Center
            )
          }
        }
      }
    } else if (!action.isVisible && action.textToDisplay != null) {
      // For notVisible assertions (successful), show green border around entire screen
      // Green border around the entire screen
      Box(
        modifier = Modifier
          .fillMaxSize()
          .border(
            4.dp, Color.Green.copy(alpha = 0.7f), shape = RoundedCornerShape(0.dp)
          )
      )

      // Text label for not found item - positioned in center
      Box(
        modifier = Modifier
          .fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Box(
          modifier = Modifier
            .background(
              Color.Transparent, shape = RoundedCornerShape(6.dp)
            )
            .border(1.dp, Color.Green.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
          Text(
            text = "\"${action.textToDisplay}\" not found",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      // Visible assertions (successful) - show at click location with transparent circle background
      Box(
        modifier = Modifier
          .size(backgroundSize)
          .offset(
            x = centerX - (backgroundSize / 2),
            y = centerY - (backgroundSize / 2)
          )
          .border(
            2.dp, Color.Green.copy(alpha = 0.6f), shape = CircleShape
          )
      ) {
        // Transparent checkmark icon
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = "Assertion Success",
          modifier = Modifier
            .size(checkSize)
            .align(Alignment.Center),
          tint = Color.Green.copy(alpha = 0.7f)
        )
      }
    }
  } else if (action is AgentDriverAction.Swipe) {
    // Check if we have start/end coordinates for animated swipe
    if (action.startX != null && action.startY != null && action.endX != null && action.endY != null) {
      // Animated swipe visualization showing arrow moving from start to end
      AnimatedSwipeAnnotation(
        startX = action.startX!!,
        startY = action.startY!!,
        endX = action.endX!!,
        endY = action.endY!!,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        durationMs = action.durationMs,
        direction = action.direction,
        isHovered = isHovered
      )
    } else {
      // Fallback to centered static arrow if coordinates not available
      val arrowSize = 28.dp
      val backgroundSize = 40.dp

      // Determine arrow icon based on direction
      val arrowIcon = swipeDirectionIcon(direction = action.direction)

      Box(
        modifier = Modifier
          .size(backgroundSize)
          .offset(
            x = (maxWidth / 2) - (backgroundSize / 2),
            y = (maxHeight / 2) - (backgroundSize / 2)
          )
          .border(
            2.dp, Color.Blue.copy(alpha = 0.6f), shape = CircleShape
          )
      ) {
        Icon(
          imageVector = arrowIcon,
          contentDescription = "Swipe ${action.direction}",
          modifier = Modifier
            .size(arrowSize)
            .align(Alignment.Center),
          tint = Color.Blue.copy(alpha = 0.7f)
        )
      }
    }
  } else {
    // Transparent red crosshairs with circle background for other action types
    val crosshairSize = 16.dp
    val backgroundSize = 24.dp
    val crosshairThickness = 2.dp

    // Circle with transparent background and translucent border
    Box(
      modifier = Modifier
        .size(backgroundSize)
        .offset(
          x = centerX - (backgroundSize / 2),
          y = centerY - (backgroundSize / 2)
        )
        .border(
          2.dp, Color.Red.copy(alpha = 0.6f), shape = CircleShape
        ) // Translucent border only, no background fill
    ) {
      // Translucent crosshairs
      Box(
        modifier = Modifier
          .size(crosshairSize)
          .align(Alignment.Center)
      ) {
        // Vertical line
        Box(
          modifier = Modifier
            .width(crosshairThickness)
            .height(crosshairSize)
            .align(Alignment.Center)
            .background(Color.Red.copy(alpha = 0.7f)) // Translucent red
        )
        // Horizontal line
        Box(
          modifier = Modifier
            .width(crosshairSize)
            .height(crosshairThickness)
            .align(Alignment.Center)
            .background(Color.Red.copy(alpha = 0.7f)) // Translucent red
        )
      }
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScreenshotImage(
  sessionId: String,
  screenshotFile: String?,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  action: AgentDriverAction? = null,
  modifier: Modifier = Modifier,
  imageLoader: ImageLoader = NetworkImageLoader(),
  forceHighQuality: Boolean = false,
  onImageClick: ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?) -> Unit)? = null,
) {
  // A step with no screenshot is the one case with nothing to show and nothing to say about it.
  // Blank as well as null: a reference of "" reaches the loaders as a real name and resolves to a
  // directory URL, which fails deep in the decoder instead of here.
  if (screenshotFile.isNullOrBlank()) return

  val state = rememberScreenshotLoadState(sessionId, screenshotFile, imageLoader, pane = "Screenshot")
  val imageModel = state.model
  val message = state.message

  if (message != null) {
    ScreenshotUnavailable(
      message = message,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      onRetry = if (state.canRetry) state::retry else null,
      modifier = modifier,
    )
  } else {
    var isHovered by remember { mutableStateOf(false) }

    BoxWithConstraints(
      modifier = modifier
        .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
        .clip(RoundedCornerShape(8.dp))
        .onPointerEvent(PointerEventType.Enter) { isHovered = true }
        .onPointerEvent(PointerEventType.Exit) { isHovered = false }
        // Only when there is something to do with the click. A clickable that calls nothing still
        // consumes the event and paints a ripple, which takes the click away from whatever the
        // caller wrapped this in — the gallery's own thumbnail selection, for one.
        .then(
          if (onImageClick != null) {
            Modifier.clickable {
              onImageClick.invoke(imageModel, deviceWidth, deviceHeight, clickX, clickY)
            }
          } else {
            Modifier
          },
        )
    ) {
      val density = LocalDensity.current
      val widthPx = with(density) { maxWidth.toPx().toInt() }
      val heightPx = with(density) { maxHeight.toPx().toInt() }
      
      // Use key() to force recomposition when size changes for high quality mode
      key(if (forceHighQuality) "${widthPx}x${heightPx}" else "default") {
        AsyncImage(
          model = imageModel,
          contentDescription = "Screenshot",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
          // Recording the failure routes it to the fallback above, which is what drops the
          // annotation: drawn over a blank rectangle it reads as a tap that landed on nothing.
          onError = state.onError,
        )
      }

      // Overlay click point if provided
      if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
        val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
        val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
        // Compute center point using available space
        val centerX = maxWidth * xRatio
        val centerY = maxHeight * yRatio

        ScreenshotAnnotation(
          centerX = centerX,
          centerY = centerY,
          maxWidth = maxWidth,
          maxHeight = maxHeight,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          action = action,
          isHovered = isHovered
        )
      } else if (action is AgentDriverAction.Swipe) {
        // For swipe gestures, always show annotation in center even without click coordinates
        ScreenshotAnnotation(
          centerX = maxWidth / 2,
          centerY = maxHeight / 2,
          maxWidth = maxWidth,
          maxHeight = maxHeight,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          action = action,
          isHovered = isHovered
        )
      }

    }
  }
}

/**
 * What [ScreenshotImage] shows in place of a screenshot it cannot draw.
 *
 * Holds the screenshot's own aspect ratio so a timeline row or gallery cell keeps its shape, and —
 * the reason this is a separate branch rather than an `error` slot on the image — draws no
 * annotation. The annotation is placed in device coordinates over the screenshot; over a blank
 * rectangle it reads as a tap that landed on nothing, which sends the reader after the trail
 * instead of after the image.
 *
 * The message is bounded by [ScreenshotDiagnostics] and ellipsized here, so a screenshot inlined
 * as a `data:` URI cannot stretch a timeline row. It stays complete in the semantics tree, which
 * is what a screen reader and a copy of the pane get.
 *
 * [onRetry], when a retry could change the answer, makes the whole cell a retry target. A live
 * session writes its screenshots while the timeline is already on screen, so a row can render
 * before its file lands and latch a failure that is over by the time the reader sees it.
 */
@Composable
private fun ScreenshotUnavailable(
  message: String,
  deviceWidth: Int,
  deviceHeight: Int,
  onRetry: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .then(
        // A device size is normally known even when the screenshot is missing; 0 would be a
        // divide-by-zero, so fall back to a shape that is at least visible.
        if (deviceWidth > 0 && deviceHeight > 0) {
          Modifier.aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
        } else {
          Modifier.heightIn(min = 96.dp)
        },
      )
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
      .then(if (onRetry != null) Modifier.clickable(onClick = onRetry) else Modifier)
      .padding(8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      // Plain text rather than the selectable variant the full-pane surfaces use: selection would
      // take the drag and the tap that the retry sits on, and this cell is small enough that the
      // message is on screen in full anyway.
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        // Compose applies the ellipsis per line limit, not per available height, so without a
        // `maxLines` the overflow setting does nothing and a long cause is cropped with no sign it
        // was cropped. Six lines holds the whole message in a normal pane and shows a "…" in a cell
        // too small for it.
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
      )
      if (onRetry != null) {
        Text(
          text = RETRY_PROMPT,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/** Kept out of the message so the wording every pane shares stays one string. */
internal const val RETRY_PROMPT = "Click to retry"

@Composable
fun ScreenshotImageModal(
  imageModel: Any,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int?,
  clickY: Int?,
  action: AgentDriverAction? = null,
  onDismiss: () -> Unit,
) {
  FullScreenModalOverlay(onDismiss = onDismiss) {
    ImagePreviewDialog(
      imageModel = imageModel,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      clickX = clickX,
      clickY = clickY,
      action = action,
      onDismiss = onDismiss
    )
  }
}

fun swipeDirectionIcon(
  direction: String,
): ImageVector {
  return when (direction.uppercase()) {
    "UP" -> Icons.Filled.ArrowUpward
    "DOWN" -> Icons.Filled.ArrowDownward
    "LEFT" -> Icons.Filled.ArrowBack
    "RIGHT" -> Icons.Filled.ArrowForward
    else -> {
      Icons.Filled.ArrowUpward
    }
  }
}