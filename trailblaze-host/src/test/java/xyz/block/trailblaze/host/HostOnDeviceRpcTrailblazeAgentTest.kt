package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.messageContains
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import org.junit.After
import org.junit.Before
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.mobile.tools.AdbShellTrailblazeTool
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test

/**
 * Tests for [HostOnDeviceRpcTrailblazeAgent] using a local [MockRpcServer] to verify:
 * - executeMaestroCommands() forwards commands via RPC (not silently succeeding)
 * - RPC error details from the server are surfaced in error messages
 * - executeNodeSelectorTap() returns null to delegate to executeMaestroCommands()
 */
class HostOnDeviceRpcTrailblazeAgentTest {

  private val testDeviceId =
    TrailblazeDeviceId(
      instanceId = "test-device-rpc-agent",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  private val testRunYamlRequest =
    RunYamlRequest(
      testName = "test",
      yaml = "",
      trailFilePath = null,
      targetAppName = null,
      useRecordedSteps = false,
      trailblazeDeviceId = testDeviceId,
      trailblazeLlmModel =
        TrailblazeLlmModel(
          trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
          modelId = "test-model",
          inputCostPerOneMillionTokens = 0.0,
          outputCostPerOneMillionTokens = 0.0,
          contextLength = 1000,
          maxOutputTokens = 1000,
          capabilityIds = emptyList(),
        ),
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
    )

  private val mockServer = MockRpcServer(testDeviceId)
  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    mockServer.start()
    rpcClient = OnDeviceRpcClient(testDeviceId)
  }

  @After
  fun tearDown() {
    rpcClient.close()
    mockServer.stop()
  }

