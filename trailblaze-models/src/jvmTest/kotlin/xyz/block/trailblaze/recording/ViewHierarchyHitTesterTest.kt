package xyz.block.trailblaze.recording

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewHierarchyHitTesterTest {

  @Test
  fun `hitTest returns deepest node containing point`() {
    val child = createNode(
      nodeId = 2,
      text = "Sign In",
      clickable = true,
      centerX = 150,
      centerY = 250,
      width = 100,
      height = 50,
    )
    val root = createNode(
      nodeId = 1,
      centerX = 200,
      centerY = 400,
      width = 400,
      height = 800,
      children = listOf(child),
    )

    val result = ViewHierarchyHitTester.hitTest(root, 150, 250)
    assertNotNull(result)
    assertEquals("Sign In", result.text)
    assertEquals(2L, result.nodeId)
  }

  @Test
  fun `hitTest returns null for point outside all bounds`() {
    val root = createNode(
      nodeId = 1,
      centerX = 200,
      centerY = 400,
      width = 400,
      height = 800,
    )

    val result = ViewHierarchyHitTester.hitTest(root, 500, 500)
    assertNull(result)
  }

  @Test
  fun `hitTest prefers clickable node over non-clickable sibling`() {
    val nonClickable = createNode(
      nodeId = 2,
      text = "Label",
      clickable = false,
      centerX = 150,
      centerY = 250,
      width = 200,
      height = 100,
    )
    val clickable = createNode(
      nodeId = 3,
      text = "Button",
      clickable = true,
      centerX = 150,
      centerY = 250,
      width = 200,
      height = 100,
    )
    val root = createNode(
      nodeId = 1,
      centerX = 200,
      centerY = 400,
      width = 400,
      height = 800,
      children = listOf(nonClickable, clickable),
    )

    val result = ViewHierarchyHitTester.hitTest(root, 150, 250)
    assertNotNull(result)
    assertEquals("Button", result.text)
  }

  @Test
  fun `hitTest prefers clickable node over focusable-only sibling`() {
    val clickable = createNode(
      nodeId = 2,
      text = "Button",
      clickable = true,
      centerX = 150,
      centerY = 250,
      width = 200,
      height = 100,
    )
    val focusable = ViewHierarchyTreeNode(
      nodeId = 3,
      text = "Input",
      focusable = true,
      clickable = false,
      centerPoint = "150,250",
      dimensions = "200x100",
    )
    val root = createNode(
      nodeId = 1,
      centerX = 200,
      centerY = 400,
      width = 400,
      height = 800,
      children = listOf(clickable, focusable),
    )

    val result = ViewHierarchyHitTester.hitTest(root, 150, 250)
    assertNotNull(result)
    assertEquals("Button", result.text)
    assertTrue(result.clickable)
  }

  @Test
  fun `hitTest returns parent when children don't contain point`() {
    val child = createNode(
      nodeId = 2,
      text = "Button",
      clickable = true,
      centerX = 100,
      centerY = 100,
      width = 80,
      height = 40,
    )
    val root = createNode(
      nodeId = 1,
      text = "Container",
      centerX = 200,
      centerY = 400,
      width = 400,
      height = 800,
      children = listOf(child),
    )

    // Point is in root but not in child
    val result = ViewHierarchyHitTester.hitTest(root, 300, 600)
    assertNotNull(result)
    assertEquals("Container", result.text)
  }

  @Test
  fun `resolveSemanticText prefers accessibilityText`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      accessibilityText = "Sign In Button",
      text = "Sign In",
      resourceId = "btn_sign_in",
    )
    assertEquals("Sign In Button", ViewHierarchyHitTester.resolveSemanticText(node))
  }

  @Test
  fun `resolveSemanticText falls back to text`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      text = "Submit",
    )
    assertEquals("Submit", ViewHierarchyHitTester.resolveSemanticText(node))
  }

  @Test
  fun `resolveSemanticText falls back to hintText`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      hintText = "Enter email",
    )
    assertEquals("Enter email", ViewHierarchyHitTester.resolveSemanticText(node))
  }

  @Test
  fun `resolveSemanticText falls back to resourceId`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      resourceId = "email_field",
    )
    assertEquals("email_field", ViewHierarchyHitTester.resolveSemanticText(node))
  }

  @Test
  fun `resolveSemanticText returns null for empty node`() {
    val node = ViewHierarchyTreeNode(nodeId = 1)
    assertNull(ViewHierarchyHitTester.resolveSemanticText(node))
  }

  @Test
  fun `isInputField detects EditText`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "android.widget.EditText",
      focusable = true,
    )
    assertTrue(ViewHierarchyHitTester.isInputField(node))
  }

  @Test
  fun `isInputField detects node with hintText`() {
    val node = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "SomeCustomView",
      focusable = true,
      hintText = "Enter value",
    )
    assertTrue(ViewHierarchyHitTester.isInputField(node))
  }

  private fun createNode(
    nodeId: Long,
    text: String? = null,
    clickable: Boolean = false,
    centerX: Int = 0,
    centerY: Int = 0,
    width: Int = 0,
    height: Int = 0,
    children: List<ViewHierarchyTreeNode> = emptyList(),
  ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
    nodeId = nodeId,
    text = text,
    clickable = clickable,
    centerPoint = "$centerX,$centerY",
    dimensions = "${width}x$height",
    children = children,
  )
}
