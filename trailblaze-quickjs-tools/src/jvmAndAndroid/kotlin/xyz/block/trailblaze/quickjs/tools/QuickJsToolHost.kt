package xyz.block.trailblaze.quickjs.tools

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Evaluates a `@trailblaze/tools` JS bundle in a QuickJS engine and exposes the registered
 * tools through [listTools] / [callTool]. No MCP, no transport — direct same-process dispatch.
 * One host = one engine = one bundle; [shutdown] tears it down.
 *
 * Architectural rationale lives in the `2026-04-30-scripted-tools-not-mcp` devlog.
 */
class QuickJsToolHost internal constructor(
  /**
   * The QuickJS engine hosting this bundle. Owned by this host; closed in [shutdown].
   * Exposed `internal` so tests in this module can poke at the engine directly when an
   * assertion needs to read state outside the normal listTools/callTool surface.
   */
  internal val quickJs: QuickJs,
) {

  /**
   * Serializes every QuickJS evaluation through one mutex. QuickJS itself is single-threaded
   * (the underlying QuickJS-NG engine assumes a single owning thread), and [callTool] reads
   * back results through a shared global (`__trailblazeLastResult`). Without serialization,
   * two concurrent `callTool`s can race on either the engine or the result global — the
   * second call's writeback can clobber the first's result before the first reads it. The
   * mutex makes "evaluate then read" atomic with respect to other evaluations.
   *
   * `internal` so future helpers in this module can borrow the same lock for their own
   * QuickJS interactions; `private` would force them to use a parallel lock and reintroduce
   * the same race.
   */
  internal val evalMutex: Mutex = Mutex()

  /**
   * List the tools the bundle registered. Reads `globalThis.__trailblazeTools` and serializes
   * per-tool spec to a [RegisteredToolSpec]. Returns an empty list when the bundle didn't
   * register anything (legitimate — the bundle might be a no-op fixture).
   */
  suspend fun listTools(): List<RegisteredToolSpec> = evalMutex.withLock {
    val specsJson = quickJs.evaluate<String>(
      // Reads the registry, projects each entry to `{name, spec}`, JSON-stringifies. The
      // `|| {}` fallback means an empty registry returns `[]` rather than crashing on `Object.keys(undefined)`.
      """
      JSON.stringify(
        Object.entries(globalThis.__trailblazeTools || {}).map(([name, t]) => ({
          name,
          spec: t.spec || {}
        }))
      )
      """.trimIndent(),
      "list-tools.js",
      false,
    )
    val parsed = JSON.parseToJsonElement(specsJson) as kotlinx.serialization.json.JsonArray
    parsed.map { entry ->
      val obj = entry.jsonObject
      val name = (obj["name"] as kotlinx.serialization.json.JsonPrimitive).content
      val spec = obj["spec"]?.jsonObject ?: JsonObject(emptyMap())
      RegisteredToolSpec(name = name, spec = spec)
    }
  }

  /**
   * Invoke a registered tool. Returns the handler's resolved [TrailblazeToolResult] as a
   * [JsonObject]. Throws if no tool with [name] is registered.
   *
   * @param args arguments to pass as the handler's first parameter (the `args` object).
   * @param ctx per-invocation envelope passed as the handler's second parameter (the `ctx`).
   *   Pass `null` to send `undefined` to the handler — useful for ad-hoc invocations outside
   *   a session.
   */
  suspend fun callTool(name: String, args: JsonObject, ctx: JsonObject? = null): JsonObject =
    evalMutex.withLock {
      // Encode args/ctx as JS string literals + `JSON.parse(...)` inside the module rather than
      // splicing the raw JSON output into the generated source. JSON encodes the U+2028
      // (line separator) and U+2029 (paragraph separator) code points as themselves — valid
      // JSON, but in classic JS source they are line terminators that break a multi-line
      // string-spliced expression at parse time. Embedding via a JS string literal turns the
      // bytes into characters QuickJS reads through the string-literal grammar (which treats
      // them as ordinary characters), and `JSON.parse` reconstructs the object on the JS side.
      val argsLiteral = jsString(JSON.encodeToString(JsonObject.serializer(), args))
      val ctxLiteral = if (ctx != null) {
        // Same string-literal-then-parse trick. Wrapping in `JSON.parse(<literal>)` produces
        // an Object the handler sees as `ctx`.
        "JSON.parse(${jsString(JSON.encodeToString(JsonObject.serializer(), ctx))})"
      } else {
        "undefined"
      }
      // Module-mode evaluation supports top-level await in QuickJS-NG; script-mode does not, and
      // quickjs-kt's `evaluate<T>` doesn't unwrap returned Promises (returns the literal Promise
      // object stringified). The dispatch path therefore uses module mode + writes the result to a
      // global, then a separate script-mode read picks the result up. The [evalMutex] above
      // makes the "evaluate then read" pair atomic so concurrent callers can't clobber each
      // other's `__trailblazeLastResult`.
      // The `try { JSON.stringify } catch` on the JS side guarantees the string we read back
      // is always parseable JSON — handlers that return non-serializable values (functions,
      // BigInts, circular structures) get a structured `{ isError: true, ... }` envelope
      // instead of crashing the Kotlin parse step. Without this, `JSON.stringify` on a bad
      // value emits `undefined`, the read-back returns the literal string `"undefined"`, and
      // `JSON.parseToJsonElement` throws an opaque `JsonDecodingException` from a layer with
      // no author context.
      val dispatchExpr = """
        const tool = globalThis.__trailblazeTools && globalThis.__trailblazeTools[${jsString(name)}];
        if (!tool) throw new Error('Tool not registered: ' + ${jsString(name)});
        const __args = JSON.parse(${argsLiteral});
        const __ctx = ${ctxLiteral};
        const result = await tool.handler(__args, __ctx);
        try {
          globalThis.__trailblazeLastResult = JSON.stringify(result == null ? {} : result);
        } catch (e) {
          globalThis.__trailblazeLastResult = JSON.stringify({
            isError: true,
            content: [{ type: 'text', text: 'Tool ' + ${jsString(name)} + ' returned a non-JSON-serializable value: ' + (e && e.message || String(e)) }]
          });
        }
      """.trimIndent()
      quickJs.evaluate<Any?>(dispatchExpr, "call-tool-$name.js", true)
      val resultJson = quickJs.evaluate<String>(
        "globalThis.__trailblazeLastResult",
        "read-result.js",
        false,
      )
      JSON.parseToJsonElement(resultJson).jsonObject
    }

  /**
   * Free the QuickJS engine. Idempotent; safe to call multiple times. Runs the native free
   * under `Dispatchers.IO` because tearing down a bundle with lots of retained JS allocations
   * can block.
   */
  suspend fun shutdown() {
    withContext(Dispatchers.IO) {
      runCatching { quickJs.close() }
    }
  }

  companion object {

    /**
     * Stand up a fresh QuickJS engine, install the optional host binding, evaluate the bundle.
     * The bundle's top-level `trailblaze.tool(...)` calls populate `globalThis.__trailblazeTools`
     * during evaluation; once this returns, the host is ready for [listTools] / [callTool].
     *
     * @param bundleJs the bundled JS source. Must be a single self-contained string —
     *   typically the output of esbuild against an author's `tools.ts` with `@trailblaze/tools`
     *   aliased to the SDK shim. `import` / `export` survive only if the caller passes
     *   `asModule = true`; today this evaluates as a script, which works because the shim
     *   uses globals rather than ESM modules.
     * @param bundleFilename used in QuickJS evaluation errors. Carry a meaningful name
     *   (e.g. the original `tools.ts` path) so a runtime failure points at the author file.
     * @param hostBinding optional callback for `trailblaze.call(name, args)` from inside
     *   handler bodies. Pass `null` when the bundle isn't expected to compose; the SDK shim
     *   throws a clear error if the binding is missing and a handler tries to call out.
     */
    suspend fun connect(
      bundleJs: String,
      bundleFilename: String = "tools.bundle.js",
      hostBinding: HostBinding? = null,
      logSink: (String) -> Unit = DEFAULT_LOG_SINK,
    ): QuickJsToolHost {
      val quickJs = QuickJs.create(Dispatchers.Default)
      try {
        if (hostBinding != null) {
          // Async binding so JS can `await __trailblazeCall(name, argsJson)`. Returns the
          // result-JSON string the SDK shim will JSON.parse on the JS side.
          quickJs.asyncFunction(HOST_CALL_BINDING) { args ->
            val name = args.getOrNull(0) as? String
              ?: error("$HOST_CALL_BINDING called without a tool name string")
            val argsJson = args.getOrNull(1) as? String
              ?: error("$HOST_CALL_BINDING called without an argsJson string")
            hostBinding.callFromBundle(name, argsJson)
          }
        }
        // `console.log`/`error`/`warn`/`info` shim — author code from any Node-flavored
        // source expects `console` to exist. Bundles that hit `console.foo(...)` route
        // through the [logSink] parameter so callers control destination (logcat, daemon
        // log, test capture). Default sink uses `println` to stderr so a developer running
        // a host-embedded engine sees their own log lines without wiring anything; on-device
        // callers can pass a sink that forwards to logcat. Mirrors the field-style format
        // `BundleRuntimePrelude.CONSOLE_BINDING` uses in the legacy bundle module so the
        // two modules' logs grep the same.
        quickJs.function("__trailblazeLog") { args ->
          val level = (args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() } ?: "log"
          val message = args.getOrNull(1) as? String ?: ""
          val sanitized = message.replace("\n", "\\n").replace("\r", "\\r")
          logSink("[trailblaze-tools] level=$level msg=$sanitized")
          null
        }
        // Console shim: each method joins its variadic args to a single string so the
        // Kotlin-side binding receives (level, message) and not a fan-out of N positional
        // args. Joiner mirrors the convention used by the legacy bundle module's prelude.
        quickJs.evaluate<Any?>(
          """
          (function () {
            function fmt(args) { return Array.prototype.map.call(args, function (a) {
              return typeof a === 'string' ? a : (function () { try { return JSON.stringify(a); } catch (e) { return String(a); } })();
            }).join(' '); }
            globalThis.console = globalThis.console || {
              log:   function () { __trailblazeLog('log',   fmt(arguments)); },
              error: function () { __trailblazeLog('error', fmt(arguments)); },
              warn:  function () { __trailblazeLog('warn',  fmt(arguments)); },
              info:  function () { __trailblazeLog('info',  fmt(arguments)); },
            };
          })();
          """.trimIndent(),
          "console-shim.js",
          false,
        )
        // Evaluate the author bundle. Population of globalThis.__trailblazeTools happens
        // here as a side effect.
        quickJs.evaluate<Any?>(bundleJs, bundleFilename, false)
        return QuickJsToolHost(quickJs)
      } catch (t: Throwable) {
        runCatching { quickJs.close() }
        throw t
      }
    }

    /** Name of the host binding installed for `trailblaze.call(name, args)` round-trips. */
    const val HOST_CALL_BINDING: String = "__trailblazeCall"

    /**
     * Default destination for bundle-side `console.*` output: stderr via `System.err.println`.
     * Visible to a developer running a host-embedded engine without any extra wiring; on-device
     * callers will typically pass a sink that forwards to logcat.
     */
    val DEFAULT_LOG_SINK: (String) -> Unit = { line -> System.err.println(line) }
  }
}

