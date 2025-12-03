package xyz.block.trailblaze.viewmatcher.models

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Analysis of whether a node's text and ID properties are unique in the view hierarchy.
 * Uses Maestro's matching logic (case-insensitive regex for text, suffix matching for ID).
 */
data class PropertyUniqueness(
  val text: String?,
  val textIsUnique: Boolean,
  val textOccurrences: Int,
  val textMatchingNodeIds: List<Long>,
  val id: String?,
  val idIsUnique: Boolean,
  val idOccurrences: Int,
  val idMatchingNodeIds: List<Long>,
) {
  companion object {
    /**
     * Analyzes whether the target node's text and ID properties are unique in the hierarchy.
     * Uses Maestro's matching semantics to ensure consistency with selector behavior.
     */
    fun analyzePropertyUniqueness(
      target: ViewHierarchyTreeNode,
      root: ViewHierarchyTreeNode,
    ): PropertyUniqueness {
      val targetText = target.resolveMaestroText()
      val targetId = target.resourceId

      val allNodes = root.aggregate()

      // Find all nodes matching the text using Maestro's text matching logic
      val textMatchingNodes = if (targetText?.isNotBlank() == true) {
        allNodes.filter { node ->
          matchesText(node, targetText)
        }
      } else {
        emptyList()
      }

      // Find all nodes matching the ID using Maestro's ID matching logic
      val idMatchingNodes = if (targetId?.isNotBlank() == true) {
        allNodes.filter { node ->
          matchesId(node, targetId)
        }
      } else {
        emptyList()
      }

      return PropertyUniqueness(
        text = targetText,
        textIsUnique = textMatchingNodes.size == 1,
        textOccurrences = textMatchingNodes.size,
        textMatchingNodeIds = textMatchingNodes.map { it.nodeId },
        id = targetId,
        idIsUnique = idMatchingNodes.size == 1,
        idOccurrences = idMatchingNodes.size,
        idMatchingNodeIds = idMatchingNodes.map { it.nodeId },
      )
    }
  }
}

/**
 * Matches text using Maestro's logic:
 * - Case-insensitive regex matching
 * - Checks text, hintText, and accessibilityText attributes
 * - Tries both original and newline-normalized values
 * - Supports both regex match and exact pattern match
 */
private fun matchesText(node: ViewHierarchyTreeNode, targetText: String): Boolean {
  // Maestro uses IGNORE_CASE, DOT_MATCHES_ALL, MULTILINE options
  val regex = try {
    targetText.toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
  } catch (e: Exception) {
    // If the pattern is invalid, treat it as a literal string
    return matchesTextLiteral(node, targetText)
  }

  return listOfNotNull(
    node.resolveMaestroText(),
    node.hintText,
    node.accessibilityText,
  ).any { value ->
    if (value.isBlank()) return@any false

    val strippedValue = value.replace('\n', ' ')

    // Maestro's logic: try both original and stripped values with both match and pattern comparison
    regex.matches(value) ||
      regex.pattern == value ||
      regex.matches(strippedValue) ||
      regex.pattern == strippedValue
  }
}

/**
 * Fallback for invalid regex patterns - matches as literal string (case-insensitive)
 */
private fun matchesTextLiteral(node: ViewHierarchyTreeNode, targetText: String): Boolean = listOfNotNull(
  node.resolveMaestroText(),
  node.hintText,
  node.accessibilityText,
).any { value ->
  value.equals(targetText, ignoreCase = true) ||
    value.replace('\n', ' ').equals(targetText, ignoreCase = true)
}

/**
 * Matches ID using Maestro's logic:
 * - Case-insensitive regex matching
 * - Checks both full resource-id and suffix after last '/'
 * - Supports both regex match and exact pattern match
 */
private fun matchesId(node: ViewHierarchyTreeNode, targetId: String): Boolean {
  val nodeId = node.resourceId
  if (nodeId.isNullOrBlank()) return false

  // Maestro uses IGNORE_CASE, DOT_MATCHES_ALL, MULTILINE options
  val regex = try {
    targetId.toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
  } catch (e: Exception) {
    // If the pattern is invalid, treat it as a literal string
    return nodeId.equals(targetId, ignoreCase = true) ||
      nodeId.substringAfterLast('/').equals(targetId, ignoreCase = true)
  }

  // Maestro's logic: match both full ID and suffix after last '/'
  return regex.matches(nodeId) ||
    regex.matches(nodeId.substringAfterLast('/'))
}
