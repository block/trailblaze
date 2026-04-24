package xyz.block.trailblaze.scripting

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Runs a JavaScript source string and expands its returned YAML block into a list of
 * executable Trailblaze tools.
 *
 * The script can read `input.memory` and `input.params`, and can call
 * `trailblaze.execute(toolName, params)` mid-evaluation to invoke a Trailblaze tool and
 * branch on the returned `{ isError, message }` result. Any YAML string the script
 * returns expands to additional tools that run after the script finishes.
 *
 * Marked `isRecordable = false` so the recording captures the expanded primitive tool
 * calls, not this wrapper. That means Android on-device replay never needs to run JS.
 *
 * Example — mixed imperative + return-YAML:
 * ```yaml
 * - tools:
 *     - script:
 *         source: |
 *           const r = trailblaze.execute("assertVisibleWithText", { text: "Login" });
 *           if (r.isError) {
 *             return `
 *             - launchApp:
 *                 appId: com.example
 *             `;
 *           }
 *           return "";
 * ```
 */
@Serializable
@TrailblazeToolClass(name = "script", isForLlm = false, isRecordable = false)
@LLMDescription("Delegates to a JavaScript source string that returns a YAML block of tool calls.")
data class ScriptTrailblazeTool(
  val source: String,
  val params: Map<String, String> = emptyMap(),
) : DelegatingTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val input = buildInput(executionContext)
    val dispatcher = ScriptToolDispatcher(executionContext)
    val yaml = TrailblazeScriptEngine.evaluate(source = source, input = input, dispatcher = dispatcher)
    if (yaml.isBlank()) return emptyList()
    val decoded = try {
      TrailblazeYaml.Default.decodeTools(yaml)
    } catch (e: Exception) {
      val preview = if (yaml.length > YAML_ERROR_PREVIEW_CHARS) {
        yaml.take(YAML_ERROR_PREVIEW_CHARS) + "…(truncated, ${yaml.length} chars total)"
      } else {
        yaml
      }
      throw ScriptEvaluationException(
        message = "Script returned YAML that could not be decoded into Trailblaze tools: " +
          "${e.message}\n--- returned YAML ---\n$preview",
        cause = e,
        source = source,
      )
    }
    return decoded.flatMapIndexed { index, wrapper ->
      when (val tool = wrapper.trailblazeTool) {
        // Direct primitive: pass through unchanged.
        is ExecutableTrailblazeTool -> listOf(tool)
        // Delegating tool (e.g. a YAML-defined tool like `pressBack` built via `tools:`
        // composition): recursively expand into the primitives it composes so the script
        // can return them by name without the caller having to inline the composition.
        //
        // Expansion can throw (missing required param, malformed interpolation). Wrap in
        // ScriptEvaluationException so the downstream error carries script-source context
        // just like the decode-failure path above — otherwise a YAML-tool misuse surfaces
        // as a bare RuntimeException with no "which script returned this?" framing.
        is DelegatingTrailblazeTool -> try {
          tool.toExecutableTrailblazeTools(executionContext)
        } catch (e: Exception) {
          throw ScriptEvaluationException(
            message = "Script returned YAML whose delegating tool '${wrapper.name}' failed to " +
              "expand: ${e.message}",
            cause = e,
            source = source,
          )
        }
        else -> throw ScriptEvaluationException(
          message = "Script returned tool at index $index (name='${wrapper.name}') which is not " +
            "an ExecutableTrailblazeTool or DelegatingTrailblazeTool — got " +
            "${tool::class.qualifiedName ?: tool::class.simpleName}. Memory / unknown tools " +
            "cannot be expanded from scripts.",
          source = source,
        )
      }
    }
  }

  private fun buildInput(ctx: TrailblazeToolExecutionContext): JsonObject = buildJsonObject {
    put("memory", buildJsonObject {
      ctx.memory.variables.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    })
    put("params", buildJsonObject {
      params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    })
  }

  companion object {
    private const val YAML_ERROR_PREVIEW_CHARS = 500
    internal const val MAX_DISPATCH_DEPTH = 16

    /**
     * Per-thread counter that bumps for every `trailblaze.execute()` call in progress.
     * Scripted-tool-in-scripted-tool reentrance is legitimate; runaway recursion is not.
     * Thread-local is sufficient because the engine evaluates synchronously via
     * `runBlocking`, so all dispatch frames for a given top-level invocation share
     * the same calling thread.
     */
    internal val dispatchDepth = ThreadLocal.withInitial { 0 }
  }
}

