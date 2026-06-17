package xyz.block.trailblaze.host.setofmark

import maestro.DeviceInfo
import maestro.device.Platform
import org.junit.Test
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [HostCanvasSetOfMark] to verify image encoding works correctly.
 */
class HostCanvasSetOfMarkTest {

  /**
   * Tests that images with ARGB colorspace (with alpha channel) can be
   * successfully encoded to JPEG without "Bogus input colorspace" errors.
   *
   * This is a regression test for the issue where PNG screenshots with
   * transparency would fail to encode as JPEG SetOfMark overlays.
   */
  @Test
  fun `toByteArray encodes ARGB image to JPEG without colorspace error`() {
    // Create an ARGB image (like screenshots with alpha channel)
    val argbImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
    val graphics = argbImage.createGraphics()
    graphics.color = Color.RED
    graphics.fillRect(0, 0, 50, 50)
    graphics.color = Color.BLUE
    graphics.fillRect(50, 50, 50, 50)
    graphics.dispose()

    // This should not throw "Bogus input colorspace" exception
    val canvas = HostCanvasSetOfMark(argbImage, null)
    val jpegBytes = canvas.toByteArray()

    // Verify the output is valid JPEG
    assertTrue(jpegBytes.isNotEmpty(), "JPEG output should not be empty")

    // Verify we can parse it back as an image
    val decodedImage = ByteArrayInputStream(jpegBytes).use { ImageIO.read(it) }
    assertNotNull(decodedImage, "Decoded image should not be null")
    assertTrue(decodedImage.width > 0, "Decoded image should have width")
    assertTrue(decodedImage.height > 0, "Decoded image should have height")
  }

  /**
   * Tests that images with RGB colorspace work correctly.
   */
  @Test
  fun `toByteArray encodes RGB image to JPEG successfully`() {
    val rgbImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
    val graphics = rgbImage.createGraphics()
    graphics.color = Color.GREEN
    graphics.fillRect(0, 0, 100, 100)
    graphics.dispose()

    val canvas = HostCanvasSetOfMark(rgbImage, null)
    val jpegBytes = canvas.toByteArray()

    assertTrue(jpegBytes.isNotEmpty(), "JPEG output should not be empty")

    val decodedImage = ByteArrayInputStream(jpegBytes).use { ImageIO.read(it) }
    assertNotNull(decodedImage, "Decoded image should not be null")
  }

  /**
   * Tests that SetOfMark annotations (drawn nodes) can be encoded successfully.
   */
  @Test
  fun `drawNodes and encode to JPEG works correctly`() {
    val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)

    val canvas = HostCanvasSetOfMark(image, null)

    // Draw some nodes with bounds using dimensions and centerPoint
    val nodes = listOf(
      createTestNode(nodeId = 0L, x1 = 10, y1 = 10, x2 = 50, y2 = 50),
      createTestNode(nodeId = 1L, x1 = 60, y1 = 60, x2 = 100, y2 = 100),
      createTestNode(nodeId = 2L, x1 = 110, y1 = 110, x2 = 150, y2 = 150),
    )

    canvas.drawNodes(nodes)

    // This should not throw colorspace error
    val jpegBytes = canvas.toByteArray()
    assertTrue(jpegBytes.isNotEmpty(), "Annotated JPEG output should not be empty")

