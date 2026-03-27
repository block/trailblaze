package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for [ViewHierarchyTreeNode.bounds] parsing and fallback behavior. */
class ViewHierarchyTreeNodeBoundsTest {

  @Test
  fun `valid integer bounds take precedence over legacy fields`() {
    val node = ViewHierarchyTreeNode(
      x1 = 10, y1 = 20, x2 = 110, y2 = 120,
      centerPoint = "999,999", dimensions = "999x999",
    )
    val b = node.bounds!!
    assertEquals(10, b.x1)
    assertEquals(20, b.y1)
    assertEquals(110, b.x2)
    assertEquals(120, b.y2)
  }

  @Test
  fun `invalid integer rect falls back to legacy centerPoint and dimensions`() {
    // x2 < x1 is an invalid rectangle — should fall back to legacy parsing
    val node = ViewHierarchyTreeNode(
      x1 = 100, y1 = 0, x2 = 50, y2 = 100,
      centerPoint = "200,200", dimensions = "100x100",
    )
    val b = node.bounds!!
    // Fallback: center=200,200 dims=100x100 → left=150, top=150, right=250, bottom=250
    assertEquals(150, b.x1)
    assertEquals(150, b.y1)
    assertEquals(250, b.x2)
    assertEquals(250, b.y2)
  }

  @Test
  fun `all-zero integer bounds falls back to legacy parsing`() {
    val node = ViewHierarchyTreeNode(
      centerPoint = "50,75", dimensions = "20x30",
    )
    val b = node.bounds!!
    assertEquals(40, b.x1)
    assertEquals(60, b.y1)
    assertEquals(60, b.x2)
    assertEquals(90, b.y2)
  }

  @Test
  fun `malformed dimensions returns null bounds`() {
    val node = ViewHierarchyTreeNode(
      centerPoint = "50,75", dimensions = "not_a_number",
    )
    assertNull(node.bounds)
  }

  @Test
  fun `malformed centerPoint returns null bounds`() {
    val node = ViewHierarchyTreeNode(
      centerPoint = "abc,def", dimensions = "20x30",
    )
    assertNull(node.bounds)
  }

  @Test
  fun `empty dimensions returns null bounds`() {
    val node = ViewHierarchyTreeNode(
      centerPoint = "50,75", dimensions = "",
    )
    assertNull(node.bounds)
  }

  @Test
  fun `missing dimensions returns null bounds`() {
    val node = ViewHierarchyTreeNode(centerPoint = "50,75")
    assertNull(node.bounds)
  }

  @Test
  fun `missing centerPoint returns null bounds`() {
    val node = ViewHierarchyTreeNode(dimensions = "20x30")
    assertNull(node.bounds)
  }

  @Test
  fun `no bounds fields at all returns null`() {
    val node = ViewHierarchyTreeNode(text = "hello")
    assertNull(node.bounds)
  }

  @Test
  fun `element at origin with non-zero size uses integer bounds`() {
    // x1=0 but x2 != 0, so hasIntBounds is true
    val node = ViewHierarchyTreeNode(x1 = 0, y1 = 0, x2 = 100, y2 = 50)
    val b = node.bounds!!
    assertEquals(0, b.x1)
    assertEquals(0, b.y1)
    assertEquals(100, b.x2)
    assertEquals(50, b.y2)
  }
}
