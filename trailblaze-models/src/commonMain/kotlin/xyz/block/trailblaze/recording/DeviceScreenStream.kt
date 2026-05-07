package xyz.block.trailblaze.recording

import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.api.TrailblazeNode
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

  /**
   * Forward a swipe gesture between two points.
   *
   * @param durationMs How long the swipe takes on the device, end-to-end. Pass the actual
   *   wall-clock duration of the user's gesture on the host so the device-side swipe
   *   matches their intent — a fast flick stays a flick, a slow drag stays a drag. `null`
   *   means "let the underlying driver pick a default" (typically Maestro's 400ms).
   */
  suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long? = null)

  /** Forward text input to the currently focused field. */
  suspend fun inputText(text: String)

  /** Forward a special key press (Enter, Backspace, Tab, Escape, etc.). */
  suspend fun pressKey(key: String)

  /** Get the current view hierarchy for hit-testing. */
  suspend fun getViewHierarchy(): ViewHierarchyTreeNode

  /**
   * Get the current accessibility tree as a [TrailblazeNode] tree.
   *
   * This is the richer, driver-typed tree consumed by [xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator]
   * to convert a tap coordinate into a stable, semantically meaningful selector during recording.
   * Returns null when the underlying driver doesn't expose an accessibility tree
   * (e.g. Compose desktop, or platforms not yet wired).
   */
  suspend fun getTrailblazeNodeTree(): TrailblazeNode? = null

  /** Capture a screenshot (for recording, separate from the display stream). */
  suspend fun getScreenshot(): ByteArray

  /** Device screen width in device pixels. */
  val deviceWidth: Int

  /** Device screen height in device pixels. */
  val deviceHeight: Int
}
