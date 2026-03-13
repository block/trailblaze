package xyz.block.trailblaze.viewhierarchy

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

class ViewHierarchyFilterTest {

  private val screenWidth = 1080
  private val screenHeight = 1920

  private fun makeBounds(x1: Int, y1: Int, x2: Int, y2: Int): Pair<String, String> {
    val w = x2 - x1
    val h = y2 - y1
    val cx = x1 + w / 2
    val cy = y1 + h / 2
    return "${w}x$h" to "$cx,$cy"
  }

  private fun node(
    nodeId: Long,
    text: String? = null,
    className: String? = null,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    enabled: Boolean = true,
    children: List<ViewHierarchyTreeNode> = emptyList(),
    bounds: Pair<String, String>? = makeBounds(0, 0, 100, 50),
  ): ViewHierarchyTreeNode {
    return ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = text,
      className = className,
      clickable = clickable,
      scrollable = scrollable,
      enabled = enabled,
      children = children,
      dimensions = bounds?.first,
      centerPoint = bounds?.second,
    )
  }

  @Test
  fun `child nodes are not duplicated as top-level entries when parent is also interactable`() {
    // RecyclerView (scrollable) -> ViewGroup (clickable) -> TextView "Bagel", TextView "$2"
    val textBagel = node(nodeId = 39, text = "Bagel")
    val textPrice = node(nodeId = 40, text = "$2.00")
    val viewGroup = node(
      nodeId = 37,
      className = "android.view.ViewGroup",
      clickable = true,
      children = listOf(textBagel, textPrice),
    )
    val recyclerView = node(
      nodeId = 29,
      className = "androidx.recyclerview.widget.RecyclerView",
      scrollable = true,
      children = listOf(viewGroup),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(recyclerView)

    // Collect all nodeIds in the result tree
    val allNodeIds = result.aggregate().map { it.nodeId }

    // Each text node should appear exactly once (nested under its parent), not duplicated at top level
    assertEquals(1, allNodeIds.count { it == 39L }, "TextView 'Bagel' should appear exactly once")
    assertEquals(1, allNodeIds.count { it == 40L }, "TextView '\$2.00' should appear exactly once")
  }

  @Test
  fun `independent sibling nodes are not removed by deduplication`() {
    // Two independent clickable buttons at the same level
    val button1 = node(nodeId = 10, text = "Save", clickable = true)
    val button2 = node(nodeId = 11, text = "Cancel", clickable = true)
    val root = node(
      nodeId = 1,
      className = "android.widget.FrameLayout",
      children = listOf(button1, button2),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(root)

    val allNodeIds = result.aggregate().map { it.nodeId }

    assertEquals(1, allNodeIds.count { it == 10L }, "Button 'Save' should appear exactly once")
    assertEquals(1, allNodeIds.count { it == 11L }, "Button 'Cancel' should appear exactly once")
  }

  @Test
  fun `offscreen elements near viewport are included in filtered output`() {
    // On-screen button
    val onScreen = node(nodeId = 1, text = "Visible", clickable = true, bounds = makeBounds(0, 0, 200, 50))
    // Offscreen button just below the viewport
    val belowViewport = node(nodeId = 2, text = "Below", clickable = true, bounds = makeBounds(0, screenHeight, 200, screenHeight + 50))
    // Root bounds match the screen so the filter correctly identifies offscreen elements
    val root = node(
      nodeId = 3,
      className = "android.widget.FrameLayout",
      children = listOf(onScreen, belowViewport),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(root)

    val allTexts = result.aggregate().mapNotNull { it.text }
    assertContains(allTexts, "Visible", "On-screen element should be present")
    assertContains(allTexts, "Below", "Near-viewport offscreen element should be included")
  }

  @Test
  fun `far offscreen elements are excluded`() {
    // On-screen button
    val onScreen = node(nodeId = 1, text = "Visible", clickable = true, bounds = makeBounds(0, 0, 200, 50))
    // Far offscreen button (more than 1 viewport away)
    val farAway = node(nodeId = 2, text = "FarAway", clickable = true, bounds = makeBounds(0, screenHeight * 3, 200, screenHeight * 3 + 50))
    // Root bounds match the screen (Android filter uses root bounds as screenBounds)
    val root = node(
      nodeId = 3,
      className = "android.widget.FrameLayout",
      children = listOf(onScreen, farAway),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(root)

    val allTexts = result.aggregate().mapNotNull { it.text }
    assertContains(allTexts, "Visible")
    assertFalse(allTexts.contains("FarAway"), "Far offscreen element should be excluded")
  }

  @Test
  fun `compact formatter filters out offscreen elements by default`() {
    // On-screen button
    val onScreen = node(nodeId = 1, text = "Visible", clickable = true, bounds = makeBounds(0, 0, 200, 50))
    // Offscreen button just below the viewport
    val belowViewport = node(nodeId = 2, text = "Below", clickable = true, bounds = makeBounds(0, screenHeight, 200, screenHeight + 50))
    val root = node(
      nodeId = 3,
      className = "android.widget.FrameLayout",
      children = listOf(onScreen, belowViewport),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(root)

    val formatted = ViewHierarchyCompactFormatter.format(
      root = result,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
    )

    // The on-screen element should be present without (offscreen)
    val visibleLine = formatted.lines().first { it.contains("\"Visible\"") }
    assertFalse(visibleLine.contains("(offscreen)"), "On-screen element should not be annotated")

    // The offscreen element should be filtered out
    assertFalse(
      formatted.contains("\"Below\""),
      "Offscreen element should be filtered out, but got:\n$formatted",
    )

    // Summary line should be present
    assertContains(formatted, "offscreen elements hidden", message = "Should have summary line")
  }

  @Test
  fun `compact formatter includes offscreen elements when includeOffscreen is true`() {
    // On-screen button
    val onScreen = node(nodeId = 1, text = "Visible", clickable = true, bounds = makeBounds(0, 0, 200, 50))
    // Offscreen button just below the viewport
    val belowViewport = node(nodeId = 2, text = "Below", clickable = true, bounds = makeBounds(0, screenHeight, 200, screenHeight + 50))
    val root = node(
      nodeId = 3,
      className = "android.widget.FrameLayout",
      children = listOf(onScreen, belowViewport),
      bounds = makeBounds(0, 0, screenWidth, screenHeight),
    )

    val filter = ViewHierarchyFilter.create(screenWidth, screenHeight, TrailblazeDevicePlatform.ANDROID)
    val result = filter.filterInteractableViewHierarchyTreeNodes(root)

    val formatted = ViewHierarchyCompactFormatter.format(
      root = result,
      platform = TrailblazeDevicePlatform.ANDROID,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
      includeOffscreen = true,
    )

    // The on-screen element should NOT have (offscreen)
    val visibleLine = formatted.lines().first { it.contains("\"Visible\"") }
    assertFalse(visibleLine.contains("(offscreen)"), "On-screen element should not be annotated")

    // The offscreen element should have (offscreen)
    val offscreenLine = formatted.lines().first { it.contains("\"Below\"") }
    assertContains(offscreenLine, "(offscreen)", message = "Offscreen element should be annotated")

    // No summary line when includeOffscreen is true
    assertFalse(
      formatted.contains("offscreen elements hidden"),
      "Should not have summary line when includeOffscreen is true",
    )
  }
}
