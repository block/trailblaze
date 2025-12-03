@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * Data class representing a selector option with its strategy and YAML representation.
 */
data class SelectorOptionDisplay(
  val yamlSelector: String,
  val strategy: String,
  val isSimplified: Boolean = false,
  val isBest: Boolean = false, // True if this is the selector returned by findBestTrailblazeElementSelectorForTargetNode
)

/**
 * Data class containing property uniqueness information for a node.
 */
data class PropertyUniquenessDisplay(
  val text: String?,
  val textIsUnique: Boolean,
  val textOccurrences: Int,
  val textMatchingNodeIds: List<Long>,
  val id: String?,
  val idIsUnique: Boolean,
  val idOccurrences: Int,
  val idMatchingNodeIds: List<Long>,
)

/**
 * Combined result containing both selector options and property uniqueness analysis.
 */
data class SelectorAnalysisResult(
  val selectorOptions: List<SelectorOptionDisplay>,
  val propertyUniqueness: PropertyUniquenessDisplay?,
)

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
  // Screenshot width is fixed, details/hierarchy use weight ratio with persistence
  screenshotWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH,
  detailsWeight: Float = 1f,
  hierarchyWeight: Float = 1f,
  showRawJson: Boolean = false,
  fontScale: Float = 1f,
  onDetailsWeightChanged: (Float) -> Unit = {},
  onHierarchyWeightChanged: (Float) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onShowRawJsonChanged: (Boolean) -> Unit = {},
  onClose: () -> Unit = {},
  // Callback to compute selector analysis (options + uniqueness) for a given node
  // This is platform-specific and only available in JVM
  computeSelectorOptions: ((ViewHierarchyTreeNode) -> SelectorAnalysisResult)? = null,
) {
  var selectedNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }
  var hoveredNode by remember { mutableStateOf<ViewHierarchyTreeNode?>(null) }
  var showFilteredHierarchy by remember { mutableStateOf(false) }
  var highlightedNodeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

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

  // Screenshot has fixed width, details and hierarchy use weights
  var currentDetailsWeight by remember { mutableStateOf(detailsWeight) }
  var currentHierarchyWeight by remember { mutableStateOf(hierarchyWeight) }

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
      // Left: Screenshot panel with overlays (fixed width, non-resizable)
      Box(
        modifier = Modifier
          .width(screenshotWidth.dp)
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

      Spacer(modifier = Modifier.width(8.dp))

      // Middle: Element details panel (stretches with weight)
      Box(
        modifier = Modifier
          .weight(currentDetailsWeight)
          .fillMaxHeight()
      ) {
        Card(
          modifier = Modifier.fillMaxSize(),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
          )
        ) {
          NodeDetailsPanel(
            selectedNode = selectedNode,
            hoveredNode = hoveredNode,
            viewHierarchy = viewHierarchy,
            fontScale = fontScale,
            modifier = Modifier.fillMaxSize(),
            computeSelectorOptions = computeSelectorOptions,
            onNodeSelected = { selectedNode = it },
            onHighlightedNodeIdsChange = { highlightedNodeIds = it }
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
              // Adjust the weight ratio based on drag
              val dragDp = dragAmount / density.density
              val weightAdjustment = dragDp / 100f // Adjust sensitivity

              val newDetailsWeight = (currentDetailsWeight + weightAdjustment).coerceAtLeast(0.3f)
              val newHierarchyWeight = (currentHierarchyWeight - weightAdjustment).coerceAtLeast(0.3f)

              currentDetailsWeight = newDetailsWeight
              currentHierarchyWeight = newHierarchyWeight

              onDetailsWeightChanged(newDetailsWeight)
              onHierarchyWeightChanged(newHierarchyWeight)
            }
          }
          .background(MaterialTheme.colorScheme.outlineVariant)
      )

      // Right: View hierarchy panel (stretches with weight)
      Box(
        modifier = Modifier
          .weight(currentHierarchyWeight)
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
                hoveredNode = hoveredNode,
                highlightedNodeIds = highlightedNodeIds,
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
  hoveredNode: ViewHierarchyTreeNode?,
  highlightedNodeIds: Set<Long>,
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
      ViewHierarchyTreeItem(
        node = viewHierarchy,
        selectedNode = selectedNode,
        hoveredNode = hoveredNode,
        highlightedNodeIds = highlightedNodeIds,
        onNodeSelected = onNodeSelected,
        fontScale = fontScale
      )
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
  hoveredNode: ViewHierarchyTreeNode?,
  highlightedNodeIds: Set<Long>,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit,
  fontScale: Float,
  modifier: Modifier = Modifier,
  level: Int = 0,
) {
  // Determine the display node (hovered takes priority over selected for highlighting)
  val isSelected = node == selectedNode
  val isHovered = node == hoveredNode
  val isMatchHighlighted = node.nodeId in highlightedNodeIds
  val isHighlighted = isHovered || isSelected || isMatchHighlighted

  Column(modifier = modifier) {
    // Current node
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onNodeSelected(node) }
        .background(
          when {
            isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
            isMatchHighlighted -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) // Amber/warning color for matches
            else -> Color.Transparent
          }
        )
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
        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
        hoveredNode = hoveredNode,
        highlightedNodeIds = highlightedNodeIds,
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
  hoveredNode: ViewHierarchyTreeNode?,
  viewHierarchy: ViewHierarchyTreeNode,
  fontScale: Float,
  modifier: Modifier = Modifier,
  computeSelectorOptions: ((ViewHierarchyTreeNode) -> SelectorAnalysisResult)? = null,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit = {},
  onHighlightedNodeIdsChange: (Set<Long>) -> Unit = {},
) {
  // Show hovered node if available, otherwise show selected node
  val displayNode = hoveredNode ?: selectedNode

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

    if (displayNode != null) {
      // Compute selector analysis asynchronously if available
      var selectorAnalysis by remember { mutableStateOf<SelectorAnalysisResult?>(null) }
      var selectorError by remember { mutableStateOf<String?>(null) }
      var isComputingSelectors by remember { mutableStateOf(false) }

      LaunchedEffect(displayNode?.nodeId, computeSelectorOptions) {
        if (computeSelectorOptions == null || displayNode == null) {
          // No computation available
          isComputingSelectors = false
          selectorAnalysis = null
          selectorError = null
          return@LaunchedEffect
        }

        // Reset state and start computing
        isComputingSelectors = true
        selectorAnalysis = null
        selectorError = null

        try {
          // Run in a background coroutine to avoid blocking UI
          val result = withContext(Dispatchers.Default) {
            computeSelectorOptions.invoke(displayNode)
          }

          // Success - update state
          selectorAnalysis = result
          isComputingSelectors = false
        } catch (e: CancellationException) {
          // Silently ignore cancellation - this is expected when hovering quickly
          throw e
        } catch (e: Exception) {
          // Error - update state
          e.printStackTrace()
          selectorError = "Failed to compute selectors: ${e.message}"
          isComputingSelectors = false
        }
      }

      Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
      ) {
        Text(
          text = "Properties",
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        DetailRow(
          label = "Node ID",
          value = displayNode.nodeId.toString(),
          fontScale = fontScale
        )

        if (!displayNode.text.isNullOrBlank()) {
          DetailRow(
            label = "Text",
            value = displayNode.text!!,
            fontScale = fontScale
          )
        }

        if (!displayNode.hintText.isNullOrBlank()) {
          DetailRow(
            label = "Hint",
            value = displayNode.hintText!!,
            fontScale = fontScale
          )
        }

        if (!displayNode.accessibilityText.isNullOrBlank()) {
          DetailRow(
            label = "Content Description",
            value = displayNode.accessibilityText!!,
            fontScale = fontScale
          )
        }

        displayNode.resourceId?.let {
          DetailRow(
            label = "Resource ID",
            value = it,
            fontScale = fontScale
          )
        }

        displayNode.className?.let {
          DetailRow(
            label = "Class",
            value = it,
            fontScale = fontScale
          )
        }

        displayNode.bounds?.let { bounds ->
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
          text = "State Properties",
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        PropertiesGrid(displayNode, fontScale)

        if (displayNode.children.isNotEmpty()) {
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Children (${displayNode.children.size})",
            style = MaterialTheme.typography.titleMedium.copy(
              fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
            ),
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(8.dp))

          displayNode.children.forEach { child ->
            ChildNodeItem(
              child = child,
              fontScale = fontScale,
              onNodeSelected = onNodeSelected
            )
          }
        }

        // Show selector options section after properties (only if computation is available)
        if (computeSelectorOptions != null) {
          Spacer(modifier = Modifier.height(16.dp))

          // Show property uniqueness first
          val currentAnalysis = selectorAnalysis
          if (currentAnalysis?.propertyUniqueness != null) {
            PropertyUniquenessCard(
              uniqueness = currentAnalysis.propertyUniqueness,
              fontScale = fontScale,
              viewHierarchy = viewHierarchy,
              onNodeSelected = onNodeSelected,
              computeSelectorOptions = computeSelectorOptions
            )
            Spacer(modifier = Modifier.height(16.dp))
          }

          Text(
            text = "Selector Options",
            style = MaterialTheme.typography.titleMedium.copy(
              fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
            ),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
          )
          Spacer(modifier = Modifier.height(8.dp))

          when {
            isComputingSelectors -> {
              Text(
                text = "Computing selectors...",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            selectorError != null -> {
              Text(
                text = "⚠️ $selectorError",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
                ),
                color = MaterialTheme.colorScheme.error
              )
            }

            currentAnalysis?.selectorOptions?.isNotEmpty() == true -> {
              currentAnalysis.selectorOptions.forEach { option ->
                SelectorOptionCard(option, fontScale)
                Spacer(modifier = Modifier.height(8.dp))
              }
            }

            !isComputingSelectors && currentAnalysis?.selectorOptions?.isEmpty() == true && selectorError == null -> {
              SelectableText(
                text = "No selectors available for this node",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
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
private fun SelectorOptionCard(
  option: SelectorOptionDisplay,
  fontScale: Float,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = when {
        option.isBest -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) // Highlight the best selector
        option.isSimplified -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
      }
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
          text = option.strategy,
          style = MaterialTheme.typography.labelMedium.copy(
            fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.primary
        )

        // Show badge for the production default selector
        if (option.isBest) {
          Card(
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.padding(start = 8.dp)
          ) {
            Text(
              text = "DEFAULT",
              style = MaterialTheme.typography.labelSmall.copy(
                fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
              ),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(4.dp))

      // Code block for YAML selector
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
          )
          .padding(8.dp)
      ) {
        SelectableText(
          text = option.yamlSelector,
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
            fontFamily = FontFamily.Monospace
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun PropertyUniquenessCard(
  uniqueness: PropertyUniquenessDisplay,
  fontScale: Float,
  viewHierarchy: ViewHierarchyTreeNode,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit = {},
  computeSelectorOptions: ((ViewHierarchyTreeNode) -> SelectorAnalysisResult)? = null,
) {
  var textExpanded by remember { mutableStateOf(false) }
  var idExpanded by remember { mutableStateOf(false) }

  // Store computed default selectors for matching nodes
  var defaultSelectorsByNodeId by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

  // Compute selectors for text matching nodes when expanded
  LaunchedEffect(textExpanded, uniqueness.textMatchingNodeIds, computeSelectorOptions) {
    if (textExpanded && computeSelectorOptions != null) {
      val allNodes = viewHierarchy.aggregate()
      val newSelectors = mutableMapOf<Long, String>()

      uniqueness.textMatchingNodeIds.forEach { nodeId ->
        allNodes.find { it.nodeId == nodeId }?.let { node ->
          try {
            val result = withContext(Dispatchers.Default) {
              computeSelectorOptions.invoke(node)
            }
            // Find the default selector (isBest = true)
            result.selectorOptions.find { it.isBest }?.yamlSelector?.let { selector ->
              newSelectors[nodeId] = selector
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // Silently ignore errors for individual nodes
          }
        }
      }

      defaultSelectorsByNodeId = defaultSelectorsByNodeId + newSelectors
    }
  }

  // Compute selectors for ID matching nodes when expanded
  LaunchedEffect(idExpanded, uniqueness.idMatchingNodeIds, computeSelectorOptions) {
    if (idExpanded && computeSelectorOptions != null) {
      val allNodes = viewHierarchy.aggregate()
      val newSelectors = mutableMapOf<Long, String>()

      uniqueness.idMatchingNodeIds.forEach { nodeId ->
        allNodes.find { it.nodeId == nodeId }?.let { node ->
          try {
            val result = withContext(Dispatchers.Default) {
              computeSelectorOptions.invoke(node)
            }
            // Find the default selector (isBest = true)
            result.selectorOptions.find { it.isBest }?.yamlSelector?.let { selector ->
              newSelectors[nodeId] = selector
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // Silently ignore errors for individual nodes
          }
        }
      }

      defaultSelectorsByNodeId = defaultSelectorsByNodeId + newSelectors
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    )
  ) {
    Column(
      modifier = Modifier.padding(12.dp)
    ) {
      Text(
        text = "Property Uniqueness",
        style = MaterialTheme.typography.labelMedium.copy(
          fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
        ),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
      )
      Spacer(modifier = Modifier.height(8.dp))

      // Text property
      if (uniqueness.text != null) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Text: \"${uniqueness.text}\"",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
                  fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Card(
              colors = CardDefaults.cardColors(
                containerColor = if (uniqueness.textIsUnique) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.error
                }
              ),
              modifier = if (!uniqueness.textIsUnique) {
                Modifier.clickable { textExpanded = !textExpanded }
              } else Modifier
            ) {
              Text(
                text = if (uniqueness.textIsUnique) {
                  "UNIQUE ✓"
                } else {
                  "${uniqueness.textOccurrences}x (${if (textExpanded) "hide" else "show matching"})"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
                ),
                fontWeight = FontWeight.Bold,
                color = if (uniqueness.textIsUnique) {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onError
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          // Show matching nodes when expanded
          if (textExpanded && !uniqueness.textIsUnique) {
            Spacer(modifier = Modifier.height(8.dp))
            val allNodes = remember(viewHierarchy) { viewHierarchy.aggregate() }
            val matchingNodes = uniqueness.textMatchingNodeIds.mapNotNull { nodeId ->
              allNodes.find { it.nodeId == nodeId }
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(8.dp)
            ) {
              Text(
                text = "Matching nodes:",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(4.dp))

              matchingNodes.forEach { node ->
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNodeSelected(node) }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .padding(4.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = "#${node.nodeId}",
                      style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale,
                        fontFamily = FontFamily.Monospace
                      ),
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.width(60.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = node.resolveMaestroText() ?: node.resourceId ?: node.className ?: "(empty)",
                      style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
                        fontFamily = FontFamily.Monospace
                      ),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  }

                  // Show default selector if available
                  defaultSelectorsByNodeId[node.nodeId]?.let { selector ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .background(
                          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        )
                        .padding(6.dp)
                    ) {
                      Text(
                        text = selector,
                        style = MaterialTheme.typography.bodySmall.copy(
                          fontSize = (MaterialTheme.typography.bodySmall.fontSize * fontScale * 0.9f),
                          fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                }
              }
            }
          }
        }
      } else {
        Text(
          text = "Text: (none)",
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // ID property
      if (uniqueness.id != null) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "ID: \"${uniqueness.id}\"",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
                  fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Card(
              colors = CardDefaults.cardColors(
                containerColor = if (uniqueness.idIsUnique) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.error
                }
              ),
              modifier = if (!uniqueness.idIsUnique) {
                Modifier.clickable { idExpanded = !idExpanded }
              } else Modifier
            ) {
              Text(
                text = if (uniqueness.idIsUnique) {
                  "UNIQUE ✓"
                } else {
                  "${uniqueness.idOccurrences}x (${if (idExpanded) "hide" else "show matching"})"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
                ),
                fontWeight = FontWeight.Bold,
                color = if (uniqueness.idIsUnique) {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onError
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          // Show matching nodes when expanded
          if (idExpanded && !uniqueness.idIsUnique) {
            Spacer(modifier = Modifier.height(8.dp))
            val allNodes = remember(viewHierarchy) { viewHierarchy.aggregate() }
            val matchingNodes = uniqueness.idMatchingNodeIds.mapNotNull { nodeId ->
              allNodes.find { it.nodeId == nodeId }
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(8.dp)
            ) {
              Text(
                text = "Matching nodes:",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(4.dp))

              matchingNodes.forEach { node ->
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNodeSelected(node) }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .padding(4.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = "#${node.nodeId}",
                      style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale,
                        fontFamily = FontFamily.Monospace
                      ),
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.width(60.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = node.resourceId ?: node.resolveMaestroText() ?: node.className ?: "(empty)",
                      style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
                        fontFamily = FontFamily.Monospace
                      ),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  }

                  // Show default selector if available
                  defaultSelectorsByNodeId[node.nodeId]?.let { selector ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .background(
                          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        )
                        .padding(6.dp)
                    ) {
                      Text(
                        text = selector,
                        style = MaterialTheme.typography.bodySmall.copy(
                          fontSize = (MaterialTheme.typography.bodySmall.fontSize * fontScale * 0.9f),
                          fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                }
              }
            }
          }
        }
      } else {
        Text(
          text = "ID: (none)",
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
      }
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
  fontScale: Float,
  onNodeSelected: (ViewHierarchyTreeNode) -> Unit = {},
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
      .clickable { onNodeSelected(child) },
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
