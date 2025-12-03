package xyz.block.trailblaze.viewmatcher.search

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import kotlin.test.assertEquals

/**
 * Tests for IndexCalculator utility functions.
 */
class IndexCalculatorTest {

  companion object {
    fun createNode(
      nodeId: Long,
      text: String? = null,
      centerX: Int = 500,
      centerY: Int = 500,
      width: Int = 100,
      height: Int = 100,
    ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = text,
      centerPoint = "$centerX,$centerY",
      dimensions = "${width}x$height",
    )
  }

  @Test
  fun `findIndexAtCenterPoint finds exact match`() {
    // Setup: Three nodes at different positions
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 100),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
      createNode(nodeId = 3, centerX = 500, centerY = 300),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "500,200",
      centerPointTolerancePx = 1,
    )

    assertEquals(1, result, "Should find middle node at index 1")
  }

  @Test
  fun `findIndexAtCenterPoint finds match within tolerance`() {
    // Setup: Node at 500,200 but we're looking for 501,201 (within 1px tolerance)
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 100),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
      createNode(nodeId = 3, centerX = 500, centerY = 300),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "501,201",
      centerPointTolerancePx = 1,
    )

    assertEquals(1, result, "Should find node within 1px tolerance")
  }

  @Test
  fun `findIndexAtCenterPoint returns -1 when no match found`() {
    // Setup: Three nodes at different positions
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 100),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
      createNode(nodeId = 3, centerX = 500, centerY = 300),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "700,700",
      centerPointTolerancePx = 1,
    )

    assertEquals(-1, result, "Should return -1 when no match found")
  }

  @Test
  fun `findIndexAtCenterPoint returns -1 when outside tolerance`() {
    // Setup: Node at 500,200 but we're looking for 503,203 (outside 1px tolerance)
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 100),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
      createNode(nodeId = 3, centerX = 500, centerY = 300),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "503,203",
      centerPointTolerancePx = 1,
    )

    assertEquals(-1, result, "Should return -1 when outside tolerance")
  }

  @Test
  fun `findIndexAtCenterPoint finds first match when multiple at same location`() {
    // Setup: Two nodes at the same center point
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 200),
      createNode(nodeId = 2, centerX = 500, centerY = 200), // Same location
      createNode(nodeId = 3, centerX = 500, centerY = 300),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "500,200",
      centerPointTolerancePx = 1,
    )

    assertEquals(0, result, "Should return first match when multiple at same location")
  }

  @Test
  fun `findIndexAtCenterPoint handles null target center point`() {
    val sortedMatches = listOf(
      createNode(nodeId = 1, centerX = 500, centerY = 100),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = null,
      centerPointTolerancePx = 1,
    )

    assertEquals(-1, result, "Should return -1 when target center point is null")
  }

  @Test
  fun `findIndexAtCenterPoint handles empty list`() {
    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = emptyList(),
      targetCenterPoint = "500,200",
      centerPointTolerancePx = 1,
    )

    assertEquals(-1, result, "Should return -1 for empty list")
  }

  @Test
  fun `findIndexAtCenterPoint handles nodes without center point`() {
    val sortedMatches = listOf(
      ViewHierarchyTreeNode(
        nodeId = 1,
        text = "Node without center",
        centerPoint = null,
        dimensions = null,
      ),
      createNode(nodeId = 2, centerX = 500, centerY = 200),
    )

    val result = IndexCalculator.findIndexAtCenterPoint(
      sortedMatches = sortedMatches,
      targetCenterPoint = "500,200",
      centerPointTolerancePx = 1,
    )

    assertEquals(1, result, "Should skip nodes without center point and find valid match")
  }
}
