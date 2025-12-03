package xyz.block.trailblaze.viewmatcher

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.matching.CenterPointMatcher
import xyz.block.trailblaze.viewmatcher.models.OrderedSpatialHints
import xyz.block.trailblaze.viewmatcher.models.RelativePosition
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for TapSelectorV2 - generates element selectors for UI automation.
 *
 * Tests verify the progressive strategy: simple properties → parent/child → spatial → descendants → index.
 */
class TapSelectorV2Test {

  companion object {
    const val DEVICE_WIDTH = 1080
    const val DEVICE_HEIGHT = 2400

    /**
     * Creates a ViewHierarchyTreeNode for testing with sensible defaults.
     */
    fun createNode(
      nodeId: Long,
      text: String? = null,
      resourceId: String? = null,
      centerX: Int = 500,
      centerY: Int = 500,
      width: Int = 100,
      height: Int = 100,
      clickable: Boolean = false,
      enabled: Boolean = true,
      selected: Boolean = false,
      checked: Boolean = false,
      focused: Boolean = false,
      children: List<ViewHierarchyTreeNode> = emptyList(),
    ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = text,
      resourceId = resourceId,
      centerPoint = "$centerX,$centerY",
      dimensions = "${width}x$height",
      clickable = clickable,
      enabled = enabled,
      selected = selected,
      checked = checked,
      focused = focused,
      children = children,
    )
  }

