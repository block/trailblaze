package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Filters the [TrailblazeNode] tree to only include nodes marked as important for accessibility.
 *
 * Nodes where [DriverNodeDetail.AndroidAccessibility.isImportantForAccessibility] is false are
 * removed and their children are promoted up to take their place, preserving any important
 * descendants. Non-Android nodes are always kept.
 *
 * The root node is always kept regardless of its importance flag. When an app fails to mark
 * a legitimately interactive node as important (e.g. a buggy Compose text field), the user
 * can reach it via the `--all` CLI flag / `SnapshotDetail.ALL_ELEMENTS`, which bypasses the
 * downstream meaningful-element filter. The default response stays small.
 */
internal fun TrailblazeNode.filterImportantForAccessibility(): TrailblazeNode {
  fun processChildren(children: List<TrailblazeNode>): List<TrailblazeNode> =
    children.flatMap { child ->
      val processedChild = child.copy(children = processChildren(child.children))
      val isImportant = when (val detail = child.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility -> detail.isImportantForAccessibility
        else -> true
      }
      if (isImportant) listOf(processedChild) else processedChild.children
    }
  return copy(children = processChildren(children))
}

/**
 * Maps [AccessibilityNode] trees to [TrailblazeNode] trees with
 * [DriverNodeDetail.AndroidAccessibility] detail.
 *
 * This is a lossless conversion — every property captured in [AccessibilityNode] is
 * preserved in the [DriverNodeDetail.AndroidAccessibility] detail, enabling rich
 * selector generation that uses the full surface of the Android accessibility framework.
 */
fun AccessibilityNode.toTrailblazeNode(): TrailblazeNode {
  return TrailblazeNode(
    nodeId = nodeId,
    bounds = boundsInScreen?.toTrailblazeNodeBounds(),
    children = children.map { it.toTrailblazeNode() },
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      // Identity
      className = className,
      resourceId = resourceId,
      uniqueId = uniqueId,
      packageName = packageName,

      // Text content
      text = text,
      contentDescription = contentDescription,
      hintText = hintText,
      labeledByText = labeledByText,
      stateDescription = stateDescription,
      paneTitle = paneTitle,
      roleDescription = roleDescription,
      composeTestTag = composeTestTag,
      tooltipText = tooltipText,
      error = error,
      isShowingHintText = isShowingHintText,

      // State
      isEnabled = isEnabled,
      isClickable = isClickable,
      isCheckable = isCheckable,
      isChecked = isChecked,
      isSelected = isSelected,
      isFocused = isFocused,
      isEditable = isEditable,
      isScrollable = isScrollable,
      isPassword = isPassword,
      isHeading = isHeading,
      isMultiLine = isMultiLine,
      isContentInvalid = isContentInvalid,
      isVisibleToUser = isVisibleToUser,
      isLongClickable = isLongClickable,
      isFocusable = isFocusable,
      isTextSelectable = isTextSelectable,
      isImportantForAccessibility = isImportantForAccessibility,

      // Input
      inputType = inputType,
      maxTextLength = maxTextLength,

      // Collection semantics
      collectionItemInfo = collectionItemInfo?.toDriverDetail(),
      collectionInfo = collectionInfo?.toDriverDetail(),
      rangeInfo = rangeInfo?.toDriverDetail(),

      // Interaction
      actions = actions,
      drawingOrder = drawingOrder,
    ),
  )
}

// --- Private mapping helpers ---

private fun AccessibilityNode.Bounds.toTrailblazeNodeBounds(): TrailblazeNode.Bounds =
  TrailblazeNode.Bounds(left = left, top = top, right = right, bottom = bottom)

private fun AccessibilityNode.CollectionItemInfo.toDriverDetail():
  DriverNodeDetail.AndroidAccessibility.CollectionItemInfo =
  DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
    rowIndex = rowIndex,
    rowSpan = rowSpan,
    columnIndex = columnIndex,
    columnSpan = columnSpan,
    isHeading = isHeading,
  )

private fun AccessibilityNode.CollectionInfo.toDriverDetail():
  DriverNodeDetail.AndroidAccessibility.CollectionInfo =
  DriverNodeDetail.AndroidAccessibility.CollectionInfo(
    rowCount = rowCount,
    columnCount = columnCount,
    isHierarchical = isHierarchical,
  )

private fun AccessibilityNode.RangeInfo.toDriverDetail():
  DriverNodeDetail.AndroidAccessibility.RangeInfo =
  DriverNodeDetail.AndroidAccessibility.RangeInfo(
    type = type,
    min = min,
    max = max,
    current = current,
  )
