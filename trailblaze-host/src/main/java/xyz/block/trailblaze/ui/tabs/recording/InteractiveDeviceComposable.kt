package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import xyz.block.trailblaze.util.Console

/**
 * Wraps a stream-side fetch (`getScreenshot` / `getViewHierarchy` / `getTrailblazeNodeTree`)
 * so transient failures don't kill the launch coroutine but DO leave a breadcrumb. Without a
 * log, "why did my recording fail to capture a hierarchy?" is opaque in production — the
 * recorder silently emits a tool with no screenshot and no node tree, and the only way to
 * tell something went wrong is by noticing what's missing.
 *
 * Inline so the suspending body inside [block] composes naturally with the surrounding
 * coroutine scope.
 */
private inline fun <T> tryFetch(description: String, block: () -> T?): T? = try {
  block()
} catch (e: kotlinx.coroutines.CancellationException) {
  throw e
} catch (e: Throwable) {
  Console.log("InteractiveDeviceComposable: $description failed: ${e.message}")
  null
}

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

  /**
   * Inverse of [toDeviceCoords]: project a device-space point back into the composable
   * coordinate system, including the image offset. Used by the gesture-feedback overlay to
   * draw indicators at the same spot the user tapped on the device's rendered viewport.
   */
  fun toComposableCoords(deviceX: Int, deviceY: Int): Pair<Float, Float> =
    Pair(offsetX + deviceX * scale, offsetY + deviceY * scale)

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
 * Most-recent gesture the user performed on the device preview, packaged for the visual
 * feedback overlay. Modeled as a sealed interface (not just a coordinate pair) so the
 * renderer can branch on shape — a tap is a single point, a long press is a tap variant
 * that warrants a distinct visual, and a swipe needs both endpoints + a connecting line.
 *
 * Coordinates are stored in **device space**, not composable space, so the overlay survives
 * a window resize: composable-space coordinates would be wrong after the user changes the
 * window size between gesture-time and the next paint, but device-space ones project back
 * through the live `imageLayout` and always land on the right pixel of the rendered image.
 */
internal sealed interface GestureOverlay {
  data class Tap(
    val deviceX: Int,
    val deviceY: Int,
    /**
     * True when the gesture came from `detectTapGestures.onLongPress` (held ~400ms+).
     * Drives the renderer to use a distinct color + slightly larger pulse so the user can
     * tell a long-press registered without inspecting the recorded action card.
     */
    val isLongPress: Boolean,
  ) : GestureOverlay

  data class Swipe(
    val startDeviceX: Int,
    val startDeviceY: Int,
    val endDeviceX: Int,
    val endDeviceY: Int,
  ) : GestureOverlay
}

/**
 * Composable that renders a live-streamed device screen and captures user input
 * (tap, drag/swipe, keyboard) for interactive recording.
 *
 * Input is always forwarded to the device so the user can interact.
 * When [isRecording] is true, interactions are also buffered for trail generation.
 *
 * @param onConnectionLost Called when a stream call throws (typically the Maestro iOS
 *   XCUITest socket flapping). The default rethrows so dev environments still see
 *   stack traces; production callers should pass a handler that flips the parent
 *   composable into an error/reconnect state instead of letting the exception
 *   propagate to Compose-Desktop's `DesktopCoroutineExceptionHandler` (which
 *   surfaces a fatal AWT dialog the user has to dismiss).
 */
