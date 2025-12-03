package xyz.block.trailblaze.viewmatcher.search

import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for HierarchyNavigator utility for navigating view hierarchies.
 */
class HierarchyNavigatorTest {

  companion object {
    fun createNode(
      nodeId: Long,
      text: String? = null,
      children: List<ViewHierarchyTreeNode> = emptyList(),
    ): ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = text,
      children = children,
    )
  }

  @Test
  fun `findPathToTarget returns empty list when target is root`() {
    val root = createNode(nodeId = 1, text = "Root")

    val path = HierarchyNavigator.findPathToTarget(
      target = root,
      root = root,
    )

    assertTrue(path.isEmpty(), "Path should be empty when target is root")
  }

  @Test
  fun `findPathToTarget returns path from root to parent for direct child`() {
    val child = createNode(nodeId = 2, text = "Child")
    val root = createNode(nodeId = 1, text = "Root", children = listOf(child))

    val path = HierarchyNavigator.findPathToTarget(
      target = child,
      root = root,
    )

    assertEquals(1, path.size, "Path should contain only the root")
    assertEquals(1, path[0].nodeId)
    assertEquals("Root", path[0].text)
  }

  @Test
  fun `findPathToTarget returns full path for deeply nested target`() {
    // Structure: Root -> Level1 -> Level2 -> Target
    val target = createNode(nodeId = 4, text = "Target")
    val level2 = createNode(nodeId = 3, text = "Level2", children = listOf(target))
    val level1 = createNode(nodeId = 2, text = "Level1", children = listOf(level2))
    val root = createNode(nodeId = 1, text = "Root", children = listOf(level1))

    val path = HierarchyNavigator.findPathToTarget(
      target = target,
      root = root,
    )

    assertEquals(3, path.size, "Path should contain all ancestors")
    assertEquals("Root", path[0].text, "First should be root")
    assertEquals("Level1", path[1].text, "Second should be Level1")
    assertEquals("Level2", path[2].text, "Third should be Level2 (immediate parent)")
  }

  @Test
  fun `findPathToTarget returns empty list when target not in tree`() {
    val target = createNode(nodeId = 999, text = "NonExistent")
    val child = createNode(nodeId = 2, text = "Child")
    val root = createNode(nodeId = 1, text = "Root", children = listOf(child))

    val path = HierarchyNavigator.findPathToTarget(
      target = target,
      root = root,
    )

    assertTrue(path.isEmpty(), "Path should be empty when target not found")
  }
}
