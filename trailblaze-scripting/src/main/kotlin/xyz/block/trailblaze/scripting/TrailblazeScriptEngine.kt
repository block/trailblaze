package xyz.block.trailblaze.scripting

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Host for evaluating JavaScript source strings inside QuickJS. Exposes an `input` global
 * (memory + params) and, when a [ToolDispatcher] is supplied, a synchronous
 * `trailblaze.execute(toolName, params)` callback that the script can use to invoke
 * Trailblaze tools mid-evaluation and branch on the result.
 *
 * The script's return value (if any) is surfaced as the engine's string result — the
 * caller decides what to do with it (e.g. [ScriptTrailblazeTool] decodes it as a YAML
 * block of additional tools to execute afterward).
 *
 * Threading: QuickJS evaluation is a suspending API. This wrapper is blocking because
 * `DelegatingTrailblazeTool.toExecutableTrailblazeTools` is not suspending.
 */
object TrailblazeScriptEngine {

  /**
   * Synchronous bridge that resolves a tool name + JSON params into an executed
   * `TrailblazeToolResult`, serialized as a JSON string. Passed down through the engine
   * so `trailblaze.execute()` from JS can dispatch real Trailblaze tools and branch on
   * the result.
   *
   * The [ScriptTrailblazeTool] owns the concrete implementation — the engine only knows
   * how to wire the bridge into JS and marshal strings across.
   */
  fun interface ToolDispatcher {
    /**
     * Dispatches [toolName] with [paramsJson] (JSON-stringified object). Must return
     * a JSON string matching `{ "isError": boolean, "message": string? }` per the
     * MVP shape documented in docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md.
     */
    fun dispatch(toolName: String, paramsJson: String): String
  }

  /**
   * Evaluates [source] with [input] exposed as a global `input` variable and, if
   * [dispatcher] is non-null, a global `trailblaze.execute(name, params)` function.
   * Returns whatever string the script produced as its final expression.
   *
   * Throws [ScriptEvaluationException] on any QuickJS-level failure, with the original
   * exception chained.
   */
  fun evaluate(
    source: String,
    input: JsonObject = JsonObject(emptyMap()),
    dispatcher: ToolDispatcher? = null,
  ): String = runBlocking {
    val inputJson = Json.encodeToString(JsonObject.serializer(), input)
    val wrapped = buildString {
      appendLine("const input = $inputJson;")
      if (dispatcher != null) {
        // Wrap the raw string-in/string-out binding so user code can pass/receive
        // JS objects naturally. JSON is valid YAML flow syntax, so we hand the
        // stringified params straight into the Kotlin decoder.
        appendLine(
          """
          const trailblaze = {
            execute: function(name, params) {
              const paramsJson = JSON.stringify(params == null ? {} : params);
              const resultJson = __trailblazeExecuteRaw(name, paramsJson);
              return JSON.parse(resultJson);
            }
          };
          """.trimIndent(),
        )
      }
      appendLine("(function() {")
      appendLine(source)
      appendLine("})()")
    }
    val quickJs = QuickJs.create(Dispatchers.Default)
    try {
      if (dispatcher != null) {
        quickJs.function("__trailblazeExecuteRaw") { args ->
          val toolName = args[0] as String
          val paramsJson = args[1] as String
          dispatcher.dispatch(toolName, paramsJson)
        }
      }
      // No wall-clock timeout: quickjs-kt 1.0.5 exposes no interrupt handler, so a
      // `while(true){}` in JS never yields to the coroutine and withTimeout can't fire.
      // Real timeout discipline lands in a follow-up (interrupt handler + budget).
      quickJs.evaluate<String>(wrapped)
    } catch (e: QuickJsException) {
      // quickjs-kt raises QuickJsException for every engine-side failure — syntax errors,
      // runtime throws, and type-mismatches (including "script did not return a String").
      throw ScriptEvaluationException(
        "JavaScript evaluation failed: ${e.message}",
        cause = e,
        source = source,
      )
    } finally {
      quickJs.close()
    }
  }
}

class ScriptEvaluationException(
  message: String,
  cause: Throwable? = null,
  val source: String,
) : RuntimeException(message, cause)
