package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/** Formats a [ScreenState] view hierarchy at the requested [ViewHierarchyVerbosity]. */
object ViewHierarchyFormatter {

  fun format(screenState: ScreenState, verbosity: ViewHierarchyVerbosity): String {
    val vhFilter =
      ViewHierarchyFilter.create(
        screenWidth = screenState.deviceWidth,
        screenHeight = screenState.deviceHeight,
        platform = screenState.trailblazeDevicePlatform,
      )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

    return when (verbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchy)
    }
  }

  private fun buildMinimalViewHierarchy(node: ViewHierarchyTreeNode): String {
    val elements = mutableListOf<String>()
    collectInteractableElements(node, elements)
    return if (elements.isEmpty()) {
      "No interactable elements found on screen."
    } else {
      elements.joinToString("\n")
    }
  }

  private fun collectInteractableElements(
    node: ViewHierarchyTreeNode,
    elements: MutableList<String>,
  ) {
    if (node.isInteractable()) {
      val selector = node.asTrailblazeElementSelector()
      val description = selector?.description() ?: node.className
      val position = node.centerPoint?.let { "@($it)" } ?: ""
      elements.add("- $description $position")
    }
    node.children.forEach { child -> collectInteractableElements(child, elements) }
  }

  private fun buildFullViewHierarchy(
    node: ViewHierarchyTreeNode,
    depth: Int = 0,
  ): String {
    val indent = "  ".repeat(depth)
    val className = node.className ?: "Unknown"
    val text = node.text?.let { " '$it'" } ?: ""
    val resourceId = node.resourceId?.let { " [$it]" } ?: ""
    val position = node.centerPoint?.let { " @($it)" } ?: ""
    val interactable = if (node.isInteractable()) " *" else ""

    val thisLine = "$indent$className$text$resourceId$position$interactable"

    val childDescriptions =
      node.children.map { child -> buildFullViewHierarchy(child, depth + 1) }.filter {
        it.isNotBlank()
      }

    return listOf(thisLine).plus(childDescriptions).joinToString("\n")
  }

  private fun buildViewHierarchyDescription(
    node: ViewHierarchyTreeNode,
    depth: Int = 0,
  ): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine =
      if (selectorDescription != null) {
        val positionSuffix = centerPoint?.let { " @$it" } ?: ""
        "$indent$selectorDescription$positionSuffix"
      } else {
        null
      }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions =
      node.children.map { child -> buildViewHierarchyDescription(child, childDepth) }.filter {
        it.isNotBlank()
      }

    return listOfNotNull(thisNodeLine).plus(childDescriptions).joinToString("\n")
  }
}
