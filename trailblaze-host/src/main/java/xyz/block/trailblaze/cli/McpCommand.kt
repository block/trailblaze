package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.canRunDesktopGui
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Start the MCP server with a specified transport.
 *
 * By default, starts an STDIO server for MCP client integrations
 * (e.g., Claude Code, Claude Desktop, Firebender, Goose) with a menu bar
 * tray icon so you can open the Trailblaze desktop app and view logs.
 * Use `--http` to start a standalone Streamable HTTP server instead.
 *
 * Examples:
 *   trailblaze mcp                  - Start STDIO MCP server with tray icon
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
        "Bypasses the Trailblaze daemon and runs everything in a single process. " +
        "Use this for environments where the HTTP daemon cannot run."
    ],
  )
  var direct: Boolean = false

  @Option(
    names = ["--tool-profile"],
    description = ["Tool profile: FULL or MINIMAL (only device/blaze/verify/ask/trail). Defaults to MINIMAL for STDIO, FULL for HTTP."],
  )
  var toolProfile: String? = null

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
      return CommandLine.ExitCode.OK
    }

    // Resolve tool profile: env var > CLI flag > transport-based default
    // STDIO defaults to MINIMAL (external MCP clients), HTTP defaults to FULL (internal use)
    val transportDefault = if (http) McpToolProfile.FULL else McpToolProfile.MINIMAL
    val allowedProfiles = McpToolProfile.entries.joinToString(", ") { it.name }
    val resolvedProfile = System.getenv("TRAILBLAZE_TOOL_PROFILE")?.let { env ->
      try { McpToolProfile.valueOf(env.uppercase()) } catch (_: IllegalArgumentException) {
        Console.error("Invalid TRAILBLAZE_TOOL_PROFILE '$env'. Allowed: $allowedProfiles")
        return CommandLine.ExitCode.USAGE
      }
    } ?: toolProfile?.let { flag ->
      try { McpToolProfile.valueOf(flag.uppercase()) } catch (_: IllegalArgumentException) {
        Console.error("Invalid --tool-profile '$flag'. Allowed: $allowedProfiles")
        return CommandLine.ExitCode.USAGE
      }
    } ?: transportDefault

    // OSS CLI always starts in MCP_CLIENT_AS_AGENT — the external client is the agent.
    // TRAILBLAZE_AS_AGENT is the server default for deployments with a configured LLM.
    val resolvedMode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT

    if (http) {
      // Streamable HTTP transport (explicit opt-in)
      System.setProperty("java.awt.headless", "true")

      val app = parent.appProvider()
      app.trailblazeMcpServer.defaultToolProfile = resolvedProfile
      app.trailblazeMcpServer.defaultMode = resolvedMode
      val port = parent.getEffectivePort()
      val httpsPort = parent.getEffectiveHttpsPort()
      Console.log("Trailblaze MCP server starting with HTTP transport on port $port (profile=${resolvedProfile.name})...")

      // Apply port overrides so the server knows the effective ports
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = port, httpsPort = httpsPort)
      }

      app.trailblazeMcpServer.startStreamableHttpMcpServer(
        port = port,
        httpsPort = httpsPort,
        wait = true,
      )
    } else if (direct) {
      // Direct STDIO transport (opt-in via --direct/--no-daemon) — runs an in-process
      // MCP server without the HTTP daemon proxy. Use for environments where the
      // daemon cannot run.
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

      Console.log("Trailblaze MCP server starting with direct STDIO transport (profile=${resolvedProfile.name})...")

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

      if (ownsDaemon && canRunDesktopGui()) {
        // This process owns the daemon — show tray icon with window hidden.
        // The Compose Desktop event loop must run on the main thread (macOS AppKit
        // requirement), so we launch the STDIO server on a background thread.
        // NON-daemon so the JVM stays alive even if the desktop app exits early.
        val stdioThread = Thread {
          runBlocking {
            app.trailblazeMcpServer.startStdioMcpServer(
              stdout = stdoutForTransport,
              toolProfile = resolvedProfile,
            )
          }
          // Client disconnected — request graceful shutdown via the Compose event loop.
          // onShutdownRequest is wired to exitApplication() inside the Compose app block,
          // which cleanly tears down the UI, runs shutdown hooks, and exits the process.
          app.trailblazeMcpServer.onShutdownRequest?.invoke() ?: exitProcess(0)
        }
        stdioThread.isDaemon = false // Must be non-daemon: if the desktop app crashes or
        // exits early, the JVM must stay alive to keep the STDIO pipe open for MCP.
        stdioThread.name = "mcp-stdio"
        stdioThread.start()

        // Start the desktop app on the main thread (tray icon visible, window hidden).
        // The HTTP server is already running via ensureServerRunning(), so
        // startTrailblazeDesktopApp will detect the daemon and skip starting another.
        // Wrapped in try-catch: if the desktop app crashes, the STDIO server must keep
        // running (the non-daemon thread above keeps the JVM alive).
        try {
          app.startTrailblazeDesktopApp(headless = true)
        } catch (e: Exception) {
          Console.error("[MCP] Desktop app exited with error: ${e.message}")
          Console.error("[MCP] STDIO MCP server continues running without tray icon.")
          // Block the main thread so the JVM doesn't exit.
          // The STDIO thread will call exitProcess(0) when the client disconnects.
          stdioThread.join()
        }
      } else {
        // Secondary STDIO client (daemon owned by another process), or no display.
        // Run STDIO server directly — no tray icon needed.
        if (!ownsDaemon) {
          Console.log("Daemon already running on port ${parent.getEffectivePort()} — running as headless STDIO client")
        }
        runBlocking {
          app.trailblazeMcpServer.startStdioMcpServer(
            stdout = stdoutForTransport,
            toolProfile = resolvedProfile,
          )
        }
      }
    } else {
      // Default: STDIO-to-HTTP proxy mode — lightweight proxy that forwards JSON-RPC
      // to the Trailblaze daemon. Reconnects transparently on daemon restarts.
      return McpProxy(port = parent.getEffectivePort()).run()
    }

    return CommandLine.ExitCode.OK
  }
}