/**
 * Implements the synchronous `trailblaze.execute(toolName, params)` callback the engine
 * exposes to JS. Decodes the tool by name via [TrailblazeYaml], executes it against the
 * captured [TrailblazeToolExecutionContext], logs the invocation so recordings capture
 * it, and returns a JSON-serialized `{ isError, message }` shape the JS wrapper parses.
 *
 * Recording parity with normal dispatch: uses the same [logToolExecution] helper
 * [xyz.block.trailblaze.MaestroTrailblazeAgent] uses, constructing a minimal
 * [TrailblazeAgentContext] from the execution context's logger/session/device/memory
 * fields. Downstream replay sees the primitives as if the agent had dispatched them
 * directly.
 */
private class ScriptToolDispatcher(
  private val context: TrailblazeToolExecutionContext,
) : TrailblazeScriptEngine.ToolDispatcher {

  private val agentContext: TrailblazeAgentContext = object : TrailblazeAgentContext {
    override val trailblazeLogger: TrailblazeLogger = context.trailblazeLogger
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo =
      { context.trailblazeDeviceInfo }
    override val sessionProvider: TrailblazeSessionProvider = context.sessionProvider
    override val memory: AgentMemory = context.memory
  }

  override fun dispatch(toolName: String, paramsJson: String): String {
    val currentDepth = ScriptTrailblazeTool.dispatchDepth.get()
    if (currentDepth >= ScriptTrailblazeTool.MAX_DISPATCH_DEPTH) {
      return encodeResult(
        TrailblazeToolResult.Error.FatalError(
          errorMessage = "Scripted tool recursion exceeded depth " +
            "${ScriptTrailblazeTool.MAX_DISPATCH_DEPTH}; likely infinite reentry via " +
            "trailblaze.execute(). Aborting.",
        ),
      )
    }

    val tool: ExecutableTrailblazeTool = try {
      // JSON is valid YAML flow syntax, so splicing paramsJson into an inline mapping
      // works without a separate JSON→YAML transform.
      val yaml = "- $toolName: $paramsJson"
      val wrapper = TrailblazeYaml.Default.decodeTools(yaml).firstOrNull()
        ?: return encodeResult(
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "trailblaze.execute: decoder returned no tool for '$toolName'",
          ),
        )
      // The return-YAML path in [ScriptTrailblazeTool.toExecutableTrailblazeTools] recursively
      // expands DelegatingTrailblazeTool (e.g. YAML-defined tools like `pressBack`) into
      // their primitives. This imperative `trailblaze.execute(...)` path does NOT yet do
      // the same — a script calling `trailblaze.execute("pressBack", {})` mid-evaluation
      // gets an error result. Symmetrizing is deferred: execute() has synchronous
      // recording/depth semantics that expanding into multiple primitives would need to
      // thread through carefully.
      wrapper.trailblazeTool as? ExecutableTrailblazeTool
        ?: return encodeResult(
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "trailblaze.execute: '$toolName' is not an ExecutableTrailblazeTool " +
              "(got ${wrapper.trailblazeTool::class.simpleName}). " +
              "Delegating / memory / unknown tools cannot be dispatched from scripts yet — " +
              "return them via the script's return-YAML instead, which expands delegating " +
              "tools into their primitives.",
          ),
        )
    } catch (e: Exception) {
      return encodeResult(TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e))
    }

    ScriptTrailblazeTool.dispatchDepth.set(currentDepth + 1)
    return try {
      val before = Clock.System.now()
      // Catch inner so the logger always fires — the devlog's "each trailblaze.execute
      // call records as its own primitive entry" invariant must hold even when a tool's
      // execute() throws rather than returning an Error result.
      val result: TrailblazeToolResult = try {
        runBlocking { tool.execute(context) }
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, tool)
      }
      agentContext.logToolExecution(
        tool = tool,
        timeBeforeExecution = before,
        context = context,
        result = result,
      )
      encodeResult(result)
    } finally {
      ScriptTrailblazeTool.dispatchDepth.set(currentDepth)
    }
  }

  private fun encodeResult(result: TrailblazeToolResult): String = buildJsonObject {
    put("isError", JsonPrimitive(!result.isSuccess()))
    val message = when (result) {
      is TrailblazeToolResult.Success -> result.message
      is TrailblazeToolResult.Error -> result.errorMessage
    }
    if (message != null) put("message", JsonPrimitive(message))
  }.toString()
}
