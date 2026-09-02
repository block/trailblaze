package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The estate bridge: an `androidMaestro`-shaped selector — as recorded by the UiAutomator-backed
 * instrumentation driver across the existing external trail estate — resolves against the
 * ANDROID_TEST driver's in-process hybrid tree without re-recording.
 *
 * The selector cases here are real ones from `suite_71172/case_4837703` (the transactions-applet
 * trail this bridge was proven on): bare `textRegex`, the structural
 * `containsChild: {androidMaestro: …}` tap shape, and the
 * `childOf: {containsChild: …}` filter-row shape.
 *
 * Two density rules do the heavy lifting, both exercised here:
 * - `containsChild` recorded against a PROJECTED tree means "directly under this node there",
 *   which is descendant-shaped on the dense tree (wrappers the projection pruned sit between).
 * - a projected tree MERGES a widget into one node, so the dense tree matches the same widget
 *   several times along one ancestor chain; the chain collapses to its innermost node.
 */
class EstateSelectorBridgeTest {

  private var nextId = 1L

  private fun node(
    detail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  private fun maestroText(text: String) = DriverNodeMatch.AndroidMaestro(textRegex = text)

  // -- Bare textRegex, the estate's dominant shape --

  @Test
  fun `maestro text selector resolves a View node`() {
    nextId = 1L
    val more = node(DriverNodeDetail.AndroidView(text = "More", isClickable = true))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(more),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("More")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(more.nodeId, result.node.nodeId)
  }

  @Test
  fun `maestro text selector resolves a Compose node`() {
    nextId = 1L
    val transactions = node(DriverNodeDetail.Compose(text = "Transactions", hasClickAction = true))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(transactions),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("Transactions")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(transactions.nodeId, result.node.nodeId)
  }

  @Test
  fun `MAESTRO dialect stays case-insensitive across the bridge`() {
    nextId = 1L
    val more = node(DriverNodeDetail.AndroidView(text = "More"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(more),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("more")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(more.nodeId, result.node.nodeId)
  }

  @Test
  fun `MAESTRO dialect degrades an invalid pattern to a literal`() {
    nextId = 1L
    val charge = node(DriverNodeDetail.AndroidView(text = "Charge $25.00"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(charge),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      // Unescaped, the way Maestro recordings carry natural-language values: `$2` can never
      // regex-match ($ anchors end-of-input), so this only resolves via the literal degrade.
      TrailblazeNodeSelector.withMatch(maestroText("Charge $25.00")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(charge.nodeId, result.node.nodeId)
  }

  @Test
  fun `maestro text folds onto contentDescription, matching Maestro's own text filter`() {
    nextId = 1L
    val iconButton = node(
      DriverNodeDetail.AndroidView(contentDescription = "Back", isClickable = true),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(iconButton),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("Back")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(iconButton.nodeId, result.node.nodeId)
  }

  // -- Strictness: unanswerable predicates fail closed --

  @Test
  fun `classNameRegex never matches a Compose node even when its text matches`() {
    nextId = 1L
    val composeButton = node(
      DriverNodeDetail.Compose(text = "Sign in", hasClickAction = true),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(composeButton),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidMaestro(textRegex = "Sign in", classNameRegex = "android.widget.Button"),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `classNameRegex still matches a View node across the bridge`() {
    nextId = 1L
    val button = node(
      DriverNodeDetail.AndroidView(text = "Sign in", className = "android.widget.Button"),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(button),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidMaestro(textRegex = "Sign in", classNameRegex = "android.widget.Button"),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(button.nodeId, result.node.nodeId)
  }

  @Test
  fun `resourceIdRegex matches a Compose testTag, the testTagsAsResourceId contract`() {
    nextId = 1L
    val tagged = node(DriverNodeDetail.Compose(testTag = "amount_field"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(tagged),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidMaestro(resourceIdRegex = "amount_field")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(tagged.nodeId, result.node.nodeId)
  }

  // -- The two density rules --

  /**
   * The trail's step-2 tap, verbatim: `{containsChild: {androidMaestro: {textRegex: Orders}}}`.
   * On the projected tree that matched the one node directly above "Orders". On the dense tree
   * the text sits under a wrapper inside the clickable row, so root, row and wrapper all contain
   * it — one widget, three densities. Densified containsChild finds them all; the chain collapse
   * keeps the innermost.
   */
  @Test
  fun `structural containsChild selector collapses to the innermost containing node`() {
    nextId = 1L
    val ordersText = node(DriverNodeDetail.AndroidView(text = "Orders"))
    val wrapper = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(ordersText),
    )
    val row = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", isClickable = true),
      children = listOf(wrapper),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(row),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(null, containsChild = TrailblazeNodeSelector.withMatch(maestroText("Orders"))),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(wrapper.nodeId, result.node.nodeId)
  }

  /**
   * The trail's step-3 filter tap, verbatim:
   * `{containsChild: {textRegex: Active}, childOf: {containsChild: {textRegex: Status}}}`.
   * The childOf anchor must NOT collapse (its matches' descendants are unioned into the search
   * scope), or the merged container's scope would shrink to the Status text's own wrapper and
   * "Active" would fall outside it.
   */
  @Test
  fun `childOf anchor keeps the merged container's whole scope`() {
    nextId = 1L
    val statusLabel = node(DriverNodeDetail.AndroidView(text = "Status"))
    val activeValue = node(DriverNodeDetail.AndroidView(text = "Active"))
    val filterRow = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", isClickable = true),
      children = listOf(statusLabel, activeValue),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(filterRow),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        null,
        containsChild = TrailblazeNodeSelector.withMatch(maestroText("Active")),
        childOf = TrailblazeNodeSelector.withMatch(
          null,
          containsChild = TrailblazeNodeSelector.withMatch(maestroText("Status")),
        ),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(filterRow.nodeId, result.node.nodeId)
  }

  /**
   * The same filter tap on a DEEP tree — the on-device shape of case 4837703's failure. The
   * bridged anchor (`containsChild: Status`) matches every ancestor of the Status text, and a
   * childOf scope built by flat union entered each node once per enclosing anchor: the one
   * Active row came back as "36 elements" on device, every one of them the same node. The
   * union of an ancestor chain's scopes is just the outermost scope, so one widget must be
   * one match no matter how deep the chain runs.
   */
  @Test
  fun `a bridged anchor chain never multiplies one match into duplicates`() {
    nextId = 1L
    val statusText = node(DriverNodeDetail.AndroidView(text = "Status"))
    val headerWrapper = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(statusText),
    )
    val activeText = node(DriverNodeDetail.AndroidView(text = "Active"))
    val activeRow = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", isClickable = true),
      children = listOf(activeText),
    )
    val listWrapper = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(activeRow),
    )
    val sheet = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(headerWrapper, listWrapper),
    )
    val screen = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(sheet),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(screen),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        null,
        containsChild = TrailblazeNodeSelector.withMatch(maestroText("Active")),
        childOf = TrailblazeNodeSelector.withMatch(
          null,
          containsChild = TrailblazeNodeSelector.withMatch(maestroText("Status")),
        ),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(activeRow.nodeId, result.node.nodeId)
  }

  /**
   * A projected tree merges a container's contentDescription with its child's text into one
   * node. On the dense tree both match the same text selector — one widget, not an ambiguity.
   */
  @Test
  fun `one merged widget matching twice collapses to a single match`() {
    nextId = 1L
    val innerText = node(DriverNodeDetail.AndroidView(text = "More"))
    val tabItem = node(
      DriverNodeDetail.AndroidView(contentDescription = "More", isClickable = true),
      children = listOf(innerText),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(tabItem),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("More")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(innerText.nodeId, result.node.nodeId)
  }

  /** Two genuinely distinct widgets must stay ambiguous — the collapse only narrows chains. */
  @Test
  fun `two distinct widgets stay ambiguous after the collapse`() {
    nextId = 1L
    val first = node(
      DriverNodeDetail.AndroidView(text = "Automation Test Item"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val second = node(
      DriverNodeDetail.AndroidView(text = "Automation Test Item"),
      bounds = TrailblazeNode.Bounds(0, 200, 100, 250),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(first, second),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("Automation Test Item")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(result)
    assertEquals(2, result.nodes.size)
  }

  /**
   * A recorded `index:` counted widgets on the projected tree — one node per widget. The collapse
   * runs before the index step so the dense tree's wrapper duplicates don't shift the count.
   */
  @Test
  fun `index counts widgets, not the wrappers the dense tree adds`() {
    nextId = 1L
    fun itemRow(top: Int): Pair<TrailblazeNode, TrailblazeNode> {
      val text = node(
        DriverNodeDetail.AndroidView(text = "Item"),
        bounds = TrailblazeNode.Bounds(10, top + 10, 90, top + 40),
      )
      val row = node(
        DriverNodeDetail.AndroidView(contentDescription = "Item", isClickable = true),
        bounds = TrailblazeNode.Bounds(0, top, 100, top + 50),
        children = listOf(text),
      )
      return row to text
    }
    val (row1, text1) = itemRow(top = 100)
    val (row2, text2) = itemRow(top = 200)
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(row1, row2),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("Item"), index = 1),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(text2.nodeId, result.node.nodeId)
    // Guard the fixture: both rows would have matched without the collapse.
    check(row1.nodeId != text1.nodeId)
  }

  // -- The native maestro-detail path is untouched --

  @Test
  fun `maestro selector against maestro detail nodes still matches directly`() {
    nextId = 1L
    val target = node(DriverNodeDetail.AndroidMaestro(text = "Submit"))
    val root = node(
      DriverNodeDetail.AndroidMaestro(className = "android.widget.FrameLayout"),
      children = listOf(target),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(maestroText("Submit")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }
}
