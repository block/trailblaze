package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import xyz.block.trailblaze.AgentMemory
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
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.RunCommandTrailblazeTool
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

  private fun createClient(
    memory: AgentMemory = AgentMemory(),
    toolExecutionContextProvider: ((TraceId?) -> xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext)? = null,
  ): HostAccessibilityRpcClient =
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
      toolExecutionContextProvider = toolExecutionContextProvider,
      memory = memory,
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

  @Test
  fun `per-tool dispatch forwards traceId into RunYamlRequest`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    val result = runBlocking {
      createClient().execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        traceId,
      )
    }

    assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    val requestJson = Json.parseToJsonElement(
      mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single(),
    ).jsonObject
    assertThat(requestJson["traceId"]!!.jsonPrimitive.content).isEqualTo(traceId.traceId)
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
   * When the host doesn't recognize the tool name, the args are wrapped verbatim into an
   * [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool] with the supplied name and
   * forwarded to the device — the on-device server's repo may know the tool (YAML-defined
   * tools that aren't surfaced to the LLM, or device-only registrations). The previous
   * polymorphic-fallback could produce a blank-name `OtherTrailblazeTool` here; with the
   * routing change there's nothing to dispatch with a blank name because the toolName is
   * always taken from the input.
   */
  @Test
  fun `unknown tool wraps args verbatim and forwards to device`() {
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

    assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    assertThat(dispatched).isTrue()
    assertThat(mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty()).hasSize(1)
  }

  /**
   * Branch coverage: even when the args carry stale `class` discriminators that used to
   * confuse the polymorphic decoder, the host now ignores them — the tool is looked up
   * purely by `toolName` and falls back to a verbatim [OtherTrailblazeTool] wrap when the
   * host repo doesn't recognize it.
   */
  @Test
  fun `unknown tool with stale class discriminator still wraps and forwards`() {
    var dispatched = false
    mockServer.onPost("/rpc/RunYamlRequest") {
      dispatched = true
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val staleArgs = Json.parseToJsonElement(
      """{"class": "TapOnPointTrailblazeTool", "completelyBogusField": "xyz"}""",
    ).jsonObject

    val result = runBlocking {
      createClient().execute("totallyUnknownToolName", staleArgs, null)
    }

    assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    assertThat(dispatched).isTrue()
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

  /**
   * Regression for the host→device memory interpolation gap: a value remembered on the host
   * (typically by a host-local tool that wrote `context.memory.remember(...)` — or, in the
   * legacy on-device-RPC path, by a `MemoryTrailblazeTool`) lives only in the host's
   * [AgentMemory]. The on-device runner spins up a fresh, empty `AgentMemory` per
   * `RunYamlRequest`, so without host-side resolution, `{{var}}` / `${var}` tokens in the
   * tool args reach the device and substitute as empty strings.
   */
  @Test
  fun `string args are interpolated against host AgentMemory before RPC dispatch`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val memory = AgentMemory().apply { remember("memberPreferredName", "Cinque") }
    runBlocking {
      val result = createClient(memory).execute(
        "inputText",
        Json.parseToJsonElement("""{"text":"{{memberPreferredName}}"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    // The resolved literal must reach the device YAML; the unresolved token must not.
    assertThat(yaml).contains("Cinque")
    assertThat(yaml).doesNotContain("{{memberPreferredName}}")
  }

  /**
   * The substitution must operate on the typed JSON tree of the tool — not on the
   * already-encoded YAML string — so the YAML encoder can escape any YAML-sensitive
   * characters (`"`, `\n`, `:`, `#`) in the resolved value. Otherwise a remembered value
   * containing a colon or a quote could splice into the wire payload as raw text and
   * corrupt subsequent tool-arg parsing on the device.
   */
  @Test
  fun `interpolation preserves YAML escaping for sensitive characters in remembered values`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    // Quote, colon, hash, and newline: each is YAML-significant in scalar context.
    val tricky = "He said: \"ok\"\nNext: # not a comment"
    val memory = AgentMemory().apply { remember("greeting", tricky) }
    runBlocking {
      val result = createClient(memory).execute(
        "inputText",
        Json.parseToJsonElement("""{"text":"{{greeting}}"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    // Round-trip through the YAML decoder to verify the on-device parser would recover the
    // exact remembered string. If interpolation had spliced raw YAML, the decode would
    // either fail or produce a different string.
    val toolItems = xyz.block.trailblaze.yaml.createTrailblazeYaml().decodeTrail(yaml)
    val toolItem =
      toolItems.single() as xyz.block.trailblaze.yaml.TrailYamlItem.ToolTrailItem
    val recovered = toolItem.tools.single().trailblazeTool as
      xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
    assertThat(recovered.text).isEqualTo(tricky)
  }

  /**
   * Pins the V3 wiring contract from [TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml]:
   * the runner constructs one [AgentMemory], hands it to both the executor and to every
   * [TrailblazeToolExecutionContext], then host-local tools write into it via
   * `context.memory.remember(...)` — after the client has been constructed. Subsequent RPC
   * tool dispatches must see those writes. A future refactor that copied the memory at
   * construction would silently re-break the V3 fix without any other test failing.
   */
  @Test
  fun `value remembered after client construction is visible to subsequent RPC dispatch`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val sharedMemory = AgentMemory()
    val client = createClient(sharedMemory)
    // Simulate a host-local tool writing through `context.memory` AFTER the client was built.
    sharedMemory.remember("memberPreferredName", "Cinque")

    runBlocking {
      val result = client.execute(
        "inputText",
        Json.parseToJsonElement("""{"text":"{{memberPreferredName}}"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    assertThat(yaml).contains("Cinque")
    assertThat(yaml).doesNotContain("{{memberPreferredName}}")
  }

  /**
   * Pins the host-only interpolation contract: `RunCommandTrailblazeTool` is `requiresHost = true`
   * and does NOT self-interpolate its `command` field. Without the host-side resolution step,
   * a `{{var}}` token in `command` would reach `sh -c` verbatim. The fact that
   * `interpolateMemoryInTool` rewrites the typed tool's string field is what makes the host-only
   * branch in `execute` honor memory tokens — same as the RPC branch.
   */
  @Test
  fun `host-only tool string fields are interpolated by interpolateMemoryInTool`() {
    val memory = AgentMemory().apply { remember("name", "world") }
    val original = RunCommandTrailblazeTool(command = "echo {{name}}")
    val resolved = interpolateMemoryInTool(original, memory) as RunCommandTrailblazeTool
    assertThat(resolved.command).isEqualTo("echo world")
  }

  @Test
  fun `interpolateMemoryInTool resolves multiple tokens in a single string`() {
    val memory = AgentMemory().apply {
      remember("first", "Hello")
      remember("second", "World")
    }
    val resolved = interpolateMemoryInTool(
      InputTextTrailblazeTool(text = "{{first}}, {{second}}!"),
      memory,
    ) as InputTextTrailblazeTool
    assertThat(resolved.text).isEqualTo("Hello, World!")
  }

  @Test
  fun `interpolateMemoryInTool supports the dollar-brace syntax`() {
    val memory = AgentMemory().apply { remember("name", "Cinque") }
    val resolved = interpolateMemoryInTool(
      InputTextTrailblazeTool(text = "\${name}"),
      memory,
    ) as InputTextTrailblazeTool
    assertThat(resolved.text).isEqualTo("Cinque")
  }

  /**
   * AgentMemory.interpolateVariables resolves unknown tokens to the empty string. Pin that
   * behavior for the boundary path so a future change to "leave the raw token in place"
   * surfaces as a test diff rather than silent data drift.
   */
  @Test
  fun `interpolateMemoryInTool resolves unknown tokens to empty string`() {
    val memory = AgentMemory().apply { remember("known", "value") }
    val resolved = interpolateMemoryInTool(
      InputTextTrailblazeTool(text = "[{{unknown}}]"),
      memory,
    ) as InputTextTrailblazeTool
    assertThat(resolved.text).isEqualTo("[]")
  }

  /**
   * `OtherTrailblazeTool` has a `raw: JsonObject` field that holds opaque tool-specific args.
   * Interpolation must walk into the nested JSON tree and resolve string scalars there too —
   * otherwise yaml-recorded tools (e.g. `tapOnElementBySelector`) that go through the
   * polymorphic-fallback path would skip interpolation entirely.
   */
  // Note: a full host-only e2e through `client.execute(...)` would require a tool that is
  // both `isForLlm = true` (so the toolRepo's class-name lookup can find it — see
  // [TrailblazeKoogToolExt.toKoogToolDescriptor]'s null-return on isForLlm=false) AND
  // `requiresHost = true`. No such tool exists in the codebase today, and inventing a
  // test-only one would require @Serializable plumbing. The host-only branch correctness is
  // covered by:
  //   1. `host-only tool string fields are interpolated by interpolateMemoryInTool` —
  //      proves the resolution step rewrites the typed tool's string fields.
  //   2. The code structure of `HostAccessibilityRpcClient.execute`, which captures
  //      `val resolvedTool = interpolateMemoryInTool(...)` ABOVE the host-only branch and
  //      passes the same variable to `resolvedTool.execute(context)` on that branch.
  // If those two ever drift, the unit test catches the drift in interpolation; the code
  // structure ensures the host-only branch sees the resolved value.
  @Test
  fun `interpolateMemoryInTool walks into OtherTrailblazeTool raw json tree`() {
    val memory = AgentMemory().apply { remember("label", "Catalog") }
    val raw = Json.parseToJsonElement(
      """{"selector":{"textRegex":"{{label}}","keep":42}}""",
    ).jsonObject
    val original = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
      toolName = "tapOnElementBySelector",
      raw = raw,
    )

    val resolved = interpolateMemoryInTool(original, memory) as
      xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
    val textRegex = resolved.raw["selector"]!!.jsonObject["textRegex"]!!.jsonPrimitive.content
    assertThat(textRegex).isEqualTo("Catalog")
    // Non-string scalars in nested objects must be left alone.
    val keep = resolved.raw["selector"]!!.jsonObject["keep"]!!.jsonPrimitive.content
    assertThat(keep).isEqualTo("42")
  }

  /**
   * Empty memory is the typical case (most trails don't use `rememberWithAi`/`rememberText`/
   * `rememberNumber`). The interpolation path must early-exit so steady-state trails pay
   * nothing for the new code.
   */
  @Test
  fun `empty memory leaves tool args untouched`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    runBlocking {
      val result = createClient(AgentMemory()).execute(
        "inputText",
        Json.parseToJsonElement("""{"text":"hello world"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val yaml = Json.parseToJsonElement(body).jsonObject["yaml"]!!.jsonPrimitive.content
    assertThat(yaml).contains("hello world")
  }

  /**
   * Bidirectional memory sync — outbound half: the host's `AgentMemory` is serialized into the
   * `RunYamlRequest.memorySnapshot` field so on-device tools can read keys directly (the
   * boundary pre-resolution above only covers `{{var}}` interpolation in tool args).
   */
  @Test
  fun `dispatched RunYamlRequest carries the host memory snapshot`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to runYamlSuccessBody()
    }

    val sharedMemory = AgentMemory().apply {
      remember("merchantToken", "tok_abc123")
      remember("personToken", "ptok_xyz")
    }
    runBlocking {
      val result = createClient(sharedMemory).execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    val body = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().single()
    val sentSnapshot = Json.parseToJsonElement(body)
      .jsonObject["memorySnapshot"]!!.jsonObject
    assertThat(sentSnapshot["merchantToken"]!!.jsonPrimitive.content).isEqualTo("tok_abc123")
    assertThat(sentSnapshot["personToken"]!!.jsonPrimitive.content).isEqualTo("ptok_xyz")
  }

  /**
   * Bidirectional memory sync — inbound half: when the device's RPC response carries a
   * `memorySnapshot`, the host replaces its `AgentMemory` with it. This is the path that
   * makes writes from on-device tools (Kotlin or scripted TS handlers) visible to the next
   * host-side or RPC dispatch.
   */
  @Test
  fun `device response memory snapshot replaces host memory on success`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"host-top-level-session","success":true,""" +
        """"memorySnapshot":{"deviceWroteThis":"value-from-device","preserved":"still-here"}}"""
    }

    val sharedMemory = AgentMemory().apply {
      remember("preserved", "still-here")
      remember("willBeReplaced", "old-value")
    }
    runBlocking {
      val result = createClient(sharedMemory).execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        null,
      )
      assertThat(result).isInstanceOf(ExecutionResult.Success::class)
    }

    // Full-state replace: device's snapshot wins. Keys present only on the host vanish
    // (deletes via absence); keys the device wrote appear; carried-through keys stay.
    assertThat(sharedMemory.variables["deviceWroteThis"]).isEqualTo("value-from-device")
    assertThat(sharedMemory.variables["preserved"]).isEqualTo("still-here")
    assertThat(sharedMemory.variables.containsKey("willBeReplaced")).isFalse()
  }

  /**
   * The host always replaces its memory with the device's post-execution snapshot. An
   * empty server-sent `memorySnapshot` (or the field omitted entirely — they decode to
   * the same default `emptyMap()`) means "device's memory is empty," and the host
   * mirrors that. Host and on-device runtime ship 1-to-1 so there is no
   * "older server omits the field" case to defend against.
   */
  @Test
  fun `empty memorySnapshot clears host memory`() {
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to
        """{"sessionId":"host-top-level-session","success":true,"memorySnapshot":{}}"""
    }

    val sharedMemory = AgentMemory().apply { remember("k", "v") }
    runBlocking {
      createClient(sharedMemory).execute(
        "pressKey",
        Json.parseToJsonElement("""{"keyCode":"BACK"}""").jsonObject,
        null,
      )
    }
    assertThat(sharedMemory.variables.isEmpty()).isTrue()
  }

  private fun runYamlSuccessBody(): String =
    """{"sessionId":"host-top-level-session","success":true}"""
}
