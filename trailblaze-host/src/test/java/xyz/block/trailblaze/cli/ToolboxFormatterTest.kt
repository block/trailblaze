package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the rendering rules for [ToolboxFormatter]. Two flavors of assertion:
 *
 *  1. **Targeted assertions** at the top — load-bearing behaviors that MUST hold no matter
 *     how the output style evolves. Ellipsis on truncation, no-ellipsis on full fit,
 *     lowercase platform ids, column padding, etc. If a future tweak breaks one of these,
 *     the user-facing affordance regresses (e.g. silent description drop after a "small"
 *     wording change).
 *
 *  2. **Snapshot test** at the bottom — eyeball baseline pinned to
 *     `src/test/resources/toolbox-formatter-snapshots/compact-default.txt`. The header of
 *     that file explicitly says it's NOT a contract; reviewers should look at any diff and
 *     decide if it's intentional. Catches visual regressions (column drift, accidental
 *     blank lines, wording changes that don't violate a targeted assertion).
 *
 * The split is deliberate: targeted assertions reject behavioral regressions; the snapshot
 * makes formatting drift visible without fossilizing it.
 */
class ToolboxFormatterTest {

  // ---------------------------------------------------------------------------------------
  // Targeted assertions — load-bearing behaviors
  // ---------------------------------------------------------------------------------------

  @Test
  fun `compact line shows name and description with colon`() {
    val line = ToolboxFormatter.compactToolPeekLine("tap", "Tap an element on the screen.")
    assertEquals("- tap: Tap an element on the screen.", line)
  }

  @Test
  fun `compact line drops colon when description is empty`() {
    // Descriptions can be empty for inline-script tools that haven't been given one yet.
    // Trailing ": " on an empty description looks like a bug.
    val line = ToolboxFormatter.compactToolPeekLine("untyped_tool", "")
    assertEquals("- untyped_tool", line)
  }

  @Test
  fun `compact line drops colon when description is only whitespace`() {
    val line = ToolboxFormatter.compactToolPeekLine("ws_tool", "   \n\n  \t  ")
    assertEquals("- ws_tool", line)
  }

  @Test
  fun `compact line appends ellipsis when first line exceeds char cap`() {
    val long = "x".repeat(ToolboxFormatter.COMPACT_DESC_MAX_CHARS + 50)
    val line = ToolboxFormatter.compactToolPeekLine("noisy", long)
    assertTrue(line.endsWith("…"), "Expected trailing ellipsis on hard-truncated line: $line")
    // Total displayed text (the part after `: `) is at most COMPACT_DESC_MAX_CHARS chars
    // including the ellipsis itself.
    val displayed = line.substringAfter(": ")
    assertTrue(
      displayed.length <= ToolboxFormatter.COMPACT_DESC_MAX_CHARS,
      "Displayed description is longer than the cap (${displayed.length}): $displayed",
    )
  }

  @Test
  fun `compact line appends ellipsis when description has more lines below the first`() {
    // The case the original cut missed: first line fits in the cap, but the description
    // has additional paragraphs the LLM uses for context. Without the ellipsis, the user
    // thinks they have the complete description from the listing alone.
    val multi = """
      Tap an element on the screen.

      Used for clicking buttons, links, or any tappable widget. Pair with
      ``--objective`` so Trailblaze can self-heal if the UI changes.
    """.trimIndent()
    val line = ToolboxFormatter.compactToolPeekLine("tap", multi)
    assertTrue(line.endsWith("…"), "Expected trailing ellipsis when more lines exist: $line")
    assertTrue(line.startsWith("- tap: Tap an element on the screen."))
  }

  @Test
  fun `compact line does NOT append ellipsis when description is a single short line`() {
    val line = ToolboxFormatter.compactToolPeekLine("tap", "Tap an element.")
    assertFalse(line.endsWith("…"), "Single short line must not get an ellipsis: $line")
    assertEquals("- tap: Tap an element.", line)
  }

  @Test
  fun `compact line does NOT append ellipsis when description has only trailing whitespace`() {
    // Trailing whitespace should be normalized away; otherwise users see a spurious
    // ellipsis when an author left a stray newline after the description.
    val line = ToolboxFormatter.compactToolPeekLine("tap", "Tap an element.\n")
    assertFalse(line.endsWith("…"), "Trailing whitespace must not trigger ellipsis: $line")
  }

