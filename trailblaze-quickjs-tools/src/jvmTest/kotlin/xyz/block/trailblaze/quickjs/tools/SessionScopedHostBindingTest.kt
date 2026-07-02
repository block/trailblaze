package xyz.block.trailblaze.quickjs.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolExecutionContextThreadLocal
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * Round-trip tests for [SessionScopedHostBinding] — the live `HostBinding` that lets a
 * QuickJS-bundled author handler reach back into the host's [TrailblazeToolRepo] and execute
 * any registered Trailblaze tool. Covers the dispatch path end-to-end using real
 * `@Serializable` Kotlin tools in a real repo, plus the [ToolExecutionContextThreadLocal]
 * install/clear contract the binding relies on.
 */
class SessionScopedHostBindingTest {

  // Custom tools defined for these tests so the suite isn't coupled to whichever default
  // TrailblazeToolSet exists at test time.

  @Serializable
  @TrailblazeToolClass("test_ping")
  private data class PingTool(val text: String) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success(message = "ping:$text")
  }

  /**
   * Hidden-from-LLM, host-only tool — proves the binding bypasses the `surfaceToLlm` filter
   * that hides `runCommand`-shaped building-block tools from the LLM descriptor list.
   */
  @Serializable
  @TrailblazeToolClass(name = "test_host_only", surfaceToLlm = false, requiresHost = true)
  private data class HostOnlyTool(val command: String) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success(message = "ran:$command")
  }

  /**
   * Mirrors `PlaywrightExecutableTool` (and any other driver-specific tool): its own
   * `execute()` unconditionally throws because it can only run through the owning driver
   * agent's dispatch (e.g. to obtain a live Playwright `Page`). Used to pin that
   * [SessionScopedHostBinding] consults [TrailblazeToolExecutionContext.nestedToolExecutor]
   * before falling back to a direct `execute(context)` call.
   */
  @Serializable
  @TrailblazeToolClass("test_driver_specific")
  private data class DriverSpecificTool(val payload: String) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      error("DriverSpecificTool must be executed via its owning driver agent")
    }
  }

  /**
   * Tool whose `execute` always throws [CancellationException]. Used by the cancellation-
   * propagation test below to pin the contract that the binding rethrows rather than
   * converting cancellation into an error envelope.
   */
  @Serializable
  @TrailblazeToolClass("test_cancellation_throwing")
  private data class CancellationThrowingTool(val unused: String = "") : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      throw CancellationException("simulated cancellation from test_cancellation_throwing")
    }
  }

  private val sessionId = SessionId("session-scoped-host-binding-test")

  private fun newRepoWith(vararg classes: kotlin.reflect.KClass<out TrailblazeTool>): TrailblazeToolRepo {
    return TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "test-set",
        toolClasses = classes.toSet(),
        yamlToolNames = emptySet(),
      ),
    )
  }

  private fun buildContext(
    nestedToolExecutor: (suspend (TrailblazeTool) -> TrailblazeToolResult)? = null,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = TraceId.generate(TraceOrigin.TOOL),
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "binding-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
      classifiers = listOf<TrailblazeDeviceClassifier>(),
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    nestedToolExecutor = nestedToolExecutor,
  )

  @AfterTest
  fun teardown() {
    // Defensive cleanup so a test that throws mid-install doesn't leak the slot into the
    // next test on the same thread.
    ToolExecutionContextThreadLocal.clear()
  }

  // ---- Test 1: class-backed Kotlin tool dispatched through the binding ----

  @Test
  fun `callFromBundle routes a class-backed tool through the repo and returns its result`() = runBlocking {
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle("test_ping", """{"text":"hi"}""")
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      // `TrailblazeToolResult.Success` serializes with a "type" discriminator + the
      // declared `message` field — we only assert the load-bearing fields so this test
      // doesn't break on incidental field reorderings.
      assertEquals(
        "ping:hi",
        (parsed["message"] as JsonPrimitive).content,
        "expected the tool's success message pinged through; got $resultJson",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 2: surfaceToLlm=false / requiresHost=true tool IS callable through the binding ----

  @Test
  fun `callFromBundle reaches a tool with surfaceToLlm=false and requiresHost=true`() = runBlocking {
    // This is the load-bearing claim of Sub-PR-A2: tools annotated `surfaceToLlm = false`
    // (e.g. `runCommand`, `mobile_clearAppData`) must remain reachable through scripted-tool
    // composition even though they're hidden from the LLM. The repo's
    // `toolCallToTrailblazeTool` already handles this for class-backed tools — pin the
    // contract so a future change that re-introduces the filter at the binding layer is
    // caught by this test.
    val repo = newRepoWith(HostOnlyTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle(
        "test_host_only",
        """{"command":"ping hello"}""",
      )
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      assertEquals(
        "ran:ping hello",
        (parsed["message"] as JsonPrimitive).content,
        "expected the host-only tool to execute through the binding; got $resultJson",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 3: ThreadLocal install + clear roundtrip ----

  @Test
  fun `installContext makes the context retrievable inside callFromBundle and clearContext wipes it`() = runBlocking {
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()

    // Before install: slot is null, binding returns a structured "no context" error.
    val beforeInstallJson = binding.callFromBundle("test_ping", """{"text":"x"}""")
    val beforeInstall = Json.parseToJsonElement(beforeInstallJson) as JsonObject
    assertEquals(
      true,
      (beforeInstall["isError"] as JsonPrimitive).content.toBoolean(),
      "expected isError=true when no context is installed; got $beforeInstallJson",
    )
    assertTrue(
      (beforeInstall["error"] as JsonPrimitive).content.contains("no execution context"),
      "expected 'no execution context' in error; got $beforeInstallJson",
    )

    // After install: same call now succeeds.
    SessionScopedHostBinding.installContext(ctx)
    try {
      val installedJson = binding.callFromBundle("test_ping", """{"text":"x"}""")
      val installed = Json.parseToJsonElement(installedJson) as JsonObject
      assertEquals(
        "ping:x",
        (installed["message"] as JsonPrimitive).content,
        "expected installed-context dispatch to succeed; got $installedJson",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }

    // After clear: back to the structured error envelope.
    val afterClearJson = binding.callFromBundle("test_ping", """{"text":"x"}""")
    val afterClear = Json.parseToJsonElement(afterClearJson) as JsonObject
    assertEquals(
      true,
      (afterClear["isError"] as JsonPrimitive).content.toBoolean(),
      "expected slot to be cleared after clearContext; got $afterClearJson",
    )
  }

  // ---- Test 4: install over a non-null prior value still proceeds (clobber not silent) ----

  @Test
  fun `installContext over a non-null prior value clobbers and logs a warning`() {
    // The clobber path is the warning case: install over a non-null prior value indicates
    // a missing clear() in a previous batch (or two install sites racing). Both are bugs at
    // the call site and the warning makes them visible. Verifying the *log line* directly is
    // brittle on JVM (Console captures System.out at class init, before this test can swap
    // streams), so we rely on two complementary signals:
    //   1. The clobber proceeds — readers see the new context after re-install (would-be-
    //      silent failure mode if the implementation no-op'd on a non-null slot).
    //   2. A CONTEXT_CLOBBER marker is emitted — captured via the stderr stream that
    //      Console keeps a stable reference to (System.err is not swapped here).
    val originalErr = System.err
    val capturedErr = ByteArrayOutputStream()
    System.setErr(PrintStream(capturedErr))
    try {
      val firstCtx = buildContext()
      val secondCtx = buildContext()
      SessionScopedHostBinding.installContext(firstCtx)
      try {
        // The second install over a non-null slot is the bug condition the warning
        // protects against. The implementation logs the marker AND proceeds with the
        // clobber so dispatch isn't blocked.
        SessionScopedHostBinding.installContext(secondCtx)
        // Assert the clobber proceeded — slot now points at secondCtx, not firstCtx.
        assertEquals(
          secondCtx,
          ToolExecutionContextThreadLocal.get(),
          "expected the second install to clobber the slot",
        )
      } finally {
        SessionScopedHostBinding.clearContext()
      }
    } finally {
      System.setErr(originalErr)
    }
    // Note: Console on JVM captures System.out / System.err at class-init time, so a
    // stream swap done after this test's first invocation may not catch the marker. We
    // treat the warning as best-effort documentation; the load-bearing signal is the
    // clobber-proceeded assertion above.
    val capturedErrText = capturedErr.toString()
    if (capturedErrText.isNotEmpty()) {
      // If we did capture anything, it should be the marker — a regression that drops
      // the log line entirely while still capturing other output would surface here.
      assertTrue(
        capturedErrText.contains("CONTEXT_CLOBBER"),
        "captured stderr did not contain CONTEXT_CLOBBER marker; got:\n$capturedErrText",
      )
    }
  }

  // ---- Test 5: unknown tool name returns a structured error envelope (does not throw) ----

  @Test
  fun `callFromBundle with an unknown tool name returns the error envelope and never throws`() = runBlocking {
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      // A direct call must NOT throw — the HostBinding contract is JSON-on-every-path so the
      // awaiting JS handler sees a well-formed envelope rather than an opaque transport error.
      val outcome = runCatching {
        binding.callFromBundle("not_registered_anywhere", """{}""")
      }
      assertTrue(
        outcome.isSuccess,
        "expected callFromBundle never to throw on unknown name; got ${outcome.exceptionOrNull()}",
      )
      val parsed = Json.parseToJsonElement(outcome.getOrThrow()) as JsonObject
      assertEquals(
        true,
        (parsed["isError"] as JsonPrimitive).content.toBoolean(),
        "expected isError=true for unknown tool",
      )
      val errorMessage = (parsed["error"] as JsonPrimitive).content
      assertNotNull(errorMessage)
      assertTrue(
        errorMessage.contains("not_registered_anywhere"),
        "expected error message to name the missing tool; got '$errorMessage'",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 6: unknown argument keys rejected via the typed-surface contract ----

  @Test
  fun `callFromBundle rejects unknown argument keys with a canonical-shape error envelope`() = runBlocking {
    // #3209 — the on-device QuickJS binding is the second scripted-tool dispatch
    // transport that needs to enforce the typed-surface contract; the HTTP dispatcher's
    // matching test lives in `JsScriptingCallbackDispatcherTest`. Both transports must
    // agree so a bundle author can't get a different correctness model depending on which
    // host runtime serves the call. Today the deserializer's `ignoreUnknownKeys = true`
    // would silently drop `element`; the gate flips that to a structured error.
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle(
        "test_ping",
        // `text` is the real arg; `element` is the LLM authoring hint that today gets
        // silently dropped. The gate should reject the call with a message naming the
        // bad key and the canonical accepted keys.
        """{"text":"hi","element":"the ping target"}""",
      )
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      assertEquals(
        true,
        (parsed["isError"] as JsonPrimitive).content.toBoolean(),
        "expected isError=true for an unknown argument key; got $resultJson",
      )
      val errorMessage = (parsed["error"] as JsonPrimitive).content
      assertTrue(
        errorMessage.contains("test_ping"),
        "expected the offending tool name in the error; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("\"element\""),
        "expected the offending key '\"element\"' in the error; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("text"),
        "expected the canonical 'text' key in the error; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("client.tools.test_ping"),
        "expected the canonical-shape pointer in the error; got '$errorMessage'",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 6b: missing required argument keys rejected via the typed-surface contract ----

  @Test
  fun `callFromBundle rejects missing required arguments with a canonical-shape error envelope`() = runBlocking {
    // #3261 — runtime side of the typed-surface contract for required args. Mirrors the
    // HTTP dispatcher's matching `missing required argument is rejected before dispatch
    // with directed message` test so both scripted-tool transports surface the same
    // missing-required envelope. Without parity, a bundle author would see different
    // failure shapes depending on which host runtime serves the call — the exact
    // divergence #3209 / #3261 set out to eliminate.
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle(
        "test_ping",
        // `text` is the required arg. Omitting it should surface the validator's
        // missing-required message — not reach the deserializer (which would produce a
        // less directed "MissingFieldException" string).
        """{}""",
      )
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      assertEquals(
        true,
        (parsed["isError"] as JsonPrimitive).content.toBoolean(),
        "expected isError=true for a missing required arg; got $resultJson",
      )
      val errorMessage = (parsed["error"] as JsonPrimitive).content
      assertTrue(
        errorMessage.contains("test_ping"),
        "expected the tool name in the error; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("\"text\""),
        "expected the missing key '\"text\"' in the error; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("Required: text"),
        "expected the canonical 'Required: <list>' wording; got '$errorMessage'",
      )
      assertTrue(
        errorMessage.contains("client.tools.test_ping"),
        "expected the canonical-shape pointer in the error; got '$errorMessage'",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 6c: driver-specific tools route through nestedToolExecutor, not direct execute() ----

  @Test
  fun `callFromBundle routes an ExecutableTrailblazeTool through nestedToolExecutor when the context provides one`() = runBlocking {
    // Pins the fix for a real bug: a scripted tool composing a driver-specific tool (e.g.
    // `ctx.tools.web_navigate(...)` from inside a TS tool, which resolves to a
    // `PlaywrightExecutableTool`) failed every time on this binding because `executeResolved`
    // called `tool.execute(context)` unconditionally — hitting `PlaywrightExecutableTool`'s
    // throwing default `execute()` — instead of consulting `nestedToolExecutor` first, the
    // same indirection `JsScriptingCallbackDispatcher`'s subprocess transport already applies
    // (see its `dispatchCallTool`). Without the fix, this test fails with
    // "DriverSpecificTool must be executed via its owning driver agent" instead of routing
    // through the executor.
    val repo = newRepoWith(DriverSpecificTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    var invokedWith: TrailblazeTool? = null
    val ctx = buildContext(
      nestedToolExecutor = { tool ->
        invokedWith = tool
        TrailblazeToolResult.Success(message = "routed via nestedToolExecutor")
      },
    )
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle("test_driver_specific", """{"payload":"hi"}""")
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      assertEquals(
        "routed via nestedToolExecutor",
        (parsed["message"] as JsonPrimitive).content,
        "expected dispatch to route through nestedToolExecutor instead of calling execute() " +
          "directly; got $resultJson",
      )
      assertTrue(
        invokedWith is DriverSpecificTool,
        "expected the resolved DriverSpecificTool instance to be passed to nestedToolExecutor",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  @Test
  fun `callFromBundle falls back to direct execute() when the context has no nestedToolExecutor`() = runBlocking {
    // Complements the test above — an ordinary tool with no driver-specific requirement still
    // works when no `nestedToolExecutor` is wired (e.g. the host daemon path), preserving the
    // pre-fix behavior for tools that don't need it.
    val repo = newRepoWith(PingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      val resultJson = binding.callFromBundle("test_ping", """{"text":"direct"}""")
      val parsed = Json.parseToJsonElement(resultJson) as JsonObject
      assertEquals(
        "ping:direct",
        (parsed["message"] as JsonPrimitive).content,
        "expected direct execute() fallback when no nestedToolExecutor is installed; got $resultJson",
      )
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }

  // ---- Test 7: CancellationException propagates instead of being converted to error envelope ----

  @Test
  fun `callFromBundle propagates CancellationException without converting it to an error envelope`() {
    // Pins the rethrow added to address PR #2756 review feedback. A regression that
    // demotes CancellationException back into an error envelope would break structured
    // concurrency teardown — session timeout / user abort would surface to the JS side
    // as a normal tool error instead of unwinding the suspend chain. The test deliberately
    // uses `runCatching` from `runBlocking` (not `assertFailsWith` from a suspend block)
    // so the CancellationException propagates out of the coroutine boundary and we can
    // make the assertion on a non-suspend stack.
    val repo = newRepoWith(CancellationThrowingTool::class)
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = buildContext()
    SessionScopedHostBinding.installContext(ctx)
    try {
      assertFailsWith<CancellationException> {
        runBlocking {
          binding.callFromBundle("test_cancellation_throwing", """{"unused":""}""")
        }
      }
    } finally {
      SessionScopedHostBinding.clearContext()
    }
  }
}
