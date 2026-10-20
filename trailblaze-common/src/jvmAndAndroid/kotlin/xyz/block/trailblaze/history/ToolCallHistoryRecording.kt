package xyz.block.trailblaze.history

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.withExecutedToolSecretsRedacted
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.SensitiveValuesTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.buildLogSafeResolvedPayload
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.toolcalls.resolveToolName
import xyz.block.trailblaze.toolcalls.scrubSensitivePayload
import xyz.block.trailblaze.toolcalls.scrubSensitiveValues
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.toolcalls.withSensitiveValuesRedacted
import xyz.block.trailblaze.util.Console

// The dispatch side of [ToolCallHistory]: what the agent loop and the tool log call. None of it
// throws, because recording history must never fail a tool.

/**
 * A started call: its entry, the sensitive values memory held when it started, the values the tool
 * declares secret once resolved, and whether its success message is a credential. The call can
 * overwrite or forget a secret before it ends, so its outcome is scrubbed against both memories.
 */
internal class HistoryDispatch(
  val handle: ToolCallHistory.CallHandle,
  val memoryAtStart: AgentMemory,
  val declaredSecrets: Collection<String> = emptyList(),
  val secretResult: Boolean = false,
)

/**
 * The sensitive keys and args [this] holds right now, copied, so a later write to memory can't
 * take a secret out of the scrub.
 */
internal fun AgentMemory.sensitiveSnapshot(): AgentMemory = AgentMemory().also { snapshot ->
  sensitiveKeys.forEach { key ->
    variables[key]?.let { value ->
      snapshot.markSensitive(key)
      snapshot.variables[key] = value
    }
  }
  val args = args
  sensitiveArgNames.forEach { name ->
    snapshot.markArgSensitive(name)
    args[name]?.let { snapshot.putArg(name, it) }
  }
}

/** Starts the entry for [tool], dispatched on [context] as [toolName]; null if the session can't be read. */
internal fun ToolCallHistory.beginDispatch(
  context: TrailblazeToolExecutionContext,
  toolName: String,
  tool: TrailblazeTool,
  device: String?,
  memory: AgentMemory,
): HistoryDispatch? = try {
  beginDispatch(context.sessionProvider.invoke().sessionId, toolName, tool, device, memory)
} catch (e: Exception) {
  Console.log("[ToolCallHistory] could not record $toolName: ${e::class.simpleName}: ${e.message}")
  null
}

/** [beginDispatch] for a caller that knows the session but has no execution context. */
internal fun ToolCallHistory.beginDispatch(
  sessionId: SessionId,
  toolName: String,
  tool: TrailblazeTool,
  device: String?,
  memory: AgentMemory,
): HistoryDispatch? = try {
  val memoryAtStart = memory.sensitiveSnapshot()
  // The two payloads the session log keeps, built the same way: declared sensitive args masked,
  // and the rendered form resolved against memory as it stands now, before the tool can change it.
  // A call another tool made arrives with its values already resolved, so both are also scrubbed,
  // sending any sensitive memory value back to its token.
  // A declared secret can itself be a token (`secrets: ["{{token}}"]`), so mask what the executed
  // instance declares, as the session log does.
  val executed = runCatching { interpolateMemoryInTool(tool, memory) }.getOrDefault(tool)
  val declaredSecrets = (tool.sensitiveValues() + executed.sensitiveValues()).distinct()
  val maskedSecrets = declaredSecrets.withJsonEscapedForms()
  val raw = tool.toLogPayload().withExecutedToolSecretsRedacted(executed).withSensitiveValuesRedacted(declaredSecrets)
  val rendered = buildLogSafeResolvedPayload(raw, memory).withSensitiveValuesRedacted(declaredSecrets)
  val scrubbing = listOf(memoryAtStart)
  HistoryDispatch(
    begin(
      sessionId = sessionId,
      name = toolName,
      args = scrubSensitivePayload(rendered.raw, scrubbing) as? JsonObject ?: rendered.raw,
      rawArgs = scrubSensitivePayload(raw.raw, scrubbing) as? JsonObject ?: raw.raw,
      device = device,
    ),
    memoryAtStart,
    maskedSecrets,
    secretResult = tool is SecretResultTrailblazeTool || executed is SecretResultTrailblazeTool,
  )
} catch (e: Exception) {
  Console.log("[ToolCallHistory] could not record $toolName: ${e::class.simpleName}: ${e.message}")
  null
}

internal fun ToolCallHistory.endDispatch(dispatch: HistoryDispatch?, result: TrailblazeToolResult, memory: AgentMemory) {
  dispatch ?: return
  end(
    dispatch.handle,
    success = result is TrailblazeToolResult.Success,
    message = result.historyMessage(listOf(dispatch.memoryAtStart, memory), dispatch.secretResult)
      ?.let { redactSensitiveValuesIn(it, dispatch.declaredSecrets) },
  )
}