@Composable
fun InteractiveDeviceComposable(
  stream: DeviceScreenStream,
  buffer: InteractionEventBuffer,
  isRecording: Boolean,
  modifier: Modifier = Modifier,
  onConnectionLost: (Throwable) -> Unit = { throw it },
) {
  var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
  var composableSize by remember { mutableStateOf(IntSize.Zero) }
  var isTextInputMode by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }

  // Local helper that wraps each scope.launch body. CancellationException must propagate
  // (otherwise structured concurrency breaks); everything else is a connection-level
  // failure to surface upstream so the user can reconnect.
  fun launchIo(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Throwable) {
        onConnectionLost(e)
      }
    }
  }

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
            launchIo {
              if (isRecording) {
                val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
                buffer.onSpecialKey("Enter", screenshot, null)
              }
              stream.pressKey("Enter")
            }
            true
          }
          Key.Tab -> {
            launchIo { stream.pressKey("Tab") }
            true
          }
          Key.Escape -> {
            launchIo {
              stream.pressKey("Escape")
              isTextInputMode = false
            }
            true
          }
          Key.Backspace -> {
            launchIo {
              if (isRecording) buffer.onBackspace()
              stream.pressKey("Backspace")
            }
            true
          }
          else -> {
            val codePoint = keyEvent.utf16CodePoint
            if (codePoint > 31) {
              val char = codePoint.toChar()
              launchIo {
                if (isRecording) {
                  val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
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

      // Track drag gesture state. `dragStartTimeMs` lets us measure wall-clock duration
      // from press to release so the dispatched (and recorded) swipe matches the user's
      // actual gesture speed — a fast flick stays a flick, a slow drag stays a drag.
      var dragStart by remember { mutableStateOf<Offset?>(null) }
      var dragEnd by remember { mutableStateOf<Offset?>(null) }
      var dragStartTimeMs by remember { mutableStateOf(0L) }

      // Most-recent gesture overlay — drives the visual feedback layer drawn over the
      // device image. Without it the user has no on-screen confirmation that their tap or
      // swipe was actually picked up by the host before the device's next frame arrives;
      // confidence-builder more than a debugging tool.
      var gestureOverlay by remember { mutableStateOf<GestureOverlay?>(null) }
      val overlayAlpha = remember { Animatable(0f) }
      LaunchedEffect(gestureOverlay) {
        val current = gestureOverlay ?: return@LaunchedEffect
        // Snap to full opacity so the overlay is immediately visible the moment the user
        // releases, then fade. Long-presses get a longer dwell so the distinct visual sticks
        // around long enough to register; regular taps disappear quickly to stay unobtrusive.
        overlayAlpha.snapTo(1f)
        val durationMs = if (current is GestureOverlay.Tap && current.isLongPress) 900 else 600
        overlayAlpha.animateTo(0f, animationSpec = tween(durationMs))
        gestureOverlay = null
      }

      // Shared tap-handling — reused by detectTapGestures AND the "drag too small to
      // count as a swipe" branch in onDragEnd. Compose's `detectDragGestures` fires the
      // moment the pointer crosses the touch-slop threshold (a handful of pixels), so a
      // tap with any finger drift gets classified as a drag and never reaches
      // `detectTapGestures`. Without a downstream check, every "tap" with any movement
      // would record as a swipeWithRelativeCoordinates of like 54%,73% → 54%,72%.
      fun handleTapAt(deviceX: Int, deviceY: Int, isLongPress: Boolean = false) {
        gestureOverlay = GestureOverlay.Tap(deviceX = deviceX, deviceY = deviceY, isLongPress = isLongPress)
        launchIo {
          val hierarchy = tryFetch("getViewHierarchy") { stream.getViewHierarchy() }
          val trailblazeTree = tryFetch("getTrailblazeNodeTree") { stream.getTrailblazeNodeTree() }
          val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
          val hitNode = hierarchy?.let { ViewHierarchyHitTester.hitTest(it, deviceX, deviceY) }
          isTextInputMode = hitNode != null && ViewHierarchyHitTester.isInputField(hitNode)
          if (isRecording) {
            if (isLongPress) {
              buffer.onLongPress(hitNode, deviceX, deviceY, screenshot, null, trailblazeTree)
            } else {
              buffer.onTap(hitNode, deviceX, deviceY, screenshot, null, trailblazeTree)
            }
          }
          if (isLongPress) {
            stream.longPress(deviceX, deviceY)
          } else {
            stream.tap(deviceX, deviceY)
          }
        }
      }

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
              onDragStart = { start ->
                focusRequester.requestFocus()
                dragStart = start
                dragStartTimeMs = System.currentTimeMillis()
              },
              onDragEnd = {
                val start = dragStart ?: return@detectDragGestures
                val end = dragEnd ?: return@detectDragGestures
                val (startDeviceX, startDeviceY) = imageLayout.toDeviceCoords(start.x, start.y)
                  ?: return@detectDragGestures
                val (endDeviceX, endDeviceY) = imageLayout.toDeviceCoords(end.x, end.y)
                  ?: return@detectDragGestures

                val dx = (endDeviceX - startDeviceX).toFloat()
                val dy = (endDeviceY - startDeviceY).toFloat()
                val distancePx = kotlin.math.sqrt(dx * dx + dy * dy)
                // 3% of the smaller device dimension — finger drift on a typical 1080-px-
                // wide phone (~32px) reads as a tap, while real swipes (typically 10–50%
                // of screen height) clear the threshold by a wide margin. Scaling by
                // device dimension means small phones and tablets both behave correctly
                // without per-form-factor tuning.
                val tapThreshold = minOf(stream.deviceWidth, stream.deviceHeight) * 0.03f

                if (distancePx < tapThreshold) {
                  // What Compose called a drag was finger drift on a tap. Re-classify so
                  // the recording captures it as a tap (which can resolve to a stable
                  // selector via the hit-tester) instead of a fragile near-zero-distance
                  // swipe.
                  handleTapAt(startDeviceX, startDeviceY)
                } else {
                  // Floor at 50ms — gives the underlying driver a non-degenerate duration
                  // even if the user's gesture was so fast that wall-clock measurement
                  // rounded to 0 (rare, but happens with trackpad flicks). The driver caps
                  // velocity on its own so we don't need a ceiling here.
                  val measuredDurationMs = (System.currentTimeMillis() - dragStartTimeMs)
                    .coerceAtLeast(50L)
                  gestureOverlay = GestureOverlay.Swipe(
                    startDeviceX = startDeviceX,
                    startDeviceY = startDeviceY,
                    endDeviceX = endDeviceX,
                    endDeviceY = endDeviceY,
                  )
                  launchIo {
                    if (isRecording) {
                      val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
                      buffer.onSwipe(
                        startX = startDeviceX,
                        startY = startDeviceY,
                        endX = endDeviceX,
                        endY = endDeviceY,
                        screenshot = screenshot,
                        hierarchyText = null,
                        durationMs = measuredDurationMs,
                      )
                    }
                    stream.swipe(
                      startX = startDeviceX,
                      startY = startDeviceY,
                      endX = endDeviceX,
                      endY = endDeviceY,
                      durationMs = measuredDurationMs,
                    )
                  }
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
                // Tapping the device area must re-grab keyboard focus. Without this, focus
                // gets stuck on whichever non-device UI element the user clicked last
                // (action card, dropdown, button), and subsequent keystrokes on the Mac
                // keyboard route there instead of into onKeyEvent below — that's why
                // typed characters silently fail to record after interacting with the
                // right-hand panel.
                focusRequester.requestFocus()
                val (deviceX, deviceY) = imageLayout.toDeviceCoords(offset.x, offset.y)
                  ?: return@detectTapGestures
                handleTapAt(deviceX, deviceY)
              },
              onLongPress = { offset ->
                focusRequester.requestFocus()
                val (deviceX, deviceY) = imageLayout.toDeviceCoords(offset.x, offset.y)
                  ?: return@detectTapGestures
                // handleTapAt now branches on `isLongPress` so the overlay, recording, and
                // dispatch all stay aligned between the short-tap and long-press paths.
                handleTapAt(deviceX, deviceY, isLongPress = true)
              },
            )
          },
      )

      // Visual gesture-feedback overlay drawn on top of the device image — confirms to the
      // user that their tap/swipe registered before the next device frame arrives. Drawn in
      // composable space (NOT device space) via `imageLayout.toComposableCoords`, so the
      // indicator lands on the same pixel the user clicked. Pointer events pass through to
      // the gesture detectors below; this is purely cosmetic.
      val overlay = gestureOverlay
      if (overlay != null && overlayAlpha.value > 0f) {
        val tapColor = MaterialTheme.colorScheme.primary
        val longPressColor = MaterialTheme.colorScheme.tertiary
        val swipeStartColor = MaterialTheme.colorScheme.primary
        val swipeEndColor = MaterialTheme.colorScheme.error
        Canvas(modifier = Modifier.fillMaxSize()) {
          val alpha = overlayAlpha.value
          when (overlay) {
            is GestureOverlay.Tap -> {
              val (cx, cy) = imageLayout.toComposableCoords(overlay.deviceX, overlay.deviceY)
              val baseRadiusPx = if (overlay.isLongPress) 36f else 24f
              // Outer ring fades + grows so the tap feels like a ripple. Inner filled disc
              // stays the same size — that's the "you tapped here" anchor.
              drawCircle(
                color = (if (overlay.isLongPress) longPressColor else tapColor).copy(alpha = alpha * 0.4f),
                radius = baseRadiusPx + (1f - alpha) * baseRadiusPx,
                center = Offset(cx, cy),
                style = Stroke(width = 3f),
              )
              drawCircle(
                color = (if (overlay.isLongPress) longPressColor else tapColor).copy(alpha = alpha * 0.85f),
                radius = baseRadiusPx * 0.5f,
                center = Offset(cx, cy),
              )
            }
            is GestureOverlay.Swipe -> {
              val (sx, sy) = imageLayout.toComposableCoords(overlay.startDeviceX, overlay.startDeviceY)
              val (ex, ey) = imageLayout.toComposableCoords(overlay.endDeviceX, overlay.endDeviceY)
              // Start (green-ish) → End (red-ish) so the direction is unambiguous. Line gets
              // a subtle thickness so it reads as a gesture trail rather than a hairline.
              drawLine(
                color = swipeStartColor.copy(alpha = alpha * 0.6f),
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = 4f,
              )
              drawCircle(
                color = swipeStartColor.copy(alpha = alpha * 0.85f),
                radius = 14f,
                center = Offset(sx, sy),
              )
              drawCircle(
                color = swipeEndColor.copy(alpha = alpha * 0.85f),
                radius = 14f,
                center = Offset(ex, ey),
              )
            }
          }
        }
      }

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
