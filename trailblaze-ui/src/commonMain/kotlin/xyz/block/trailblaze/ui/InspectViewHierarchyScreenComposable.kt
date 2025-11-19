@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.ui.composables.SelectableText
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InspectViewHierarchyScreenComposable(
  sessionId: String,
  viewHierarchy: ViewHierarchyTreeNode,
  viewHierarchyFiltered: ViewHierarchyTreeNode? = null,
  imageUrl: String?,
  deviceWidth: Int,
  deviceHeight: Int,
  imageLoader: ImageLoader,
  // Column widths and font scale with persistence
  initialScreenshotWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH,
  initialDetailsWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_DETAILS_WIDTH,
  initialHierarchyWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_HIERARCHY_WIDTH,
  showRawJson: Boolean = false,
  fontScale: Float = 1f,
  onScreenshotWidthChanged: (Int) -> Unit = {},
  onDetailsWidthChanged: (Int) -> Unit = {},
  onHierarchyWidthChanged: (Int) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onShowRawJsonChanged: (Boolean) -> Unit = {},
  onClose: () -> Unit = {},
) {
  var selectedNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }
  var hoveredNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }
  var showFilteredHierarchy by remember { mutableStateOf(false) }

  // Calculate counts once - use unique node IDs to avoid counting duplicates
  val viewHierarchyCount = remember(viewHierarchy) {
    viewHierarchy.aggregate().size
  }
  val viewHierarchyFilteredCount = remember(viewHierarchyFiltered) {
    viewHierarchyFiltered?.aggregate()?.size
  }

  // Use filtered hierarchy if toggled on and available, otherwise use unfiltered
  val activeHierarchy = if (showFilteredHierarchy && viewHierarchyFiltered != null) {
    viewHierarchyFiltered
  } else {
    viewHierarchy
  }

  // Reset selected and hovered nodes when switching hierarchies
  LaunchedEffect(activeHierarchy) {
    selectedNode = null
    hoveredNode = null
  }

  // Column widths in dp
  var screenshotWidth by remember { mutableStateOf(initialScreenshotWidth.dp) }
  var detailsWidth by remember { mutableStateOf(initialDetailsWidth.dp) }
  var hierarchyWidth by remember { mutableStateOf(initialHierarchyWidth.dp) }

  val density = LocalDensity.current

  Column(modifier = Modifier.fillMaxSize()) {
    // Header with close button and controls
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "UI Inspector",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
      )

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Filter toggle button (only show if filtered hierarchy is available)
        if (viewHierarchyFiltered != null) {
          Button(
            onClick = { showFilteredHierarchy = !showFilteredHierarchy },
            colors = ButtonDefaults.buttonColors(
              containerColor = if (showFilteredHierarchy) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.tertiary
              }
            )
          ) {
            Text(
              text = if (showFilteredHierarchy) {
                "Filtered ($viewHierarchyFilteredCount nodes)"
              } else {
                "Unfiltered ($viewHierarchyCount nodes)"
              }
            )
          }
        }

        // Font size controls
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .background(
              MaterialTheme.colorScheme.surfaceVariant,
              shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(2.dp)
        ) {
          IconButton(
            onClick = {
              val newScale = (fontScale - 0.1f).coerceAtLeast(0.5f)
              onFontScaleChanged(newScale)
            },
            enabled = fontScale > 0.5f
          ) {
            Icon(Icons.Outlined.TextDecrease, contentDescription = "Decrease font size")
          }
          IconButton(
            onClick = {
              val newScale = (fontScale + 0.1f).coerceAtMost(2f)
              onFontScaleChanged(newScale)
            },
            enabled = fontScale < 2f
          ) {
            Icon(Icons.Outlined.TextIncrease, contentDescription = "Increase font size")
          }
        }

        // Toggle button
        Button(
          onClick = { onShowRawJsonChanged(!showRawJson) },
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
          )
        ) {
          Text(text = if (showRawJson) "Show Tree" else "Show JSON")
        }

        Button(onClick = onClose) {
          Text("Close")
        }
      }
    }

    // Main content row with panels
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
      // Left: Screenshot panel with overlays
      Box(
        modifier = Modifier
          .width(screenshotWidth)
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
                viewHierarchy = activeHierarchy,
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

      // Resizer between screenshot and details
      Box(
        modifier = Modifier
          .width(8.dp)
          .fillMaxHeight()
          .pointerInput(Unit) {
            detectHorizontalDragGestures { change, dragAmount ->
              change.consume()
              val newWidth = screenshotWidth + with(density) { dragAmount.toDp() }
              screenshotWidth = newWidth.coerceIn(300.dp, 1200.dp)
              onScreenshotWidthChanged(screenshotWidth.value.toInt())
            }
          }
          .background(MaterialTheme.colorScheme.outlineVariant)
      )

      // Middle: Element details panel
      Box(
        modifier = Modifier
          .width(detailsWidth)
          .fillMaxHeight()
      ) {
        Card(
          modifier = Modifier.fillMaxSize(),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
          )
        ) {
          NodeDetailsPanel(
            selectedNode = selectedNode ?: hoveredNode,
            fontScale = fontScale,
            modifier = Modifier.fillMaxSize()
          )
        }
      }

      // Resizer between details and hierarchy
      Box(
        modifier = Modifier
          .width(8.dp)
          .fillMaxHeight()
          .pointerInput(Unit) {
            detectHorizontalDragGestures { change, dragAmount ->
              change.consume()
              val newWidth = detailsWidth + with(density) { dragAmount.toDp() }
              detailsWidth = newWidth.coerceIn(250.dp, 800.dp)
              onDetailsWidthChanged(detailsWidth.value.toInt())
            }
          }
          .background(MaterialTheme.colorScheme.outlineVariant)
      )

      // Right: View hierarchy panel
      Box(
        modifier = Modifier
          .width(hierarchyWidth)
          .fillMaxHeight()
      ) {
        Card(
          modifier = Modifier.fillMaxSize(),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
          )
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            // Title for the panel with copy button
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = if (showRawJson) "Raw JSON" else "View Hierarchy",
                style = MaterialTheme.typography.headlineSmall.copy(
                  fontSize = MaterialTheme.typography.headlineSmall.fontSize * fontScale
                ),
                fontWeight = FontWeight.Bold
              )

              // Copy button
              val clipboardManager = LocalClipboardManager.current
              Button(
                onClick = {
                  val jsonString = Json { prettyPrint = true }.encodeToString(activeHierarchy)
                  clipboardManager.setText(AnnotatedString(jsonString))
                }
              ) {
                Text("Copy View Hierarchy")
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (showRawJson) {
              RawJsonPanel(
                viewHierarchy = activeHierarchy,
                fontScale = fontScale,
                modifier = Modifier.weight(1f).fillMaxWidth()
              )
            } else {
              ViewHierarchyTreePanel(
                viewHierarchy = activeHierarchy,
                selectedNode = selectedNode,
                onNodeSelected = { selectedNode = it },
                fontScale = fontScale,
                modifier = Modifier
                  .weight(1f)
                  .fillMaxWidth()
              )
            }
          }
        }
      }

      // Resizer for hierarchy panel width
      Box(
        modifier = Modifier
          .width(8.dp)
          .fillMaxHeight()
          .pointerInput(Unit) {
            detectHorizontalDragGestures { change, dragAmount ->
              change.consume()
              val newWidth = hierarchyWidth + with(density) { dragAmount.toDp() }
              hierarchyWidth = newWidth.coerceIn(350.dp, 1000.dp)
              onHierarchyWidthChanged(hierarchyWidth.value.toInt())
            }
          }
          .background(MaterialTheme.colorScheme.outlineVariant)
      )
    }
  }

  // Debug: Print node counts and check for duplicates
  LaunchedEffect(viewHierarchy, viewHierarchyFiltered) {
    println("=== View Hierarchy Debug ===")
    println("Main hierarchy node count: $viewHierarchyCount")
    println("Alt hierarchy node count: $viewHierarchyFilteredCount")

    // Check for duplicate node IDs in each hierarchy
    val mainNodes = viewHierarchy.aggregate()
    val mainNodeIds = mainNodes.map { it.nodeId }
    val mainDuplicates = mainNodeIds.groupingBy { it }.eachCount().filter { it.value > 1 }
    if (mainDuplicates.isNotEmpty()) {
      println("WARNING: Main hierarchy has duplicate node IDs: $mainDuplicates")
    }

    viewHierarchyFiltered?.let { filtered ->
      val filteredNodes = filtered.aggregate()
      val filteredNodeIds = filteredNodes.map { it.nodeId }
      val filteredDuplicates = filteredNodeIds.groupingBy { it }.eachCount().filter { it.value > 1 }
      if (filteredDuplicates.isNotEmpty()) {
        println("WARNING: Alt hierarchy has duplicate node IDs: $filteredDuplicates")
      }

      // Show unique node count
      val uniqueCount = filteredNodeIds.toSet().size
      println(
        "Alt hierarchy unique node count: $uniqueCount (total including duplicates: ${filteredNodeIds.size})"
      )
    }
    println("===========================")
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

  // Get the image model directly from imageLoader (pre-loaded for WASM, direct for JVM)
  // This is cached and won't change, preventing flickering
  val imageModel = remember(sessionId, screenshotFile, imageLoader) {
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
  // Only draw overlay if the node is selected or hovered
  if (!isSelected && !isHovered) {
    return
  }

  val left = bounds.x1 * scaleX
  val top = bounds.y1 * scaleY
  val right = bounds.x2 * scaleX
  val bottom = bounds.y2 * scaleY

  // Determine colors and stroke width based on state
  val strokeColor = when {
    isSelected -> Color(0xFF2196F3)  // Blue for selected
    isHovered -> Color(0xFF4CAF50)   // Green for hovered
    else -> Color(0xFF9E9E9E)        // Gray for normal (fallback, shouldn't reach here)
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
private fun ViewHierarchyTreePanel(
  viewHierarchy: ViewHierarchyTreeNode,
  selectedNode: ViewHierarchyTreeNode?,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState())
        .padding(16.dp)
    ) {
      ViewHierarchyTreeItem(viewHierarchy, selectedNode, onNodeSelected, fontScale = fontScale)
    }
  }
}

