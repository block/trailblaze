package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import picocli.CommandLine
import xyz.block.trailblaze.util.Console
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

  /**
   * Guarantees the cleanup that the quiet-mode assertion below cannot do for itself: an assertion
   * that fires has already returned, so a regressed `renderHelp` would otherwise leave the flag set
   * and silence every test scheduled behind this one — the cascade this class is watching for.
   */
  @Rule
  @JvmField
  val quietMode = QuietModeRule()

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
  fun `renderToolNameLines shows how to pass the arguments, without the agent-only reasoning`() {
    val daemonResponse = """
      {
        "tool": {
          "name": "tap",
          "description": "Tap an element by its ref.",
          "requiredParameters": [{"name": "ref", "type": "STRING", "description": "The element ref"}],
          "optionalParameters": [
            {"name": "longPress", "type": "BOOLEAN", "description": "Long press instead"},
            {"name": "reasoning", "type": "STRING", "description": ""}
          ]
        }
      }
    """.trimIndent()

    val rendered = ToolboxFormatter.renderToolNameLines(Json.parseToJsonElement(daemonResponse).jsonObject)
    val lines = (rendered as ToolboxFormatter.ToolNameRender.Lines).lines
    assertTrue(
      "  Run: trailblaze tool tap ref=<ref> [longPress=<longPress>] -s \"<what this step does>\"" in lines,
      "help must show a copy-pasteable key=value invocation: $lines",
    )
  }

  @Test
  fun `renderToolNameLines warns above the Run line that nothing offers the tool`() {
    // `tapOn` is real, is what the recorder writes, and is in no toolset — so help renders a
    // command line that will be refused. The warning has to land before the reader reaches it.
    val daemonResponse = """
      {
        "tool": {
          "name": "tapOn",
          "description": "Tap the element resolved by a node selector.",
          "requiredParameters": [{"name": "selector", "type": "OBJECT", "description": "What to tap"}],
          "optionalParameters": []
        },
        "availability": "Not offered by any toolset, so no agent will choose it and 'trailblaze tool' refuses to run it. The name is still valid in a trail — a recording that uses it replays."
      }
    """.trimIndent()

    val lines = (
      ToolboxFormatter.renderToolNameLines(Json.parseToJsonElement(daemonResponse).jsonObject)
        as ToolboxFormatter.ToolNameRender.Lines
      ).lines

    val noteIndex = lines.indexOfFirst { "Not offered by any toolset" in it }
    val runIndex = lines.indexOfFirst { it.startsWith("  Run:") }
    assertTrue(noteIndex >= 0, "availability note must be rendered: $lines")
    assertTrue(runIndex >= 0, "the Run line is still rendered for a tool nothing offers: $lines")
    assertTrue(
      noteIndex < runIndex,
      "the note must be read before the command line it disclaims (note=$noteIndex, run=$runIndex): $lines",
    )
  }

  @Test
  fun `renderToolNameLines adds no note for an ordinary offered tool`() {
    val daemonResponse = """
      {
        "tool": {"name": "tap", "description": "Tap an element.", "requiredParameters": [], "optionalParameters": []},
        "foundInCategories": ["Core Interaction"]
      }
    """.trimIndent()

    val lines = (
      ToolboxFormatter.renderToolNameLines(Json.parseToJsonElement(daemonResponse).jsonObject)
        as ToolboxFormatter.ToolNameRender.Lines
      ).lines

    // Absent, not empty — an always-present "Note:" row trains readers to skip it.
    assertTrue(lines.none { it.startsWith("  Note:") }, "offered tool must render no note: $lines")
  }

  @Test
  fun `renderToolNameLines survives an availability field of the wrong shape`() {
    // Same defensive contract as the sibling `Categories:` / `Targets:` rows: a malformed
    // daemon payload drops the row rather than crashing help.
    val daemonResponse = """
      {
        "tool": {"name": "tap", "description": "Tap an element.", "requiredParameters": [], "optionalParameters": []},
        "availability": {"unexpected": "object"}
      }
    """.trimIndent()

    val rendered = ToolboxFormatter.renderToolNameLines(Json.parseToJsonElement(daemonResponse).jsonObject)

    assertTrue(rendered is ToolboxFormatter.ToolNameRender.Lines, "must still render help: $rendered")
    assertTrue(rendered.lines.none { it.startsWith("  Note:") }, "bad row must be dropped: ${rendered.lines}")
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

  // -- Help answers itself, and a bad name is the user's mistake, not an outage ----------

  @Test
  fun `a built-in tool is described with no daemon and no network`() {
    // The whole point of the local path: asking what a tool does must not start a background
    // service to find out. This runs in a plain test JVM — there is no daemon to fall back to,
    // so an answer here is proof the classpath catalogue is enough.
    val json = captureConsole { ToolHelpRenderer.describeLocally("tap") }.result

    assertNotNull(json, "`tap` must be describable in-process")
    assertEquals("tap", json["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `renderHelp answers a built-in tool end to end and leaves the console audible`() {
    // The composition, not the halves. Every other test here drives `describeLocally` or
    // `renderResponse` directly, so a `renderHelp` that wired them together wrongly — or that
    // skipped the local path and went straight to the daemon — would still pass all of them.
    val captured = captureConsole { ToolHelpRenderer.renderHelp("tap") }

    assertEquals(TrailblazeExitCode.SUCCESS.code, captured.result)
    assertTrue("tap" in captured.out, captured.out)
    // The catalogue scan runs quiet, and has to hand the console back. Leaving quiet mode on
    // would silence the daemon-bootstrap progress that this same function's fallback prints,
    // making the one remaining slow path the one that looks hung.
    //
    // Read straight off the global flag rather than through a saved-and-restored copy. This test
    // used to save and restore it, because CLI commands elsewhere in the suite left quiet mode on
    // and the assertion was green alone and red in a full run. Those commands restore it now, so
    // the flag at this point is `renderHelp`'s own doing and nobody else's.
    //
    // Restoring is QuietModeRule's job, not this assertion's — see the rule on this class.
    assertFalse(Console.isQuietMode(), "the local scan must not leave the rest of the process silenced")
  }

  @Test
  fun `a name the classpath catalogue lacks defers rather than denying`() {
    // A workspace that ships its own `*.tool.yaml` overlays tools this process never scanned, and
    // only the daemon holds the merged registry. Returning null is what sends the lookup there;
    // answering "not found" locally would deny a name the daemon would have described.
    val json = captureConsole { ToolHelpRenderer.describeLocally("definitely_not_a_tool") }.result

    assertNull(json, "an unknown name must defer to the daemon, not resolve to a local verdict")
  }

  @Test
  fun `a tool no built-in toolset offers defers rather than calling it unrunnable`() {
    // `tapOn` is on the classpath but in no built-in toolset, so in-process discovery knows the
    // tool and reports it as offered by nothing. This process has no workspace loaded, and a
    // workspace trailmap can offer a class-backed tool by name — so "offered by nothing" here is
    // only "offered by no built-in toolset", while the note help would print says 'trailblaze
    // tool' refuses to run it. That is a claim about the workspace, and only the daemon has one.
    val json = captureConsole { ToolHelpRenderer.describeLocally("tapOn") }.result

    assertNull(json, "a not-offered verdict must come from the daemon, which knows the workspace")
  }

  @Test
  fun `a tool name the daemon does not know is MISUSE, not an infrastructure failure`() {
    // The MCP layer flags a response as an error when it carries a JSON `error` field — which is
    // exactly how tool discovery reports an unrecognized name. Reading that flag first classified
    // every typo as INFRA_FAILED, so `trailblaze tool tapOnn --help` exited 2 and told a shell to
    // retry a command that can never succeed. `isError = true` is passed deliberately: it is what
    // the daemon really sends for this case.
    val notFound = """{"error":"Tool 'tapOnn' not found. Did you mean 'tap'?"}"""

    val captured = captureConsole { ToolHelpRenderer.renderResponse(notFound, isError = true) }

    assertEquals(TrailblazeExitCode.MISUSE.code, captured.result)
    assertTrue("not found" in captured.err, "the daemon's own message must reach the user: ${captured.err}")
    // The `Error: ` prefix belonged to the transport-failure branch this no longer takes.
    assertTrue("Error: " !in captured.err, "a typo was dressed up as a system error: ${captured.err}")
  }

  @Test
  fun `a transport failure is still an infrastructure failure`() {
    // The one branch INFRA_FAILED belongs on, and the only way a `--help` can still fail: a
    // response that is not a discovery result at all. Without this the fix above would have
    // reclassified a genuinely broken daemon as the user's typo.
    val captured = captureConsole {
      ToolHelpRenderer.renderResponse("connection reset by peer", isError = true)
    }

    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, captured.result)
  }

  @Test
  fun `a rendered tool exits zero`() {
    val found = """{"tool":{"name":"tap","description":"Tap an element.","requiredParameters":[]}}"""

    val captured = captureConsole { ToolHelpRenderer.renderResponse(found, isError = false) }

    assertEquals(TrailblazeExitCode.SUCCESS.code, captured.result)
    assertTrue("tap" in captured.out, captured.out)
  }
}
