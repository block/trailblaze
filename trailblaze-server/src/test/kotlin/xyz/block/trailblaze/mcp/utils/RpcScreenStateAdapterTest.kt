package xyz.block.trailblaze.mcp.utils

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

/**
 * The host end of the capture-completeness signal.
 *
 * On a host-orchestrated Android run the screen the matcher and the query tools evaluate is this
 * adapter over an RPC response, not the on-device capture itself. If the count stops at the wire,
 * every host-side consumer silently reverts to treating a capture that lost nodes as a complete
 * one — the exact false-absence this signal exists to prevent, with nothing to show it happened.
 */
class RpcScreenStateAdapterTest {

  @Test
  fun `a capture the device reported as having lost nodes reads as partial host-side`() {
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = 5))

    assertEquals(5, screenState.droppedNodeFetches)
    assertTrue(screenState.isCaptureKnownPartial)
  }

  @Test
  fun `a capture the device reported as complete reads as complete`() {
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = 0))

    assertEquals(0, screenState.droppedNodeFetches)
    assertFalse(screenState.isCaptureKnownPartial)
  }

  @Test
  fun `a device that reported nothing reads as unknown, which is not partial`() {
    // An on-device server that predates the field, or a request that asked for no tree. Unknown
    // must not be read as partial: that would make every forbidden-bearing waypoint and every
    // absence-shaped selector queries unanswerable against an older device.
    val screenState = RpcScreenStateAdapter(response(droppedNodeFetches = null))

    assertNull(screenState.droppedNodeFetches)
    assertFalse(screenState.isCaptureKnownPartial)
  }

  /**
   * Captures that never reach an LLM no longer ask the device to render the set-of-mark overlay,
   * so an LLM prompt built from one must get it drawn here. Falling back to the clean screenshot
   * would hand the model an image with none of the `[eN]` labels its element list refers to.
   */
  @Test
  fun `a response without an annotation is annotated on the host from the device screenshot`() {
    val response = responseWithSaveButton()

    val annotated = RpcScreenStateAdapter(response).annotatedScreenshotBytes

    val clean = assertNotNull(ImageIO.read(ByteArrayInputStream(screenshot)), "WebP not decodable")
    val drawn = assertNotNull(ImageIO.read(ByteArrayInputStream(annotated)))
    assertEquals(clean.width to clean.height, drawn.width to drawn.height)
    // Every pixel the overlay changed must sit on the button, scaled from device to screenshot
    // pixels. None changed means the clean screenshot came back re-encoded.
    val changed = (0 until clean.width).flatMap { x ->
      (0 until clean.height).filter { y -> clean.getRGB(x, y) != drawn.getRGB(x, y) }.map { y -> x to y }
    }
    assertTrue(changed.isNotEmpty(), "no mark drawn")
    val left = 100 * clean.width / 1080
    val top = 400 * clean.height / 2400
    val right = 600 * clean.width / 1080
    val bottom = 520 * clean.height / 2400
    val margin = 40
    val offButton = changed.filterNot { (x, y) ->
      x in (left - margin)..(right + margin) && y in (top - margin)..(bottom + margin)
    }
    assertTrue(offButton.isEmpty(), "${offButton.size} pixels changed away from the button")
  }

  /**
   * The device's renderer marks every element, and the element list the LLM reads names every one.
   * A phone's pixel dimensions must not put it in the desktop renderer's compact mode, which stops
   * at 60 marks and leaves the rest of the list pointing at nothing on the image.
   */
  @Test
  fun `a busy phone screen gets a mark on every element`() {
    val columns = 8
    val rows = 10
    val bounds = (0 until rows).flatMap { row ->
      (0 until columns).map { column ->
        TrailblazeNode.Bounds(column * 135 + 20, row * 240 + 20, column * 135 + 110, row * 240 + 170)
      }
    }

    val annotated = RpcScreenStateAdapter(responseWithButtons(bounds)).annotatedScreenshotBytes

    val clean = assertNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
    val drawn = assertNotNull(ImageIO.read(ByteArrayInputStream(annotated)))
    // Each button's left edge, halfway down: only its own outline can reach it.
    val unmarked = bounds.indices.filterNot { index ->
      val b = bounds[index]
      val x = b.left * clean.width / 1080
      val y = (b.top + b.bottom) / 2 * clean.height / 2400
      (x - 3..x + 3).any { px -> (y - 3..y + 3).any { py -> clean.getRGB(px, py) != drawn.getRGB(px, py) } }
    }
    assertEquals(emptyList(), unmarked, "buttons with no mark")
  }

  /**
   * The drawing travels in every LLM request that carries it, so it must come out in the capture's
   * own compressed format; a PNG of the same screenshot is several times larger.
   */
  @Test
  fun `the host-drawn annotation is written by the caller's encoder`() {
    val encoded = mutableListOf<java.awt.image.BufferedImage>()
    val marker = byteArrayOf(9, 9, 9)

    val annotated = RpcScreenStateAdapter(responseWithSaveButton()) { image ->
      encoded += image
      marker
    }.annotatedScreenshotBytes

    assertContentEquals(marker, annotated)
    val clean = assertNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
    assertEquals(listOf(clean.width to clean.height), encoded.map { it.width to it.height })
  }

  @Test
  fun `a screen with nothing to mark returns the screenshot without re-encoding it`() {
    val response = response(droppedNodeFetches = null).apply { screenshotBytes = screenshot }

    val annotated = RpcScreenStateAdapter(response) { error("nothing to draw, so nothing to encode") }
      .annotatedScreenshotBytes

    assertContentEquals(screenshot, annotated)
  }

  @Test
  fun `a device-rendered annotation is used as is`() {
    val deviceAnnotation = byteArrayOf(1, 2, 3)
    val response = response(droppedNodeFetches = null).apply {
      annotatedScreenshotBytes = deviceAnnotation
    }

    assertContentEquals(deviceAnnotation, RpcScreenStateAdapter(response).annotatedScreenshotBytes)
  }

  private val screenshot: ByteArray =
    javaClass.getResourceAsStream("/android_device_screenshot.webp")!!.readBytes()

  private fun responseWithSaveButton(): GetScreenStateResponse =
    responseWithButtons(listOf(TrailblazeNode.Bounds(100, 400, 600, 520)))

  private fun responseWithButtons(bounds: List<TrailblazeNode.Bounds>): GetScreenStateResponse {
    val buttons = bounds.mapIndexed { index, b ->
      TrailblazeNode(
        nodeId = index + 1L,
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.widget.Button",
          text = "Button $index",
          isClickable = true,
        ),
        bounds = b,
      )
    }
    val root = TrailblazeNode(
      nodeId = 0,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 2400),
      children = buttons,
    )
    return GetScreenStateResponse(
      viewHierarchy = ViewHierarchyTreeNode(),
      screenshotBase64 = null,
      deviceWidth = 1080,
      deviceHeight = 2400,
      trailblazeNodeTree = root,
    ).apply { screenshotBytes = screenshot }
  }

  private fun response(droppedNodeFetches: Int?) = GetScreenStateResponse(
    viewHierarchy = ViewHierarchyTreeNode(),
    screenshotBase64 = null,
    deviceWidth = 1080,
    deviceHeight = 1920,
    droppedNodeFetches = droppedNodeFetches,
  )
}
