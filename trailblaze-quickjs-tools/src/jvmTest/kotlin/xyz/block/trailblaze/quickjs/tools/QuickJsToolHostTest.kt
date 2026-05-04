package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Round-trip tests for [QuickJsToolHost] using a hand-written JS bundle that simulates what
 * esbuild would emit when bundling an author's `tools.ts` against `@trailblaze/tools`.
 *
 * The bundle here inlines the shim's `globalThis.__trailblazeTools` registry directly — no
 * SDK npm package needed, no MCP. That matches the architecture of the new on-device runtime:
 * the author bundle is self-contained, registers handlers in a global, and the runtime
 * dispatches against that global.
 *
 * If these tests pass, the load-bearing claim of devlog
 * `2026-04-30-scripted-tools-not-mcp.md` is demonstrable: tools can be authored, bundled, and
 * dispatched in QuickJS without any MCP framing.
 */
class QuickJsToolHostTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  private suspend fun connect(bundleJs: String, hostBinding: HostBinding? = null): QuickJsToolHost {
    val host = QuickJsToolHost.connect(bundleJs, hostBinding = hostBinding)
    hosts.add(host)
    return host
  }

  @Test
  fun `bundle registers a tool and listTools surfaces it with its spec`() = runBlocking {
    val host = connect(
      // Inlined shim + one tool. Reproduces what esbuild would produce when bundling
      // `import { trailblaze } from "@trailblaze/tools"; trailblaze.tool(...)`.
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      const trailblaze = {
        tool(name, spec, handler) { tools[name] = { name, spec, handler }; },
      };
      trailblaze.tool(
        "greet",
        { description: "Say hi", inputSchema: { name: { type: "string" } } },
        async (args) => ({ content: [{ type: "text", text: "hi " + args.name }] }),
      );
      """.trimIndent(),
    )
    val registered = host.listTools()
    assertEquals(1, registered.size, "expected one tool, got ${registered.map { it.name }}")
    val greet = registered.single()
    assertEquals("greet", greet.name)
    assertEquals("Say hi", (greet.spec["description"] as JsonPrimitive).content)
  }

  @Test
  fun `callTool invokes the registered async handler and returns its result`() = runBlocking {
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["greet"] = {
        name: "greet",
        spec: { description: "Say hi" },
        handler: async (args) => ({ content: [{ type: "text", text: "hi " + args.name }] }),
      };
      """.trimIndent(),
    )
    val result = host.callTool("greet", buildJsonObject { put("name", "Sam") })
    val content = result["content"] as JsonArray
    val first = content.first().jsonObject
    assertEquals("text", (first["type"] as JsonPrimitive).content)
    assertEquals("hi Sam", (first["text"] as JsonPrimitive).content)
  }

  @Test
  fun `callTool throws when the named tool is not registered`() = runBlocking {
    val host = connect(
      // Empty registry — no tools registered.
      "globalThis.__trailblazeTools = {};",
    )
    val err = runCatching {
      host.callTool("nope", JsonObject(emptyMap()))
    }.exceptionOrNull()
    assertNotNull(err, "expected callTool to throw")
    assertTrue("expected error to mention the missing tool name; got: ${err.message}") {
      err.message.orEmpty().contains("nope")
    }
  }

  @Test
  fun `host binding lets a handler call another tool via globalThis __trailblazeCall`() = runBlocking {
    // Simulates the SDK's `trailblaze.call(name, args)` round-trip — author handler invokes
    // the host-installed binding, which delegates to a Kotlin lambda that we control here.
    // Crucially: no MCP, no in-process transport, no Client — just a Kotlin function the
    // bundle can `await` from JS.
    val binding = HostBinding { name, argsJson ->
      assertEquals("inner", name)
      assertEquals("""{"x":2}""", argsJson)
      // Return shape matches the SDK shim's TrailblazeToolResult expectation.
      """{"content":[{"type":"text","text":"inner-result"}]}"""
    }
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async (args) => {
          const innerResultJson = await globalThis.__trailblazeCall("inner", JSON.stringify({ x: 2 }));
          const inner = JSON.parse(innerResultJson);
          return { content: [{ type: "text", text: "outer wraps " + inner.content[0].text }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    val result = host.callTool("outer", JsonObject(emptyMap()))
    val text = (result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive
    assertEquals("outer wraps inner-result", text.content)
  }

  @Test
  fun `ctx is forwarded through to the handler when supplied`() = runBlocking {
    // Round-trips a session id through the ctx envelope. Proves the runtime correctly
    // serializes ctx and the handler can read it — the basis for handlers branching on
    // session, device, etc.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["getSessionId"] = {
        name: "getSessionId",
        spec: {},
        handler: async (args, ctx) => ({
          content: [{ type: "text", text: ctx ? ctx.sessionId : "no-ctx" }],
        }),
      };
      """.trimIndent(),
    )
    val ctx = buildJsonObject { put("sessionId", "session-abc") }
    val result = host.callTool("getSessionId", JsonObject(emptyMap()), ctx = ctx)
    val text = (result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive
    assertEquals("session-abc", text.content)
  }

  // ---- Pinned contracts: lifecycle, error paths, and load-bearing invariants ----

  @Test
  fun `connect throws and frees the engine when the bundle has a syntax error`() = runBlocking {
    // The `runCatching { quickJs.close() }` cleanup before rethrow in `connect` is the
    // engine's only protection against a native-handle leak on malformed bundles. A
    // regression here would silently leak QuickJS engines on every bad bundle.
    val err = runCatching {
      QuickJsToolHost.connect("this is not valid JavaScript ((", bundleFilename = "broken.js")
    }.exceptionOrNull()
    assertNotNull(err, "expected connect to throw on a malformed bundle")
    // A subsequent connect must succeed — proves the previous failure didn't poison the
    // module's global state (e.g. by leaving the QuickJS native init in a bad spot).
    val good = connect("globalThis.__trailblazeTools = {};")
    assertEquals(0, good.listTools().size)
  }

  @Test
  fun `callTool propagates handler exceptions to the caller`() = runBlocking {
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["boom"] = {
        name: "boom",
        spec: {},
        handler: async () => { throw new Error("boom from handler"); },
      };
      """.trimIndent(),
    )
    val err = runCatching {
      host.callTool("boom", JsonObject(emptyMap()))
    }.exceptionOrNull()
    assertNotNull(err, "expected callTool to throw when the handler throws")
    assertTrue("expected handler error message in: ${err.message}") {
      err.message.orEmpty().contains("boom from handler")
    }
  }

  @Test
  fun `evalMutex serializes concurrent callTool invocations so results don't cross-talk`() = runBlocking {
    // Pins the load-bearing concurrency claim on `evalMutex`. Each handler suspends briefly
    // (so concurrent dispatches actually overlap in time) and returns a result derived from
    // its `marker` arg. With the mutex, every call's `__trailblazeLastResult` write is read
    // back before another call clobbers it. Without the mutex, this test would flake or
    // fail outright as different markers swap into each other's results.
    // QuickJS doesn't expose `setTimeout`, but `await Promise.resolve()` yields a microtask
    // — enough to surface the dispatch/read interleave the mutex is guarding. Without the
    // mutex, the writeback to `__trailblazeLastResult` from a second coroutine's dispatch
    // can overwrite the first's value before the first reads it, producing cross-talk where
    // marker values get attributed to the wrong call.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["replayMarker"] = {
        name: "replayMarker",
        spec: {},
        handler: async (args) => {
          await Promise.resolve();
          await Promise.resolve();
          return { content: [{ type: "text", text: "marker=" + args.marker }] };
        },
      };
      """.trimIndent(),
    )
    val markers = (1..10).map { "m$it" }
    val results = coroutineScope {
      markers.map { m ->
        async {
          host.callTool("replayMarker", buildJsonObject { put("marker", m) })
        }
      }.awaitAll()
    }
    val seen = results.map {
      ((it["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
    }
    assertEquals(markers.map { "marker=$it" }, seen, "results were reordered or cross-talked")
  }

  @Test
  fun `callTool round-trips U+2028 and U+2029 in args without breaking JS source parsing`() = runBlocking {
    // Regression test for the jsString escape introduced in commit b3c82e6a7. JSON encodes
    // U+2028 / U+2029 as themselves; in classic JS source they are LineTerminator characters
    // that break a multi-line spliced expression. Without the escape, this test fails with a
    // QuickJS SyntaxError before the handler runs.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["replayText"] = {
        name: "replayText",
        spec: {},
        handler: async (args) => ({ content: [{ type: "text", text: String(args.text) }] }),
      };
      """.trimIndent(),
    )
    val needle = "before line-sep para-sep-after"
    val result = host.callTool("replayText", buildJsonObject { put("text", needle) })
    val received =
      ((result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
    assertEquals(needle, received)
  }

  @Test
  fun `listTools returns empty list when no tools are registered`() = runBlocking {
    val host = connect("globalThis.__trailblazeTools = {};")
    assertEquals(0, host.listTools().size)
  }

  @Test
  fun `listTools defaults missing spec to empty object`() = runBlocking {
    // Pins the `spec: t.spec || {}` fallback in `listTools`'s JS projection — a tool that
    // registers without a `spec` field still surfaces with a usable RegisteredToolSpec.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["specless"] = { name: "specless", handler: async () => ({ content: [] }) };
      """.trimIndent(),
    )
    val tool = host.listTools().single()
    assertEquals("specless", tool.name)
    assertEquals(0, tool.spec.size, "expected empty fallback spec, got ${tool.spec}")
  }

  @Test
  fun `shutdown is idempotent and safe to call multiple times`() = runBlocking {
    // The kdoc claims idempotency; the implementation wraps `quickJs.close()` in
    // `runCatching`, so a future change that drops the runCatching could break this without
    // any test catching it. Pin the contract.
    val host = QuickJsToolHost.connect("globalThis.__trailblazeTools = {};")
    host.shutdown()
    // Second call must not throw even though the engine is already closed.
    val secondShutdown = runCatching { host.shutdown() }
    if (secondShutdown.isFailure) {
      fail("shutdown is not idempotent: second call threw ${secondShutdown.exceptionOrNull()}")
    }
  }
}
