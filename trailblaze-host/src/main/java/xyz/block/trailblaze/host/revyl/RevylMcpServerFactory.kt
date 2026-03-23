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
 * Supports provisioning one or more devices at creation time.
 * When multiple platforms are requested, a device is started for
 * each platform and the MCP bridge can switch between them.
 *
 * Usage:
 * ```
 * // Single device
 * val server = RevylMcpServerFactory.create(platform = "android")
 *
 * // Both platforms
 * val server = RevylMcpServerFactory.create(platforms = listOf("android", "ios"))
 *
 * server.startStreamableHttpMcpServer(port = 8080, wait = true)
 * ```
 *
 * The CLI binary must be on PATH (or set via REVYL_BINARY env var)
 * and authenticated via REVYL_API_KEY or `revyl auth login`.
 */
object RevylMcpServerFactory {

  /**
   * Creates a [TrailblazeMcpServer] that provisions a single Revyl cloud device.
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
    return create(
      platforms = listOf(platform),
      appUrl = appUrl,
      appLink = appLink,
      trailsDir = trailsDir,
    )
  }

  /**
   * Creates a [TrailblazeMcpServer] that provisions one or more Revyl cloud devices.
   *
   * When multiple platforms are provided, a device session is started for each.
   * The MCP bridge supports switching between sessions via [RevylMcpBridge.selectDevice].
   *
   * @param platforms List of platforms to provision (e.g. ["android", "ios"]).
   * @param appUrl Optional public URL to an .apk/.ipa to install on each device.
   * @param appLink Optional deep-link to open after launch.
   * @param trailsDir Directory containing .trail YAML files.
   * @return A configured [TrailblazeMcpServer] ready to start.
   * @throws RevylCliException If any device provisioning fails.
   */
  fun create(
    platforms: List<String>,
    appUrl: String? = null,
    appLink: String? = null,
    trailsDir: File = File(System.getProperty("user.dir"), "trails"),
  ): TrailblazeMcpServer {
    require(platforms.isNotEmpty()) { "At least one platform must be specified" }

    Console.log("RevylMcpServerFactory: creating CLI-backed MCP server")
    Console.log("  Platforms: ${platforms.joinToString(", ")}")

    val cliClient = RevylCliClient()

    for (p in platforms) {
      Console.log("RevylMcpServerFactory: provisioning $p device via CLI...")
      val session = cliClient.startSession(platform = p, appUrl = appUrl, appLink = appLink)
      Console.log("RevylMcpServerFactory: $p device ready (session ${session.index})")
      Console.log("  Viewer: ${session.viewerUrl}")
    }

    val primaryPlatform = platforms.first()
    val revylDeviceService = RevylDeviceService(cliClient)
    val agent = RevylTrailblazeAgent(cliClient, primaryPlatform)
    val bridge = RevylMcpBridge(cliClient, revylDeviceService, agent, primaryPlatform)

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
