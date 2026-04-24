package xyz.block.trailblaze.scripting.bundle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.fail
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
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

/**
 * Direct coverage for the `__trailblazeCallback` JS→Kotlin binding installed by
 * [QuickJsBridge]. Skips the MCP handshake and the author bundle — evaluates a tiny JS
 * that calls `globalThis.__trailblazeCallback(json)` and reads the resolved Promise, so
 * failures in the binding wiring (name, argument marshaling, serialization) surface here
 * rather than inside the on-device end-to-end test where the signal is murkier.
 *
 * On-device coverage lives at
 * `OnDeviceBundleClientCallToolTest` in the android-sample-app-uitests module; that
 * test exercises a full bundle + toolRepo round-trip. This one proves the *binding* is
 * installed correctly in isolation.
 */
class QuickJsBridgeCallbackBindingTest {

  @Test
  fun bindingResolvesWithCallbackResponseJson_whenInvocationMissing() = runBlocking {
    // Ghost invocation id never registered → JsScriptingCallbackDispatcher returns Error("not found").
    // The binding serializes that into a JsScriptingCallbackResponse JSON and resolves the Promise
    // with the string. Asserts: binding name is right, args are passed through, response
    // JSON deserializes to the expected shape.
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      QuickJsBridge(quickJs)

      // Drive the async binding by chaining into a global: quickjs-kt's evaluate awaits all
      // pending asyncFunction jobs before returning, so by the time this call completes the
      // `.then` has fired and `globalThis.__result` holds the JSON string. Reading the
      // top-level expression directly would hand us the unresolved Promise object.
      quickJs.evaluate<Any?>(
        // language=JavaScript
        """
          globalThis.__trailblazeCallback(JSON.stringify({
            version: 1,
            session_id: "any-session",
            invocation_id: "ghost-invocation-that-was-never-registered",
            action: { type: "call_tool", tool_name: "inputText", arguments_json: "{}" }
          })).then(r => { globalThis.__result = r; });
        """.trimIndent(),
        filename = "test.js",
        asModule = false,
      )
      val responseJson = quickJs.evaluate<String>("globalThis.__result", "read.js", false)

      val envelope = Json.parseToJsonElement(responseJson).jsonObject
      val result = envelope["result"]?.jsonObject ?: error("Missing result: $responseJson")
      assertThat(result["type"]?.jsonPrimitive?.content).isEqualTo("error")
      val message = result["message"]?.jsonPrimitive?.content.orEmpty()
      assertThat(message).contains("not found")
    } finally {
      withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
    }
  }

  @Test
  fun bindingRespondsWithErrorEnvelope_whenRequestJsonIsMalformed() = runBlocking {
    // Malformed JSON → the Kotlin binding must still resolve the Promise with a
    // JsScriptingCallbackResponse envelope (not throw). Otherwise an author bug in a handler would
    // show up as a QuickJS internal rejection that's nearly impossible to debug from the
    // TS side.
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      QuickJsBridge(quickJs)

      quickJs.evaluate<Any?>(
        // language=JavaScript
        """
          globalThis.__trailblazeCallback("{ not valid json }").then(r => { globalThis.__result = r; });
        """.trimIndent(),
        filename = "test.js",
        asModule = false,
      )
      val responseJson = quickJs.evaluate<String>("globalThis.__result", "read.js", false)

      val envelope = Json.parseToJsonElement(responseJson).jsonObject
      val result = envelope["result"]?.jsonObject ?: error("Missing result: $responseJson")
      assertThat(result["type"]?.jsonPrimitive?.content).isEqualTo("error")
      val message = result["message"]?.jsonPrimitive?.content.orEmpty()
      assertThat(message).contains("Malformed JsScriptingCallbackRequest")
    } finally {
      withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
    }
  }

  @Test
  fun bindingNameMatchesConstant() {
    // Locks the JS-side binding name against BundleRuntimePrelude's constant. A rename on
    // either side without updating both breaks every bundled handler silently — this test
    // is the single-string guard against that drift.
    assertThat(BundleRuntimePrelude.CALLBACK_BINDING).isEqualTo("__trailblazeCallback")
  }

  @Test
  fun consoleBinding_isInstalledAfterPreludeEvaluates() = runBlocking {
    // Proves `globalThis.console` exists on-device after the prelude runs. QuickJS doesn't
    // ship a `console` global; the prelude's shim is the only reason author `console.log`
    // calls work on-device. If this regresses, every bundled tool that logs crashes with
    // ReferenceError — catch it here, not in a CI ATF run.
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      QuickJsBridge(quickJs)
      quickJs.evaluate<Any?>(BundleRuntimePrelude.SOURCE, "prelude.js", false)

      // Sanity: the five standard methods are all callable. We don't assert on log output
      // here (Console.log routes to SLF4J NOP in tests); the ATF run shows the routing
      // works end-to-end. Here we just prove the shim installed and doesn't throw.
      quickJs.evaluate<Any?>(
        // language=JavaScript
        """
          console.log("hello", 1, { a: 2 });
          console.info("info msg");
          console.warn("warn msg");
          console.error(new Error("boom"));
          console.debug("debug msg");
        """.trimIndent(),
        "console-shim.js",
        false,
      )

      // Confirm the shim shape JS consumers depend on.
      val typeOfConsole = quickJs.evaluate<String>("typeof globalThis.console", "t.js", false)
      assertThat(typeOfConsole).isEqualTo("object")
      val typeOfLog = quickJs.evaluate<String>("typeof globalThis.console.log", "t.js", false)
      assertThat(typeOfLog).isEqualTo("function")
    } finally {
      withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
    }
  }

  @Test
  fun consoleBindingNameMatchesConstant() {
    // Same drift-guard as bindingNameMatchesConstant, for the console shim. The prelude
    // JS references the binding by string via `__CONSOLE_BINDING__` placeholder; a rename
    // on one side must update the constant here.
    assertThat(BundleRuntimePrelude.CONSOLE_BINDING).isEqualTo("__trailblazeConsole")
  }

  @Test
  fun callbackResponseHasExpectedStructure() = runBlocking {
    // Sanity on the wire shape the binding serializes. A reader that relies on the
    // `{ "result": { "type": "...", ... } }` structure (the TS SDK) would break if we
    // ever let the result flatten or rename `result`; this test locks that.
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      QuickJsBridge(quickJs)
      quickJs.evaluate<Any?>(
        // language=JavaScript
        """
          globalThis.__trailblazeCallback(JSON.stringify({
            version: 1,
            session_id: "s",
            invocation_id: "missing",
            action: { type: "call_tool", tool_name: "x", arguments_json: "{}" }
          })).then(r => { globalThis.__result = r; });
        """.trimIndent(),
        filename = "test.js",
        asModule = false,
      )
      val responseJson = quickJs.evaluate<String>("globalThis.__result", "read.js", false)
      val envelope = Json.parseToJsonElement(responseJson).jsonObject
      assertThat(envelope.keys).isInstanceOf(Set::class)
      assertThat(envelope.keys.contains("result")).isEqualTo(true)
    } finally {
      withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
    }
  }

  @Test
  fun callbackBinding_rethrowsCancellationException_fromDispatchedTool() = runBlocking {
    // Load-bearing structured-concurrency contract: if a dispatched tool throws
    // CancellationException (session teardown, agent abort), the binding MUST rethrow
    // out of the JS boundary, NOT wrap it as a JsScriptingCallbackResult.Error envelope. A future
    // refactor that reorders the catches in QuickJsBridge.init (CE catch before Throwable
    // catch) — or drops the explicit CE rethrow in JsScriptingCallbackDispatcher's
    // dispatchCallTool — would silently swallow cancellations and break session shutdown;
    // this test fails loud on that regression.
    val sessionId = SessionId("cancellation-rethrow")
    val cancellingTool = object : ExecutableTrailblazeTool {
      override suspend fun execute(
        toolExecutionContext: TrailblazeToolExecutionContext,
      ): TrailblazeToolResult = throw CancellationException("simulated teardown mid-dispatch")
    }
    val registration = object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName("cancellingTool")
      override val trailblazeDescriptor: TrailblazeToolDescriptor =
        TrailblazeToolDescriptor(name = name.toolName, description = "Test-only: throws CE.")

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("buildKoogTool not used by this test path")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = cancellingTool
    }
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "ce-rethrow-test-toolset",
        setOf(InputTextTrailblazeTool::class),
      ),
    )
    repo.addDynamicTools(listOf(registration))
    val ctx = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 2400,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = repo,
      executionContext = ctx,
      depth = 0,
    )
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      QuickJsBridge(quickJs)
      var caughtCe = false
      try {
        quickJs.evaluate<Any?>(
          // language=JavaScript
          """
            globalThis.__trailblazeCallback(JSON.stringify({
              version: 1,
              session_id: "${sessionId.value}",
              invocation_id: "${handle.invocationId}",
              action: { type: "call_tool", tool_name: "cancellingTool", arguments_json: "{}" }
            })).then(r => { globalThis.__result = r; });
          """.trimIndent(),
          filename = "test.js",
          asModule = false,
        )
      } catch (e: CancellationException) {
        caughtCe = true
      }
      if (!caughtCe) {
        fail(
          "Expected CancellationException to propagate through the callback binding, but evaluate " +
            "returned normally. A silent wrap into JsScriptingCallbackResult.Error would break structured " +
            "concurrency teardown.",
        )
      }
    } finally {
      runCatching { handle.close() }
      JsScriptingInvocationRegistry.clearForTest()
      withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
    }
  }

  @Test
  fun binding_isAbsentBeforeBridgeInstall_andPresentAfter() = runBlocking {
    // Locks the precondition the TS SDK's `hasInProcessBinding()` check relies on: on a
    // fresh QuickJs with no QuickJsBridge attached, `typeof globalThis.__trailblazeCallback`
    // is "undefined"; after QuickJsBridge(quickJs) runs, it's "function". A regression that
    // makes the binding install unreliably or conditionally would let the TS SDK's
    // "spoofed runtime=ondevice but no binding" error path fire in a real on-device
    // session. Can't directly exercise the TS guard (no TS harness yet — that is a
    // follow-up), but we can pin the invariant the guard depends on.
    val freshQuickJs = QuickJs.create(Dispatchers.Default)
    try {
      val before = freshQuickJs.evaluate<String>(
        "typeof globalThis.__trailblazeCallback",
        "before.js",
        false,
      )
      assertThat(before).isEqualTo("undefined")
      QuickJsBridge(freshQuickJs)
      val after = freshQuickJs.evaluate<String>(
        "typeof globalThis.__trailblazeCallback",
        "after.js",
        false,
      )
      assertThat(after).isEqualTo("function")
    } finally {
      withContext(Dispatchers.IO) { runCatching { freshQuickJs.close() } }
    }
  }
}
