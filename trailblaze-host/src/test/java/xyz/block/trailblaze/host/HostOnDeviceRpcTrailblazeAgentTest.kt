package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
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
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
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

  private fun createAgent(): HostOnDeviceRpcTrailblazeAgent {
    return HostOnDeviceRpcTrailblazeAgent(
      rpcClient = rpcClient,
      runYamlRequestTemplate = testRunYamlRequest,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
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
    )
  }

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
