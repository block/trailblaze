package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionEventBuffer
import xyz.block.trailblaze.recording.ViewHierarchyHitTester

/**
 * Layout info for a [ContentScale.Fit] image within its composable container.
 * Used to correctly map composable-space tap coordinates to device-space coordinates,
 * accounting for letterbox margins.
 */
private data class FitImageLayout(
  val offsetX: Float,
  val offsetY: Float,
  val scale: Float,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
) {
  val renderedWidth get() = deviceWidth * scale
  val renderedHeight get() = deviceHeight * scale

  /** Convert a composable-space tap to device coordinates, or null if outside the image. */
  fun toDeviceCoords(tapX: Float, tapY: Float): Pair<Int, Int>? {
    val imgX = tapX - offsetX
    val imgY = tapY - offsetY
    if (imgX < 0 || imgY < 0 || imgX > renderedWidth || imgY > renderedHeight) return null
    return Pair((imgX / scale).toInt(), (imgY / scale).toInt())
  }

  companion object {
    fun calculate(composableSize: IntSize, deviceWidth: Int, deviceHeight: Int): FitImageLayout {
      if (composableSize.width == 0 || composableSize.height == 0 || deviceWidth == 0 || deviceHeight == 0) {
        return FitImageLayout(0f, 0f, 1f)
      }
      val scaleX = composableSize.width.toFloat() / deviceWidth
      val scaleY = composableSize.height.toFloat() / deviceHeight
      val scale = minOf(scaleX, scaleY)
      val renderedW = deviceWidth * scale
      val renderedH = deviceHeight * scale
      return FitImageLayout(
        offsetX = (composableSize.width - renderedW) / 2f,
        offsetY = (composableSize.height - renderedH) / 2f,
        scale = scale,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
      )
    }
  }
}

/**
 * Composable that renders a live-streamed device screen and captures user input
 * (tap, drag/swipe, keyboard) for interactive recording.
 *
 * Input is always forwarded to the device so the user can interact.
 * When [isRecording] is true, interactions are also buffered for trail generation.
 */
