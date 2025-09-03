package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImagePreviewDialog(
  imageModel: Any,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  onDismiss: () -> Unit,
) {
  // Zoom and pan state
  var scale by remember { mutableStateOf(1f) }
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }

  // Dimensions state for button constraints
  var containerWidth by remember { mutableStateOf(0f) }
  var containerHeight by remember { mutableStateOf(0f) }
  var imageWidth by remember { mutableStateOf(0f) }
  var imageHeight by remember { mutableStateOf(0f) }

  // Function to zoom with cursor position
  fun zoomAtPosition(zoomFactor: Float, cursorX: Float, cursorY: Float, containerWidth: Float, containerHeight: Float) {
    val newScale = (scale * zoomFactor).coerceIn(0.5f, 5f)
    if (newScale != scale) {
      // Calculate the cursor position relative to the image center
      val imageCenterX = containerWidth / 2f
      val imageCenterY = containerHeight / 2f

      // Calculate the point in the unscaled image coordinate system
      val relativeX = (cursorX - imageCenterX - offsetX) / scale
      val relativeY = (cursorY - imageCenterY - offsetY) / scale

      // Update scale
      val scaleChange = newScale / scale
      scale = newScale

      // Adjust offset to keep the cursor point stationary
      offsetX = offsetX * scaleChange + relativeX * (scale - newScale)
      offsetY = offsetY * scaleChange + relativeY * (scale - newScale)
    }
  }

  // Function to constrain panning within bounds  
  fun constrainPanning(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float) {
    val scaledImageWidth = imageWidth * scale
    val scaledImageHeight = imageHeight * scale

    // When zoomed in, allow full panning to see all parts of the scaled image
    // Calculate how much the scaled image extends beyond the container
    val maxOffsetX = kotlin.math.max(0f, (scaledImageWidth - containerWidth) / 2f)
    val maxOffsetY = kotlin.math.max(0f, (scaledImageHeight - containerHeight) / 2f)

    // Allow panning the full range to see all parts of the zoomed image
    offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
    offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
  }

  // Function to reset zoom and pan
  fun resetZoom() {
    scale = 1f
    offsetX = 0f
    offsetY = 0f
  }

  // Simple zoom functions for buttons (will apply constraints later)
  fun buttonZoomIn(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float) {
    scale = (scale * 1.2f).coerceIn(0.5f, 5f)
    constrainPanning(containerWidth, containerHeight, imageWidth, imageHeight)
  }

  fun buttonZoomOut(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float) {
    scale = (scale * 0.8f).coerceIn(0.5f, 5f)
    constrainPanning(containerWidth, containerHeight, imageWidth, imageHeight)
  }

  Box(
    modifier = Modifier
      .background(Color.Transparent)
      .fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    // Image card - positioned below buttons with top padding
    Card(
      modifier = Modifier
        .fillMaxSize(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
      )
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            shape = RoundedCornerShape(8.dp)
          )
          .padding(8.dp), // Add small padding inside border
        contentAlignment = Alignment.Center
      ) {
        BoxWithConstraints {
          val density = LocalDensity.current
          val imageAspectRatio = deviceWidth.toFloat() / deviceHeight.toFloat()

          // Convert device pixel dimensions to dp using current density
          val deviceWidthDp = with(density) { deviceWidth.toDp() }
          val deviceHeightDp = with(density) { deviceHeight.toDp() }

          // Calculate the size that fits within available space while respecting actual image size
          val availableWidth = maxWidth
          val availableHeight = maxHeight

          // Don't scale images larger than their actual size
          val maxDisplayWidth = minOf(deviceWidthDp, availableWidth)
          val maxDisplayHeight = minOf(deviceHeightDp, availableHeight)

          // Calculate final dimensions maintaining aspect ratio
          val finalWidth: Dp
          val finalHeight: Dp

          if (maxDisplayWidth / imageAspectRatio <= maxDisplayHeight) {
            finalWidth = maxDisplayWidth
            finalHeight = maxDisplayWidth / imageAspectRatio
          } else {
            finalHeight = maxDisplayHeight
            finalWidth = maxDisplayHeight * imageAspectRatio
          }

          // Update dimensions for button constraints
          containerWidth = finalWidth.value  // Use actual image display area, not full container
          containerHeight = finalHeight.value // Use actual image display area, not full container  
          imageWidth = finalWidth.value
          imageHeight = finalHeight.value

          // Use BoxWithConstraints to get the exact image area for overlay positioning
          BoxWithConstraints(
            modifier = Modifier
              .width(finalWidth)
              .height(finalHeight)
              .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                  scale = (scale * zoom).coerceIn(0.5f, 5f) // Limit zoom between 50% and 500%
                  offsetX += pan.x
                  offsetY += pan.y
                  // Apply panning constraints
                  constrainPanning(maxWidth.value, maxHeight.value, finalWidth.value, finalHeight.value)
                }
              }
              .onPointerEvent(PointerEventType.Scroll) { event ->
                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                val position = event.changes.firstOrNull()?.position
                if (scrollDelta != 0f && position != null) {
                  val zoomFactor = if (scrollDelta > 0) 0.9f else 1.1f
                  // Use the BoxWithConstraints maxWidth/maxHeight for container dimensions
                  zoomAtPosition(zoomFactor, position.x, position.y, maxWidth.value, maxHeight.value)
                  // Apply panning constraints after zoom
                  constrainPanning(maxWidth.value, maxHeight.value, finalWidth.value, finalHeight.value)
                }
              }
              .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
              )
          ) {
            // Base image that exactly fills this container
            AsyncImage(
              model = imageModel,
              contentDescription = "Screenshot Preview",
              modifier = Modifier
                .width(finalWidth)
                .height(finalHeight),
              contentScale = ContentScale.FillBounds
            )

            // Overlay click point if provided
            if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
              val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
              val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
              val dotSize = 18.dp

              // Calculate click point position using the exact display dimensions
              val clickPointX = finalWidth * xRatio - (dotSize / 2)
              val clickPointY = finalHeight * yRatio - (dotSize / 2)

              // Red dot with white outline
              Box(
                modifier = Modifier
                  .size(dotSize)
                  .offset(
                    x = clickPointX.coerceIn(0.dp, finalWidth - dotSize),
                    y = clickPointY.coerceIn(0.dp, finalHeight - dotSize)
                  )
                  .background(Color.Red, shape = CircleShape)
                  .border(width = 3.dp, color = Color.White, shape = CircleShape)
              )
            }
          }
        }
      }
    }
    // Zoom control buttons - positioned above the image card
    Card(
      modifier = Modifier
        .padding(16.dp)
        .align(Alignment.TopEnd)
        .padding(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Row(
        modifier = Modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        IconButton(
          onClick = {
            buttonZoomIn(containerWidth, containerHeight, imageWidth, imageHeight)
          }
        ) {
          Icon(Icons.Default.Add, contentDescription = "Zoom In")
        }

        IconButton(
          onClick = {
            buttonZoomOut(containerWidth, containerHeight, imageWidth, imageHeight)
          }
        ) {
          Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
        }

        IconButton(
          onClick = { resetZoom() }
        ) {
          Icon(Icons.Default.Refresh, contentDescription = "Reset Zoom")
        }
      }
    }
    // Zoom control buttons - positioned above the image card
    Card(
      modifier = Modifier
        .padding(16.dp)
        .align(Alignment.TopStart)
        .padding(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Row(
        modifier = Modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        IconButton(
          onClick = onDismiss
        ) {
          Icon(Icons.Default.Close, contentDescription = "Close")
        }
      }
    }
  }
}

