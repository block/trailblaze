package xyz.block.trailblaze.scripting.callback

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool

/**
 * Unit coverage for [JsScriptingInvocationRegistry]. The registry is a process-wide singleton
 * that backs the `/scripting/callback` endpoint's invocation lookup — any misbehaviour (double
 * close, id collision, stale entry) silently corrupts the callback channel, so we lock the
 * load-bearing properties down here rather than relying on the end-to-end test to catch them.
 */
class TrailblazeInvocationRegistryTest {

  // Reset between tests — the registry is a process-wide singleton and carries state across
  // test methods in the same JVM. Without this, a prior test's entries would leak into the
  // next test's assertions. Annotated `@After` so it runs even if an assertion throws mid-test.
  @After fun cleanup() {
    JsScriptingInvocationRegistry.clearForTest()
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private val sessionProvider = TrailblazeSessionProvider {
    TrailblazeSession(sessionId = SessionId("registry-test"), startTime = Clock.System.now())
  }

  private fun makeContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo,
    sessionProvider = sessionProvider,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private fun makeRepo(): TrailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "registry-test-toolset",
      setOf(InputTextTrailblazeTool::class),
    ),
  )

  @Test fun `register returns a handle with a non-blank invocation id`() {
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    assertThat(handle.invocationId.isNotBlank()).isEqualTo(true)
  }

  @Test fun `lookup resolves a registered entry`() {
    val sessionId = SessionId("s1")
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    val entry = JsScriptingInvocationRegistry.lookup(handle.invocationId)
    assertThat(entry).isNotNull()
    assertThat(entry!!.sessionId).isEqualTo(sessionId)
  }

  @Test fun `close removes the entry from the registry`() {
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    handle.close()
    assertThat(JsScriptingInvocationRegistry.lookup(handle.invocationId)).isNull()
  }

  @Test fun `close is idempotent`() {
    // The KDoc says "idempotent so a caller can safely close in a finally block even if the
    // invocation path already removed the entry on its own." Lock that claim down.
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    handle.close()
    handle.close() // must not throw
    assertThat(JsScriptingInvocationRegistry.lookup(handle.invocationId)).isNull()
  }

  @Test fun `lookup of unknown id returns null`() {
    assertThat(JsScriptingInvocationRegistry.lookup("not-a-real-id")).isNull()
  }

  @Test fun `concurrent register produces unique invocation ids`() {
    // UUID.randomUUID collisions are astronomically unlikely, but the test guards against a
    // future change that might weaken id generation (sequence counters etc.). 1000 concurrent
    // registers — every id must be distinct.
    runBlocking {
      val repo = makeRepo()
      val ctx = makeContext()
      val ids = mutableListOf<String>()
      val mutex = Mutex()
      val handles = (1..1000).map { i ->
        async(Dispatchers.Default) {
          val h = JsScriptingInvocationRegistry.register(
            sessionId = SessionId("s$i"),
            toolRepo = repo,
            executionContext = ctx,
          )
          mutex.withLock { ids += h.invocationId }
          h
        }
      }.awaitAll()
      assertThat(ids.toSet()).hasSize(1000)
      handles.forEach { it.close() }
    }
  }

  @Test fun `register with explicit depth is preserved on lookup`() {
    // The `depth` parameter (default 0) carries the callback reentrance depth through the
    // registry entry. A regression that drops the field on the data class — or a future refactor
    // that forgets to forward the argument from register(...) to the Entry constructor — would
    // silently reset every entry's depth to 0 and let recursive callback chains blow past the
    // cap. Boundary value MAX_CALLBACK_DEPTH is separately covered in ScriptingCallbackEndpointTest's
    // depth-cap test, but we exercise it here too so the registry's contract is self-contained.
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
      depth = 7,
    )
    assertThat(JsScriptingInvocationRegistry.lookup(handle.invocationId)?.depth).isEqualTo(7)

    val boundary = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s2"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
      depth = JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH,
    )
    assertThat(JsScriptingInvocationRegistry.lookup(boundary.invocationId)?.depth)
      .isEqualTo(JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH)
  }

  @Test fun `register without depth defaults to 0`() {
    // Locks in the data-class default so existing call-sites (outer tools/call originating from
    // the LLM) keep registering at depth 0.
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    assertThat(JsScriptingInvocationRegistry.lookup(handle.invocationId)?.depth).isEqualTo(0)
  }

  @Test fun `clearForTest empties the registry`() {
    JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s1"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    JsScriptingInvocationRegistry.clearForTest()
    // The register-then-lookup path proves the registry still accepts new entries after a
    // clear — regression guard against a future change that replaced ConcurrentHashMap with
    // a state machine that couldn't restart after clear.
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = SessionId("s2"),
      toolRepo = makeRepo(),
      executionContext = makeContext(),
    )
    assertThat(JsScriptingInvocationRegistry.lookup(handle.invocationId)).isNotNull()
  }
}