@Composable
fun InteractiveDeviceComposable(
  stream: DeviceScreenStream,
  buffer: InteractionEventBuffer,
  isRecording: Boolean,
  modifier: Modifier = Modifier,
) {
  var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
  var composableSize by remember { mutableStateOf(IntSize.Zero) }
  var isTextInputMode by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }

  // Collect frames from the stream
  LaunchedEffect(stream) {
    stream.frames().collect { bytes ->
      try {
        val skiaImage = SkiaImage.makeFromEncoded(bytes)
        try {
          currentFrame = skiaImage.toComposeImageBitmap()
        } finally {
          skiaImage.close()
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Skip malformed frames
      }
    }
  }

  // Request focus so key events are received.
  // Re-request when isRecording changes because clicking the Record/Stop button steals focus.
  LaunchedEffect(isRecording) {
    focusRequester.requestFocus()
  }

  Box(
    modifier = modifier
      .onSizeChanged { composableSize = it }
      .focusRequester(focusRequester)
      .focusable()
      .onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

        // Ignore modifier key combos (Cmd+Tab, Ctrl+C, Alt+..., etc.)
        if (keyEvent.isMetaPressed || keyEvent.isCtrlPressed || keyEvent.isAltPressed) {
          return@onKeyEvent false
        }

        // Forward keystrokes to device for live interaction, and record when active.
        when (keyEvent.key) {
          Key.Enter -> {
            scope.launch {
              if (isRecording) {
                val screenshot = try { stream.getScreenshot() } catch (_: Exception) { null }
                buffer.onSpecialKey("Enter", screenshot, null)
              }
              stream.pressKey("Enter")
            }
            true
          }
          Key.Tab -> {
            scope.launch { stream.pressKey("Tab") }
            true
          }
          Key.Escape -> {
            scope.launch {
              stream.pressKey("Escape")
              isTextInputMode = false
            }
            true
          }
          Key.Backspace -> {
            scope.launch {
              if (isRecording) buffer.onBackspace()
              stream.pressKey("Backspace")
            }
            true
          }
          else -> {
            val codePoint = keyEvent.utf16CodePoint
            if (codePoint > 31) {
              val char = codePoint.toChar()
              scope.launch {
                if (isRecording) {
                  val screenshot = try { stream.getScreenshot() } catch (_: Exception) { null }
                  buffer.onCharacterTyped(char, screenshot, null)
                }
                stream.inputText(char.toString())
              }
              true
            } else {
              false
            }
          }
        }
      },
  ) {
    val frame = currentFrame
    if (frame != null) {
      val imageLayout = remember(composableSize, stream.deviceWidth, stream.deviceHeight) {
        FitImageLayout.calculate(composableSize, stream.deviceWidth, stream.deviceHeight)
      }

      // Track drag gesture state
      var dragStart by remember { mutableStateOf<Offset?>(null) }
      var dragEnd by remember { mutableStateOf<Offset?>(null) }

      Image(
        bitmap = frame,
        contentDescription = "Device screen",
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          // Drag/swipe detection MUST come before tap detection.
          // detectTapGestures (with onLongPress) holds the pointer down for ~400ms,
          // which prevents detectDragGestures from ever seeing the events.
          .pointerInput(isRecording, imageLayout) {
            detectDragGestures(
              onDragStart = { start -> dragStart = start },
              onDragEnd = {
                val start = dragStart ?: return@detectDragGestures
                val end = dragEnd ?: return@detectDragGestures
                val (startDeviceX, startDeviceY) = imageLayout.toDeviceCoords(start.x, start.y)
                  ?: return@detectDragGestures
                val (endDeviceX, endDeviceY) = imageLayout.toDeviceCoords(end.x, end.y)
                  ?: return@detectDragGestures

                scope.launch {
                  if (isRecording) {
                    val screenshot = try { stream.getScreenshot() } catch (_: Exception) { null }
                    buffer.onSwipe(
                      startX = startDeviceX,
                      startY = startDeviceY,
                      endX = endDeviceX,
                      endY = endDeviceY,
                      screenshot = screenshot,
                      hierarchyText = null,
                    )
                  }
                  stream.swipe(
                    startX = startDeviceX,
                    startY = startDeviceY,
                    endX = endDeviceX,
                    endY = endDeviceY,
                  )
                }
                dragStart = null
                dragEnd = null
              },
              onDrag = { change, _ ->
                dragEnd = change.position
              },
            )
          }
          .pointerInput(isRecording, imageLayout) {
            detectTapGestures(
              onTap = { offset ->
                val (deviceX, deviceY) = imageLayout.toDeviceCoords(offset.x, offset.y)
                  ?: return@detectTapGestures

                scope.launch {
                  val hierarchy = try { stream.getViewHierarchy() } catch (_: Exception) { null }
                  val screenshot = try { stream.getScreenshot() } catch (_: Exception) { null }
                  val hitNode = hierarchy?.let { ViewHierarchyHitTester.hitTest(it, deviceX, deviceY) }

                  if (hitNode != null && ViewHierarchyHitTester.isInputField(hitNode)) {
                    isTextInputMode = true
                  } else {
                    isTextInputMode = false
                  }

                  if (isRecording) {
                    buffer.onTap(hitNode, deviceX, deviceY, screenshot, null)
                  }
                  stream.tap(deviceX, deviceY)
                }
              },
              onLongPress = { offset ->
                val (deviceX, deviceY) = imageLayout.toDeviceCoords(offset.x, offset.y)
                  ?: return@detectTapGestures

                scope.launch {
                  val hierarchy = try { stream.getViewHierarchy() } catch (_: Exception) { null }
                  val screenshot = try { stream.getScreenshot() } catch (_: Exception) { null }
                  val hitNode = hierarchy?.let { ViewHierarchyHitTester.hitTest(it, deviceX, deviceY) }
                  if (isRecording) {
                    buffer.onLongPress(hitNode, deviceX, deviceY, screenshot, null)
                  }
                  stream.longPress(deviceX, deviceY)
                }
              },
            )
          },
      )

      // Text input mode indicator
      if (isTextInputMode) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(8.dp)
            .background(
              color = Color(0xCC000000),
              shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
          Text(
            text = if (isRecording) "Keyboard active (recording)" else "Keyboard active",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    } else {
      // Loading state
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "Connecting to device...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
