package xyz.block.trailblaze.host.setofmark

import org.junit.Test
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
