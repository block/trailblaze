package xyz.block.trailblaze.ui.recording

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
import kotlinx.datetime.Clock
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
 * Debounce window before flushing a typing burst to the device via `stream.inputText`. With
 * the cumulative-buffer model (see [pendingLiveText]) the debounce mostly exists to coalesce
 * fast-typed bursts into one RPC — even a per-char flush is correct because each call sends
 * the full cumulative text and the on-device runner's `ACTION_SET_TEXT` replaces the field.
 * 60 ms is short enough that a 100 WPM user (≈125 ms/char) still sees each char propagate
 * within one debounce window, but long enough that a typed-fast burst lands as a single RPC.
 */
private const val LIVE_TYPING_DEBOUNCE_MS: Long = 60L

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
  buffer: InteractionEventBuffer?,
  isRecording: Boolean,
  modifier: Modifier = Modifier,
  onConnectionLost: (Throwable) -> Unit = { throw it },
  /**
   * Secondary recording hook used by the wasmJs web viewer, which doesn't have JVM-only
   * typed `TrailblazeTool` classes available to construct a `RecordedInteraction`. Fires
   * alongside [buffer] (when non-null) for every recorded gesture, with the raw event
   * coordinates and timing. When [isRecording] is false this callback is never invoked.
   */
  onWebGesture: ((WebGesture) -> Unit)? = null,
  /**
   * Supplies the next monotonic [GestureId] for each emitted [WebGesture]. The caller owns
   * the counter (typically a per-page `var nextGestureId by remember { mutableStateOf(0L) }`)
   * so unique-id semantics survive recompositions of this composable. Only consulted when
   * [onWebGesture] is non-null; the JVM buffer path keys interactions differently.
   */
  nextGestureId: () -> GestureId = { 0L },
) {
  var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
  var composableSize by remember { mutableStateOf(IntSize.Zero) }
  var isTextInputMode by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // "Device Mirroring FPS" diagnostic: counts frames that actually landed in this composable
  // and got decoded, sampled once per second. NOT the device's screen-refresh rate — that
  // would require the device to surface its own frame counter, which we don't have. This is
  // purely "frames per second this viewer is seeing", which is a function of the daemon's
  // capture/encode pipeline and any network back-pressure between us. Useful as a
  // before/after gauge for capture-pipeline work (e.g. polling-vs-streaming).
  var framesInWindow by remember { mutableStateOf(0) }
  var displayedMirroringFps by remember { mutableStateOf(0) }
  LaunchedEffect(Unit) {
    // Tick every second on the composable's coroutine scope (so we naturally stop counting
    // when the composable leaves composition / disposes). One-second window is the smallest
    // interval that gives a stable integer FPS readout without flicker.
    while (true) {
      kotlinx.coroutines.delay(1_000)
      displayedMirroringFps = framesInWindow
      framesInWindow = 0
    }
  }

  // For the wasmJs web recorder: buffer characters and flush on a 500ms idle so a typed word
  // emits one `inputText("hello")` gesture instead of five `pressKey('h')`-shaped events.
  // Matches what InteractionEventBuffer does internally for the JVM buffer path. Kept here
  // because the buffer-less web path needs the same UX without dragging in the buffer class.
  //
  // **isRecording transitions to false** discards any buffered text without emitting — the
  // user has signaled "stop recording", so silently emitting a delayed InputText after Stop
  // would be a footgun (Copilot caught this).
  val pendingWebText = remember { StringBuilder() }
  var pendingTextFlushNonce by remember { mutableStateOf(0) }
  LaunchedEffect(pendingTextFlushNonce) {
    if (pendingWebText.isEmpty()) return@LaunchedEffect
    kotlinx.coroutines.delay(500)
    val text = pendingWebText.toString()
    pendingWebText.clear()
    // Re-check isRecording AFTER the debounce delay — the user may have hit Stop while we
    // were waiting, in which case dropping the buffer matches their intent.
    if (text.isNotEmpty() && isRecording) {
      onWebGesture?.invoke(WebGesture.InputText(text, nextGestureId()))
    }
  }
  // Discard any pending text when recording transitions to false so a paused-then-resumed
  // session doesn't drag the previous burst into the new recording.
  LaunchedEffect(isRecording) {
    if (!isRecording) {
      pendingWebText.clear()
    }
  }
  fun flushPendingWebText() {
    if (pendingWebText.isEmpty()) return
    val text = pendingWebText.toString()
    pendingWebText.clear()
    if (isRecording) {
      onWebGesture?.invoke(WebGesture.InputText(text, nextGestureId()))
    }
  }
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
      bytes.decodeFrameBytes()?.let { currentFrame = it }
      // Count every frame the daemon delivered, whether it was a fresh pixel change or a
      // dedup-suppressed identical frame (the daemon never sends identical bytes today, so
      // in practice this is the fresh-frame rate). Increment regardless of whether decoding
      // produced a usable bitmap — a bad decode is still a delivered frame.
      framesInWindow++
    }
  }

  /**
   * Cumulative live-typing buffer for the device-side `inputText` RPC. NEVER cleared on
   * flush — flushes always send `pendingLiveText.toString()` as one whole string, and the
   * buffer keeps growing for the duration of the typing burst until a focus-changing event
   * resets it (tap, Enter, Tab, Escape, or a Backspace that empties the buffer).
   *
   * The cumulative model is forced by the on-device runner: Android's
   * `TrailblazeAccessibilityService.inputText` dispatches via `ACTION_SET_TEXT`, which
   * **replaces** the field's text rather than appending. If we flushed per-char as
   * `inputText("h")`, `inputText("e")`, etc., every flush would overwrite the previous and
   * only the LAST char would survive — which is exactly the "typed 'hello', field shows 'o'"
   * bug Sam hit on the wasm `/devices` viewer.
   *
   * Sending cumulative content (`inputText("h")`, `inputText("he")`, `inputText("hel")`, …)
   * makes every flush idempotent against the device's field: the replace semantics now match
   * what the user actually wants, because each replacement extends the visible text. This
   * works regardless of typing speed: a fast typist coalesces to one RPC via the debounce; a
   * slow typist sends N cumulative RPCs and the field still shows the correct final string.
   *
   * Desktop didn't see this bug because its host-side driver uses
   * `MaestroAndroidUiAutomatorDriver.inputText` → `InstrumentationUtil.inputTextByTyping`,
   * which synthesizes per-char `KeyEvent`s (append semantics). The on-device runtime only
   * has the accessibility-service path, which is `ACTION_SET_TEXT`-based.
   *
   * Backspace handling: when [pendingLiveText] is non-empty, the Backspace handler pops one
   * char locally and triggers another cumulative flush — the device's field is updated by
   * the next `inputText(shorter)`. When [pendingLiveText] empties via Backspace AND we'd
   * previously flushed at least once, the empty `inputText("")` is a no-op on the device, so
   * we additionally send a Backspace keypress so the device-side field actually loses its
   * last char. See the [Key.Backspace] branch below.
   */
  val pendingLiveText = remember { StringBuilder() }
  var liveTextFlushNonce by remember { mutableStateOf(0) }
  LaunchedEffect(liveTextFlushNonce) {
    if (liveTextFlushNonce == 0) return@LaunchedEffect
    kotlinx.coroutines.delay(LIVE_TYPING_DEBOUNCE_MS)
    val text = pendingLiveText.toString()
    // Skip empty — `ACTION_SET_TEXT` is a no-op on empty strings on the on-device runner
    // (see TrailblazeAccessibilityService.inputText line ~791). The Backspace handler
    // sends an explicit pressKey("Backspace") when the buffer transitions to empty, so the
    // device doesn't end up holding stale cumulative content.
    if (text.isEmpty()) return@LaunchedEffect
    try {
      stream.inputText(text)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Throwable) {
      Console.log("[InteractiveDeviceComposable] inputText('$text') failed: ${e.message}")
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
        // Diagnostic — temporary while we debug "only 1 keypress works on wasm" reported
        // against the live device viewer. Log every key event the composable receives so
        // we can tell whether the issue is "events stop arriving" (focus problem) vs
        // "events arrive but dispatch is broken" (RPC problem).
        Console.log(
          "[InteractiveDeviceComposable] onKeyEvent type=${keyEvent.type} key=${keyEvent.key} " +
            "codePoint=${keyEvent.utf16CodePoint} meta=${keyEvent.isMetaPressed} " +
            "ctrl=${keyEvent.isCtrlPressed} alt=${keyEvent.isAltPressed}",
        )
        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

        // Ignore modifier key combos (Cmd+Tab, Ctrl+C, Alt+..., etc.)
        if (keyEvent.isMetaPressed || keyEvent.isCtrlPressed || keyEvent.isAltPressed) {
          return@onKeyEvent false
        }

        // Forward keystrokes to device for live interaction, and record when active.
        // Enter / Tab / Escape commit the current input or move focus away — the live-
        // typing burst ends here, so the cumulative buffer is no longer authoritative for
        // the next char. Clearing it makes the next char start a fresh burst rather than
        // appending to stale content from a different field.
        when (keyEvent.key) {
          Key.Enter -> {
            pendingLiveText.clear()
            launchIo {
              if (isRecording) {
                val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
                buffer?.onSpecialKey("Enter", screenshot, null)
                if (onWebGesture != null) {
                  flushPendingWebText()
                  onWebGesture(
                    WebGesture.PressKey("Enter", nextGestureId()),
                  )
                }
              }
              stream.pressKey("Enter")
            }
            true
          }
          Key.Tab -> {
            pendingLiveText.clear()
            launchIo { stream.pressKey("Tab") }
            true
          }
          Key.Escape -> {
            pendingLiveText.clear()
            launchIo {
              stream.pressKey("Escape")
              isTextInputMode = false
            }
            true
          }
          Key.Backspace -> {
            // Pop one char off the cumulative live-typing buffer if we have content tracked
            // there. The next debounced flush will re-send the (now shorter) cumulative text
            // and the device's `ACTION_SET_TEXT` replace will reflect the deletion. When the
            // buffer transitions from non-empty to EMPTY, an `inputText("")` would be a
            // no-op on the device — so we additionally dispatch a Backspace keypress so the
            // device's field actually drops its final character.
            val justEmptiedFromTrackedBurst: Boolean
            if (pendingLiveText.isNotEmpty()) {
              pendingLiveText.deleteAt(pendingLiveText.length - 1)
              liveTextFlushNonce += 1
              justEmptiedFromTrackedBurst = pendingLiveText.isEmpty()
            } else {
              justEmptiedFromTrackedBurst = false
            }
            launchIo {
              if (isRecording) {
                buffer?.onBackspace()
                if (onWebGesture != null && pendingWebText.isNotEmpty()) {
                  // Pop the last char from the pending text buffer so the next debounce
                  // flush reflects the user's actual final string.
                  pendingWebText.deleteAt(pendingWebText.length - 1)
                  pendingTextFlushNonce += 1
                }
              }
              // Send the Backspace keypress when either:
              //   (a) there's nothing in our cumulative buffer — the user is editing
              //       pre-existing field content we don't track, OR
              //   (b) the buffer just transitioned to empty — `inputText("")` is a no-op,
              //       so the device's last cumulative char wouldn't be removed otherwise.
              // In the "still has tracked content" case, the cumulative re-flush handles it
              // and dispatching a Backspace keypress would double-delete.
              if (pendingLiveText.isEmpty() || justEmptiedFromTrackedBurst) {
                stream.pressKey("Backspace")
              }
            }
            true
          }
          else -> {
            val codePoint = keyEvent.utf16CodePoint
            if (codePoint > 31) {
              val char = codePoint.toChar()
              // Append to the live-typing buffer and bump the nonce so the debounced flush
              // restarts. The LaunchedEffect above flushes the whole accumulated burst as
              // ONE `inputText("hello")` RPC instead of five racing `inputText("h"|"e"|…)`
              // — see kdoc on [pendingLiveText] for why batching matters on wasm.
              pendingLiveText.append(char)
              liveTextFlushNonce += 1
              if (isRecording) {
                launchIo {
                  val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
                  buffer?.onCharacterTyped(char, screenshot, null)
                  if (onWebGesture != null) {
                    pendingWebText.append(char)
                    // Bumping the nonce restarts the debounce timer so a continued typing
                    // burst doesn't flush mid-word.
                    pendingTextFlushNonce += 1
                  }
                }
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
        // A tap may move focus to a different (or no) editable field, so the cumulative
        // live-typing buffer is no longer authoritative for whatever the device now shows.
        // Clear it so the next keystroke starts a fresh burst rather than extending content
        // from the previous field.
        pendingLiveText.clear()
        launchIo {
          val hierarchy = tryFetch("getViewHierarchy") { stream.getViewHierarchy() }
          // Order matters: [getScreenshot] is the atomic-fetch RPC that populates the
          // stream's internal cache; [getTrailblazeNodeTree] then reads that cache so the
          // (screenshot, tree) pair we record came from the *same* on-device call. Reversing
          // these would either fetch the tree twice (once stale via cache, once fresh on
          // miss) or pair a fresh screenshot with a stale tree, breaking the atomicity
          // selector generation depends on.
          val screenshot = tryFetch("getScreenshot") { stream.getScreenshot() }
          val trailblazeTree = tryFetch("getTrailblazeNodeTree") { stream.getTrailblazeNodeTree() }
          val hitNode = hierarchy?.let { ViewHierarchyHitTester.hitTest(it, deviceX, deviceY) }
          isTextInputMode = hitNode != null && ViewHierarchyHitTester.isInputField(hitNode)
          if (isRecording) {
            if (isLongPress) {
              buffer?.onLongPress(hitNode, deviceX, deviceY, screenshot, null, trailblazeTree)
            } else {
              buffer?.onTap(hitNode, deviceX, deviceY, screenshot, null, trailblazeTree)
            }
            if (onWebGesture != null) {
              flushPendingWebText()
              // Try to generate a stable `tapOnElementBySelector` from the atomically-
              // captured tree. If the tree is null (driver doesn't expose it / capture
              // failed) or the generated selector fails round-trip validation (child
              // element would intercept at replay), fall back to raw `tapOnPoint` so the
              // recording stays faithful to the user's actual gesture. Mirrors the desktop
              // recorder's `MaestroInteractionToolFactory.createSelectorTapOrPoint` policy.
              val selectorTap = WebGestureSelectorTapBuilder.buildOrNull(
                trailblazeTree = trailblazeTree,
                deviceX = deviceX,
                deviceY = deviceY,
                longPress = isLongPress,
                id = nextGestureId(),
              )
              // Diagnostic — Sam reported "more tapOnPoint, less selector" after the
              // typing-fix daemon restart. Surface enough info to distinguish the three
              // fallback cases: (a) tree was null, (b) no node at tap point, (c) selector
              // resolved but is class-only / round-trip-invalid. Re-runs resolution on the
              // fallback path so we can see the rejected selector's description.
              if (selectorTap == null) {
                val resolution = trailblazeTree?.let {
                  xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
                    .resolveFromTap(it, deviceX, deviceY)
                }
                Console.log(
                  "[InteractiveDeviceComposable] selector fallback at " +
                    "($deviceX,$deviceY): tree=${if (trailblazeTree == null) "NULL" else "ok"} " +
                    "resolution=${
                      resolution?.let {
                        "desc='${it.selector.description()}' roundTripValid=${it.roundTripValid} " +
                          "driverMatch=${it.selector.driverMatch}"
                      } ?: "null-no-node-at-point"
                    }",
                )
              } else {
                Console.log(
                  "[InteractiveDeviceComposable] selector ok at " +
                    "($deviceX,$deviceY): ${selectorTap.description}",
                )
              }
              val gesture: WebGesture = selectorTap ?: WebGesture.Tap(
                x = deviceX,
                y = deviceY,
                longPress = isLongPress,
                id = nextGestureId(),
              )
              onWebGesture(gesture)
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
                dragStartTimeMs = Clock.System.now().toEpochMilliseconds()
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
                  val measuredDurationMs = (Clock.System.now().toEpochMilliseconds() - dragStartTimeMs)
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
                      buffer?.onSwipe(
                        startX = startDeviceX,
                        startY = startDeviceY,
                        endX = endDeviceX,
                        endY = endDeviceY,
                        screenshot = screenshot,
                        hierarchyText = null,
                        durationMs = measuredDurationMs,
                      )
                      if (onWebGesture != null) {
                        flushPendingWebText()
                        onWebGesture(
                          WebGesture.Swipe(
                            startX = startDeviceX,
                            startY = startDeviceY,
                            endX = endDeviceX,
                            endY = endDeviceY,
                            durationMs = measuredDurationMs,
                            deviceWidth = stream.deviceWidth,
                            deviceHeight = stream.deviceHeight,
                            id = nextGestureId(),
                          ),
                        )
                      }
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
                // Diagnostic — debugging "iOS clicks don't propagate" report on the wasm
                // viewer. Log composable-space offset, layout fields, and computed device
                // coords so we can tell whether the tap is being misconverted, dropped as
                // out-of-bounds (toDeviceCoords returning null), or dispatched correctly
                // but failing somewhere downstream.
                val resolved = imageLayout.toDeviceCoords(offset.x, offset.y)
                Console.log(
                  "[InteractiveDeviceComposable] onTap composable=(${offset.x},${offset.y}) " +
                    "layout=offset(${imageLayout.offsetX},${imageLayout.offsetY}) " +
                    "scale=${imageLayout.scale} device=${imageLayout.deviceWidth}x${imageLayout.deviceHeight} " +
                    "→ deviceCoords=$resolved",
                )
                // Tapping the device area must re-grab keyboard focus. Without this, focus
                // gets stuck on whichever non-device UI element the user clicked last
                // (action card, dropdown, button), and subsequent keystrokes on the Mac
                // keyboard route there instead of into onKeyEvent below — that's why
                // typed characters silently fail to record after interacting with the
                // right-hand panel.
                focusRequester.requestFocus()
                val (deviceX, deviceY) = resolved ?: return@detectTapGestures
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

      // "Device Mirroring FPS" overlay — labelled this way (not just "FPS") so users
      // don't confuse it with the device's actual screen refresh rate. This is the rate
      // frames are arriving at this viewer, which is gated by the daemon's capture
      // pipeline and any network back-pressure. BottomStart so it doesn't collide with
      // the BottomCenter Keyboard-active indicator above.
      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(8.dp)
          .background(
            color = Color(0xCC000000),
            shape = MaterialTheme.shapes.small,
          )
          .padding(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Text(
          text = "Device Mirroring FPS: $displayedMirroringFps",
          color = Color.White,
          style = MaterialTheme.typography.labelSmall,
        )
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
