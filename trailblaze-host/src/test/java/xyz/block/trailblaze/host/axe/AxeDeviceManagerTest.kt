package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.host.ios.IosDriverAction

/**
 * Unit tests for pure helpers on [AxeDeviceManager]. The resolver-poll loops
 * (`executeTapOnElement` / `executeAssertVisible` / `executeAssertNotVisible`) depend on
 * `AxeCli` subprocess calls and need a stubbable seam to unit-test — tracked separately.
 */
class AxeDeviceManagerTest {

  // Typical portrait iPhone 16 Pro sim: 402x874 in points.
  private val width = 402
  private val height = 874

  @Test
  fun `swipe UP starts from center and ends 10% from the top`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(IosDriverAction.Direction.UP, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals(width / 2, startX, "startX is center")
    assertEquals(height / 2, startY, "startY is center")
    assertEquals(width / 2, endX, "endX stays on center column")
    assertEquals((height * 0.1).toInt(), endY, "endY is 10% from top for an UP swipe")
  }

  @Test
  fun `swipe DOWN starts from center and ends 10% from the bottom`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(IosDriverAction.Direction.DOWN, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals(width / 2, startX)
    assertEquals(height / 2, startY)
    assertEquals(width / 2, endX)
    assertEquals((height * 0.9).toInt(), endY, "endY is 90% down for a DOWN swipe")
  }

  @Test
  fun `swipe LEFT starts from 90% right and ends 10% from left`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(IosDriverAction.Direction.LEFT, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals((width * 0.9).toInt(), startX, "startX is 90% across for a LEFT swipe")
    assertEquals(height / 2, startY)
    assertEquals((width * 0.1).toInt(), endX, "endX is 10% across")
    assertEquals(height / 2, endY)
  }

  @Test
  fun `swipe RIGHT starts from 10% left and ends 90% across`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(IosDriverAction.Direction.RIGHT, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals((width * 0.1).toInt(), startX)
    assertEquals(height / 2, startY)
    assertEquals((width * 0.9).toInt(), endX)
    assertEquals(height / 2, endY)
  }

  @Test
  fun `swipe math handles small simulator dimensions without going negative`() {
    // Regression guard: at 100x100 both 10% and 90% endpoints must stay in bounds.
    val all = IosDriverAction.Direction.entries.map { dir ->
      dir to AxeDeviceManager.computeDirectionalSwipeCoords(dir, 100, 100)
    }
    for ((dir, coords) in all) {
      for (v in coords) {
        assert(v in 0..100) { "$dir produced out-of-bounds coord: $v" }
      }
    }
  }

  // --- pixelFromPercent (relative tap / swipe percent → pixel conversion) ---

  @Test
  fun `in-range percents convert proportionally`() {
    assertEquals(width / 2, AxeDeviceManager.pixelFromPercent(50.0, width))
    assertEquals(0, AxeDeviceManager.pixelFromPercent(0.0, width))
  }

  @Test
  fun `100 percent clamps to the last on-screen pixel`() {
    // Unclamped, 100% would yield `deviceWidth` — one past the edge.
    assertEquals(width - 1, AxeDeviceManager.pixelFromPercent(100.0, width))
  }

  @Test
  fun `out-of-range percents clamp to the device bounds`() {
    assertEquals(0, AxeDeviceManager.pixelFromPercent(-10.0, width))
    assertEquals(width - 1, AxeDeviceManager.pixelFromPercent(150.0, width))
  }

  // --- isTreeReady (launch / open-link readiness poll) ---

  private fun node(children: List<TrailblazeNode> = emptyList(), label: String? = null) =
    TrailblazeNode(driverDetail = DriverNodeDetail.IosAxe(label = label), children = children)

  @Test
  fun `a null tree (capture failed) is never ready`() {
    assertFalse(AxeDeviceManager.isTreeReady(null))
  }

  @Test
  fun `a populated tree of blank containers is not ready`() {
    // The mid-render state the gate exists to wait out: structure present, no content yet.
    val root = node(children = listOf(node(), node(), node()))
    assertFalse(AxeDeviceManager.isTreeReady(root))
  }

  @Test
  fun `a tree with enough content-bearing nodes is ready`() {
    val root = node(
      children = listOf(node(label = "Sign In"), node(label = "Welcome"), node(label = "Settings")),
    )
    assertTrue(AxeDeviceManager.isTreeReady(root))
  }

