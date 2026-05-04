package xyz.block.trailblaze.viewmatcher.matching

import maestro.TreeNode
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import java.util.regex.Pattern

/**
 * Maps Maestro [TreeNode] trees to [TrailblazeNode] trees with
 * [DriverNodeDetail.IosMaestro] detail for iOS platform.
 *
 * This follows the same conversion pattern as [AccessibilityNode.toTrailblazeNode()]
 * in `:trailblaze-android`, preserving all Maestro-captured properties in the
 * typed [DriverNodeDetail.IosMaestro] variant.
 */
private class NodeIdCounter {
  private var next = 0L
  fun next(): Long = next++
}

fun TreeNode.toTrailblazeNodeAndroidMaestro(): TrailblazeNode? =
  toTrailblazeNodeAndroidMaestro(NodeIdCounter())

fun TreeNode.toTrailblazeNodeIosMaestro(): TrailblazeNode? =
  toTrailblazeNodeIosMaestro(NodeIdCounter())

private fun TreeNode.toTrailblazeNodeAndroidMaestro(counter: NodeIdCounter): TrailblazeNode? {
  if (attributes.isEmpty() && children.isEmpty()) return null

  val bounds = parseBounds(attributes["bounds"])
  val resourceId = getAttributeIfNotBlank("resource-id")

  return TrailblazeNode(
    nodeId = counter.next(),
    bounds = bounds,
    children = children.mapNotNull { it.toTrailblazeNodeAndroidMaestro(counter) },
    driverDetail = DriverNodeDetail.AndroidMaestro(
      text = getAttributeIfNotBlank("text"),
      resourceId = resourceId,
      accessibilityText = getAttributeIfNotBlank("accessibilityText"),
      className = getAttributeIfNotBlank("class"),
      hintText = getAttributeIfNotBlank("hintText"),
      clickable = clickable ?: false,
      enabled = enabled ?: true,
      focused = focused ?: false,
      checked = checked ?: false,
      selected = selected ?: false,
      focusable = attributes["focusable"] == "true",
      scrollable = attributes["scrollable"] == "true",
      password = attributes["password"] == "true",
    ),
  )
}

private fun TreeNode.toTrailblazeNodeIosMaestro(counter: NodeIdCounter): TrailblazeNode? {
  if (attributes.isEmpty() && children.isEmpty()) return null

  val bounds = parseBounds(attributes["bounds"])

  return TrailblazeNode(
    nodeId = counter.next(),
    bounds = bounds,
    children = children.mapNotNull { it.toTrailblazeNodeIosMaestro(counter) },
    driverDetail = DriverNodeDetail.IosMaestro(
      text = getAttributeIfNotBlank("text"),
      resourceId = getAttributeIfNotBlank("resource-id"),
      accessibilityText = getAttributeIfNotBlank("accessibilityText"),
      className = getAttributeIfNotBlank("class"),
      hintText = getAttributeIfNotBlank("hintText"),
      clickable = clickable ?: false,
      enabled = enabled ?: true,
      focused = focused ?: false,
      checked = checked ?: false,
      selected = selected ?: false,
      focusable = attributes["focusable"] == "true",
      scrollable = attributes["scrollable"] == "true",
      password = attributes["password"] == "true",
      visible = attributes["visible"]?.toBoolean() ?: true,
      ignoreBoundsFiltering = attributes["ignoreBoundsFiltering"] == "true",
    ),
  )
}

// --- Private helpers ---

private val BOUNDS_PATTERN: Pattern =
  Pattern.compile("\\[([0-9-]+),([0-9-]+)]\\[([0-9-]+),([0-9-]+)]")

private fun parseBounds(boundsString: String?): TrailblazeNode.Bounds? {
  if (boundsString.isNullOrBlank()) return null
  val m = BOUNDS_PATTERN.matcher(boundsString)
  if (!m.matches()) return null

  val left = m.group(1).toIntOrNull() ?: return null
  val top = m.group(2).toIntOrNull() ?: return null
  val right = m.group(3).toIntOrNull() ?: return null
  val bottom = m.group(4).toIntOrNull() ?: return null

  return TrailblazeNode.Bounds(left = left, top = top, right = right, bottom = bottom)
}

private fun TreeNode.getAttributeIfNotBlank(attributeName: String): String? =
  attributes[attributeName]?.ifBlank { null }
