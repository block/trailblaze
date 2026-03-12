@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader

/**
 * Screenshot overlay for TrailblazeNode - shows bounds rectangles on hover/select.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TrailblazeNodeInspector(
  sessionId: String,
  screenshotFile: String,
  trailblazeNodeTree: TrailblazeNode,
  deviceWidth: Int,
  deviceHeight: Int,
  selectedNode: TrailblazeNode?,
  hoveredNode: TrailblazeNode?,
  onNodeSelected: (TrailblazeNode) -> Unit,
  onNodeHovered: (TrailblazeNode?) -> Unit,
  imageLoader: ImageLoader,
) {
  val density = LocalDensity.current
  val allNodes = remember(trailblazeNodeTree) { trailblazeNodeTree.aggregate() }

  val imageModel = remember(sessionId, screenshotFile, imageLoader) {
    imageLoader.getImageModel(sessionId, screenshotFile)
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    if (imageModel != null) {
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

      Canvas(
        modifier = Modifier
          .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
          .fillMaxSize()
          .onPointerEvent(PointerEventType.Move) { event ->
            val position = event.changes.first().position
            val canvasSize = size
            val scaleX = deviceWidth.toFloat() / canvasSize.width
            val scaleY = deviceHeight.toFloat() / canvasSize.height
            val deviceX = (position.x * scaleX).toInt()
            val deviceY = (position.y * scaleY).toInt()

            val hitNode = allNodes
              .filter { node ->
                node.bounds?.containsPoint(deviceX, deviceY) == true
              }
              .minByOrNull { node ->
                val b = node.bounds!!
                b.width.toLong() * b.height.toLong()
              }

            onNodeHovered(hitNode)
          }
          .onPointerEvent(PointerEventType.Exit) {
            onNodeHovered(null)
          }
          .onPointerEvent(PointerEventType.Press) { event ->
            hoveredNode?.let { onNodeSelected(it) }
            event.changes.forEach { it.consume() }
          }
      ) {
        val scaleX = size.width / deviceWidth
        val scaleY = size.height / deviceHeight

        allNodes.forEach { node ->
          node.bounds?.let { bounds ->
            drawTrailblazeNodeOverlay(
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

private fun DrawScope.drawTrailblazeNodeOverlay(
  bounds: TrailblazeNode.Bounds,
  scaleX: Float,
  scaleY: Float,
  isSelected: Boolean,
  isHovered: Boolean,
  node: TrailblazeNode,
) {
  if (!isSelected && !isHovered) return

  val left = bounds.left * scaleX
  val top = bounds.top * scaleY
  val right = bounds.right * scaleX
  val bottom = bounds.bottom * scaleY

  val strokeColor = when {
    isSelected -> Color(0xFF2196F3)
    isHovered -> Color(0xFF4CAF50)
    else -> Color(0xFF9E9E9E)
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

  drawRect(
    color = strokeColor.copy(alpha = alpha),
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    style = Stroke(width = strokeWidth)
  )

  // Fill for interactive elements based on driver detail
  val isInteractive = when (val detail = node.driverDetail) {
    is DriverNodeDetail.AndroidAccessibility -> detail.isClickable || detail.isFocusable || detail.isScrollable
    is DriverNodeDetail.AndroidMaestro -> detail.clickable || detail.focusable || detail.scrollable
    is DriverNodeDetail.Web -> detail.isInteractive
    is DriverNodeDetail.Compose -> detail.hasClickAction || detail.hasScrollAction
  }

  if (isInteractive) {
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

/**
 * Tree panel for TrailblazeNode hierarchy.
 */
