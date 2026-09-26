package xyz.block.trailblaze.logs.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.BoundDeviceRosterMember
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.test.assertEquals

/**
 * A force-terminated MCP session (a device claimed out from under it) must forget its named cast,
 * as a transport close does. The close that follows a termination finds the context already gone
 * and skips its own cleanup, so a roster left behind would pull every later session opened on any
 * of its devices, by any client, onto the stale cast.
 */
class TrailblazeMcpServerTerminateRosterTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `terminating a session forgets the cast it declared`() {
    val rosterUpdates = mutableListOf<Pair<String, List<BoundDeviceRosterMember>>>()
    val bridge = object : TrailblazeMcpBridge by NoopBridge {
      override fun setBoundDeviceRoster(rosterId: String, members: List<BoundDeviceRosterMember>): SessionId? {
        rosterUpdates += rosterId to members
        return null
      }
    }
    val server = TrailblazeMcpServer(
      logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
      mcpBridge = bridge,
      trailsDirProvider = { tempFolder.newFolder("trails") },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      llmModelListsProvider = { emptySet() },
    )
    server.installSessionContextForTest(
      "cast-session",
      TrailblazeMcpSessionContext(mcpServerSession = null, mcpSessionId = McpSessionId("cast-session")),
    )

    server.terminateSession("cast-session")

    assertEquals(listOf("cast-session" to emptyList()), rosterUpdates)
  }
}
