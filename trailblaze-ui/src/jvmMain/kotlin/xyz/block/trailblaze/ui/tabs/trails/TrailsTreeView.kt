package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Displays a tree view of trail nodes using a lazy column.
 *
 * @param nodes The root nodes to display
 * @param selectedFilePath The path of the currently selected file
 * @param onToggleExpand Callback when a directory is expanded/collapsed
 * @param onFileClick Callback when a file is clicked
 */
@Composable
fun TrailsTreeView(
  nodes: List<TrailNode>,
  selectedFilePath: String?,
  onToggleExpand: (TrailNode.Directory) -> Unit,
  onFileClick: (TrailNode.TrailFile) -> Unit,
) {
  // Flatten the tree into a list for LazyColumn
  val flattenedNodes = flattenTree(nodes)
  
  LazyColumn(
    modifier = Modifier.fillMaxSize()
  ) {
    items(
      items = flattenedNodes,
      key = { it.path }
    ) { node ->
      TrailsTreeItem(
        node = node,
        isSelected = when (node) {
          is TrailNode.TrailFile -> node.path == selectedFilePath
          is TrailNode.Directory -> false
        },
        onToggleExpand = onToggleExpand,
        onFileClick = onFileClick
      )
    }
  }
}

/**
 * Flattens a tree structure into a list for display in a LazyColumn.
 * Only includes expanded directories and their children.
 *
 * @param nodes The nodes to flatten
 * @return A flat list of nodes to display
 */
private fun flattenTree(nodes: List<TrailNode>): List<TrailNode> {
  val result = mutableListOf<TrailNode>()
  
  fun addNode(node: TrailNode) {
    result.add(node)
    if (node is TrailNode.Directory && node.isExpanded) {
      node.children.forEach { child ->
        addNode(child)
      }
    }
  }
  
  nodes.forEach { addNode(it) }
  
  return result
}
