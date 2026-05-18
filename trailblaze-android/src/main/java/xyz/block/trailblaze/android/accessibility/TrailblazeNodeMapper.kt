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
 *
 * **WebView exception.** [WEBVIEW_CLASS_NAME] nodes are always kept and their entire subtree
 * is preserved as-is. The standard Android importance heuristic does not apply to web content:
 * an `<h1>` rendered inside a WebView arrives with `isImportantForAccessibility=false` because
 * Chromium does not set that Android flag for its in-page nodes, and the WebView container
 * itself is typically not marked important either. Without this exception, the default
 * snapshot would silently drop every scrap of page content Chromium does expose
 * (page title, ARIA-labeled landmarks, etc.). See `TrailblazeNodeMapperTest` for the
 * regression coverage.
 */
internal fun TrailblazeNode.filterImportantForAccessibility(): TrailblazeNode {
  // Receiver-side check: if the tree this filter is invoked on is itself rooted at a
  // WebView, return it untouched. Without this, the recursion below would still trim
  // non-important descendants of the root WebView, breaking the kdoc's "entire subtree
  // is preserved" promise for any caller that passes a WebView-rooted subtree (today
  // there is no such caller, but the function is `internal` and tests/future callers
  // should not silently see the wrong behavior).
  if (isWebView()) return this
  fun processChildren(children: List<TrailblazeNode>): List<TrailblazeNode> =
    children.flatMap { child ->
      // Preserve WebView and its full subtree unconditionally — see kdoc. Nested WebViews
      // (one WebView inside another, e.g. iframes/ad containers) are intentionally kept as
      // a single unit: returning `listOf(child)` without recursing into `processChildren`
      // means the inner WebView never re-enters the receiver-side guard at the top of this
      // function, but the kdoc's "entire subtree preserved as-is" promise still holds —
      // every node beneath the outer WebView survives untouched.
      if (child.isWebView()) return@flatMap listOf(child)
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
 * Exact runtime class name for Android's WebView. The match in [isWebView] is intentionally
 * exact-equality, not contains/regex — verified by the negative test in
 * `TrailblazeNodeMapperTest` (a custom `MyWebViewWrapper` subclass must NOT trigger the
 * exception).
 *
 * **If WebView ever ships under a different runtime class** (e.g. an `androidx.webkit.*`
 * delegate that reports its own class, or a Chromium re-namespacing), the empty-WebView-tree
 * problem this exception solves will silently return. Extend the check here by adding the
 * new class name to a small set rather than loosening the match — exact matching keeps
 * `MyWebViewWrapper`-style false positives out.
 */
private const val WEBVIEW_CLASS_NAME = "android.webkit.WebView"

private fun TrailblazeNode.isWebView(): Boolean {
  val detail = driverDetail
  return detail is DriverNodeDetail.AndroidAccessibility &&
    detail.className == WEBVIEW_CLASS_NAME
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
