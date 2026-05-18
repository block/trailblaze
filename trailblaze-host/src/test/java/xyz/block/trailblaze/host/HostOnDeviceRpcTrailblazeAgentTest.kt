package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
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
import xyz.block.trailblaze.mobile.tools.AndroidSendBroadcastTrailblazeTool
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
   * Regression for the host→device memory interpolation gap (SQATB-336): a value remembered
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
   * Regression net for the `prefersHostSideForCallback` allowlist. The whole `mcp_servers:`
   * migration (PR #2344) relies on `mobile_listInstalledApps`, `android_sendBroadcast`, and
   * `android_adbShell` taking the host-side branch in `executeTool` — the on-device-RPC return path
   * silently drops `TrailblazeToolResult.Success.message`, so any tool whose return value is
   * the contract MUST be host-routed. A future refactor (annotation rename, allowlist string
   * typo, branch removal) could silently flip these back to RPC and re-introduce the
   * `JSON.parse(undefined)` failure in TS scripted-tool handlers. This test fails loud the
   * moment that happens.
   */
  @Test
  fun `prefersHostSideForCallback routes the three composition primitives host-side`() {
    val agent = createAgent()
    // Positive cases — the three tools the migration's callback chain depends on.
    assertThat(agent.prefersHostSideForCallback(ListInstalledAppsTrailblazeTool)).isEqualTo(true)
    assertThat(
      agent.prefersHostSideForCallback(
        AndroidSendBroadcastTrailblazeTool(
          action = "com.example.ACTION",
          componentPackage = "com.example",
          componentClass = "com.example.Receiver",
        ),
      ),
    )
      .isEqualTo(true)
    assertThat(agent.prefersHostSideForCallback(AdbShellTrailblazeTool(command = listOf("id"))))
      .isEqualTo(true)
  }

  @Test
  fun `prefersHostSideForCallback returns false for tools that should still route via RPC`() {
    val agent = createAgent()
    // Negative case — an unrelated tool must still go through the RPC path so action-style
    // tools (tap, swipe, inputText, etc.) keep their existing behaviour.
    assertThat(agent.prefersHostSideForCallback(InputTextTrailblazeTool(text = "anything")))
      .isEqualTo(false)
  }

  @Test
  fun `prefersHostSideForCallback reads the annotation rather than a hard-coded allowlist`() {
    // Pins the migration from a hard-coded set in this agent to the
    // `@TrailblazeToolClass(prefersHostSideForCallback = true)` annotation. Adding a new
    // dual-mode primitive should only require the annotation — the predicate must not need
    // a parallel edit here. Sanity-check by reading the annotation directly on the three
    // known primitives and asserting they all opt in.
    val annotated = listOf(
      ListInstalledAppsTrailblazeTool::class,
      AndroidSendBroadcastTrailblazeTool::class,
      AdbShellTrailblazeTool::class,
    )
    annotated.forEach { kClass ->
      val annotation = kClass.java
        .getAnnotation(xyz.block.trailblaze.toolcalls.TrailblazeToolClass::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation!!.prefersHostSideForCallback)
        .isEqualTo(true)
    }
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

  /**
   * Test stand-in for the dual-mode composition primitive path. Annotated with
   * `@TrailblazeToolClass(prefersHostSideForCallback = true)` so [HostOnDeviceRpcTrailblazeAgent
   * .prefersHostSideForCallback]'s annotation-flag predicate matches and routes the dispatch
   * through `executeHostLocalWithLogging` — exercising the same code path as the real
   * `AdbShellTrailblazeTool` without touching `AndroidDeviceCommandExecutor` /
   * `AndroidHostAdbUtils`. The real tool's `execute` reaches dadb over the wire on a host
   * without a live ADB server, which would hang or flake unit tests in CI environments.
   *
   * Name is intentionally test-specific (`fake_prefers_host_side`) rather than matching one
   * of the production primitives — the annotation flag is what the predicate reads, the
   * tool name no longer participates in routing after the annotation-driven refactor.
   */
  @TrailblazeToolClass(name = "fake_prefers_host_side", prefersHostSideForCallback = true)
  private class FakePrefersHostSideTool : xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  // ── Per-branch logging coverage (#2935 review follow-up) ──────────────────────────────────
  //
  // `BaseTrailblazeAgent` already pins the contract that every top-level
  // `HostLocalExecutableTrailblazeTool` emits a `TrailblazeToolLog`. These tests cover the
  // *other* host-local paths this agent introduces — the `requiresHostInstance` branch and the
  // `prefersHostSideForCallback` branch in `executeTool`, plus the `DelegatingTrailblazeTool`
  // sub-tool host-local expansion — so a future refactor of `executeHostLocalWithLogging` can't
  // silently regress logging for `cbot` / `dip-slot` / `requires_host: true` YAML tools or the
  // dual-mode composition primitives.

  @Test
  fun `prefersHostSideForCallback branch emits a TrailblazeToolLog and bypasses RPC`() {
    // Pin: a tool annotation-named `android_adbShell` (one of the three allowlisted composition
    // primitives) dispatched at the top level routes through `executeHostLocalWithLogging` and
    // produces a log entry with the correct tool name. The `RecordingRpcServer` would observe a
    // `/rpc/RunYamlRequest` POST if we accidentally fell through to the RPC branch — the
    // assertion that the request log is empty is what defends the routing.
    //
    // Uses [FakePrefersHostSideTool] (annotated `@TrailblazeToolClass(prefersHostSideForCallback
    // = true)` so the annotation-flag predicate matches) instead of the real
    // `AdbShellTrailblazeTool` — the real tool's `execute` reaches dadb over the wire, which
    // would hang or flake on a host without a live ADB server. Codex P2 from the PR review.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))

    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(FakePrefersHostSideTool()),
        elementComparator = NoopElementComparator,
      )
    }

    assertThat(result.result).isInstanceOf(TrailblazeToolResult::class)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("fake_prefers_host_side")
    // Defense: no RPC dispatch happened — the routing actually went host-side, not "logged AND
    // sent over the wire." A future change that double-dispatches would fail this assertion.
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
  }

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
  fun `delegating tool expanding to a prefersHostSideForCallback primitive routes host-side`() {
    // Lead-dev review #3: the top-level dispatch path checks `prefersHostSideForCallback` but
    // the delegating-subtool path also needs the check. Otherwise a delegating alias that
    // expands to one of the dual-mode composition primitives (e.g. an alias delegating to
    // `android_adbShell`) would silently RPC-route and lose `Success.message` — the on-device-RPC-
    // strips-payload gotcha this whole flag exists to prevent.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = createAgent(trailblazeLogger = capturingLogger(captured))
    val annotatedSubTool = FakePrefersHostSideTool()
    val delegating = object : xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool {
      override fun toExecutableTrailblazeTools(
        executionContext: TrailblazeToolExecutionContext,
      ): List<xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool> = listOf(annotatedSubTool)
    }

    runBlocking {
      agent.runTrailblazeTools(tools = listOf(delegating), elementComparator = NoopElementComparator)
    }

    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("fake_prefers_host_side")
    // Defense — no RPC dispatch fired. The whole point of the delegating-subtool fix is to
    // catch annotated primitives BEFORE the RPC fallthrough.
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).isEmpty()
    // dispatchedHostSide=true confirms the routing actually went through
    // `executeHostLocalWithLogging` and not some other host-local code path that happens to
    // log without setting the flag.
    assertThat(toolLogs[0].dispatchedHostSide).isEqualTo(true)
  }

  @Test
  fun `prefersHostSideForCallback toolMetadata override wins over class annotation`() {
    // Lead-dev review #2: a YAML-defined dual-mode tool should be able to opt in via its
    // per-instance `toolMetadata` rather than requiring the class-level annotation (the
    // class annotation can't carry per-instance data because YAML tools share one backing
    // class across N configs). Pins that the metadata-override path works end-to-end.
    val toolWithMetadataOverride = object : xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool {
      override val toolMetadata =
        xyz.block.trailblaze.toolcalls.TrailblazeToolMetadata(prefersHostSideForCallback = true)
      override suspend fun execute(
        toolExecutionContext: TrailblazeToolExecutionContext,
      ): TrailblazeToolResult = TrailblazeToolResult.Success()
    }
    val agent = createAgent()
    assertThat(agent.prefersHostSideForCallback(toolWithMetadataOverride)).isEqualTo(true)
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
