package xyz.block.trailblaze.toolcalls

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What callers outside any tool (a trail step, `trailblaze tool`, MCP `step`, the agent) wrote into
 * the arguments of the calls they made, so a tool that forwards a tool name it was handed
 * (`runIf`'s branches, an undo's `calls`) can't use it to reach a `callableOnlyFromTools` tool.
 * Such a tool runs only when a tool's own code names it.
 *
 * Shared by every context a call's nested calls run on, including the fresh one the daemon builds
 * for each nested host-local call, so the origin follows the call rather than one context.
 *
 * Matched by name, wherever it sits in the arguments: a key (`runIf`'s `{ exec: {...} }`) or a
 * value (an undo's `tool: exec`), as written and with its `{{memory}}` tokens resolved. A call's
 * names count only while that call runs, so a later call's own `exec` isn't refused. While two
 * outside calls run at once on one origin, each one's names count for both. That, and a caller's
 * argument that merely equals a tool-only name, err on the refusing side.
 */
class CallerArguments {
  /** The names each outside call still running passed, by the [Note] it holds. */
  private val running: MutableMap<Note, Set<String>> = ConcurrentHashMap()

  /** An outside call's names, counted until [end]. */
  class Note internal constructor(private val origin: CallerArguments) {
    fun end() {
      origin.running.remove(this)
    }
  }

  /**
   * Notes [tool]'s arguments, unless a tool is the one making the call, and returns the [Note] to
   * end when the call does. [resolve] turns a string's `{{tokens}}` into what the tool will see.
   */
  fun noteDispatch(tool: TrailblazeTool, resolve: (String) -> String? = { null }): Note? {
    if (inToolCall.get() != null) return null
    val payload = runCatching { tool.toLogPayload().raw }.getOrNull() ?: return null
    val names = HashSet<String>()
    collect(payload, names, resolve)
    if (names.isEmpty()) return null
    return Note(this).also { running[it] = names }
  }

  /**
   * Why a call a tool makes to [toolName] must be refused, or null when it may run: [toolName] is
   * `callableOnlyFromTools` and a caller outside any tool passed it in, so the tool is forwarding it.
   */
  fun forwardedToolOnlyRefusal(toolName: String, repo: TrailblazeToolRepo?): String? {
    if (running.values.none { toolName in it }) return null
    val toolOnly = repo?.isCallableOnlyFromTools(toolName)
      ?: TrailblazeSerializationInitializer.buildAllTools()[ToolName(toolName)]?.isCallableOnlyFromTools()
      ?: false
    return if (toolOnly) forwardedToolOnlyMessage(toolName) else null
  }

  /**
   * Runs [block], a call a tool is making, so the dispatches inside it aren't noted as a caller's,
   * and so a context built for it on the way ([current]) shares this origin. Scoped to the
   * coroutine, so an outside call running alongside is still noted.
   */
  suspend fun <T> callFromTool(block: suspend () -> T): T =
    withContext(Element(this) + inToolCall.asContextElement(this)) { block() }

  private fun collect(element: JsonElement, into: MutableSet<String>, resolve: (String) -> String?) {
    fun add(text: String) {
      if (text.length <= MAX_NAME_CHARS) into += text
      if ('{' in text) runCatching { resolve(text) }.getOrNull()?.takeIf { it.length <= MAX_NAME_CHARS }?.let { into += it }
    }
    when (element) {
      is JsonObject -> element.forEach { (key, value) ->
        add(key)
        collect(value, into, resolve)
      }
      is JsonArray -> element.forEach { collect(it, into, resolve) }
      is JsonPrimitive -> if (element.isString) add(element.content)
    }
  }

  private class Element(val origin: CallerArguments) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Element>
  }

  companion object {
    /** Longest string value kept; tool names are far shorter. */
    private const val MAX_NAME_CHARS = 128

    /**
     * The origin whose tool call this thread is running, carried across the coroutine's thread
     * hops; read by [noteDispatch], which isn't suspending.
     */
    private val inToolCall = ThreadLocal<CallerArguments?>()

    /** The origin of the tool call running on this coroutine, for a context built to run its nested call. */
    suspend fun current(): CallerArguments? = currentCoroutineContext()[Element]?.origin
  }
}

/** The refusal a tool meets when it forwards a `callableOnlyFromTools` tool's name it was handed. */
fun forwardedToolOnlyMessage(toolName: String): String =
  "`$toolName` can only be called from another tool's own code. Here its name was passed in by a " +
    "trail step, `trailblaze tool`, MCP `step`, or the agent, and a tool can't forward it."
