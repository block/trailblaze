package xyz.block.trailblaze.history

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * The framework host function behind `ctx.history`. Not a trailmap host function: the dispatcher
 * answers it before the registry, from any tool, and it reads the session the calling tool runs in.
 *
 * It returns the flat record, ids and links included. The SDK builds the call tree scripts see.
 */
object ToolCallHistoryHostFunctions {

  /** Mirrors `HOST_FUNCTION_HISTORY_TOOL_CALLS` in the SDK's `history.ts`. */
  const val TOOL_CALLS: String = "trailblaze.history.toolCalls"

  // Every field written, nulls included, so a script reads `endedAt: null` rather than a missing key.
  private val json = Json(TrailblazeJsonInstance) {
    encodeDefaults = true
    explicitNulls = true
  }

  fun handles(name: String): Boolean = name == TOOL_CALLS

  /**
   * Runs [name] with [args]; throws [TrailblazeHostFunctionException] with a script-safe message.
   * `section` names one of [HistorySectionKind.RUN_SECTIONS] and means this run's latest section of
   * that kind, with the sections inside it; none open yet reads as no calls.
   */
  fun call(name: String, args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    val sessionId = context.sessionProvider.invoke().sessionId
    rejectUnknown(name, args, accepted = setOf("section"))
    val kind = args["section"]?.let { section ->
      (section as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in HistorySectionKind.RUN_SECTIONS }
        ?: throw TrailblazeHostFunctionException(
          "ctx.history.toolCalls: section must be one of " +
            "${HistorySectionKind.RUN_SECTIONS.joinToString { "\"$it\"" }}, not $section.",
        )
    }
    val calls = if (kind == null) {
      ToolCallHistory.toolCalls(sessionId)
    } else {
      currentRunSection(sessionId, kind)?.let { ToolCallHistory.toolCalls(sessionId, it.id) }.orEmpty()
    }
    return json.encodeToJsonElement(ListSerializer(HistoryToolCall.serializer()), calls)
  }

  /**
   * The latest [kind] section inside the latest run, so a run whose setup failed before its trail
   * started reads no trail rather than the previous run's.
   */
  private fun currentRunSection(sessionId: SessionId, kind: String): HistorySection? {
    val sections = ToolCallHistory.sections(sessionId)
    val run = sections.lastOrNull { it.kind == HistorySectionKind.RUN } ?: return sections.lastOrNull { it.kind == kind }
    val byId = sections.associateBy { it.id }
    fun inRun(section: HistorySection): Boolean {
      var parent = section.parentId
      while (parent != null) {
        if (parent == run.id) return true
        parent = byId[parent]?.parentId
      }
      return false
    }
    return sections.lastOrNull { it.kind == kind && inRun(it) }
  }

  private fun rejectUnknown(name: String, args: JsonObject, accepted: Set<String>) {
    val unknown = args.keys - accepted
    if (unknown.isNotEmpty()) {
      throw TrailblazeHostFunctionException(
        "Host function '$name' does not accept ${unknown.joinToString { "'$it'" }}. " +
          "Accepted: ${accepted.joinToString().ifEmpty { "(no arguments)" }}.",
      )
    }
  }
}
