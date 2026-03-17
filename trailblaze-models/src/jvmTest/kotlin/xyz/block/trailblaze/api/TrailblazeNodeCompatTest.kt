package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.toIosMaestroTrailblazeNode

class TrailblazeNodeCompatTest {

  // ======================================================================
  // IosMaestro → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `IosMaestro converts to ViewHierarchyTreeNode with all properties`() {
    val node = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 200, bottom = 100),
      children = emptyList(),
      driverDetail = DriverNodeDetail.IosMaestro(
        text = "Hello",
        resourceId = "greeting_label",
        accessibilityText = "Greeting",
        className = "UILabel",
        hintText = "Greeting text",
        clickable = false,
        enabled = true,
        focused = false,
        checked = false,
        selected = false,
        focusable = false,
        scrollable = false,
        password = false,
        ignoreBoundsFiltering = true,
      ),
    )

    val vh = node.toViewHierarchyTreeNode()
    assertEquals(7L, vh.nodeId)
    assertEquals("UILabel", vh.className)
    assertEquals("greeting_label", vh.resourceId)
    assertEquals("Hello", vh.text)
    assertEquals("Greeting", vh.accessibilityText)
    assertEquals("Greeting text", vh.hintText)
    assertEquals(true, vh.enabled)
    assertEquals(false, vh.clickable)
    assertEquals(false, vh.focused)
    assertEquals(false, vh.checked)
    assertEquals(false, vh.selected)
    assertEquals(false, vh.scrollable)
    assertEquals(false, vh.password)
    assertEquals(false, vh.focusable)
    assertEquals(true, vh.ignoreBoundsFiltering)
    assertEquals("200x100", vh.dimensions)
    assertEquals("100,50", vh.centerPoint)
  }

  // ======================================================================
  // AndroidAccessibility → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `AndroidAccessibility converts to ViewHierarchyTreeNode`() {
    val node = TrailblazeNode(
      nodeId = 3,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 50, bottom = 50),
      children = emptyList(),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        resourceId = "com.example:id/btn",
        text = "OK",
        contentDescription = "Accept",
        hintText = null,
        isEnabled = true,
        isClickable = true,
        isFocused = false,
        isChecked = false,
        isSelected = false,
        isScrollable = false,
        isPassword = false,
        isFocusable = true,
      ),
    )

    val vh = node.toViewHierarchyTreeNode()
    assertEquals("android.widget.Button", vh.className)
    assertEquals("com.example:id/btn", vh.resourceId)
    assertEquals("OK", vh.text)
    assertEquals("Accept", vh.accessibilityText)
    assertNull(vh.hintText)
    assertEquals(true, vh.clickable)
    assertEquals(true, vh.focusable)
  }

  // ======================================================================
  // AndroidMaestro → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `AndroidMaestro converts to ViewHierarchyTreeNode`() {
    val node = TrailblazeNode(
      nodeId = 4,
      bounds = null,
      children = emptyList(),
      driverDetail = DriverNodeDetail.AndroidMaestro(
        text = "Cancel",
        resourceId = "btn_cancel",
        accessibilityText = "Cancel button",
        className = "android.widget.Button",
        clickable = true,
        enabled = true,
        focused = false,
        checked = false,
        selected = false,
        focusable = true,
        scrollable = false,
        password = false,
      ),
    )

    val vh = node.toViewHierarchyTreeNode()
    assertEquals("Cancel", vh.text)
    assertEquals("btn_cancel", vh.resourceId)
    assertEquals("Cancel button", vh.accessibilityText)
    assertEquals("android.widget.Button", vh.className)
    assertEquals(true, vh.clickable)
    assertEquals(true, vh.focusable)
  }

  // ======================================================================
  // Web → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `Web converts to ViewHierarchyTreeNode`() {
    val node = TrailblazeNode(
      nodeId = 5,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 30),
      children = emptyList(),
      driverDetail = DriverNodeDetail.Web(
        ariaRole = "button",
        ariaName = "Submit",
      ),
    )

    val vh = node.toViewHierarchyTreeNode()
    assertEquals("button", vh.className) // ariaRole -> className
    assertEquals("Submit", vh.text) // ariaName -> text
    assertEquals("100x30", vh.dimensions)
  }

  // ======================================================================
  // Compose → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `Compose converts to ViewHierarchyTreeNode`() {
    val node = TrailblazeNode(
      nodeId = 6,
      bounds = null,
      children = emptyList(),
      driverDetail = DriverNodeDetail.Compose(
        testTag = "submit_btn",
        role = "Button",
        text = "Submit",
        contentDescription = "Submit form",
        isEnabled = true,
        isFocused = false,
        isSelected = false,
        isPassword = false,
      ),
    )

    val vh = node.toViewHierarchyTreeNode()
    assertEquals("Button", vh.className) // role -> className
    assertEquals("submit_btn", vh.resourceId) // testTag -> resourceId
    assertEquals("Submit", vh.text)
    assertEquals("Submit form", vh.accessibilityText) // contentDescription -> accessibilityText
  }

  // ======================================================================
  // Round-trip: ViewHierarchyTreeNode → IosMaestro → ViewHierarchyTreeNode
  // ======================================================================

  @Test
  fun `IosMaestro round-trip preserves all matchable properties`() {
    val original = ViewHierarchyTreeNode(
      nodeId = 99,
      className = "UITextField",
      resourceId = "email_field",
      text = "user@example.com",
      accessibilityText = "Email input",
      hintText = "Enter email",
      enabled = true,
      clickable = true,
      focused = true,
      checked = false,
      selected = false,
      scrollable = false,
      password = true,
      focusable = true,
      centerPoint = "200,300",
      dimensions = "300x50",
    )

    // ViewHierarchyTreeNode → IosMaestro TrailblazeNode → ViewHierarchyTreeNode
    val trailblazeNode = original.toIosMaestroTrailblazeNode()
    val roundTripped = trailblazeNode.toViewHierarchyTreeNode()

    assertEquals(original.nodeId, roundTripped.nodeId)
    assertEquals(original.className, roundTripped.className)
    assertEquals(original.resourceId, roundTripped.resourceId)
    assertEquals(original.text, roundTripped.text)
    assertEquals(original.accessibilityText, roundTripped.accessibilityText)
    assertEquals(original.hintText, roundTripped.hintText)
    assertEquals(original.enabled, roundTripped.enabled)
    assertEquals(original.clickable, roundTripped.clickable)
    assertEquals(original.focused, roundTripped.focused)
    assertEquals(original.checked, roundTripped.checked)
    assertEquals(original.selected, roundTripped.selected)
    assertEquals(original.scrollable, roundTripped.scrollable)
    assertEquals(original.password, roundTripped.password)
    assertEquals(original.focusable, roundTripped.focusable)
  }

  @Test
  fun `IosMaestro round-trip preserves children`() {
    val child = ViewHierarchyTreeNode(nodeId = 2, text = "Child text")
    val parent = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "UIView",
      children = listOf(child),
    )

    val roundTripped = parent.toIosMaestroTrailblazeNode().toViewHierarchyTreeNode()
    assertEquals(1, roundTripped.children.size)
    assertEquals("Child text", roundTripped.children[0].text)
  }
}