/**
 * Spec of one registered tool — what the bundle declared via `trailblaze.tool(name, spec, handler)`.
 * The SDK's `TrailblazeToolSpec` shape is preserved as a generic [JsonObject] so the runtime
 * doesn't have to track every authoring-side optional field; consumers project out what
 * they care about (description, inputSchema, _meta, etc.).
 */
data class RegisteredToolSpec(
  val name: String,
  val spec: JsonObject,
)

/**
 * Host-side hook the bundle calls into via `trailblaze.call(name, args)`. One method, one
 * responsibility: dispatch a tool by name with JSON-encoded args, return JSON-encoded result.
 *
 * Implementations bridge to whatever Trailblaze runtime layer aggregates tools (the
 * `TrailblazeToolRepo` in production code, an in-memory map in tests).
 */
fun interface HostBinding {
  suspend fun callFromBundle(name: String, argsJson: String): String
}

/**
 * Module-level JSON instance. Configured for the runtime's serialization needs:
 * `ignoreUnknownKeys` because author specs can carry forward-compatible fields the runtime
 * doesn't know about; default classDiscriminator is fine since we serialize plain objects, not
 * sealed hierarchies.
 */
private val JSON: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/**
 * JSON.stringify equivalent for embedding a string literal safely into a JS expression. Wraps
 * in double quotes and escapes interior characters via kotlinx.serialization. Uses the JSON
 * instance's serializer because we want exact JS string semantics (Unicode escapes, control
 * chars, quote escaping) — manual `replace("\"", "\\\"")` would miss edge cases.
 *
 * **Additionally** rewrites U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) to their
 * `\uXXXX` escape forms. JSON encodes these code points as themselves (valid JSON), but they
 * are LineTerminator characters in the JavaScript source grammar — splicing the raw bytes
 * into a JS expression would break parsing on engines that haven't adopted ES2019's
 * "valid in string literals" relaxation. Defense in depth: even though QuickJS-NG handles
 * the relaxed grammar, escaping here removes any ambiguity for embedders that might switch
 * engines later.
 */
private fun jsString(s: String): String =
  JSON.encodeToString(String.serializer(), s)
    .replace("\u2028", "\\u2028")
    .replace("\u2029", "\\u2029")
