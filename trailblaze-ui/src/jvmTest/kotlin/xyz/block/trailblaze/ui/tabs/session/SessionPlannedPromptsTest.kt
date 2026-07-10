package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [plannedPromptsFromRawYaml], which feeds the session progress view's "pending objectives"
 * preview. It must read the ordered objective NL from BOTH a legacy v1 trail (a top-level list with
 * a `- prompts:` item) and a unified single-file trail (a `config:`/`trail:` mapping) — the latter
 * regressed to an empty preview when the extractor parsed only the v1 list shape.
 */
class SessionPlannedPromptsTest {

  @Test
  fun `a unified trail yields its ordered step objectives, excluding the trailhead`() {
    // The trailhead is step 0 (a deterministic bootstrap), not a planned objective — same as v1,
    // where a trailhead item never appears in the prompts list. A `verify:` step is an objective.
    val unified = """
      config:
        target: myapp
      trailhead:
        step: Log in first
        recording:
          android:
            pressBack: {}
      trail:
        - step: Open settings
        - verify: The settings screen is shown
    """.trimIndent()

    assertEquals(
      listOf("Open settings", "The settings screen is shown"),
      plannedPromptsFromRawYaml(unified),
    )
  }

  @Test
  fun `a v1 trail yields its prompt-step objectives in order`() {
    val v1 = """
      - config:
          target: myapp
      - prompts:
          - step: Open settings
          - step: The settings screen is shown
    """.trimIndent()

    assertEquals(
      listOf("Open settings", "The settings screen is shown"),
      plannedPromptsFromRawYaml(v1),
    )
  }

  @Test
  fun `unparseable yaml yields an empty preview rather than throwing`() {
    assertEquals(emptyList(), plannedPromptsFromRawYaml("this: is: not: a: trail"))
    assertEquals(emptyList(), plannedPromptsFromRawYaml(""))
  }
}
