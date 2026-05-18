package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

class TrailblazeNodeMapperTest {

  @Test
  fun `maps identity properties`() {
    val node = AccessibilityNode(
      nodeId = 42,
      className = "android.widget.Button",
      resourceId = "com.example:id/btn_ok",
      uniqueId = "uid-99",
      packageName = "com.example",
    )

    val result = node.toTrailblazeNode()
    assertEquals(42L, result.nodeId)
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals("android.widget.Button", detail.className)
    assertEquals("com.example:id/btn_ok", detail.resourceId)
    assertEquals("uid-99", detail.uniqueId)
    assertEquals("com.example", detail.packageName)
  }

  @Test
  fun `maps text content properties`() {
    val node = AccessibilityNode(
      text = "Submit",
      contentDescription = "Submit button",
      hintText = "Tap to submit",
      labeledByText = "Form action",
      stateDescription = "Enabled",
      paneTitle = "Dialog",
      roleDescription = "Toggle",
      composeTestTag = "submit_button",
      tooltipText = "Click me",
      error = "Error!",
      isShowingHintText = true,
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals("Submit", detail.text)
    assertEquals("Submit button", detail.contentDescription)
    assertEquals("Tap to submit", detail.hintText)
    assertEquals("Form action", detail.labeledByText)
    assertEquals("Enabled", detail.stateDescription)
    assertEquals("Dialog", detail.paneTitle)
    assertEquals("Toggle", detail.roleDescription)
    assertEquals("submit_button", detail.composeTestTag)
    assertEquals("Click me", detail.tooltipText)
    assertEquals("Error!", detail.error)
    assertEquals(true, detail.isShowingHintText)
  }

  @Test
  fun `composeTestTag alone marks node as identifiable`() {
    val node = AccessibilityNode(composeTestTag = "checkout_button").toTrailblazeNode()
    val detail = node.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals(true, detail.hasIdentifiableProperties)
  }

  @Test
  fun `roleDescription alone does not mark node as identifiable`() {
    // roleDescription is matchable only when paired with className (selector strategy 17),
    // so a node carrying only roleDescription cannot be resolved by a generated selector.
    // Including it in `hasIdentifiableProperties` would inflate the identifiability of
    // role-only nodes — this test pins the rule documented on the property.
    val node = AccessibilityNode(roleDescription = "Toggle").toTrailblazeNode()
    val detail = node.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals(false, detail.hasIdentifiableProperties)
  }

  @Test
  fun `maps boolean state flags`() {
    val node = AccessibilityNode(
      isEnabled = false,
      isClickable = true,
      isCheckable = true,
      isChecked = true,
      isSelected = true,
      isFocused = true,
      isEditable = true,
      isScrollable = true,
      isPassword = true,
      isHeading = true,
      isMultiLine = true,
      isContentInvalid = true,
      isVisibleToUser = false,
      isLongClickable = true,
      isFocusable = true,
      isTextSelectable = true,
      isImportantForAccessibility = false,
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals(false, detail.isEnabled)
    assertEquals(true, detail.isClickable)
    assertEquals(true, detail.isCheckable)
    assertEquals(true, detail.isChecked)
    assertEquals(true, detail.isSelected)
    assertEquals(true, detail.isFocused)
    assertEquals(true, detail.isEditable)
    assertEquals(true, detail.isScrollable)
    assertEquals(true, detail.isPassword)
    assertEquals(true, detail.isHeading)
    assertEquals(true, detail.isMultiLine)
    assertEquals(true, detail.isContentInvalid)
    assertEquals(false, detail.isVisibleToUser)
    assertEquals(true, detail.isLongClickable)
    assertEquals(true, detail.isFocusable)
    assertEquals(true, detail.isTextSelectable)
    assertEquals(false, detail.isImportantForAccessibility)
  }

  @Test
  fun `maps input type and max text length`() {
    val node = AccessibilityNode(
      inputType = 33, // TYPE_TEXT_VARIATION_EMAIL_ADDRESS
      maxTextLength = 100,
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals(33, detail.inputType)
    assertEquals(100, detail.maxTextLength)
  }

  @Test
  fun `maps bounds`() {
    val node = AccessibilityNode(
      boundsInScreen = AccessibilityNode.Bounds(left = 10, top = 20, right = 110, bottom = 70),
    )

    val result = node.toTrailblazeNode()
    assertNotNull(result.bounds)
    assertEquals(10, result.bounds!!.left)
    assertEquals(20, result.bounds!!.top)
    assertEquals(110, result.bounds!!.right)
    assertEquals(70, result.bounds!!.bottom)
  }

  @Test
  fun `null bounds maps to null`() {
    val node = AccessibilityNode(boundsInScreen = null)
    val result = node.toTrailblazeNode()
    assertNull(result.bounds)
  }

  @Test
  fun `maps children recursively`() {
    val grandchild = AccessibilityNode(nodeId = 3, text = "Deep")
    val child = AccessibilityNode(nodeId = 2, text = "Middle", children = listOf(grandchild))
    val root = AccessibilityNode(nodeId = 1, children = listOf(child))

    val result = root.toTrailblazeNode()
    assertEquals(1, result.children.size)
    assertEquals(2L, result.children[0].nodeId)

    val childResult = result.children[0]
    val childDetail = childResult.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals("Middle", childDetail.text)
    assertEquals(1, childResult.children.size)
    assertEquals(3L, childResult.children[0].nodeId)
  }

  @Test
  fun `maps collectionItemInfo`() {
    val node = AccessibilityNode(
      collectionItemInfo = AccessibilityNode.CollectionItemInfo(
        rowIndex = 2,
        rowSpan = 1,
        columnIndex = 3,
        columnSpan = 2,
        isHeading = true,
      ),
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertNotNull(detail.collectionItemInfo)
    assertEquals(2, detail.collectionItemInfo!!.rowIndex)
    assertEquals(1, detail.collectionItemInfo!!.rowSpan)
    assertEquals(3, detail.collectionItemInfo!!.columnIndex)
    assertEquals(2, detail.collectionItemInfo!!.columnSpan)
    assertEquals(true, detail.collectionItemInfo!!.isHeading)
  }

  @Test
  fun `maps collectionInfo`() {
    val node = AccessibilityNode(
      collectionInfo = AccessibilityNode.CollectionInfo(
        rowCount = 10,
        columnCount = 2,
        isHierarchical = true,
      ),
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertNotNull(detail.collectionInfo)
    assertEquals(10, detail.collectionInfo!!.rowCount)
    assertEquals(2, detail.collectionInfo!!.columnCount)
    assertEquals(true, detail.collectionInfo!!.isHierarchical)
  }

  @Test
  fun `maps rangeInfo`() {
    val node = AccessibilityNode(
      rangeInfo = AccessibilityNode.RangeInfo(
        type = 1,
        min = 0f,
        max = 100f,
        current = 50f,
      ),
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertNotNull(detail.rangeInfo)
    assertEquals(1, detail.rangeInfo!!.type)
    assertEquals(0f, detail.rangeInfo!!.min)
    assertEquals(100f, detail.rangeInfo!!.max)
    assertEquals(50f, detail.rangeInfo!!.current)
  }

  @Test
  fun `maps actions and drawingOrder`() {
    val node = AccessibilityNode(
      actions = listOf("ACTION_CLICK", "ACTION_SCROLL_FORWARD"),
      drawingOrder = 5,
    )

    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility
    assertEquals(listOf("ACTION_CLICK", "ACTION_SCROLL_FORWARD"), detail.actions)
    assertEquals(5, detail.drawingOrder)
  }

  @Test
  fun `default AccessibilityNode maps to default DriverNodeDetail`() {
    val node = AccessibilityNode()
    val result = node.toTrailblazeNode()
    val detail = result.driverDetail as DriverNodeDetail.AndroidAccessibility

    assertNull(detail.className)
    assertNull(detail.resourceId)
    assertNull(detail.text)
    assertEquals(true, detail.isEnabled)
    assertEquals(false, detail.isClickable)
    assertEquals(0, detail.inputType)
  }

  // --- filterImportantForAccessibility ---

  private fun node(
    id: Long,
    important: Boolean,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = id,
    driverDetail = DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = important),
    children = children,
  )

  @Test
  fun `filterImportantForAccessibility keeps all-important tree unchanged`() {
    val root = node(1, true, listOf(node(2, true), node(3, true)))
    val result = root.filterImportantForAccessibility()
    assertEquals(2, result.children.size)
    assertEquals(2L, result.children[0].nodeId)
    assertEquals(3L, result.children[1].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility drops non-important leaf`() {
    val root = node(1, true, listOf(node(2, false)))
    val result = root.filterImportantForAccessibility()
    assertEquals(0, result.children.size)
  }

  @Test
  fun `filterImportantForAccessibility promotes children of non-important node`() {
    val root = node(1, true, listOf(
      node(2, false, listOf(node(3, true), node(4, true))),
    ))
    val result = root.filterImportantForAccessibility()
    assertEquals(2, result.children.size)
    assertEquals(3L, result.children[0].nodeId)
    assertEquals(4L, result.children[1].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility always keeps the root`() {
    val root = node(1, false, listOf(node(2, true)))
    val result = root.filterImportantForAccessibility()
    assertEquals(1L, result.nodeId)
    assertEquals(1, result.children.size)
    assertEquals(2L, result.children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility promotes through deep non-important chain`() {
    val root = node(1, true, listOf(
      node(2, false, listOf(
        node(3, false, listOf(
          node(4, true),
        )),
      )),
    ))
    val result = root.filterImportantForAccessibility()
    assertEquals(1, result.children.size)
    assertEquals(4L, result.children[0].nodeId)
  }

  // --- response-size guard ---
  // The filter must not broaden beyond `isImportantForAccessibility`. A node
  // that is editable / clickable / focusable or exposes interactive actions
  // but is NOT marked important is dropped by default — users can reach it
  // via the --all / ALL_ELEMENTS flag. This keeps the default tree compact.

  private fun nodeWithDetail(
    id: Long,
    detail: DriverNodeDetail.AndroidAccessibility,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = id,
    driverDetail = detail,
    children = children,
  )

  @Test
  fun `filterImportantForAccessibility drops non-important editable or clickable nodes`() {
    // If this ever regresses to a broad keep-rule (editable / clickable /
    // focusable / ACTION_SET_TEXT), every background view that happens to be
    // clickable will start showing up and the snapshot will balloon. Per-signal
    // coverage ensures we fail loudly if any single signal is added back.
    val root = node(
      1, true,
      listOf(
        nodeWithDetail(
          10,
          DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false, isEditable = true),
        ),
        nodeWithDetail(
          11,
          DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false, isClickable = true),
        ),
        nodeWithDetail(
          12,
          DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false, isFocusable = true),
        ),
        nodeWithDetail(
          13,
          DriverNodeDetail.AndroidAccessibility(
            isImportantForAccessibility = false,
            actions = listOf("ACTION_SET_TEXT", "ACTION_CLICK"),
          ),
        ),
      ),
    )
    val result = root.filterImportantForAccessibility()
    assertEquals(0, result.children.size, "All non-important nodes should be dropped regardless of interactive signals")
  }

  // --- WebView exception ---
  // WebView descendants are HTML nodes whose `isImportantForAccessibility` is not set by
  // Chromium, and the WebView container itself is typically not marked important either.
  // Without the exception, the default snapshot loses every scrap of page content
  // Chromium does expose (page title, ARIA-labeled landmarks, etc.).

  @Test
  fun `filterImportantForAccessibility preserves WebView even when not-important`() {
    val webChild = nodeWithDetail(
      20,
      DriverNodeDetail.AndroidAccessibility(
        isImportantForAccessibility = false,
        text = "Example Domain",
      ),
    )
    val webView = nodeWithDetail(
      10,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(webChild),
    )
    val root = node(1, true, listOf(webView))
    val result = root.filterImportantForAccessibility()

    // WebView itself survives despite isImportantForAccessibility=false
    assertEquals(1, result.children.size)
    assertEquals(10L, result.children[0].nodeId)
    // ...and so does its subtree (which would otherwise be dropped by the heuristic).
    assertEquals(1, result.children[0].children.size)
    assertEquals(20L, result.children[0].children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility preserves entire subtree when root is a WebView`() {
    // Receiver-side guard: a tree rooted at a WebView must be returned untouched. The
    // recursive child-filter would otherwise still trim non-important descendants of
    // the root WebView, contradicting the kdoc promise that the entire WebView subtree
    // is preserved as-is.
    val notImportantLeaf = nodeWithDetail(
      30,
      DriverNodeDetail.AndroidAccessibility(
        isImportantForAccessibility = false,
        text = "Some web text",
      ),
    )
    val notImportantInner = nodeWithDetail(
      20,
      DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false),
      children = listOf(notImportantLeaf),
    )
    val webViewRoot = nodeWithDetail(
      10,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(notImportantInner),
    )

    val result = webViewRoot.filterImportantForAccessibility()

    // Root identity preserved.
    assertEquals(10L, result.nodeId)
    // Full subtree preserved exactly — none of the non-important descendants are dropped.
    assertEquals(1, result.children.size)
    assertEquals(20L, result.children[0].nodeId)
    assertEquals(1, result.children[0].children.size)
    assertEquals(30L, result.children[0].children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility WebView exception only matches exact webkit class`() {
    // Don't accidentally preserve subclasses or unrelated views whose className contains "WebView".
    // The match is exact `android.webkit.WebView` only.
    val pretender = nodeWithDetail(
      10,
      DriverNodeDetail.AndroidAccessibility(
        className = "com.example.MyWebViewWrapper",
        isImportantForAccessibility = false,
      ),
      children = listOf(
        nodeWithDetail(20, DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false)),
      ),
    )
    val root = node(1, true, listOf(pretender))
    val result = root.filterImportantForAccessibility()

    // Pretender + its child should both be dropped via normal importance filtering.
    assertEquals(0, result.children.size)
  }

  @Test
  fun `filterImportantForAccessibility promotes WebView out of non-important parent`() {
    // The interesting combination: a WebView nested under a parent that gets filtered out
    // (isImportantForAccessibility = false). The non-important parent must be dropped per
    // the standard heuristic, but the WebView and its subtree must survive and be promoted
    // up to take the parent's place. Without this guarantee, sites that wrap their WebView
    // in a non-semantic container (FrameLayout, etc.) would lose all web content even
    // though the WebView exception is in place.
    val webChild = nodeWithDetail(
      30,
      DriverNodeDetail.AndroidAccessibility(
        isImportantForAccessibility = false,
        text = "Example Domain",
      ),
    )
    val webView = nodeWithDetail(
      20,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(webChild),
    )
    val nonImportantWrapper = nodeWithDetail(
      10,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        isImportantForAccessibility = false,
      ),
      children = listOf(webView),
    )
    val root = node(1, true, listOf(nonImportantWrapper))

    val result = root.filterImportantForAccessibility()

    // Wrapper is dropped; WebView is promoted directly under the root.
    assertEquals(1, result.children.size)
    assertEquals(20L, result.children[0].nodeId)
    // WebView's subtree survives intact despite the wrapper being filtered.
    assertEquals(1, result.children[0].children.size)
    assertEquals(30L, result.children[0].children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility tolerates non-AndroidAccessibility driver details`() {
    // The `isWebView()` predicate guards on `driverDetail is DriverNodeDetail.AndroidAccessibility`
    // before reaching for `className`. Other driver-detail types (Web, IosAxe, etc.) should
    // therefore route through the `else -> true` branch and be kept as-is. The function is
    // `internal` and currently only called from the Android accessibility path, but this is
    // a cheap guarantee against a future caller passing a heterogeneous tree.
    val webNode = TrailblazeNode(
      nodeId = 10,
      driverDetail = DriverNodeDetail.Web(),
      children = emptyList(),
    )
    val root = node(1, true, listOf(webNode))

    val result = root.filterImportantForAccessibility()

    // Non-Android nodes pass through unchanged.
    assertEquals(1, result.children.size)
    assertEquals(10L, result.children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility preserves nested WebViews as a single unit`() {
    // A WebView inside a WebView (iframe / ad container). The child-side guard returns
    // the outer WebView without recursing, so the inner WebView never re-enters the
    // receiver-side guard — but the kdoc promises the entire subtree is preserved as-is,
    // so every node beneath must survive even if marked isImportantForAccessibility=false.
    val deeplyNestedLeaf = nodeWithDetail(
      40,
      DriverNodeDetail.AndroidAccessibility(
        isImportantForAccessibility = false,
        text = "Ad inside iframe inside WebView",
      ),
    )
    val innerWebView = nodeWithDetail(
      30,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(deeplyNestedLeaf),
    )
    val outerWebView = nodeWithDetail(
      20,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(innerWebView),
    )
    val root = node(1, true, listOf(outerWebView))

    val result = root.filterImportantForAccessibility()

    // Outer WebView present.
    assertEquals(1, result.children.size)
    assertEquals(20L, result.children[0].nodeId)
    // Inner WebView present (not filtered despite isImportantForAccessibility=false).
    assertEquals(1, result.children[0].children.size)
    assertEquals(30L, result.children[0].children[0].nodeId)
    // Deepest leaf survives all the way down.
    assertEquals(1, result.children[0].children[0].children.size)
    assertEquals(40L, result.children[0].children[0].children[0].nodeId)
  }

  @Test
  fun `filterImportantForAccessibility keeps mixed-importance children under a WebView`() {
    // The kdoc promises the WebView subtree is preserved as-is. That has to mean ALL
    // children survive — including the non-important sibling between two important
    // siblings. If someone refactored the child-side guard to e.g. drop non-important
    // descendants while preserving the WebView itself, this test catches it.
    val importantA = nodeWithDetail(
      20,
      DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = true, text = "A"),
    )
    val notImportant = nodeWithDetail(
      21,
      DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = false, text = "B"),
    )
    val importantC = nodeWithDetail(
      22,
      DriverNodeDetail.AndroidAccessibility(isImportantForAccessibility = true, text = "C"),
    )
    val webView = nodeWithDetail(
      10,
      DriverNodeDetail.AndroidAccessibility(
        className = "android.webkit.WebView",
        isImportantForAccessibility = false,
      ),
      children = listOf(importantA, notImportant, importantC),
    )
    val root = node(1, true, listOf(webView))

    val result = root.filterImportantForAccessibility()

    // WebView kept, all three children survive in order.
    assertEquals(1, result.children.size)
    assertEquals(10L, result.children[0].nodeId)
    val webChildren = result.children[0].children
    assertEquals(3, webChildren.size)
    assertEquals(listOf(20L, 21L, 22L), webChildren.map { it.nodeId })
  }
}