  private fun createAgent(
    trailblazeLogger: TrailblazeLogger = TrailblazeLogger.createNoOp(),
    resolvedTarget: xyz.block.trailblaze.model.ResolvedTarget? = null,
    appId: String? = null,
    trailblazeToolRepo: xyz.block.trailblaze.toolcalls.TrailblazeToolRepo? = null,
    reWarmTimeoutMs: Long = 50L,
    reWarmPollIntervalMs: Long = 1L,
  ): HostOnDeviceRpcTrailblazeAgent {
    return HostOnDeviceRpcTrailblazeAgent(
      rpcClient = rpcClient,
      runYamlRequestTemplate = testRunYamlRequest,
      trailblazeLogger = trailblazeLogger,
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = testDeviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          widthPixels = 1080,
          heightPixels = 1920,
        )
      },
      sessionProvider =
        TrailblazeSessionProvider {
          TrailblazeSession(
            sessionId = SessionId("test-session"),
            startTime = Clock.System.now(),
          )
        },
      resolvedTarget = resolvedTarget,
      appId = appId,
      trailblazeToolRepo = trailblazeToolRepo,
      // Collapse production's 10s re-warm budget + 500ms poll into ~50ms / 1ms so the
      // circuit-breaker tests exercise the state machine without paying real-clock retry
      // delays. Production callers keep the defaults from the constructor.
      reWarmTimeoutMs = reWarmTimeoutMs,
      reWarmPollIntervalMs = reWarmPollIntervalMs,
    )
  }

  /** Captures every emitted [TrailblazeLog]. The agent's logger is swapped in via [createAgent]. */
  private fun capturingLogger(captured: MutableList<TrailblazeLog>): TrailblazeLogger =
    TrailblazeLogger(
      logEmitter = LogEmitter { log -> captured.add(log) },
      screenStateLogger = ScreenStateLogger { "" },
    )

  @Test
  fun `runMaestroCommands with empty list returns success without RPC call`() {
    val result = runBlocking { createAgent().runMaestroCommands(emptyList(), null) }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `runMaestroCommands with commands attempts RPC call instead of silently succeeding`() {
    // Server returns 500 — the key assertion is that we get an Error, not Success.
    // Before the fix, executeMaestroCommands was a no-op that returned Success().
    mockServer.responseStatus = HttpStatusCode.InternalServerError
    mockServer.responseBody =
      """{"errorType":"UNKNOWN_ERROR","message":"device crash","details":"NPE at Line 42"}"""

    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*Allow Full Access.*")))
    val result = runBlocking {
      createAgent()
        .runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `RPC error details from server are included in error message`() {
    val deviceErrorDetails = "java.lang.NullPointerException: accessibilityService was null"
    mockServer.responseStatus = HttpStatusCode.InternalServerError
    mockServer.responseBody =
      """{"errorType":"UNKNOWN_ERROR","message":"execution failed","details":"$deviceErrorDetails"}"""

    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      createAgent()
        .runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    val errorMessage = (result as TrailblazeToolResult.Error).errorMessage
    // The server-side details must be surfaced, not silently discarded
    assertThat(errorMessage).contains(deviceErrorDetails)
  }

  @Test
  fun `per-tool RunYamlRequests reuse the host session ID as overrideSessionId`() {
    // Pin the invariant that every per-tool RPC dispatch carries the host's top-level
    // session ID as overrideSessionId, so on-device logs consolidate into a single session
    // directory. A regression here (generating a fresh per-tool session ID) would silently
    // re-scatter logs into per-tool session directories.
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val agent = createAgent()
    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    runBlocking {
      repeat(2) {
        agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
      }
    }

    val runYamlBodies = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()
    assertThat(runYamlBodies).hasSize(2)
    runYamlBodies.forEach { body ->
      val overrideSessionId = Json.parseToJsonElement(body)
        .jsonObject["config"]!!.jsonObject["overrideSessionId"]!!.jsonPrimitive.content
      assertThat(overrideSessionId).isEqualTo("test-session")
    }
    // Regression pin: the old polling path made a GetExecutionStatusRequest per tool. The
    // sync-response contract removes that round-trip entirely — if this ever goes non-empty,
    // the client regressed back to polling.
    assertThat(mockServer.requestLog["/rpc/GetExecutionStatusRequest"].orEmpty()).isEmpty()
  }

  @Test
  fun `per-tool RunYamlRequests preserve the originating traceId`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    runBlocking {
      createAgent().runMaestroCommands(commands, traceId)
    }

    val requestJson = Json.parseToJsonElement(
      mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single(),
    ).jsonObject
    assertThat(requestJson["traceId"]!!.jsonPrimitive.content).isEqualTo(traceId.traceId)
  }

  @Test
  fun `tool RPC failure re-warms once and retries the same request`() {
    val runYamlAttempts = AtomicInteger(0)
    mockServer.onPost("/rpc/RunYamlRequest") {
      if (runYamlAttempts.incrementAndGet() == 1) {
        HttpStatusCode.InternalServerError to
          """{"errorType":"NETWORK_ERROR","message":"Network error during RPC call","details":"Failed to connect to localhost/127.0.0.1:56166"}"""
      } else {
        HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
      }
    }
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.OK to
        """
        {
          "viewHierarchy": {},
          "screenshotBase64": null,
          "deviceWidth": 1080,
          "deviceHeight": 1920
        }
        """.trimIndent()
    }

    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      createAgent()
        .runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).hasSize(2)
    assertThat(mockServer.requestLog["/rpc/GetScreenStateRequest"].orEmpty()).hasSize(1)
  }

  /**
   * Defensive contract check: the per-tool RPC dispatches with `awaitCompletion = true` (the
   * request default + explicit set in the client). If the server returns a fire-and-forget
   * shape (no `success` field), we cannot safely interpret that as success — treat as a
   * server contract violation and surface the error.
   */
  @Test
  fun `null success on sync dispatch surfaces as contract violation error`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session"}"""
    }

    val commands =
      listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      createAgent()
        .runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    val errorMessage = (result as TrailblazeToolResult.Error).errorMessage
    assertThat(errorMessage).contains("contract violation")
  }

  /**
   * Regression for the host→device memory interpolation gap: a value remembered
   * on the host (here via a `MemoryTrailblazeTool` short-circuited in `BaseTrailblazeAgent`)
   * lives in the host's `AgentMemory`. The on-device runner spins up a fresh, empty
   * `AgentMemory` per `RunYamlRequest`, so a subsequent `inputText { text: "{{var}}" }`
   * arrives at the device with empty memory and reaches Maestro as the empty string. Without
   * this fix the test would observe the `inputText` YAML carry the raw `{{name}}` token to
   * the device.
   */
  @Test
  fun `tool args are interpolated against host memory before RPC dispatch`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val agent = createAgent()
    // Populate the host memory directly — RememberTextTrailblazeTool needs a screen-state
    // backed ElementComparator that we don't have in unit tests, so seed the underlying
    // store the same way BaseTrailblazeAgent's MemoryTrailblazeTool short-circuit would.
    agent.memory.remember("memberPreferredName", "Cinque")

    val tools = listOf(InputTextTrailblazeTool(text = "{{memberPreferredName}}"))
    val result = agent.runTrailblazeTools(
      tools = tools,
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      screenState = null as ScreenState?,
      elementComparator = NoopElementComparator,
      screenStateProvider = null,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    // Resolved literal must reach the device YAML; the raw token must not.
    assertThat(yaml).contains("Cinque")
    assertThat(yaml).doesNotContain("{{memberPreferredName}}")
  }

  /**
   * The `MemoryTrailblazeTool` short-circuit in `BaseTrailblazeAgent` and the per-tool RPC
   * path must share the same `AgentMemory` instance. If they don't, a `rememberText` populates
   * one map but the next RPC tool reads from a different (empty) map and the interpolation
   * is a no-op.
   */
  @Test
  fun `value remembered by MemoryTrailblazeTool is available to subsequent RPC tool`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val agent = createAgent()
    val elementComparator = StubElementComparator(value = "Cinque")
    val tools = listOf(
      RememberTextTrailblazeTool(prompt = "the preferred name", variable = "memberPreferredName"),
      InputTextTrailblazeTool(text = "{{memberPreferredName}}"),
    )

    val result = agent.runTrailblazeTools(
      tools = tools,
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      screenState = null as ScreenState?,
      elementComparator = elementComparator,
      screenStateProvider = null,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    // Only the `inputText` tool dispatches over RPC — `rememberText` is short-circuited on
    // the host. Verify the dispatched YAML carries the resolved literal.
    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    assertThat(yaml).contains("Cinque")
  }

  /**
   * `executeMaestroCommands` wraps raw maestro YAML into a `MaestroTrailblazeTool(yaml = ...)`
   * and pipes it through the same `executeToolViaRpc` interpolation path. If `MaestroTrailblazeTool.yaml`
   * is ever renamed or retyped, this test fails — protecting trails whose maestro fallback path
   * carries `{{var}}` tokens.
   */
  @Test
  fun `maestro YAML carrying memory tokens is interpolated before RPC dispatch`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val agent = createAgent()
    agent.memory.remember("greeting", "hello-world")

    // TapOnElementCommand serialized to maestro YAML carries the textRegex verbatim, so a
    // remembered token inside textRegex will appear as a string scalar in the resulting YAML.
    val commands = listOf(
      TapOnElementCommand(selector = ElementSelector(textRegex = ".*{{greeting}}.*")),
    )
    val result = runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    // The token-bearing maestro yaml is the `yaml: String` field on MaestroTrailblazeTool;
    // interpolation must rewrite it before the outer trail YAML is produced.
    assertThat(yaml).contains("hello-world")
    assertThat(yaml).doesNotContain("{{greeting}}")
  }

  /**
   * Bidirectional memory sync — outbound: the agent's `memory` is serialized into the
   * `RunYamlRequest.memorySnapshot` field on every per-tool dispatch.
   */
  @Test
  fun `dispatched RunYamlRequest carries the host memory snapshot`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val agent = createAgent()
    agent.memory.remember("merchantToken", "tok_abc123")
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val sentSnapshot = Json.parseToJsonElement(body).jsonObject["memorySnapshot"]!!.jsonObject
    assertThat(sentSnapshot["merchantToken"]!!.jsonPrimitive.content).isEqualTo("tok_abc123")
  }

  /**
   * Bidirectional memory sync — inbound: the device's response `memorySnapshot` replaces
   * the agent's memory on RPC success, so on-device tool writes flow back to the host.
   */
  @Test
  fun `device response memory snapshot replaces agent memory on success`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"test-session","success":true,""" +
        """"memorySnapshot":{"deviceWroteThis":"value-from-device"}}"""
    }

    val agent = createAgent()
    agent.memory.remember("willBeReplaced", "old-value")
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    // Full-state replace: device wins.
    assertThat(agent.memory.variables["deviceWroteThis"]).isEqualTo("value-from-device")
    assertThat(agent.memory.variables.containsKey("willBeReplaced")).isFalse()
  }

  @Test
  fun `executeNodeSelectorTap returns null to delegate to maestro commands`() {
    val result = runBlocking {
      createAgent()
        .executeNodeSelectorTap(
          nodeSelector =
            TrailblazeNodeSelector(
              iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Allow Full Access"),
            ),
          longPress = false,
          traceId = null,
        )
    }
    assertThat(result).isNull()
  }

  /** Without the breaker a wedged emulator hangs the trail for the full per-tool RPC budget;
   *  trip early with a TrailblazeException naming the device-unhealthy state. */
  @Test
  fun `circuit breaker trips after 3 consecutive UiAutomation-wedge GetScreenState failures`() {
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.InternalServerError to
        """{"errorType":"UNKNOWN_ERROR","message":"GetScreenState failed",""" +
        """"details":"java.lang.RuntimeException: Error while connecting UiAutomation@1a3e9fe""" +
        """ caused by android.os.DeadObjectException"}"""
    }

    val agent = createAgent()
    val failure: Throwable? = runBlocking {
      assertThat(agent.captureScreenState()).isNull()
      assertThat(agent.captureScreenState()).isNull()
      try {
        agent.captureScreenState()
        null
      } catch (t: Throwable) {
        t
      }
    }
    assertThat(failure).isNotNull()
    assertThat(failure!!).isInstanceOf(TrailblazeException::class)
    assertThat(failure).messageContains("Device unhealthy")
    assertThat(failure).messageContains("Error while connecting UiAutomation")
  }

  /** Disconnect-side analog of the connect-side wedge: `UiAutomation.disconnect()` throws a
   *  bare `RuntimeException` (not `IllegalStateException`) on a half-connected handle with
   *  `id=-1, flags=1`. The on-device recovery shim now widens its catch to cover this, but
   *  if the recovery fails 3 times in a row the circuit breaker must still trip — same as
   *  the connect side. */
  @Test
  fun `circuit breaker trips after 3 consecutive UiAutomation-disconnect-wedge GetScreenState failures`() {
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.InternalServerError to
        """{"errorType":"UNKNOWN_ERROR","message":"GetScreenState failed",""" +
        """"details":"java.lang.RuntimeException: Error while disconnecting UiAutomation@e035ae8""" +
        """[id=-1, displayId=0, flags=1]"}"""
    }

    val agent = createAgent()
    val failure: Throwable? = runBlocking {
      assertThat(agent.captureScreenState()).isNull()
      assertThat(agent.captureScreenState()).isNull()
      try {
        agent.captureScreenState()
        null
      } catch (t: Throwable) {
        t
      }
    }
    assertThat(failure).isNotNull()
    assertThat(failure!!).isInstanceOf(TrailblazeException::class)
    assertThat(failure).messageContains("Device unhealthy")
    assertThat(failure).messageContains("Error while disconnecting UiAutomation")
  }

  /** Reset on any successful capture: an unbroken streak of wedge-signature failures
   *  trips the breaker, but a clean success in between resets the counter. */
  @Test
  fun `circuit breaker resets on intervening success`() {
    val healthy = AtomicBoolean(false)
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      if (healthy.get()) {
        HttpStatusCode.OK to
          """{"viewHierarchy":{},"screenshotBase64":null,"deviceWidth":1080,"deviceHeight":1920}"""
      } else {
        HttpStatusCode.InternalServerError to
          """{"errorType":"UNKNOWN_ERROR","message":"GetScreenState failed",""" +
          """"details":"java.lang.RuntimeException: Error while connecting UiAutomation@1a3e9fe""" +
          """ caused by android.os.DeadObjectException"}"""
      }
    }

    val agent = createAgent()
    runBlocking {
      assertThat(agent.captureScreenState()).isNull()
      assertThat(agent.captureScreenState()).isNull()
      // Flip the mock healthy: the next capture succeeds and must reset the counter.
      healthy.set(true)
      assertThat(agent.captureScreenState()).isNotNull()
      // Flip back to wedged: two more failures alone must NOT trip — counter is 0 again.
      healthy.set(false)
      assertThat(agent.captureScreenState()).isNull()
      assertThat(agent.captureScreenState()).isNull()
    }
  }

  /** Non-wedge failures (network, generic 500s) must NOT count against the breaker —
   *  an unrelated flake shouldn't falsely abort an otherwise-recoverable trail. */
  @Test
  fun `circuit breaker ignores non-wedge GetScreenState failures`() {
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.InternalServerError to
        """{"errorType":"NETWORK_ERROR","message":"Network error during RPC call",""" +
        """"details":"Failed to connect to localhost/127.0.0.1:56166"}"""
    }

    val agent = createAgent()
    // Even after 5 non-wedge failures the breaker must NOT throw — the existing retry path
    // returns null and the caller decides what to do.
    runBlocking {
      repeat(5) {
        assertThat(agent.captureScreenState()).isNull()
      }
    }
  }

  /** Regression check: the `reWarmTimeoutMs` constructor param must actually flow to
   *  `rpcClient.waitForReady`. Without this, a refactor that silently re-hard-codes
   *  `10_000L` at the call site would only surface as the wedge tests above getting slower
   *  again — which is easy to miss in code review. This test makes the contract explicit:
   *  pass a tight 5ms budget, force the re-warm path, and bound the wall clock. If the
   *  param is ignored, this test takes 10 seconds and fails the bound. */
  @Test
  fun `reWarmTimeoutMs constructor param is honored end-to-end`() {
    // Permanently-500ing mock forces every captureScreenState into the re-warm path. The
    // "random failure" detail intentionally does NOT match any DEVICE_WEDGE_SIGNATURES, so
    // the breaker stays at 0 and a single call returns null (no exception).
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.InternalServerError to
        """{"errorType":"UNKNOWN_ERROR","message":"GetScreenState failed",""" +
        """"details":"random failure"}"""
    }

    val agent = createAgent(reWarmTimeoutMs = 5L, reWarmPollIntervalMs = 1L)
    val elapsedMs = runBlocking {
      val start = System.currentTimeMillis()
      assertThat(agent.captureScreenState()).isNull()
      System.currentTimeMillis() - start
    }

    // Generous upper bound — 5ms budget + JVM/test/HTTP-mock overhead. Production's 10_000L
    // default would put this WAY over 500ms.
    assertThat(elapsedMs).isLessThan(500L)
  }

  @Test
  fun `host-local marker tool dispatches via host execute, not RPC`() {
    val executedOnHost = AtomicBoolean(false)
    val fakeTool = FakeHostLocalTool(executedOnHost)
    val agent = createAgent()

    val executeToolMethod = agent.javaClass.getDeclaredMethod(
      "executeTool",
      xyz.block.trailblaze.toolcalls.TrailblazeTool::class.java,
      TrailblazeToolExecutionContext::class.java,
      MutableList::class.java,
    ).apply { isAccessible = true }

    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = testDeviceId,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(
          sessionId = SessionId("test-session"),
          startTime = Clock.System.now(),
        )
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = agent.memory,
      maestroTrailblazeAgent = agent,
    )

    val result = executeToolMethod.invoke(agent, fakeTool, context, mutableListOf<Any>())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(executedOnHost.get()).isEqualTo(true)
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

  /**
   * Regression net for the dual-mode composition primitive routing. Before the
   * `RunYamlResponse.toolMessage` / `toolStructuredContent` wire-shape extension,
   * `mobile_listInstalledApps`, `android_sendBroadcast`, and `android_adbShell` had to be
   * short-circuited host-side because the RPC envelope discarded per-tool `Success.message`. The
   * envelope now carries those payloads, so the three primitives route through RPC like any other
   * tool — the host-side `toToolResult` reads `rpcResult.data.toolMessage` /
   * `rpcResult.data.toolStructuredContent` and packages them back into
   * `TrailblazeToolResult.Success`. This test pins that wiring against an on-device server stub.
   */
  @Test
  fun `RunYamlResponse toolMessage round-trips through executeToolViaRpc as Success message`() {
    // Before the RunYamlResponse wire-shape extension, on-device RPC of `android_adbShell` /
    // `android_sendBroadcast` / `mobile_listInstalledApps` silently dropped the per-tool
    // Success.message — `prefersHostSideForCallback` was the workaround forcing them host-side.
    // With `toolMessage` on the envelope, the host's `toToolResult` reads it and packages it back
    // into `TrailblazeToolResult.Success.message`. This test pins that wiring.
    val deviceStdout = "uid=2000(shell) gid=2000(shell) groups=2000(shell)"
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        "{\"sessionId\":\"test-session\",\"success\":true,\"toolMessage\":\"$deviceStdout\"}"
    }

    val agent = createAgent()
    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(AdbShellTrailblazeTool(command = listOf("id"))),
        elementComparator = NoopElementComparator,
      )
    }

    val success = result.result as? TrailblazeToolResult.Success
      ?: error("Expected Success, got ${result.result}")
    assertThat(success.message).isEqualTo(deviceStdout)
    // Confirm we actually went through the RPC dispatch path (not silently host-routed).
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).hasSize(1)
  }

  @Test
  fun `RunYamlResponse toolStructuredContent round-trips through executeToolViaRpc`() {
    // Structured-content path mirrors message — when the on-device tool returns a typed JSON
    // payload (today: MCP scripted tools / on-device QuickJS bundles), the host receives it on
    // `Success.structuredContent` so the TS SDK's `client.tools.<name>` proxy can unwrap it.
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"test-session","success":true,"toolStructuredContent":{"appIds":["com.example.a","com.example.b"]}}"""
    }

    val agent = createAgent()
    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(ListInstalledAppsTrailblazeTool),
        elementComparator = NoopElementComparator,
      )
    }

    val success = result.result as? TrailblazeToolResult.Success
      ?: error("Expected Success, got ${result.result}")
    val structured = success.structuredContent
      ?: error("Expected structuredContent, got null")
    // Cheap pin without re-deserializing: the JsonElement's textual form is byte-equal to the
    // payload the mock server returned. If the value or the field ever stops round-tripping, this
    // assertion shifts and the failure points at the wire mapping rather than a downstream parse.
    assertThat(structured.toString())
      .isEqualTo("""{"appIds":["com.example.a","com.example.b"]}""")
  }

  @Test
  fun `RunYamlResponse round-trips both toolMessage and toolStructuredContent together`() {
    // Pin: when an on-device tool's Success carries BOTH a `message` (human-readable string) AND
    // a `structuredContent` (typed JSON payload) — which is the legitimate shape for MCP scripted
    // tools whose handler returns a typed result alongside a textual narrative — the host's
    // `toToolResult` must mirror both fields, not have one shadow the other in the wire mapping.
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"test-session","success":true,"toolMessage":"Found 2 installed apps","toolStructuredContent":{"appIds":["com.example.a","com.example.b"]}}"""
    }

    val agent = createAgent()
    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(ListInstalledAppsTrailblazeTool),
        elementComparator = NoopElementComparator,
      )
    }

    val success = result.result as? TrailblazeToolResult.Success
      ?: error("Expected Success, got ${result.result}")
    assertThat(success.message).isEqualTo("Found 2 installed apps")
    val structured = success.structuredContent
      ?: error("Expected structuredContent, got null")
    assertThat(structured.toString())
      .isEqualTo("""{"appIds":["com.example.a","com.example.b"]}""")
  }

  // ── OtherTrailblazeTool repo-resolution coverage (PR #3541 lead-dev #3) ───────────────────
  //
  // `HostOnDeviceRpcTrailblazeAgent.executeTool` resolves `OtherTrailblazeTool` placeholders
  // through the session's `trailblazeToolRepo` before the type-discriminating `when`. The
  // tests below cover the three behavioral branches the resolution introduces:
  //   1. Registered dynamic-tool name → repo lookup returns concrete tool → dispatched.
  //   2. Unknown name → repo lookup throws → fall through to else-branch with improved error.
  //   3. `trailblazeToolRepo == null` → early-return with original tool → unsupported-tool error.
  // Drift in either the resolution try/catch or the else-branch error message surfaces here.

  @Test
  fun `executeTool resolves OtherTrailblazeTool via toolRepo when name is registered as dynamic tool`() {
    // Pin: an OtherTrailblazeTool whose name matches a registered DynamicTrailblazeToolRegistration
    // gets routed to that registration's decoded tool, which (because it's a HostLocalExecutableTool)
    // dispatches host-local. Before #3541's fix, the OtherTrailblazeTool fell into the `else`
    // branch and surfaced as "Unsupported tool type for RPC execution: OtherTrailblazeTool".
    val executedOnHost = AtomicBoolean(false)
    val registration = FakeDynamicToolRegistration(
      registeredName = "fake_dynamic_tool",
      decodedTool = FakeHostLocalTool(executedOnHost),
    )
    val toolRepo = xyz.block.trailblaze.toolcalls.TrailblazeToolRepo(
      trailblazeToolSet = xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "test",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
      ),
      toolSetCatalog = null,
    ).apply { addDynamicTools(listOf(registration)) }

    val agent = createAgent(trailblazeToolRepo = toolRepo)
    val otherTool = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
      toolName = "fake_dynamic_tool",
      raw = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    val executeToolMethod = agent.javaClass.getDeclaredMethod(
      "executeTool",
      xyz.block.trailblaze.toolcalls.TrailblazeTool::class.java,
      TrailblazeToolExecutionContext::class.java,
      MutableList::class.java,
    ).apply { isAccessible = true }
    val context = minimalExecutionContext(agent)
    val result = executeToolMethod.invoke(agent, otherTool, context, mutableListOf<Any>())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(executedOnHost.get()).isEqualTo(true)
    // No RPC traffic — the resolved tool was host-local, dispatched via `executeHostLocalWithLogging`.
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

  @Test
  fun `executeTool surfaces improved error with toolName when OtherTrailblazeTool name is unknown to repo`() {
    // Pin: when repo lookup throws (`IllegalStateException` "Could not find Trailblaze tool for
    // name: X" from `toolCallToTrailblazeTool`'s `error(...)` branch), the catch block falls
    // through with the original `OtherTrailblazeTool`, the `when` lands in the `else` branch,
    // and the FatalError message includes the unresolved tool's `toolName` plus the
    // "not registered ... as a class-backed, YAML-defined, or dynamic scripted tool" hint.
    // Drift on either the toolName surface or the prose would make CI K1-style failures
    // harder to triage.
    val toolRepo = xyz.block.trailblaze.toolcalls.TrailblazeToolRepo(
      trailblazeToolSet = xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "test",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
      ),
      toolSetCatalog = null,
    )

    val agent = createAgent(trailblazeToolRepo = toolRepo)
    val otherTool = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
      toolName = "totally_unknown_tool",
      raw = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    val executeToolMethod = agent.javaClass.getDeclaredMethod(
      "executeTool",
      xyz.block.trailblaze.toolcalls.TrailblazeTool::class.java,
      TrailblazeToolExecutionContext::class.java,
      MutableList::class.java,
    ).apply { isAccessible = true }
    val context = minimalExecutionContext(agent)
    val result = executeToolMethod.invoke(agent, otherTool, context, mutableListOf<Any>())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.FatalError::class)
    val message = (result as TrailblazeToolResult.Error.FatalError).errorMessage
    // toolName visible (the K1-style triage signal a triager needs).
    assertThat(message).contains("toolName='totally_unknown_tool'")
    // Precise taxonomy (matches the parallel diagnostic in `MaestroTrailblazeAgent`).
    assertThat(message).contains("class-backed, YAML-defined, or dynamic scripted tool")
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

  @Test
  fun `executeTool with null trailblazeToolRepo passes OtherTrailblazeTool through to else-branch error`() {
    // Pin the `repo == null` early-return path. Without a configured tool repo, the agent
    // can't resolve any OtherTrailblazeTool — but it must still surface a clear error
    // rather than throw NPE or silently succeed. The else-branch diagnostic still includes
    // the toolName so a developer running outside a fully-wired session (unit-test fixture,
    // ad-hoc REPL) gets the same triage signal as a production failure.
    val agent = createAgent(trailblazeToolRepo = null)
    val otherTool = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
      toolName = "would_resolve_in_real_session",
      raw = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    val executeToolMethod = agent.javaClass.getDeclaredMethod(
      "executeTool",
      xyz.block.trailblaze.toolcalls.TrailblazeTool::class.java,
      TrailblazeToolExecutionContext::class.java,
      MutableList::class.java,
    ).apply { isAccessible = true }
    val context = minimalExecutionContext(agent)
    val result = executeToolMethod.invoke(agent, otherTool, context, mutableListOf<Any>())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.FatalError::class)
    val message = (result as TrailblazeToolResult.Error.FatalError).errorMessage
    assertThat(message).contains("toolName='would_resolve_in_real_session'")
    assertThat(message).contains("Unsupported tool type for RPC execution")
  }

  /**
   * Builds a minimal [TrailblazeToolExecutionContext] for direct `executeTool` invocation —
   * mirrors the context shape the production runner threads through, just enough to satisfy
   * `executeHostLocalWithLogging` and the `else`-branch fatal-error path.
   */
  private fun minimalExecutionContext(
    agent: HostOnDeviceRpcTrailblazeAgent,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = testDeviceId,
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = agent.memory,
    maestroTrailblazeAgent = agent,
  )

  /**
   * Minimal [DynamicTrailblazeToolRegistration] for tests. `decodeToolCall` returns the
   * pre-built [decodedTool] verbatim (ignoring the args JSON) so callers can plant a
   * [HostLocalExecutableTrailblazeTool] under any name and assert dispatch.
   */
  private class FakeDynamicToolRegistration(
    registeredName: String,
    private val decodedTool: xyz.block.trailblaze.toolcalls.TrailblazeTool,
  ) : xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration {
    override val name: xyz.block.trailblaze.toolcalls.ToolName =
      xyz.block.trailblaze.toolcalls.ToolName(registeredName)
    override val trailblazeDescriptor: xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor =
      xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor(
        name = registeredName,
        description = "fake test tool",
        requiredParameters = emptyList(),
        optionalParameters = emptyList(),
      )

    override fun buildKoogTool(
      trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
    ): xyz.block.trailblaze.toolcalls.TrailblazeKoogTool<out xyz.block.trailblaze.toolcalls.TrailblazeTool> =
      error("buildKoogTool not used in these tests — executeTool dispatches via decodeToolCall")

    override fun decodeToolCall(argumentsJson: String): xyz.block.trailblaze.toolcalls.TrailblazeTool =
      decodedTool
  }

  private class FakeHostLocalTool(
    private val executedOnHost: AtomicBoolean,
  ) : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String = "fake_host_local_tool"
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      executedOnHost.set(true)
      return TrailblazeToolResult.Success()
    }
  }

  // ── Per-branch logging coverage (#2935 review follow-up) ──────────────────────────────────
  //
  // `BaseTrailblazeAgent` already pins the contract that every top-level
  // `HostLocalExecutableTrailblazeTool` emits a `TrailblazeToolLog`. These tests cover the
  // *other* host-local paths this agent introduces — the `requiresHostInstance` branch in
  // `executeTool` plus the `DelegatingTrailblazeTool` sub-tool host-local expansion — so a future
  // refactor of `executeHostLocalWithLogging` can't silently regress logging for `cbot` /
  // `dip-slot` / `requires_host: true` YAML tools.

  @Test
  fun `delegating sub-tool host-local branch emits a TrailblazeToolLog per dispatched sub-tool`() {
    // Pin: when a `DelegatingTrailblazeTool` expands into a host-local sub-tool, the sub-tool's
    // dispatch must produce a `TrailblazeToolLog`. The base agent's short-circuit catches only
    // top-level host-local tools; delegating-expansion sub-tools route through this agent's
    // `executeTool` override, which is the other surface `executeHostLocalWithLogging` covers.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val executedOnHost = AtomicBoolean(false)
    val hostLocalSubTool = FakeHostLocalTool(executedOnHost)
    val delegating = object : xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool {
      override fun toExecutableTrailblazeTools(
        executionContext: TrailblazeToolExecutionContext,
      ): List<xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool> = listOf(hostLocalSubTool)
    }

    runBlocking {
      agent.runTrailblazeTools(tools = listOf(delegating), elementComparator = NoopElementComparator)
    }

    assertThat(executedOnHost.get()).isEqualTo(true)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("fake_host_local_tool")
    // The sub-tool ran host-side; nothing should have gone over RPC for this dispatch.
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

  @Test
  fun `top-level HostLocal dispatched through this agent emits exactly one TrailblazeToolLog`() {
    // Pin: even though `BaseTrailblazeAgent`'s short-circuit catches top-level host-local
    // dispatches, the host-local branch this agent's `executeTool` keeps (for defensive
    // belt-and-suspenders coverage when something dispatches via `executeTool` directly)
    // must also log and must not double-log when both paths could fire.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val executedOnHost = AtomicBoolean(false)
    val tool = FakeHostLocalTool(executedOnHost)

    runBlocking {
      agent.runTrailblazeTools(tools = listOf(tool), elementComparator = NoopElementComparator)
    }

    assertThat(executedOnHost.get()).isEqualTo(true)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    // Exactly one — not zero (regression vector for the gate-removed-from-base-agent case) and
    // not two (regression vector for the base short-circuit + agent override both firing).
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("fake_host_local_tool")
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

  // ── On-device double-log fix (#3818) ──────────────────────────────────────────────────────
  //
  // An RPC-routed tool is logged on-device (its `TrailblazeToolLog` is pulled back to the host
  // and merged into the same session). Before the fix, `executeToolViaRpc` ALSO emitted a
  // host-side `TrailblazeToolLog` for every dispatch, so the single execution rendered twice in
  // the report. The device now reports how many tool logs it emitted via
  // `RunYamlResponse.onDeviceToolLogCount`; the host skips its own emit when that count is > 0
  // and keeps it (the recording catch-all) when the device logged nothing.

  @Test
  fun `host skips its tool-log emit when device reports it already logged the tool`() {
    // Device reports it emitted a tool log for this dispatch — the host must NOT emit a second
    // one, or the tool double-renders in the report (the #3818 bug).
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true,"onDeviceToolLogCount":1}"""
    }

    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    // The on-device dispatch already logged the tool; the host adds nothing.
    assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).isEmpty()
  }

  @Test
  fun `host emits its catch-all tool-log when device reports it logged nothing`() {
    // Device reports zero tool logs (the bypass case: e.g. an accessibility nodeSelector tap
    // that only produces driver-action logs). The host must keep emitting so the dispatch
    // stays visible to recording / reports.
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true,"onDeviceToolLogCount":0}"""
    }

    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    // Exactly one host-side tool log — the recording catch-all is preserved.
    assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).hasSize(1)
  }

  @Test
  fun `host treats an absent onDeviceToolLogCount as zero and emits its catch-all`() {
    // Back-compat / default: a response omitting `onDeviceToolLogCount` decodes to 0, so the
    // host keeps its catch-all emit (the safe default — worst case is the prior duplicate, never
    // a dropped recording).
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true}"""
    }

    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).hasSize(1)
  }

  @Test
  fun `host honors the device count from the retry response after a re-warm`() {
    // The count must be read off the RETRY response too, not just the first attempt. First call
    // 500s (forcing the re-warm path), retry succeeds reporting it logged the tool — the host
    // must still skip its own emit. Without reading `retry.data.onDeviceToolLogCount`, the host
    // would double-log every tool that needed a re-warm.
    val attempts = AtomicInteger(0)
    mockServer.onPost("/rpc/RunYamlRequest") {
      if (attempts.incrementAndGet() == 1) {
        HttpStatusCode.InternalServerError to
          """{"errorType":"NETWORK_ERROR","message":"Network error during RPC call","details":"connect timeout"}"""
      } else {
        HttpStatusCode.OK to """{"sessionId":"test-session","success":true,"onDeviceToolLogCount":1}"""
      }
    }
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.OK to
        """{"viewHierarchy":{},"screenshotBase64":null,"deviceWidth":1080,"deviceHeight":1920}"""
    }

    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking {
      agent.runMaestroCommands(commands, TraceId.generate(TraceId.Companion.TraceOrigin.TOOL))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).hasSize(2)
    // Retry reported a device log → host defers, no duplicate.
    assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).isEmpty()
  }

  @Test
  fun `host skips its tool-log emit when there is no traceId to attach`() {
    // The gate is `traceId != null && count == 0`. With a null traceId there's no trace to hang
    // the log on, so the host never emits regardless of the device count. Pins the null-traceId
    // half of the gate (the existing tests all pass a generated traceId).
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"test-session","success":true,"onDeviceToolLogCount":0}"""
    }

    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val commands = listOf(TapOnElementCommand(selector = ElementSelector(textRegex = ".*OK.*")))
    val result = runBlocking { agent.runMaestroCommands(commands, traceId = null) }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).isEmpty()
  }

  /**
   * Regression net: scripted tools dispatched through this agent get
   * `ctx.target.resolveAppId()` populated. The bug this guards against was the agent's
   * constructor silently dropping `resolvedTarget` / `appId` (it extended
   * `MaestroTrailblazeAgent` without forwarding those super-ctor params), so
   * `MaestroTrailblazeAgent.buildExecutionContext` always built a
   * `TrailblazeToolExecutionContext` with both fields null. The envelope writer
   * (`TrailblazeContextEnvelope.buildMetaTrailblaze`) then wrote no `target` block into
   * `_meta.trailblaze`, and TS handlers calling `ctx.target.resolveAppId()` crashed with
   * `ctx.target is undefined` on the very first line.
   *
   * Checks the public `MaestroTrailblazeAgent.resolvedTarget` / `.appId` properties
   * — those are the storage that `buildExecutionContext` reads when constructing the per-call
   * context, so a constructor that fails to forward them shows up here as nulls. The
   * downstream property-to-context wiring inside `MaestroTrailblazeAgent.buildExecutionContext`
   * pre-existed this PR (it's unconditional) and is covered end-to-end by the sample-app's
   * `assertResolvedAppId` trail in CI.
   */
  @Test
  fun `agent forwards resolvedTarget and appId from constructor to MaestroTrailblazeAgent super`() {
    val fakeTarget = FakeTrailblazeHostAppTarget(
      id = "test-target",
      androidAppIds = listOf("com.example.testtarget", "com.example.testtarget.dev"),
    )
    val resolvedTarget = xyz.block.trailblaze.model.ResolvedTarget(
      target = fakeTarget,
      deviceId = testDeviceId,
    )
    val agent = createAgent(
      resolvedTarget = resolvedTarget,
      appId = "com.example.testtarget",
    )

    // Both fields must propagate to the MaestroTrailblazeAgent super-constructor properties.
    // If either is null here, the agent dropped them in its constructor and downstream
    // `buildExecutionContext` will produce contexts with `resolvedTarget = null`.
    assertThat(agent.resolvedTarget).isNotNull()
    assertThat(agent.resolvedTarget?.id).isEqualTo("test-target")
    assertThat(agent.appId).isEqualTo("com.example.testtarget")
  }

  /**
   * Default values for `resolvedTarget` and `appId` are preserved (both null) when
   * the constructor's defaults are used. Belt-and-suspenders against a future "remove the
   * defaults" refactor that requires every caller to pass both — existing call sites that
   * legitimately don't have a target (e.g. ad-hoc tool dispatch outside of a target-scoped
   * trail) need the null path to keep working.
   */
  @Test
  fun `agent defaults resolvedTarget and appId to null when constructor args omitted`() {
    val agent = createAgent()
    assertThat(agent.resolvedTarget).isNull()
    assertThat(agent.appId).isNull()
  }

  /**
   * Integration regression net: dispatches a host-local scripted-tool surrogate through the
   * agent's `runTrailblazeTools` entry point, captures the `TrailblazeToolExecutionContext`
   * the agent built for the call, and verifies that the same fields a TS handler reads via
   * `ctx.target.resolveAppId()` end up in `_meta.trailblaze.target` when
   * `TrailblazeContextEnvelope.buildMetaTrailblaze` writes the envelope for that dispatch.
   *
   * Exercises the full chain this PR's regression fix covers, hermetically (no esbuild, no
   * daemon, no device):
   *   1. agent constructor → `MaestroTrailblazeAgent` super-ctor properties
   *   2. super properties → `MaestroTrailblazeAgent.buildExecutionContext` → context fields
   *   3. context fields → `TrailblazeContextEnvelope.buildMetaTrailblaze` → JSON envelope
   *
   * If step 1 breaks (the regression class this PR fixes), the captured context has
   * `resolvedTarget = null` and the first assertion fails. If steps 2 or 3 break, the
   * envelope's `target` block is missing or malformed and the later assertions catch it.
   */
  @Test
  fun `dispatched host-local tool sees resolvedTarget and produces target envelope block`() {
    val fakeTarget = FakeTrailblazeHostAppTarget(
      id = "test-target",
      androidAppIds = listOf("com.example.testtarget", "com.example.testtarget.dev"),
    )
    val resolvedTarget = xyz.block.trailblaze.model.ResolvedTarget(
      target = fakeTarget,
      deviceId = testDeviceId,
    )
    val agent = createAgent(
      resolvedTarget = resolvedTarget,
      appId = "com.example.testtarget",
    )

    val capturedCtx = java.util.concurrent.atomic.AtomicReference<TrailblazeToolExecutionContext?>()
    val capturingTool = CapturingHostLocalTool(capturedCtx)

    runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(capturingTool),
        elementComparator = NoopElementComparator,
      )
    }

    // Step 1 + 2: the context the agent built for this dispatch has both fields propagated
    // through from the constructor.
    val ctx = checkNotNull(capturedCtx.get()) { "capturing tool did not receive a context" }
    assertThat(ctx.resolvedTarget).isNotNull()
    assertThat(ctx.resolvedTarget?.id).isEqualTo("test-target")
    assertThat(ctx.appId).isEqualTo("com.example.testtarget")

    // Step 3: the envelope writer projects those fields onto `_meta.trailblaze.target` — the
    // shape the TS SDK's `fromMeta` reads when constructing `ctx.target` in scripted-tool
    // handlers. A future regression in the envelope writer (dropping the target block,
    // misnaming fields) fails the JSON-shape assertions below.
    val envelope = xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope.buildMetaTrailblaze(
      context = ctx,
      baseUrl = "http://localhost:0/test",
      sessionId = SessionId("envelope-test"),
      invocationId = "envelope-test-invocation",
    )
    val targetBlock = envelope["target"]?.jsonObject
      ?: error("envelope did not write `target` block: $envelope")
    assertThat(targetBlock["id"]?.jsonPrimitive?.content).isEqualTo("test-target")
    assertThat(targetBlock["appId"]?.jsonPrimitive?.content).isEqualTo("com.example.testtarget")
    val appIdsArray = targetBlock["appIds"] as? kotlinx.serialization.json.JsonArray
      ?: error("envelope target block missing or non-array `appIds`: $targetBlock")
    assertThat(appIdsArray.map { it.jsonPrimitive.content })
      .isEqualTo(listOf("com.example.testtarget", "com.example.testtarget.dev"))
  }

  /**
   * Host-local tool that records the `TrailblazeToolExecutionContext` it received so the
   * surrounding test can assert on the agent-built context without reflection.
   */
  private class CapturingHostLocalTool(
    private val captured: java.util.concurrent.atomic.AtomicReference<TrailblazeToolExecutionContext?>,
  ) : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String = "capturing_host_local_tool"
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      captured.set(toolExecutionContext)
      return TrailblazeToolResult.Success()
    }
  }

  /**
   * Minimal `TrailblazeHostAppTarget` for the wiring regression test above — declares an
   * Android app-id list, returns empty for the per-driver tool-class methods that the
   * regression doesn't exercise.
   */
  private class FakeTrailblazeHostAppTarget(
    id: String,
    private val androidAppIds: List<String>,
  ) : xyz.block.trailblaze.model.TrailblazeHostAppTarget(id = id, displayName = id) {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = if (platform == TrailblazeDevicePlatform.ANDROID) androidAppIds else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<kotlin.reflect.KClass<out xyz.block.trailblaze.toolcalls.TrailblazeTool>> = emptySet()
  }

  /** Lightweight stub for tests that don't exercise element comparison — every method errors. */
  private object NoopElementComparator : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      error("not used in this test")
    override fun evaluateString(query: String) =
      error("not used in this test")
    override fun extractNumberFromString(input: String): Double? = null
  }

  /** Element comparator that always returns the same string from [getElementValue]. */
  private class StubElementComparator(private val value: String) : ElementComparator {
    override fun getElementValue(prompt: String): String = value
    override fun evaluateBoolean(statement: String) =
      error("not used in this test")
    override fun evaluateString(query: String) =
      error("not used in this test")
    override fun extractNumberFromString(input: String): Double? = null
  }
}
