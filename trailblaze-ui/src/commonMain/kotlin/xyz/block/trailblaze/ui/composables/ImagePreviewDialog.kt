package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ImagePreviewDialog(
  imageModel: Any,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  onDismiss: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.8f),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    )
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      BoxWithConstraints {
        // Base image
        AsyncImage(
          model = imageModel,
          contentDescription = "Screenshot Preview",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit
        )

        // Overlay click point if provided
        if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
          val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
          val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
          val dotSize = 18.dp

          val imageAspectRatio = deviceWidth.toFloat() / deviceHeight.toFloat()
          val boxAspectRatio = maxWidth / maxHeight

          val imageWidth: Dp
          val imageHeight: Dp
          if (imageAspectRatio > boxAspectRatio) {
            imageWidth = maxWidth
            imageHeight = maxWidth / imageAspectRatio
          } else {
            imageHeight = maxHeight
            imageWidth = maxHeight * imageAspectRatio
          }

          val offsetX = ((maxWidth - imageWidth) / 2) + (imageWidth * xRatio)
          val offsetY = ((maxHeight - imageHeight) / 2) + (imageHeight * yRatio)

          // Red dot with black outline
          Box(
            modifier = Modifier
              .size(dotSize)
              .offset(
                x = (offsetX - (dotSize / 2)).coerceIn(0.dp, maxWidth - dotSize),
                y = (offsetY - (dotSize / 2)).coerceIn(0.dp, maxHeight - dotSize)
              )
              .background(Color.Red, shape = CircleShape)
              .border(width = 3.dp, color = Color.White, shape = CircleShape)
          )
        }
      }
    }
  }
}