  @Test
  fun `a tree with too few content-bearing nodes is not ready`() {
    val root = node(children = listOf(node(label = "Loading"), node(), node()))
    assertFalse(AxeDeviceManager.isTreeReady(root))
  }

  @Test
  fun `a populated tree of blank-string containers is not ready`() {
    // Mid-render, AXe can report label as "" or whitespace before real text lands — blank
    // is not content, exactly as the readiness kdoc promises.
    val root = node(children = listOf(node(label = ""), node(label = "  "), node(label = "")))
    assertFalse(AxeDeviceManager.isTreeReady(root))
  }

  // --- isTreeReadyAfterAction (pre-launch baseline) ---

  private fun readyTree(vararg labels: String) =
    node(children = labels.map { node(label = it) })

  @Test
  fun `a ready tree identical to the pre-action baseline is not ready-after-action`() {
    // The stale-tree trap: after launchApp the PREVIOUS screen's tree is already "ready",
    // so a baseline-less gate returns immediately and selectors resolve against it.
    val preLaunch = readyTree("Sign In", "Welcome", "Settings")
    val baseline = AxeDeviceManager.treeContentSignature(preLaunch)
    assertFalse(AxeDeviceManager.isTreeReadyAfterAction(readyTree("Sign In", "Welcome", "Settings"), baseline))
  }

  @Test
  fun `a ready tree that differs from the baseline is ready-after-action`() {
    val baseline = AxeDeviceManager.treeContentSignature(readyTree("Sign In", "Welcome", "Settings"))
    assertTrue(AxeDeviceManager.isTreeReadyAfterAction(readyTree("Home", "Activity", "Money"), baseline))
  }

  @Test
  fun `without a baseline a ready tree is ready-after-action`() {
    assertTrue(AxeDeviceManager.isTreeReadyAfterAction(readyTree("Home", "Activity", "Money"), null))
  }

  @Test
  fun `a changed but still-blank tree is not ready-after-action`() {
    val baseline = AxeDeviceManager.treeContentSignature(readyTree("Sign In", "Welcome", "Settings"))
    assertFalse(AxeDeviceManager.isTreeReadyAfterAction(node(children = listOf(node(), node())), baseline))
  }

  @Test
  fun `a null capture is never ready-after-action`() {
    assertFalse(AxeDeviceManager.isTreeReadyAfterAction(null, null))
  }

  @Test
  fun `the tree signature ignores capture-assigned nodeIds`() {
    // nodeIds are counter-assigned per capture, so two captures of the SAME screen carry
    // different ids — the signature must still read them as unchanged.
    val a = TrailblazeNode(
      nodeId = 0,
      driverDetail = DriverNodeDetail.IosAxe(),
      children = listOf(TrailblazeNode(nodeId = 1, driverDetail = DriverNodeDetail.IosAxe(label = "Home"))),
    )
    val b = TrailblazeNode(
      nodeId = 7,
      driverDetail = DriverNodeDetail.IosAxe(),
      children = listOf(TrailblazeNode(nodeId = 8, driverDetail = DriverNodeDetail.IosAxe(label = "Home"))),
    )
    assertEquals(AxeDeviceManager.treeContentSignature(a), AxeDeviceManager.treeContentSignature(b))
  }

  // --- off-viewport failure reporting ---

  @Test
  fun `off-viewport failure description distinguishes in-tree from on-screen`() {
    val message = AxeDeviceManager.offViewportDescription(
      bounds = TrailblazeNode.Bounds(left = 16, top = 1036, right = 386, bottom = 1080),
      viewportWidth = 402,
      viewportHeight = 874,
    )
    assertTrue(message.contains("accessibility tree"), "must say the element IS in the tree")
    assertTrue(message.contains("outside the viewport"), "must say it is NOT on screen")
    assertTrue(message.contains("402x874"), "must report the viewport for triage")
    assertTrue(message.contains("1036"), "must report the element bounds for triage")
  }

  // IntArray destructuring — mirrors the private extensions in AxeDeviceManager so the
  // test reads naturally without reaching into internals.
  private operator fun IntArray.component1() = this[0]
  private operator fun IntArray.component2() = this[1]
  private operator fun IntArray.component3() = this[2]
  private operator fun IntArray.component4() = this[3]
}
