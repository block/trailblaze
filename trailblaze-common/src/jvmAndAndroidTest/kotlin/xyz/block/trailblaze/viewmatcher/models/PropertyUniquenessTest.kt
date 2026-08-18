package xyz.block.trailblaze.viewmatcher.models

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewmatcher.models.PropertyUniqueness.Companion.analyzePropertyUniqueness

/**
 * Behavioral contract for [analyzePropertyUniqueness]'s Maestro-semantics matching: node text
 * is treated as BOTH a regex (Orchestra's IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE dialect)
 * and an exact literal, so text that happens to be a valid regex that can't match itself —
 * prices, parenthesized counts — must still match its own node through the literal leg.
 * That leg regressed once by comparing against the compiled pattern instead of the raw
 * target text; these tests pin the observable counts.
 */
class PropertyUniquenessTest {

  private var nextNodeId = 1L

  private fun node(
    text: String? = null,
    resourceId: String? = null,
  ) = ViewHierarchyTreeNode(nodeId = nextNodeId++, text = text, resourceId = resourceId)

  private fun analyze(target: ViewHierarchyTreeNode, vararg siblings: ViewHierarchyTreeNode) =
    analyzePropertyUniqueness(
      target = target,
      root = ViewHierarchyTreeNode(nodeId = nextNodeId++, children = listOf(target) + siblings),
    )

  @Test
  fun `text that is a valid regex but cannot match itself still matches its own node`() {
    // Each is a well-formed pattern ("$" anchor, "(...)" group) that does not match its own
    // literal spelling — only the exact-literal leg can count the node itself.
    for (text in listOf("Total: \$5.00", "Add item (2)", "Save 50% (limited)")) {
      val result = analyze(node(text = text), node(text = "Unrelated"))
      assertThat(result.textOccurrences, name = text).isEqualTo(1)
      assertThat(result.textIsUnique, name = text).isTrue()
    }
  }

  @Test
  fun `regex-shaped text also matches other nodes via Maestro regex semantics`() {
    val result = analyze(node(text = "Total: .*"), node(text = "Total: \$5.00"))
    assertThat(result.textOccurrences).isEqualTo(2)
    assertThat(result.textIsUnique).isFalse()
  }

  @Test
  fun `dot matches newlines - Maestro DOT_MATCHES_ALL dialect`() {
    val result = analyze(node(text = "Review.sale"), node(text = "Review\nsale"))
    assertThat(result.textOccurrences).isEqualTo(2)
    assertThat(result.textIsUnique).isFalse()
  }

  @Test
  fun `invalid regex degrades to case-insensitive literal matching`() {
    val result = analyze(node(text = "50% off (("), node(text = "50% OFF (("))
    assertThat(result.textOccurrences).isEqualTo(2)
    assertThat(result.textIsUnique).isFalse()
  }

  @Test
  fun `unique text and id report unique`() {
    val result = analyze(
      node(text = "Checkout", resourceId = "com.app:id/checkout"),
      node(text = "Back", resourceId = "com.app:id/back"),
    )
    assertThat(result.textIsUnique).isTrue()
    assertThat(result.idIsUnique).isTrue()
  }

  @Test
  fun `id matches other nodes by suffix after the last slash`() {
    val result = analyze(
      node(text = "Save", resourceId = "save"),
      node(text = "Other", resourceId = "com.app:id/save"),
    )
    assertThat(result.idOccurrences).isEqualTo(2)
    assertThat(result.idIsUnique).isFalse()
  }
}
