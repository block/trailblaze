package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.client.TrailblazeJson

/**
 * Fixture-driven tests for [AndroidCompactElementList].
 *
 * Companion to [AndroidCompactElementListTest], which builds trees programmatically.
 * These tests load real subtrees captured from device snapshots — small JSON slices
 * extracted from the framework's logged view hierarchy. The point is to exercise the
 * compact builder against shapes that actually show up in the wild, including the
 * awkward-but-common ones (clickable wrapper container with the label one level
 * down on a non-clickable text child) that hand-rolled fixtures tend to gloss over.
 *
 * Adding a fixture: drop a JSON file into
 * `src/jvmTest/resources/fixtures/android-accessibility/` containing a
 * [TrailblazeNode] subtree, then add a test below that loads it and asserts the
 * compact-list invariants you care about. Keep fixtures small — extract just the
 * subtree relevant to the bug, not the whole captured screen.
 */
class AndroidCompactElementListFixtureTest {

  private fun loadFixture(name: String): TrailblazeNode {
    val resource = checkNotNull(this::class.java.classLoader.getResource("fixtures/android-accessibility/$name")) {
      "Fixture not found: $name"
    }
    return TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(TrailblazeNode.serializer(), resource.readText())
  }

  /**
   * Real shape from the Square pre-login screen: an unlabeled clickable
   * `android.view.View` wraps a TextView "Sign in" that itself only carries
   * accessibility-focus / selection actions (no `ACTION_CLICK`).
   *
   * The compact list must emit a single ref pointing at the **clickable parent**
   * (so a `tap ref=…` sends `ACTION_CLICK` to the node that actually handles it),
   * with the child's text absorbed as the parent's label. Emitting the ref on the
   * TextView would silently no-op on tap because TextView lacks ACTION_CLICK; doing
   * the parent/child fold here is what keeps the recordable selector tied to the
   * node that actually does something.
   */
  @Test
  fun `clickable wrapper around text-only child gets the ref with absorbed label`() {
    val root = loadFixture("sign-in-button.json")
    val result = AndroidCompactElementList.build(root)

    // Exactly one element should be exposed — the clickable wrapper. The TextView
    // child's label gets folded into the parent line, not its own ref.
    assertEquals(
      1,
      result.elementNodeIds.size,
      "Expected one ref on the clickable wrapper; got refs for: ${result.elementNodeIds}\n${result.text}",
    )
    // The ref must be on the clickable parent (nodeId=8), not on the TextView (nodeId=6).
    // If this flips to 6 the tap will dispatch ACTION_CLICK to a node that doesn't
    // handle it, and the screen won't transition — the bug we want to keep regressing.
    assertEquals(8L, result.elementNodeIds.single())
    // The compact line should carry the absorbed label so the LLM can identify it.
    assertTrue(
      result.text.contains("\"Sign in\""),
      "Expected 'Sign in' label absorbed onto the parent line, got:\n${result.text}",
    )
    // Verify the ref→node mapping points at the clickable parent.
    val ref = result.refMapping.entries.single()
    assertEquals(8L, ref.value)
    assertNotNull(ref.key)
  }

  /**
   * Real shape from a Material 3 / Compose `NavigationBar` bottom-tab — the
   * currently-selected "More" tab as captured from a real device session.
   *
   * The parent `android.view.View` carries `isSelected=true`, `roleDescription="Tab"` on
   * a sibling, and a `collectionItemInfo` row/column — but **no `isClickable`** and **no
   * `ACTION_CLICK`** in its action list (only `ACTION_FOCUS` + `ACTION_64`). Compose's M3
   * `NavigationBarItem` drops the click action from the selected tab; tapping the same
   * tab again is a runtime no-op. The visible "More" label lives on a child TextView
   * that is itself **not** selected.
   *
   * Before the fix the compact text emitted `TextView "More"` on the child with no
   * `[selected]` marker — leaving the agent unable to tell which bottom-nav tab was
   * active. This manifested as the LLM re-tapping "More" 17+ times in a row and
   * stuck-detecting out of the trail. The fix surfaces `[selected]` by treating
   * `isSelected` as meaningful in `isMeaningful()` and absorbing the child label up to
   * the parent in `resolveChildLabel()` even when the parent isn't clickable.
   */
  @Test
  fun `m3 navigation bar selected tab surfaces selected marker with absorbed label`() {
    val root = loadFixture("m3-navigation-bar-selected-tab.json")
    val result = AndroidCompactElementList.build(root)

    // Exactly one ref on the selected parent View (node 51), not on the child TextView
    // (node 49). If the ref flips to 49, the `[selected]` marker disappears (because
    // the TextView itself isn't selected) and the agent loses the active-tab signal.
    assertEquals(
      1,
      result.elementNodeIds.size,
      "Expected one ref on the selected parent; got refs for: ${result.elementNodeIds}\n${result.text}",
    )
    assertEquals(51L, result.elementNodeIds.single())
    // The label must be absorbed up to the parent so the line reads `"More" [selected]`
    // and not `[c596]  [selected]` with the text on an indented child line.
    assertTrue(
      result.text.contains("\"More\" [selected]"),
      "Expected '\"More\" [selected]' on a single line, got:\n${result.text}",
    )
  }
}
