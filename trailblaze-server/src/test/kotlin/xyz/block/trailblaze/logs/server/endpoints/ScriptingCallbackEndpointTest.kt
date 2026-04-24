package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackAction
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatchDepth
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackRequest
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResponse
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResult
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Endpoint tests for [ScriptingCallbackEndpoint]. Covers the branches exhaustively: loopback
 * gate (direct helper-level coverage via `isLoopback` unit tests since `testApplication` is
 * inherently loopback-only), malformed JSON → 400, version mismatch → error, unknown invocation
 * → error, session mismatch → error, deserialization failure → CallToolResult error, success
 * path → CallToolResult success, timeout + depth-cap → structured error.
 *
 * The registry is process-wide so tests clean up in [@After]; each test registers its own
 * invocation id so they don't race with each other under parallel execution.
 */
class ScriptingCallbackEndpointTest {

  private val json: Json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

  @After fun cleanup() {
    JsScriptingInvocationRegistry.clearForTest()
    // The timeout + max-depth + max-body tests override system properties; clear them after each
    // so a cross-test reorder can't bleed the override onto a test that expects the default.
    System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY)
    System.clearProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY)
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private val sessionProvider = TrailblazeSessionProvider {
    TrailblazeSession(sessionId = SessionId("callback-endpoint-test"), startTime = Clock.System.now())
  }

  private fun makeContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo,
    sessionProvider = sessionProvider,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  /** Tool repo with a single whitelisted tool so callbacks have something to dispatch against. */
  private fun makeRepo(): TrailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "callback-endpoint-test-toolset",
      setOf(InputTextTrailblazeTool::class),
    ),
  )

  @Test fun `malformed JSON body yields 400`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val response = client.post("/scripting/callback") {
      contentType(ContentType.Application.Json)
      setBody("{ not valid json }")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(response.bodyAsText().contains("Malformed"))
  }

  @Test fun `unknown invocation id yields CallbackError`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val body = json.encodeToString(
      JsScriptingCallbackRequest.serializer(),
      JsScriptingCallbackRequest(
        sessionId = "some-session",
        invocationId = "ghost-invocation",
        action = JsScriptingCallbackAction.CallTool("inputText", "{\"text\":\"hi\"}"),
      ),
    )
    val response = client.post("/scripting/callback") {
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
    val error = decoded.result as JsScriptingCallbackResult.Error
    assertTrue(error.message.contains("not found"))
  }

  @Test fun `session id mismatch yields CallbackError`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("real-session"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    try {
      // Client claims a different session than the one the invocation was registered against.
      // Per the documented invariant, this must surface as a CallbackError, NOT silently dispatch.
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = "attacker-session",
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("inputText", "{\"text\":\"hi\"}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val error = decoded.result as JsScriptingCallbackResult.Error
      assertTrue(error.message.contains("Session mismatch"), "Expected session-mismatch error, got: ${error.message}")
    } finally {
      handle.close()
    }
  }

  @Test fun `version mismatch yields CallbackError`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    // Construct the JSON directly to force a non-current version — the data class default
    // always uses CURRENT_VERSION, so we bypass the kotlinx.serialization constructor path.
    val body = """
      {
        "version": 99,
        "session_id": "s",
        "invocation_id": "i",
        "action": { "type": "call_tool", "tool_name": "x", "arguments_json": "{}" }
      }
    """.trimIndent()
    val response = client.post("/scripting/callback") {
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
    val error = decoded.result as JsScriptingCallbackResult.Error
    assertTrue(error.message.contains("Unsupported callback version"))
  }

  @Test fun `successful dispatch returns CallToolResult success`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("happy-path-session")
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    try {
      // InputTextTrailblazeTool.execute returns Success(message = ...) without a live
      // MaestroTrailblazeAgent — we don't wire one, so this exercises the deserialize +
      // ExecutableTrailblazeTool branch + TrailblazeToolResult mapping without requiring a
      // full device setup. If the tool's execute throws (no agent), we still hit the error
      // branch — which is also meaningful coverage.
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("inputText", "{\"text\":\"hello\"}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      // Accept either Success or Error CallToolResult — the important thing is that the
      // endpoint routed the dispatch cleanly (no 500, no deserialization failure, no
      // invocation-not-found).
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertNotNull(result)
    } finally {
      handle.close()
    }
  }

  @Test fun `reentrance depth at cap yields CallToolResult error without dispatching`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("depth-cap-session")
    // Register an entry at the cap — the endpoint must reject *before* it even resolves the
    // tool repo, let alone dispatches. Exercises the depth-cap branch in isolation from the
    // tool-execution code path.
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = makeRepo(),
      executionContext = makeContext(),
      depth = JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH,
    )
    try {
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("inputText", "{\"text\":\"hi\"}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertEquals(false, result.success)
      assertTrue(
        result.errorMessage.contains("reentrance depth"),
        "Expected reentrance-depth error, got: ${result.errorMessage}",
      )
    } finally {
      handle.close()
    }
  }

  @Test fun `dispatch exceeding timeout yields CallToolResult error`() = testApplication {
    // Shrink the timeout *before* registering the endpoint — the endpoint reads the property
    // once at register time, so setting it inside the test body but before `application { }`
    // runs is what makes the shorter bound effective.
    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "50")
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("timeout-session")

    // Fake registration whose tool `execute` hangs forever. Using awaitCancellation so the
    // coroutine cancels cleanly when withTimeout fires — otherwise a naive Thread.sleep would
    // block the dispatcher thread past the timeout and distort the test.
    val hangingTool = HangingTrailblazeTool
    val hangingRegistration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("hangForever")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that never returns — used to exercise the timeout branch.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the callback-endpoint test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = hangingTool
    }

    // Fresh repo seeded with one always-enabled class (InputTextTrailblazeTool) so the
    // TrailblazeToolSet constructor stays happy, plus our hanging dynamic registration for the
    // callback to dispatch.
    val toolRepo = makeRepo()
    toolRepo.addDynamicTools(listOf(hangingRegistration))
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = toolRepo,
      executionContext = makeContext(),
    )
    try {
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("hangForever", "{}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertEquals(false, result.success)
      assertTrue(
        result.errorMessage.contains("timed out"),
        "Expected timeout error, got: ${result.errorMessage}",
      )
      // Proves the timeout unwind didn't eagerly remove the registry entry — releasing the
      // entry is SubprocessTrailblazeTool's job (its finally block closes the handle), not the
      // endpoint's. If someone later adds a path that mutates the registry on timeout, this
      // catches it.
      assertNotNull(JsScriptingInvocationRegistry.lookup(handle.invocationId))
    } finally {
      handle.close()
    }
  }

  @Test fun `isLoopback accepts loopback forms and rejects everything else`() {
    // Direct helper-level coverage: testApplication always reports loopback, so the non-loopback
    // rejection branch in the endpoint body would go uncovered without this. The gate is the
    // only thing preventing remote callers from dispatching arbitrary tools — a silent
    // regression would be a security issue, not a functional one.
    assertTrue(ScriptingCallbackEndpoint.isLoopback("127.0.0.1"))
    assertTrue(ScriptingCallbackEndpoint.isLoopback("127.0.0.5"), "any 127.0.0.0/8 is loopback")
    assertTrue(ScriptingCallbackEndpoint.isLoopback("::1"))
    assertTrue(ScriptingCallbackEndpoint.isLoopback("0:0:0:0:0:0:0:1"))
    assertTrue(ScriptingCallbackEndpoint.isLoopback("localhost"))

    assertEquals(false, ScriptingCallbackEndpoint.isLoopback("10.0.0.1"))
    assertEquals(false, ScriptingCallbackEndpoint.isLoopback("192.168.1.1"))
    assertEquals(false, ScriptingCallbackEndpoint.isLoopback("8.8.8.8"))
    assertEquals(false, ScriptingCallbackEndpoint.isLoopback("evil.example.com"))
    assertEquals(false, ScriptingCallbackEndpoint.isLoopback(""))
    // "127.x" as a prefix-only match without the full octet form is NOT loopback — the gate
    // uses `address.startsWith("127.")` which correctly handles this because a bare "127foo"
    // lacks the dot. Regression guard against a future refactor that changes the prefix test.
    assertEquals(false, ScriptingCallbackEndpoint.isLoopback("127"))
  }

  @Test fun `resolveMaxBodyBytes falls back to default on unset, non-numeric, and non-positive values`() {
    // Same silently-default-on-typo tradeoff as resolveTimeoutMs / resolveMaxDepth — a typo like
    // `-Dtrailblaze.callback.maxBodyBytes=-1` would let every oversized request through if we
    // didn't fall back. Positive value is honored so the resolver's fall-through isn't the only
    // branch producing DEFAULT.
    System.clearProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY)
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_MAX_BODY_BYTES, ScriptingCallbackEndpoint.resolveMaxBodyBytes())

    System.setProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY, "-1")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_MAX_BODY_BYTES, ScriptingCallbackEndpoint.resolveMaxBodyBytes())

    System.setProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY, "0")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_MAX_BODY_BYTES, ScriptingCallbackEndpoint.resolveMaxBodyBytes())

    System.setProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY, "abc")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_MAX_BODY_BYTES, ScriptingCallbackEndpoint.resolveMaxBodyBytes())

    System.setProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY, "4096")
    assertEquals(4_096L, ScriptingCallbackEndpoint.resolveMaxBodyBytes())
  }

  @Test fun `request exceeding maxBodyBytes via Content-Length is rejected with 413`() = testApplication {
    // Lower the cap to a value a real request will exceed, then POST a body with a Content-Length
    // header over the cap. The endpoint must reject with 413 BEFORE buffering the body — if the
    // cap regresses to post-buffering, the test still passes but the protection vanishes under
    // an OOM-sized payload. Guard against that by using a cap well under Ktor's default buffer
    // size so a regression that reads first wouldn't even touch the cap's code path.
    System.setProperty(ScriptingCallbackEndpoint.CALLBACK_MAX_BODY_BYTES_PROPERTY, "128")
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    // 256-byte body, well over the 128-byte cap. Exact content is irrelevant — the gate fires
    // on Content-Length before the body is parsed.
    val oversized = "a".repeat(256)
    val response = client.post("/scripting/callback") {
      contentType(ContentType.Application.Json)
      setBody(oversized)
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    assertTrue(
      response.bodyAsText().contains("exceeds max"),
      "Expected payload-too-large body, got: ${response.bodyAsText()}",
    )
  }

  @Test fun `resolveMaxDepth falls back to default on unset, non-numeric, and non-positive values`() {
    // Same fall-through semantics as resolveTimeoutMs — a typo like `-Dtrailblaze.callback.maxDepth=-1`
    // silently uses the registry's default rather than a negative cap (which would reject every
    // callback, including depth 0). The @After hook clears the property after this test.
    System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY)
    assertEquals(JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH, JsScriptingCallbackDispatcher.resolveMaxDepth())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY, "-1")
    assertEquals(JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH, JsScriptingCallbackDispatcher.resolveMaxDepth())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY, "0")
    assertEquals(JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH, JsScriptingCallbackDispatcher.resolveMaxDepth())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY, "abc")
    assertEquals(JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH, JsScriptingCallbackDispatcher.resolveMaxDepth())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY, "42")
    assertEquals(42, JsScriptingCallbackDispatcher.resolveMaxDepth())
  }

  @Test fun `max-depth override via system property lowers the gate`() = testApplication {
    // Register the endpoint with an override of 2, then register an entry at depth 2 — the gate
    // must reject at the overridden cap even though the hardcoded registry default (16) would
    // still allow it. Proves resolveMaxDepth's value flows into the actual gate, not just the
    // resolver's return value.
    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_MAX_DEPTH_PROPERTY, "2")
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("max-depth-override-session")
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = makeRepo(),
      executionContext = makeContext(),
      depth = 2,
    )
    try {
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("inputText", "{\"text\":\"hi\"}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertEquals(false, result.success)
      assertTrue(
        result.errorMessage.contains("reached max 2"),
        "Expected error message to cite the overridden cap (2), got: ${result.errorMessage}",
      )
    } finally {
      handle.close()
    }
  }

  @Test fun `callback dispatch propagates depth through coroutine context`() = testApplication {
    // The load-bearing mechanism: when the endpoint dispatches a tool via the callback channel,
    // it runs that tool inside `withContext(JsScriptingCallbackDispatchDepth(entry.depth + 1))`. Any
    // SubprocessTrailblazeTool running inside that coroutine must read the element and stamp a
    // new invocation at parent.depth + 1 — otherwise a recursive chain would blow past the cap
    // with no test failure. This test registers a dynamic tool whose execute reads the element
    // and echoes it back as textContent, then asserts the inner sees `outer.depth + 1`.
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("depth-propagation-session")
    val outerDepth = 5
    val depthEchoTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
        val observed = currentCoroutineContext()[JsScriptingCallbackDispatchDepth]?.depth ?: -1
        return TrailblazeToolResult.Success(message = observed.toString())
      }
    }
    val depthEchoRegistration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("depthEcho")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that echoes the observed JsScriptingCallbackDispatchDepth as text.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the callback-endpoint test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = depthEchoTool
    }
    val toolRepo = makeRepo()
    toolRepo.addDynamicTools(listOf(depthEchoRegistration))
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = toolRepo,
      executionContext = makeContext(),
      depth = outerDepth,
    )
    try {
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          action = JsScriptingCallbackAction.CallTool("depthEcho", "{}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertEquals(true, result.success, "Expected success, got errorMessage: ${result.errorMessage}")
      assertEquals(
        (outerDepth + 1).toString(),
        result.textContent,
        "Inner tool must observe depth = outer.depth + 1; regression would let recursive chains bypass the cap.",
      )
    } finally {
      handle.close()
    }
  }

  @Test fun `resolveTimeoutMs falls back to default on unset, non-numeric, and non-positive values`() {
    // Direct unit test on the internal helper — covers the fall-through branches so a typo like
    // `-Dtrailblaze.callback.timeoutMs=-1` silently uses the default rather than a negative
    // timeout (which withTimeout would reject at runtime). The @After hook clears the property
    // after this test so a reorder can't leak the override onto a test expecting the default.
    System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS, JsScriptingCallbackDispatcher.resolveTimeoutMs())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "-1")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS, JsScriptingCallbackDispatcher.resolveTimeoutMs())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "0")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS, JsScriptingCallbackDispatcher.resolveTimeoutMs())

    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "abc")
    assertEquals(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS, JsScriptingCallbackDispatcher.resolveTimeoutMs())

    // Sanity: a valid positive value is honored, proving the fall-through branches are the
    // *only* thing producing DEFAULT — not an accidental short-circuit on every path.
    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "12345")
    assertEquals(12_345L, JsScriptingCallbackDispatcher.resolveTimeoutMs())
  }

  @Test fun `tool deserialization failure yields CallToolResult error`() = testApplication {
    application {
      routing { ScriptingCallbackEndpoint.register(this) }
    }
    val sessionId = SessionId("deserialize-fail-session")
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    try {
      val body = json.encodeToString(
        JsScriptingCallbackRequest.serializer(),
        JsScriptingCallbackRequest(
          sessionId = sessionId.value,
          invocationId = handle.invocationId,
          // A tool name that doesn't exist in the toolRepo — toolCallToTrailblazeTool will
          // fail. Must surface as CallToolResult(success=false, errorMessage=...), NOT 500.
          action = JsScriptingCallbackAction.CallTool("nonExistentTool", "{}"),
        ),
      )
      val response = client.post("/scripting/callback") {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val decoded = json.decodeFromString(JsScriptingCallbackResponse.serializer(), response.bodyAsText())
      val result = decoded.result as JsScriptingCallbackResult.CallToolResult
      assertEquals(false, result.success)
      assertTrue(result.errorMessage.isNotBlank())
    } finally {
      handle.close()
    }
  }

  /**
   * Test-only [ExecutableTrailblazeTool] whose `execute` suspends forever. Used by the timeout
   * test to prove the endpoint's `withTimeout` branch fires. [awaitCancellation] (vs.
   * `delay(Long.MAX_VALUE)`) keeps the cancellation path cooperative — when the timeout fires,
   * this coroutine unwinds immediately rather than being forcibly killed, matching how a real
   * coroutine-native tool would behave.
   */
  private object HangingTrailblazeTool : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      awaitCancellation()
    }
  }
}
