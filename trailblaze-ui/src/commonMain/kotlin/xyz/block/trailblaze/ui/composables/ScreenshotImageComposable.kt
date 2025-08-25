package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader

@Composable
fun ScreenshotImage(
  sessionId: String,
  screenshotFile: String?,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  modifier: Modifier = Modifier,
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  var showImageDialog by remember { mutableStateOf(false) }
  val imageModel = imageLoader.getImageModel(sessionId, screenshotFile)

  if (imageModel != null) {
    BoxWithConstraints(
      modifier = modifier
        .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
        .clip(RoundedCornerShape(8.dp))
        .clickable { showImageDialog = true }
    ) {
      // Base image
      AsyncImage(
        model = imageModel,
        contentDescription = "Screenshot",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
      )

      // Overlay click point if provided
      if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
        val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
        val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
        val dotSize = 15.dp
        // Compute center point offsets in Dp using available space
        val centerX = maxWidth * xRatio
        val centerY = maxHeight * yRatio
        val offsetX = centerX - (dotSize / 2)
        val offsetY = centerY - (dotSize / 2)

        // Red dot (behind) with black outline
        Box(
          modifier = Modifier
            .size(dotSize)
            .offset(
              x = offsetX.coerceIn(0.dp, maxWidth - dotSize),
              y = offsetY.coerceIn(0.dp, maxHeight - dotSize)
            )
            .background(Color.Red, shape = CircleShape)
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
        )
      }
    }

    // Image preview modal
    if (showImageDialog) {
      Dialog(onDismissRequest = { showImageDialog = false }) {
        ImagePreviewDialog(
          imageModel = imageModel,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          clickX = clickX,
          clickY = clickY,
          onDismiss = { showImageDialog = false }
        )
      }
    }
  }
}