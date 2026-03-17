package xyz.block.trailblaze.api

import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * Maps [ViewHierarchyTreeNode] trees to [TrailblazeNode] trees with
 * [DriverNodeDetail.IosMaestro] detail.
 *
 * Used by the iOS custom hierarchy path where the on-device service produces
 * [ViewHierarchyTreeNode] JSON. The custom hierarchy and the Maestro accessibility
 * hierarchy both produce the same fidelity of data, so both use [DriverNodeDetail.IosMaestro].
 */
fun ViewHierarchyTreeNode.toIosMaestroTrailblazeNode(): TrailblazeNode {
  val vhBounds = bounds
  return TrailblazeNode(
    nodeId = nodeId,
    bounds = vhBounds?.toTrailblazeNodeBounds(),
    children = children.map { it.toIosMaestroTrailblazeNode() },
    driverDetail = DriverNodeDetail.IosMaestro(
      text = text,
      resourceId = resourceId,
      accessibilityText = accessibilityText,
      className = className,
      hintText = hintText,
      clickable = clickable,
      enabled = enabled,
      focused = focused,
      checked = checked,
      selected = selected,
      focusable = focusable,
      scrollable = scrollable,
      password = password,
      ignoreBoundsFiltering = ignoreBoundsFiltering,
    ),
  )
}

private fun ViewHierarchyFilter.Bounds.toTrailblazeNodeBounds(): TrailblazeNode.Bounds =
  TrailblazeNode.Bounds(left = x1, top = y1, right = x2, bottom = y2)
