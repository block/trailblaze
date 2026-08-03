package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [plannedPromptsFromRawYaml], which feeds the session progress view's "pending objectives"
 * preview. It reads the ordered objective NL from a unified single-file trail (a `config:`/`trail:`
 * mapping). A legacy v1 trail (a top-level list) is no longer parseable and degrades to an empty
 * preview rather than crashing the session view.
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
  fun `a legacy v1 trail yields an empty preview instead of crashing`() {
    // v1 (top-level list) is no longer parseable; the preview degrades to empty rather than
    // throwing, keeping the session view rendering.
    val v1 = """
      - config:
          target: myapp
      - prompts:
          - step: Open settings
          - step: The settings screen is shown
    """.trimIndent()

    assertEquals(emptyList(), plannedPromptsFromRawYaml(v1))
  }

  @Test
  fun `unparseable yaml yields an empty preview rather than throwing`() {
    assertEquals(emptyList(), plannedPromptsFromRawYaml("this: is: not: a: trail"))
    assertEquals(emptyList(), plannedPromptsFromRawYaml(""))
  }
}
