package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Produces a compact, LLM-optimised text representation of a [ViewHierarchyTreeNode] tree.
 *
 * Every node is included with all non-default property values:
 * ```
 * [1] Button "Sign In" (clickable, id: "btn_sign_in", center: 540,960, size: 200x48)
 * [3] EditText (focusable, hint: "Enter your email", center: 270,400, size: 300x48)
 * ```
 */
object ViewHierarchyCompactFormatter {

  /**
   * Format the view hierarchy tree into a compact text representation.
   *
   * @param root The root node of the view hierarchy tree.
   * @param platform The device platform (Android, iOS, Web).
   * @param screenWidth The screen width in pixels.
   * @param screenHeight The screen height in pixels.
   * @param foregroundAppId The foreground app package name, if known.
   * @param deviceClassifiers Device classifiers (e.g. "phone", "tablet", "ipad").
   * @param includeOffscreen When true, offscreen elements are included with an `(offscreen)`
   *   annotation. When false (default), offscreen elements are filtered out and a summary
   *   line is appended showing how many were hidden.
   * @param fullHierarchy Retained for API compatibility; all properties are now always included.
   */
  fun format(
    root: ViewHierarchyTreeNode,
    platform: TrailblazeDevicePlatform,
    screenWidth: Int,
    screenHeight: Int,
    foregroundAppId: String? = null,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    includeOffscreen: Boolean = false,
    @Suppress("UNUSED_PARAMETER") fullHierarchy: Boolean = false,
  ): String = buildString {
    // Context header
    appendLine("Platform: ${platform.displayName}")
    appendLine("Screen: ${screenWidth}x${screenHeight}")
    if (foregroundAppId != null) {
      appendLine("App: $foregroundAppId")
    }
    if (deviceClassifiers.isNotEmpty()) {
      appendLine("Device: ${deviceClassifiers.joinToString(", ")}")
    }
    appendLine()

    // Format the tree, tracking offscreen elements that are filtered out
    val offscreenCount = intArrayOf(0)
    formatNode(
      this, root,
      indent = 0,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
      includeOffscreen = includeOffscreen,
      offscreenCount = offscreenCount,
    )

    if (!includeOffscreen && offscreenCount[0] > 0) {
      appendLine(
        "(${offscreenCount[0]} offscreen elements hidden" +
          " — request OFFSCREEN_ELEMENTS for full list)",
      )
    }
  }.trimEnd()

  private fun formatNode(
    sb: StringBuilder,
    node: ViewHierarchyTreeNode,
    indent: Int,
    screenWidth: Int,
    screenHeight: Int,
    includeOffscreen: Boolean,
    offscreenCount: IntArray,
  ) {
    val nodeIsOffscreen = isOffscreen(node, screenWidth, screenHeight)

    if (nodeIsOffscreen && !includeOffscreen) {
      // Count this node and all descendants as hidden
      offscreenCount[0] += countNodes(node)
      return
    }

    val indentStr = "  ".repeat(indent)
    val offscreen = if (nodeIsOffscreen) " (offscreen)" else ""
    sb.appendLine("$indentStr${formatSingleNode(node)}$offscreen")

    for (child in node.children) {
      formatNode(sb, child, indent + 1, screenWidth, screenHeight, includeOffscreen, offscreenCount)
    }
  }

  private fun countNodes(node: ViewHierarchyTreeNode): Int {
    var count = 1
    for (child in node.children) {
      count += countNodes(child)
    }
    return count
  }

  private fun isOffscreen(node: ViewHierarchyTreeNode, screenWidth: Int, screenHeight: Int): Boolean {
    val bounds = node.bounds ?: return false
    return bounds.x2 <= 0 || bounds.x1 >= screenWidth ||
      bounds.y2 <= 0 || bounds.y1 >= screenHeight
  }

  internal fun formatSingleNode(
    node: ViewHierarchyTreeNode,
  ): String = buildString {
    // [nodeId]
    append("[${node.nodeId}]")

    // Role (short class name)
    append(" ${shortClassName(node.className)}")

    // Text in quotes
    val displayText = node.text ?: node.accessibilityText
    if (displayText != null) {
      append(" \"$displayText\"")
    }

    // Attributes in parens
    val attrs = buildAttributes(node)
    if (attrs.isNotEmpty()) {
      append(" (${attrs.joinToString(", ")})")
    }
  }

  private fun buildAttributes(
    node: ViewHierarchyTreeNode,
  ): List<String> = buildList {
    // State flags (non-default only)
    if (node.clickable) add("clickable")
    if (node.focusable) add("focusable")
    if (node.scrollable) add("scrollable")
    if (node.selected) add("selected")
    if (node.checked) add("checked")
    if (node.focused) add("focused")
    if (node.password) add("password")
    if (!node.enabled) add("disabled")

    // Hint text
    if (node.hintText != null) {
      add("hint: \"${node.hintText}\"")
    }

    // Resource ID
    if (node.resourceId != null) {
      add("id: \"${node.resourceId}\"")
    }

    // Bounds and dimensions
    if (node.centerPoint != null) {
      add("center: ${node.centerPoint}")
    }
    if (node.dimensions != null) {
      add("size: ${node.dimensions}")
    }
  }

  private fun shortClassName(className: String?): String {
    if (className == null) return "View"
    val lastDot = className.lastIndexOf('.')
    return if (lastDot >= 0) className.substring(lastDot + 1) else className
  }
}
