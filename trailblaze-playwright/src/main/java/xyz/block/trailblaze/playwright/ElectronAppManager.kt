package xyz.block.trailblaze.playwright

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.ElectronAppConfig
import java.net.HttpURLConnection
import java.net.URI

/**
 * Manages the lifecycle of an Electron application process for testing.
 *
 * Launches the Electron app with CDP remote debugging enabled, waits for the CDP
 * endpoint to become available, and provides graceful shutdown (SIGTERM → wait → SIGKILL).
 *
 * If [ElectronAppConfig.cdpUrl] is set, the app is assumed to be already running and
 * this manager only provides the CDP URL without launching or managing a process.
 */
class ElectronAppManager(
  private val config: ElectronAppConfig,
) : AutoCloseable {

  private var process: Process? = null

  /** The CDP WebSocket URL to connect Playwright to. */
  val cdpUrl: String
    get() = config.cdpUrl ?: "http://localhost:${config.cdpPort}"

  /** Whether this manager launched the app (vs. attaching to an existing one). */
  val isManagingProcess: Boolean
    get() = process != null

  /**
   * Launches the Electron app and waits for CDP to become available.
   *
   * If [ElectronAppConfig.cdpUrl] is already set, this is a no-op — the caller
   * wants to attach to an already-running app.
   *
   * @throws IllegalStateException if no command is configured and no cdpUrl is set
   * @throws IllegalStateException if CDP does not become available within the timeout
   */
  fun start() {
    if (config.cdpUrl != null) {
      Console.log("Electron: attaching to existing app at ${config.cdpUrl}")
      waitForCdp()
      return
    }

    val command = config.command
      ?: error("ElectronAppConfig requires either 'command' or 'cdpUrl' to be set")

    val args = buildList {
      add(command)
      addAll(config.args)
      add("--remote-debugging-port=${config.cdpPort}")
    }

    val env = buildMap {
      putAll(config.env)
      // Always ensure ENABLE_PLAYWRIGHT is set for Goose-like apps
      putIfAbsent("ENABLE_PLAYWRIGHT", "true")
      if (config.headless) {
        put("ELECTRON_HEADLESS", "true")
      }
    }

    Console.log("Electron: launching ${args.joinToString(" ")}")
    val processBuilder = ProcessBuilder(args)
    processBuilder.environment().putAll(System.getenv())
    processBuilder.environment().putAll(env)
    processBuilder.redirectErrorStream(true)

    process = processBuilder.start()

    // Drain stdout/stderr on a daemon thread to prevent buffer blocking
    val proc = process!!
    Thread({
      proc.inputStream.bufferedReader().use { reader ->
        reader.forEachLine { line ->
          Console.log("  [electron] $line")
        }
      }
    }, "electron-stdout-drain").apply {
      isDaemon = true
      start()
    }

    waitForCdp()
  }

  /**
   * Polls the CDP `/json/version` endpoint until it responds with HTTP 200.
   */
  private fun waitForCdp() {
    val baseUrl = config.cdpUrl ?: "http://localhost:${config.cdpPort}"
    val url = "${baseUrl.trimEnd('/')}/json/version"
    val timeoutMs = config.cdpTimeoutSeconds * 1000L
    val pollIntervalMs = 500L
    val deadline = System.currentTimeMillis() + timeoutMs

    Console.log("Electron: waiting for CDP at $url (timeout: ${config.cdpTimeoutSeconds}s)")

    while (System.currentTimeMillis() < deadline) {
      // If we launched the process, make sure it hasn't exited
      process?.let { proc ->
        if (!proc.isAlive) {
          error("Electron app process exited with code ${proc.exitValue()} before CDP was ready")
        }
      }

      try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.requestMethod = "GET"
        val responseCode = connection.responseCode
        connection.disconnect()
        if (responseCode == 200) {
          Console.log("Electron: CDP is ready")
          return
        }
      } catch (_: Exception) {
        // CDP not ready yet
      }

      Thread.sleep(pollIntervalMs)
    }

    error("Electron CDP endpoint did not become available within ${config.cdpTimeoutSeconds}s at $url")
  }

  /**
   * Gracefully shuts down the Electron app process.
   * Sends SIGTERM, waits up to 5 seconds, then force-kills.
   */
  override fun close() {
    val proc = process ?: return
    process = null

    Console.log("Electron: shutting down app process")
    proc.destroy() // SIGTERM

    try {
      val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      if (!exited) {
        Console.log("Electron: force-killing app process after 5s timeout")
        proc.destroyForcibly()
        proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      }
    } catch (_: InterruptedException) {
      proc.destroyForcibly()
    }
    Console.log("Electron: app process terminated")
  }
}
