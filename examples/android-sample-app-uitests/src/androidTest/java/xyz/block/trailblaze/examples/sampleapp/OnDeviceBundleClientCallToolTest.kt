package xyz.block.trailblaze.examples.sampleapp

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import xyz.block.trailblaze.scripting.bundle.AndroidAssetBundleJsSource
import xyz.block.trailblaze.scripting.bundle.BundleToolRegistration
import xyz.block.trailblaze.scripting.bundle.BundleTrailblazeTool
import xyz.block.trailblaze.scripting.bundle.McpBundleSession
import xyz.block.trailblaze.scripting.bundle.fetchAndFilterTools
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
 * **What this test proves:** a TypeScript tool authored with `@trailblaze/scripting` and running
 * inside the Android on-device QuickJS bundle can invoke `ctx.client.callTool("someTool", args)`
 * from its handler and receive the result, without any HTTP server running in the instrumentation
 * process. Kotlin-side, that call lands at the `__trailblazeCallback` QuickJS binding → resolves
 * through [xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher] against the live
 * session's [TrailblazeToolRepo] → returns the result across the binding.
 *
 * The test fixture (`bundle-callback-fixture.js`) hand-rolls what a real SDK bundle would emit
 * because QuickJS can't parse TypeScript and the TS→JS bundler automation hasn't landed. When that
 * lands, a follow-up will swap the fixture for an SDK-authored tool; the Kotlin-side transport
 * under test is identical either way.
 *
 * **Coverage (5 tests, each isolating one branch):**
 * 1. [bundleHandler_callsKotlinToolViaCallback_happyPath] — happy path. Bundle handler calls a
 *    Kotlin tool, handler receives the result and wraps it.
 * 2. [bundleHandler_callbackToUnknownKotlinTool_receivesErrorEnvelope] — dispatcher's unknown-tool
 *    branch surfaces as `JsScriptingCallbackResult.CallToolResult(success=false)` and the outer
 *    handler can read it.
 * 3. [bundleHandler_callbackWithForgedSessionId_receivesSessionMismatchError] — dispatcher rejects
 *    a callback whose forged `session_id` doesn't match the live invocation, surfacing as
 *    `JsScriptingCallbackResult.Error`.
 * 4. [bundleHandler_withoutCallbackContext_envelopeOmittedAndHandlerFails] — sanity that omitting
 *    the `JsScriptingCallbackContext` (no invocation registered) means `_meta.trailblaze` is
 *    omitted and a handler that expects it fails loudly.
 * 5. [fixtureBundle_advertisesExpectedTools] — fixture sanity; `tools/list` returns the three tools
 *    the other tests depend on.
 *
 * **Grep the logcat.** Each test uses a session id that names its scenario (`callback-happy-path`,
 * `callback-unknown-tool`, …) and the transport emits tagged logs at each hop — `[QuickJsBridge]`,
 * `[JsScriptingCallbackDispatcher]`, `[BundleTrailblazeTool]`. Searching logcat for the scenario's
 * session id walks the full callback chain for that test.
 *
 * **Scope caveat — bundle→bundle via callback is deliberately NOT covered.** Recursively
 * re-entering the in-process MCP transport from inside a callback would re-acquire
 * `InProcessMcpTransport.evalMutex` (plus quickjs-kt's own `jsResultMutex`) and deadlock. The
 * current consumer only needs bundle→Kotlin composition; a follow-up will teach the transport to
 * suspend its mutex across callback dispatch so bundle→bundle becomes safe. Until then every test
 * here callbacks to the Kotlin-side [KOTLIN_CALLBACK_TARGET_NAME] tool, never another bundle tool.
 *
 * Runs as an Android instrumentation test — built via `assembleDebugAndroidTest` and invoked as a
 * standard `@Test` on a connected device/emulator — so any CI that runs the debug test APK picks it
 * up automatically.
 */
class OnDeviceBundleClientCallToolTest {

  @After
  fun cleanup() {
    // The registry is a process-wide singleton; `BundleTrailblazeTool.execute` already
    // closes its handle in a finally block, so a successful test leaves nothing behind.
    // We clear anyway because a thrown assertion would leave a stale entry, and the next
    // test in the suite would see it if it happens to invoke the same session id.
    JsScriptingInvocationRegistry.clearForTest()
  }

  private fun bundleSource(): AndroidAssetBundleJsSource =
    AndroidAssetBundleJsSource(assetPath = "fixtures/bundle-callback-fixture.js")

  private val deviceInfo =
    TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 2400,
    )

  private fun makeSessionProvider(sessionId: SessionId): TrailblazeSessionProvider =
    TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    }

  private fun makeExecutionContext(sessionId: SessionId): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = makeSessionProvider(sessionId),
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )

  /**
   * A Kotlin-side callback target: takes `{ text: string }` and returns it reversed. Authored as a
   * [DynamicTrailblazeToolRegistration] — the simplest way to seed a tool into the repo without
   * declaring a Maestro/driver-backed one that'd need a live screen. The reverse is the observable
   * behavior the test asserts on.
   */
  private fun reverseEchoRegistration(): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(KOTLIN_CALLBACK_TARGET_NAME)
      override val trailblazeDescriptor: TrailblazeToolDescriptor =
        TrailblazeToolDescriptor(
          name = name.toolName,
          description = "Test-only Kotlin tool that reverses its `text` argument.",
        )

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext
      ): TrailblazeKoogTool<out TrailblazeTool> =
        error("buildKoogTool not exercised by the callback test path")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
        // Parse the `{ "text": "..." }` envelope the callback built, close over it so the
        // executable tool's [execute] returns the reversed text. Matches the shape
        // JsScriptingCallbackDispatcher expects from [TrailblazeToolRepo.toolCallToTrailblazeTool].
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val text = args["text"]?.jsonPrimitive?.content.orEmpty()
        val reversed = text.reversed()
        return object : ExecutableTrailblazeTool {
          override suspend fun execute(
            toolExecutionContext: TrailblazeToolExecutionContext
          ): TrailblazeToolResult = TrailblazeToolResult.Success(message = reversed)
        }
      }
    }

  /**
   * Wires a session + tool repo + callback context per the production launcher shape. Every bundle
   * tool the fixture advertises becomes a [BundleToolRegistration] in the repo, so the in-process
   * callback can dispatch any of them. We also seed a Kotlin-side [KOTLIN_CALLBACK_TARGET_NAME]
   * tool (see [reverseEchoRegistration]) which is the actual callback target exercised by
   * `outerCompose`.
   *
   * [InputTextTrailblazeTool::class] is the seed Kotlin tool — it isn't the subject of any
   * assertion but satisfies `TrailblazeToolSet` + `TrailblazeToolRepo`'s expected non-empty initial
   * set and keeps the repo wiring shape close to production.
   */
  private suspend fun connectAndWire(): WiredSession {
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val toolRepo =
        TrailblazeToolRepo(
          TrailblazeToolSet.DynamicTrailblazeToolSet(
            "ondevice-callback-test-toolset",
            setOf(InputTextTrailblazeTool::class),
          )
        )
      val registered = session.fetchAndFilterTools(driver = deviceInfo.trailblazeDriverType)
      val callbackContext = BundleToolRegistration.JsScriptingCallbackContext(toolRepo = toolRepo)
      val bundleRegistrations =
        registered.map { reg ->
          BundleToolRegistration(
            registered = reg,
            sessionProvider = { session },
            callbackContext = callbackContext,
          )
        }
      toolRepo.addDynamicTools(bundleRegistrations + reverseEchoRegistration())
      return WiredSession(session = session, toolRepo = toolRepo, callbackContext = callbackContext)
    } catch (t: Throwable) {
      runCatching { session.shutdown() }
      throw t
    }
  }

  private data class WiredSession(
    val session: McpBundleSession,
    val toolRepo: TrailblazeToolRepo,
    val callbackContext: BundleToolRegistration.JsScriptingCallbackContext,
  )

  /**
   * Build a [BundleTrailblazeTool] for [toolName] that dispatches through [wired]'s session and
   * callback context. Matches what
   * [xyz.block.trailblaze.scripting.bundle.BundleToolSerializer.deserialize] would produce when the
   * Koog layer routes the LLM's tool-call args through the registration — i.e. the same instance
   * the production dispatch path executes.
   */
  private fun buildExecutableTool(
    wired: WiredSession,
    toolName: String,
    argsJson: String,
  ): BundleTrailblazeTool =
    wired.toolRepo.toolCallToTrailblazeTool(toolName, argsJson) as BundleTrailblazeTool

  @Test
  fun bundleHandler_callsKotlinToolViaCallback_happyPath() = runBlocking {
    // The full happy-path round-trip. `outerCompose` (bundle tool) receives { text: "on-device" },
    // calls `__trailblazeCallback` to dispatch the Kotlin-side `reverseEcho` tool, and wraps the
    // Kotlin tool's reversed result. Failure here means the callback transport broke end-to-end;
    // logcat `[QuickJsBridge]` / `[JsScriptingCallbackDispatcher]` / `[BundleTrailblazeTool]`
    // entries tagged
    // with the session id below will show which hop failed.
    val wired = connectAndWire()
    try {
      val sessionId = SessionId("callback-happy-path")
      val ctx = makeExecutionContext(sessionId)
      val tool = buildExecutableTool(wired, "outerCompose", """{"text":"on-device"}""")

      val result = tool.execute(ctx)

      val success =
        result as? TrailblazeToolResult.Success ?: error("Expected Success, got: $result")
      val composed = Json.parseToJsonElement(success.message!!).jsonObject
      assertEquals(
        "outerCompose should tag the result with its inner tool",
        KOTLIN_CALLBACK_TARGET_NAME,
        composed["composedFrom"]?.jsonPrimitive?.content,
      )
      assertEquals(
        "original input should round-trip unchanged",
        "on-device",
        composed["original"]?.jsonPrimitive?.content,
      )
      assertEquals(
        "Kotlin tool's reversed output should be passed through the callback envelope",
        "ecived-no",
        composed["reversed"]?.jsonPrimitive?.content,
      )
    } finally {
      wired.session.shutdown()
    }
  }

  @Test
  fun bundleHandler_callbackToUnknownKotlinTool_receivesErrorEnvelope() = runBlocking {
    // A bundle handler calls back to a tool name that isn't registered in the repo. The
    // dispatcher's deserialize step fails (no registration found), which surfaces as
    // JsScriptingCallbackResult.CallToolResult(success=false, errorMessage=...). Proves the outer
    // handler
    // sees a structured error envelope instead of an unhandled QuickJS rejection. Watch for
    // `[JsScriptingCallbackDispatcher] DESERIALIZE_FAILED tool='tool_that_does_not_exist'` in
    // logcat.
    val wired = connectAndWire()
    try {
      val sessionId = SessionId("callback-unknown-tool")
      val ctx = makeExecutionContext(sessionId)
      val tool = buildExecutableTool(wired, "callInvalidTool", "{}")
      val result = tool.execute(ctx)

      val success =
        result as? TrailblazeToolResult.Success
          ?: error("Expected Success with error-shaped inner payload, got: $result")
      val inner = Json.parseToJsonElement(success.message!!).jsonObject
      assertEquals(
        "Unknown tool must surface as call_tool_result with success=false",
        "call_tool_result",
        inner["type"]?.jsonPrimitive?.content,
      )
      assertEquals(false, inner["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
      val errorMessage = inner["error_message"]?.jsonPrimitive?.content.orEmpty()
      assertTrue(
        "Error message should mention the bogus tool name; got: $errorMessage",
        errorMessage.contains("tool_that_does_not_exist"),
      )
    } finally {
      wired.session.shutdown()
    }
  }

  @Test
  fun bundleHandler_callbackWithForgedSessionId_receivesSessionMismatchError() = runBlocking {
    // Security invariant: never silently dispatch against the wrong session, even if a misbehaving
    // bundle forges the `session_id` field on the wire. The dispatcher cross-checks against the
    // registry entry's session and surfaces JsScriptingCallbackResult.Error (distinct from
    // CallToolResult(success=false)). Watch for `[JsScriptingCallbackDispatcher] ... Session
    // mismatch` in
    // logcat.
    val wired = connectAndWire()
    try {
      val sessionId = SessionId("callback-forged-session")
      val ctx = makeExecutionContext(sessionId)
      val tool = buildExecutableTool(wired, "callBadSession", "{}")
      val result = tool.execute(ctx)

      val success =
        result as? TrailblazeToolResult.Success
          ?: error("Expected Success wrapping the callback's Error envelope, got: $result")
      val inner = Json.parseToJsonElement(success.message!!).jsonObject
      assertEquals(
        "Session mismatch must surface as JsScriptingCallbackResult.Error (type=error)",
        "error",
        inner["type"]?.jsonPrimitive?.content,
      )
      val errorMessage = inner["message"]?.jsonPrimitive?.content.orEmpty()
      assertTrue(
        "Error message should cite session mismatch; got: $errorMessage",
        errorMessage.contains("Session mismatch"),
      )
    } finally {
      wired.session.shutdown()
    }
  }

  @Test
  fun bundleHandler_withoutCallbackContext_envelopeOmittedAndHandlerFails() = runBlocking {
    // Covers the "outer bundle tool dispatched with NO callback context" case — the
    // envelope is omitted, the registry never gets an entry, and any callback attempt
    // from the bundled handler would have to forge the invocation_id entirely. We can
    // exercise this by constructing the BundleTrailblazeTool without threading through
    // the JsScriptingCallbackContext so the registry remains empty, then asserting the callback
    // fixture surfaces the `not found` error the Kotlin dispatcher returns.
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val sessionId = SessionId("callback-no-registry")
      val ctx = makeExecutionContext(sessionId)
      // Note: no JsScriptingCallbackContext threaded, so this execute() doesn't register an
      // invocation. The fixture's outerCompose would then throw because _meta.trailblaze
      // is omitted — instead we exercise `callBadSession` which forges a session id and
      // invocation id both, guaranteeing the registry lookup misses.
      // Hand-build the tool rather than going through the repo so we don't accidentally
      // pick up a cached JsScriptingCallbackContext from an earlier test's wiring.
      val noCallbackTool =
        BundleTrailblazeTool(
          sessionProvider = { session },
          advertisedName = ToolName("callBadSession"),
          args = JsonObject(emptyMap()),
          callbackContext = null,
        )
      val result = noCallbackTool.execute(ctx)
      // Without a callback context the outer's own envelope is omitted, so the fixture's
      // `readTrailblazeMeta` throws → the fixture catches and returns isError=true.
      val error = result as? TrailblazeToolResult.Error.ExceptionThrown
      assertNotNull("Expected ExceptionThrown without envelope, got: $result", error)
      // Relax the assertion to just confirm an error came back: the exact message is the
      // fixture's JS-side throw, which isn't part of the Kotlin contract.
    } finally {
      session.shutdown()
    }
  }

  @Test
  fun fixtureBundle_advertisesExpectedTools() = runBlocking {
    // Sanity on the fixture — the three-tool advertised shape is what every other test in
    // this class relies on. If the fixture JS breaks `tools/list`, these tests would fail
    // in obscure ways; this test fails fast with a readable diff.
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val response =
        session.client.listTools(io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest())
      val advertised = response.tools.map { it.name }.toSet()
      val expected = setOf("outerCompose", "callInvalidTool", "callBadSession")
      assertEquals("Fixture must advertise exactly the expected tool names", expected, advertised)
    } finally {
      session.shutdown()
    }
  }

  companion object {
    /**
     * Name of the Kotlin-side callback target we register into the test repo. Kept as a shared
     * constant because both the Kotlin registration and the JS fixture name it — a rename without
     * updating both silently breaks the composition assertion.
     */
    private const val KOTLIN_CALLBACK_TARGET_NAME: String = "reverseEcho"
  }
}