  @Test
  fun `STRATEGY 1 - target properties only - unique text`() {
    // Setup: Two buttons with different text
    // Expected: Should select by text alone since "Submit" is unique
    val target = createNode(nodeId = 1, text = "Submit", clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        createNode(nodeId = 2, text = "Cancel"),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector uses only the text property, no parent/child needed
    assertEquals("Submit", result.textRegex)
    assertNull(result.childOf)
    assertNull(result.containsChild)
  }

  @Test
  fun `STRATEGY 1 - target properties only - unique resource id`() {
    // Setup: Two buttons with unique resource IDs
    // Expected: Should select by resource ID alone
    val target = createNode(nodeId = 1, resourceId = "submit_button", clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        createNode(nodeId = 2, resourceId = "cancel_button"),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector uses resource ID, no parent/child needed
    assertEquals("submit_button", result.idRegex)
    assertNull(result.childOf)
  }

  @Test
  fun `STRATEGY 1 - target properties only - unique state property`() {
    // Setup: Three options with same text but different selection state
    // Expected: Should combine text + selected state to uniquely identify
    val target = createNode(nodeId = 1, text = "Option", selected = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        createNode(nodeId = 2, text = "Option", selected = false),
        createNode(nodeId = 3, text = "Option", selected = false),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector combines text + state property
    assertEquals("Option", result.textRegex)
    assertEquals(true, result.selected)
    assertNull(result.childOf)
  }

  @Test
  fun `STRATEGY 2 - target with unique parent when target alone is not unique`() {
    // Setup: Two "Edit" buttons in different containers
    // - One in user_profile_container
    // - One in settings_container
    // Expected: Should use childOf to distinguish which "Edit" button
    val target = createNode(nodeId = 3, text = "Edit", clickable = true, centerX = 200, centerY = 200)
    val uniqueParent = createNode(
      nodeId = 2,
      resourceId = "user_profile_container",
      centerX = 300,
      centerY = 300,
      width = 400,
      height = 400,
      children = listOf(target),
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(
        uniqueParent,
        createNode(
          nodeId = 4,
          resourceId = "settings_container",
          children = listOf(
            createNode(nodeId = 5, text = "Edit"), // Duplicate "Edit" text
          ),
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector uses text + parent container to disambiguate
    assertEquals("Edit", result.textRegex)
    assertNotNull(result.childOf)
    assertEquals("user_profile_container", result.childOf?.idRegex)
  }

  @Test
  fun `STRATEGY 3 - target with unique child when parent not sufficient`() {
    // Setup: Two clickable containers, each with different children
    // - Target contains "Profile Picture"
    // - Other container contains "Settings Icon"
    // Expected: Should use containsChild to identify target by its child
    val uniqueChild = createNode(nodeId = 4, text = "Profile Picture")
    val target = createNode(
      nodeId = 3,
      clickable = true,
      centerX = 200,
      centerY = 200,
      width = 300,
      height = 300,
      children = listOf(uniqueChild),
    )
    val parent = createNode(
      nodeId = 2,
      children = listOf(
        target,
        createNode(
          nodeId = 5,
          clickable = true,
          children = listOf(
            createNode(nodeId = 6, text = "Settings Icon"),
          ),
        ),
      ),
    )
    val root = createNode(nodeId = 0, children = listOf(parent))

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector identifies target by its unique child
    assertNotNull(result.containsChild)
    assertEquals("Profile Picture", result.containsChild?.textRegex)
  }

  @Test
  fun `STRATEGY 4 - target with unique descendants`() {
    // Setup: Two product cards with different nested content
    // - Both cards have same text "Product Card" so they're not distinguishable by properties alone
    // - Target has "Product Name" and "price_label" descendants
    // - Other card has "Other Product" descendant
    // Expected: Should use containsDescendants to match multiple nested elements
    val descendant1 = createNode(nodeId = 5, text = "Product Name", centerX = 320, centerY = 320)
    val descendant2 = createNode(nodeId = 6, resourceId = "price_label", centerX = 340, centerY = 340)
    val target = createNode(
      nodeId = 3,
      text = "Product Card",
      clickable = true,
      centerX = 300,
      centerY = 300,
      width = 400,
      height = 400,
      children = listOf(
        createNode(
          nodeId = 4,
          centerX = 330,
          centerY = 330,
          children = listOf(descendant1, descendant2),
        ),
      ),
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        createNode(
          nodeId = 7,
          text = "Product Card", // Same text as target - not distinguishable by properties alone
          clickable = true,
          centerX = 700,
          centerY = 700,
          width = 400,
          height = 400,
          children = listOf(
            createNode(nodeId = 8, text = "Other Product", centerX = 720, centerY = 720),
          ),
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Selector matches target by its unique combination of descendants
    assertNotNull(result.containsDescendants)
    assertEquals(2, result.containsDescendants?.size)
  }

  @Test
  fun `STRATEGY 5 - index based selector when all else fails`() {
    // Setup: Three identical items that only differ by position
    // Expected: Should use index as last resort (sorted top-to-bottom by Y coordinate)
    val target = createNode(nodeId = 2, text = "Item", centerY = 200)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        createNode(nodeId = 1, text = "Item", centerY = 100), // Above target
        target, // Middle
        createNode(nodeId = 3, text = "Item", centerY = 300), // Below target
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Uses index 1 (0-based, middle item when sorted by Y)
    assertEquals("Item", result.textRegex)
    assertEquals("1", result.index)
  }

  @Test
  fun `STRATEGY 2_5 - spatial hint ABOVE without parent`() {
    // Setup: Two elements with duplicate text "Target"
    // - One above "Reference" (centerY=300, our target)
    // - One below "Reference" (centerY=700)
    // Spatial hint: LLM suggests target is ABOVE referenceNodeId=2
    // Expected: Should use spatial relationship (above) to disambiguate
    val referenceNode = createNode(nodeId = 2, text = "Reference", centerY = 500, width = 100, height = 100)
    val target = createNode(nodeId = 1, text = "Target", centerY = 300, width = 100, height = 100, clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target, // Above reference (300-50=250 to 300+50=350)
        referenceNode, // (500-50=450 to 500+50=550)
        createNode(
          nodeId = 3,
          text = "Target",
          centerY = 700,
          width = 100,
          height = 100,
        ), // Below reference - duplicate text
      ),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.ABOVE,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: Selector uses spatial relationship, no parent needed
    assertEquals("Target", result.textRegex)
    assertNotNull(result.above)
    assertEquals("Reference", result.above?.textRegex)
    assertNull(result.childOf)
  }

  @Test
  fun `STRATEGY 2_5 - spatial hint BELOW validates geometry and uses spatial`() {
    // Setup: Two buttons with duplicate text "Button"
    // Spatial hint: LLM suggests target is BELOW referenceNodeId=2
    // Expected: Validates geometry is correct (target.y1 >= reference.y2) and uses spatial selector
    val referenceNode = createNode(nodeId = 2, text = "Button", centerX = 500, centerY = 200, width = 100, height = 100)
    val target =
      createNode(nodeId = 1, text = "Button", centerX = 500, centerY = 400, width = 100, height = 100, clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        referenceNode, // Above target  (200-50=150 to 200+50=250)
        target, // Below reference (400-50=350 to 400+50=450)
      ),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.BELOW,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: Geometry validated, spatial selector created
    assertEquals("Button", result.textRegex)
    assertNotNull(result.below)
    assertEquals("Button", result.below?.textRegex)
  }

  @Test
  fun `index sorting by Y then X coordinates`() {
    // Setup: Grid layout with 4 identical items
    //   [1] [2]    Y=400
    //   [3] [4]    Y=600
    // Expected: Index sorted first by Y (top-to-bottom), then X (left-to-right)
    // Target is bottom-right = index 3
    val target = createNode(nodeId = 4, text = "Item", centerX = 600, centerY = 600)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        createNode(nodeId = 1, text = "Item", centerX = 400, centerY = 400), // Top-left
        createNode(nodeId = 2, text = "Item", centerX = 600, centerY = 400), // Top-right
        createNode(nodeId = 3, text = "Item", centerX = 400, centerY = 600), // Bottom-left
        target, // Bottom-right
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Correct index based on Y-then-X sorting
    assertEquals("Item", result.textRegex)
    assertEquals("3", result.index)
  }

  @Test
  fun `index fallback for nodes without distinguishing properties`() {
    // Setup: Two completely identical nodes (no text, no id, no state, same position)
    // This is an edge case - in real apps, elements usually have some distinguishing property
    // Expected: Index strategy should always succeed as the ultimate fallback
    val target = createNode(
      nodeId = 1,
      centerX = 500,
      centerY = 500,
      width = 100,
      height = 100,
    )
    val root = createNode(
      nodeId = 0,
      centerX = 400,
      centerY = 400,
      width = 300,
      height = 300,
      children = listOf(
        target,
        createNode(nodeId = 2, centerX = 500, centerY = 500, width = 100, height = 100), // Identical
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Index strategy successfully provides a selector
    assertNotNull(result.index)
  }

  @Test
  fun `iOS platform selector generation`() {
    // Setup: Same test as unique text, but on iOS platform
    // Expected: Algorithm should work identically across platforms
    val target = createNode(nodeId = 1, text = "Submit", clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        createNode(nodeId = 2, text = "Cancel"),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: iOS selector works same as Android
    assertEquals("Submit", result.textRegex)
  }

  @Test
  fun `STRATEGY 2_5 - spatial hint LEFT_OF validates geometry`() {
    // Setup: Two buttons side-by-side with duplicate text
    // Spatial hint: Target is LEFT_OF referenceNodeId=2
    // Expected: Validates target.x2 <= reference.x1 and uses spatial selector
    val referenceNode = createNode(nodeId = 2, text = "Button", centerX = 600, width = 100, height = 100)
    val target = createNode(nodeId = 1, text = "Button", centerX = 400, width = 100, height = 100, clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target, // Left of reference (400-50=350 to 400+50=450)
        referenceNode, // (600-50=550 to 600+50=650) - no overlap
      ),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.LEFT_OF,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: Horizontal spatial relationship validated and used
    assertEquals("Button", result.textRegex)
    assertNotNull(result.leftOf)
    assertEquals("Button", result.leftOf?.textRegex)
  }

  @Test
  fun `STRATEGY 2_5 - spatial hint RIGHT_OF validates geometry`() {
    // Setup: Two buttons side-by-side with duplicate text
    // Spatial hint: Target is RIGHT_OF referenceNodeId=2
    // Expected: Validates target.x1 >= reference.x2 and uses spatial selector
    val referenceNode = createNode(nodeId = 2, text = "Button", centerX = 400, width = 100, height = 100)
    val target = createNode(nodeId = 1, text = "Button", centerX = 600, width = 100, height = 100, clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        referenceNode, // (400-50=350 to 400+50=450)
        target, // Right of reference (600-50=550 to 600+50=650) - no overlap
      ),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.RIGHT_OF,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: Horizontal spatial relationship validated and used
    assertEquals("Button", result.textRegex)
    assertNotNull(result.rightOf)
    assertEquals("Button", result.rightOf?.textRegex)
  }

  @Test
  fun `spatial hint skipped when geometric validation fails`() {
    // Setup: LLM provides incorrect spatial hint
    // - LLM claims target is ABOVE reference
    // - But geometry shows target is actually BELOW reference (target.y1 > reference.y2)
    // Expected: Should skip invalid spatial hint and fall back to simple text selector
    val referenceNode = createNode(nodeId = 2, text = "Reference", centerY = 300, width = 100, height = 100)
    val target = createNode(nodeId = 1, text = "Target", centerY = 500, width = 100, height = 100, clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        referenceNode,
        target, // Actually BELOW reference, not ABOVE
      ),
    )

    // LLM hallucination: claims ABOVE but geometry says BELOW
    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.ABOVE, // Wrong!
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: Invalid spatial hint ignored, falls back to text-only selector
    assertEquals("Target", result.textRegex)
    assertNull(result.above)
  }

  @Test
  fun `spatial hint respects LLM preference order by trying first hint`() {
    // Setup: Target has duplicate text, both items are "below TopMarker"
    // - Target at Y=300 (closer to TopMarker)
    // - Other item at Y=500 (further from TopMarker)
    // Expected: May or may not use spatial (both match "below"), but selector always succeeds
    // This test demonstrates that spatial hints alone aren't always sufficient
    val topMarker = createNode(nodeId = 2, text = "TopMarker", centerY = 100, width = 100, height = 100)
    val target = createNode(nodeId = 1, text = "Item", centerY = 300, width = 100, height = 100, clickable = true)
    val otherItem = createNode(nodeId = 3, text = "Item", centerY = 500, width = 100, height = 100)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        topMarker,
        target, // Below TopMarker
        otherItem, // Also below TopMarker but further down
      ),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 2,
          relationship = RelativePosition.BELOW,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Verify: A selector was found (may use spatial + index, or just index)
    assertEquals("Item", result.textRegex)
  }

  @Test
  fun `center point tolerance - descendant with tolerance difference treated as same location`() {
    // Setup: Target node has a descendant with center point off by tolerance pixels in both X and Y
    // This can happen due to rounding when converting between different coordinate systems
    // - Target: centerPoint = "500,500" with text "ITEM" (all caps)
    // - Descendant: centerPoint offset by DEFAULT_TOLERANCE_PX with text "Item" (mixed case)
    // Expected: Should recognize descendant is at "same" location (within tolerance) and use its selector
    val offset = CenterPointMatcher.DEFAULT_TOLERANCE_PX
    val descendant = createNode(nodeId = 2, text = "Item", centerX = 500 + offset, centerY = 500 + offset)
    val target = createNode(
      nodeId = 1,
      text = "ITEM",
      centerX = 500,
      centerY = 500,
      clickable = true,
      children = listOf(descendant),
    )
    val duplicateNode = createNode(nodeId = 3, text = "ITEM", centerX = 600, centerY = 600)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        target,
        duplicateNode, // Same text as target, different location
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Should use the descendant's unique "Item" text (case-sensitive match)
    // because it's considered at the same location (within tolerance)
    assertEquals("Item", result.textRegex)
  }

  @Test
  fun `center point tolerance - outside tolerance not treated as same location`() {
    // Setup: Similar to previous test but descendant is outside tolerance (tolerance + 1px)
    // - Target: centerPoint = "500,500" with text "ITEM"
    // - Descendant: centerPoint offset by (tolerance + 1) with text "Item"
    // Expected: Should NOT treat as same location, falls back to other strategies
    val offset = CenterPointMatcher.DEFAULT_TOLERANCE_PX + 1
    val descendant = createNode(nodeId = 2, text = "Item", centerX = 500 + offset, centerY = 500 + offset)
    val target = createNode(
      nodeId = 1,
      text = "TARGET",
      centerX = 500,
      centerY = 500,
      clickable = true,
      children = listOf(descendant),
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(target),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Should use target's own unique text since descendant is too far away
    assertEquals("TARGET", result.textRegex)
  }

  @Test
  fun `center point tolerance - index matching with tolerance difference`() {
    // Setup: Multiple items with same text, need index to disambiguate
    // One of the items has center point off by tolerance pixels from expected
    // Expected: Should still match correctly with tolerance
    val offset = CenterPointMatcher.DEFAULT_TOLERANCE_PX
    val target = createNode(nodeId = 2, text = "Item", centerX = 500, centerY = 200 + offset)
    val root = createNode(
      nodeId = 0,
      children = listOf(
        createNode(nodeId = 1, text = "Item", centerX = 500, centerY = 100),
        target,
        createNode(nodeId = 3, text = "Item", centerX = 500, centerY = 300),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Verify: Should find correct index despite tolerance difference
    assertEquals("Item", result.textRegex)
    assertEquals("1", result.index)
  }

  @Test
  fun `test strategy descriptions include context`() {
    // Create a hierarchy where different strategies will be used
    val childNode = createNode(nodeId = 2, text = "Child", centerX = 500, centerY = 500)
    val target = createNode(
      nodeId = 1,
      text = "Button",
      centerX = 500,
      centerY = 500,
      children = listOf(childNode),
    )
    val parent = createNode(
      nodeId = 0,
      resourceId = "parent_id",
      centerX = 500,
      centerY = 500,
      children = listOf(target),
    )

    // Test direct match
    val directOptions = TapSelectorV2.findValidSelectorsForUI(
      root = parent,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
      maxResults = 5,
    )

    // Verify we get contextual descriptions
    val strategies = directOptions.map { it.strategy }

    // Should have at least one strategy
    assert(strategies.isNotEmpty()) { "Expected at least one strategy" }

    // The first strategy should be the direct match with context
    val directMatch = directOptions.find { it.strategy.contains("direct match") }
    assertNotNull(directMatch, "Expected to find 'direct match' in strategy descriptions")

    // If we have a parent strategy, it should include parent property info
    val parentMatch = directOptions.find { it.strategy.contains("unique parent") }
    if (parentMatch != null) {
      // Should mention which parent property was used (id in this case)
      assert(parentMatch.strategy.contains("parent:")) {
        "Expected parent strategy to include property details, got: ${parentMatch.strategy}"
      }
    }

    println("Strategy descriptions:")
    directOptions.forEach { option ->
      println("  - ${option.strategy}")
    }
  }

  @Test
  fun `handles target with no bounds gracefully`() {
    // Edge case: Target element has null bounds (malformed hierarchy or invisible element)
    // This happens when centerPoint or dimensions are missing
    // Expected: Should not crash, should still find a valid selector
    val targetWithNoBounds = ViewHierarchyTreeNode(
      nodeId = 1,
      text = "Button",
      clickable = true,
      centerPoint = null, // No center point means bounds will be null
      dimensions = null,
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(targetWithNoBounds),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = targetWithNoBounds,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Should succeed with text-only selector since "Button" is unique
    assertEquals("Button", result.textRegex)
  }

  @Test
  fun `handles spatial hint with non-existent nodeId`() {
    // Edge case: LLM provides spatial hint referencing a nodeId that doesn't exist
    // Expected: Should skip the invalid hint and succeed with fallback strategy
    val target = createNode(nodeId = 1, text = "Button", clickable = true)
    val root = createNode(
      nodeId = 0,
      children = listOf(target),
    )

    val spatialHints = OrderedSpatialHints(
      hints = listOf(
        OrderedSpatialHints.SpatialHint(
          referenceNodeId = 999, // Node doesn't exist
          relationship = RelativePosition.ABOVE,
        ),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = spatialHints,
    )

    // Should succeed by falling back to simpler strategy
    assertNotNull(result)
    assertEquals("Button", result.textRegex)
    // Spatial hint should be ignored since reference node doesn't exist
    assertNull(result.above)
  }

  @Test
  fun `handles root equals target`() {
    // Edge case: Target element is the root of the hierarchy
    // This can happen when searching within a subtree or for top-level containers
    // Expected: Should find a valid selector for the root element itself
    val rootAndTarget = createNode(
      nodeId = 1,
      text = "Root Container",
      resourceId = "root_id",
      clickable = true,
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = rootAndTarget,
      target = rootAndTarget,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Should succeed with unique properties
    assertNotNull(result)
    // Either text or id should be used since both are unique in this single-node tree
    assert(result.textRegex != null || result.idRegex != null) {
      "Expected either text or id selector for root element"
    }
  }

  @Test
  fun `handles hierarchy with duplicate text and no distinguishing features`() {
    // Edge case: Multiple identical elements that can only be distinguished by index
    // This tests the fallback to IndexStrategy when all else fails
    val target = createNode(
      nodeId = 2,
      text = "Item",
      centerX = 500,
      centerY = 200,
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(
        createNode(nodeId = 1, text = "Item", centerX = 500, centerY = 100),
        target,
        createNode(nodeId = 3, text = "Item", centerX = 500, centerY = 300),
      ),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Should fall back to index-based selector
    assertEquals("Item", result.textRegex)
    assertEquals("1", result.index) // Middle item (0-indexed, sorted by Y coordinate)
  }

  @Test
  fun `handles target with neither text nor id`() {
    // Edge case: Element with no text or id (e.g., a decorative container)
    // Expected: Should still find a valid selector, potentially using state or index
    val target = createNode(
      nodeId = 1,
      centerX = 500,
      centerY = 500,
      clickable = true,
    )
    val root = createNode(
      nodeId = 0,
      children = listOf(target),
    )

    val result = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
      root = root,
      target = target,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      widthPixels = DEVICE_WIDTH,
      heightPixels = DEVICE_HEIGHT,
      spatialHints = null,
    )

    // Should succeed - IndexStrategy always provides a fallback
    assertNotNull(result)
  }
}
