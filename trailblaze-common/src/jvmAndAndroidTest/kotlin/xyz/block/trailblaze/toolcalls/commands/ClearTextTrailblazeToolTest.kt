package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Locks the contract that `clearText` extracts the focused field's text length from the
 * view hierarchy snapshot, then passes that exact count to `eraseText`. This is the bit that
 * matters — every driver's `eraseText` cap behavior already has its own tests; the new
 * behavior here is "we read the right length first."
 */
class ClearTextTrailblazeToolTest {

  @Test
  fun `picks length of focused editable node with text`() {
    val root = container(
      ViewHierarchyTreeNode(
        focused = true,
        text = "hello world",
      ),
    )
    assertEquals(11, ClearTextTrailblazeTool.focusedEditableTextLength(root))
  }

  @Test
  fun `returns null when no node is focused`() {
    val root = container(
      ViewHierarchyTreeNode(focused = false, text = "not the one"),
      ViewHierarchyTreeNode(focused = false, text = "also not"),
    )
    assertNull(ClearTextTrailblazeTool.focusedEditableTextLength(root))
  }

  @Test
  fun `returns null when focused node has no text - drops to fallback at the call site`() {
    val root = container(
      ViewHierarchyTreeNode(focused = true, text = null),
    )
    assertNull(ClearTextTrailblazeTool.focusedEditableTextLength(root))
  }

  @Test
  fun `prefers innermost focused node over a focused ancestor`() {
    // Compose merged-semantics trees can mark both the EditText and its wrapping row as
    // focused. The innermost (last visited in depth-first order) is the actual input.
    val root = ViewHierarchyTreeNode(
      focused = true,
      text = "wrapper container text we should ignore",
      children = listOf(
        ViewHierarchyTreeNode(
          focused = true,
          text = "actual field value",
        ),
      ),
    )
    assertEquals(18, ClearTextTrailblazeTool.focusedEditableTextLength(root))
  }

  @Test
  fun `handles long content past Maestro's 50-char cap`() {
    val longValue = "x".repeat(327)
    val root = container(
      ViewHierarchyTreeNode(focused = true, text = longValue),
    )
    // The whole point of the tool: report the real length, not a clamped 50.
    assertEquals(327, ClearTextTrailblazeTool.focusedEditableTextLength(root))
  }

  @Test
  fun `fallback constant covers any realistic single-field content but stays bounded`() {
    // Sanity-checks the fallback so a future refactor doesn't silently raise it into
    // catastrophic-loop territory on the instrumentation driver.
    assertEquals(500, ClearTextTrailblazeTool.FALLBACK_ERASE_COUNT)
  }

  private fun container(vararg children: ViewHierarchyTreeNode): ViewHierarchyTreeNode =
    ViewHierarchyTreeNode(focused = false, children = children.toList())
}
