package xyz.block.trailblaze.viewmatcher.search

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewmatcher.models.RelativePosition
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SpatialRelationshipValidator utility for validating spatial relationships.
 */
class SpatialRelationshipValidatorTest {

  companion object {
    fun createNode(
      nodeId: Long,
      centerX: Int,
      centerY: Int,
      width: Int,
      height: Int,
    ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = nodeId,
      centerPoint = "$centerX,$centerY",
      dimensions = "${width}x$height",
    )

    fun createBounds(x1: Int, y1: Int, x2: Int, y2: Int): ViewHierarchyFilter.Bounds = ViewHierarchyFilter.Bounds(x1 = x1, y1 = y1, x2 = x2, y2 = y2)
  }

  @Test
  fun `ABOVE validates target y2 is less than or equal to reference y1`() {
    // Target: [0, 0] to [100, 100]
    // Reference: [0, 200] to [100, 300] (clearly below target)
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 250, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 0, x2 = 100, y2 = 100)

    assertTrue(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.ABOVE,
      ),
      "Target should be above reference",
    )
  }

  @Test
  fun `ABOVE returns false when target overlaps or is below reference`() {
    // Target: [0, 200] to [100, 300] (below reference)
    // Reference: [0, 0] to [100, 100]
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 200, x2 = 100, y2 = 300)

    assertFalse(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.ABOVE,
      ),
      "Target below reference should not satisfy ABOVE",
    )
  }

  @Test
  fun `BELOW validates target y1 is greater than or equal to reference y2`() {
    // Target: [0, 200] to [100, 300]
    // Reference: [0, 0] to [100, 100] (clearly above target)
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 200, x2 = 100, y2 = 300)

    assertTrue(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.BELOW,
      ),
      "Target should be below reference",
    )
  }

  @Test
  fun `BELOW returns false when target overlaps or is above reference`() {
    // Target: [0, 0] to [100, 100] (above reference)
    // Reference: [0, 200] to [100, 300]
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 250, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 0, x2 = 100, y2 = 100)

    assertFalse(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.BELOW,
      ),
      "Target above reference should not satisfy BELOW",
    )
  }

  @Test
  fun `LEFT_OF validates target x2 is less than or equal to reference x1`() {
    // Target: [0, 0] to [100, 100]
    // Reference: [200, 0] to [300, 100] (clearly to the right)
    val referenceNode = createNode(nodeId = 1, centerX = 250, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 0, x2 = 100, y2 = 100)

    assertTrue(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.LEFT_OF,
      ),
      "Target should be left of reference",
    )
  }

  @Test
  fun `LEFT_OF returns false when target overlaps or is right of reference`() {
    // Target: [200, 0] to [300, 100] (right of reference)
    // Reference: [0, 0] to [100, 100]
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 200, y1 = 0, x2 = 300, y2 = 100)

    assertFalse(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.LEFT_OF,
      ),
      "Target right of reference should not satisfy LEFT_OF",
    )
  }

  @Test
  fun `RIGHT_OF validates target x1 is greater than or equal to reference x2`() {
    // Target: [200, 0] to [300, 100]
    // Reference: [0, 0] to [100, 100] (clearly to the left)
    val referenceNode = createNode(nodeId = 1, centerX = 50, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 200, y1 = 0, x2 = 300, y2 = 100)

    assertTrue(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.RIGHT_OF,
      ),
      "Target should be right of reference",
    )
  }

  @Test
  fun `RIGHT_OF returns false when target overlaps or is left of reference`() {
    // Target: [0, 0] to [100, 100] (left of reference)
    // Reference: [200, 0] to [300, 100]
    val referenceNode = createNode(nodeId = 1, centerX = 250, centerY = 50, width = 100, height = 100)
    val targetBounds = createBounds(x1 = 0, y1 = 0, x2 = 100, y2 = 100)

    assertFalse(
      SpatialRelationshipValidator.validateSpatialRelationship(
        referenceNode = referenceNode,
        targetBounds = targetBounds,
        relationship = RelativePosition.RIGHT_OF,
      ),
      "Target left of reference should not satisfy RIGHT_OF",
    )
  }
}
