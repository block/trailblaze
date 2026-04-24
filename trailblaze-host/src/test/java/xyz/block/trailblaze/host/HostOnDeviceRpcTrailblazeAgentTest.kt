package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
import xyz.block.trailblaze.api.DriverNodeMatch
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

}