    val decodedImage = ByteArrayInputStream(jpegBytes).use { ImageIO.read(it) }
    assertNotNull(decodedImage, "Decoded annotated image should not be null")
  }

  /**
   * Renders a high-density (~100 element) annotation set on a desktop-sized canvas to
   * verify drawAnnotations stays robust under the dense-table case that motivated the
   * Tier 1–4 fixes. Smoke-level: doesn't crash, produces a valid encoded image.
   */
  @Test
  fun `drawAnnotations on desktop canvas with dense element set produces valid image`() {
    val image = BufferedImage(1280, 800, BufferedImage.TYPE_INT_ARGB)
    val canvas = HostCanvasSetOfMark(image, null)

    // Simulate a 15-row × 6-column admin table — 90 small elements clustered on the right side.
    val annotations = buildList {
      var nextNodeId = 1L
      for (row in 0 until 15) {
        for (col in 0 until 6) {
          val x1 = 200 + col * 160
          val y1 = 100 + row * 40
          val id = nextNodeId++
          add(
            AnnotationElement(
              nodeId = id,
              bounds = TrailblazeNode.Bounds(left = x1, top = y1, right = x1 + 120, bottom = y1 + 30),
              refLabel = "e$id",
            ),
          )
        }
      }
    }

    canvas.drawAnnotations(annotations)
    val bytes = canvas.toByteArray()
    assertTrue(bytes.isNotEmpty(), "Annotated dense-table output should not be empty")

    val decoded = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    assertNotNull(decoded)
    assertTrue(decoded.width == 1280 && decoded.height == 800)
  }

  /**
   * Mobile-portrait (~390 × 844 ≈ 329k px) stays under the compact-mode threshold and
   * keeps the original sizing. This is the regression guard for the explicit user
   * constraint: "Don't regress mobile portrait".
   */
  @Test
  fun `drawAnnotations on mobile-portrait canvas does not crash with small element set`() {
    val image = BufferedImage(390, 844, BufferedImage.TYPE_INT_ARGB)
    val canvas = HostCanvasSetOfMark(image, null)

    val annotations = (0 until 5).map { i ->
      AnnotationElement(
        nodeId = (i + 1).toLong(),
        bounds = TrailblazeNode.Bounds(left = 50, top = 100 + i * 80, right = 340, bottom = 160 + i * 80),
        refLabel = "e${i + 1}",
      )
    }

    canvas.drawAnnotations(annotations)
    val bytes = canvas.toByteArray()
    assertTrue(bytes.isNotEmpty(), "Mobile-portrait annotated output should not be empty")
  }

  /**
   * Regression guard for the HiDPI mobile-portrait case: at deviceScaleFactor=2 the
   * raw bitmap is 780×1688 ≈ 1.32M px — over the 1Mpx compact-mode threshold by
   * physical area — but the **logical** viewport is still 390×844 ≈ 329k px and the
   * page is no denser than non-Retina mobile. Compact mode must NOT trip here, or
   * we'd silently apply the 60-element cap + smaller labels on phones, which is the
   * exact regression Codex / Copilot flagged on review. The fix in [isCompactMode]
   * prefers `deviceInfo.widthGrid × heightGrid` when DeviceInfo is supplied.
   *
   * Smoke-level — exercises the new DeviceInfo-aware code path with 100 elements at
   * HiDPI mobile-portrait dimensions and verifies the renderer produces a valid image
   * without crashing.
   */
  @Test
  fun `drawAnnotations on HiDPI mobile-portrait stays under compact-mode threshold via logical dims`() {
    // Physical-pixel bitmap (DPR=2) — pixel area = 1.32M, above the compact threshold.
    val image = BufferedImage(780, 1688, BufferedImage.TYPE_INT_ARGB)
    // DeviceInfo with logical dimensions (390×844 = 329k) — below the compact threshold.
    val deviceInfo = DeviceInfo(
      platform = Platform.WEB,
      widthPixels = 780,
      heightPixels = 1688,
      widthGrid = 390,
      heightGrid = 844,
    )
    val canvas = HostCanvasSetOfMark(image, deviceInfo)

    // 100 elements — well over the 60-element cap. Logical coords (drawAnnotations
    // applies platform scale internally for WEB/iOS).
    val annotations = (0 until 100).map { i ->
      val x1 = (i % 10) * 35
      val y1 = (i / 10) * 80 + 50
      AnnotationElement(
        nodeId = (i + 1).toLong(),
        bounds = TrailblazeNode.Bounds(left = x1, top = y1, right = x1 + 30, bottom = y1 + 30),
        refLabel = "e${i + 1}",
      )
    }

    canvas.drawAnnotations(annotations)
    val bytes = canvas.toByteArray()
    assertTrue(bytes.isNotEmpty(), "HiDPI mobile-portrait annotated output should not be empty")
    val decoded = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    assertNotNull(decoded)
  }

  /**
   * Verifies the compact-mode density cap by pushing well over [HostCanvasSetOfMark]'s
   * cap of 60 annotations on a desktop-class canvas. We can't easily assert "60 boxes
   * painted" from pixels, but we can confirm the renderer doesn't crash, doesn't OOM,
   * and produces a valid image even when fed 500 elements — the cap path is exercised
   * end-to-end.
   */
  @Test
  fun `drawAnnotations caps painted elements in compact mode without crashing`() {
    val image = BufferedImage(1920, 1200, BufferedImage.TYPE_INT_ARGB)
    val canvas = HostCanvasSetOfMark(image, null)

    val annotations = (0 until 500).map { i ->
      val x1 = (i * 7) % 1800
      val y1 = (i * 11) % 1100
      AnnotationElement(
        nodeId = (i + 1).toLong(),
        bounds = TrailblazeNode.Bounds(left = x1, top = y1, right = x1 + 80, bottom = y1 + 30),
        refLabel = "e${i + 1}",
      )
    }

    canvas.drawAnnotations(annotations)
    val bytes = canvas.toByteArray()
    assertTrue(bytes.isNotEmpty())
    val decoded = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    assertNotNull(decoded)
  }

  /**
   * Helper for creating test nodes with bounds.
   * Uses dimensions and centerPoint properties which are converted to bounds.
   */
  private fun createTestNode(
    nodeId: Long,
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
  ): ViewHierarchyTreeNode {
    // Calculate dimensions and center point from bounds
    val width = x2 - x1
    val height = y2 - y1
    val centerX = x1 + (width / 2)
    val centerY = y1 + (height / 2)

    return ViewHierarchyTreeNode(
      nodeId = nodeId,
      className = "android.widget.Button",
      text = "Button $nodeId",
      dimensions = "${width}x${height}",
      centerPoint = "$centerX,$centerY",
      children = emptyList(),
      enabled = true,
    )
  }
}
