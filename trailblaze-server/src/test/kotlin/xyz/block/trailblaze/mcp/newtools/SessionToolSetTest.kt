package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        TrailblazeMcpSessionContext.CaptureArtifactInfo("device.log", "LOGCAT", 50000),
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
    assertContains(json["message"]!!.jsonPrimitive.content, "device.log")
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

    // Asserts the user-actionable CLI hint, not the MCP tool-name shape.
    // The previous "No title" substring also matched the old (worse) wording
    // — pinning the new phrasing here so a regression away from CLI-shaped
    // guidance trips this test loudly instead of silently passing.
    val err = json["error"]!!.jsonPrimitive.content
    assertContains(err, "Trail title is required")
    assertContains(err, "trailblaze session save")
  }

  @Test
  fun `session SAVE for an iOS session writes ios-trail-yaml even when live context is Android`() = runTest {
    // Regression guard for the OOBE-sweep bug: when `session save --id <ios-id>`
    // fired after an Android command had touched the daemon, the filename
    // platform came from the live `sessionContext.associatedDeviceId` (Android,
    // last-touched) instead of the saved session's own SessionStarted log.
    // Result: an iOS session's YAML — which correctly contained `platform: ios`
    // inside — was written to a path ending `/android.trail.yaml`, contradicting
    // the file content. Now the filename is keyed off the session's own log.

    val logsDir = java.nio.file.Files.createTempDirectory("session-save-platform-").toFile()
    val trailsDir = java.nio.file.Files.createTempDirectory("session-save-trails-").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = logsDir, watchFileSystem = false)

      // Persist a SessionStarted log for an iOS session. This is the source of
      // truth `saveFromLogs` should consult — not the live sessionContext.
      val iosSessionId = SessionId("2026_05_31_21_22_12_yaml_ios")
      val iosDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "E5BDD6FB-1C7C-46B7-A479-E8A772C3922D",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
        ),
        trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
        widthPixels = 1170,
        heightPixels = 2532,
        classifiers = emptyList(),
      )
      val sessionStartedLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus =
          SessionStatus.Started(
            trailConfig = null,
            trailFilePath = null,
            hasRecordedSteps = false,
            testMethodName = "Pause the splash video",
            testClassName = "MCP",
            trailblazeDeviceInfo = iosDeviceInfo,
            trailblazeDeviceId = iosDeviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
        session = iosSessionId,
        timestamp = Clock.System.now(),
      )
      logsRepo.saveLogToDisk(sessionStartedLog)

      // Need at least one recordable step in the session log for
      // `saveFromLogs` to clear its "no recordable steps" guard. The cheapest
      // shape that produces a non-empty trail body is an ObjectiveStart paired
      // with an ObjectiveComplete carrying a non-blank step.
      val pauseStep = xyz.block.trailblaze.yaml.DirectionStep(step = "Tap the pause button")
      logsRepo.saveLogToDisk(
        TrailblazeLog.ObjectiveStartLog(
          promptStep = pauseStep,
          session = iosSessionId,
          timestamp = Clock.System.now(),
        ),
      )
      logsRepo.saveLogToDisk(
        TrailblazeLog.ObjectiveCompleteLog(
          promptStep = pauseStep,
          objectiveResult =
            xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete(
              llmExplanation = "Tapped pause",
              statusData =
                xyz.block.trailblaze.agent.model.AgentTaskStatusData(
                  taskId = xyz.block.trailblaze.logs.model.TaskId.generate(),
                  prompt = pauseStep.prompt,
                  callCount = 1,
                  taskStartTime = Clock.System.now(),
                  totalDurationMs = 50,
                ),
            ),
          session = iosSessionId,
          timestamp = Clock.System.now(),
        ),
      )

      // Live context points at an ANDROID device — this is the "last-touched
      // device" state that the buggy code path keyed off. The mock bridge
      // returns Android as the available device. If the fix regresses, the
      // filename will end up `android.trail.yaml`.
      val androidDeviceId = TrailblazeDeviceId(
        instanceId = "emulator-5554",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      )
      val sessionContext = createSessionContext()
      sessionContext.setAssociatedDevice(androidDeviceId)

      val bridge = SessionTestBridge(activeSessionId = iosSessionId)

      val toolSet = SessionToolSet(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        logsRepo = logsRepo,
        sessionIdProvider = { iosSessionId },
        trailsDirectory = trailsDir.absolutePath,
      )

      val result = toolSet.session(
        action = SessionToolSet.SessionAction.SAVE,
        id = iosSessionId.value,
        title = "iOS pause test",
      )
      val json = Json.parseToJsonElement(result).jsonObject

      val errMsg = json["error"]?.jsonPrimitive?.content
      assertNull(
        errMsg,
        "save should succeed against the seeded session, got error: $errMsg",
      )
      val file = json["file"]?.jsonPrimitive?.content
      assertNotNull(file, "save result should include a file path")
      assertTrue(
        file.endsWith("/ios.trail.yaml"),
        "iOS session must write ios.trail.yaml; got: $file",
      )
      assertTrue(
        File(file).readText().contains("platform: ios"),
        "file content should mark the platform as ios; got: ${File(file).readText()}",
      )
      assertTrue(
        File(file).readText().contains("title: iOS pause test"),
        "the caller-supplied save title must override into the saved config; got: ${File(file).readText()}",
      )
    } finally {
      logsDir.deleteRecursively()
      trailsDir.deleteRecursively()
    }
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

  // ── target / targetSource fields ────────────────────────────────────────
  //
  // These pin the visibility plumbing the PR added — session-start, session-
  // info and session-stop must surface the effective target (or report "none")
  // so users / agents don't lose track of which target a recording is running
  // under. Foot-gun closer: after `session stop`, the override is gone; the
  // hint reminds the user.

  @Test
  fun `session START reports session-override when a per-session target is set`() = runTest {
    val bridge = SessionTestBridge(
      activeSessionId = trailblazeSessionId,
      sessionTargets = mutableMapOf(trailblazeSessionId to "myapp"),
      daemonWideTarget = "default",
    )
    val toolSet = SessionToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
      sessionIdProvider = { trailblazeSessionId },
    )

    val result = toolSet.session(action = SessionToolSet.SessionAction.START)
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals("myapp", json["target"]?.jsonPrimitive?.content)
    assertEquals(
      SessionToolSet.TARGET_SOURCE_SESSION_OVERRIDE,
      json["targetSource"]?.jsonPrimitive?.content,
    )
    assertContains(json["message"]!!.jsonPrimitive.content, "Target: myapp (session-override)")
  }

  @Test
  fun `session START reports daemon-wide when no override is set`() = runTest {
    val bridge = SessionTestBridge(
      activeSessionId = trailblazeSessionId,
      daemonWideTarget = "default",
      // sessionTargets intentionally empty — fall through to daemon-wide.
    )
    val toolSet = SessionToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
      sessionIdProvider = { trailblazeSessionId },
    )

    val result = toolSet.session(action = SessionToolSet.SessionAction.START)
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals("default", json["target"]?.jsonPrimitive?.content)
    assertEquals(
      SessionToolSet.TARGET_SOURCE_DAEMON_WIDE,
      json["targetSource"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `session START reports null target when nothing is configured`() = runTest {
    val bridge = SessionTestBridge(
      activeSessionId = trailblazeSessionId,
      // No session override, no daemon-wide default.
    )
    val toolSet = SessionToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
      sessionIdProvider = { trailblazeSessionId },
    )

    val result = toolSet.session(action = SessionToolSet.SessionAction.START)
    val json = Json.parseToJsonElement(result).jsonObject

    // kotlinx-serialization omits null fields, so the keys are absent (not
    // present-with-JsonNull). Use containsKey to distinguish "absent" from
    // "null literal" — they're both rendered the same way over the wire
    // but the assertion's intent is clearer.
    kotlin.test.assertNull(json["target"])
    kotlin.test.assertNull(json["targetSource"])
  }

  @Test
  fun `session STOP includes cleared-override hint only when an override existed`() = runTest {
    val bridge = SessionTestBridge(
      activeSessionId = trailblazeSessionId,
      sessionTargets = mutableMapOf(trailblazeSessionId to "myapp"),
    )
    val toolSet = SessionToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
      sessionIdProvider = { trailblazeSessionId },
    )

    val result = toolSet.session(action = SessionToolSet.SessionAction.STOP)
    val json = Json.parseToJsonElement(result).jsonObject

    assertContains(
      json["message"]!!.jsonPrimitive.content,
      "the `--target` override set on this session is cleared",
    )
  }

  @Test
  fun `session STOP omits cleared-override hint when no override was set`() = runTest {
    val bridge = SessionTestBridge(
      activeSessionId = trailblazeSessionId,
      // sessionTargets empty — no override on this session.
    )
    val toolSet = SessionToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
      sessionIdProvider = { trailblazeSessionId },
    )

    val result = toolSet.session(action = SessionToolSet.SessionAction.STOP)
    val json = Json.parseToJsonElement(result).jsonObject

    kotlin.test.assertFalse(
      "override is cleared" in json["message"]!!.jsonPrimitive.content,
      "stops on sessions without an override should not mention overrides — got: ${json["message"]}",
    )
  }
}

/** Mock bridge for session tool tests. */
class SessionTestBridge(
  private val activeSessionId: SessionId? = null,
  /**
   * Per-session target overrides — keyed by session id. Mirrors the
   * production registry on `TrailblazeDeviceManager`. Tests populate this
   * to drive the resolver in `SessionToolSet.resolveTargetForSession`.
   */
  private val sessionTargets: MutableMap<SessionId, String> = mutableMapOf(),
  /** Returned by [getCurrentAppTargetId] — the daemon-wide fallback. */
  private val daemonWideTarget: String? = null,
) : TrailblazeMcpBridge {
  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()

  override suspend fun selectDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): TrailblazeConnectedDeviceSummary = throw NotImplementedError()

  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String =
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
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? = null

  override fun getActiveSessionId(): SessionId? = activeSessionId

  override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = activeSessionId

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}

  override fun selectAppTarget(appTargetId: String): String? = null

  override fun getCurrentAppTargetId(): String? = daemonWideTarget

  override fun getTargetForSession(sessionId: SessionId): String? = sessionTargets[sessionId]
}
