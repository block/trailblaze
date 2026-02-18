package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.ui.images.NetworkImageLoader

/**
 * Central Interface for The Trailblaze Desktop App
 */
abstract class TrailblazeDesktopApp(
  protected val desktopAppConfig: TrailblazeDesktopAppConfig,
) {
  abstract val desktopYamlRunner: DesktopYamlRunner

  abstract val trailblazeMcpServer: TrailblazeMcpServer

  abstract val deviceManager: TrailblazeDeviceManager

  abstract fun startTrailblazeDesktopApp(headless: Boolean = false)

  /** Shortcut to the port manager owned by the settings repo. */
  val portManager: TrailblazePortManager
    get() = desktopAppConfig.trailblazeSettingsRepo.portManager

  /**
   * Applies CLI port overrides as **transient, in-memory** values.
   *
   * The overrides are NOT written to the settings file, so multiple
   * concurrent instances (each started with different `-p` flags or
   * `TRAILBLAZE_PORT` env vars) won't race on the shared config.
   */
  fun applyPortOverrides(httpPort: Int, httpsPort: Int) {
    portManager.setRuntimeOverrides(httpPort, httpsPort)
    // Update the global server URL used by NetworkImageLoader for screenshot loading
    NetworkImageLoader.currentServerBaseUrl = portManager.serverUrl
  }

  /**
   * Ensures the MCP server is running.
   * 
   * This is called automatically when operations that need the server are performed
   * (e.g., OAuth callbacks, log collection). If the server is already running 
   * (either from this app instance or a daemon), this is a no-op.
   */
  open fun ensureServerRunning() {
    val serverPort = portManager.httpPort
    val serverHttpsPort = portManager.httpsPort
    val daemon = DaemonClient(port = serverPort)
    if (daemon.isRunning()) {
      return // Server already running
    }
    
    Console.log("Starting Trailblaze server...")
    runBlocking {
      trailblazeMcpServer.startStreamableHttpMcpServer(
        port = serverPort,
        httpsPort = serverHttpsPort,
        wait = false,
      )
    }
    
    // Wait for server to be ready
    var attempts = 0
    while (!daemon.isRunning() && attempts < 30) {
      Thread.sleep(200)
      attempts++
    }
    
    if (daemon.isRunning()) {
      Console.log("Server started on port $serverPort")
    } else {
      Console.error("Warning: Server may not have started properly")
    }
  }
}
