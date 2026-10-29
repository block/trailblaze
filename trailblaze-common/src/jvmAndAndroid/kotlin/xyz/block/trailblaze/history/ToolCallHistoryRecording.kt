package xyz.block.trailblaze.history

import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
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
import xyz.block.trailblaze.toolcalls.resolveToolName
import xyz.block.trailblaze.toolcalls.scrubSensitivePayload
import xyz.block.trailblaze.toolcalls.sensitiveArgValues
import xyz.block.trailblaze.toolcalls.scrubSensitiveValues
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.util.Console

// The dispatch side of [ToolCallHistory]: what the agent loop and the tool log call. None of it
// throws, because recording history must never fail a tool.

/**
 * A started call: its entry, the sensitive values memory held when it started, the values the tool
 * declares secret once resolved, and whether its success message is a credential. The call can
 * overwrite or forget a secret before it ends, so its outcome is scrubbed against both memories.
 *
 * [valuesAtStart] and [sensitiveKeysAtStart] are what memory held when it started, for the
 * changes it commits. Diffed here rather than recorded by [AgentMemory] because memory has no one
 * place every write goes through: callers write [AgentMemory.variables] directly (the host merging
 * a device's memory after an RPC, for one), and scratch copies made to resolve args would record
 * changes no tool made.
 */