@Composable
private fun RawJsonPanel(
  viewHierarchy: ViewHierarchyTreeNode,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  val jsonString = remember(viewHierarchy) {
    Json { prettyPrint = true }.encodeToString(viewHierarchy)
  }
  Box(
    modifier = modifier
  ) {
    Box(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState())
        .padding(16.dp)
    ) {
      SelectableText(
        text = jsonString,
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun ViewHierarchyTreeItem(
  node: ViewHierarchyTreeNode,
  selectedNode: ViewHierarchyTreeNode?,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit,
  fontScale: Float,
  modifier: Modifier = Modifier,
  level: Int = 0,
) {
  Column(modifier = modifier) {
    // Current node
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onNodeSelected(node) }
        .padding(start = (level * 16).dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Node ID
      Text(
        text = "${node.nodeId}",
        style = MaterialTheme.typography.labelMedium.copy(
          fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        fontWeight = if (node == selectedNode) FontWeight.Bold else FontWeight.Normal,
        color = if (node == selectedNode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.width(8.dp))

      // Display the most relevant text information
      val displayText = when {
        !node.text.isNullOrBlank() -> "\"${node.text}\""
        !node.accessibilityText.isNullOrBlank() -> "[${node.accessibilityText}]"
        !node.resourceId.isNullOrBlank() -> "#${node.resourceId}"
        !node.className.isNullOrBlank() -> "<${
          node.className?.split(".")
            ?.lastOrNull() ?: node.className
        }>"

        else -> "(empty)"
      }

      Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        softWrap = false
      )
    }

    // Children nodes recursively
    node.children.forEach { child ->
      ViewHierarchyTreeItem(
        node = child,
        selectedNode = selectedNode,
        onNodeSelected = onNodeSelected,
        fontScale = fontScale,
        level = level + 1
      )
    }
  }
}

@Composable
private fun NodeDetailsPanel(
  selectedNode: ViewHierarchyTreeNode?,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .padding(16.dp)
  ) {
    Text(
      text = "Element Details",
      style = MaterialTheme.typography.headlineSmall.copy(
        fontSize = MaterialTheme.typography.headlineSmall.fontSize * fontScale
      ),
      fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (selectedNode != null) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
      ) {
        DetailRow(
          label = "Node ID",
          value = selectedNode.nodeId.toString(),
          fontScale = fontScale
        )

        if (!selectedNode.text.isNullOrBlank()) {
          DetailRow(
            label = "Text",
            value = selectedNode.text!!,
            fontScale = fontScale
          )
        }

        if (!selectedNode.hintText.isNullOrBlank()) {
          DetailRow(
            label = "Hint",
            value = selectedNode.hintText!!,
            fontScale = fontScale
          )
        }

        if (!selectedNode.accessibilityText.isNullOrBlank()) {
          DetailRow(
            label = "Content Description",
            value = selectedNode.accessibilityText!!,
            fontScale = fontScale
          )
        }

        selectedNode.resourceId?.let {
          DetailRow(
            label = "Resource ID",
            value = it,
            fontScale = fontScale
          )
        }

        selectedNode.className?.let {
          DetailRow(
            label = "Class",
            value = it,
            fontScale = fontScale
          )
        }

        selectedNode.bounds?.let { bounds ->
          DetailRow(
            label = "Bounds",
            value = "${bounds.x1},${bounds.y1} - ${bounds.x2},${bounds.y2}",
            fontScale = fontScale
          )
          DetailRow(
            label = "Size",
            value = "${bounds.width}x${bounds.height}",
            fontScale = fontScale
          )
          DetailRow(
            label = "Center",
            value = "${bounds.centerX},${bounds.centerY}",
            fontScale = fontScale
          )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Properties",
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        PropertiesGrid(selectedNode, fontScale)

        if (selectedNode.children.isNotEmpty()) {
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Children (${selectedNode.children.size})",
            style = MaterialTheme.typography.titleMedium.copy(
              fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
            ),
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(8.dp))

          selectedNode.children.forEach { child ->
            ChildNodeItem(child, fontScale)
          }
        }
      }
    } else {
      Text(
        text = "Hover or click on an element in the screenshot to see its details.",
        style = MaterialTheme.typography.bodyMedium.copy(
          fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium.copy(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
      ),
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Medium
    )
    SelectableText(
      text = value,
      style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale
      ),
      modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
  }
}

@Composable
private fun PropertiesGrid(
  node: ViewHierarchyTreeNode,
  fontScale: Float
) {
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
            style = MaterialTheme.typography.labelSmall.copy(
              fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
            ),
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    }
  } else {
    Text(
      text = "No active properties",
      style = MaterialTheme.typography.bodySmall.copy(
        fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
      ),
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun ChildNodeItem(
  child: ViewHierarchyTreeNode,
  fontScale: Float
) {
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
          style = MaterialTheme.typography.labelMedium.copy(
            fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.Medium
        )
        if (child.children.isNotEmpty()) {
          Text(
            text = "${child.children.size} children",
            style = MaterialTheme.typography.labelSmall.copy(
              fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (!child.text.isNullOrBlank()) {
        Text(
          text = child.text!!,
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      } else if (!child.className.isNullOrEmpty()) {
        Text(
          text = child.className!!,
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      }
    }
  }
}
