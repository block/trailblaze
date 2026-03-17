package xyz.block.trailblaze.api

/**
 * Backward-compatibility adapter: converts a [TrailblazeNode] back to a
 * [ViewHierarchyTreeNode] by extracting LCD properties from the [DriverNodeDetail].
 *
 * This enables existing [ViewHierarchyFilter] + [ViewHierarchyCompactFormatter]
 * pipelines to work unchanged during migration to [TrailblazeNode].
 */
fun TrailblazeNode.toViewHierarchyTreeNode(): ViewHierarchyTreeNode {
  val bounds = this.bounds
  val dimensions = if (bounds != null) "${bounds.width}x${bounds.height}" else null
  val centerPoint = if (bounds != null) "${bounds.centerX},${bounds.centerY}" else null

  return when (val detail = driverDetail) {
    is DriverNodeDetail.IosMaestro -> ViewHierarchyTreeNode(
      nodeId = nodeId,
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
      dimensions = dimensions,
      centerPoint = centerPoint,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.AndroidAccessibility -> ViewHierarchyTreeNode(
      nodeId = nodeId,
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
      dimensions = dimensions,
      centerPoint = centerPoint,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.AndroidMaestro -> ViewHierarchyTreeNode(
      nodeId = nodeId,
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
      dimensions = dimensions,
      centerPoint = centerPoint,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.Web -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      className = detail.ariaRole,
      text = detail.ariaName,
      dimensions = dimensions,
      centerPoint = centerPoint,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
    is DriverNodeDetail.Compose -> ViewHierarchyTreeNode(
      nodeId = nodeId,
      className = detail.role,
      resourceId = detail.testTag,
      text = detail.text ?: detail.editableText,
      accessibilityText = detail.contentDescription,
      enabled = detail.isEnabled,
      focused = detail.isFocused,
      selected = detail.isSelected,
      password = detail.isPassword,
      dimensions = dimensions,
      centerPoint = centerPoint,
      children = children.map { it.toViewHierarchyTreeNode() },
    )
  }
}
