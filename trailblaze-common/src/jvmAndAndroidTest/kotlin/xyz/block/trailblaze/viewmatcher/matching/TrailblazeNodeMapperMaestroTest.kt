package xyz.block.trailblaze.viewmatcher.matching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import maestro.TreeNode
import xyz.block.trailblaze.api.DriverNodeDetail

class TrailblazeNodeMapperMaestroTest {

  @Test
  fun `maps text properties from attributes`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "Submit",
        "resource-id" to "btn_submit",
        "accessibilityText" to "Submit button",
        "class" to "UIButton",
        "hintText" to "Tap here",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val detail = result.driverDetail as DriverNodeDetail.IosMaestro
    assertEquals("Submit", detail.text)
    assertEquals("btn_submit", detail.resourceId)
    assertEquals("Submit button", detail.accessibilityText)
    assertEquals("UIButton", detail.className)
    assertEquals("Tap here", detail.hintText)
  }

  @Test
  fun `maps boolean properties from TreeNode fields`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf("text" to "Item"),
      clickable = true,
      enabled = false,
      focused = true,
      checked = true,
      selected = true,
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val detail = result.driverDetail as DriverNodeDetail.IosMaestro
    assertEquals(true, detail.clickable)
    assertEquals(false, detail.enabled)
    assertEquals(true, detail.focused)
    assertEquals(true, detail.checked)
    assertEquals(true, detail.selected)
  }

  @Test
  fun `maps string boolean attributes`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "Field",
        "focusable" to "true",
        "scrollable" to "true",
        "password" to "true",
        "visible" to "false",
        "ignoreBoundsFiltering" to "true",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val detail = result.driverDetail as DriverNodeDetail.IosMaestro
    assertEquals(true, detail.focusable)
    assertEquals(true, detail.scrollable)
    assertEquals(true, detail.password)
    assertEquals(false, detail.visible)
    assertEquals(true, detail.ignoreBoundsFiltering)
  }

  @Test
  fun `parses bounds from attributes`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "Button",
        "bounds" to "[100,200][300,400]",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val bounds = result.bounds
    assertNotNull(bounds)
    assertEquals(100, bounds.left)
    assertEquals(200, bounds.top)
    assertEquals(300, bounds.right)
    assertEquals(400, bounds.bottom)
  }

  @Test
  fun `null bounds when bounds attribute missing`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf("text" to "No bounds"),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    assertNull(result.bounds)
  }

  @Test
  fun `null bounds for malformed bounds string`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "Bad bounds",
        "bounds" to "not-a-bounds-string",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    assertNull(result.bounds)
  }

  @Test
  fun `maps children recursively`() {
    val child1 = TreeNode(attributes = mutableMapOf("text" to "Child 1"))
    val child2 = TreeNode(attributes = mutableMapOf("text" to "Child 2"))
    val parent = TreeNode(
      attributes = mutableMapOf("class" to "UIView"),
      children = listOf(child1, child2),
    )

    val result = parent.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    assertEquals(2, result.children.size)
    assertIs<DriverNodeDetail.IosMaestro>(result.children[0].driverDetail)
    assertEquals("Child 1", (result.children[0].driverDetail as DriverNodeDetail.IosMaestro).text)
    assertEquals("Child 2", (result.children[1].driverDetail as DriverNodeDetail.IosMaestro).text)
  }

  @Test
  fun `filters out empty nodes`() {
    val empty = TreeNode(attributes = mutableMapOf(), children = emptyList())
    val valid = TreeNode(attributes = mutableMapOf("text" to "Valid"))
    val parent = TreeNode(
      attributes = mutableMapOf("class" to "UIView"),
      children = listOf(empty, valid),
    )

    val result = parent.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    assertEquals(1, result.children.size)
    assertEquals("Valid", (result.children[0].driverDetail as DriverNodeDetail.IosMaestro).text)
  }

  @Test
  fun `returns null for empty node`() {
    val empty = TreeNode(attributes = mutableMapOf(), children = emptyList())
    val result = empty.toTrailblazeNodeIosMaestro()
    assertNull(result)
  }

  @Test
  fun `defaults for null TreeNode fields`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf("text" to "Item"),
      // All nullable fields left as null
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val detail = result.driverDetail as DriverNodeDetail.IosMaestro
    assertEquals(false, detail.clickable)
    assertEquals(true, detail.enabled) // defaults to true
    assertEquals(false, detail.focused)
    assertEquals(false, detail.checked)
    assertEquals(false, detail.selected)
  }

  @Test
  fun `blank attribute values treated as null`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "   ",
        "resource-id" to "",
        "class" to "UILabel",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val detail = result.driverDetail as DriverNodeDetail.IosMaestro
    assertNull(detail.text)
    assertNull(detail.resourceId)
    assertEquals("UILabel", detail.className)
  }

  @Test
  fun `resolveText priority is text then hintText then accessibilityText`() {
    val withAll = DriverNodeDetail.IosMaestro(
      text = "primary",
      hintText = "hint",
      accessibilityText = "a11y",
    )
    assertEquals("primary", withAll.resolveText())

    val noText = DriverNodeDetail.IosMaestro(
      hintText = "hint",
      accessibilityText = "a11y",
    )
    assertEquals("hint", noText.resolveText())

    val onlyA11y = DriverNodeDetail.IosMaestro(
      accessibilityText = "a11y",
    )
    assertEquals("a11y", onlyA11y.resolveText())

    val empty = DriverNodeDetail.IosMaestro()
    assertNull(empty.resolveText())
  }

  @Test
  fun `handles negative coordinates in bounds`() {
    val treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to "Offscreen",
        "bounds" to "[-10,-20][100,200]",
      ),
    )

    val result = treeNode.toTrailblazeNodeIosMaestro()
    assertNotNull(result)
    val bounds = result.bounds
    assertNotNull(bounds)
    assertEquals(-10, bounds.left)
    assertEquals(-20, bounds.top)
    assertEquals(100, bounds.right)
    assertEquals(200, bounds.bottom)
  }
}
