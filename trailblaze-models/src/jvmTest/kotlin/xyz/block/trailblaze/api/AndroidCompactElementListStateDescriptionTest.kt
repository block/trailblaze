package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCompactElementListStateDescriptionTest {
  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail = DriverNodeDetail.AndroidAccessibility(),
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = detail,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    children = children,
  )

  @Test
  fun `checkable container absorbs child text instead of using state as its label`() {
    for ((checked, state) in listOf(true to "Selected", false to "Not selected")) {
      val child = node(DriverNodeDetail.AndroidAccessibility(text = "Medium"))
      val control = node(
        DriverNodeDetail.AndroidAccessibility(
          resourceId = "com.example:id/size_medium",
          stateDescription = state,
          isCheckable = true,
          isChecked = checked,
        ),
        children = listOf(child),
      )
      val root = node(children = listOf(control))
      val result = AndroidCompactElementList.build(root)
      val line = result.text.lines().single { "[id=com.example:id/size_medium]" in it }

      assertContains(line, "\"Medium\"")
      assertContains(line, "[state: \"$state\"]")
      assertContains(line, if (checked) "[checked]" else "[unchecked]")
      assertFalse(line.substringBefore("[id=").contains("\"$state\""), line)
      assertTrue(child.nodeId in result.textNodeIds)
      val ref = result.refMapping.entries.single { it.value == control.nodeId }.key
      assertContains(line, "[$ref]")
      assertEquals(control.nodeId, CompactScreenElements.buildForAndroid(root).applyRefsToTree(root).findFirst { it.ref == ref }?.nodeId)
    }
  }

  @Test
  fun `state-only nodes stay visible and addressable across Android drivers`() {
    val details = listOf(
      DriverNodeDetail.AndroidAccessibility(stateDescription = "Expanded"),
      DriverNodeDetail.AndroidView(stateDescription = "Expanded"),
      DriverNodeDetail.Compose(stateDescription = "Expanded"),
    )
    for (detail in details) {
      val control = node(detail)
      val root = node(children = listOf(control))
      val result = AndroidCompactElementList.build(root)

      assertContains(result.text, "[state: \"Expanded\"]")
      assertFalse(result.text.substringBefore("[state:").contains("\"Expanded\""), result.text)
      assertTrue(control.nodeId in result.elementNodeIds)
      val ref = result.refMapping.entries.single { it.value == control.nodeId }.key
      assertContains(result.text, "[$ref]")
      assertEquals(control.nodeId, CompactScreenElements.buildForAndroid(root).applyRefsToTree(root).findFirst { it.ref == ref }?.nodeId)
    }
  }

  @Test
  fun `state-only children are not flattened into ordinary text`() {
    val child = node(DriverNodeDetail.AndroidAccessibility(stateDescription = "Loading"))
    val parent = node(
      DriverNodeDetail.AndroidAccessibility(text = "Details", isClickable = true),
      children = listOf(child),
    )
    val result = AndroidCompactElementList.build(node(children = listOf(parent)))

    assertContains(result.text, "[state: \"Loading\"]")
    assertTrue(child.nodeId in result.elementNodeIds)
    assertFalse(child.nodeId in result.textNodeIds)
  }

  @Test
  fun `view and Compose controls keep child labels separate from state`() {
    val details = listOf(
      DriverNodeDetail.AndroidView(isClickable = true, stateDescription = "Busy"),
      DriverNodeDetail.Compose(hasClickAction = true, stateDescription = "Busy"),
    )
    for (detail in details) {
      val child = node(DriverNodeDetail.AndroidAccessibility(text = "Upload"))
      val control = node(detail, children = listOf(child))
      val result = AndroidCompactElementList.build(node(children = listOf(control)))

      assertContains(result.text, "\"Upload\" [state: \"Busy\"]")
      assertTrue(child.nodeId in result.textNodeIds)
    }
  }

  @Test
  fun `pane containers keep their title and state annotation`() {
    val details = listOf(
      DriverNodeDetail.AndroidAccessibility(paneTitle = "Checkout", stateDescription = "Expanded"),
      DriverNodeDetail.Compose(paneTitle = "Checkout", stateDescription = "Expanded"),
    )
    for (detail in details) {
      val child = node(DriverNodeDetail.AndroidAccessibility(text = "Total", isClickable = true))
      val pane = node(detail, children = listOf(child))
      val result = AndroidCompactElementList.build(node(children = listOf(pane)))

      assertContains(result.text.lines().first(), "\"Checkout\" [state: \"Expanded\"]:")
      assertContains(result.text, "\"Total\"")
      assertTrue(child.nodeId in result.elementNodeIds)
    }
  }

  @Test
  fun `list containers keep their item count and state annotation`() {
    val child = node(DriverNodeDetail.AndroidAccessibility(text = "Item", isClickable = true))
    val list = node(
      DriverNodeDetail.AndroidAccessibility(
        className = "androidx.recyclerview.widget.RecyclerView",
        stateDescription = "Refreshing",
        isScrollable = true,
        collectionInfo = DriverNodeDetail.AndroidAccessibility.CollectionInfo(rowCount = 2, columnCount = 1, isHierarchical = false),
      ),
      children = listOf(child),
    )
    val result = AndroidCompactElementList.build(node(children = listOf(list)))

    assertContains(result.text.lines().first(), "RecyclerView [2 items] [state: \"Refreshing\"]:")
    assertContains(result.text, "\"Item\"")
    assertTrue(child.nodeId in result.elementNodeIds)
  }

  @Test
  fun `real text remains a label even when it equals the state description`() {
    val control = node(
      DriverNodeDetail.AndroidAccessibility(text = "Ready", stateDescription = "Ready"),
    )
    val result = AndroidCompactElementList.build(node(children = listOf(control)))

    assertContains(result.text, "\"Ready\"")
    assertTrue(control.nodeId in result.elementNodeIds)
  }

  @Test
  fun `text hint and content description still supply ordinary labels`() {
    val details = listOf(
      DriverNodeDetail.AndroidAccessibility(text = "Status", stateDescription = "Pending"),
      DriverNodeDetail.AndroidAccessibility(hintText = "Status", stateDescription = "Pending"),
      DriverNodeDetail.AndroidAccessibility(contentDescription = "Status", stateDescription = "Pending"),
    )
    for (detail in details) {
      val result = AndroidCompactElementList.build(node(children = listOf(node(detail))))
      assertContains(result.text, "\"Status\" [state: \"Pending\"]")
    }
  }

  @Test
  fun `blank state descriptions do not make empty wrappers meaningful`() {
    val empty = node(DriverNodeDetail.AndroidAccessibility(stateDescription = "  "))
    val result = AndroidCompactElementList.build(node(children = listOf(empty)))

    assertFalse(empty.nodeId in result.elementNodeIds)
    assertTrue(result.refMapping.isEmpty())
  }
}
