package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A single item in the trails tree view.
 * Can represent either a directory or a trail file.
 *
 * @param node The tree node to display
 * @param isSelected Whether this item is currently selected
 * @param onToggleExpand Callback when a directory's expand/collapse button is clicked
 * @param onFileClick Callback when a file is clicked
 */
@Composable
fun TrailsTreeItem(
  node: TrailNode,
  isSelected: Boolean,
  onToggleExpand: (TrailNode.Directory) -> Unit,
  onFileClick: (TrailNode.TrailFile) -> Unit,
) {
  val depth = when (node) {
    is TrailNode.Directory -> node.depth
    is TrailNode.TrailFile -> node.depth
  }
  
  val indentWidth = (depth * 20).dp
  
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable {
        when (node) {
          is TrailNode.Directory -> onToggleExpand(node)
          is TrailNode.TrailFile -> onFileClick(node)
        }
      },
    color = if (isSelected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surface
    }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp, horizontal = 8.dp)
        .padding(start = indentWidth),
      verticalAlignment = Alignment.CenterVertically
    ) {
      when (node) {
        is TrailNode.Directory -> {
          // Expand/collapse icon
          Icon(
            imageVector = if (node.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (node.isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
          
          Spacer(modifier = Modifier.width(4.dp))
          
          // Folder icon
          Icon(
            imageVector = if (node.isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
            contentDescription = "Folder",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
          )
          
          Spacer(modifier = Modifier.width(8.dp))
          
          // Directory name
          Text(
            text = "${node.name}/ (${node.children.size})",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        
        is TrailNode.TrailFile -> {
          // Spacer for alignment with directories
          Spacer(modifier = Modifier.width(20.dp))
          
          // File icon with badge
          TrailFileIcon(
            existingTrail = node.existingTrail,
            modifier = Modifier.size(20.dp)
          )
          
          Spacer(modifier = Modifier.width(8.dp))
          
          // File name
          Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (node.existingTrail.isDefaultTrailFile) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}