internal class HistoryDispatch(
  val handle: ToolCallHistory.CallHandle,
  val memoryAtStart: AgentMemory,
  val valuesAtStart: Map<String, String>,
  val sensitiveKeysAtStart: Set<String>,
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

/**
 * What a call's outcome and memory values are scrubbed against once it ends: the secrets memory held
 * when it started, those it holds now, and whatever now sits in a key that was sensitive at the
 * start. That last catches a replacement written after a `clear()` dropped the key's marker.
 */
private fun HistoryDispatch.scrubbing(memory: AgentMemory): List<AgentMemory> {
  val stillSecret = AgentMemory()
  sensitiveKeysAtStart.forEach { key ->
    memory.variables[key]?.let { value ->
      stillSecret.markSensitive(key)
      stillSecret.variables[key] = value
    }
  }
  return listOf(memoryAtStart, memory, stillSecret)
}

/** [this] plus the JSON-escaped form of each value that differs, as a secret reads inside a JSON string. */
private fun Collection<String>.withJsonEscapedForms(): List<String> = flatMap { value ->
  val escaped = Json.encodeToString(String.serializer(), value).removeSurrounding("\"")
  if (escaped == value) listOf(value) else listOf(value, escaped)
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
  // A secret arg the tool names is as secret as one it gives by value, wherever its value lands.
  // A scripted tool gets its args as written and resolves tokens itself, so resolve them here too.
  val resolve = { value: String -> runCatching { memory.interpolateVariables(value) }.getOrNull() }
  val secretArgs = tool.sensitiveArgValues(resolve) + executed.sensitiveArgValues()
  val maskedSecrets = (declaredSecrets + secretArgs).distinct().withJsonEscapedForms()
  val raw = tool.toLogPayload().withExecutedToolSecretsRedacted(executed)
  val rendered = buildLogSafeResolvedPayload(raw, memory)
  val scrubbing = listOf(memoryAtStart)
  HistoryDispatch(
    begin(
      sessionId = sessionId,
      name = toolName,
      // Secrets are masked in one pass with memory's, so one overlapping another is masked whole.
      // Resolving can copy a named secret arg's value into another arg (`note: "{{credential}}"`).
      args = scrubSensitivePayload(rendered.raw, scrubbing, maskedSecrets) as? JsonObject ?: rendered.raw,
      rawArgs = scrubSensitivePayload(raw.raw, scrubbing, declaredSecrets) as? JsonObject ?: raw.raw,
      device = device,
    ),
    memoryAtStart,
    // A shallow copy per call, the one cost of recording memory changes.
    valuesAtStart = memory.variables.takeIf { it.isNotEmpty() }?.let { HashMap(it) } ?: emptyMap(),
    sensitiveKeysAtStart = memory.sensitiveKeys.takeIf { it.isNotEmpty() }?.toHashSet() ?: emptySet(),
    declaredSecrets = maskedSecrets,
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
    message = result.historyMessage(dispatch.scrubbing(memory), dispatch.secretResult, dispatch.declaredSecrets),
    endedAt = System.currentTimeMillis(),
    memoryChanges = dispatch.memoryChanges(memory),
    memoryId = memory.historyId(),
    changeAtEnd = dispatch.changeAtEnd(memory),
  )
}

internal fun ToolCallHistory.endDispatch(dispatch: HistoryDispatch?, thrown: Throwable, memory: AgentMemory) {
  dispatch ?: return
  val message = thrown.message?.takeIf { it.isNotBlank() } ?: thrown::class.simpleName.orEmpty()
  end(
    dispatch.handle,
    success = false,
    message = scrubSensitiveValues(
      "${thrown::class.simpleName}: $message",
      dispatch.scrubbing(memory),
      dispatch.declaredSecrets,
    ),
    endedAt = System.currentTimeMillis(),
    // A tool that wrote memory and then threw still changed it.
    memoryChanges = dispatch.memoryChanges(memory),
    memoryId = memory.historyId(),
    changeAtEnd = dispatch.changeAtEnd(memory),
  )
}

/**
 * How [memory] differs from when the call started, by key. A key sensitive at either end records
 * its `{{key}}` token, so a `clear()` that drops a marker can't expose the value set after it;
 * other values are scrubbed of sensitive values, and of the tool's declared secrets, like the
 * outcome is. Reads aren't changes.
 */
private fun HistoryDispatch.memoryChanges(memory: AgentMemory): List<ToolCallHistory.MemoryChange> = try {
  if (memory.variables == valuesAtStart) {
    emptyList()
  } else {
    val after = memory.variables.toMap()
    (valuesAtStart.keys + after.keys).sorted().mapNotNull { key ->
      val value = after[key]
      if (value == valuesAtStart[key]) null else changeTo(key, value, memory)
    }
  }
} catch (e: Exception) {
  Console.log("[ToolCallHistory] could not diff memory: ${e::class.simpleName}: ${e.message}")
  emptyList()
}

/** Each memory a call has recorded against, by identity; weak, so a finished session's memory can go. */
private val memoryIds = WeakHashMap<AgentMemory, Long>()
private val nextMemoryId = AtomicLong()

/** The number the history store tells [this] memory apart by, the same for every call made on it. */
private fun AgentMemory.historyId(): Long = synchronized(memoryIds) {
  memoryIds.getOrPut(this) { nextMemoryId.incrementAndGet() }
}

/**
 * [key] going to [value] (null: deleted), masked; see [memoryChanges]. A key can hold a secret too
 * (`cache-{{pin}}` resolved), so it is scrubbed the same way.
 */
private fun HistoryDispatch.changeTo(key: String, value: String?, memory: AgentMemory): ToolCallHistory.MemoryChange {
  val masked = { text: String -> scrubSensitiveValues(text, scrubbing(memory), declaredSecrets) }
  val shownKey = masked(key)
  if (value == null) {
    return ToolCallHistory.MemoryChange(HistoryMemoryChange(HistoryMemoryChange.DELETE, shownKey, null), null, key)
  }
  val recorded = when {
    key !in sensitiveKeysAtStart && key !in memory.sensitiveKeys -> masked(value)
    // A token naming a key that holds a secret would show the secret.
    shownKey != key -> REDACTED_TOOL_ARG_PLACEHOLDER
    else -> "{{$key}}"
  }.take(ToolCallHistory.MAX_MESSAGE_CHARS)
  return ToolCallHistory.MemoryChange(HistoryMemoryChange(HistoryMemoryChange.SET, shownKey, recorded), sha256(value), key)
}

/** What [key] holds in [memory] now, as a change; for a key the call put back after a nested call changed it. */
private fun HistoryDispatch.changeAtEnd(memory: AgentMemory): (String) -> ToolCallHistory.MemoryChange? = { key ->
  try {
    changeTo(key, memory.variables[key], memory)
  } catch (e: Exception) {
    null
  }
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

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Records a call the tool log saw that no dispatch started. See [ToolCallHistory.recordFinished].
 * It has no memory changes: nothing saw memory before it ran.
 */
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

private fun TrailblazeToolResult.historyMessage(
  memories: List<AgentMemory>,
  secretResult: Boolean,
  declaredSecrets: Collection<String> = emptyList(),
): String? {
  val raw = when (this) {
    is TrailblazeToolResult.Success -> if (secretResult) return message?.let { REDACTED_TOOL_ARG_PLACEHOLDER } else message
    is TrailblazeToolResult.Error -> errorMessage
  } ?: return null
  // Scrubbed whole, before the length cap: a cut first could split a secret the scrub then misses.
  return scrubSensitiveValues(raw, memories, declaredSecrets)
}
