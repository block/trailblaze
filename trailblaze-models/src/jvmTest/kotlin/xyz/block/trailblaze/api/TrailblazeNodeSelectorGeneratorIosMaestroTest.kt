package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrailblazeNodeSelectorGeneratorIosMaestroTest : TrailblazeNodeSelectorGeneratorTestBase() {

  // -- iOS Strategy 1: Resource ID --

  @Test
  fun `iOS - resourceId selector`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "login_button", text = "Log in"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "signup_button", text = "Sign up"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.resourceIdRegex)
  }

  // -- iOS Strategy 2: Accessibility text alone --

  @Test
  fun `iOS - accessibilityText for icon`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(accessibilityText = "Close"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Save"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.accessibilityTextRegex)
  }

  // -- iOS Strategy 3: Text alone --

  @Test
  fun `iOS - text alone`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Next"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Cancel"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.textRegex)
  }

  // -- iOS Strategy 4: Hint text alone --

  @Test
  fun `iOS - hintText for empty input`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        hintText = "Email or phone number",
        className = "UITextField",
      ),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Sign in"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.hintTextRegex)
  }

  // -- iOS Strategy 5: Text + className --

  @Test
  fun `iOS - text plus className disambiguation`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Done", className = "UIButton"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Done", className = "UIBarButtonItem"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.textRegex)
    assertNotNull(match.classNameRegex)
  }

  // -- iOS Strategy 8: ClassName alone --

  @Test
  fun `iOS - className alone when unique`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UISlider"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UILabel", text = "Volume"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.classNameRegex)
  }

  // -- iOS Strategy 9: childOf unique parent --

  @Test
  fun `iOS - childOf unique parent`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val otherOk = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "confirm_dialog"),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 200),
      children = listOf(target),
    )
    val otherParent = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "delete_dialog"),
      bounds = TrailblazeNode.Bounds(0, 300, 200, 400),
      children = listOf(otherOk),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(parent, otherParent),
    )

    val selector = assertUniqueMatch(root, target)
    assertNotNull(selector.childOf, "Expected childOf selector for iOS")
  }

  // -- iOS Strategy 11: Spatial below sibling --

  @Test
  fun `iOS - spatial below sibling`() {
    nextId = 1L
    val anchor = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Section Header"),
      bounds = TrailblazeNode.Bounds(0, 100, 400, 150),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIButton"),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 40),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIButton"),
      bounds = TrailblazeNode.Bounds(0, 160, 200, 200),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(other, anchor, target),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 200),
    )

    val selector = assertUniqueMatch(root, target)
    assertTrue(
      selector.below != null || selector.above != null ||
        selector.leftOf != null || selector.rightOf != null,
      "Expected spatial relationship in iOS selector",
    )
  }

  // -- iOS Strategy 12: Index fallback --

  @Test
  fun `iOS - index fallback for identical nodes`() {
    nextId = 1L
    val node1 = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIView"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val node2 = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIView"),
      bounds = TrailblazeNode.Bounds(0, 50, 100, 100),
    )
    val node3 = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIView"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(node1, node2, node3),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 150),
    )

    val selector = assertUniqueMatch(root, node2)
    assertNotNull(selector.index, "Expected index-based selector for identical iOS nodes")
  }

  // -- iOS: propertyless container skipped in favor of identifiable sibling --

  @Test
  fun `iOS - selector prefers identifiable node over smaller propertyless container`() {
    nextId = 1L
    // Small className-only container at (20,10)-(80,40) — 60x30 = 1800 area
    // Real iOS containers typically have className but no text/resourceId/accessibilityText
    val propertyless = nodeOf(
      detail = DriverNodeDetail.IosMaestro(className = "UIView"),
      bounds = TrailblazeNode.Bounds(20, 10, 80, 40),
    )
    // Slightly larger node with properties at (10,5)-(90,45) — 80x40 = 3200 area
    // Both contain center point (50, 25)
    val identifiable = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "Contacts", accessibilityText = "Contacts"),
      bounds = TrailblazeNode.Bounds(10, 5, 90, 45),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(propertyless, identifiable),
    )

    // hitTest at the overlapping center should pick the identifiable node
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(identifiable.nodeId, hit.nodeId)

    // The selector should use resourceId, not a bare index fallback
    val selector = assertUniqueMatch(root, identifiable)
    val match = selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.resourceIdRegex)
  }

  // -- iOS: findAllValidSelectors returns multiple strategies --

  @Test
  fun `iOS - findAllValidSelectors returns non-empty list`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        resourceId = "email_field",
        hintText = "Email or phone number",
        className = "UITextField",
      ),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Sign in"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val selectors = TrailblazeNodeSelectorGenerator.findAllValidSelectors(root, target)
    assertTrue(selectors.isNotEmpty(), "Expected at least one selector for iOS node")
    assertTrue(selectors.first().isBest, "First selector should be marked as best")
    // Should not be a bare index fallback since the node has a resource ID
    assertTrue(
      selectors.first().strategy != "Global index fallback",
      "Expected a real strategy, not global index fallback",
    )
  }

  // -- iOS: findBestStructuralSelector --

  @Test
  fun `iOS - structural selector uses resourceId`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        resourceId = "submit_button",
        text = "Submit",
      ),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Cancel"),
    )
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val named = TrailblazeNodeSelectorGenerator.findBestStructuralSelector(root, target)
    assertTrue(
      named.strategy != "Structural: global index fallback",
      "Expected structural strategy, not global index fallback",
    )
    val match = named.selector.driverMatch as DriverNodeMatch.IosMaestro
    assertNotNull(match.resourceIdRegex)
  }
}
