package xyz.block.trailblaze.scripting.callback

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
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

/**
 * Direct coverage of [JsScriptingCallbackDispatcher.dispatch]. The HTTP endpoint test
 * (`ScriptingCallbackEndpointTest`) exercises the same core through the HTTP shell; this
 * test skips the shell and hits the dispatcher directly so the shared semantics stay pinned
 * regardless of transport. Matters because the on-device `__trailblazeCallback` binding in
 * `:trailblaze-scripting-bundle` also calls this dispatcher — a regression that only shows
 * up on one transport would bypass the endpoint test.
 */
class CallbackDispatcherTest {

  @After fun cleanup() {
    JsScriptingInvocationRegistry.clearForTest()
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private fun makeContext(sessionId: SessionId): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )

  private fun makeRepo(): TrailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "callback-dispatcher-test-toolset",
      setOf(InputTextTrailblazeTool::class),
    ),
  )

  private fun register(
    sessionId: SessionId,
    depth: Int = 0,
    repo: TrailblazeToolRepo = makeRepo(),
  ): JsScriptingInvocationRegistry.Handle = JsScriptingInvocationRegistry.register(
    sessionId = sessionId,
    toolRepo = repo,
    executionContext = makeContext(sessionId),
    depth = depth,
  )

  private fun register(
    sessionId: SessionId,
    executionContext: TrailblazeToolExecutionContext,
    depth: Int = 0,
    repo: TrailblazeToolRepo = makeRepo(),
  ): JsScriptingInvocationRegistry.Handle = JsScriptingInvocationRegistry.register(
    sessionId = sessionId,
    toolRepo = repo,
    executionContext = executionContext,
    depth = depth,
  )

  private fun buildCallToolRequest(
    sessionId: String,
    invocationId: String,
    toolName: String,
    argumentsJson: String = "{}",
  ): JsScriptingCallbackRequest = JsScriptingCallbackRequest(
    sessionId = sessionId,
    invocationId = invocationId,
    action = JsScriptingCallbackAction.CallTool(toolName = toolName, argumentsJson = argumentsJson),
  )

  @Test
  fun `unknown invocation id surfaces as Error not success`() = runBlocking {
    val result = JsScriptingCallbackDispatcher.dispatch(
      buildCallToolRequest("any", "ghost-id", "inputText", "{\"text\":\"hi\"}"),
    )
    val error = result as? JsScriptingCallbackResult.Error ?: error("Expected Error, got: $result")
    assertThat(error.message).contains("not found")
  }

  @Test
  fun `session id mismatch surfaces as Error`() = runBlocking {
    val handle = register(SessionId("real-session"))
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest("attacker-session", handle.invocationId, "inputText"),
      )
      val error = result as? JsScriptingCallbackResult.Error ?: error("Expected Error, got: $result")
      assertThat(error.message).contains("Session mismatch")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `depth at cap yields CallToolResult with reentrance error`() = runBlocking {
    val sessionId = SessionId("depth-cap")
    val handle = register(sessionId = sessionId, depth = 4)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        request = buildCallToolRequest(sessionId.value, handle.invocationId, "inputText"),
        maxDepth = 4,
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(false)
      assertThat(cap.errorMessage).contains("reentrance depth")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `version mismatch surfaces as Error`() = runBlocking {
    val result = JsScriptingCallbackDispatcher.dispatch(
      // Version 99 is deliberately out of range — data-class ctor defaults to
      // CURRENT_VERSION, so we override explicitly here. Matches the HTTP endpoint's
      // version-gate contract.
      JsScriptingCallbackRequest(
        version = 99,
        sessionId = "s",
        invocationId = "i",
        action = JsScriptingCallbackAction.CallTool("x", "{}"),
      ),
    )
    val error = result as? JsScriptingCallbackResult.Error ?: error("Expected Error, got: $result")
    assertThat(error.message).contains("Unsupported callback version")
  }

  @Test
  fun `deserialize failure yields CallToolResult with errorMessage`() = runBlocking {
    val sessionId = SessionId("deserialize-fail")
    val handle = register(sessionId)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(sessionId.value, handle.invocationId, "tool_that_does_not_exist"),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(false)
      assertThat(cap.errorMessage).contains("tool_that_does_not_exist")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `timeout yields CallToolResult with timed out error`() = runBlocking {
    // Short timeout + a hanging tool proves the timeout branch fires even when a tool is
    // otherwise unwrappable. 30-ms bound is generous for the `withTimeout` coroutine but
    // tight enough to keep the test fast.
    val hangingTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
        awaitCancellation()
      }
    }
    val hangingRegistration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("hangForever")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that never returns.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = hangingTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(hangingRegistration))
    val sessionId = SessionId("timeout")
    val handle = register(sessionId = sessionId, repo = repo)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        request = buildCallToolRequest(sessionId.value, handle.invocationId, "hangForever"),
        timeoutMs = 30L,
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(false)
      assertThat(cap.errorMessage).contains("timed out")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `depth increments by one inside dispatched coroutine`() = runBlocking {
    // Load-bearing: child invocations must register at parent depth + 1 so the depth gate
    // catches recursive chains at the NEXT callback, not the one after. Same invariant the
    // HTTP endpoint test covers; repeating here keeps the shared dispatcher pinned.
    val observedDepths = mutableListOf<Int>()
    val depthEchoTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
        val depth = currentCoroutineContext()[JsScriptingCallbackDispatchDepth]?.depth ?: -1
        observedDepths += depth
        return TrailblazeToolResult.Success(message = depth.toString())
      }
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("depthEcho")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Echoes the observed JsScriptingCallbackDispatchDepth as text.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = depthEchoTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("depth-propagation")
    val handle = register(sessionId = sessionId, depth = 3, repo = repo)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(sessionId.value, handle.invocationId, "depthEcho"),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(true)
      assertThat(cap.textContent).isEqualTo("4")
      assertThat(observedDepths).isInstanceOf(List::class).isEqualTo(listOf(4))
    } finally {
      handle.close()
    }
  }

  @Test
  fun `nested tool executor is preferred over direct tool execution`() = runBlocking {
    var directExecutionCount = 0
    val dispatchedTools = mutableListOf<TrailblazeTool>()
    val directTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
        directExecutionCount += 1
        return TrailblazeToolResult.Success(message = "direct-execute")
      }
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("nestedExecutorProbe")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Confirms callback dispatch uses the nested tool executor when present.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = directTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("nested-executor")
    val context = makeContext(sessionId).let { base ->
      TrailblazeToolExecutionContext(
        screenState = base.screenState,
        traceId = base.traceId,
        trailblazeDeviceInfo = base.trailblazeDeviceInfo,
        sessionProvider = base.sessionProvider,
        screenStateProvider = base.screenStateProvider,
        androidDeviceCommandExecutor = base.androidDeviceCommandExecutor,
        trailblazeLogger = base.trailblazeLogger,
        memory = base.memory,
        maestroTrailblazeAgent = base.maestroTrailblazeAgent,
        nestedToolExecutor = { tool ->
          dispatchedTools += tool
          TrailblazeToolResult.Success(message = "nested-executor")
        },
        workingDirectory = base.workingDirectory,
        nodeSelectorMode = base.nodeSelectorMode,
      )
    }
    val handle = register(sessionId = sessionId, executionContext = context, repo = repo)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(sessionId.value, handle.invocationId, "nestedExecutorProbe"),
      )
      val callToolResult =
        result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(callToolResult.success).isEqualTo(true)
      assertThat(callToolResult.textContent).isEqualTo("nested-executor")
      assertThat(directExecutionCount).isEqualTo(0)
      assertThat(dispatchedTools.size).isEqualTo(1)
      assertThat(dispatchedTools.single()).isEqualTo(directTool)
    } finally {
      handle.close()
    }
  }

  /**
   * Captures `Console.log` output while [block] runs. `Console.jvm.kt` caches `System.out`
   * into a private `out: PrintStream` field at object-init time, so `System.setOut(...)`
   * DOES NOT affect subsequent `Console.log` writes — we have to swap the field directly
   * via reflection. Fragile (binds to the field name), but Console is in-tree and the field
   * name is documented in `Console.jvm.kt`; the alternative is introducing an injectable
   * sink on the common Console API just for tests, which is more invasive than a reflection
   * hook here.
   *
   * Restores the original PrintStream in finally so a failing assertion can't corrupt other
   * tests' output. Returns the captured text; one line per `Console.log` call is preserved.
   */
  private suspend fun captureConsoleLog(block: suspend () -> Unit): String {
    val field = Console::class.java.getDeclaredField("out").apply { isAccessible = true }
    val original = field.get(Console) as PrintStream
    val captured = ByteArrayOutputStream()
    val printStream = PrintStream(captured, /* autoFlush = */ true, Charsets.UTF_8)
    field.set(Console, printStream)
    return try {
      block()
      captured.toString(Charsets.UTF_8)
    } finally {
      field.set(Console, original)
      printStream.close()
    }
  }

  @Test
  fun `unknown argument keys are rejected before dispatch with canonical-shape message`() = runBlocking {
    // #3209 — the typed-surface contract (`client.d.ts`) rejects unknown keys at
    // compile time; this runtime check closes the matching gap so a scripted tool that
    // skipped tsc (or copied an LLM-emitted authoring hint like `element:` from a
    // recording) can't silently drop the key at the wire. The expected shape:
    //  - CallToolResult, success=false (NOT a protocol-level Error — the scripted caller's
    //    awaiting promise sees the same envelope as any other tool failure).
    //  - errorMessage names the offending key, the canonical accepted keys, and points at
    //    `client.tools.<toolName>` so the author knows where to look.
    val sessionId = SessionId("unknown-key-reject")
    val handle = register(sessionId)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(
          sessionId.value,
          handle.invocationId,
          "inputText",
          // `text` is the real required arg; `element` is the LLM authoring hint that today
          // gets silently dropped. The runtime gate should reject the call before reaching
          // the deserializer, naming `element` as the offending key.
          """{"text":"hi","element":"the input"}""",
        ),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(false)
      val message = cap.errorMessage ?: error("Expected errorMessage on rejection")
      assertThat(message).contains("inputText")
      assertThat(message).contains("\"element\"")
      assertThat(message).contains("text")
      assertThat(message).contains("client.tools.inputText")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `payload using only known keys passes the unknown-key gate`() = runBlocking {
    // Companion to the rejection test: the gate must be a no-op for the well-formed case so
    // every existing scripted-tool caller keeps working. If this regresses, every legitimate
    // tool call would start returning an unknown-key error.
    val sessionId = SessionId("unknown-key-allow")
    val handle = register(sessionId)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(
          sessionId.value,
          handle.invocationId,
          "inputText",
          """{"text":"hello"}""",
        ),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      // The tool itself may fail in this test harness (no real device), so we only assert
      // that the error — if any — is NOT the unknown-key rejection message.
      val message = cap.errorMessage.orEmpty()
      assertThat(message).doesNotContain("unknown argument keys")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `missing required argument is rejected before dispatch with directed message`() = runBlocking {
    // #3261 — runtime side of the typed-surface contract for required args. The validator
    // must reject a payload that omits a `required: true` arg with a CallToolResult whose
    // errorMessage names the missing key and points at `client.tools.<name>`. Without
    // this gate, the call would route into the JS handler with `args.text === undefined`
    // and surface as an opaque "missing argument" runtime error several frames into the
    // bundle — the validator gives the author an actionable message before any handler
    // code runs. Mirrors the unknown-key rejection test above for shape parity; the only
    // difference is omission vs. extra-key.
    val sessionId = SessionId("missing-required-reject")
    val handle = register(sessionId)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(
          sessionId.value,
          handle.invocationId,
          "inputText",
          // `inputText` declares `text` as required (no default in the data class). Omitting
          // it must surface the validator's missing-required message — not the deserializer's
          // MissingFieldException, which would reach the caller as a "decode failed" string.
          """{}""",
        ),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(false)
      val message = cap.errorMessage ?: error("Expected errorMessage on rejection")
      assertThat(message).contains("inputText")
      assertThat(message).contains("\"text\"")
      assertThat(message).contains("Required: text")
      assertThat(message).contains("client.tools.inputText")
    } finally {
      handle.close()
    }
  }

  @Test
  fun `missing-required rejection emits MISSING_REQUIRED_REJECTED log marker distinct from unknown-key`() = runBlocking {
    // Pins the operator-debugging contract for the new rejection tag: missing-required
    // must NOT log under `UNKNOWN_KEYS_REJECTED` (the pre-#3261 single tag). A grep on
    // `MISSING_REQUIRED_REJECTED` should surface only missing-required rejections from
    // EITHER dispatch transport; `UNKNOWN_KEYS_REJECTED` should surface only the original
    // unknown-key flavor. A refactor that re-collapses the tags would mask the failure
    // mode in production triage.
    val sessionId = SessionId("missing-required-log-marker")
    val handle = register(sessionId)
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest(
            sessionId.value,
            handle.invocationId,
            "inputText",
            """{}""",
          ),
        )
      }
    } finally {
      handle.close()
    }
    assertThat(output).contains("[JsScriptingCallbackDispatcher] MISSING_REQUIRED_REJECTED")
    assertThat(output).doesNotContain("UNKNOWN_KEYS_REJECTED")
    assertThat(output).contains("tool 'inputText'")
    assertThat(output).contains("session ${sessionId.value}")
  }

  @Test
  fun `unknown-key rejection emits UNKNOWN_KEYS_REJECTED log marker tagged with tool and session`() = runBlocking {
    // Pins the operator-debugging contract for the rejection path: a single grep on
    // `UNKNOWN_KEYS_REJECTED` must find rejections from EITHER dispatch transport. The
    // matching marker on the QuickJS path lives in `SessionScopedHostBinding`. A refactor
    // that renames or drops one of the two markers would silently break the unified grep —
    // assertion here catches that.
    val sessionId = SessionId("unknown-key-log-marker")
    val handle = register(sessionId)
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest(
            sessionId.value,
            handle.invocationId,
            "inputText",
            """{"text":"hi","element":"the input"}""",
          ),
        )
      }
    } finally {
      handle.close()
    }
    assertThat(output).contains("[JsScriptingCallbackDispatcher] UNKNOWN_KEYS_REJECTED")
    assertThat(output).contains("tool 'inputText'")
    assertThat(output).contains("session ${sessionId.value}")
  }

  @Test
  fun `happy path emits START and END log lines tagged with session and invocation`() = runBlocking {
    // Pins the debugging contract: every dispatch emits a correlated START/END pair tagged
    // with session + invocation id, so a tester can grep a single session's full chain across
    // transports. A regression that drops either line (e.g., a refactor that moves the log
    // call behind a branch) silently breaks the triage workflow documented in the devlog.
    val sessionId = SessionId("log-contract-happy")
    val handle = register(sessionId)
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest(sessionId.value, handle.invocationId, "inputText", "{\"text\":\"x\"}"),
        )
      }
    } finally {
      handle.close()
    }
    // START line — includes session, invocation, and the tool-name action summary.
    assertThat(output).contains("[JsScriptingCallbackDispatcher] START")
    assertThat(output).contains("session=${sessionId.value}")
    assertThat(output).contains("invocation=${handle.invocationId}")
    assertThat(output).contains("call_tool name=inputText")
    // END line — pairs with START. Logged regardless of tool-level success so debugging
    // always sees a "dispatch completed" marker.
    assertThat(output).contains("[JsScriptingCallbackDispatcher] END")
  }

  @Test
  fun `session mismatch emits SESSION_MISMATCH log tagged with request and entry sessions`() = runBlocking {
    // Distinct from the happy-path test: locks the error-branch log line, which is what an
    // operator greps for when a misbehaving bundle forges a session id. Both the request's
    // claimed session and the entry's real session must appear so the operator can tell
    // which is the attacker and which is the legitimate invocation.
    val handle = register(SessionId("real-session"))
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest("attacker-session", handle.invocationId, "inputText"),
        )
      }
    } finally {
      handle.close()
    }
    assertThat(output).contains("[JsScriptingCallbackDispatcher] SESSION_MISMATCH")
    assertThat(output).contains("request_session=attacker-session")
    assertThat(output).contains("entry_session=real-session")
  }

  @Test
  fun `Success with structuredContent flows onto wire CallToolResult`() = runBlocking {
    // Producer-side wiring for the typed-result feature. A scripted tool whose handler
    // returns a non-string typed value (e.g. `trailblaze.tool<I, O>({ handler })`) hands the
    // dispatcher a `TrailblazeToolResult.Success(structuredContent = <JsonElement>)`. The
    // dispatcher must thread that payload onto the wire's `CallToolResult.structuredContent`
    // so the TS SDK proxy can unwrap it as the typed `result` declared in `TrailblazeToolMap`.
    val structuredPayload = buildJsonObject {
      put("formatted", JsonPrimitive("hello world"))
      put("inputLength", JsonPrimitive(5))
    }
    val structuredTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
        TrailblazeToolResult.Success(message = null, structuredContent = structuredPayload)
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("structuredEcho")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that returns a structured payload.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = structuredTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("structured-content")
    val handle = register(sessionId = sessionId, repo = repo)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(sessionId.value, handle.invocationId, "structuredEcho"),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult
        ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(true)
      assertThat(cap.structuredContent).isEqualTo(structuredPayload)
    } finally {
      handle.close()
    }
  }

  @Test
  fun `Success without structuredContent leaves wire structuredContent null`() = runBlocking {
    // Negative companion: producer that returned only a text message must NOT have a non-null
    // structuredContent forced onto the wire — that would force every existing string-returning
    // tool to start tripping the TS SDK's "unwrap structured payload" branch and surface
    // null/empty objects in place of the expected string. Pin the absence so a refactor that
    // accidentally synthesizes a stub structured payload can't slip through.
    val textOnlyTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
        TrailblazeToolResult.Success(message = "plain text")
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("textOnlyEcho")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that returns text only.",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = textOnlyTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("structured-content-absent")
    val handle = register(sessionId = sessionId, repo = repo)
    try {
      val result = JsScriptingCallbackDispatcher.dispatch(
        buildCallToolRequest(sessionId.value, handle.invocationId, "textOnlyEcho"),
      )
      val cap = result as? JsScriptingCallbackResult.CallToolResult
        ?: error("Expected CallToolResult, got: $result")
      assertThat(cap.success).isEqualTo(true)
      assertThat(cap.textContent).isEqualTo("plain text")
      assertThat(cap.structuredContent).isEqualTo(null)
    } finally {
      handle.close()
    }
  }

  @Test
  fun `END log line carries structured=true when producer populated a structured payload`() = runBlocking {
    // Pin the dispatcher's END log format — operators grep `structured=true` to find calls
    // that carried a typed payload (and `structured=false` for the legacy text-only shape).
    // Without this assertion, a future change to the log string would silently break the
    // operator runbook / dashboard queries that depend on it.
    val structuredPayload = buildJsonObject {
      put("formatted", JsonPrimitive("hi"))
    }
    val structuredTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
        TrailblazeToolResult.Success(message = null, structuredContent = structuredPayload)
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("structuredLogProbe")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that returns a structured payload (for log-format test).",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = structuredTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("log-format-structured-true")
    val handle = register(sessionId = sessionId, repo = repo)
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest(sessionId.value, handle.invocationId, "structuredLogProbe"),
        )
      }
    } finally {
      handle.close()
    }
    assertThat(output).contains("[JsScriptingCallbackDispatcher] END")
    assertThat(output).contains("call_tool_result success=true structured=true")
  }

  @Test
  fun `END log line carries structured=false when producer returned text only`() = runBlocking {
    // Negative companion to the structured=true test — pin that the text-only path emits
    // `structured=false`, not the structured flag being silently omitted or always-true.
    val textOnlyTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
        TrailblazeToolResult.Success(message = "plain text")
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("textOnlyLogProbe")
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
        name = name.toolName,
        description = "Test-only tool that returns text only (for log-format test).",
      )
      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the dispatcher test path")
      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = textOnlyTool
    }
    val repo = makeRepo()
    repo.addDynamicTools(listOf(registration))
    val sessionId = SessionId("log-format-structured-false")
    val handle = register(sessionId = sessionId, repo = repo)
    val output = try {
      captureConsoleLog {
        JsScriptingCallbackDispatcher.dispatch(
          buildCallToolRequest(sessionId.value, handle.invocationId, "textOnlyLogProbe"),
        )
      }
    } finally {
      handle.close()
    }
    assertThat(output).contains("call_tool_result success=true structured=false")
  }
}
