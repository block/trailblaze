package xyz.block.trailblaze.viewhierarchy

import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Given the root of the view hierarchy and a target node, find the best Maestro command for tapping.
 * Prefers unique text, then unique accessibility text, then falls back to center point.
 */
fun findBestTapMaestroCommandForNode(
  root: ViewHierarchyTreeNode,
  target: ViewHierarchyTreeNode,
  longPress: Boolean = false, // Pass this if you want to support long press
): Command {
  val allNodes = root.aggregate().distinctBy { System.identityHashCode(it) }
  val targetAndDescendants = target.aggregate()

  // Try text in target or any descendant, prioritizing first found unique
  for (descendant in targetAndDescendants) {
    val text = descendant.text
    if (!text.isNullOrBlank()) {
      val nodesWithText = allNodes.filter { it.text == text }.distinctBy { System.identityHashCode(it) }
      if (nodesWithText.size == 1) {
        return TapOnElementCommand(
          selector = ElementSelector(
            textRegex = text,
          ),
          longPress = longPress,
          retryIfNoChange = false,
        )
      }
    }
  }

  // Try accessibility text in target or any descendant, prioritizing first found unique
  for (descendant in targetAndDescendants) {
    val accessibilityText = descendant.accessibilityText
    if (!accessibilityText.isNullOrBlank()) {
      val nodesWithAccText = allNodes.filter { it.accessibilityText == accessibilityText }
        .distinctBy { System.identityHashCode(it) }
      if (nodesWithAccText.size == 1) {
        return TapOnElementCommand(
          selector = ElementSelector(
            textRegex = accessibilityText,
          ),
          longPress = longPress,
          retryIfNoChange = false,
        )
      }
    }
  }

  // Fallback to center point of the target node
  val bounds = target.bounds
  val (x, y) = if (bounds != null) bounds.centerX to bounds.centerY else 0 to 0
  return TapOnPointV2Command(
    point = "$x,$y",
    longPress = longPress,
    retryIfNoChange = false,
  )
}
