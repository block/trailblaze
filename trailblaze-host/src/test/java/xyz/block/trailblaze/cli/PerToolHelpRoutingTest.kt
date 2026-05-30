package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the routing decision for `trailblaze tool <name> --help` — the discoverable
 * per-tool help path. The rendering side-effect ([ToolHelpRenderer.renderHelp]) needs
 * a live daemon, so these tests exercise [perToolHelpToolName] in isolation and a
 * separate test pins the output of [ToolboxFormatter.renderToolNameLines] against a
 * fixture daemon response that includes the `ref` arg.
 *
 * Without this routing, the bare `tool --help` form would always win and users would
 * see the wrapper-options banner instead of the named tool's arg docs — the #1 UX
 * wart the OSS Trailblaze skill currently teaches agents to navigate.
 */
class PerToolHelpRoutingTest {

  private fun rootCommandLine(): CommandLine = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked during routing tests") },
      configProvider = { error("configProvider must not be invoked during routing tests") },
    ),
  ).setCaseInsensitiveEnumValuesAllowed(true)

  @Test
  fun `tool name plus --help routes to per-tool renderer`() {
    val parseResult = rootCommandLine().parseArgs("tool", "web_click", "--help")
    assertEquals(
      "web_click",
      perToolHelpToolName(parseResult),
      "tool <name> --help must surface the tool name so the renderer can target it",
    )
  }

  @Test
  fun `short -h alias also routes`() {
    // Picocli registers `-h` as an alias for `--help` via mixinStandardHelpOptions, so
    // muscle-memory-typed `tool web_click -h` must hit the same path as `--help`.
    val parseResult = rootCommandLine().parseArgs("tool", "tap", "-h")
    assertEquals("tap", perToolHelpToolName(parseResult))
  }

  @Test
  fun `bare tool --help with no tool name falls through to default wrapper help`() {
    // The wrapper-options banner remains the right surface when the user is asking
    // about the `tool` subcommand itself rather than a specific tool — `perToolHelpToolName`
    // returns null so the IExecutionStrategy delegates to picocli's RunLast.
    val parseResult = rootCommandLine().parseArgs("tool", "--help")
    assertNull(perToolHelpToolName(parseResult))
  }

  @Test
  fun `tool name without --help does not divert`() {
    // Without --help we want the normal execution path, not per-tool help — even if
    // required options are missing (picocli's parameter exception handler will surface
    // the missing-option error before we'd ever get here).
    val parseResult = rootCommandLine().parseArgs(
      "tool",
      "web_click",
      "--device=android",
      "--objective=irrelevant",
    )
    assertNull(perToolHelpToolName(parseResult))
  }

  @Test
  fun `--help on an unrelated subcommand is not diverted`() {
    // We only want to intercept the `tool` subcommand. `snapshot --help` etc. must keep
    // showing their normal picocli usage banner.
    val parseResult = rootCommandLine().parseArgs("snapshot", "--help")
    assertNull(perToolHelpToolName(parseResult))
  }

  @Test
  fun `--help at top level is not diverted`() {
    val parseResult = rootCommandLine().parseArgs("--help")
    assertNull(perToolHelpToolName(parseResult))
  }

  /**
   * Render-side pinning: the formatter is what `tool <name> --help` ultimately calls
   * after the routing decision fires, so this test feeds it the JSON shape the daemon
   * returns for a tool with a `ref` parameter and asserts the rendered output carries
   * that parameter's name and description — i.e., the user sees what `ref` means
   * instead of the wrapper's `-d`/`-o`/`--target` flags.
   */
  @Test
  fun `renderToolNameLines includes ref arg name and description for a clicked tool`() {
    val daemonResponse = """
      {
        "tool": {
          "name": "web_click",
          "description": "Click on a web element identified by a ref from snapshot.",
          "requiredParameters": [],
          "optionalParameters": [
            {
              "name": "ref",
              "type": "STRING",
              "description": "Element ID from snapshot output."
            },
            {
              "name": "reasoning",
              "type": "STRING",
              "description": "Why this click is being performed."
            }
          ]
        },
        "foundInCategories": ["web"],
        "foundInTargets": []
      }
    """.trimIndent()

    val json = Json.parseToJsonElement(daemonResponse).jsonObject
    val rendered = ToolboxFormatter.renderToolNameLines(json)
    assertTrue(
      rendered is ToolboxFormatter.ToolNameRender.Lines,
      "well-formed daemon response must produce Lines, got: $rendered",
    )
    val output = rendered.lines.joinToString("\n")
    assertTrue("web_click" in output, "tool name should appear in rendered help: $output")
    assertTrue(
      "Click on a web element" in output,
      "tool description should appear in rendered help: $output",
    )
    assertTrue(
      "ref (STRING, optional)" in output,
      "ref parameter row should appear in rendered help: $output",
    )
    assertTrue(
      "Element ID from snapshot output." in output,
      "ref parameter description should appear in rendered help: $output",
    )
  }

  @Test
  fun `renderToolNameLines surfaces an Error when daemon reports tool not found`() {
    val daemonResponse = """{"error": "Tool 'no_such_tool' not found"}"""
    val json = Json.parseToJsonElement(daemonResponse).jsonObject
    val rendered = ToolboxFormatter.renderToolNameLines(json)
    assertTrue(
      rendered is ToolboxFormatter.ToolNameRender.Error,
      "daemon error response should produce Error, got: $rendered",
    )
    assertEquals("Tool 'no_such_tool' not found", rendered.message)
  }

  @Test
  fun `renderToolNameLines emits required-param row and populated categories and targets`() {
    // Pins the branches the happy-path test above doesn't cover: required parameters
    // (rendered as `name (TYPE, required)`), and the trailing `Categories:` / `Targets:`
    // rows when their arrays carry content. Without this, a regression that broke the
    // required-param formatter, or that dropped the trailing rows, would be invisible
    // to the suite.
    val daemonResponse = """
      {
        "tool": {
          "name": "session_save",
          "description": "Persist the current CLI session.",
          "requiredParameters": [
            {"name": "title", "type": "STRING", "description": "Human-readable session title."}
          ],
          "optionalParameters": []
        },
        "foundInCategories": ["session", "trail"],
        "foundInTargets": ["default", "sample_app"]
      }
    """.trimIndent()

    val json = Json.parseToJsonElement(daemonResponse).jsonObject
    val rendered = ToolboxFormatter.renderToolNameLines(json)
    assertTrue(rendered is ToolboxFormatter.ToolNameRender.Lines)
    val output = rendered.lines.joinToString("\n")
    assertTrue(
      "title (STRING, required): Human-readable session title." in output,
      "required parameter row should appear in rendered help: $output",
    )
    assertTrue(
      "Categories: session, trail" in output,
      "populated foundInCategories should render as a Categories: row: $output",
    )
    assertTrue(
      "Targets: default, sample_app" in output,
      "populated foundInTargets should render as a Targets: row: $output",
    )
  }

  @Test
  fun `renderToolNameLines tolerates malformed entries in foundInCategories and foundInTargets`() {
    // Sibling parsers in ToolboxFormatter (parseTargetSummariesJson) defensively drop
    // null / non-primitive entries — the categories/targets render must do the same so a
    // single bad row from the daemon doesn't crash help rendering with a ClassCastException.
    val daemonResponse = """
      {
        "tool": {
          "name": "tap",
          "description": "Tap an element.",
          "requiredParameters": [],
          "optionalParameters": []
        },
        "foundInCategories": ["interaction", null, {"nested": "bad"}, "core"],
        "foundInTargets": [null, "default"]
      }
    """.trimIndent()

    val json = Json.parseToJsonElement(daemonResponse).jsonObject
    val rendered = ToolboxFormatter.renderToolNameLines(json)
    assertTrue(rendered is ToolboxFormatter.ToolNameRender.Lines)
    val output = rendered.lines.joinToString("\n")
    assertTrue(
      "Categories: interaction, core" in output,
      "malformed category entries should be dropped, keeping the good ones: $output",
    )
    assertTrue(
      "Targets: default" in output,
      "malformed target entries should be dropped, keeping the good ones: $output",
    )
  }

  @Test
  fun `renderToolNameLines renders cleanly for a tool with no parameters`() {
    // A scripted tool that takes no inputs (e.g. `web_snapshot`) must still render
    // — no orphan "required: " row, no missing description, and no exception.
    val daemonResponse = """
      {
        "tool": {
          "name": "web_snapshot",
          "description": "Capture the current web view."
        }
      }
    """.trimIndent()

    val json = Json.parseToJsonElement(daemonResponse).jsonObject
    val rendered = ToolboxFormatter.renderToolNameLines(json)
    assertTrue(rendered is ToolboxFormatter.ToolNameRender.Lines)
    val output = rendered.lines.joinToString("\n")
    assertTrue("web_snapshot" in output)
    assertTrue("Capture the current web view." in output)
    assertTrue(
      "required" !in output && "optional" !in output,
      "no parameters should mean no parameter rows: $output",
    )
  }

  @Test
  fun `tool --help with no tool name renders the wrapper banner via default strategy`() {
    // Pins the fall-through: when our IExecutionStrategy returns null, picocli's RunLast
    // must run and print the standard wrapper-options banner for the `tool` subcommand.
    // Without this end-to-end check, a future change to installPerToolHelpExecutionStrategy
    // could silently swallow this case and only the routing predicate test would still
    // pass (returning null is necessary but not sufficient — the default strategy must
    // also run and emit help).
    val stdoutBuffer = java.io.ByteArrayOutputStream()
    val capturedOut = java.io.PrintStream(stdoutBuffer, true, Charsets.UTF_8)

    val commandLine = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked during --help") },
        configProvider = { error("configProvider must not be invoked during --help") },
      ),
    )
      .setCaseInsensitiveEnumValuesAllowed(true)
      .setOut(java.io.PrintWriter(capturedOut, true))
      .also { installPerToolHelpExecutionStrategy(it) }

    val exitCode = commandLine.execute("tool", "--help")
    val stdout = stdoutBuffer.toString(Charsets.UTF_8)

    assertEquals(0, exitCode, "bare tool --help should exit 0 via RunLast.printHelpIfRequested")
    assertTrue(
      "Usage: trailblaze tool" in stdout,
      "default strategy should print the wrapper banner for bare tool --help: $stdout",
    )
    assertTrue(
      "<toolName>" in stdout || "tool name" in stdout.lowercase(),
      "wrapper banner should mention the toolName positional: $stdout",
    )
  }

  @Test
  fun `blank tool name still falls through to the wrapper banner`() {
    // Defensive guard from perToolHelpToolName — an empty positional must not divert
    // to the daemon. Pins behavior at the predicate level rather than execute() so the
    // test stays free of daemon dependencies.
    val parseResult = rootCommandLine().parseArgs("tool", "", "--help")
    assertNull(
      perToolHelpToolName(parseResult),
      "blank tool name must not route — would hit the daemon with name=\"\" and surface a confusing error",
    )
  }
}
