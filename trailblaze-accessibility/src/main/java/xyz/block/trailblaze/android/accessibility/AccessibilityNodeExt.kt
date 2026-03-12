package xyz.block.trailblaze.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import xyz.block.trailblaze.android.AndroidSdkVersion

/**
 * Direct conversion from [AccessibilityNodeInfo] to [AccessibilityNode], bypassing Maestro's
 * `TreeNode` entirely. This captures the **full** richness of the accessibility framework
 * with zero data loss.
 *
 * Compare with [toTreeNode] in `AccessibilityServiceExt.kt` which converts to Maestro's
 * `TreeNode` and drops most of the accessibility-specific properties.
 */
internal fun AccessibilityNodeInfo.toAccessibilityNode(nodeIdCounter: NodeIdCounter = NodeIdCounter()): AccessibilityNode {
  val nodeRect = Rect().apply { getBoundsInScreen(this) }
  val bounds = AccessibilityNode.Bounds(
    left = nodeRect.left,
    top = nodeRect.top,
    right = nodeRect.right,
    bottom = nodeRect.bottom,
  )

  // Resolve labeledBy text — if this node has a labeling relationship, capture the label's text.
  val labeledByText = labeledBy?.let { node ->
    try { node.text?.toString() } finally { node.recycle() }
  }

  // Capture the Compose-style hint text hoisting: when an EditText has no text or hintText,
  // use the first child's text as hint. This matches what toTreeNode() does.
  val resolvedHintText = hintText?.toString() ?: run {
    // Compose fallback: EditText placeholder rendered as child TextView
    if (className?.toString() == "android.widget.EditText" && text.isNullOrEmpty()) {
      (0 until childCount).firstNotNullOfOrNull { i ->
        val child = getChild(i)
        val childText = child?.text?.toString()
        child?.recycle()
        childText
      }
    } else {
      null
    }
  }

  // Map actions to readable string names for serialization
  val actionNames = actionList?.map { action ->
    action.label?.toString() ?: standardActionName(action.id)
  } ?: emptyList()

  // Recursively convert children (recycle each child's AccessibilityNodeInfo after conversion)
  val childNodes = (0 until childCount).mapNotNull { index ->
    val child = getChild(index) ?: return@mapNotNull null
    try {
      child.toAccessibilityNode(nodeIdCounter)
    } finally {
      child.recycle()
    }
  }

  return AccessibilityNode(
    nodeId = nodeIdCounter.next(),

    // Identity
    className = className?.toString(),
    resourceId = viewIdResourceName,
    uniqueId = if (AndroidSdkVersion.isAtLeast(33)) uniqueId else null,
    packageName = packageName?.toString(),

    // Text content
    text = text?.toString(),
    contentDescription = contentDescription?.toString(),
    hintText = resolvedHintText,
    tooltipText = if (AndroidSdkVersion.isAtLeast(28)) tooltipText?.toString() else null,
    error = error?.toString(),
    paneTitle = if (AndroidSdkVersion.isAtLeast(28)) paneTitle?.toString() else null,
    stateDescription = if (AndroidSdkVersion.isAtLeast(30)) stateDescription?.toString() else null,
    isShowingHintText = isShowingHintText,

    // State
    isEnabled = isEnabled,
    isClickable = isClickable,
    isLongClickable = isLongClickable,
    isFocusable = isFocusable,
    isFocused = isFocused,
    isCheckable = isCheckable,
    isChecked = isChecked,
    isSelected = isSelected,
    isEditable = isEditable,
    isScrollable = isScrollable,
    isPassword = isPassword,
    isMultiLine = isMultiLine,
    isVisibleToUser = isVisibleToUser,
    isHeading = if (AndroidSdkVersion.isAtLeast(28)) isHeading else false,
    isContentInvalid = isContentInvalid,
    isTextSelectable = if (AndroidSdkVersion.isAtLeast(33)) isTextSelectable else false,
    isImportantForAccessibility = isImportantForAccessibility,

    // Input
    inputType = inputType,
    maxTextLength = maxTextLength,

    // Bounds
    boundsInScreen = bounds,
    drawingOrder = drawingOrder,

    // Relationships
    labeledByText = labeledByText,
    children = childNodes,

    // Actions
    actions = actionNames,

    // Collection semantics
    collectionInfo = collectionInfo?.let {
      AccessibilityNode.CollectionInfo(
        rowCount = it.rowCount,
        columnCount = it.columnCount,
        isHierarchical = it.isHierarchical,
      )
    },
    collectionItemInfo = collectionItemInfo?.let {
      AccessibilityNode.CollectionItemInfo(
        rowIndex = it.rowIndex,
        rowSpan = it.rowSpan,
        columnIndex = it.columnIndex,
        columnSpan = it.columnSpan,
        isHeading = it.isHeading,
      )
    },
    rangeInfo = rangeInfo?.let {
      AccessibilityNode.RangeInfo(
        type = it.type,
        min = it.min,
        max = it.max,
        current = it.current,
      )
    },
  )
}

/** Auto-incrementing counter for assigning node IDs within a single tree capture. Not thread-safe — intended for single-threaded recursive use only. */
internal class NodeIdCounter {
  private var counter = 0L
  fun next(): Long = ++counter
}

/**
 * Maps standard [AccessibilityNodeInfo] action IDs to readable names.
 * Custom actions use their label; standard actions use the constant name.
 */
private fun standardActionName(actionId: Int): String = when (actionId) {
  AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
  AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
  AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
  AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "ACTION_CLEAR_FOCUS"
  AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
  AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "ACTION_CLEAR_SELECTION"
  AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
  AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
  AccessibilityNodeInfo.ACTION_COPY -> "ACTION_COPY"
  AccessibilityNodeInfo.ACTION_PASTE -> "ACTION_PASTE"
  AccessibilityNodeInfo.ACTION_CUT -> "ACTION_CUT"
  AccessibilityNodeInfo.ACTION_SET_SELECTION -> "ACTION_SET_SELECTION"
  AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
  AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
  AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "ACTION_SCROLL_UP"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "ACTION_SCROLL_DOWN"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "ACTION_SCROLL_LEFT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "ACTION_SCROLL_RIGHT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> "ACTION_SHOW_ON_SCREEN"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id -> "ACTION_CONTEXT_CLICK"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> "ACTION_SET_PROGRESS"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id -> "ACTION_DISMISS"
  else -> "ACTION_$actionId"
}
