package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.util.encodeBase64
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/**
 * Observation tools for screen state inspection.
 *
 * NOT registered by default - these tools return large payloads (screenshots, full hierarchy)
 * that should stay out of the primary context window.
 *
 * Enable via: enableToolCategories(["OBSERVATION"])
 *
 * The two-tier pattern tools (getNextActionRecommendation) handle screen state internally
 * without polluting the outer agent's context.
 */
@Suppress("unused")
class ObservationToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  private suspend fun getScreenStateDirectly() = ScreenStateCaptureUtil.captureScreenState(mcpBridge)

  @LLMDescription(
    """
    Get current screen state (view hierarchy and optional screenshot).

    WARNING: Returns large payload. Prefer getNextActionRecommendation for UI automation
    to keep screen data out of your context window.

    Use this only when you need to manually inspect screen state.
    """
  )
  @Tool
  suspend fun getScreenState(
    @LLMDescription("Include base64 screenshot (default: true)")
    includeScreenshot: Boolean = true,
    @LLMDescription("Verbosity: MINIMAL, STANDARD, or FULL")
    verbosity: ViewHierarchyVerbosity? = null,
  ): String {
    val screenState = getScreenStateDirectly()
      ?: return "Error: No screen state available. Is a device connected?"

    val effectiveVerbosity = verbosity
      ?: sessionContext?.viewHierarchyVerbosity
      ?: ViewHierarchyVerbosity.MINIMAL

    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

    val viewHierarchyText = when (effectiveVerbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchy)
    }

    return if (includeScreenshot) {
      val screenshot = screenState.screenshotBytes?.encodeBase64() ?: "Screenshot unavailable"
      """
      |== View Hierarchy ==
      |$viewHierarchyText
      |
      |== Screenshot (base64 PNG) ==
      |$screenshot
      """.trimMargin()
    } else {
      viewHierarchyText
    }
  }

  @LLMDescription(
    """
    Get view hierarchy only (no screenshot).

    Verbosity levels:
    - MINIMAL: Interactable elements with coordinates
    - STANDARD: Interactable elements with descriptions and hierarchy
    - FULL: Complete view hierarchy including non-interactable elements
    """
  )
  @Tool
  suspend fun viewHierarchy(
    @LLMDescription("Verbosity: MINIMAL, STANDARD, or FULL")
    verbosity: ViewHierarchyVerbosity? = null,
  ): String {
    val screenState = getScreenStateDirectly()
      ?: return "Error: No screen state available. Is a device connected?"

    val effectiveVerbosity = verbosity
      ?: sessionContext?.viewHierarchyVerbosity
      ?: ViewHierarchyVerbosity.MINIMAL

    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

    return when (effectiveVerbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchy)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // View hierarchy formatting helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private fun buildMinimalViewHierarchy(node: ViewHierarchyTreeNode): String {
    val elements = mutableListOf<String>()
    collectInteractableElements(node, elements)
    return if (elements.isEmpty()) {
      "No interactable elements found on screen."
    } else {
      elements.joinToString("\n")
    }
  }

  private fun collectInteractableElements(node: ViewHierarchyTreeNode, elements: MutableList<String>) {
    if (node.isInteractable()) {
      val selector = node.asTrailblazeElementSelector()
      val description = selector?.description() ?: node.className
      val position = node.centerPoint?.let { "@($it)" } ?: ""
      elements.add("- $description $position")
    }
    node.children.forEach { child -> collectInteractableElements(child, elements) }
  }

  private fun buildFullViewHierarchy(node: ViewHierarchyTreeNode, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val className = node.className ?: "Unknown"
    val text = node.text?.let { " '$it'" } ?: ""
    val resourceId = node.resourceId?.let { " [$it]" } ?: ""
    val position = node.centerPoint?.let { " @($it)" } ?: ""
    val interactable = if (node.isInteractable()) " *" else ""

    val thisLine = "$indent$className$text$resourceId$position$interactable"

    val childDescriptions = node.children
      .map { child -> buildFullViewHierarchy(child, depth + 1) }
      .filter { it.isNotBlank() }

    return listOf(thisLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  private fun buildViewHierarchyDescription(node: ViewHierarchyTreeNode, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine = if (selectorDescription != null) {
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      "$indent$selectorDescription$positionSuffix"
    } else {
      null
    }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildViewHierarchyDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }
}
