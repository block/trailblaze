package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exit-code contract for `trailblaze toolbox` when the daemon returns
 * an empty result. Agents shelling out branch on `$?` rather than parsing stderr
 * — a "tool not found" surfacing as exit 0 means automation can't distinguish
 * "rendered the catalogue" from "asked about something that isn't there."
 *
 * The contract: any envelope whose mode-specific shape signals "no result" maps
 * to [TrailblazeExitCode.MISUSE] (input naming something that doesn't exist),
 * never `SUCCESS`:
 *
 *  - `--name <unknown>` → `ToolDiscoveryNameResult(error = "Tool '…' not found …")`
 *  - `--target <unknown>` → `ToolDiscoveryTargetResult(error = "Target '…' not found …")`
 *  - `--search <no-matches>` → `ToolDiscoverySearchResult(query = …, matches = null)`
 *    (the daemon's distinguished "no rows" shape, no `error` field)
 *  - role filter (`trailheads`/`shortcuts`) with empty `trailheadTools` /
 *    `shortcutTools` → same regression class as search-no-matches
 *  - top-level `error` (e.g. unknown `--device`) → routed the same way regardless
 *    of which mode-specific formatter would otherwise have rendered it
 *  - malformed daemon JSON (protocol violation) → also `NOT_FOUND`; treating
 *    a garbage response as success would defeat the same `&&`-chain contract
 *
 * Regression pin: pre-fix, `formatToolsResult` returned `Unit` and `call()`
 * unconditionally returned `SUCCESS.code`, even when [Console.error] had
 * already emitted an envelope error — so a wrapping script branching on `$?`
 * could not tell a successful render from an empty-result envelope.
 */
class ToolboxCommandExitCodeTest {

  private val command = ToolboxCommand()

  // ---------------------------------------------------------------------------------------
  // --name: unknown tool returns MISUSE-shaped outcome
  // ---------------------------------------------------------------------------------------

  @Test
  fun `name mode with unknown tool returns NOT_FOUND`() {
    val envelope = """{"error":"Tool 'nonexistent_tool' not found. Use toolbox() to see all available tools."}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = "nonexistent_tool",
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
    assertEquals(TrailblazeExitCode.MISUSE.code, outcome.exitCode)
  }

  @Test
  fun `name mode with found tool returns OK`() {
    val envelope = """
      {
        "tool": {
          "name": "tap",
          "description": "Tap an element on the screen.",
          "requiredParameters": [],
          "optionalParameters": []
        }
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = "tap",
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // --search: zero matches returns MISUSE-shaped outcome (the user asked about a keyword
  // that doesn't exist in the catalogue — agent needs to know the search came up empty)
  // ---------------------------------------------------------------------------------------

  @Test
  fun `search mode with no matches returns NOT_FOUND`() {
    val envelope = """{"query":"xyzzy","matches":null}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = "xyzzy",
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
    assertEquals(TrailblazeExitCode.MISUSE.code, outcome.exitCode)
  }

  @Test
  fun `search mode with empty matches array returns NOT_FOUND`() {
    // Defensive: the daemon currently emits `matches: null` for zero hits, but
    // `matches: []` is the equivalent shape and must be treated the same way —
    // otherwise a future daemon-side refactor flips the exit code silently.
    val envelope = """{"query":"xyzzy","matches":[]}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = "xyzzy",
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
  }

  @Test
  fun `search mode with matches returns OK`() {
    val envelope = """
      {
        "query": "tap",
        "matches": [
          {
            "source": "Touch",
            "tool": {"name": "tap", "description": "Tap an element."}
          }
        ]
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = "tap",
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // --target: unknown target returns MISUSE-shaped outcome
  // ---------------------------------------------------------------------------------------

  @Test
  fun `target mode with unknown target returns NOT_FOUND`() {
    val envelope = """{"error":"Target 'unknown_app' not found. Available targets: default, sampleapp"}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = "unknown_app",
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
    assertEquals(TrailblazeExitCode.MISUSE.code, outcome.exitCode)
  }

  @Test
  fun `target mode with known target and tool groups returns OK`() {
    val envelope = """
      {
        "target": "sampleapp",
        "displayName": "Sample App",
        "currentPlatform": "android",
        "toolGroups": [
          {"name": "sample_tools", "description": "Sample tools.", "tools": ["sample_a"]}
        ]
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = "sampleapp",
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Top-level error envelope (e.g. `--device=typo` rejected pre-dispatch) routes through
  // the same not-found path regardless of which mode the call was in.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `top-level error envelope returns NOT_FOUND in index mode`() {
    val envelope = """{"error":"Unknown platform 'androd'. Accepted values: android, ios, web."}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Sanity: a fully-populated index envelope is OK.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `index mode with platform toolsets returns OK`() {
    val envelope = """
      {
        "currentTarget": "default",
        "currentPlatform": "android",
        "platformToolsets": [
          {"name": "touch", "description": "Touch tools.", "tools": ["tap"]}
        ]
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Role filter: empty trailheads/shortcuts list is the same regression class as a search
  // with zero matches — the user asked the catalogue for X tools and got none.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `role filter trailheads with empty list returns NOT_FOUND`() {
    val envelope = """
      {
        "currentTarget": "default",
        "currentPlatform": "android",
        "trailheadTools": []
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = "trailheads",
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
    assertEquals(TrailblazeExitCode.MISUSE.code, outcome.exitCode)
  }

  @Test
  fun `role filter trailheads with populated list returns OK`() {
    val envelope = """
      {
        "currentTarget": "default",
        "currentPlatform": "android",
        "trailheadTools": ["launch_app"]
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = "trailheads",
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  @Test
  fun `role filter shortcuts with empty list returns NOT_FOUND`() {
    val envelope = """{"currentTarget":"default","currentPlatform":"android","shortcutTools":[]}"""
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = "shortcuts",
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Malformed daemon response — non-JSON or top-level non-object is a protocol violation,
  // not a successful render. Branch on `$?` would otherwise treat garbage as success.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `malformed JSON returns NOT_FOUND`() {
    val outcome = command.formatToolsResult(
      content = "not valid json {{{",
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
  }

  @Test
  fun `top-level JSON array returns NOT_FOUND`() {
    // Daemon contract is JsonObject — a top-level array is a protocol violation.
    val outcome = command.formatToolsResult(
      content = "[1, 2, 3]",
      nameFilter = null,
      targetFilter = null,
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Coverage gaps closed: target-found-no-tools, --target=default routing, dispatch order.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `target mode with target found but no tool groups returns OK`() {
    // "Target exists, has no custom tools for this platform" is informational —
    // the daemon successfully resolved the target, the rendered output explains
    // there's nothing to show. Distinct from "target not found" which is MISUSE.
    val envelope = """
      {
        "target": "sampleapp",
        "displayName": "Sample App",
        "currentPlatform": "android"
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = "sampleapp",
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  @Test
  fun `targetFilter default routes to index mode and returns OK`() {
    // The default-target equality check at the dispatch site routes "default" to
    // index mode rather than the target-specific formatter. Pins the case-
    // insensitive equality so a refactor of the dispatch can't silently flip it.
    val envelope = """
      {
        "currentTarget": "default",
        "currentPlatform": "android",
        "platformToolsets": [
          {"name": "touch", "description": "Touch tools.", "tools": ["tap"]}
        ]
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = envelope,
      nameFilter = null,
      targetFilter = "default",
      searchQuery = null,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  @Test
  fun `name filter wins when combined with search filter`() {
    // Dispatch precedence in formatToolsResult is name → search → role → target → index.
    // A future refactor that reorders the branches would change which mode "wins"
    // when the user passes overlapping flags; pin the current behavior so the change
    // is visible.
    val nameModeEnvelope = """
      {
        "tool": {
          "name": "tap",
          "description": "Tap an element.",
          "requiredParameters": [],
          "optionalParameters": []
        }
      }
    """.trimIndent()
    val outcome = command.formatToolsResult(
      content = nameModeEnvelope,
      nameFilter = "tap",
      targetFilter = null,
      searchQuery = "irrelevant",
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = false,
    )
    // Name-mode formatter rendered the lines, so OK. If search had won, the same
    // envelope would have surfaced as NOT_FOUND because it lacks a `matches` array.
    assertEquals(ToolboxRenderOutcome.OK, outcome)
  }

  // ---------------------------------------------------------------------------------------
  // Enum-level mapping: the exit codes live on the enum, not in the test. Asserting them
  // here pins the contract once for the production [ToolboxCommand.call] consumer.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `outcome enum maps OK to SUCCESS and NOT_FOUND to MISUSE`() {
    assertEquals(TrailblazeExitCode.SUCCESS.code, ToolboxRenderOutcome.OK.exitCode)
    assertEquals(TrailblazeExitCode.MISUSE.code, ToolboxRenderOutcome.NOT_FOUND.exitCode)
  }
}
