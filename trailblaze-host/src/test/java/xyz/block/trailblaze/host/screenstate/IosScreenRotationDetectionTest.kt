package xyz.block.trailblaze.host.screenstate

import kotlin.test.Test
import kotlin.test.assertEquals
import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.capture.video.IosScreenRotation
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState.Companion.iosScreenRotation

/**
 * Tests for the iOS orientation decision in [HostMaestroDriverScreenState].
 *
 * An iOS device hands out its framebuffer in the native portrait orientation whatever the screen is
 * showing, and nothing in the pixels, the stream, or `simctl io enumerate` says which way up it is.
 * The status bar's position in the accessibility tree is the only signal there is, which is why
 * this decision is shared: the screenshot path rotates its image by it, and the session recorder
 * rotates its video by it. Two consumers, one answer — a disagreement between them would show up
 * as a report whose screenshots and video contradict each other.
 *
 * The position is read from the `0x0` overlay node the iOS tree carries: everything inside it is
 * status bar, so the first descendant with real bounds is the thing to measure.
 */
class IosScreenRotationDetectionTest {

  private val portraitDevice = DeviceInfo(
    platform = Platform.IOS,
    widthPixels = 1206,
    heightPixels = 2622,
    widthGrid = 402,
    heightGrid = 874,
  )

  private val landscapeDevice = DeviceInfo(
    platform = Platform.IOS,
    widthPixels = 2622,
    heightPixels = 1206,
    widthGrid = 874,
    heightGrid = 402,
  )

  @Test
  fun `a portrait device with its status bar at the top needs no turn`() {
    assertEquals(
      IosScreenRotation.NONE,
      iosScreenRotation(treeWithStatusBarAt(x = 100, y = 10), portraitDevice),
    )
  }

  @Test
  fun `a portrait device with its status bar at the bottom is upside down`() {
    assertEquals(
      IosScreenRotation.HALF_TURN,
      iosScreenRotation(treeWithStatusBarAt(x = 100, y = 860), portraitDevice),
    )
  }

  @Test
  fun `a landscape device is turned according to which side its status bar is on`() {
    assertEquals(
      IosScreenRotation.CLOCKWISE_90,
      iosScreenRotation(treeWithStatusBarAt(x = 20, y = 200), landscapeDevice),
      "status bar on the left half",
    )
    assertEquals(
      IosScreenRotation.COUNTER_CLOCKWISE_90,
      iosScreenRotation(treeWithStatusBarAt(x = 850, y = 200), landscapeDevice),
      "status bar on the right half",
    )
  }

  @Test
  fun `a landscape device with no status bar still gets turned`() {
    // A full-screen app hides the status bar. Guessing one of the two landscape turns is wrong half
    // the time; recording a landscape session in a portrait frame is wrong every time.
    assertEquals(
      IosScreenRotation.COUNTER_CLOCKWISE_90,
      iosScreenRotation(treeWithNoStatusBar(), landscapeDevice),
    )
  }

  @Test
  fun `a portrait device with no status bar is left alone`() {
    assertEquals(IosScreenRotation.NONE, iosScreenRotation(treeWithNoStatusBar(), portraitDevice))
  }

  @Test
  fun `the status bar is looked for inside the zero-sized overlay, not in the app window`() {
    // The app window is a sibling of the overlay and has real bounds of its own. Measuring it
    // instead of the status bar would answer with wherever the app happens to start.
    val appWindowAtTopLeft = ViewHierarchyTreeNode(nodeId = 2, dimensions = "874x402", x1 = 0, y1 = 0, x2 = 874, y2 = 402)
    val statusBarOnTheRight = ViewHierarchyTreeNode(
      nodeId = 3,
      dimensions = "0x0",
      children = listOf(ViewHierarchyTreeNode(nodeId = 4, dimensions = "40x20", x1 = 840, y1 = 190, x2 = 860, y2 = 210)),
    )
    val root = ViewHierarchyTreeNode(nodeId = 1, children = listOf(appWindowAtTopLeft, statusBarOnTheRight))

    assertEquals(
      IosScreenRotation.COUNTER_CLOCKWISE_90,
      iosScreenRotation(root, landscapeDevice),
      "the overlay's status bar decides, not the app window that precedes it",
    )
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** The iOS tree shape: a root holding a `0x0` overlay whose first real-bounds child is the clock. */
  private fun treeWithStatusBarAt(x: Int, y: Int): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
    nodeId = 1,
    children = listOf(
      ViewHierarchyTreeNode(
        nodeId = 2,
        dimensions = "0x0",
        children = listOf(
          ViewHierarchyTreeNode(nodeId = 3, dimensions = "40x20", x1 = x - 20, y1 = y - 10, x2 = x + 20, y2 = y + 10),
        ),
      ),
    ),
  )

  private fun treeWithNoStatusBar(): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
    nodeId = 1,
    children = listOf(ViewHierarchyTreeNode(nodeId = 2, dimensions = "402x874", x1 = 0, y1 = 0, x2 = 402, y2 = 874)),
  )
}