@Composable
internal fun TrailblazeNodeTreePanel(
  trailblazeNodeTree: TrailblazeNode,
  selectedNode: TrailblazeNode?,
  hoveredNode: TrailblazeNode?,
  highlightedNodeIds: Set<Long>,
  onNodeSelected: (TrailblazeNode) -> Unit,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    Column(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState())
        .padding(16.dp)
    ) {
      TrailblazeNodeTreeItem(
        node = trailblazeNodeTree,
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
private fun TrailblazeNodeTreeItem(
  node: TrailblazeNode,
  selectedNode: TrailblazeNode?,
  hoveredNode: TrailblazeNode?,
  highlightedNodeIds: Set<Long>,
  onNodeSelected: (TrailblazeNode) -> Unit,
  fontScale: Float,
  modifier: Modifier = Modifier,
  level: Int = 0,
) {
  val isSelected = node == selectedNode
  val isHovered = node == hoveredNode
  val isMatchHighlighted = node.nodeId in highlightedNodeIds
  val isHighlighted = isHovered || isSelected || isMatchHighlighted

  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onNodeSelected(node) }
        .background(
          when {
            isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
            isMatchHighlighted -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
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

      // Display text varies by driver detail type
      val displayText = resolveDisplayText(node)

      Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        softWrap = false
      )

      // Show driver type badge
      Spacer(modifier = Modifier.width(8.dp))
      val driverBadge = when (node.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility -> "a11y"
        is DriverNodeDetail.AndroidMaestro -> "maestro"
        is DriverNodeDetail.Web -> "web"
        is DriverNodeDetail.Compose -> "compose"
      }
      Text(
        text = driverBadge,
        style = MaterialTheme.typography.labelSmall.copy(
          fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale * 0.85f,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
      )
    }

    node.children.forEach { child ->
      TrailblazeNodeTreeItem(
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

/**
 * Resolves a human-readable display text for a TrailblazeNode based on its driver detail.
 */
private fun resolveDisplayText(node: TrailblazeNode): String {
  return when (val detail = node.driverDetail) {
    is DriverNodeDetail.AndroidAccessibility -> {
      when {
        !detail.text.isNullOrBlank() -> "\"${detail.text}\""
        !detail.contentDescription.isNullOrBlank() -> "[${detail.contentDescription}]"
        !detail.resourceId.isNullOrBlank() -> "#${detail.resourceId}"
        !detail.className.isNullOrBlank() -> "<${detail.className?.substringAfterLast(".")}>"
        else -> "(empty)"
      }
    }
    is DriverNodeDetail.AndroidMaestro -> {
      when {
        !detail.text.isNullOrBlank() -> "\"${detail.text}\""
        !detail.accessibilityText.isNullOrBlank() -> "[${detail.accessibilityText}]"
        !detail.resourceId.isNullOrBlank() -> "#${detail.resourceId}"
        !detail.className.isNullOrBlank() -> "<${detail.className?.substringAfterLast(".")}>"
        else -> "(empty)"
      }
    }
    is DriverNodeDetail.Web -> {
      when {
        !detail.ariaName.isNullOrBlank() -> "\"${detail.ariaName}\""
        !detail.ariaDescriptor.isNullOrBlank() -> "[${detail.ariaDescriptor}]"
        !detail.dataTestId.isNullOrBlank() -> "#${detail.dataTestId}"
        !detail.ariaRole.isNullOrBlank() -> "<${detail.ariaRole}>"
        else -> "(empty)"
      }
    }
    is DriverNodeDetail.Compose -> {
      when {
        !detail.text.isNullOrBlank() -> "\"${detail.text}\""
        !detail.contentDescription.isNullOrBlank() -> "[${detail.contentDescription}]"
        !detail.testTag.isNullOrBlank() -> "#${detail.testTag}"
        !detail.role.isNullOrBlank() -> "<${detail.role}>"
        else -> "(empty)"
      }
    }
  }
}

/**
 * Details panel for a TrailblazeNode, showing driver-specific properties and selector analysis.
 */
@Composable
internal fun TrailblazeNodeDetailsPanel(
  selectedNode: TrailblazeNode?,
  hoveredNode: TrailblazeNode?,
  fontScale: Float,
  modifier: Modifier = Modifier,
  computeSelectorOptions: ((TrailblazeNode) -> TrailblazeNodeSelectorAnalysisResult)? = null,
  onHighlightedNodeIdsChange: (Set<Long>) -> Unit = {},
) {
  val displayNode = hoveredNode ?: selectedNode

  // Compute selector analysis asynchronously when a node is selected
  var selectorAnalysis by remember { mutableStateOf<TrailblazeNodeSelectorAnalysisResult?>(null) }
  var isComputingSelectors by remember { mutableStateOf(false) }

  LaunchedEffect(selectedNode, computeSelectorOptions) {
    if (selectedNode != null && computeSelectorOptions != null) {
      isComputingSelectors = true
      try {
        selectorAnalysis = withContext(Dispatchers.Default) {
          computeSelectorOptions(selectedNode)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        selectorAnalysis = null
      }
      isComputingSelectors = false
    } else {
      selectorAnalysis = null
      isComputingSelectors = false
    }
  }

  Column(
    modifier = modifier.padding(16.dp)
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
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
      ) {
        // Common properties
        Text(
          text = "Common Properties",
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        TrailblazeDetailRow(label = "Node ID", value = displayNode.nodeId.toString(), fontScale = fontScale)

        displayNode.bounds?.let { bounds ->
          TrailblazeDetailRow(
            label = "Bounds",
            value = "${bounds.left},${bounds.top} - ${bounds.right},${bounds.bottom}",
            fontScale = fontScale
          )
          TrailblazeDetailRow(
            label = "Size",
            value = "${bounds.width}x${bounds.height}",
            fontScale = fontScale
          )
          TrailblazeDetailRow(
            label = "Center",
            value = "${bounds.centerX},${bounds.centerY}",
            fontScale = fontScale
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Driver-specific properties
        val driverLabel = when (displayNode.driverDetail) {
          is DriverNodeDetail.AndroidAccessibility -> "Android Accessibility Properties"
          is DriverNodeDetail.AndroidMaestro -> "Android Maestro Properties"
          is DriverNodeDetail.Web -> "Web (Playwright) Properties"
          is DriverNodeDetail.Compose -> "Compose Properties"
        }
        Text(
          text = driverLabel,
          style = MaterialTheme.typography.titleMedium.copy(
            fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        DriverNodeDetailProperties(detail = displayNode.driverDetail, fontScale = fontScale)

        // Selector analysis (only shown for clicked/selected node, not hovered)
        if (selectedNode != null && displayNode == selectedNode) {
          Spacer(modifier = Modifier.height(16.dp))
          TrailblazeNodeSelectorSection(
            selectorAnalysis = selectorAnalysis,
            isComputing = isComputingSelectors,
            fontScale = fontScale,
          )
        }

        // Children summary
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
            TrailblazeNodeChildItem(child = child, fontScale = fontScale)
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

/**
 * Displays selector analysis results for a TrailblazeNode.
 */
@Composable
private fun TrailblazeNodeSelectorSection(
  selectorAnalysis: TrailblazeNodeSelectorAnalysisResult?,
  isComputing: Boolean,
  fontScale: Float,
) {
  Text(
    text = "Selector Analysis",
    style = MaterialTheme.typography.titleMedium.copy(
      fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale
    ),
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary
  )
  Spacer(modifier = Modifier.height(8.dp))

  when {
    isComputing -> {
      Text(
        text = "Computing selectors...",
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    selectorAnalysis == null || selectorAnalysis.selectorOptions.isEmpty() -> {
      Text(
        text = "No selectors available",
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    else -> {
      val contentSelectors = selectorAnalysis.selectorOptions.filter {
        !it.strategy.startsWith("Structural:")
      }
      val structuralSelectors = selectorAnalysis.selectorOptions.filter {
        it.strategy.startsWith("Structural:")
      }

      if (contentSelectors.isNotEmpty()) {
        contentSelectors.forEach { option ->
          TrailblazeNodeSelectorCard(option = option, fontScale = fontScale)
          Spacer(modifier = Modifier.height(4.dp))
        }
      }

      if (structuralSelectors.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Structural (content-free)",
          style = MaterialTheme.typography.labelMedium.copy(
            fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.height(4.dp))
        structuralSelectors.forEach { option ->
          TrailblazeNodeSelectorCard(option = option, fontScale = fontScale, isStructural = true)
          Spacer(modifier = Modifier.height(4.dp))
        }
      }
    }
  }
}

/**
 * Displays a single selector option as a card with strategy name, serialized selector,
 * and a uniqueness badge showing whether the selector uniquely identifies the target node.
 */
@Composable
private fun TrailblazeNodeSelectorCard(
  option: TrailblazeNodeSelectorOptionDisplay,
  fontScale: Float,
  isStructural: Boolean = false,
) {
  val accentColor = if (isStructural) {
    MaterialTheme.colorScheme.tertiary
  } else {
    MaterialTheme.colorScheme.primary
  }

  val isUnique = option.matchCount == 1
  val uniqueColor = Color(0xFF2E7D32) // Green
  val ambiguousColor = Color(0xFFE65100) // Deep orange / amber

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = when {
        option.isBest -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        isStructural -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
      }
    )
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = option.strategy.removePrefix("Structural: "),
          style = MaterialTheme.typography.labelMedium.copy(
            fontSize = MaterialTheme.typography.labelMedium.fontSize * fontScale
          ),
          fontWeight = FontWeight.Medium,
          color = if (option.isBest) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Match count badge
          Text(
            text = when {
              option.matchCount == 0 -> "NO MATCH"
              isUnique -> "UNIQUE"
              else -> "${option.matchCount} MATCHES"
            },
            style = MaterialTheme.typography.labelSmall.copy(
              fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
            ),
            fontWeight = FontWeight.Bold,
            color = when {
              option.matchCount == 0 -> MaterialTheme.colorScheme.error
              isUnique -> uniqueColor
              else -> ambiguousColor
            },
          )
          if (option.isBest) {
            Text(
              text = "BEST",
              style = MaterialTheme.typography.labelSmall.copy(
                fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
              ),
              fontWeight = FontWeight.Bold,
              color = accentColor,
            )
          }
        }
      }
      // Coordinate verification: hit-tests the resolved center to confirm
      // no child element would intercept the tap
      Spacer(modifier = Modifier.height(2.dp))
      if (option.resolvedCenter != null) {
        val (cx, cy) = option.resolvedCenter
        Text(
          text = if (option.hitsTarget) {
            "Tap ($cx, $cy) hits target"
          } else {
            "Tap ($cx, $cy) would hit a different element"
          },
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
          ),
          color = if (option.hitsTarget) uniqueColor else MaterialTheme.colorScheme.error,
        )
      } else if (option.matchCount > 0) {
        Text(
          text = "No bounds — tap verification unavailable",
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = MaterialTheme.typography.labelSmall.fontSize * fontScale
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      SelectableText(
        text = option.yamlSelector.trim(),
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}

/**
 * Renders driver-specific properties from [DriverNodeDetail].
 */
@Composable
private fun DriverNodeDetailProperties(
  detail: DriverNodeDetail,
  fontScale: Float,
) {
  when (detail) {
    is DriverNodeDetail.AndroidAccessibility -> AndroidAccessibilityProperties(detail, fontScale)
    is DriverNodeDetail.AndroidMaestro -> AndroidMaestroProperties(detail, fontScale)
    is DriverNodeDetail.Web -> WebProperties(detail, fontScale)
    is DriverNodeDetail.Compose -> ComposeProperties(detail, fontScale)
  }
}

@Composable
private fun AndroidAccessibilityProperties(
  detail: DriverNodeDetail.AndroidAccessibility,
  fontScale: Float,
) {
  // Identity
  detail.className?.let { TrailblazeDetailRow(label = "Class", value = it, fontScale = fontScale) }
  detail.resourceId?.let { TrailblazeDetailRow(label = "Resource ID", value = it, fontScale = fontScale) }
  detail.uniqueId?.let { TrailblazeDetailRow(label = "Unique ID", value = it, fontScale = fontScale) }
  detail.packageName?.let { TrailblazeDetailRow(label = "Package", value = it, fontScale = fontScale) }

  // Text content
  detail.text?.let { TrailblazeDetailRow(label = "Text", value = it, fontScale = fontScale) }
  detail.contentDescription?.let { TrailblazeDetailRow(label = "Content Description", value = it, fontScale = fontScale) }
  detail.hintText?.let { TrailblazeDetailRow(label = "Hint", value = it, fontScale = fontScale) }
  detail.labeledByText?.let { TrailblazeDetailRow(label = "Labeled By", value = it, fontScale = fontScale) }
  detail.stateDescription?.let { TrailblazeDetailRow(label = "State Description", value = it, fontScale = fontScale) }
  detail.paneTitle?.let { TrailblazeDetailRow(label = "Pane Title", value = it, fontScale = fontScale) }
  detail.tooltipText?.let { TrailblazeDetailRow(label = "Tooltip", value = it, fontScale = fontScale) }
  detail.error?.let { TrailblazeDetailRow(label = "Error", value = it, fontScale = fontScale) }

  // Input type
  if (detail.inputType != 0) {
    TrailblazeDetailRow(label = "Input Type", value = detail.inputType.toString(), fontScale = fontScale)
  }
  if (detail.maxTextLength != 0) {
    TrailblazeDetailRow(label = "Max Text Length", value = detail.maxTextLength.toString(), fontScale = fontScale)
  }

  // Collection info
  detail.collectionItemInfo?.let { info ->
    TrailblazeDetailRow(
      label = "Collection Item",
      value = "row=${info.rowIndex}, col=${info.columnIndex}, rowSpan=${info.rowSpan}, colSpan=${info.columnSpan}",
      fontScale = fontScale
    )
  }
  detail.collectionInfo?.let { info ->
    TrailblazeDetailRow(
      label = "Collection",
      value = "rows=${info.rowCount}, cols=${info.columnCount}, hierarchical=${info.isHierarchical}",
      fontScale = fontScale
    )
  }
  detail.rangeInfo?.let { info ->
    TrailblazeDetailRow(
      label = "Range",
      value = "current=${info.current}, min=${info.min}, max=${info.max}",
      fontScale = fontScale
    )
  }

  // Actions
  if (detail.actions.isNotEmpty()) {
    TrailblazeDetailRow(label = "Actions", value = detail.actions.joinToString(", "), fontScale = fontScale)
  }

  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text = "State",
    style = MaterialTheme.typography.titleSmall.copy(
      fontSize = MaterialTheme.typography.titleSmall.fontSize * fontScale
    ),
    fontWeight = FontWeight.SemiBold
  )
  Spacer(modifier = Modifier.height(4.dp))

  // State properties - show all with their values
  TrailblazePropertiesGrid(
    properties = listOf(
      "Enabled" to detail.isEnabled,
      "Clickable" to detail.isClickable,
      "Checkable" to detail.isCheckable,
      "Checked" to detail.isChecked,
      "Selected" to detail.isSelected,
      "Focused" to detail.isFocused,
      "Editable" to detail.isEditable,
      "Scrollable" to detail.isScrollable,
      "Password" to detail.isPassword,
      "Heading" to detail.isHeading,
      "Multi-line" to detail.isMultiLine,
      "Long-clickable" to detail.isLongClickable,
      "Focusable" to detail.isFocusable,
      "Text Selectable" to detail.isTextSelectable,
      "Visible to User" to detail.isVisibleToUser,
      "Important for A11y" to detail.isImportantForAccessibility,
      "Content Invalid" to detail.isContentInvalid,
      "Showing Hint" to detail.isShowingHintText,
    ),
    fontScale = fontScale
  )
}

@Composable
private fun AndroidMaestroProperties(
  detail: DriverNodeDetail.AndroidMaestro,
  fontScale: Float,
) {
  detail.text?.let { TrailblazeDetailRow(label = "Text", value = it, fontScale = fontScale) }
  detail.accessibilityText?.let { TrailblazeDetailRow(label = "Accessibility Text", value = it, fontScale = fontScale) }
  detail.resourceId?.let { TrailblazeDetailRow(label = "Resource ID", value = it, fontScale = fontScale) }
  detail.className?.let { TrailblazeDetailRow(label = "Class", value = it, fontScale = fontScale) }
  detail.hintText?.let { TrailblazeDetailRow(label = "Hint", value = it, fontScale = fontScale) }

  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text = "State",
    style = MaterialTheme.typography.titleSmall.copy(
      fontSize = MaterialTheme.typography.titleSmall.fontSize * fontScale
    ),
    fontWeight = FontWeight.SemiBold
  )
  Spacer(modifier = Modifier.height(4.dp))

  TrailblazePropertiesGrid(
    properties = listOf(
      "Clickable" to detail.clickable,
      "Enabled" to detail.enabled,
      "Focused" to detail.focused,
      "Checked" to detail.checked,
      "Selected" to detail.selected,
      "Focusable" to detail.focusable,
      "Scrollable" to detail.scrollable,
      "Password" to detail.password,
    ),
    fontScale = fontScale
  )
}

@Composable
private fun WebProperties(
  detail: DriverNodeDetail.Web,
  fontScale: Float,
) {
  detail.ariaRole?.let { TrailblazeDetailRow(label = "ARIA Role", value = it, fontScale = fontScale) }
  detail.ariaName?.let { TrailblazeDetailRow(label = "ARIA Name", value = it, fontScale = fontScale) }
  detail.ariaDescriptor?.let { TrailblazeDetailRow(label = "ARIA Descriptor", value = it, fontScale = fontScale) }
  detail.headingLevel?.let { TrailblazeDetailRow(label = "Heading Level", value = it.toString(), fontScale = fontScale) }
  detail.cssSelector?.let { TrailblazeDetailRow(label = "CSS Selector", value = it, fontScale = fontScale) }
  detail.dataTestId?.let { TrailblazeDetailRow(label = "data-testid", value = it, fontScale = fontScale) }
  if (detail.nthIndex > 0) {
    TrailblazeDetailRow(label = "Nth Index", value = detail.nthIndex.toString(), fontScale = fontScale)
  }

  Spacer(modifier = Modifier.height(8.dp))
  TrailblazePropertiesGrid(
    properties = listOf(
      "Interactive" to detail.isInteractive,
      "Landmark" to detail.isLandmark,
    ),
    fontScale = fontScale
  )
}

@Composable
private fun ComposeProperties(
  detail: DriverNodeDetail.Compose,
  fontScale: Float,
) {
  detail.testTag?.let { TrailblazeDetailRow(label = "Test Tag", value = it, fontScale = fontScale) }
  detail.role?.let { TrailblazeDetailRow(label = "Role", value = it, fontScale = fontScale) }
  detail.text?.let { TrailblazeDetailRow(label = "Text", value = it, fontScale = fontScale) }
  detail.editableText?.let { TrailblazeDetailRow(label = "Editable Text", value = it, fontScale = fontScale) }
  detail.contentDescription?.let { TrailblazeDetailRow(label = "Content Description", value = it, fontScale = fontScale) }
  detail.toggleableState?.let { TrailblazeDetailRow(label = "Toggleable State", value = it, fontScale = fontScale) }

  Spacer(modifier = Modifier.height(8.dp))
  TrailblazePropertiesGrid(
    properties = listOf(
      "Enabled" to detail.isEnabled,
      "Focused" to detail.isFocused,
      "Selected" to detail.isSelected,
      "Password" to detail.isPassword,
      "Has Click Action" to detail.hasClickAction,
      "Has Scroll Action" to detail.hasScrollAction,
    ),
    fontScale = fontScale
  )
}

/**
 * Raw JSON panel for TrailblazeNode.
 */
@Composable
internal fun TrailblazeNodeRawJsonPanel(
  trailblazeNodeTree: TrailblazeNode,
  fontScale: Float,
  modifier: Modifier = Modifier,
) {
  val jsonString = remember(trailblazeNodeTree) {
    Json { prettyPrint = true }.encodeToString(TrailblazeNode.serializer(), trailblazeNodeTree)
  }
  Box(modifier = modifier) {
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

// -- Shared detail components --

@Composable
private fun TrailblazeDetailRow(
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
private fun TrailblazePropertiesGrid(
  properties: List<Pair<String, Boolean>>,
  fontScale: Float,
) {
  val activeProperties = properties.filter { it.second }

  if (activeProperties.isNotEmpty()) {
    Column {
      activeProperties.forEach { (property, _) ->
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
private fun TrailblazeNodeChildItem(
  child: TrailblazeNode,
  fontScale: Float,
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

      Text(
        text = resolveDisplayText(child),
        style = MaterialTheme.typography.bodySmall.copy(
          fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
      )
    }
  }
}
