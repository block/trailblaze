package xyz.block.trailblaze.viewmatcher.models

import maestro.TreeNode
import xyz.block.trailblaze.api.TrailblazeElementSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Boundary coverage for [deterministicTapTarget] — the primitive behind the Maestro 2.6.1
 * containsChild selector relaxation. Maestro taps `filter(nodes).first()` after a clickable-first
 * sort, so a multi-match resolves deterministically only when exactly one match is clickable.
 */
class ElementMatchesTest {

  private val selector = TrailblazeElementSelector()

  // Non-empty attributes so TreeNode.toViewHierarchyTreeNode() (eagerly !!-ed by SingleMatch) is non-null.
  private fun node(text: String, clickable: Boolean?) =
    TreeNode(attributes = mutableMapOf("text" to text), clickable = clickable)

  @Test
  fun `single match taps its node`() {
    val only = node("a", clickable = true)
    assertEquals(only, ElementMatches.SingleMatch(only, selector).deterministicTapTarget())
  }

  @Test
  fun `multi match with exactly one clickable taps that node`() {
    val clickable = node("clickable", clickable = true)
    val matches = ElementMatches.MultipleMatches(
      nodes = listOf(node("a", clickable = false), clickable, node("b", clickable = null)),
      trailblazeElementSelector = selector,
    )
    assertEquals(clickable, matches.deterministicTapTarget())
  }

  @Test
  fun `multi match with no clickable node has no deterministic tap`() {
    val matches = ElementMatches.MultipleMatches(
      nodes = listOf(node("a", clickable = false), node("b", clickable = null)),
      trailblazeElementSelector = selector,
    )
    assertNull(matches.deterministicTapTarget())
  }

  @Test
  fun `multi match with multiple clickable nodes has no deterministic tap`() {
    val matches = ElementMatches.MultipleMatches(
      nodes = listOf(node("a", clickable = true), node("b", clickable = true)),
      trailblazeElementSelector = selector,
    )
    assertNull(matches.deterministicTapTarget())
  }

  @Test
  fun `no matches has no tap target`() {
    assertNull(ElementMatches.NoMatches(selector).deterministicTapTarget())
  }
}
