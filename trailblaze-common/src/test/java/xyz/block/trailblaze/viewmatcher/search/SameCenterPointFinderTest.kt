package xyz.block.trailblaze.viewmatcher.search

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for SameCenterPointFinder utility for finding selectors at the same tap location.
 */
class SameCenterPointFinderTest {

  companion object {
    const val DEVICE_WIDTH = 1080
    const val DEVICE_HEIGHT = 2400

    fun createNode(
      nodeId: Long,
      text: String? = null,
      resourceId: String? = null,
      centerX: Int = 500,
      centerY: Int = 500,
      width: Int = 100,
      height: Int = 100,
      children: List<ViewHierarchyTreeNode> = emptyList(),
    ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = text,
      resourceId = resourceId,
      centerPoint = "$centerX,$centerY",
      dimensions = "${width}x$height",
      children = children,
    )
  }

  @Test
  fun `findFromDescendants returns null when no descendants at same center`() {
    val target = createNode(nodeId = 1, text = "Target", centerX = 500, centerY = 500)
    val root = createNode(nodeId = 0, children = listOf(target))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNull(result, "Should return null when target has no descendants")
  }

  @Test
  fun `findFromDescendants finds unique descendant at same center point`() {
    // Setup: Target with duplicate text "ITEM" but has unique descendant "UniqueChild" at same location
    val descendant = createNode(nodeId = 2, text = "UniqueChild", centerX = 500, centerY = 500)
    val target = createNode(
      nodeId = 1,
      text = "ITEM",
      centerX = 500,
      centerY = 500,
      children = listOf(descendant),
    )
    val otherNode = createNode(nodeId = 3, text = "ITEM", centerX = 600, centerY = 600)
    val root = createNode(nodeId = 0, children = listOf(target, otherNode))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNotNull(result, "Should find descendant at same center point")
    assertEquals("UniqueChild", result.selector.textRegex)
  }

  @Test
  fun `findFromDescendants respects center point tolerance`() {
    // Setup: Descendant is 1px away (within tolerance)
    val descendant = createNode(nodeId = 2, text = "NearbyChild", centerX = 501, centerY = 501)
    val target = createNode(
      nodeId = 1,
      text = "Target",
      centerX = 500,
      centerY = 500,
      children = listOf(descendant),
    )
    val root = createNode(nodeId = 0, children = listOf(target))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNotNull(result, "Should find descendant within 1px tolerance")
    assertEquals("NearbyChild", result.selector.textRegex)
  }

  @Test
  fun `findFromDescendants prefers deepest leaf elements`() {
    // Setup: Multiple descendants at same center, deepest one should be preferred
    val deepestChild = createNode(nodeId = 4, text = "DeepestChild", centerX = 500, centerY = 500)
    val middleChild = createNode(
      nodeId = 3,
      text = "MiddleChild",
      centerX = 500,
      centerY = 500,
      children = listOf(deepestChild),
    )
    val target = createNode(
      nodeId = 1,
      text = "Target",
      centerX = 500,
      centerY = 500,
      children = listOf(middleChild),
    )
    val root = createNode(nodeId = 0, children = listOf(target))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNotNull(result, "Should find a descendant")
    assertEquals("DeepestChild", result.selector.textRegex, "Should prefer deepest leaf element")
  }

  @Test
  fun `findFromAncestors finds unique ancestor at same center point`() {
    // Setup: Target with duplicate text but ancestor has unique ID at same location
    val target = createNode(nodeId = 2, text = "Button", centerX = 500, centerY = 500)
    val parent = createNode(
      nodeId = 1,
      resourceId = "unique_container",
      centerX = 500,
      centerY = 500,
      children = listOf(target),
    )
    val root = createNode(nodeId = 0, children = listOf(parent))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromAncestors(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNotNull(result, "Should find ancestor at same center point")
    assertEquals("unique_container", result.selector.idRegex)
  }

  @Test
  fun `findFromAncestors respects maxLevels parameter`() {
    // Setup: Ancestor 4 levels up should not be found when maxLevels=3
    val target = createNode(nodeId = 4, text = "Target", centerX = 500, centerY = 500)
    val level1 = createNode(nodeId = 3, centerX = 500, centerY = 500, children = listOf(target))
    val level2 = createNode(nodeId = 2, centerX = 500, centerY = 500, children = listOf(level1))
    val level3 = createNode(nodeId = 1, centerX = 500, centerY = 500, children = listOf(level2))
    val level4 = createNode(
      nodeId = 0,
      text = "FarAncestor",
      centerX = 500,
      centerY = 500,
      children = listOf(level3),
    )

    val context = SelectorSearchContext(
      root = level4,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    // Should not find level4 ancestor when maxLevels=3
    val result = SameCenterPointFinder.findFromAncestors(
      context = context,
      targetCenterPoint = "500,500",
      maxLevels = 3,
    )

    // Since none of the first 3 ancestors have unique properties, result should be null
    assertNull(result, "Should respect maxLevels limit")
  }

  @Test
  fun `findFromAncestors prefers closest ancestors`() {
    // Setup: Multiple ancestors at same center, closest should be tried first
    val target = createNode(nodeId = 3, text = "DuplicateText", centerX = 500, centerY = 500)
    val parent = createNode(
      nodeId = 2,
      text = "ClosestAncestor",
      centerX = 500,
      centerY = 500,
      children = listOf(target),
    )
    val grandparent = createNode(
      nodeId = 1,
      text = "FartherAncestor",
      centerX = 500,
      centerY = 500,
      children = listOf(parent),
    )
    val root = createNode(nodeId = 0, children = listOf(grandparent))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromAncestors(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNotNull(result, "Should find an ancestor")
    assertEquals("ClosestAncestor", result.selector.textRegex, "Should prefer closest ancestor")
  }

  @Test
  fun `findFromDescendants returns null when descendant has no identifying properties`() {
    // Setup: Descendant at same center but has no text, id, or state properties
    val emptyDescendant = createNode(nodeId = 2, text = null, resourceId = null, centerX = 500, centerY = 500)
    val target = createNode(
      nodeId = 1,
      text = "Target",
      centerX = 500,
      centerY = 500,
      children = listOf(emptyDescendant),
    )
    val root = createNode(nodeId = 0, children = listOf(target))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
    )

    assertNull(result, "Should return null when descendant has no identifying properties")
  }

  @Test
  fun `findFromDescendants includes description when requested`() {
    val descendant = createNode(nodeId = 2, text = "Child", centerX = 500, centerY = 500)
    val target = createNode(
      nodeId = 1,
      text = "Target",
      centerX = 500,
      centerY = 500,
      children = listOf(descendant),
    )
    val root = createNode(nodeId = 0, children = listOf(target))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromDescendants(
      context = context,
      targetCenterPoint = "500,500",
      includeDescription = true,
    )

    assertNotNull(result)
    assert(result.description.contains("descendant at same center")) {
      "Description should mention descendant, got: ${result.description}"
    }
  }

  @Test
  fun `findFromAncestors includes description when requested`() {
    val target = createNode(nodeId = 2, text = "Target", centerX = 500, centerY = 500)
    val parent = createNode(
      nodeId = 1,
      text = "Parent",
      centerX = 500,
      centerY = 500,
      children = listOf(target),
    )
    val root = createNode(nodeId = 0, children = listOf(parent))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = SameCenterPointFinder.findFromAncestors(
      context = context,
      targetCenterPoint = "500,500",
      includeDescription = true,
    )

    assertNotNull(result)
    assert(result.description.contains("ancestor") && result.description.contains("level")) {
      "Description should mention ancestor and level, got: ${result.description}"
    }
  }
}
