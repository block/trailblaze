@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.ui.composables.SelectableText

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InspectViewHierarchyScreenComposable(
  sessionId: String,
  viewHierarchy: ViewHierarchyTreeNode,
  imageUrl: String?,
  deviceWidth: Int,
  deviceHeight: Int,
) {
  var selectedNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }
  var hoveredNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }

  val imageLoader = remember { createLogsFileSystemImageLoader() }

  Row(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp)
  ) {
    // Main content area with screenshot and overlays
    Box(
      modifier = Modifier
        .weight(0.7f)
        .fillMaxHeight()
    ) {
      Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
          contentAlignment = Alignment.Center
        ) {
          if (imageUrl != null) {
            ViewHierarchyInspector(
              sessionId = sessionId,
              screenshotFile = imageUrl,
              viewHierarchy = viewHierarchy,
              deviceWidth = deviceWidth,
              deviceHeight = deviceHeight,
              selectedNode = selectedNode,
              hoveredNode = hoveredNode,
              onNodeSelected = { selectedNode = it },
              onNodeHovered = { hoveredNode = it },
              imageLoader = imageLoader
            )
          } else {
            Text(
              text = "No screenshot available",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.width(16.dp))

    // Side panel with node details
    Card(
      modifier = Modifier
        .width(350.dp)
        .fillMaxHeight(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
      )
    ) {
      NodeDetailsPanel(
        selectedNode = selectedNode ?: hoveredNode,
        modifier = Modifier.fillMaxSize()
      )
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ViewHierarchyInspector(
  sessionId: String,
  screenshotFile: String,
  viewHierarchy: ViewHierarchyTreeNode,
  deviceWidth: Int,
  deviceHeight: Int,
  selectedNode: ViewHierarchyTreeNode?,
  hoveredNode: ViewHierarchyTreeNode?,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit,
  onNodeHovered: (ViewHierarchyTreeNode?) -> Unit,
  imageLoader: ImageLoader,
) {
  val density = LocalDensity.current
  val allNodes = remember(viewHierarchy) { viewHierarchy.aggregate() }
  val imageModel = remember(sessionId, screenshotFile) {
    imageLoader.getImageModel(sessionId, screenshotFile)
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    if (imageModel != null) {
      // Screenshot as background
      AsyncImage(
        model = imageModel,
        contentDescription = "App Screenshot",
        modifier = Modifier
          .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
          .fillMaxSize()
          .defaultMinSize(minWidth = 200.dp, minHeight = 200.dp)
          .clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Fit
      )

      // Overlays for each UI element
      Canvas(
        modifier = Modifier
          .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
          .fillMaxSize()
          .onPointerEvent(PointerEventType.Move) { event ->
            val position = event.changes.first().position

            // Convert screen coordinates to device coordinates
            val canvasSize = size
            val scaleX = deviceWidth.toFloat() / canvasSize.width
            val scaleY = deviceHeight.toFloat() / canvasSize.height
            val deviceX = (position.x * scaleX).toInt()
            val deviceY = (position.y * scaleY).toInt()

            // Find the topmost (smallest) node that contains this point
            val hitNode = allNodes
              .filter { node ->
                node.bounds?.let { bounds ->
                  deviceX >= bounds.x1 && deviceX <= bounds.x2 &&
                      deviceY >= bounds.y1 && deviceY <= bounds.y2
                } ?: false
              }
              .minByOrNull { node ->
                val bounds = node.bounds!!
                (bounds.x2 - bounds.x1) * (bounds.y2 - bounds.y1)  // Smallest area
              }

            onNodeHovered(hitNode)
          }
          .onPointerEvent(PointerEventType.Exit) {
            onNodeHovered(null)
          }
          .onPointerEvent(PointerEventType.Press) { event ->
            // Handle clicks only within this Canvas area and consume the event
            hoveredNode?.let { onNodeSelected(it) }
            // Consume the event to prevent it from bubbling to other handlers
            event.changes.forEach { it.consume() }
          }
      ) {
        val scaleX = size.width / deviceWidth
        val scaleY = size.height / deviceHeight

        allNodes.forEach { node ->
          node.bounds?.let { bounds ->
            drawNodeOverlay(
              bounds = bounds,
              scaleX = scaleX,
              scaleY = scaleY,
              isSelected = node == selectedNode,
              isHovered = node == hoveredNode,
              node = node
            )
          }
        }
      }
    } else {
      Text(
        text = "Failed to load screenshot",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

private fun DrawScope.drawNodeOverlay(
  bounds: ViewHierarchyFilter.Bounds,
  scaleX: Float,
  scaleY: Float,
  isSelected: Boolean,
  isHovered: Boolean,
  node: ViewHierarchyTreeNode,
) {
  val left = bounds.x1 * scaleX
  val top = bounds.y1 * scaleY
  val right = bounds.x2 * scaleX
  val bottom = bounds.y2 * scaleY

  // Determine colors and stroke width based on state
  val strokeColor = when {
    isSelected -> Color(0xFF2196F3)  // Blue for selected
    isHovered -> Color(0xFF4CAF50)   // Green for hovered
    else -> Color(0xFF9E9E9E)        // Gray for normal
  }

  val strokeWidth = when {
    isSelected -> 3f
    isHovered -> 2f
    else -> 0.5f
  }

  val alpha = when {
    isSelected -> 0.8f
    isHovered -> 0.6f
    else -> 0.3f
  }

  // Draw rectangle outline
  drawRect(
    color = strokeColor.copy(alpha = alpha),
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    style = Stroke(width = strokeWidth)
  )

  // Fill with slight transparency for interactable elements
  if (node.clickable || node.focusable || node.scrollable) {
    val fillAlpha = when {
      isSelected -> 0.2f
      isHovered -> 0.15f
      else -> 0.05f
    }

    drawRect(
      color = strokeColor.copy(alpha = fillAlpha),
      topLeft = Offset(left, top),
      size = Size(right - left, bottom - top)
    )
  }
}

@Composable
private fun NodeDetailsPanel(
  selectedNode: ViewHierarchyTreeNode?,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .padding(16.dp)
  ) {
    Text(
      text = "Element Details",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (selectedNode != null) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
      ) {
        DetailRow("Node ID", selectedNode.nodeId.toString())

        if (!selectedNode.text.isNullOrBlank()) {
          DetailRow("Text", selectedNode.text!!)
        }

        if (!selectedNode.accessibilityText.isNullOrBlank()) {
          DetailRow("Content Description", selectedNode.accessibilityText!!)
        }

        selectedNode.resourceId?.let {
          DetailRow("Resource ID", it)
        }

        selectedNode.className?.let {
          DetailRow("Class", it)
        }

        selectedNode.bounds?.let { bounds ->
          DetailRow("Bounds", "${bounds.x1},${bounds.y1} - ${bounds.x2},${bounds.y2}")
          DetailRow("Size", "${bounds.width}x${bounds.height}")
          DetailRow("Center", "${bounds.centerX},${bounds.centerY}")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Properties",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        PropertiesGrid(selectedNode)

        if (selectedNode.children.isNotEmpty()) {
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Children (${selectedNode.children.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(8.dp))

          selectedNode.children.forEach { child ->
            ChildNodeItem(child)
          }
        }
      }
    } else {
      Text(
        text = "Hover or click on an element in the screenshot to see its details.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Medium
    )
    SelectableText(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
  }
}

@Composable
private fun PropertiesGrid(node: ViewHierarchyTreeNode) {
  val properties = listOf(
    "Clickable" to node.clickable,
    "Enabled" to node.enabled,
    "Focusable" to node.focusable,
    "Focused" to node.focused,
    "Scrollable" to node.scrollable,
    "Selected" to node.selected,
    "Checked" to node.checked,
    "Password" to node.password
  ).filter { it.second } // Only show true properties

  if (properties.isNotEmpty()) {
    Column {
      properties.forEach { (property, _) ->
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
          )
        ) {
          Text(
            text = property,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    }
  } else {
    Text(
      text = "No active properties",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun ChildNodeItem(child: ViewHierarchyTreeNode) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
  ) {
    Column(
      modifier = Modifier.padding(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "ID: ${child.nodeId}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Medium
        )
        if (child.children.isNotEmpty()) {
          Text(
            text = "${child.children.size} children",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (!child.text.isNullOrBlank()) {
        Text(
          text = child.text!!,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      } else if (!child.className.isNullOrEmpty()) {
        Text(
          text = child.className!!,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      }
    }
  }
}
