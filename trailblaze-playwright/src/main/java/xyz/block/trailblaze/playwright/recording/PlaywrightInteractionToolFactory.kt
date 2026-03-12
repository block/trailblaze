package xyz.block.trailblaze.playwright.recording

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativePressKeyTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeScrollTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeTypeTool
import xyz.block.trailblaze.recording.InteractionToolFactory
import xyz.block.trailblaze.recording.ViewHierarchyHitTester
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.math.abs

/**
 * Creates Playwright-native tool instances (playwright_click, playwright_type, etc.)
 * from user input events during interactive recording.
 *
 * Resolves taps to ARIA element refs using the [PlaywrightAriaSnapshot] compact element
 * mapping. Falls back to coordinate-based clicks when no ARIA element matches.
 */
class PlaywrightInteractionToolFactory(
  private val stream: PlaywrightDeviceScreenStream,
) : InteractionToolFactory {

  companion object {
    private val QUOTED_TEXT_REGEX = Regex("\"([^\"]+)\"")
  }

  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> {
    val ref = resolveElementRef(node)
    val description = node?.let { ViewHierarchyHitTester.resolveSemanticText(it) } ?: ""
    val tool = PlaywrightNativeClickTool(
      ref = ref ?: "css=html",
      element = description,
    )
    return tool to "playwright_click"
  }

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> {
    // Playwright doesn't have a native long-press tool, use click as fallback
    return createTapTool(node, x, y)
  }

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): Pair<TrailblazeTool, String> {
    val direction = computeScrollDirection(startX, startY, endX, endY)
    val tool = PlaywrightNativeScrollTool(direction = direction)
    return tool to "playwright_scroll"
  }

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> {
    val tool = PlaywrightNativeTypeTool(
      text = text,
      ref = "css=:focus",
      element = "",
      clearFirst = false,
    )
    return tool to "playwright_type"
  }

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>? {
    val tool = PlaywrightNativePressKeyTool(key = key)
    return tool to "playwright_press_key"
  }

  /**
   * Resolve a hit-tested view hierarchy node to the closest ARIA element ref (e.g., "e5").
   * Returns null if no matching element is found.
   */
  private fun resolveElementRef(node: ViewHierarchyTreeNode?): String? {
    if (node == null) return null
    val semanticText = ViewHierarchyHitTester.resolveSemanticText(node)
    if (semanticText == null) return null

    // Get the current ARIA snapshot compact elements
    val compactElements = stream.getCompactAriaElements()

    // Try to find the element by matching the semantic text against ARIA descriptors
    for ((elementId, elementRef) in compactElements.elementIdMapping) {
      if (elementRef.descriptor.contains(semanticText, ignoreCase = true)) {
        return elementId
      }
    }

    // Try matching by just the text part of the descriptor (e.g., "Submit" in 'button "Submit"')
    for ((elementId, elementRef) in compactElements.elementIdMapping) {
      val quotedTextMatch = QUOTED_TEXT_REGEX.find(elementRef.descriptor)
      if (quotedTextMatch != null && quotedTextMatch.groupValues[1].equals(semanticText, ignoreCase = true)) {
        return elementId
      }
    }

    return null
  }

  private fun computeScrollDirection(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): PlaywrightNativeScrollTool.ScrollDirection {
    val dx = endX - startX
    val dy = endY - startY
    return if (abs(dy) >= abs(dx)) {
      if (dy < 0) PlaywrightNativeScrollTool.ScrollDirection.DOWN
      else PlaywrightNativeScrollTool.ScrollDirection.UP
    } else {
      if (dx < 0) PlaywrightNativeScrollTool.ScrollDirection.RIGHT
      else PlaywrightNativeScrollTool.ScrollDirection.LEFT
    }
  }
}
