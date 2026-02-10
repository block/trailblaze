package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer

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

  /**
   * Ensures the MCP server is running.
   * 
   * This is called automatically when operations that need the server are performed
   * (e.g., OAuth callbacks, log collection). If the server is already running 
   * (either from this app instance or a daemon), this is a no-op.
   */
  open fun ensureServerRunning() {
    val daemon = DaemonClient()
    if (daemon.isRunning()) {
      return // Server already running
    }
    
    println("Starting Trailblaze server...")
    val serverPort = desktopAppConfig.trailblazeSettingsRepo.serverStateFlow.value.appConfig.serverPort
    runBlocking {
      trailblazeMcpServer.startStreamableHttpMcpServer(
        port = serverPort,
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
      println("Server started on port $serverPort")
    } else {
      System.err.println("Warning: Server may not have started properly")
    }
  }
}
