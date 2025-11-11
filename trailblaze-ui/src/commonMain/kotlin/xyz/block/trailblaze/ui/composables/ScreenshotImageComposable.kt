package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader

/**
 * Shared composable for rendering screenshot annotations consistently
 */
@Composable
fun ScreenshotAnnotation(
  centerX: Dp,
  centerY: Dp,
  maxWidth: Dp,
  maxHeight: Dp,
  action: MaestroDriverActionType? = null,
  modifier: Modifier = Modifier
) {
  if (action is MaestroDriverActionType.AssertCondition) {
    val checkSize = 20.dp
    val backgroundSize = 28.dp

    // For notVisible assertions, show the check mark in upper corner
    if (!action.isVisible && action.textToDisplay != null) {
      // Position check mark in upper corner
      Box(
        modifier = Modifier
          .size(backgroundSize)
          .offset(
            x = 8.dp, // Position near left edge
            y = 8.dp  // Position at top
          )
          .border(
            2.dp, Color.Green.copy(alpha = 0.6f), shape = CircleShape
          ) // Translucent border only
      ) {
        // Transparent checkmark icon
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = "Assertion Success",
          modifier = Modifier
            .size(checkSize)
            .align(Alignment.Center),
          tint = Color.Green.copy(alpha = 0.7f) // Translucent check mark
        )
      }

      // Text label for not found item
      Box(
        modifier = Modifier
          .offset(
            x = 8.dp + backgroundSize + 4.dp, // Position next to the check mark
            y = 8.dp + (backgroundSize / 2) - 8.dp  // Vertically centered with check mark
          )
          .background(
            Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)
          ) // Very transparent background
          .border(1.dp, Color.Green.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp))
      ) {
        Text(
          text = "\"${action.textToDisplay}\" not found",
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          color = Color.Black,
          textAlign = TextAlign.Center
        )
      }
    } else {
      // Visible assertions - show at click location with transparent circle background
      Box(
        modifier = Modifier
          .size(backgroundSize)
          .offset(
            x = centerX - (backgroundSize / 2),
            y = centerY - (backgroundSize / 2)
          )
          .border(
            2.dp, Color.Green.copy(alpha = 0.6f), shape = CircleShape
          ) // Translucent border only, no background fill
      ) {
        // Transparent checkmark icon
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = "Assertion Success",
          modifier = Modifier
            .size(checkSize)
            .align(Alignment.Center),
          tint = Color.Green.copy(alpha = 0.7f) // Translucent check mark
        )
      }
    }
  } else {
    // Transparent red crosshairs with circle background
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

@Composable
fun ScreenshotImage(
  sessionId: String,
  screenshotFile: String?,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  action: MaestroDriverActionType? = null,
  modifier: Modifier = Modifier,
  imageLoader: ImageLoader = NetworkImageLoader(),
  forceHighQuality: Boolean = false,
  onImageClick: ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?) -> Unit)? = null,
) {
  val imageModel = imageLoader.getImageModel(sessionId, screenshotFile)

  if (imageModel != null) {
    BoxWithConstraints(
      modifier = modifier
        .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
        .clip(RoundedCornerShape(8.dp))
        .clickable { 
          onImageClick?.invoke(imageModel, deviceWidth, deviceHeight, clickX, clickY)
        }
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
          contentScale = ContentScale.Fit
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
          action = action
        )
      }
    }

  }
}

@Composable
fun ScreenshotImageModal(
  imageModel: Any,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int?,
  clickY: Int?,
  action: MaestroDriverActionType? = null,
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