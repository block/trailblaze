package xyz.block.trailblaze.agent.util

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Helper class for interacting with view hierarchy to retrieve element values.
 * This class provides utility methods to find and extract text from elements in the view hierarchy.
 */
object ElementRetriever {
  private const val TAG = "ElementRetriever"

  /**
   * Gets the text content of an element identified by resource ID.
   *
   * @param resourceId The resource ID to locate the element
   * @param index The index of the element if multiple elements share the same resource ID (0-based)
   * @return The text content of the element
   */
  fun getTextByResourceId(
    currentViewHierarchy: ViewHierarchyTreeNode?,
    resourceId: String,
    index: Int = 0,
  ): String {
    println("$TAG: Getting text by resource ID: $resourceId, index: $index")

    if (currentViewHierarchy == null) {
      println("$TAG: Error - View hierarchy is null")
      return "Error: View hierarchy not set"
    }

    val nodes = findNodes(currentViewHierarchy) { it.resourceId == resourceId }

    if (nodes.isEmpty()) {
      println("$TAG: No nodes found for resource ID: $resourceId")
      return "Element not found for resource ID: $resourceId"
    }

    println("$TAG: Found ${nodes.size} nodes with resource ID: $resourceId")

    return if (index < nodes.size) {
      val node = nodes[index]
      println("$TAG: Using node at index $index with resourceId: ${node.resourceId}, text: ${node.text}, accessibilityText: ${node.accessibilityText}")
      extractTextFromNode(node) ?: "No text found for resource ID: $resourceId at index $index"
    } else {
      println("$TAG: Index $index out of bounds for resource ID: $resourceId (max index: ${nodes.size - 1})")
      "Index out of bounds for resource ID: $resourceId"
    }
  }

  /**
   * Gets the text content of an element identified by content description.
   *
   * @param contentDescription The accessibility text to locate the element
   * @param index The index of the element if multiple elements share the same content description (0-based)
   * @return The text content of the element
   */
  fun getTextByContentDescription(
    currentViewHierarchy: ViewHierarchyTreeNode?,
    contentDescription: String,
    index: Int = 0,
  ): String {
    println("$TAG: Getting text by content description: $contentDescription, index: $index")

    val nodes = findNodes(currentViewHierarchy) { it.accessibilityText == contentDescription }

    if (nodes.isEmpty()) {
      return "Element not found for content description: $contentDescription"
    }

    return if (index < nodes.size) {
      extractTextFromNode(nodes[index]) ?: "No text found for content description: $contentDescription at index $index"
    } else {
      "Index out of bounds for content description: $contentDescription"
    }
  }

  /**
   * Gets the text content of an element identified by its text.
   *
   * @param text The text to locate the element
   * @param index The index of the element if multiple elements share the same text (0-based)
   * @return The text content of the element
   */
  fun getTextByText(
    currentViewHierarchy: ViewHierarchyTreeNode?,
    text: String,
    index: Int = 0,
  ): String {
    println("$TAG: Getting text by text: $text, index: $index")

    // For text search, we can actually just return the text if we find the node
    // Since the node's text is what we're searching for
    val nodes = findNodes(currentViewHierarchy) { it.text == text }

    if (nodes.isEmpty()) {
      return "Element not found for text: $text"
    }

    return if (index < nodes.size) {
      text
    } else {
      "Index out of bounds for text: $text"
    }
  }

  /**
   * Generic method to find nodes in the view hierarchy that match a predicate.
   *
   * @param node The root node to search from
   * @param predicate The condition to match
   * @return List of nodes that match the predicate
   */
  private fun findNodes(
    node: ViewHierarchyTreeNode?,
    predicate: (ViewHierarchyTreeNode) -> Boolean,
  ): List<ViewHierarchyTreeNode> {
    val results = mutableListOf<ViewHierarchyTreeNode>()
    if (node == null) return results

    // Check if this node matches
    if (predicate(node)) {
      results.add(node)
    }

    // Check children recursively
    node.children.forEach { child ->
      results.addAll(findNodes(child, predicate))
    }

    return results
  }

  /**
   * Extract text from a node, preferring text attribute and falling back to content description
   */
  private fun extractTextFromNode(node: ViewHierarchyTreeNode): String? {
    // Try text first
    val nodeText = node.text
    if (nodeText != null && nodeText.isNotEmpty()) {
      return nodeText
    }

    // Fall back to content description
    val nodeContentDesc = node.accessibilityText
    return nodeContentDesc
  }
}
