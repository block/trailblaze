package xyz.block.trailblaze.api

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
fun TrailblazeNode.filterImportantForAccessibility(): TrailblazeNode {
  // Receiver-side check: if the tree this filter is invoked on is itself rooted at a
  // WebView, return it untouched. Without this, the recursion below would still trim
  // non-important descendants of the root WebView, breaking the kdoc's "entire subtree
  // is preserved" promise for any caller that passes a WebView-rooted subtree (today
  // there is no such caller, but this function is public API and out-of-module callers
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
      val keep = when (val detail = child.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility ->
          detail.isImportantForAccessibility ||
            (processedChild.children.isEmpty() && detail.hasReadableLabel())
        else -> true
      }
      if (keep) listOf(processedChild) else processedChild.children
    }
  return copy(children = processChildren(children))
}

/**
 * Compose-aware fallback for [filterImportantForAccessibility].
 *
 * Jetpack Compose's default a11y handling sets `importantForAccessibility=false` on inner
 * Text/Icon leaves because their content is supposed to be readable via the merged parent's
 * `contentDescription`. When that merge doesn't fire (or the parent never synthesizes a
 * usable label), the dropped child carries the only readable text on screen, and the runtime
 * LLM gets a refless container it can't select. Keeping any node that carries its own
 * readable label — text or contentDescription — regardless of the importance flag, gives
 * Compose-rendered screens a usable simplified view without resorting to `--all`.
 *
 * **Intentionally narrow.** "Readable label" is the only signal we add — interactive flags
 * like `isClickable` / `isEditable` / `isFocusable` are deliberately NOT included here. The
 * `TrailblazeNodeMapperTest` "drops non-important editable or clickable nodes" test pins the
 * historical reason: every background view that happens to be clickable (overlay scrims,
 * ripple-providing rows, dismiss layers, etc.) would re-enter the default snapshot and
 * balloon the tree the LLM has to read. Without a label, the LLM cannot address the node
 * anyway, so keeping it adds noise without selectability. Pre-Compose `View` hierarchies are
 * unaffected: decorative wrappers (`LinearLayout`, `FrameLayout`, etc.) have no text or
 * contentDescription, so they still drop as before.
 *
 * **Leaves only.** The fallback is gated on `processedChild.children.isEmpty()` — i.e. the
 * node has no surviving children after recursive filtering. Non-important *wrappers* that
 * happen to carry a label but contain children (their own or promoted ones) are still
 * dropped, so `containsChild` selectors that rely on non-important intermediates being
 * removed + their children promoted up keep working. The
 * `AccessibilityDeviceManagerTreeFilterTest` `containsChild ...` test pins that invariant.
 */
private fun DriverNodeDetail.AndroidAccessibility.hasReadableLabel(): Boolean =
  !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

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
 * Resolves [selector] the way an Android accessibility device does: against the
 * [filterImportantForAccessibility] tree first, which is the shape the LLM and recording playback
 * see by default, then against [tree] itself when that finds nothing. Filtering first keeps
 * `containsChild` working through non-important wrappers (e.g. Compose layout nodes), whose
 * children the filter promotes; the fallback keeps selectors written against an `--all` /
 * [SnapshotDetail.ALL_ELEMENTS] capture, which can target the non-important nodes themselves.
 * Trees with no Android accessibility nodes resolve exactly as [TrailblazeNodeSelectorResolver] does.
 */
fun resolveImportantForAccessibilityFirst(
  tree: TrailblazeNode,
  selector: TrailblazeNodeSelector,
  target: TargetTemplateContext? = null,
): TrailblazeNodeSelectorResolver.ResolveResult {
  val filteredResult = TrailblazeNodeSelectorResolver.resolve(tree.filterImportantForAccessibility(), selector, target)
  if (filteredResult !is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) return filteredResult
  return TrailblazeNodeSelectorResolver.resolve(tree, selector, target)
}
