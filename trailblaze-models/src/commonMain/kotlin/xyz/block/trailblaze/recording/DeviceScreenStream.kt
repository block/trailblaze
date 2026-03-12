package xyz.block.trailblaze.recording

import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Abstraction over a connected device's screen. Implementations stream frames for display
 * and forward user input (tap, swipe, text) to the device. The interactive recording UI
 * programs against this interface so the same composable works for Android, iOS, Playwright,
 * and Compose Desktop targets.
 */
interface DeviceScreenStream {
  /** Continuous flow of screenshot frames (PNG or JPEG bytes) for display. */
  fun frames(): Flow<ByteArray>

  /** Forward a tap at device coordinates. */
  suspend fun tap(x: Int, y: Int)

  /** Forward a long press at device coordinates. */
  suspend fun longPress(x: Int, y: Int)

  /** Forward a swipe gesture between two points. */
  suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int)

  /** Forward text input to the currently focused field. */
  suspend fun inputText(text: String)

  /** Forward a special key press (Enter, Backspace, Tab, Escape, etc.). */
  suspend fun pressKey(key: String)

  /** Get the current view hierarchy for hit-testing. */
  suspend fun getViewHierarchy(): ViewHierarchyTreeNode

  /** Capture a screenshot (for recording, separate from the display stream). */
  suspend fun getScreenshot(): ByteArray

  /** Device screen width in device pixels. */
  val deviceWidth: Int

  /** Device screen height in device pixels. */
  val deviceHeight: Int
}
