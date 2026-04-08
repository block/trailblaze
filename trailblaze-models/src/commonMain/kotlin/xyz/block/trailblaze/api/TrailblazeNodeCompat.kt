package xyz.block.trailblaze.api

/**
 * Backward-compatibility adapter: converts a [TrailblazeNode] back to a
 * [ViewHierarchyTreeNode] by extracting LCD properties from the [DriverNodeDetail].
 *
 * This enables existing [ViewHierarchyFilter] + [ViewHierarchyCompactFormatter]
 * pipelines to work unchanged during migration to [TrailblazeNode].
 */
fun TrailblazeNode.toViewHierarchyTreeNode(): ViewHierarchyTreeNode {
  val b = this.bounds

  return when (val detail = driverDetail) {
    is DriverNodeDetail.IosMaestro -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      x1 = b?.left ?: 0,
      y1 = b?.top ?: 0,
      x2 = b?.right ?: 0,
      y2 = b?.bottom ?: 0,
      className = detail.className,
      resourceId = detail.resourceId,
      text = detail.text,
      accessibilityText = detail.accessibilityText,
      hintText = detail.hintText,
      enabled = detail.enabled,
      clickable = detail.clickable,
      focused = detail.focused,
      checked = detail.checked,
      selected = detail.selected,
      scrollable = detail.scrollable,
      password = detail.password,
      focusable = detail.focusable,
      ignoreBoundsFiltering = detail.ignoreBoundsFiltering,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.AndroidAccessibility -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      x1 = b?.left ?: 0,
      y1 = b?.top ?: 0,
      x2 = b?.right ?: 0,
      y2 = b?.bottom ?: 0,
      className = detail.className,
      resourceId = detail.resourceId,
      text = detail.text,
      accessibilityText = detail.contentDescription,
      hintText = detail.hintText,
      enabled = detail.isEnabled,
      clickable = detail.isClickable,
      focused = detail.isFocused,
      checked = detail.isChecked,
      selected = detail.isSelected,
      scrollable = detail.isScrollable,
      password = detail.isPassword,
      focusable = detail.isFocusable,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.AndroidMaestro -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      x1 = b?.left ?: 0,
      y1 = b?.top ?: 0,
      x2 = b?.right ?: 0,
      y2 = b?.bottom ?: 0,
      className = detail.className,
      resourceId = detail.resourceId,
      text = detail.text,
      accessibilityText = detail.accessibilityText,
      hintText = detail.hintText,
      enabled = detail.enabled,
      clickable = detail.clickable,
      focused = detail.focused,
      checked = detail.checked,
      selected = detail.selected,
      scrollable = detail.scrollable,
      password = detail.password,
      focusable = detail.focusable,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.Web -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      x1 = b?.left ?: 0,
      y1 = b?.top ?: 0,
      x2 = b?.right ?: 0,
      y2 = b?.bottom ?: 0,
      className = detail.ariaRole,
      text = detail.ariaName,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.Compose -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      x1 = b?.left ?: 0,
      y1 = b?.top ?: 0,
      x2 = b?.right ?: 0,
      y2 = b?.bottom ?: 0,
      className = detail.role,
      resourceId = detail.testTag,
      text = detail.text ?: detail.editableText,
      accessibilityText = detail.contentDescription,
      enabled = detail.isEnabled,
      focused = detail.isFocused,
      selected = detail.isSelected,
      password = detail.isPassword,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
  }
}
