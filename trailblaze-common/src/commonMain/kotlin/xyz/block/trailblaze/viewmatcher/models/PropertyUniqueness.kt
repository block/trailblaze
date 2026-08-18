package xyz.block.trailblaze.viewmatcher.models

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.selectorPatternRegexMatches

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
private fun matchesText(node: ViewHierarchyTreeNode, targetText: String): Boolean = listOfNotNull(
  node.resolveMaestroText(),
  node.hintText,
  node.accessibilityText,
).any { value ->
  if (value.isBlank()) return@any false

  val strippedValue = value.replace('\n', ' ')

  // Maestro's logic: try both original and stripped values with both regex match and
  // exact-pattern comparison. The regex leg routes through models'
  // [selectorPatternRegexMatches] — the shared chokepoint for Orchestra's
  // IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE options (DOT_MATCHES_ALL exists on JVM,
  // Android, and Wasm but is absent from the common RegexOption API) and for the
  // toRegexSafe degrade that retries an invalid pattern as an escaped literal. The
  // exact-comparison leg compares the raw target text — NOT the compiled pattern — so a
  // valid regex that cannot match itself (e.g. "Total: ${'$'}5.00") still matches its own node.
  selectorPatternRegexMatches(targetText, value, maestroDialect = true) ||
    targetText == value ||
    selectorPatternRegexMatches(targetText, strippedValue, maestroDialect = true) ||
    targetText == strippedValue
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

  // Maestro's logic: match both the full ID and the suffix after the last '/'. Routed
  // through models' [selectorPatternRegexMatches] — see [matchesText] for the dialect and
  // invalid-pattern-degrade contract (the degrade reproduces the previous case-insensitive
  // literal fallback exactly).
  return selectorPatternRegexMatches(targetId, nodeId, maestroDialect = true) ||
    selectorPatternRegexMatches(targetId, nodeId.substringAfterLast('/'), maestroDialect = true)
}