internal fun ToolCallHistory.endDispatch(dispatch: HistoryDispatch?, thrown: Throwable, memory: AgentMemory) {
  dispatch ?: return
  val message = thrown.message?.takeIf { it.isNotBlank() } ?: thrown::class.simpleName.orEmpty()
  end(
    dispatch.handle,
    success = false,
    message = redactSensitiveValuesIn(
      scrubSensitiveValues("${thrown::class.simpleName}: $message", listOf(dispatch.memoryAtStart, memory)),
      dispatch.declaredSecrets,
    ),
  )
}

/**
 * Records [tool] around [execute], for a caller that runs a tool itself rather than through an
 * agent's dispatch loop: the AI agent's dynamic tools, the daemon's host-local path, the children
 * of a delegating tool. The calls it makes name it as their parent, and the log entry it writes
 * isn't recorded a second time.
 */
suspend fun ToolCallHistory.recordExecution(
  context: TrailblazeToolExecutionContext,
  tool: TrailblazeTool,
  execute: suspend () -> TrailblazeToolResult,
): TrailblazeToolResult {
  val dispatch = beginDispatch(
    context = context,
    toolName = tool.resolveToolName(),
    tool = tool,
    device = context.deviceBindings?.activeName,
    memory = context.memory,
  )
  return finishExecution(dispatch, context.memory, execute)
}

/**
 * [recordExecution] for a caller with no execution context, such as a daemon route that sends a
 * tool to a device or expands it into children. [memory] is what the tool's values resolve against.
 */
suspend fun ToolCallHistory.recordExecution(
  sessionId: SessionId,
  tool: TrailblazeTool,
  device: String? = null,
  memory: AgentMemory = AgentMemory(),
  execute: suspend () -> TrailblazeToolResult,
): TrailblazeToolResult {
  val dispatch = beginDispatch(sessionId, tool.resolveToolName(), tool, device, memory)
  return finishExecution(dispatch, memory, execute)
}

private suspend fun ToolCallHistory.finishExecution(
  dispatch: HistoryDispatch?,
  memory: AgentMemory,
  execute: suspend () -> TrailblazeToolResult,
): TrailblazeToolResult {
  val result = try {
    execute()
  } catch (e: Throwable) {
    endDispatch(dispatch, e, memory)
    throw e
  }
  endDispatch(dispatch, result, memory)
  return result
}

/** Records a call the tool log saw that no dispatch started. See [ToolCallHistory.recordFinished]. */
internal fun ToolCallHistory.recordLogged(
  sessionId: SessionId,
  tool: TrailblazeTool,
  toolName: String,
  args: JsonObject,
  rawArgs: JsonObject,
  device: String?,
  startedAt: Long,
  result: TrailblazeToolResult,
  memories: List<AgentMemory>,
) {
  try {
    recordFinished(
      sessionId = sessionId,
      name = toolName,
      args = scrubSensitivePayload(args, memories) as? JsonObject ?: args,
      rawArgs = scrubSensitivePayload(rawArgs, memories) as? JsonObject ?: rawArgs,
      device = device,
      startedAt = startedAt,
      success = result is TrailblazeToolResult.Success,
      message = result.historyMessage(memories, tool is SecretResultTrailblazeTool),
    )
  } catch (e: Exception) {
    Console.log("[ToolCallHistory] could not record $toolName: ${e::class.simpleName}: ${e.message}")
  }
}

private fun TrailblazeTool.sensitiveValues(): Collection<String> =
  (this as? SensitiveValuesTrailblazeTool)?.sensitiveValues ?: emptyList()

/** [this] plus the JSON-escaped form of each value that differs, as a secret reads inside a JSON string. */
private fun Collection<String>.withJsonEscapedForms(): List<String> = flatMap { value ->
  val escaped = Json.encodeToString(String.serializer(), value).removeSurrounding("\"")
  if (escaped == value) listOf(value) else listOf(value, escaped)
}

private fun TrailblazeToolResult.historyMessage(memories: List<AgentMemory>, secretResult: Boolean): String? {
  val raw = when (this) {
    is TrailblazeToolResult.Success -> if (secretResult) return message?.let { REDACTED_TOOL_ARG_PLACEHOLDER } else message
    is TrailblazeToolResult.Error -> errorMessage
  } ?: return null
  // Scrubbed whole, before the length cap: a cut first could split a secret the scrub then misses.
  return scrubSensitiveValues(raw, memories)
}
