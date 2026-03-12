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
    assertEquals("Click me", detail.tooltipText)
    assertEquals("Error!", detail.error)
    assertEquals(true, detail.isShowingHintText)
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
}
