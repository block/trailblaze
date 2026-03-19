package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Factory for constructing a [TrailblazeMcpServer] backed by
 * the Revyl CLI for cloud device interactions.
 *
 * Usage:
 * ```
 * val server = RevylMcpServerFactory.create(platform = "android")
 * server.startStreamableHttpMcpServer(port = 8080, wait = true)
 * ```
 *
 * The CLI binary must be on PATH (or set via REVYL_BINARY env var)
 * and authenticated via REVYL_API_KEY or `revyl auth login`.
 */
object RevylMcpServerFactory {

  /**
   * Creates a [TrailblazeMcpServer] that provisions a Revyl cloud device
   * via the CLI and routes all MCP tool calls through [RevylTrailblazeAgent].
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional public URL to an .apk/.ipa to install on the device.
   * @param appLink Optional deep-link to open after launch.
   * @param trailsDir Directory containing .trail YAML files.
   * @return A configured [TrailblazeMcpServer] ready to start.
   * @throws RevylCliException If device provisioning fails.
   */
  fun create(
    platform: String = "android",
    appUrl: String? = null,
    appLink: String? = null,
    trailsDir: File = File(System.getProperty("user.dir"), "trails"),
  ): TrailblazeMcpServer {
    Console.log("RevylMcpServerFactory: creating CLI-backed MCP server")
    Console.log("  Platform: $platform")

    val cliClient = RevylCliClient()

    Console.log("RevylMcpServerFactory: provisioning cloud device via CLI...")
    cliClient.startSession(
      platform = platform,
      appUrl = appUrl,
      appLink = appLink,
    )

    val session = cliClient.getSession()!!
    Console.log("RevylMcpServerFactory: device ready")
    Console.log("  Viewer: ${session.viewerUrl}")

    val revylDeviceService = RevylDeviceService(cliClient)
    val agent = RevylTrailblazeAgent(cliClient, platform)
    val bridge = RevylMcpBridge(cliClient, revylDeviceService, agent)

    val logsDir = File(System.getProperty("user.dir"), ".trailblaze/logs")
    logsDir.mkdirs()
    val logsRepo = LogsRepo(logsDir)

    return TrailblazeMcpServer(
      logsRepo = logsRepo,
      mcpBridge = bridge,
      trailsDirProvider = { trailsDir },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      llmModelListsProvider = { emptySet() },
    )
  }
}
