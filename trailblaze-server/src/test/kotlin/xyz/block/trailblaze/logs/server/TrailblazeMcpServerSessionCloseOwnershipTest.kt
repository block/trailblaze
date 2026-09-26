package xyz.block.trailblaze.logs.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import kotlin.test.assertEquals

/**
 * Closing (or displacing) an MCP session ends only the recording that session started. With one
 * device attached the server auto-connects every new MCP session to it, so a one-shot
 * `trailblaze device list` or a device-autodetect probe closing must not end the recording the
 * user's CLI session is building — nor cancel its automation or close its device connection.
 */
class TrailblazeMcpServerSessionCloseOwnershipTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val device = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  /** Recordings per device, answered for whichever device the caller bound — as the real bridge does. */
  private class RecordingsBridge : TrailblazeMcpBridge by NoopBridge {
    val recordings = mutableMapOf<TrailblazeDeviceId, SessionId>()
    val ended = mutableListOf<SessionId>()
    val cancelled = mutableListOf<TrailblazeDeviceId>()
    val connectionsReleased = mutableListOf<TrailblazeDeviceId>()

    override fun getActiveSessionId(): SessionId? = McpDeviceContext.currentDeviceId.get()?.let { recordings[it] }

    override suspend fun endSession(): Boolean {
      val id = McpDeviceContext.currentDeviceId.get() ?: error("endSession with no device bound")
      recordings.remove(id)?.let { ended += it }
      return true
    }

    override fun cancelAutomation(deviceId: TrailblazeDeviceId) {
      cancelled += deviceId
    }

    override fun releasePersistentDeviceConnection(deviceId: TrailblazeDeviceId) {
      connectionsReleased += deviceId
    }
  }

  private val bridge = RecordingsBridge()

  // Lazy: the TemporaryFolder rule only exists once a test starts, after field initialization.
  private val server by lazy {
    TrailblazeMcpServer(
      logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
      mcpBridge = bridge,
      trailsDirProvider = { tempFolder.newFolder("trails") },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      llmModelListsProvider = { emptySet() },
    )
  }

  /** A session context wired the way the server wires the ones it creates. */
  private fun newContext(id: String): TrailblazeMcpSessionContext {
    val context = TrailblazeMcpSessionContext(mcpServerSession = null, mcpSessionId = McpSessionId(id))
    context.activeRecordingOnDevice = { deviceId -> bridge.recordings[deviceId] }
    return context
  }

  /** Runs [block] the way the request dispatcher runs one of [context]'s tool calls. */
  private fun ownToolCall(context: TrailblazeMcpSessionContext, block: () -> Unit) {
    context.noteRecordingsBeforeOwnToolCall()
    try {
      block()
    } finally {
      context.noteRecordingsAfterOwnToolCall()
    }
  }

  private fun close(context: TrailblazeMcpSessionContext) =
    server.cleanupDeviceOnSessionClose(device, "test closure", context.mcpSessionId.sessionId, context)

  private fun assertLeftRunning(recording: SessionId) {
    assertEquals(recording, bridge.recordings[device], "the recording must still be running")
    assertEquals(emptyList(), bridge.ended)
    assertEquals(emptyList(), bridge.cancelled, "nor may the automation driving it be cancelled")
    assertEquals(emptyList(), bridge.connectionsReleased, "nor its owner's device connection closed")
  }

  @Test
  fun `closing a session that attached to an already-running recording leaves it running`() {
    val userRecording = SessionId("2026_09_24_10_17_30_yaml_6258")
    bridge.recordings[device] = userRecording
    val probe = newContext("device-list-probe")

    probe.setAssociatedDevice(device)
    ownToolCall(probe) {} // `device list` runs a tool call of its own; it starts nothing.
    close(probe)

    assertLeftRunning(userRecording)
  }

  @Test
  fun `closing a session that started the recording ends it`() {
    val oneShot = newContext("one-shot")
    val started = SessionId("2026_09_24_10_20_00_yaml_1")

    oneShot.setAssociatedDevice(device)
    ownToolCall(oneShot) { bridge.recordings[device] = started }
    close(oneShot)

    assertEquals(listOf(started), bridge.ended)
    assertEquals(listOf(device), bridge.cancelled)
    assertEquals(listOf(device), bridge.connectionsReleased)
  }

  @Test
  fun `a recording that replaced the joined one during the session's own tool call is its to end`() {
    bridge.recordings[device] = SessionId("joined")
    val context = newContext("replacer")

    context.setAssociatedDevice(device)
    val replacement = SessionId("started-by-this-session")
    ownToolCall(context) { bridge.recordings[device] = replacement }
    close(context)

    assertEquals(listOf(replacement), bridge.ended)
  }

  @Test
  fun `a recording someone else started while the session sat idle is left running`() {
    bridge.recordings[device] = SessionId("joined")
    val idle = newContext("idle-client")
    idle.setAssociatedDevice(device)

    // The joined recording's owner stops it and a third party starts another, all between calls.
    val thirdParty = SessionId("started-by-a-third-party")
    bridge.recordings[device] = thirdParty
    close(idle)

    assertLeftRunning(thirdParty)
  }

  @Test
  fun `after a detach, a recording another client started is not adopted on re-attach`() {
    val context = newContext("detaches")
    context.setAssociatedDevice(device)
    ownToolCall(context) {
      bridge.recordings.remove(device)
      context.clearAssociatedDevice() // `session stop`: ends its recording and detaches.
    }

    val otherClients = SessionId("started-by-another-client")
    bridge.recordings[device] = otherClients
    context.setAssociatedDevice(device)
    close(context)

    assertLeftRunning(otherClients)
  }

  @Test
  fun `re-attaching never disowns a recording the session started`() {
    val context = newContext("hands-back")
    context.setAssociatedDevice(device)
    val started = SessionId("started-by-this-session")
    ownToolCall(context) { bridge.recordings[device] = started }

    context.setAssociatedDevice(device)
    close(context)

    assertEquals(listOf(started), bridge.ended)
  }

  @Test
  fun `a displaced session does not cancel the automation of a recording it found running`() {
    val userRecording = SessionId("the-displacing-session's-recording")
    bridge.recordings[device] = userRecording
    val displaced = newContext("displaced-probe")
    displaced.setAssociatedDevice(device)
    server.installSessionContextForTest(displaced.mcpSessionId.sessionId, displaced)

    server.terminateSession(displaced.mcpSessionId.sessionId)

    assertLeftRunning(userRecording)
  }

  @Test
  fun `a displaced session still cancels the automation of a recording it started`() {
    val displaced = newContext("displaced-owner")
    displaced.setAssociatedDevice(device)
    ownToolCall(displaced) { bridge.recordings[device] = SessionId("started-by-the-displaced-session") }
    server.installSessionContextForTest(displaced.mcpSessionId.sessionId, displaced)

    server.terminateSession(displaced.mcpSessionId.sessionId)

    assertEquals(listOf(device), bridge.cancelled)
  }

  @Test
  fun `a cast member that joined a recording found running on another device leaves it running`() {
    // The host shares the session a cast member is on with the rest of the cast, a session another
    // client started included. Ending it from the member that joined would end the other client's work.
    val buyer = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    val userRecording = SessionId("2026_09_24_10_17_30_yaml_6258")
    bridge.recordings[device] = userRecording
    val cast = newContext("cast")
    fun bind(name: String, id: TrailblazeDeviceId) =
      cast.bindNamedDevice(name, SessionDeviceBindings.BoundDevice(id, trailblazeDeviceInfo = null, description = null, targetId = null))

    ownToolCall(cast) { bind("seller", device) }
    ownToolCall(cast) {
      bind("buyer", buyer)
      bridge.recordings[buyer] = userRecording // the bind put the buyer on the seller's session
    }
    for (member in listOf(buyer, device)) {
      server.cleanupDeviceOnSessionClose(member, "test closure", cast.mcpSessionId.sessionId, cast)
    }

    assertEquals(emptyList(), bridge.ended, "the other client's recording must still be running")
    assertEquals(userRecording, bridge.recordings[device])
    // The host cancels a device by cancelling the whole session it is on.
    assertEquals(emptyList(), bridge.cancelled, "cancelling the member would end the other client's session too")
    assertEquals(listOf(buyer), bridge.connectionsReleased, "the member's own connection is still let go")
  }

  @Test
  fun `a displaced cast does not cancel a member that joined a recording found running on another device`() {
    val buyer = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    val userRecording = SessionId("2026_09_24_10_17_30_yaml_6258")
    bridge.recordings[device] = userRecording
    val cast = newContext("displaced-cast-on-found-recording")
    fun bind(name: String, id: TrailblazeDeviceId) =
      cast.bindNamedDevice(name, SessionDeviceBindings.BoundDevice(id, trailblazeDeviceInfo = null, description = null, targetId = null))
    ownToolCall(cast) {
      bind("seller", device)
      cast.setAssociatedDevice(device)
    }
    ownToolCall(cast) {
      bind("buyer", buyer)
      bridge.recordings[buyer] = userRecording
    }
    server.installSessionContextForTest(cast.mcpSessionId.sessionId, cast)

    server.terminateSession(cast.mcpSessionId.sessionId)

    assertEquals(emptyList(), bridge.cancelled, "cancelling either member would end the other client's session")
  }

  @Test
  fun `a displaced session cleans up every device it bound, not only the active one`() {
    val buyer = TrailblazeDeviceId(instanceId = "iphone-sim", trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS)
    val iosSeller = TrailblazeDeviceId(instanceId = "ipad-sim", trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS)
    val cast = newContext("displaced-cast")
    fun bind(name: String, id: TrailblazeDeviceId) =
      cast.bindNamedDevice(name, SessionDeviceBindings.BoundDevice(id, trailblazeDeviceInfo = null, description = null, targetId = null))
    ownToolCall(cast) {
      bind("seller", iosSeller)
      cast.setAssociatedDevice(iosSeller)
      bind("buyer", buyer) // bound and warmed, never made active
    }
    server.installSessionContextForTest(cast.mcpSessionId.sessionId, cast)

    server.terminateSession(cast.mcpSessionId.sessionId)

    assertEquals(setOf(iosSeller, buyer), bridge.cancelled.toSet())
    assertEquals(setOf(iosSeller, buyer), bridge.connectionsReleased.toSet(), "no stale XCTest connection is left for the next owner")
  }
}