  @Test
  fun `target list block emits Targets header`() {
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(ToolboxFormatter.TargetSummary("clock", listOf("android", "ios"))),
    )
    assertTrue("Targets:" in block, "Missing 'Targets:' header in: $block")
  }

  @Test
  fun `target list block returns empty list when no other targets`() {
    val block = ToolboxFormatter.renderTargetListBlock(emptyList())
    assertTrue(block.isEmpty(), "Expected empty list for no targets, got: $block")
  }

  @Test
  fun `target list block pads target names so platform column aligns`() {
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(
        ToolboxFormatter.TargetSummary("clock", listOf("android", "ios")),
        ToolboxFormatter.TargetSummary("sampleapp", listOf("android", "ios")),
        ToolboxFormatter.TargetSummary("ws", listOf("android")),
      ),
    )
    // Find the rows that contain the target names.
    val shortRow = block.first { it.contains("clock ") || it.endsWith("clock  (android, ios)") }
    val longRow = block.first { it.contains("sampleapp") }
    // Platform open-paren should appear at the same column in every row regardless of
    // name length — that's the point of the padding.
    assertEquals(
      shortRow.indexOf('('),
      longRow.indexOf('('),
      "Platform '(' must align across rows. Got\n  $shortRow\n  $longRow",
    )
  }

  @Test
  fun `target list block keeps platforms lowercase for direct --device use`() {
    // The whole point of the lowercase: a user copies `(android, ios)` and pastes one of
    // those tokens into `--device`. Display names like "Android" or "Web Browser" would
    // fail. We only assert what the formatter renders — server-side normalization is
    // covered by the renderer's input contract.
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(ToolboxFormatter.TargetSummary("clock", listOf("android", "ios"))),
    )
    val joined = block.joinToString("\n")
    assertTrue("(android, ios)" in joined, "Expected lowercase platforms in: $joined")
    assertFalse("Android" in joined, "Display-name 'Android' leaked into target list: $joined")
    assertFalse("iOS" in joined, "Display-name 'iOS' leaked into target list: $joined")
  }

  @Test
  fun `target list block ends with switch-target hint`() {
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(ToolboxFormatter.TargetSummary("clock", listOf("android"))),
    )
    val joined = block.joinToString("\n")
    assertTrue("Use --target" in joined, "Missing '--target' hint in: $joined")
    assertTrue("End session to switch" in joined, "Missing 'End session to switch' hint in: $joined")
  }

  @Test
  fun `target list block handles a target with no platforms gracefully`() {
    // Edge case: a target whose YAML doesn't declare any platforms. Render the row
    // without parens rather than crashing or printing a bare `()`.
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(ToolboxFormatter.TargetSummary("orphan", platforms = null)),
    )
    val orphanRow = block.first { it.contains("orphan") }
    assertFalse("(" in orphanRow, "No-platforms row should not render an empty '()': $orphanRow")
  }

  @Test
  fun `parseTargetSummariesJson tolerates malformed platform entries`() {
    // Defensive parse: a stray null or nested object inside `platforms` shouldn't crash
    // the entire toolbox listing — drop the bad entry and keep going.
    val parsed = ToolboxFormatter.parseTargetSummariesJson(
      jsonArr(
        """
        [
          {"name": "good", "platforms": ["android", null, {"nested": "ignored"}, "ios"]},
          {"name": "noplatforms"}
        ]
        """.trimIndent(),
      ),
    )
    assertEquals(2, parsed.size)
    assertEquals(listOf("android", "ios"), parsed[0].platforms)
    assertEquals(null, parsed[1].platforms)
  }

  @Test
  fun `parseTargetSummariesJson uses placeholder for missing name rather than crashing`() {
    val parsed = ToolboxFormatter.parseTargetSummariesJson(
      jsonArr("""[{"platforms": ["android"]}]"""),
    )
    assertEquals(1, parsed.size)
    assertEquals("?", parsed[0].name)
  }

  // ---------------------------------------------------------------------------------------
  // Snapshot test — eyeball baseline, NOT a contract
  // ---------------------------------------------------------------------------------------

  /**
   * Pins the full rendered shape against a checked-in baseline file. The file's own
   * header explicitly disclaims contract status — drift means "look and decide", not
   * "auto-accept". Set `-Ptrailblaze.recordSnapshots=true` (or env
   * `TRAILBLAZE_RECORD_SNAPSHOTS=1`) to write the current output back to the file when
   * an intentional format change ships.
   */
  @Test
  fun `target list block snapshot`() {
    val block = ToolboxFormatter.renderTargetListBlock(
      listOf(
        ToolboxFormatter.TargetSummary("clock", listOf("android", "ios")),
        ToolboxFormatter.TargetSummary("sampleapp", listOf("android", "ios")),
        ToolboxFormatter.TargetSummary("ws", listOf("android")),
        ToolboxFormatter.TargetSummary("wikipedia", listOf("android")),
      ),
    )
    BaselineHarness.assertBaseline("toolbox-target-list-block.txt", block.joinToString("\n"))
  }

  @Test
  fun `compact tool peek lines snapshot`() {
    val lines = listOf(
      ToolboxFormatter.compactToolPeekLine("tap", "Tap an element on the screen."),
      ToolboxFormatter.compactToolPeekLine("assertEquals", "Verify two values match exactly."),
      ToolboxFormatter.compactToolPeekLine(
        "web_select_option",
        "Select one or more options from a <select> dropdown element identified by its element ID, " +
          "ARIA descriptor, or CSS selector. Supports multi-select via array values.",
      ),
      ToolboxFormatter.compactToolPeekLine(
        "web_press_key",
        "Press a keyboard key or key combination.\n\nSupports modifiers like ctrl, shift, alt.",
      ),
      ToolboxFormatter.compactToolPeekLine("untyped", ""),
    )
    BaselineHarness.assertBaseline("toolbox-compact-tool-peek-lines.txt", lines.joinToString("\n"))
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private fun jsonArr(text: String): JsonArray =
    Json.parseToJsonElement(text) as JsonArray
}
