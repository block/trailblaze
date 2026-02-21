package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Factory for constructing a fully wired [TrailblazeMcpServer] backed by
 * the Revyl cloud device infrastructure.
 *
 * Usage:
 * ```
 * val server = RevylMcpServerFactory.create(
 *   apiKey = System.getenv("REVYL_API_KEY"),
 *   platform = "android",
 * )
 * server.startStreamableHttpMcpServer(port = 8080, wait = true)
 * ```
 */
object RevylMcpServerFactory {

  /**
   * Creates a [TrailblazeMcpServer] that provisions a Revyl cloud device
   * and routes all MCP tool calls through [RevylTrailblazeAgent].
   *
   * @param apiKey Revyl API key (typically from REVYL_API_KEY env var).
   * @param platform "ios" or "android".
   * @param backendUrl Override for the Revyl backend URL.
   * @param appUrl Optional direct URL to an .apk/.ipa to install on the device.
   * @param appLink Optional deep-link to open after launch.
   * @param trailsDir Directory containing .trail YAML files.
   * @return A configured [TrailblazeMcpServer] ready to start.
   * @throws RevylApiException If device provisioning fails.
   */
  fun create(
    apiKey: String,
    platform: String = "android",
    backendUrl: String = RevylWorkerClient.DEFAULT_BACKEND_URL,
    appUrl: String? = null,
    appLink: String? = null,
    trailsDir: File = File(System.getProperty("user.dir"), "trails"),
  ): TrailblazeMcpServer {
    Console.log("RevylMcpServerFactory: creating Revyl-backed MCP server")
    Console.log("  Platform: $platform")
    Console.log("  Backend:  $backendUrl")

    val revylClient = RevylWorkerClient(
      apiKey = apiKey,
      backendBaseUrl = backendUrl,
    )

    Console.log("RevylMcpServerFactory: provisioning cloud device...")
    revylClient.startSession(
      platform = platform,
      appUrl = appUrl,
      appLink = appLink,
    )

    val session = revylClient.getSession()!!
    Console.log("RevylMcpServerFactory: device ready")
    Console.log("  Worker URL: ${session.workerBaseUrl}")
    Console.log("  Viewer URL: ${session.viewerUrl}")

    val revylDeviceService = RevylDeviceService(revylClient)
    val agent = RevylTrailblazeAgent(revylClient, platform)
    val bridge = RevylMcpBridge(revylClient, revylDeviceService, agent)

    val logsDir = File(System.getProperty("user.dir"), ".trailblaze/logs")
    logsDir.mkdirs()
    val logsRepo = LogsRepo(logsDir)

    return TrailblazeMcpServer(
      logsRepo = logsRepo,
      mcpBridge = bridge,
      trailsDirProvider = { trailsDir },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    )
  }
}
