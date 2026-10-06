package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Start the MCP server with a specified transport.
 *
 * By default, starts an STDIO server for MCP client integrations
 * (e.g., Claude Code, Claude Desktop, Firebender, Goose).
 * Use `--http` to start a standalone Streamable HTTP server instead.
 *
 * Examples:
 *   trailblaze mcp                  - Start STDIO MCP server
 *   trailblaze mcp --http           - Start Streamable HTTP MCP server
 *   trailblaze mcp --http -p 8080   - Start HTTP MCP server on port 8080
 */
@Command(
  name = "mcp",
  mixinStandardHelpOptions = true,
  description = [
    "Start a Model Context Protocol (MCP) server for AI agent integration",
    "",
    "Exposes Trailblaze tools via the Model Context Protocol (MCP) so that AI",
    "coding agents can control devices.",
    "",
    "Quick setup:",
    "  Claude Code:  claude mcp add trailblaze -- trailblaze mcp",
    "  Cursor:       Add to .cursor/mcp.json with command 'trailblaze mcp'",
    "  Windsurf:     Add to MCP config with command 'trailblaze mcp'",
  ],
)
class McpCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--http"],
    description = ["Use Streamable HTTP transport instead of STDIO. Starts a standalone HTTP MCP server."]
  )
  var http: Boolean = false

  @Option(
    names = ["--direct", "--no-daemon"],
    description = [
      "Run as an in-process MCP server over STDIO instead of the default proxy mode. " +
        "Runs everything in a single process instead of proxying to a separate daemon process. " +
        "This is not a way around the HTTP port: the process still starts the daemon's HTTP " +
        "server itself when none is running."
    ],
  )
  var direct: Boolean = false

  @Option(
    names = ["-d", "--device"],
    description = [
      "Pin this MCP session to a device on startup (e.g. android, android/emulator-5554). " +
        "Defaults to whatever the launching terminal pinned via " +
        "`trailblaze device connect`, or `\$TRAILBLAZE_DEVICE` if set.",
    ],
  )
  var device: String? = null

  @Option(
    names = ["-t", "--target"],
    description = [
      "Pin this MCP session to a target app on startup (e.g. default, sampleapp). " +
        "Only meaningful with --device or \$TRAILBLAZE_DEVICE. " +
        "Defaults to \$TRAILBLAZE_TARGET.",
    ],
  )
  var target: String? = null

  override fun call(): Int {
    // If running in an interactive terminal (not piped by an AI agent), show setup
    // instructions instead of starting a raw STDIO server that would spew JSON-RPC.
    if (!http && !direct && System.console() != null) {
      Console.info("The MCP server is started by an AI agent, not run directly.")
      Console.info("")
      Console.info("Quick setup:")
      Console.info("  Claude Code:  claude mcp add trailblaze -- trailblaze mcp")
      Console.info("  Cursor:       Add to .cursor/mcp.json with command 'trailblaze mcp'")
      Console.info("  Windsurf:     Add to MCP config with command 'trailblaze mcp'")
      Console.info("")
      Console.info("For a standalone HTTP server:  trailblaze mcp --http")
      return TrailblazeExitCode.SUCCESS.code
    }

    // OSS CLI always starts in MCP_CLIENT_AS_AGENT — the external client is the agent.
    // TRAILBLAZE_AS_AGENT is the server default for deployments with a configured LLM.
    val resolvedMode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT

    if (http) {
      // Streamable HTTP transport (explicit opt-in)
      System.setProperty("java.awt.headless", "true")

      val app = parent.appProvider()
      app.trailblazeMcpServer.defaultMode = resolvedMode
      val port = parent.getEffectivePort()
      val httpsPort = parent.getEffectiveHttpsPort()
      Console.log("Trailblaze MCP server starting with HTTP transport on port $port...")

      // Apply port overrides so the server knows the effective ports
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = port, httpsPort = httpsPort)
      }

      app.trailblazeMcpServer.startStreamableHttpMcpServer(
        port = port,
        httpsPort = httpsPort,
        wait = true,
        additionalRouteRegistration = {
          xyz.block.trailblaze.health.DeviceHealthEndpoint.register(routing = this)
        },
      )
    } else if (direct) {
      // Direct STDIO transport (opt-in via --direct/--no-daemon) — runs the MCP server in this
      // process instead of proxying to a separate daemon process. It is not an escape from the
      // HTTP port: `ensureServerRunning()` below still starts the daemon's HTTP server here when
      // nothing else holds it, so a port that cannot be bound fails this mode too.
      //
      // Capture the current stdout BEFORE redirecting — DesktopLogFileWriter may have
      // already wrapped it with a tee (JSON-RPC goes to both stdout and log file).
      // After Console.useStdErr(), System.out is redirected to stderr, so we must
      // save the reference now for the STDIO transport.
      val stdoutForTransport = System.out

      // Redirect all console output to stderr BEFORE app initialization so that
      // settings loading, device scanning, and other startup output doesn't
      // contaminate stdout (which must be a clean JSON-RPC stream).
      Console.useStdErr()

      // Install file logging AFTER useStdErr so that Console.log output is saved
      // to ~/.trailblaze/desktop-logs/trailblaze.log. This is safe because System.out
      // is now stderr, so DesktopLogFileWriter tees stderr→file (not the STDIO pipe).
      DesktopLogFileWriter.install(httpPort = parent.getEffectivePort())

      Console.log("Trailblaze MCP server starting with direct STDIO transport...")

      val app = parent.appProvider()
      app.trailblazeMcpServer.defaultMode = resolvedMode

      // Apply port overrides before starting the daemon
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = parent.getEffectivePort(), httpsPort = parent.getEffectiveHttpsPort())
      }

      // Start the HTTP daemon for device log ingestion (if not already running).
      // Returns true if this process started the daemon (we own it),
      // false if another process already had it running.
      val ownsDaemon = app.ensureServerRunning()

      if (!ownsDaemon) {
        Console.log("Daemon already running on port ${parent.getEffectivePort()} — running as STDIO client")
      }
      runBlocking {
        app.trailblazeMcpServer.startStdioMcpServer(
          stdout = stdoutForTransport,
        )
      }
      // Client disconnected. A process that started the daemon's HTTP server would otherwise
      // keep serving it with no client attached, so exit and take the server with it.
      if (ownsDaemon) exitProcess(0)
    } else {
      // Default: STDIO-to-HTTP proxy mode — lightweight proxy that forwards JSON-RPC
      // to the Trailblaze daemon. Reconnects transparently on daemon restarts.
      //
      // Pre-bind a device + target on startup when the shell exported TRAILBLAZE_DEVICE
      // (typically via `eval $(trailblaze device connect ...)`) or the agent's MCP
      // server registration passed --device / --target explicitly. The proxy turns
      // these into synthetic `tools/call` requests injected after `initialize` so the
      // first request the LLM issues sees a device already bound — no need to teach
      // every agent to call the `device` tool first.
      // Normalize target the same way DeviceCommand / SessionCommand do
      // (lowercase + blank→null) so `mcp --target SampleApp` matches the
      // daemon's case-insensitive lookup. Keeps behavior consistent with
      // the comment on synthesizeTargetBindCall about caller-side normalization.
      // Falls through to TRAILBLAZE_TARGET when --target is omitted — symmetric
      // with TRAILBLAZE_DEVICE for [device], so an MCP-server registration that
      // inherits the shell's `eval $(trailblaze device connect ... --target X)`
      // env picks up the target without the user re-typing it in their MCP
      // config. Routed through [resolveCliTargetPin] (NOT the four-tier
      // [resolveCliTarget]) because workspace-config / built-in-default
      // targets are already the daemon-wide fallback — synthesizing a
      // `setSessionTargetForBoundDevice` for them would be redundant noise.
      return McpProxy(
        port = parent.getEffectivePort(),
        initialDeviceSpec = resolveCliDevice(device),
        initialTarget = resolveCliTargetPin(target),
      ).run()
    }

    return TrailblazeExitCode.SUCCESS.code
  }
}
