package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import kotlin.test.Test

/**
 * Tests for [HostAccessibilityRpcClient] using a local [MockRpcServer] to verify:
 * - Every per-tool RunYamlRequest dispatches with the host session ID as overrideSessionId
 *   (regression pin for the on-device log consolidation fix — generating a fresh
 *   per-tool session ID would silently re-scatter logs into per-tool session directories).
 * - The response's inline `success` / `errorMessage` drive [ExecutionResult] directly —
 *   there is no post-dispatch status polling.
 */
class HostAccessibilityRpcClientTest {

  private val testDeviceId =
    TrailblazeDeviceId(
      instanceId = "test-device-accessibility-rpc",
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

  private fun createClient(): HostAccessibilityRpcClient =
    HostAccessibilityRpcClient(
      rpcClient = rpcClient,
      toolRepo = TrailblazeToolRepo.withDynamicToolSets(),
      runYamlRequestTemplate = testRunYamlRequest,
      sessionProvider =
        TrailblazeSessionProvider {
          TrailblazeSession(
            sessionId = SessionId("host-top-level-session"),
            startTime = Clock.System.now(),
          )
        },
    )

  @Test
  fun `sequential per-tool dispatches reuse the host session ID as overrideSessionId`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val client = createClient()
    runBlocking {
      repeat(2) {
        val result = client.execute(
          "pressKey",
          Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
          null,
        )
        assertThat(result).isInstanceOf(ExecutionResult.Success::class)
      }
    }

    val runYamlBodies = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()
    assertThat(runYamlBodies).hasSize(2)
    runYamlBodies.forEach { body ->
      val overrideSessionId = Json.parseToJsonElement(body)
        .jsonObject["config"]!!.jsonObject["overrideSessionId"]!!.jsonPrimitive.content
      assertThat(overrideSessionId).isEqualTo("host-top-level-session")
    }
    // Regression pin: the old polling path made a GetExecutionStatusRequest per tool to
    // detect completion. The sync-response contract removes that round-trip entirely —
    // if this ever goes non-empty, the client regressed back to polling.
    assertThat(mockServer.requestLog["/rpc/GetExecutionStatusRequest"].orEmpty()).isEmpty()
  }

  /**
   * Regression test for the polymorphic-decode fallback: a recording-shaped tool call
   * (e.g. `tapOnElementBySelector` — a yaml-defined tool not present in any toolset
   * catalog entry and therefore unknown to the host's [TrailblazeToolRepo]) must still
   * dispatch via RPC to the device instead of erroring out on the host's
   * `toolCallToTrailblazeTool` lookup.
   */
  @Test
  fun `recording-shaped args for unknown tool fall back via polymorphic decode and dispatch`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    // Mirror what TrailblazeToolYamlWrapper.toJsonArgs emits for an OtherTrailblazeTool:
    // the full polymorphic shape with class discriminator + toolName + raw. This is the
    // only shape the fallback is meant to accept.
    val recordingArgs = Json.parseToJsonElement(
      """
      {
        "class": "OtherTrailblazeTool",
        "toolName": "tapOnElementBySelector",
        "raw": { "selector": { "textRegex": "Catalog" } }
      }
      """.trimIndent(),
    ).jsonObject

    val result = runBlocking {
      createClient().execute("tapOnElementBySelector", recordingArgs, null)
    }

    assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    // Verify the tool reached the device rather than failing locally.
    val runYamlBodies = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()
    assertThat(runYamlBodies).hasSize(1)
    assertThat(runYamlBodies.first()).contains("tapOnElementBySelector")
  }

  /**
   * When the tool name is unknown AND the args don't carry the expected
   * `{ class, toolName, raw }` wrapper, the polymorphic serializer can still produce an
   * `OtherTrailblazeTool` with a blank `toolName` — which would get RPC'd blank-name to
   * the device. Guard against that: the original "tool not found" lookup error must
   * surface, and nothing should be dispatched.
   */
  @Test
  fun `unknown tool with non-recording args surfaces original lookup failure without dispatch`() {
    var dispatched = false
    mockServer.onPost("/rpc/RunYamlRequest") {
      dispatched = true
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    // Plain args with no wrapper shape — typical LLM-produced tool arguments.
    val plainArgs = Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject

    val result = runBlocking {
      createClient().execute("unregisteredToolName", plainArgs, null)
    }

    assertThat(result).isInstanceOf(ExecutionResult.Failure::class)
    val failure = result as ExecutionResult.Failure
    assertThat(failure.error).contains("Could not find Trailblaze tool for name:")
    assertThat(dispatched).isFalse()
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).hasSize(0)
  }

  /**
   * Branch coverage: "unknown tool name + fallback polymorphic decode also fails" must
   * rethrow the original "tool not found" error rather than surfacing the fallback's
   * serialization error (the original is more actionable for the author).
   */
  @Test
  fun `fallback decode failure rethrows original lookup error without dispatch`() {
    var dispatched = false
    mockServer.onPost("/rpc/RunYamlRequest") {
      dispatched = true
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    // `class` points at a real registered type (TapOnPointTrailblazeTool), but the args
    // don't match its schema — the polymorphic decoder will attempt to deserialize it as
    // that type and throw a SerializationException. The tool name is unknown so the
    // typed lookup fails first; the fallback decode then fails too.
    val badFallbackArgs = Json.parseToJsonElement(
      """{"class": "TapOnPointTrailblazeTool", "completelyBogusField": "xyz"}""",
    ).jsonObject

    val result = runBlocking {
      createClient().execute("totallyUnknownToolName", badFallbackArgs, null)
    }

    assertThat(result).isInstanceOf(ExecutionResult.Failure::class)
    val failure = result as ExecutionResult.Failure
    // Original lookup error wins, not the fallback decode error.
    assertThat(failure.error).contains("Could not find Trailblaze tool for name:")
    assertThat(dispatched).isFalse()
  }

  /**
   * Branch coverage: when the typed lookup throws with a message that is NOT the
   * "tool not found" prefix (e.g. schema drift on a known tool — the args don't match
   * the tool's serializer), the fallback MUST NOT engage. The original deserialization
   * error is the useful signal for fixing the recording.
   */
  @Test
  fun `known tool with malformed args does not trigger polymorphic fallback`() {
    var dispatched = false
    mockServer.onPost("/rpc/RunYamlRequest") {
      dispatched = true
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    // `pressKey` IS a registered class-backed tool (PressKeyTrailblazeTool), so the
    // typed lookup path is taken. But the args are missing the required `keyCode` field,
    // so the typed decoder throws a SerializationException whose message does NOT
    // contain "Could not find Trailblaze tool for name:". The fallback guard must let
    // this propagate (not fall through to polymorphic decode, which would otherwise
    // dispatch a blank-name OtherTrailblazeTool).
    val malformedArgs = Json.parseToJsonElement("""{"notAValidPressKeyField":"x"}""").jsonObject

    val result = runBlocking {
      createClient().execute("pressKey", malformedArgs, null)
    }

    assertThat(result).isInstanceOf(ExecutionResult.Failure::class)
    // Original decode error — NOT the "Could not find Trailblaze tool" message from the
    // unknown-tool path.
    val failure = result as ExecutionResult.Failure
    assertThat(failure.error).contains("Tool execution failed:")
    assertThat(dispatched).isFalse()
  }

  @Test
  fun `on-device failure surfaces as ExecutionResult Failure with the device's error message`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"host-top-level-session","success":false,"errorMessage":"widget not found"}"""
    }

    val result = runBlocking {
      createClient().execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        null,
      )
    }

    assertThat(result).isInstanceOf(ExecutionResult.Failure::class)
    assertThat((result as ExecutionResult.Failure).error).contains("widget not found")
    assertThat(mockServer.requestLog["/rpc/GetExecutionStatusRequest"].orEmpty()).isEmpty()
  }

  /**
   * Defensive contract check: we sent `awaitCompletion = true` but the server returned a
   * fire-and-forget shape (no `success` field, i.e. `success == null`). That can only happen
   * if the on-device server is out of date or mis-wired — treat as a non-recoverable failure
   * rather than silently interpreting it as success.
   */
  @Test
  fun `null success on sync dispatch surfaces as non-recoverable contract violation`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"host-top-level-session"}"""
    }

    val result = runBlocking {
      createClient().execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        null,
      )
    }

    assertThat(result).isInstanceOf(ExecutionResult.Failure::class)
    val failure = result as ExecutionResult.Failure
    assertThat(failure.error).contains("contract violation")
    assertThat(failure.recoverable).isFalse()
  }

  private fun runYamlSuccessBody(): String =
    """{"sessionId":"host-top-level-session","success":true}"""
}
