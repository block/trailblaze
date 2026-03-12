package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Produces a compact, LLM-optimised text representation of a [ViewHierarchyTreeNode] tree.
 *
 * **Default (compact) format** – shown every turn:
 * ```
 * [1] Button "Sign In" (clickable, id: "btn_sign_in")
 * [3] EditText (focusable, hint: "Enter your email")
 * ```
 *
 * **Full-fidelity format** – requested via `request_view_hierarchy_details` tool:
 * ```
 * [1] Button "Sign In" (clickable, enabled, id: "btn_sign_in", center: 540,960, size: 200x48)
 * ```
 *
 * Empty structural nodes (no text, no id, not interactable) are skipped in compact mode
 * and their children are promoted to the parent's indentation level.
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
   * @param fullHierarchy When true, includes all nodes (even structural), bounds, and dimensions.
   */
  fun format(
    root: ViewHierarchyTreeNode,
    platform: TrailblazeDevicePlatform,
    screenWidth: Int,
    screenHeight: Int,
    foregroundAppId: String? = null,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    fullHierarchy: Boolean = false,
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

    // Format the tree
    formatNode(this, root, indent = 0, fullHierarchy = fullHierarchy, screenWidth = screenWidth, screenHeight = screenHeight)
  }.trimEnd()

  private fun formatNode(
    sb: StringBuilder,
    node: ViewHierarchyTreeNode,
    indent: Int,
    fullHierarchy: Boolean,
    screenWidth: Int,
    screenHeight: Int,
  ) {
    val shouldSkip = !fullHierarchy && isEmptyStructuralNode(node)

    if (!shouldSkip) {
      val indentStr = "  ".repeat(indent)
      val offscreen = if (isOffscreen(node, screenWidth, screenHeight)) " (offscreen)" else ""
      sb.appendLine("$indentStr${formatSingleNode(node, fullHierarchy)}$offscreen")
    }

    // Children inherit current indent if parent was skipped, otherwise indent + 1
    val childIndent = if (shouldSkip) indent else indent + 1
    for (child in node.children) {
      formatNode(sb, child, childIndent, fullHierarchy, screenWidth, screenHeight)
    }
  }

  private fun isOffscreen(node: ViewHierarchyTreeNode, screenWidth: Int, screenHeight: Int): Boolean {
    val bounds = node.bounds ?: return false
    return bounds.x2 <= 0 || bounds.x1 >= screenWidth ||
      bounds.y2 <= 0 || bounds.y1 >= screenHeight
  }

  internal fun formatSingleNode(
    node: ViewHierarchyTreeNode,
    fullHierarchy: Boolean,
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
    val attrs = buildAttributes(node, fullHierarchy)
    if (attrs.isNotEmpty()) {
      append(" (${attrs.joinToString(", ")})")
    }
  }

  private fun buildAttributes(
    node: ViewHierarchyTreeNode,
    fullHierarchy: Boolean,
  ): List<String> = buildList {
    // State flags (non-default only)
    if (node.clickable) add("clickable")
    if (node.focusable) add("focusable")
    if (node.scrollable) add("scrollable")
    if (node.selected) add("selected")
    if (node.checked) add("checked")
    if (node.focused) add("focused")
    if (node.password) add("password")

    // Full hierarchy extras
    if (fullHierarchy) {
      if (node.enabled) add("enabled")
      if (!node.enabled) add("disabled")
    }

    // Hint text
    if (node.hintText != null) {
      add("hint: \"${node.hintText}\"")
    }

    // Resource ID — always shown when present
    if (node.resourceId != null) {
      add("id: \"${node.resourceId}\"")
    }

    // Full hierarchy: bounds and dimensions
    if (fullHierarchy) {
      if (node.centerPoint != null) {
        add("center: ${node.centerPoint}")
      }
      if (node.dimensions != null) {
        add("size: ${node.dimensions}")
      }
    }
  }

  /**
   * A node is considered an "empty structural node" if it has:
   * - No text, no accessibilityText, no hintText
   * - No resourceId
   * - Is not interactable (not clickable, focusable, scrollable, selected, checked, focused)
   */
  private fun isEmptyStructuralNode(node: ViewHierarchyTreeNode): Boolean =
    node.text == null &&
      node.accessibilityText == null &&
      node.hintText == null &&
      node.resourceId == null &&
      !node.clickable &&
      !node.focusable &&
      !node.scrollable &&
      !node.selected &&
      !node.checked &&
      !node.focused

  private fun shortClassName(className: String?): String {
    if (className == null) return "View"
    val lastDot = className.lastIndexOf('.')
    return if (lastDot >= 0) className.substring(lastDot + 1) else className
  }
}
