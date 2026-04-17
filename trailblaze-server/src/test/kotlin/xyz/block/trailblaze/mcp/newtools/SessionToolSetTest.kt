package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [SessionToolSet].
 *
 * Verifies that the session tool correctly:
 * - Starts sessions and invokes capture
 * - Stops sessions and collects artifacts
 * - Returns error when no device is connected
 * - Saves sessions as trail YAML (fallback path)
 * - Lists sessions when LogsRepo is available
 * - Fails early when LogsRepo is null for browse operations
 * - Captures sessionId before clearing state on stop
 */
class SessionToolSetTest {

  private val testSessionId = McpSessionId("test-session")
  private val trailblazeSessionId = SessionId("2026_03_25_14_30_45_test_abc123")

  private fun createSessionContext() =
    TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = testSessionId,
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    )

  private val androidDevice =
    TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      instanceId = "emulator-5554",
      description = "Pixel 6 API 34",
    )

  // ── START action ───────────────────────────────────────────────────────────

  @Test
  fun `session START returns error when no session ID (no device)`() = runTest {
    val bridge = SessionTestBridge()
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        sessionIdProvider = { null },
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.START)
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["error"]?.jsonPrimitive?.content)
    assertContains(json["error"]!!.jsonPrimitive.content, "No active device")
  }

  @Test
  fun `session START succeeds and invokes capture provider`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    var captureStarted = false
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
        startCaptureProvider = { _, _, _ ->
          captureStarted = true
          "Capturing video + device logs"
        },
      )

    val result =
      toolSet.session(
        action = SessionToolSet.SessionAction.START,
        title = "Login test",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals("started", json["status"]?.jsonPrimitive?.content)
    assertEquals("Login test", json["title"]?.jsonPrimitive?.content)
    assertContains(json["message"]!!.jsonPrimitive.content, "Capturing video")
    kotlin.test.assertTrue(captureStarted)
  }

  @Test
  fun `session START starts implicit recording when no title`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val sessionContext = createSessionContext()
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    toolSet.session(action = SessionToolSet.SessionAction.START)

    kotlin.test.assertTrue(sessionContext.isRecordingActive())
  }

  @Test
  fun `session START starts named recording when title provided`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val sessionContext = createSessionContext()
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    toolSet.session(
      action = SessionToolSet.SessionAction.START,
      title = "My trail",
    )

    kotlin.test.assertTrue(sessionContext.isRecordingActive())
    assertEquals("My trail", sessionContext.getCurrentTrailName())
  }

  // ── STOP action ────────────────────────────────────────────────────────────

  @Test
  fun `session STOP captures sessionId before clearing state`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val sessionContext = createSessionContext()
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.STOP)
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals(trailblazeSessionId.value, json["sessionId"]?.jsonPrimitive?.content)
    assertEquals("stopped", json["status"]?.jsonPrimitive?.content)
  }

  @Test
  fun `session STOP invokes capture stop callback and returns artifacts`() = runTest {
    val sessionContext = createSessionContext()
    sessionContext.stopCaptureCallback = {
      listOf(
        TrailblazeMcpSessionContext.CaptureArtifactInfo("video.mp4", "VIDEO", 1024000),
        TrailblazeMcpSessionContext.CaptureArtifactInfo("logcat.txt", "LOGCAT", 50000),
      )
    }

    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.STOP)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["message"]!!.jsonPrimitive.content, "video.mp4")
    assertContains(json["message"]!!.jsonPrimitive.content, "logcat.txt")
  }

  @Test
  fun `session STOP with save=true but no steps reports save failed`() = runTest {
    val sessionContext = createSessionContext()
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    val result =
      toolSet.session(
        action = SessionToolSet.SessionAction.STOP,
        save = true,
        title = "empty trail",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["message"]!!.jsonPrimitive.content, "save failed")
  }

  @Test
  fun `session STOP clears recording and device state`() = runTest {
    val sessionContext = createSessionContext()
    sessionContext.startTrailRecording("test")
    sessionContext.setAssociatedDevice(androidDevice.trailblazeDeviceId)
    sessionContext.sessionTitle = "My session"

    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val toolSet =
      SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    toolSet.session(action = SessionToolSet.SessionAction.STOP)

    kotlin.test.assertFalse(sessionContext.isRecordingActive())
    assertNull(sessionContext.associatedDeviceId)
    assertNull(sessionContext.sessionTitle)
    assertNull(sessionContext.stopCaptureCallback)
  }

  // ── SAVE action ────────────────────────────────────────────────────────────

  @Test
  fun `session SAVE returns error when no title and no session title`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.SAVE)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "No title")
  }

  @Test
  fun `session SAVE returns error when no steps recorded`() = runTest {
    val bridge = SessionTestBridge(activeSessionId = trailblazeSessionId)
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        sessionIdProvider = { trailblazeSessionId },
      )

    val result =
      toolSet.session(
        action = SessionToolSet.SessionAction.SAVE,
        title = "empty trail",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["error"]?.jsonPrimitive?.content)
  }

  // ── INFO / LIST / ARTIFACTS — logsRepo null ────────────────────────────────

  @Test
  fun `session INFO by id returns error when logsRepo is null`() = runTest {
    val bridge = SessionTestBridge()
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        logsRepo = null,
      )

    val result =
      toolSet.session(
        action = SessionToolSet.SessionAction.INFO,
        id = "some-session-id",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "not available")
  }

  @Test
  fun `session ARTIFACTS returns error when logsRepo is null`() = runTest {
    val bridge = SessionTestBridge()
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        logsRepo = null,
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.ARTIFACTS)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "not available")
  }

  @Test
  fun `session LIST returns error when logsRepo is null`() = runTest {
    val bridge = SessionTestBridge()
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        logsRepo = null,
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.LIST)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "not available")
  }

  @Test
  fun `session INFO for current session returns error when no active session`() = runTest {
    val bridge = SessionTestBridge()
    val toolSet =
      SessionToolSet(
        sessionContext = createSessionContext(),
        mcpBridge = bridge,
        sessionIdProvider = { null },
      )

    val result = toolSet.session(action = SessionToolSet.SessionAction.INFO)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(json["error"]!!.jsonPrimitive.content, "No active session")
  }
}

/** Mock bridge for session tool tests. */
class SessionTestBridge(
  private val activeSessionId: SessionId? = null,
) : TrailblazeMcpBridge {
  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()

  override suspend fun selectDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): TrailblazeConnectedDeviceSummary = throw NotImplementedError()

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean): String =
    "[OK]"

  override suspend fun getInstalledAppIds(): Set<String> = emptySet()

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()

  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ) = ""

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null

  override suspend fun getCurrentScreenState(): ScreenState? = null

  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? = null

  override suspend fun endSession(): Boolean = true

  override fun isOnDeviceInstrumentation(): Boolean = false

  override fun getDriverType(): TrailblazeDriverType? = null

  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ): GetScreenStateResponse? = null

  override fun getActiveSessionId(): SessionId? = activeSessionId

  override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = activeSessionId

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}

  override fun selectAppTarget(appTargetId: String): String? = null

  override fun getCurrentAppTargetId(): String? = null
}
