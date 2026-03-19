package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrailblazeNodeSelectorGeneratorComposeTest : TrailblazeNodeSelectorGeneratorTestBase() {

  // -- Compose Strategy 1: testTag --

  @Test
  fun `Compose - testTag selector`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(testTag = "cancel_btn", text = "Cancel"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.testTag)
  }

  // -- Compose Strategy 2: role + text --

  @Test
  fun `Compose - role plus text selector`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Save"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Delete"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.textRegex)
  }

  // -- Compose Strategy 2: text alone (no role) --

  @Test
  fun `Compose - text alone when unique`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(text = "Welcome"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(text = "Goodbye"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.textRegex)
  }

  // -- Compose Strategy 3: childOf unique parent --

  @Test
  fun `Compose - childOf unique parent`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val otherOk = nodeOf(
      detail = DriverNodeDetail.Compose(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "confirm_dialog"),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 200),
      children = listOf(target),
    )
    val otherParent = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "delete_dialog"),
      bounds = TrailblazeNode.Bounds(0, 300, 200, 400),
      children = listOf(otherOk),
    )
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(parent, otherParent))

    val selector = assertUniqueMatch(root, target)
    assertNotNull(selector.childOf, "Expected childOf selector for Compose")
  }

  // -- Compose Strategy 4: spatial below sibling --

  @Test
  fun `Compose - spatial below sibling`() {
    nextId = 1L
    val anchor = nodeOf(
      detail = DriverNodeDetail.Compose(text = "Section Title"),
      bounds = TrailblazeNode.Bounds(0, 100, 400, 150),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Image"),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 40),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Image"),
      bounds = TrailblazeNode.Bounds(0, 160, 200, 200),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      children = listOf(other, anchor, target),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 200),
    )

    val selector = assertUniqueMatch(root, target)
    assertTrue(
      selector.below != null || selector.above != null ||
        selector.leftOf != null || selector.rightOf != null,
      "Expected spatial relationship in Compose selector",
    )
  }
}
