package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.mcp.newtools.ToolDiscoveryToolSet
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runQuiet

/**
 * Renders per-tool help for `trailblaze tool <name> --help` — the discoverable form
 * of `trailblaze toolbox --name <name>`. Every path funnels through the same
 * [ToolboxFormatter.renderToolNameLines] formatter so their output stays in lock-step.
 *
 * Wiring lives in [TrailblazeCli]: a custom [picocli.CommandLine.IExecutionStrategy]
 * intercepts a `tool <name> --help` invocation before picocli's default
 * `printHelpIfRequested` runs and routes it here. When `--help` is passed without a
 * `<name>` positional, the default strategy is allowed to fall through and render
 * the wrapper's standard options banner as before.
 *
 * ## Why help answers itself
 *
 * Asking a question about a tool used to start a background service to answer it. Help went
 * straight to [cliWithDaemon], which auto-starts the daemon, so `trailblaze tool tap --help` paid
 * a multi-second daemon bootstrap on a cold machine — and any daemon trouble turned a
 * documentation request into a failure with no help printed and a non-zero exit, while every
 * other subcommand's `--help` is instant and cannot fail. A typo'd name additionally exited 2
 * (INFRA, "retry this") rather than 3 (MISUSE, "fix your input"); see [renderViaDaemon].
 *
 * The daemon was never the only place that knows the answer. It reads the tool catalogue out of
 * the same classpath this process is already running, so [describeLocally] runs the daemon's own
 * [ToolDiscoveryToolSet] in-process and gets an identical response for anything on the classpath
 * — which is every built-in tool.
 *
 * The daemon is still asked for a name no built-in toolset offers. That is the one case it
 * genuinely knows more: a workspace that ships its own `*.tool.yaml` overlays tools this process
 * never scanned — and can offer a class-backed tool the built-in toolsets do not — and the daemon
 * holds the merged registry ([ToolCommand.LocalToolNameRegistry] documents the same gap for the
 * typo check). Only that case can still boot a daemon, and only that case can still fail.
 *
 * What the local answer leaves out is the connected device and target: no `Targets:` line for a
 * target-specific tool, and no driver-specific filtering. That is the right trade for help, which
 * describes a tool rather than this moment's device — and the previous behaviour was not
 * device-independent either, it just varied silently with whatever was plugged in.
 */
internal object ToolHelpRenderer {

  /**
   * Resolve [toolName] and emit its per-tool help to `Console`. Returns the CLI exit code.
   *
   * Local catalogue first, daemon only for a name no built-in toolset offers.
   */
  fun renderHelp(toolName: String): Int {
    describeLocally(toolName)?.let { json -> return render(json) }
    return renderViaDaemon(toolName)
  }

  /**
   * The daemon's own tool-discovery response for [toolName], computed in this process, or null
   * when no built-in toolset offers the tool and only the daemon can say whether a workspace does.
   *
   * Runs the real [ToolDiscoveryToolSet] rather than a second reimplementation of it, so a change
   * to how tools are described cannot land on one of the two paths and not the other. The target
   * and driver providers are empty because there is no device here; a tool's identity, parameters
   * and category do not depend on one.
   *
   * Never throws: a classpath the tool scan chokes on is a reason to ask the daemon, not a reason
   * to fail a `--help`.
   *
   * `internal` so a test can pin that help is answerable with no daemon and no network at all —
   * the whole point of this path, and not observable from [renderHelp]'s exit code.
   */
  internal fun describeLocally(toolName: String): JsonObject? {
    val response = try {
      // Quiet for the scan only: building the catalogue logs its config layering and serializer
      // discovery, which is diagnostic noise above a help screen. Scoped with `runQuiet` rather
      // than a bare `enableQuietMode()`, which would silence the rest of the process — including
      // the daemon-bootstrap progress [renderViaDaemon] prints on the one path that is still slow,
      // leaving it looking hung. `runQuiet` restores the prior state in a `finally`, so the
      // `catch` below cannot leak quiet mode either.
      Console.runQuiet {
        runBlocking {
          ToolDiscoveryToolSet(
            sessionContext = null,
            allTargetAppsProvider = { emptySet() },
            currentTargetProvider = { null },
            currentDriverTypeProvider = { null },
          ).toolbox(name = toolName)
        }
      }
    } catch (_: Exception) {
      return null
    }
    val json = try {
      Json.parseToJsonElement(response).jsonObject
    } catch (_: Exception) {
      return null
    }
    // An `error` here means "not in the classpath catalogue", which is not the same as "no such
    // tool" — a workspace tool lives only in the daemon's merged registry. Hand those on rather
    // than denying a name that the daemon would have described.
    //
    // An `availability` note is the same gap wearing a different shape. Discovery emits it for a
    // tool it knows but no toolset offers — and with no targets loaded here, "no toolset" means
    // "no built-in toolset". A workspace trailmap can offer a class-backed tool by name, so the
    // daemon may answer `Targets: <trailmap>` where this process would have printed "'trailblaze
    // tool' refuses to run it". Only the daemon can make that claim; defer it.
    return json.takeIf { it["tool"] != null && it["availability"] == null }
  }

  /** The original path: ask the daemon, auto-starting it if it is not already running. */
  private fun renderViaDaemon(toolName: String): Int = cliWithDaemon(verbose = false) { client ->
    val result = client.callTool("toolbox", mapOf("name" to toolName))
    renderResponse(content = result.content, isError = result.isError)
  }

  /**
   * Emit the help for one raw daemon response and return the exit code it implies.
   *
   * Split from the transport so the exit-code classification — the part that was wrong — is
   * testable without a daemon.
   */
  internal fun renderResponse(content: String, isError: Boolean): Int {
    // Parse BEFORE consulting `isError`. The MCP layer sets that flag from a shape check on the
    // response, and one of the shapes it keys on is a JSON `error` field — which is exactly how
    // tool discovery reports a name it does not recognize. So a plain typo arrived flagged as a
    // transport failure, took the INFRA_FAILED branch, and left the MISUSE branch below
    // unreachable: `trailblaze tool tapOnn --help` exited 2, telling a shell to retry a command
    // that will never succeed. A response that parses as a discovery result IS the daemon
    // answering, whatever the flag says.
    val json = try {
      Json.parseToJsonElement(content).jsonObject
    } catch (_: Exception) {
      null
    }
    if (json != null) return render(json)

    if (isError) {
      // Not a discovery response at all — the transport or the protocol failed. This is the one
      // branch INFRA_FAILED belongs on, and the only way a `--help` can still fail.
      Console.error("Error: ${extractErrorMessage(content)}")
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    // Daemon returned non-JSON without flagging it — surface the raw content so the user has
    // *something* to act on rather than silently swallowing it.
    Console.info(content)
    return TrailblazeExitCode.SUCCESS.code
  }

  /** Format one tool-discovery response and pick the exit code it implies. */
  private fun render(json: JsonObject): Int =
    when (val rendered = ToolboxFormatter.renderToolNameLines(json)) {
      is ToolboxFormatter.ToolNameRender.Error -> {
        // A well-formed response that reports "tool not found" (or similar user-side input
        // error) is MISUSE per `TrailblazeExitCode` — the lookup worked, the user typed a bad
        // tool name. INFRA_FAILED is reserved for a broken transport, which is now reachable
        // only for a name the local catalogue could not resolve.
        Console.error(rendered.message)
        TrailblazeExitCode.MISUSE.code
      }
      is ToolboxFormatter.ToolNameRender.Lines -> {
        rendered.lines.forEach { Console.info(it) }
        TrailblazeExitCode.SUCCESS.code
      }
    }
}
