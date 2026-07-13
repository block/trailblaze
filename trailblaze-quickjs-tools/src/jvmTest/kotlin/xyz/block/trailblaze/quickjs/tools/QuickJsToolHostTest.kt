package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Round-trip tests for [QuickJsToolHost] using a hand-written JS bundle that simulates what
 * esbuild + the synthesized registration wrapper emit for an author's scripted-tool bundle.
 *
 * The bundle here inlines the shim's `globalThis.__trailblazeTools` registry directly — no
 * SDK npm package needed, no MCP. That matches the architecture of the new on-device runtime:
 * the author bundle is self-contained, registers handlers in a global, and the runtime
 * dispatches against that global.
 *
 * If these tests pass, the load-bearing claim is demonstrable: tools can be authored, bundled,
 * and dispatched in QuickJS without any MCP framing.
 */
class QuickJsToolHostTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  private suspend fun connect(
    bundleJs: String,
    hostBinding: HostBinding? = null,
    bundleFilename: String = "tools.bundle.js",
  ): QuickJsToolHost {
    val host = QuickJsToolHost.connect(bundleJs, bundleFilename = bundleFilename, hostBinding = hostBinding)
    hosts.add(host)
    return host
  }

  @Test
  fun `bundle registers a tool and listTools surfaces it with its spec`() = runBlocking {
    val host = connect(
      // Inlined shim + one tool. Reproduces the `globalThis.__trailblazeTools` registry shape a
      // scripted-tool bundle populates (the SDK-agnostic on-device dispatch contract).
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
  fun `JSON-Schema enum on an on-device tool param surfaces as validValues on the descriptor`() = runBlocking {
    // A `.ts` string-literal union (`"UP" | "DOWN" | …`) lowers to a JSON-Schema-shaped
    // inputSchema with a sibling `enum` array. The on-device descriptor builder must thread those
    // allowed values into the descriptor so the on-device agent's LLM sees the legal values instead
    // of a bare `type: string` — matching the host path and the fidelity a Kotlin enum param gets.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      const trailblaze = {
        tool(name, spec, handler) { tools[name] = { name, spec, handler }; },
      };
      trailblaze.tool(
        "directional_swipe",
        {
          description: "Swipe",
          inputSchema: {
            type: "object",
            properties: {
              direction: { type: "string", description: "which direction", enum: ["UP", "DOWN", "LEFT", "RIGHT"] },
              label: { type: "string" },
            },
            required: ["direction"],
          },
        },
        async () => ({ content: [] }),
      );
      """.trimIndent(),
    )

    val descriptor = host.listTools().single().toTrailblazeToolDescriptor()
    val direction = descriptor.requiredParameters.single { it.name == "direction" }
    assertEquals("string", direction.type, "JSON-Schema enum keeps its underlying `string` type")
    assertEquals(
      listOf("UP", "DOWN", "LEFT", "RIGHT"),
      direction.validValues,
      "the enum's allowed values must surface on the on-device descriptor",
    )
    // A plain string param must not gain validValues.
    assertNull(descriptor.optionalParameters.single { it.name == "label" }.validValues)
  }

  @Test
  fun `JSON-Schema enum surfaces as validValues in the flat-author inputSchema shape too`() = runBlocking {
    // The on-device descriptor builder has two parsing branches: the JSON-Schema shape
    // (`{type:object, properties, required}`, covered above) and the flat-author shape
    // (`{paramName: {type, ...}}`, where every key is a parameter and all are required). Both thread
    // `validValues`, so pin the enum on the flat shape as well — it's a distinct code path.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      const trailblaze = {
        tool(name, spec, handler) { tools[name] = { name, spec, handler }; },
      };
      trailblaze.tool(
        "flat_swipe",
        {
          description: "Swipe",
          inputSchema: {
            direction: { type: "string", description: "which direction", enum: ["UP", "DOWN", "LEFT", "RIGHT"] },
            label: { type: "string" },
          },
        },
        async () => ({ content: [] }),
      );
      """.trimIndent(),
    )

    val descriptor = host.listTools().single().toTrailblazeToolDescriptor()
    // Flat shape has no `required` list, so every parameter lands in requiredParameters.
    val direction = descriptor.requiredParameters.single { it.name == "direction" }
    assertEquals("string", direction.type)
    assertEquals(listOf("UP", "DOWN", "LEFT", "RIGHT"), direction.validValues)
    assertNull(descriptor.requiredParameters.single { it.name == "label" }.validValues)
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
    // Empty registry hint: the author's bundle registered nothing, so the dispatch failure
    // should call out the synthesized-wrapper / `name:` mismatch as the likely cause rather
    // than letting the author chase a typo in the lookup.
    assertTrue("expected empty-registry hint; got: ${err.message}") {
      err.message.orEmpty().contains("no tools are registered on this host")
    }
  }

  @Test
  fun `callTool error lists the registered tool names when the lookup misses but the registry is non-empty`() = runBlocking {
    // When the registry has tools but not the requested one, the author almost always has a
    // typo (mismatched `name:` field vs. bundle export). Listing what's there points the fix
    // at the right place.
    val host = connect(
      """
      globalThis.__trailblazeTools = {
        alpha: { handler: async () => ({ ok: true }) },
        beta:  { handler: async () => ({ ok: true }) },
      };
      """.trimIndent(),
    )
    val err = runCatching {
      host.callTool("aplha", JsonObject(emptyMap()))
    }.exceptionOrNull()
    assertNotNull(err, "expected callTool to throw")
    val msg = err.message.orEmpty()
    assertTrue("expected error to name the missing tool; got: $msg") {
      msg.contains("aplha")
    }
    assertTrue("expected error to enumerate registered tools; got: $msg") {
      msg.contains("alpha") && msg.contains("beta") && msg.contains("registered tools")
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
  fun `a hostBinding that re-enters the same host fails fast instead of deadlocking`() = runBlocking {
    // Before the reentrancy tripwire in QuickJsToolHost (hostCallThread/checkNotReentrant), this
    // scenario was explicitly left untested -- a real self-reentrant HostBinding would deadlock
    // the engine thread permanently (evalMutex never frees), which would hang shutdown() and the
    // whole test run right along with it. The tripwire converts that into a synchronous,
    // fail-fast error, which is what this test pins. Bounded with withTimeoutOrNull purely as a
    // belt-and-suspenders guard against a future regression reintroducing the hang -- if the
    // tripwire itself regresses, this fails loudly with a clear message instead of wedging CI.
    lateinit var host: QuickJsToolHost
    val binding = HostBinding { name, argsJson ->
      // Calls back into the SAME host that is currently dispatching this very callback.
      val args = Json.parseToJsonElement(argsJson).jsonObject
      Json.encodeToString(JsonObject.serializer(), host.callTool(name, args))
    }
    host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["selfCompose"] = {
        name: "selfCompose",
        spec: {},
        handler: async () => {
          // Realistic composing-tool shape: __trailblazeCall never throws for an ordinary
          // failure (see hostCallErrorEnvelopeJson) -- it returns a normal {isError, content}
          // envelope, same as every other tool-failure path, and it's on the calling JS to
          // check isError and propagate it, exactly like the SDK shim does in production.
          const innerResultJson = await globalThis.__trailblazeCall("selfCompose", "{}");
          const inner = JSON.parse(innerResultJson);
          if (inner.isError) {
            throw new Error("composed call failed: " + inner.content[0].text);
          }
          return { content: [{ type: "text", text: "should never get here" }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    val outcome = withTimeoutOrNull(10_000) {
      runCatching { host.callTool("selfCompose", JsonObject(emptyMap())) }
    } ?: fail(
      "self-reentrant composition hung instead of failing fast -- the reentrancy tripwire " +
        "(hostCallThread/checkNotReentrant in QuickJsToolHost) appears to have regressed",
    )
    // The tripwire's error returns as a clean {isError, content} envelope from
    // __trailblazeCall (see hostCallErrorEnvelopeJson) -- same as any other composed-call
    // failure -- which the JS handler above checks and re-throws, landing in callTool's own
    // isError envelope. What must NOT happen is the handler's unreachable success text coming
    // back, which would mean the tripwire never fired at all.
    val result = outcome.getOrThrow()
    assertTrue(
      result["isError"]?.let { it as? JsonPrimitive }?.boolean == true,
      "expected the self-reentrant call to surface as an isError envelope; got: $result",
    )
    assertTrue(
      textContent(result).contains("reentrant", ignoreCase = true),
      "expected the error message to explain the reentrancy failure; got: ${textContent(result)}",
    )
  }

  // ---- Regression coverage for the __trailblazeCall asyncFunction -> function + runBlocking
  // fix (block/trailblaze#194; see 2026-07-07-quickjs-async-host-binding-fix.md). The native JNI
  // crash itself needs real device I/O timing and full-daemon GC pressure to reproduce, so a
  // unit test can't trigger it directly -- these tests instead pin the composition scenarios the
  // fix must keep working: concurrent fan-out across hosts (the real production topology, since
  // each registered scripted tool gets its own engine), multi-level nesting, and stability under
  // repetition.

  @Test
  fun `composed tool calls stay isolated under concurrency across nested hosts`() = runBlocking {
    // Mirrors production topology: a composed call (`ctx.tools.*`) crosses into a DIFFERENT
    // QuickJsToolHost, not back into the calling host (one engine per registered scripted tool --
    // see the crash devlog's "29 live quickjs-tool-engine-N threads" observation). Fans many
    // concurrent outer calls into one shared inner host and asserts none of them cross-talk --
    // the same failure class `evalMutex serializes concurrent callTool invocations` guards
    // against for direct calls, now through the synchronous __trailblazeCall binding.
    val innerHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["inner"] = {
        name: "inner",
        spec: {},
        handler: async (args) => {
          await Promise.resolve();
          await Promise.resolve();
          return { content: [{ type: "text", text: "inner-" + args.marker }] };
        },
      };
      """.trimIndent(),
    )
    val binding = HostBinding { name, argsJson ->
      assertEquals("inner", name)
      val args = Json.parseToJsonElement(argsJson).jsonObject
      val result = innerHost.callTool(name, args)
      Json.encodeToString(JsonObject.serializer(), result)
    }
    val outerHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async (args) => {
          const innerResultJson = await globalThis.__trailblazeCall("inner", JSON.stringify({ marker: args.marker }));
          const inner = JSON.parse(innerResultJson);
          return { content: [{ type: "text", text: "outer wraps " + inner.content[0].text }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    val markers = (1..20).map { "m$it" }
    val results = coroutineScope {
      markers.map { m ->
        async { outerHost.callTool("outer", buildJsonObject { put("marker", m) }) }
      }.awaitAll()
    }
    markers.zip(results).forEach { (marker, result) ->
      assertEquals(
        "outer wraps inner-$marker",
        textContent(result),
        "expected composed result for $marker to not cross-talk with another concurrent call",
      )
    }
  }

  @Test
  fun `composition survives three levels of nested hosts`() = runBlocking {
    // Each level bridges through its own synchronous __trailblazeCall binding + runBlocking, on
    // its own dedicated engine thread. Pins that chaining the fix three deep (runBlocking inside
    // runBlocking inside runBlocking, each on a different thread) still resolves correctly.
    val leafHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["leaf"] = {
        name: "leaf",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: "leaf" }] }),
      };
      """.trimIndent(),
    )
    val leafBinding = HostBinding { name, argsJson ->
      Json.encodeToString(JsonObject.serializer(), leafHost.callTool(name, Json.parseToJsonElement(argsJson).jsonObject))
    }
    val midHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["mid"] = {
        name: "mid",
        spec: {},
        handler: async () => {
          const leafResultJson = await globalThis.__trailblazeCall("leaf", "{}");
          const leaf = JSON.parse(leafResultJson);
          return { content: [{ type: "text", text: "mid wraps " + leaf.content[0].text }] };
        },
      };
      """.trimIndent(),
      hostBinding = leafBinding,
    )
    val midBinding = HostBinding { name, argsJson ->
      Json.encodeToString(JsonObject.serializer(), midHost.callTool(name, Json.parseToJsonElement(argsJson).jsonObject))
    }
    val outerHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async () => {
          const midResultJson = await globalThis.__trailblazeCall("mid", "{}");
          const mid = JSON.parse(midResultJson);
          return { content: [{ type: "text", text: "outer wraps " + mid.content[0].text }] };
        },
      };
      """.trimIndent(),
      hostBinding = midBinding,
    )
    val result = outerHost.callTool("outer", JsonObject(emptyMap()))
    assertEquals("outer wraps mid wraps leaf", textContent(result))
  }

  @Test
  fun `composed calls remain correct across many sequential iterations (soak)`() = runBlocking {
    // Repeats the two-host composition many times over the SAME hosts/engines to build
    // confidence against subtle state leakage between calls (e.g. a stale __trailblazeLastResult
    // read, or the engine thread wedging after N dispatches) that a single call wouldn't surface.
    val innerHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["inner"] = {
        name: "inner",
        spec: {},
        handler: async (args) => ({ content: [{ type: "text", text: "inner-" + args.i }] }),
      };
      """.trimIndent(),
    )
    val binding = HostBinding { name, argsJson ->
      Json.encodeToString(
        JsonObject.serializer(),
        innerHost.callTool(name, Json.parseToJsonElement(argsJson).jsonObject),
      )
    }
    val outerHost = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async (args) => {
          const innerResultJson = await globalThis.__trailblazeCall("inner", JSON.stringify({ i: args.i }));
          const inner = JSON.parse(innerResultJson);
          return { content: [{ type: "text", text: inner.content[0].text }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    repeat(100) { i ->
      val result = outerHost.callTool("outer", buildJsonObject { put("i", i) })
      assertEquals("inner-$i", textContent(result), "iteration $i unexpectedly diverged")
    }
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

  @Test
  fun `a hostBinding that throws surfaces as a clean isError envelope, not a corrupted native exception`() = runBlocking {
    // Regression test for a bug this PR's review uncovered while writing test coverage for the
    // asyncFunction -> function + runBlocking swap: letting an arbitrary exception cross the
    // HOST_CALL_BINDING native-binding boundary uncaught does NOT surface as a normal JS-level
    // throw the composing tool's `await` can catch. Empirically (before hostCallErrorEnvelopeJson
    // existed) it aborted quickJs.evaluate() outright and corrupted the dispatch script's own
    // error-tracking state: `QuickJsException: ReferenceError: __dispatchError is not
    // initialized`, losing the real failure message ("boom-from-host-binding") entirely. This
    // pins the fix: the exception is now converted to the same {isError, content} envelope shape
    // every other tool-failure path already uses, so the composing tool's own isError check (the
    // realistic way an SDK shim would handle it) sees the real message.
    val binding = HostBinding { _, _ -> throw RuntimeException("boom-from-host-binding") }
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async () => {
          const innerResultJson = await globalThis.__trailblazeCall("inner", "{}");
          const inner = JSON.parse(innerResultJson);
          if (inner.isError) {
            throw new Error("composed call failed: " + inner.content[0].text);
          }
          return { content: [{ type: "text", text: "should never get here" }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    val result = withTimeoutOrNull(10_000) { host.callTool("outer", JsonObject(emptyMap())) }
      ?: fail("host binding throw hung instead of surfacing promptly")
    assertTrue(
      result["isError"]?.let { it as? JsonPrimitive }?.boolean == true,
      "expected a clean isError envelope; got: $result",
    )
    assertTrue(
      textContent(result).contains("boom-from-host-binding"),
      "expected the real exception message to survive, not a corrupted native-boundary " +
        "error; got: ${textContent(result)}",
    )
  }

  @Test
  fun `a hostBinding throwing CancellationException still surfaces promptly (known lossy path)`() = runBlocking {
    // CancellationException is deliberately NOT converted to an isError envelope (see the
    // catch block at the HOST_CALL_BINDING install site) -- swallowing it would suppress real
    // coroutine cancellation semantics for the outer call. But rethrowing it raw still crosses
    // the same native-binding boundary the test above documents as lossy: this pins the CURRENT,
    // known-imperfect outcome (a thrown QuickJsException, not a clean CancellationException or
    // isError envelope) so a future change to this boundary's behavior is a deliberate decision,
    // not a silent regression. Improving this is out of scope for this fix -- see the timeout
    // follow-up already tracked in the devlog.
    val binding = HostBinding { _, _ -> throw CancellationException("cancelled-from-host-binding") }
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["outer"] = {
        name: "outer",
        spec: {},
        handler: async () => {
          await globalThis.__trailblazeCall("inner", "{}");
          return { content: [{ type: "text", text: "should never get here" }] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )
    val outcome = withTimeoutOrNull(10_000) {
      runCatching { host.callTool("outer", JsonObject(emptyMap())) }
    } ?: fail("host binding cancellation hung instead of surfacing promptly")
    assertTrue(
      outcome.isFailure,
      "expected the composed cancellation to surface as a thrown exception (current known-" +
        "lossy behavior), not silently succeed; got: $outcome",
    )
  }

  // ---- ctx.target.resolveAppId / resolveBaseUrl method injection ----
  //
  // Methods can't survive JSON round-trips (host → QuickJS engine), so the
  // dispatch script in QuickJsToolHost attaches them to ctx.target after
  // deserialization. These tests pin every branch of the resolution priority
  // (appId → appIds[0] → defaultAppId → undefined) for both methods,
  // plus the no-target case.

  @Test
  fun `ctx target resolveAppId returns appId when framework resolved one`() = runBlocking {
    val host = connectGetAppIdHost("appId")
    val ctx = buildJsonObject {
      put("target", buildJsonObject {
        put("appId", "com.framework.resolved")
        put("appIds", buildJsonArray { add("com.first.declared"); add("com.second.declared") })
      })
    }
    val result = host.callTool("getAppId", buildJsonObject { put("defaultAppId", "com.caller.default") }, ctx = ctx)
    assertEquals("com.framework.resolved", textContent(result))
  }

  @Test
  fun `ctx target resolveAppId falls back to appIds zero when framework didn't resolve`() = runBlocking {
    val host = connectGetAppIdHost("appId")
    val ctx = buildJsonObject {
      put("target", buildJsonObject {
        put("appIds", buildJsonArray { add("com.first.declared"); add("com.second.declared") })
      })
    }
    val result = host.callTool("getAppId", buildJsonObject { put("defaultAppId", "com.caller.default") }, ctx = ctx)
    assertEquals("com.first.declared", textContent(result))
  }

  @Test
  fun `ctx target resolveAppId falls back to caller default when target has no candidates`() = runBlocking {
    val host = connectGetAppIdHost("appId")
    val ctx = buildJsonObject { put("target", buildJsonObject { put("appIds", buildJsonArray { }) }) }
    val result = host.callTool("getAppId", buildJsonObject { put("defaultAppId", "com.caller.default") }, ctx = ctx)
    assertEquals("com.caller.default", textContent(result))
  }

  @Test
  fun `ctx target resolveAppId returns undefined when nothing is reachable and no default given`() = runBlocking {
    // Author calls `ctx.target.resolveAppId()` with no options. Target has empty appIds
    // and no appId. Method must return undefined (not throw) — author handles
    // the missing case in their tool body.
    val host = connectGetAppIdHost("missing")
    val ctx = buildJsonObject { put("target", buildJsonObject { put("appIds", buildJsonArray { }) }) }
    val result = host.callTool("getAppId", JsonObject(emptyMap()), ctx = ctx)
    assertEquals("missing", textContent(result))
  }

  @Test
  fun `ctx target resolveBaseUrl returns caller default today (forward-compat for future base_urls field)`() = runBlocking {
    // Until target.platforms.web.base_urls: lands in the manifest, the ctx.target
    // doesn't carry resolvedBaseUrl or baseUrls fields. The method should still work
    // and fall through to the caller default — that's the forward-compat contract:
    // when the framework starts emitting baseUrls data, this method automatically
    // picks it up without any author change.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["getBaseUrl"] = {
        name: "getBaseUrl",
        spec: {},
        handler: async (args, ctx) => {
          const url = ctx?.target?.resolveBaseUrl({ defaultBaseUrl: args.defaultBaseUrl });
          return { content: [{ type: "text", text: url || "missing" }] };
        },
      };
      """.trimIndent(),
    )
    val ctx = buildJsonObject { put("target", buildJsonObject { }) }
    val result = host.callTool(
      "getBaseUrl",
      buildJsonObject { put("defaultBaseUrl", "https://en.wikipedia.org") },
      ctx = ctx,
    )
    assertEquals("https://en.wikipedia.org", textContent(result))
  }

  @Test
  fun `ctx target resolveBaseUrl picks up baseUrls when framework starts emitting them`() = runBlocking {
    // Future-proof: when the framework adds resolvedBaseUrl + baseUrls to ctx.target,
    // resolveBaseUrl picks them up via the same priority order as resolveAppId.
    // This test simulates that future state by setting the fields manually.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["getBaseUrl"] = {
        name: "getBaseUrl",
        spec: {},
        handler: async (args, ctx) => {
          const url = ctx?.target?.resolveBaseUrl();
          return { content: [{ type: "text", text: url || "missing" }] };
        },
      };
      """.trimIndent(),
    )
    val ctx = buildJsonObject {
      put("target", buildJsonObject {
        put("resolvedBaseUrl", "https://framework.resolved/")
        put("baseUrls", buildJsonArray { add("https://first.declared/") })
      })
    }
    val result = host.callTool("getBaseUrl", JsonObject(emptyMap()), ctx = ctx)
    assertEquals("https://framework.resolved/", textContent(result))
  }

  @Test
  fun `methods are not injected when ctx target is absent`() = runBlocking {
    // Belt-and-suspenders: if a tool gets a ctx without a target field (web-only
    // sessions or fixtures), accessing ctx.target.resolveAppId would throw a
    // TypeError. Authors are expected to optional-chain (`ctx.target?.resolveAppId`)
    // — confirm the host doesn't accidentally synthesize a `target` object.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["probeTarget"] = {
        name: "probeTarget",
        spec: {},
        handler: async (args, ctx) => ({
          content: [{ type: "text", text: ctx?.target === undefined ? "absent" : "present" }],
        }),
      };
      """.trimIndent(),
    )
    val ctx = buildJsonObject { put("sessionId", "no-target-session") }  // no target field
    val result = host.callTool("probeTarget", JsonObject(emptyMap()), ctx = ctx)
    assertEquals("absent", textContent(result))
  }

  /** Helper: build a host with a tool that returns whatever `resolveAppId` produces. */
  private suspend fun connectGetAppIdHost(missingValue: String) = connect(
    """
    const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
    tools["getAppId"] = {
      name: "getAppId",
      spec: {},
      handler: async (args, ctx) => {
        const appId = ctx?.target?.resolveAppId({ defaultAppId: args.defaultAppId });
        return { content: [{ type: "text", text: appId || ${jsLiteral(missingValue)} }] };
      },
    };
    """.trimIndent(),
  )

  private fun jsLiteral(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private fun textContent(result: JsonObject): String =
    ((result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content

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
  fun `connect closes its dedicated engine thread when it fails, not just the engine`() = runBlocking {
    // `connect()` mints a dedicated single-thread `engineDispatcher` before the engine even
    // exists, so closing `quickJs` alone on failure isn't enough — regression coverage for a
    // bug where `QuickJs.create` (and, by extension, the whole per-engine setup) ran outside
    // the try/catch that closes `engineDispatcher`, leaking the dedicated daemon thread on any
    // connect() failure (a real risk for a long-running daemon that retries failed connects).
    val before = Thread.getAllStackTraces().keys.count { it.name.startsWith("quickjs-tool-engine-") }
    runCatching {
      QuickJsToolHost.connect("this is not valid JavaScript ((", bundleFilename = "broken.js")
    }
    // Executor shutdown terminates its worker thread asynchronously; poll briefly rather than
    // asserting immediately, which would flake on a slow CI runner.
    val settled = withTimeoutOrNull(2_000) {
      while (Thread.getAllStackTraces().keys.count { it.name.startsWith("quickjs-tool-engine-") } > before) {
        delay(20)
      }
      true
    }
    assertTrue(settled == true, "connect() must not leak its dedicated engine thread when it fails")
  }

  @Test
  fun `callTool surfaces handler throws as isError envelope with name message and JS stack`() = runBlocking {
    // Pin the JS stack-preservation contract. A handler throw is caught on the JS side and
    // surfaced through the same `isError: true` envelope as a handler-returned error, but
    // now carries the JS-side stack so downstream `TrailblazeToolLog.exceptionMessage`
    // includes the bundle filename + line/col. Pre-fix, the throw escaped QuickJS as a
    // Kotlin throwable whose `.message` was just "boom from handler" with no source
    // breadcrumb — the bug this test pins is the loss of `Error.stack`.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["boom"] = {
        name: "boom",
        spec: {},
        handler: async () => { throw new Error("boom from handler"); },
      };
      """.trimIndent(),
      bundleFilename = "throwing-bundle.js",
    )
    val result = host.callTool("boom", JsonObject(emptyMap()))
    // Envelope shape: `{ isError: true, content: [{ type: "text", text: "..." }] }`.
    assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
    val text = (result["content"] as kotlinx.serialization.json.JsonArray)
      .first().jsonObject["text"]!!.jsonPrimitive.content
    // Error name prefix, message, and JS stack frame all present.
    assertTrue("expected 'Error: ' prefix in: $text") { text.startsWith("Error: ") }
    assertTrue("expected handler message in: $text") { text.contains("boom from handler") }
    // QuickJS-NG fills in a stack frame referencing the bundle filename — the whole point
    // of this change. Without the JS-side catch, this assertion would fail because the
    // Kotlin throwable's message doesn't include the JS stack.
    assertTrue("expected bundle filename in JS stack in: $text") { text.contains("throwing-bundle.js") }
  }

  @Test
  fun `callTool surfaces non-Error throws via String fallback`() = runBlocking {
    // `throw "literal"` and `throw 42` don't carry `.message` or `.stack`. The catch
    // recipient must still produce a structured `isError` envelope rather than crashing
    // the Kotlin parse step.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["primitive_throw"] = {
        name: "primitive_throw",
        spec: {},
        handler: async () => { throw "raw string"; },
      };
      """.trimIndent(),
    )
    val result = host.callTool("primitive_throw", JsonObject(emptyMap()))
    assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
    val text = (result["content"] as kotlinx.serialization.json.JsonArray)
      .first().jsonObject["text"]!!.jsonPrimitive.content
    assertTrue("expected stringified primitive in: $text") { text.contains("raw string") }
  }

  @Test
  fun `callTool surfaces FALSY throws as isError envelope - not silent success`() = runBlocking {
    // Regression pin from automated review feedback: a truthiness check
    // `if (__dispatchError)` misclassifies `throw 0` / `throw false` / `throw ''` /
    // `throw null` / `throw undefined` as success because the captured value is falsy. The
    // dispatch falls through, returns `{}`, and silently swallows a real failure. This test
    // is the regression net for that — the dispatcher MUST surface every caught throw as an
    // `isError: true` envelope regardless of the thrown value's truthiness.
    //
    // Loops the well-known falsy values rather than parameterizing — bun:test-style table
    // tests aren't worth pulling in for five cases, and the inline list makes the
    // explicit-truthiness pin obvious to a reviewer.
    val falsyThrows = listOf(
      """throw 0;""" to "0",
      """throw false;""" to "false",
      """throw "";""" to "",
      """throw null;""" to "null",
      """throw undefined;""" to "undefined",
    )
    falsyThrows.forEach { (throwStmt, expectedSubstring) ->
      val host = connect(
        """
        const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
        tools["falsyThrow"] = {
          name: "falsyThrow",
          spec: {},
          handler: async () => { $throwStmt },
        };
        """.trimIndent(),
      )
      val result = host.callTool("falsyThrow", JsonObject(emptyMap()))
      val isError = result["isError"]?.jsonPrimitive?.boolean
      assertEquals(true, isError, "falsy throw `$throwStmt` must surface as isError=true, got: $result")
      val text = (result["content"] as? kotlinx.serialization.json.JsonArray)
        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
        ?: fail("expected content[0].text for falsy throw `$throwStmt`, got: $result")
      // Empty-string throw stringifies to "", so the substring check is vacuous — but the
      // `isError=true` assertion above is the load-bearing one for that case. For everything
      // else, the stringified thrown value should appear in the message.
      if (expectedSubstring.isNotEmpty()) {
        assertTrue("expected '$expectedSubstring' in falsy-throw envelope text: $text") {
          text.contains(expectedSubstring)
        }
      }
    }
  }

  @Test
  fun `callTool preserves Error subtype names like TypeError and RangeError`() = runBlocking {
    // The dispatcher reads `__e.name` and falls back to literal `'Error'`. The existing
    // `Error` test passes either way (the fallback also produces `'Error: '`), so this
    // test pins that the subtype name actually flows through — guards against a regression
    // that accidentally hard-codes the prefix.
    val subtypes = listOf("TypeError", "RangeError", "SyntaxError")
    subtypes.forEach { subtypeName ->
      val host = connect(
        """
        const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
        tools["subtypeThrow"] = {
          name: "subtypeThrow",
          spec: {},
          handler: async () => { throw new $subtypeName("subtype failure"); },
        };
        """.trimIndent(),
      )
      val result = host.callTool("subtypeThrow", JsonObject(emptyMap()))
      assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
      val text = (result["content"] as kotlinx.serialization.json.JsonArray)
        .first().jsonObject["text"]!!.jsonPrimitive.content
      assertTrue("expected '$subtypeName: ' prefix in: $text") {
        text.startsWith("$subtypeName: ")
      }
      assertTrue("expected message in: $text") { text.contains("subtype failure") }
    }
  }

  @Test
  fun `callTool surfaces async-rejected promises as isError envelopes`() = runBlocking {
    // All other throw tests use synchronous `throw` syntax. The dispatcher `await`s the
    // handler, so async rejections should ride the same path — but pin that explicitly,
    // since async-reject is the most common real-world failure mode (a `fetch` that fails,
    // a `JSON.parse` on bad input, an awaited inner `callTool`).
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["asyncRejecter"] = {
        name: "asyncRejecter",
        spec: {},
        handler: async () => {
          await Promise.resolve(); // suspend so the rejection lands in the next microtask
          throw new Error("async boom");
        },
      };
      tools["explicitReject"] = {
        name: "explicitReject",
        spec: {},
        // Returning a rejected promise directly (non-async function) — different shape from
        // the `async () => { throw }` path. Both must produce the same envelope.
        handler: () => Promise.reject(new Error("explicit boom")),
      };
      """.trimIndent(),
    )
    listOf("asyncRejecter" to "async boom", "explicitReject" to "explicit boom").forEach { (name, expectedMsg) ->
      val result = host.callTool(name, JsonObject(emptyMap()))
      assertEquals(true, result["isError"]?.jsonPrimitive?.boolean, "expected isError for $name")
      val text = (result["content"] as kotlinx.serialization.json.JsonArray)
        .first().jsonObject["text"]!!.jsonPrimitive.content
      assertTrue("expected '$expectedMsg' in async-reject envelope text for $name: $text") {
        text.contains(expectedMsg)
      }
    }
  }

  @Test
  fun `callTool with a handler returning undefined produces empty success not an error envelope`() = runBlocking {
    // Void-shaped tools — handlers that don't return a value — should produce a success
    // result, not an error. `__dispatchResult == null ? {} : __dispatchResult` is the path
    // that handles this; pin it so a future refactor that removes the null-coalesce can't
    // silently turn no-op tools into Error.ExceptionThrown downstream.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["voidTool"] = {
        name: "voidTool",
        spec: {},
        handler: async () => { /* no return */ },
      };
      """.trimIndent(),
    )
    val result = host.callTool("voidTool", JsonObject(emptyMap()))
    assertEquals(null, result["isError"], "void handler must NOT produce isError envelope: $result")
    // Empty `{}` is the spec — no `content` key means downstream `toTrailblazeToolResult`
    // surfaces it as a structural error (covered separately in QuickJsTrailblazeToolTest);
    // here we just pin the host's behavior of returning `{}`.
    assertEquals(0, result.size, "expected empty object, got: $result")
  }

  @Test
  fun `callTool handles thrown objects whose String() itself throws`() = runBlocking {
    // Regression pin from automated review feedback: `String(__e)` inside the catch block can itself throw for
    // objects with a throwing `toString` / `valueOf` or a null prototype lacking both.
    // If the catch handler's defensive-stringify itself throws, we lose the envelope and
    // re-introduce the original "Kotlin throwable, no JS stack" failure mode the JS-side
    // catch exists to prevent. The dispatcher wraps the stringify in its own try/catch and
    // falls back to a static placeholder; this test pins that.
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["hostile_throw"] = {
        name: "hostile_throw",
        spec: {},
        handler: async () => {
          // Object whose toString throws — String() on this would propagate the inner throw.
          // No .message or .stack either, so the `__isErrorObj` branch is skipped and the
          // fallback `String(__e)` path is what we exercise.
          throw { toString() { throw new Error("toString sabotage"); } };
        },
      };
      """.trimIndent(),
    )
    val result = host.callTool("hostile_throw", JsonObject(emptyMap()))
    assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
    val text = (result["content"] as kotlinx.serialization.json.JsonArray)
      .first().jsonObject["text"]!!.jsonPrimitive.content
    // The defensive-stringify fallback uses a static placeholder so the envelope still ships
    // even when no string representation is available.
    assertTrue("expected static fallback in envelope text: $text") {
      text.contains("unstringifiable thrown value")
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

  @Test
  fun `shutdown waits for an in-flight callTool instead of racing it`() = runBlocking {
    // Regression test for the cross-thread hazard `engineDispatcher` confinement exists to
    // prevent, reintroduced via a different door: `shutdown()` used to close `quickJs` and
    // `engineDispatcher` without taking `evalMutex`, so it could run concurrently with a
    // `callTool` that was suspended mid-dispatch (e.g. inside an async host callback, exactly
    // the `await ctx.tools.*` shape from the original bug report). kotlinx.coroutines'
    // `ExecutorCoroutineDispatcher` falls back to `Dispatchers.IO` when a task is submitted to
    // an already-`close()`d executor, so the stranded `callTool` would resume on an arbitrary
    // IO thread against an engine `shutdown()` just freed. `shutdown()` now takes `evalMutex`
    // for its whole close, so it must block until the in-flight call finishes.
    val handlerStarted = CompletableDeferred<Unit>()
    val releaseHandler = CompletableDeferred<Unit>()
    val binding = HostBinding { _, _ ->
      handlerStarted.complete(Unit)
      releaseHandler.await()
      """{"content":[]}"""
    }
    val host = connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["slow"] = {
        name: "slow",
        spec: {},
        handler: async () => {
          await globalThis.__trailblazeCall("inner", "{}");
          return { content: [] };
        },
      };
      """.trimIndent(),
      hostBinding = binding,
    )

    val callToolResult = async { host.callTool("slow", JsonObject(emptyMap())) }
    handlerStarted.await()

    // Issued while `callTool` is suspended inside the async host callback, still holding
    // `evalMutex`. It must block rather than closing the engine out from under that call.
    val shutdownJob = async { host.shutdown() }
    val shutdownFinishedEarly = withTimeoutOrNull(200) { shutdownJob.await() } != null
    assertTrue(!shutdownFinishedEarly, "shutdown() must not complete while a callTool is still in flight")

    releaseHandler.complete(Unit)
    val result = withTimeoutOrNull(5_000) { callToolResult.await() }
    assertNotNull(result, "callTool should complete normally once its handler is released")
    assertEquals(0, (result["content"] as JsonArray).size)

    // Now that the in-flight call is done, shutdown proceeds and completes cleanly.
    withTimeoutOrNull(5_000) { shutdownJob.await() }
      ?: fail("shutdown() should have completed once the in-flight callTool released evalMutex")
  }

  @Test
  fun `engineExtension install runs before the bundle so a handler can use what it binds`() = runBlocking {
    // Pins the hook's contract: an engine extension installs globals/bindings BEFORE the author
    // bundle evaluates, so a tool handler can reference whatever it binds (the property the
    // OkHttp `fetch` extension in :trailblaze-scripting-fetch relies on). Uses a real lambda
    // extension that defines a global the bundle's tool reads back — not a fake collaborator.
    val extension = QuickJsEngineExtension { quickJs ->
      quickJs.evaluate<Any?>("globalThis.__injectedByExtension = 'installed';", "test-extension.js", false)
    }
    val host = QuickJsToolHost.connect(
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["readInjected"] = {
        name: "readInjected",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: String(globalThis.__injectedByExtension) }] }),
      };
      """.trimIndent(),
      engineExtension = extension,
    )
    hosts.add(host)
    val result = host.callTool("readInjected", JsonObject(emptyMap()))
    assertEquals("installed", textContent(result))
  }
}
