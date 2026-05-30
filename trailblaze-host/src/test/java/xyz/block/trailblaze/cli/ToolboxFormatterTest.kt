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
  // Resolved-target header — fires when --target isn't passed so first-time users
  // discover the flag without having to know the word "target" exists.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `resolved-target header includes the source label verbatim`() {
    // The formatter is now a pure renderer — the CLI-resolution layer (ToolboxCommand)
    // owns the source → label mapping. The test pins that whatever sourceLabel string
    // the caller passes lands in the header verbatim, so wording tweaks happen at the
    // call site without re-wiring the formatter.
    val out = ToolboxFormatter.renderResolvedTargetHeader(
      resolved = "square",
      sourceLabel = "from workspace config",
      availableTargets = listOf("default", "square", "sampleapp"),
    )
    assertEquals(
      listOf(
        "Using target: square (no --target specified; from workspace config)",
        "Available targets: default, square, sampleapp",
        "To switch: --target <name>",
        "",
      ),
      out,
    )
  }

  @Test
  fun `resolved-target header collapses Available list to resolved when no targets discovered`() {
    val out = ToolboxFormatter.renderResolvedTargetHeader(
      resolved = "default",
      sourceLabel = "built-in default",
      availableTargets = emptyList(),
    )
    val availableLine = out.first { it.startsWith("Available targets:") }
    assertEquals(
      "Available targets: default",
      availableLine,
      "empty target catalogue should fall back to the resolved name rather than dangling colon",
    )
  }

  @Test
  fun `resolved-target header ends with a trailing blank line so the listing breathes`() {
    val out = ToolboxFormatter.renderResolvedTargetHeader(
      resolved = "default",
      sourceLabel = "built-in default",
      availableTargets = listOf("default"),
    )
    assertEquals("", out.last(), "Header must end with a blank line separating it from the toolset listing")
  }

  // ---------------------------------------------------------------------------------------
  // Toolbox banner — the unconditional `# Trailblaze toolbox — (target, platform)` line
  // that prepends every `toolbox` invocation. Distinct from the OOBE resolved-target
  // header above: the banner is non-negotiable context for downstream LLM consumers.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `banner renders target and platform when both are known`() {
    val out = ToolboxFormatter.renderToolboxBanner(target = "contacts", platform = "ios")
    assertEquals("# Trailblaze toolbox — contacts (ios)", out)
  }

  @Test
  fun `banner appends tool-name suffix when set so single-tool drill-downs are visibly distinct`() {
    val out = ToolboxFormatter.renderToolboxBanner(
      target = "sampleapp",
      platform = "ios",
      toolName = "sampleapp_signIn",
    )
    assertEquals(
      "# Trailblaze toolbox — sampleapp (ios) — tool: sampleapp_signIn",
      out,
    )
  }

  @Test
  fun `banner falls back to bare title when neither target nor platform are known`() {
    // `--name <tool>` mode invoked without a connected device or pinned target — we still
    // emit a banner so the output starts with a recognizable header, but with no
    // (target, platform) suffix to claim.
    val out = ToolboxFormatter.renderToolboxBanner(target = null, platform = null)
    assertEquals("# Trailblaze toolbox", out)
  }

  @Test
  fun `banner renders target-only when platform is unknown`() {
    val out = ToolboxFormatter.renderToolboxBanner(target = "sampleapp", platform = null)
    assertEquals("# Trailblaze toolbox — sampleapp", out)
  }

  @Test
  fun `banner treats blank target platform and tool name as null`() {
    // Belt-and-braces guard: the upstream CLI already pre-blanks the device spec via
    // `?.takeIf { it.isNotBlank() }`, but the formatter must independently reject empty
    // and whitespace-only inputs so a future caller can't produce `(<empty>)` / `— tool: `
    // dangling fragments. The behaviour mirrors `null` exactly.
    assertEquals(
      "# Trailblaze toolbox — sampleapp",
      ToolboxFormatter.renderToolboxBanner(target = "sampleapp", platform = ""),
    )
    assertEquals(
      "# Trailblaze toolbox — sampleapp",
      ToolboxFormatter.renderToolboxBanner(target = "sampleapp", platform = "   "),
    )
    assertEquals(
      "# Trailblaze toolbox",
      ToolboxFormatter.renderToolboxBanner(target = "", platform = ""),
    )
    assertEquals(
      "# Trailblaze toolbox — sampleapp (ios)",
      ToolboxFormatter.renderToolboxBanner(target = "sampleapp", platform = "ios", toolName = "   "),
      "blank tool name must not produce a `— tool: ` suffix",
    )
  }

  // ---------------------------------------------------------------------------------------
  // System prompt section — inlines the resolved target's curated LLM-facing prose so a
  // CLI-side agent gets parity with what the framework's in-session agent reads.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `system prompt section renders header content and trailing blank when set`() {
    val out = ToolboxFormatter.renderSystemPromptSection(
      "You are controlling iOS Contacts. Prefer accessibility labels over coordinates.",
    )
    assertEquals(
      listOf(
        "## System prompt",
        "",
        "You are controlling iOS Contacts. Prefer accessibility labels over coordinates.",
        "",
      ),
      out,
    )
  }

  @Test
  fun `system prompt section is empty when content is null so callers can skip without a guard`() {
    assertTrue(ToolboxFormatter.renderSystemPromptSection(null).isEmpty())
  }

  @Test
  fun `system prompt section is empty when content is blank or whitespace`() {
    // Defensive against a system_prompt_file: pointing at an empty or whitespace-only file
    // — rendering `## System prompt` followed by nothing would be a downgrade vs. omission.
    assertTrue(ToolboxFormatter.renderSystemPromptSection("").isEmpty())
    assertTrue(ToolboxFormatter.renderSystemPromptSection("   \n  \t  \n").isEmpty())
  }

  @Test
  fun `system prompt section trims trailing whitespace but preserves internal blank lines`() {
    val out = ToolboxFormatter.renderSystemPromptSection(
      "Line one.\n\nLine two after a paragraph break.\n\n",
    )
    assertEquals(
      listOf(
        "## System prompt",
        "",
        "Line one.\n\nLine two after a paragraph break.",
        "",
      ),
      out,
      "internal paragraph break must survive; trailing whitespace stripped",
    )
  }

  @Test
  fun `tools header renders divider plus trailing blank`() {
    val out = ToolboxFormatter.renderToolsHeader()
    assertEquals(listOf("## Tools", ""), out)
  }

  @Test
  fun `system prompt block composes section plus tools header when prompt is set`() {
    // Integration-style test for the policy `ToolboxCommand` previously inlined: the
    // `## Tools` divider must follow the prompt section only when a prompt is rendered.
    val out = ToolboxFormatter.renderSystemPromptBlock("Use accessibility labels.")
    assertEquals(
      listOf(
        "## System prompt",
        "",
        "Use accessibility labels.",
        "",
        "## Tools",
        "",
      ),
      out,
    )
  }

  @Test
  fun `system prompt block returns empty when content is null or blank`() {
    // Pins the "no `## Tools` divider without a `## System prompt` above" half of the
    // policy — prompt-less targets must not introduce an orphaned tools header that
    // would split the existing catalog output.
    assertTrue(ToolboxFormatter.renderSystemPromptBlock(null).isEmpty())
    assertTrue(ToolboxFormatter.renderSystemPromptBlock("").isEmpty())
    assertTrue(ToolboxFormatter.renderSystemPromptBlock("   \n  \t  ").isEmpty())
  }

  // ---------------------------------------------------------------------------------------
  // systemPromptBlockForResponse — the cross-mode policy dispatcher
  // ---------------------------------------------------------------------------------------

  @Test
  fun `systemPromptBlockForResponse suppresses output in name mode regardless of prompt content`() {
    // `--name <tool>` is tool-specific output; the target-wide prompt would be noise above it.
    // The test pins the policy at the dispatcher seam so a future refactor of
    // ToolboxCommand.formatToolsResult can't silently drop the suppression.
    val json = Json.parseToJsonElement(
      """{"systemPrompt":"Real prompt that should NOT be rendered in name mode."}""",
    ) as kotlinx.serialization.json.JsonObject
    assertEquals(
      emptyList(),
      ToolboxFormatter.systemPromptBlockForResponse(json, isNameMode = true),
      "Name mode must suppress the system-prompt block even when the daemon shipped one.",
    )
  }

  @Test
  fun `systemPromptBlockForResponse emits the composed block in non-name modes when prompt is set`() {
    val json = Json.parseToJsonElement(
      """{"systemPrompt":"Test prompt."}""",
    ) as kotlinx.serialization.json.JsonObject
    assertEquals(
      listOf(
        "## System prompt",
        "",
        "Test prompt.",
        "",
        "## Tools",
        "",
      ),
      ToolboxFormatter.systemPromptBlockForResponse(json, isNameMode = false),
      "Index/target/search/role-filter modes must surface the prompt + Tools divider.",
    )
  }

  @Test
  fun `systemPromptBlockForResponse returns empty when daemon response has no systemPrompt field`() {
    // Forward-compat: older daemons may not ship the field. The CLI must degrade silently
    // rather than emit an orphan `## Tools` divider.
    val json = Json.parseToJsonElement("""{"otherField":"value"}""")
      as kotlinx.serialization.json.JsonObject
    assertTrue(
      ToolboxFormatter.systemPromptBlockForResponse(json, isNameMode = false).isEmpty(),
      "Missing systemPrompt field must produce no output (no orphan divider).",
    )
  }

  @Test
  fun `systemPromptBlockForResponse returns empty when daemon shipped a null systemPrompt`() {
    val json = Json.parseToJsonElement("""{"systemPrompt":null}""")
      as kotlinx.serialization.json.JsonObject
    assertTrue(
      ToolboxFormatter.systemPromptBlockForResponse(json, isNameMode = false).isEmpty(),
      "Explicit null systemPrompt must produce no output.",
    )
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
        "web_selectOption",
        "Select one or more options from a <select> dropdown element identified by its element ID, " +
          "ARIA descriptor, or CSS selector. Supports multi-select via array values.",
      ),
      ToolboxFormatter.compactToolPeekLine(
        "web_pressKey",
        "Press a keyboard key or key combination.\n\nSupports modifiers like ctrl, shift, alt.",
      ),
      ToolboxFormatter.compactToolPeekLine("untyped", ""),
    )
    BaselineHarness.assertBaseline("toolbox-compact-tool-peek-lines.txt", lines.joinToString("\n"))
  }

  // ---------------------------------------------------------------------------------------
  // Role rendering — renderRoleSection / renderRoleEmptyMessage / collectToolDescriptions
  // ---------------------------------------------------------------------------------------

  @Test
  fun `renderRoleSection returns empty list when no tool names`() {
    val out = ToolboxFormatter.renderRoleSection(
      header = "Trailheads (start your trail here):",
      toolNames = emptyList(),
      descriptionsByName = emptyMap(),
    )
    assertEquals(emptyList(), out, "empty role list must produce no output so headline silently elides")
  }

  @Test
  fun `renderRoleSection renders header + indented tool rows with descriptions inlined`() {
    // Short descriptions deliberately stay under COMPACT_DESC_MAX_CHARS so this test pins the
    // wrapping (header line, two-space row indent, `- name: desc`) without re-exercising the
    // truncation logic — that's covered by the compactToolPeekLine tests above.
    val out = ToolboxFormatter.renderRoleSection(
      header = "Trailheads (start your trail here):",
      toolNames = listOf("trailheadOne", "trailheadTwo"),
      descriptionsByName = mapOf(
        "trailheadOne" to "Bootstraps trail one.",
        "trailheadTwo" to "Bootstraps trail two.",
      ),
    )
    assertEquals(
      listOf(
        "Trailheads (start your trail here):",
        "  - trailheadOne: Bootstraps trail one.",
        "  - trailheadTwo: Bootstraps trail two.",
      ),
      out,
    )
  }

  @Test
  fun `renderRoleSection falls back to bare name when description is missing`() {
    val out = ToolboxFormatter.renderRoleSection(
      header = "Shortcuts (jump between waypoints):",
      toolNames = listOf("sample_navigate_more_to_addons"),
      descriptionsByName = emptyMap(),
    )
    assertEquals(
      listOf(
        "Shortcuts (jump between waypoints):",
        "  - sample_navigate_more_to_addons",
      ),
      out,
      "tools without descriptions render as bare `- name` (no orphan trailing colon)",
    )
  }

  @Test
  fun `renderRoleSection does not emit leading or trailing blank lines`() {
    // The caller controls spacing between sections; the formatter itself MUST stay
    // tight so two consecutive sections don't accumulate blank lines.
    val out = ToolboxFormatter.renderRoleSection(
      header = "Trailheads (start your trail here):",
      toolNames = listOf("foo"),
      descriptionsByName = mapOf("foo" to "Does foo."),
    )
    assertFalse(out.first().isBlank(), "first line must not be blank")
    assertFalse(out.last().isBlank(), "last line must not be blank")
  }

  @Test
  fun `renderRoleEmptyMessage tells the author no role tools exist and points at waypoints skill`() {
    val out = ToolboxFormatter.renderRoleEmptyMessage(
      role = "trailheads",
      target = "sampleapp",
      platform = "android",
      suffix = "*.trailhead.yaml",
    )
    assertEquals(
      listOf(
        "No trailheads tools available for sampleapp on android.",
        "",
        "If you need one, use the `waypoints` skill to author a new *.trailhead.yaml in the relevant trailmap.",
      ),
      out,
    )
  }

  @Test
  fun `renderRoleEmptyMessage falls back to placeholder text when target or platform unknown`() {
    // Used by `toolbox trailheads` invoked without --target/--device — the empty hint
    // should still read coherently rather than emit "for null on null".
    val out = ToolboxFormatter.renderRoleEmptyMessage(
      role = "shortcuts",
      target = null,
      platform = null,
      suffix = "*.shortcut.yaml",
    )
    assertTrue(out.first().contains("for this target on this platform"))
    assertTrue(out.last().contains("waypoints"))
  }

  @Test
  fun `collectToolDescriptions reads from toolDetails entries in both platform and target toolsets`() {
    val platform = jsonArr(
      """[{"name":"core_interaction","toolDetails":[{"name":"tap","description":"Tap something."}]}]""",
    )
    val target = jsonArr(
      """[{"name":"sample_android_general","toolDetails":[{"name":"sample_launchAppSignedIn","description":"Launch the sample app signed in."}]}]""",
    )
    val out = ToolboxFormatter.collectToolDescriptions(platform, target)
    assertEquals(
      mapOf("tap" to "Tap something.", "sample_launchAppSignedIn" to "Launch the sample app signed in."),
      out,
    )
  }

  @Test
  fun `collectToolDescriptions returns empty map when only compact toolset form is present`() {
    // The compact form (`tools: [name, name, ...]`, no `toolDetails`) carries no
    // descriptions; the role view will render bare names rather than ranching out a
    // second daemon call per tool.
    val platform = jsonArr("""[{"name":"core_interaction","tools":["tap","inputText"]}]""")
    val out = ToolboxFormatter.collectToolDescriptions(platform, null)
    assertTrue(out.isEmpty(), "no toolDetails → no descriptions (compact form is descriptions-less)")
  }

  @Test
  fun `collectToolDescriptions first-write-wins on duplicate tool name`() {
    // If a tool appears in both platform and target toolsets (rare but legal), the
    // platform definition wins because it's the more general one. Authors who notice
    // this can disambiguate via `toolbox --name <id>`.
    val platform = jsonArr(
      """[{"name":"core_interaction","toolDetails":[{"name":"launchApp","description":"Generic launch."}]}]""",
    )
    val target = jsonArr(
      """[{"name":"sample_android_general","toolDetails":[{"name":"launchApp","description":"Target-specific launch override."}]}]""",
    )
    val out = ToolboxFormatter.collectToolDescriptions(platform, target)
    assertEquals(
      "Generic launch.",
      out["launchApp"],
      "first toolset listed wins — preserves the platform-layer definition over a target override",
    )
  }

  @Test
  fun `collectToolDescriptions tolerates null arguments`() {
    assertEquals(emptyMap(), ToolboxFormatter.collectToolDescriptions(null, null))
  }

  // ---------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------

  private fun jsonArr(text: String): JsonArray =
    Json.parseToJsonElement(text) as JsonArray
}
