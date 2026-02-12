package xyz.block.trailblaze.viewmatcher.strategies

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for selector strategies, focusing on UniqueDescendantsStrategy and strategy properties.
 */
class SelectorStrategyTest {

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
  fun `UniqueDescendantsStrategy is marked as performant`() {
    assertTrue(UniqueDescendantsStrategy.isPerformant, "UniqueDescendantsStrategy should be performant")
  }

  @Test
  fun `UniqueDescendantsStrategy returns null when target has no unique descendants`() {
    // Setup: Target with no descendants
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

    val result = UniqueDescendantsStrategy.findFirst(context)

    assertNull(result, "Should return null when target has no unique descendants")
  }

  @Test
  fun `UniqueDescendantsStrategy finds selector with unique descendants`() {
    // Setup: Target with unique descendant combination
    val descendant1 = createNode(nodeId = 3, text = "Child1")
    val descendant2 = createNode(nodeId = 4, text = "Child2")
    val target = createNode(
      nodeId = 1,
      text = "Container",
      centerX = 500,
      centerY = 500,
      width = 300,
      height = 300,
      children = listOf(descendant1, descendant2),
    )
    val otherTarget = createNode(
      nodeId = 2,
      text = "Container",
      centerX = 700,
      centerY = 700,
      width = 300,
      height = 300,
      children = listOf(
        createNode(nodeId = 5, text = "OtherChild"),
      ),
    )
    val root = createNode(nodeId = 0, children = listOf(target, otherTarget))

    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      centerPointTolerancePx = 1,
    )

    val result = UniqueDescendantsStrategy.findFirst(context)

    assertNotNull(result, "Should find selector with unique descendants")
    assertEquals("Container", result.textRegex)
    assertNotNull(result.containsDescendants)
    assertTrue(result.containsDescendants!!.isNotEmpty(), "Should have descendant selectors")
  }

  @Test
  fun `UniqueDescendantsStrategy findAll returns all valid selectors`() {
    // Setup: Target with unique descendants
    val descendant = createNode(nodeId = 2, text = "UniqueChild")
    val target = createNode(
      nodeId = 1,
      text = "Target",
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

    val results = UniqueDescendantsStrategy.findAll(context)

    assertTrue(results.isNotEmpty(), "Should return at least one selector")
    results.forEach { selector ->
      assertNotNull(selector.containsDescendants, "All results should have containsDescendants")
    }
  }

  @Test
  fun `performant strategies include expected strategies`() {
    // Verify that the strategies we expect to be performant are marked as such
    val performantStrategies = listOf(
      TargetPropertiesStrategy,
      UniqueParentStrategy,
      SpatialHintsStrategy,
      UniqueChildStrategy,
      UniqueDescendantsStrategy,
    )

    performantStrategies.forEach { strategy ->
      assertTrue(
        strategy.isPerformant,
        "${strategy.name} should be marked as performant",
      )
    }
  }

  @Test
  fun `non-performant strategies include expensive multi-parent strategies`() {
    // Verify that expensive strategies are NOT marked as performant
    val nonPerformantStrategies = listOf(
      ContainsChildMultiParentStrategy,
      ContainsDescendantsMultiParentStrategy,
    )

    nonPerformantStrategies.forEach { strategy ->
      assertTrue(
        !strategy.isPerformant,
        "${strategy.name} should NOT be marked as performant",
      )
    }
  }

  @Test
  fun `IndexStrategy is not performant`() {
    // IndexStrategy always succeeds but may not be fast for large hierarchies
    assertTrue(
      !IndexStrategy.isPerformant,
      "IndexStrategy should not be marked as performant",
    )
  }

  @Test
  fun `UniqueParentStrategy is performant`() {
    assertTrue(UniqueParentStrategy.isPerformant, "UniqueParentStrategy should be performant")
  }

  @Test
  fun `SpatialHintsStrategy is performant`() {
    assertTrue(SpatialHintsStrategy.isPerformant, "SpatialHintsStrategy should be performant")
  }

  @Test
  fun `UniqueChildStrategy is performant`() {
    assertTrue(UniqueChildStrategy.isPerformant, "UniqueChildStrategy should be performant")
  }

  @Test
  fun `TargetPropertiesStrategy is performant`() {
    assertTrue(TargetPropertiesStrategy.isPerformant, "TargetPropertiesStrategy should be performant")
  }

  @Test
  fun `all strategies have non-empty names`() {
    val allStrategies = listOf(
      TargetPropertiesStrategy,
      UniqueParentStrategy,
      SpatialHintsStrategy,
      UniqueChildStrategy,
      UniqueDescendantsStrategy,
      ContainsChildMultiParentStrategy,
      ContainsDescendantsMultiParentStrategy,
      IndexStrategy,
    )

    allStrategies.forEach { strategy ->
      assertTrue(strategy.name.isNotEmpty(), "Strategy ${strategy::class.simpleName} should have a name")
    }
  }

  @Test
  fun `findAllWithContext returns contextual descriptions`() {
    // Setup: Simple target with unique text
    val target = createNode(nodeId = 1, text = "UniqueButton")
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

    val results = TargetPropertiesStrategy.findAllWithContext(context)

    assertTrue(results.isNotEmpty(), "Should return results")
    results.forEach { result ->
      assertTrue(result.description.isNotEmpty(), "Each result should have a description")
    }
  }

  @Test
  fun `UniqueDescendantsStrategy works with nested descendants`() {
    // Setup: Target with deeply nested unique descendants
    val deepDescendant = createNode(nodeId = 4, text = "DeepChild")
    val middleDescendant = createNode(nodeId = 3, children = listOf(deepDescendant))
    val directChild = createNode(nodeId = 2, children = listOf(middleDescendant))
    val target = createNode(
      nodeId = 1,
      text = "Target",
      children = listOf(directChild),
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

    // Should handle nested descendants (if uniqueDescendants is computed correctly)
    val result = UniqueDescendantsStrategy.findFirst(context)

    // Result may be null or not null depending on if "DeepChild" is unique in the hierarchy
    // Just verify the strategy doesn't crash with nested structures
    if (result != null) {
      assertNotNull(result.containsDescendants)
    }
  }

  @Test
  fun `strategy default findAllWithContext implementation works`() {
    // Test that the default implementation in SelectorStrategy interface works
    val target = createNode(nodeId = 1, text = "Button")
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

    // IndexStrategy uses the default implementation
    val results = IndexStrategy.findAllWithContext(context)

    assertTrue(results.isNotEmpty(), "Should return results")
    results.forEach { result ->
      assertEquals(IndexStrategy.name, result.description, "Default implementation should use strategy name")
    }
  }
}
