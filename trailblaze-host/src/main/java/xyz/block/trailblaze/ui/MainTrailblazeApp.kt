package xyz.block.trailblaze.ui

import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.trailrunner.ExternalAgentSupervisor
import xyz.block.trailblaze.util.Console
import kotlin.system.exitProcess

class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsRepo: LogsRepo,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
) {

  /**
   * Runs the daemon HTTP server in this process, blocking until it stops. Returns at once when
   * [daemonAlreadyRunning] and that daemon still answers.
   */
  fun runTrailblazeApp(
    deviceManager: TrailblazeDeviceManager,
    /**
     * Optional extra Ktor routes registered on the daemon alongside the built-in
     * ones. Lets a downstream desktop edition add its own daemon endpoints without
     * trailblaze-host — the lower layer — having to depend on them. OSS callers
     * leave this null.
     */
    extraDaemonRoutes: (io.ktor.server.routing.Routing.() -> Unit)? = null,
    /** Trail Runner route installed by [extraDaemonRoutes], or null when this app does not expose it. */
    trailRunnerPath: String? = null,
    /**
     * True when the daemon HTTP server for this port was already running when the caller checked.
     * When false, this process MUST win the port bind or exit: a failed bind means another
     * daemon owns the port.
     */
    daemonAlreadyRunning: Boolean = false,
  ) {
    // Defense-in-depth: TrailblazeCli.run already sets this for every known entry point. Without
    // it, the first AWT touch makes macOS activate the daemon and steal keyboard focus.
    System.setProperty(TrailblazeDesktopUtil.AWT_AGENT_APP_PROPERTY, "true")

    // Pins WHEN logsRepo initializes: here, on the daemon's main thread, before any HTTP route can
    // trigger it under load — see the `logsRepo` comment in the desktop app configs.
    logsRepo.logsDir

    val trailblazeMcpServer = trailblazeMcpServerProvider()
    trailblazeMcpServer.additionalActiveRunSummaries = ExternalAgentSupervisor::activeCompanionSummaries

    // Shared session state for the HTTP device API — tracks live DeviceScreenStream instances
    // established via ConnectToDeviceRequest (and published by Trail Runner's recorder) so
    // screen-poll, interaction, and live-stream handlers can reach them. Owned by the device
    // manager (one instance per daemon lifetime; the map is thread-safe internally) so the
    // recorder and the /rpc device viewer share the same registry.
    val hostDeviceSessionManager = deviceManager.hostDeviceSessionManager

    Console.log("Starting Trailblaze daemon...")
    val portManager = trailblazeSavedSettingsRepo.portManager
    // Advertise the live daemon URL to subprocess MCP plumbing BEFORE the server starts
    // (in `wait = true` mode the call below blocks forever — anything after it never runs).
    // Without this, `JsScriptingCallbackBaseUrl.get()` returns null,
    // `McpSubprocessRuntimeLauncher` skips the callback-context wiring, and scripted tools
    // see `_meta.trailblaze`-less envelopes → `ctx === undefined` in handlers.
    xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.set(portManager.serverUrl)
    // Re-verify the attach target is still alive: the flag was computed at CLI startup, and a
    // daemon that died in between means this process should take over the port instead.
    if (daemonAlreadyRunning && DaemonClient(port = portManager.httpPort).use { it.isRunningBlocking() }) {
      Console.info("Trailblaze daemon is already running on port ${portManager.httpPort} — nothing to do.")
      return
    }
    // Pin the resolved ports before binding (in `wait = true` mode the call below never
    // returns): from here on the getters mean "what this daemon is serving on", so a later
    // settings patch cannot redirect `adb reverse` and the on-device log endpoint to a port
    // this process never bound.
    portManager.pinBoundPorts(httpPort = portManager.httpPort, httpsPort = portManager.httpsPort)
    try {
      trailblazeMcpServer.startStreamableHttpMcpServer(
        port = portManager.httpPort,
        httpsPort = portManager.httpsPort,
        wait = true,
        trailRunnerPath = trailRunnerPath,
        additionalRouteRegistration = {
          allRouteRegistrations(trailblazeSavedSettingsRepo, deviceManager, hostDeviceSessionManager).invoke(this)
          extraDaemonRoutes?.invoke(this)
        },
      )
    } catch (e: Exception) {
      exitOnPortBindFailure(port = portManager.httpPort, cause = e)
    }
  }
}

/**
 * Terminal handler for a daemon-port bind failure — never returns.
 *
 * The bind is the atomic arbiter for "one daemon per port". Waits briefly for
 * the rival to answer (it may have bound the socket before its routes are up), then exits: cleanly
 * as a duplicate when a rival daemon is healthy, or as an infra failure when nothing owns the port.
 * A port the configuration made unusable skips the wait entirely — [classifyPortBindFailure] owns
 * that decision so it can be unit-tested.
 */
private fun exitOnPortBindFailure(port: Int, cause: Exception): Nothing {
  val causeText = cause.message ?: cause.toString()
  DaemonClient(port = port).use { daemon ->
    // The probe is a lambda so the classifier decides whether it happens at all: a configuration
    // error must never consult the port (see classifyPortBindFailure).
    when (
      classifyPortBindFailure(
        cause = cause,
        probeForRivalDaemon = { daemon.waitForDaemon(maxWaitMs = RIVAL_DAEMON_WAIT_MS) },
      )
    ) {
      PortBindFailureAction.ExitAsConfigError -> {
        Console.error(causeText)
        exitProcess(TrailblazeExitCode.INFRA_FAILED.code)
      }
      PortBindFailureAction.ExitAsDuplicate -> {
        // info (not log): the exit reason must survive CLI quiet mode — this is the only
        // record of why this instance vanished.
        Console.info(
          "Trailblaze is already running on port $port — exiting this duplicate instance ($causeText).",
        )
        exitProcess(0)
      }
      PortBindFailureAction.ExitAsStartupFailure -> {
        Console.error("Failed to start the Trailblaze server on port $port: $causeText")
        exitProcess(TrailblazeExitCode.INFRA_FAILED.code)
      }
    }
  }
  throw IllegalStateException("unreachable", cause)
}

/**
 * Combines all additional Ktor route registrations for the daemon:
 *  - Waypoint graph endpoints (`/waypoints/graph`, `/waypoints/graph.json`)
 *  - Device API RPC endpoints (`/rpc/GetConnectedDevicesRequest`, etc.)
 *  - Device reachability health signal (`/health/device`)
 */
private fun allRouteRegistrations(
  settingsRepo: TrailblazeSettingsRepo,
  deviceManager: TrailblazeDeviceManager,
  sessionManager: xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager,
): (io.ktor.server.routing.Routing.() -> Unit) = {
  xyz.block.trailblaze.graph.WaypointGraphEndpoint.register(
    routing = this,
    defaultRootProvider = {
      val appConfig = settingsRepo.serverStateFlow.value.appConfig
      java.io.File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(appConfig))
    },
  )
  xyz.block.trailblaze.host.recording.rpc.DeviceApiEndpoint.register(
    routing = this,
    deviceManager = deviceManager,
    sessionManager = sessionManager,
  )
  xyz.block.trailblaze.host.recording.rpc.DevicesPageEndpoint.register(routing = this)
  xyz.block.trailblaze.health.DeviceHealthEndpoint.register(routing = this)
}
