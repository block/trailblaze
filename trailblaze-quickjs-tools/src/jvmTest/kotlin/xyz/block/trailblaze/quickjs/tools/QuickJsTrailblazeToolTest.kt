package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.datetime.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Direct coverage for [QuickJsTrailblazeTool.execute] — the path the LLM-side dispatch
 * traverses when a QuickJS-bundle tool is selected. The launcher tests bypass this method
 * (they call `host.callTool(...)` directly), so this suite pins the cancellation,
 * error-mapping, malformed-envelope, and ctx-shape branches that would otherwise have no
 * direct test.
 */
class QuickJsTrailblazeToolTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() {
    runBlocking { hosts.forEach { runCatching { it.shutdown() } } }
    hosts.clear()
  }

  @Test
  fun `execute maps an isError envelope to TrailblazeToolResult Error`() = runBlocking {
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["explode"] = {
        name: "explode",
        spec: {},
        handler: async () => ({
          isError: true,
          content: [{ type: "text", text: "tool said no" }],
        }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("explode"), buildJsonObject {})
    val result = tool.execute(buildContext())
    assertTrue("expected Error, got $result") { result is TrailblazeToolResult.Error }
    val message = (result as TrailblazeToolResult.Error).errorMessage
    assertTrue("expected the rendered text in the error, got: $message") {
      message.contains("tool said no")
    }
  }

  @Test
  fun `execute end-to-end maps a handler THROW through to Error ExceptionThrown with JS stack`() = runBlocking {
    // End-to-end contract a session-log debugger actually relies on: handler throws → host's
    // JS-side catch builds an `isError: true` envelope with `name + message + stack` →
    // `QuickJsTrailblazeTool.execute`'s `toTrailblazeToolResult` maps the envelope to
    // `Error.ExceptionThrown` → downstream `TrailblazeToolLog.exceptionMessage` carries the
    // stack. Each hop is covered by a sibling unit test, but the full pipeline isn't —
    // pinning it here guards against either side drifting in a way that drops the stack.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["thrower"] = {
        name: "thrower",
        spec: {},
        handler: async () => { throw new Error("end-to-end boom"); },
      };
      """.trimIndent(),
      bundleFilename = "e2e-thrower-bundle.js",
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("thrower"), buildJsonObject {})
    val result = tool.execute(buildContext())

    assertTrue("expected Error.ExceptionThrown, got $result") {
      result is TrailblazeToolResult.Error.ExceptionThrown
    }
    val message = (result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage
    // The JS-side `Error.name + ': ' + message` prefix is preserved through the Kotlin mapping.
    assertTrue("expected 'Error: ' prefix in: $message") { message.startsWith("Error: ") }
    assertTrue("expected handler message in: $message") { message.contains("end-to-end boom") }
    // The whole point of the QuickJS-side catch — a bundle filename + line/col stack frame
    // must reach the Kotlin-side error message intact. Without the catch, the JS stack would
    // be lost at the QuickJS → quickjs-kt → Kotlin throwable boundary, and a session-log
    // reader would see "boom" with no breadcrumb to the failing line.
    assertTrue("expected bundle filename in JS stack in: $message") {
      message.contains("e2e-thrower-bundle.js")
    }
  }

  @Test
  fun `execute treats a missing content array as a structural error`() = runBlocking {
    // A bundle author who returns `{}` or `{ result: 42 }` would otherwise get
    // `Success(message=null)`, hiding the bug behind a no-op pass. Structural errors
    // bubble up here with a directed message naming the offending tool.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["malformed"] = {
        name: "malformed",
        spec: {},
        handler: async () => ({ result: 42 }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("malformed"), buildJsonObject {})
    val result = tool.execute(buildContext())
    assertTrue("expected Error for malformed envelope, got $result") {
      result is TrailblazeToolResult.Error
    }
    val message = (result as TrailblazeToolResult.Error).errorMessage
    assertTrue("expected the message to name the tool, got: $message") {
      message.contains("'malformed'")
    }
    assertTrue("expected the message to mention `content`, got: $message") {
      message.contains("`content`")
    }
  }

  @Test
  fun `execute returns Success with rendered text content joined`() = runBlocking {
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["joinTexts"] = {
        name: "joinTexts",
        spec: {},
        handler: async (args) => ({
          content: [
            { type: "text", text: "hello" },
            { type: "text", text: args.who },
          ],
        }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("joinTexts"), buildJsonObject { put("who", "Sam") })
    val result = tool.execute(buildContext())
    assertTrue("expected Success, got $result") { result is TrailblazeToolResult.Success }
    val message = (result as TrailblazeToolResult.Success).message
    assertEquals("hello\nSam", message)
  }

  @Test
  fun `execute populates ctx with sessionId and device platform and driver`() = runBlocking {
    // The handler returns its `ctx` parameter as JSON text so we can assert the shape
    // QuickJsTrailblazeTool.buildCtxEnvelope produced.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["readCtx"] = {
        name: "readCtx",
        spec: {},
        handler: async (_args, ctx) => ({
          content: [{ type: "text", text: JSON.stringify(ctx) }],
        }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("readCtx"), buildJsonObject {})
    val result = tool.execute(buildContext())
    val rendered = (result as TrailblazeToolResult.Success).message!!
    val ctx = kotlinx.serialization.json.Json.parseToJsonElement(rendered).jsonObject
    assertEquals(TEST_SESSION_ID, ctx["sessionId"]!!.jsonPrimitive.content)
    val device = ctx["device"]!!.jsonObject
    assertEquals("ANDROID", device["platform"]!!.jsonPrimitive.content)
    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.yamlKey,
      device["driver"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `execute propagates CancellationException so structured concurrency stays intact`() =
    runBlocking {
      // The `catch (e: CancellationException) { throw e }` ordering matters: without the
      // explicit re-throw, the generic `catch (e: Exception)` branch would swallow it and
      // return an Error instead, breaking session-teardown / agent-abort cancellation
      // propagation. Pin the ordering.
      val host = connect(
        """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["slow"] = {
        name: "slow",
        spec: {},
        handler: async () => {
          // Yield a few microtasks so the surrounding job can be cancelled during the
          // suspension. Then return — by the time we resume, cancellation should have
          // fired, and execute()'s rethrow must propagate it.
          for (let i = 0; i < 1000; i++) await Promise.resolve();
          return { content: [{ type: "text", text: "should-not-see-this" }] };
        },
      };
      """.trimIndent(),
      )
      val tool = QuickJsTrailblazeTool(host, ToolName("slow"), buildJsonObject {})
      var caught = false
      try {
        coroutineScope {
          val deferred = async { tool.execute(buildContext()) }
          deferred.cancel(CancellationException("cancel from test"))
          deferred.await()
        }
      } catch (e: CancellationException) {
        caught = true
      } catch (e: Throwable) {
        fail("expected CancellationException to propagate, got ${e::class.simpleName}: ${e.message}")
      }
      assertTrue("expected CancellationException to be thrown") { caught }
    }

  @Test
  fun `execute threads handler structuredContent onto Success structuredContent`() = runBlocking {
    // On-device counterpart to the subprocess `CallToolResultMapperTest` structured-content
    // case. The dispatcher requires the two transports to stay symmetric — a regression
    // that only hits one would let a TS scripted tool's typed `result` silently flatten to
    // text on whichever path was missed. Pin the on-device path here.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["typedDemo"] = {
        name: "typedDemo",
        spec: {},
        handler: async () => ({
          content: [{ type: "text", text: "(structured)" }],
          structuredContent: { formatted: "prefix:msg", inputLength: 3 },
        }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("typedDemo"), buildJsonObject {})
    val result = tool.execute(buildContext())
    val success = result as? TrailblazeToolResult.Success
      ?: fail("expected Success, got $result")
    val structured = success.structuredContent?.jsonObject
      ?: fail("expected structuredContent on Success, got null")
    assertEquals("prefix:msg", structured["formatted"]!!.jsonPrimitive.content)
    assertEquals("3", structured["inputLength"]!!.jsonPrimitive.content)
  }

  @Test
  fun `execute leaves structuredContent null when handler returns text only`() = runBlocking {
    // Negative companion: a handler that doesn't populate `structuredContent` must not have
    // a stub synthesized onto Success — otherwise every legacy text-only tool would start
    // tripping the TS SDK's "unwrap structured payload" branch and surface null/empty
    // objects in place of the expected string.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["textOnly"] = {
        name: "textOnly",
        spec: {},
        handler: async () => ({
          content: [{ type: "text", text: "plain text" }],
        }),
      };
      """.trimIndent(),
    )
    val tool = QuickJsTrailblazeTool(host, ToolName("textOnly"), buildJsonObject {})
    val result = tool.execute(buildContext())
    val success = result as? TrailblazeToolResult.Success
      ?: fail("expected Success, got $result")
    assertEquals("plain text", success.message)
    assertEquals(null, success.structuredContent)
  }

  private suspend fun connect(
    bundleJs: String,
    bundleFilename: String = "tools.bundle.js",
  ): QuickJsToolHost {
    val host = QuickJsToolHost.connect(bundleJs, bundleFilename = bundleFilename)
    hosts.add(host)
    return host
  }

  private fun buildContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "quickjs-trailblaze-tool-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId(TEST_SESSION_ID), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  companion object {
    private const val TEST_SESSION_ID = "quickjs-tool-exec-test"
  }
}
