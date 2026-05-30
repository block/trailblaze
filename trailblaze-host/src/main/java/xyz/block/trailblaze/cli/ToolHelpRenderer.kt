package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.util.Console

/**
 * Renders per-tool help for `trailblaze tool <name> --help` — the discoverable form
 * of `trailblaze toolbox --name <name>`. Both paths funnel through the same daemon
 * `toolbox` call and the same [ToolboxFormatter.renderToolNameLines] formatter so
 * their output stays in lock-step.
 *
 * Wiring lives in [TrailblazeCli]: a custom [picocli.CommandLine.IExecutionStrategy]
 * intercepts a `tool <name> --help` invocation before picocli's default
 * `printHelpIfRequested` runs and routes it here. When `--help` is passed without a
 * `<name>` positional, the default strategy is allowed to fall through and render
 * the wrapper's standard options banner as before.
 *
 * ## Cold-start caveat
 *
 * This path goes through [cliWithDaemon], which auto-starts the daemon if it isn't
 * already running. On a cold machine, `trailblaze tool tap --help` will pay the
 * full daemon-bootstrap cost (multi-second JVM + workspace compile) before the
 * help renders — every other CLI subcommand's `--help` is synchronous and instant.
 * The tradeoff is intentional: the daemon owns the tool registry (target-aware
 * descriptors, scripted-tool schemas, role tags), so static help would have to
 * either drop most of that content or duplicate the registry. Subsequent calls
 * reuse the warm daemon and feel instant. If a future change reduces what
 * per-tool help needs from the registry, this can be revisited.
 */
internal object ToolHelpRenderer {

  /**
   * Resolve [toolName] against the daemon's `toolbox` tool and emit the per-tool help
   * to `Console`. Returns the CLI exit code.
   *
   * Mirrors the daemon-call shape of `ToolboxCommand` so the daemon is auto-started
   * on first use just like `trailblaze toolbox --name <tool>` would.
   */
  fun renderHelp(toolName: String): Int = cliWithDaemon(verbose = false) { client ->
    val result = client.callTool("toolbox", mapOf("name" to toolName))
    if (result.isError) {
      Console.error("Error: ${extractErrorMessage(result.content)}")
      return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
    }

    val json = try {
      Json.parseToJsonElement(result.content).jsonObject
    } catch (_: Exception) {
      // Daemon returned non-JSON — surface the raw content so the user has *something*
      // to act on rather than silently swallowing it.
      Console.info(result.content)
      return@cliWithDaemon TrailblazeExitCode.SUCCESS.code
    }

    when (val rendered = ToolboxFormatter.renderToolNameLines(json)) {
      is ToolboxFormatter.ToolNameRender.Error -> {
        // A well-formed daemon response that reports "tool not found" (or similar
        // user-side input error) is MISUSE per `TrailblazeExitCode` — the daemon and
        // transport are healthy, the user typed a bad tool name. INFRA_FAILED is
        // reserved for the `result.isError` branch above (daemon unreachable, MCP
        // protocol error, etc.). Distinguishing these lets shells / CI retry one and
        // not the other.
        Console.error(rendered.message)
        TrailblazeExitCode.MISUSE.code
      }
      is ToolboxFormatter.ToolNameRender.Lines -> {
        rendered.lines.forEach { Console.info(it) }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}
