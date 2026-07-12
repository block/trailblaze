package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves `{{var}}` / `${var}` tokens in a [TrailblazeTool]'s string fields against [memory].
 *
 * This is the SINGLE memory-interpolation boundary for tool dispatch: every agent's
 * [xyz.block.trailblaze.BaseTrailblazeAgent] loop runs each tool through this function once,
 * right before driver dispatch, so individual tools never need to self-interpolate inside
 * `execute()` (a tool that forgot to was the recurring footgun — it operated on the literal
 * token). The function runs wherever the dispatch loop runs: on the host for host drivers,
 * and on the device for RPC-routed tools (the RPC clients send the raw, token-bearing tool
 * plus a memory snapshot, and the on-device dispatch loop resolves it there).
 *
 * Contract: returns the SAME instance when nothing changed (kill-switch on, empty memory,
 * non-interpolatable tool type, or no tokens present). Callers use referential identity to
 * detect "interpolation actually rewrote this tool" — that is what drives the raw-vs-resolved
 * split in `TrailblazeLog.TrailblazeToolLog` (`rawTrailblazeTool`). When it does rewrite, the
 * result is always the same concrete class as the input (interpolation only mutates string
 * scalars), which is what makes the generic signature sound.
 *
 * Substitution runs on the typed JSON tree (string scalars only) so the downstream encoder owns
 * escaping of `"`, `\n`, `:`, `#` in resolved values rather than splicing raw text into a wire
 * payload. Single-pass, no rescan (see [AgentMemory.interpolateVariables]) — so a second pass
 * over an already-resolved tool is a no-op, which keeps double-interpolation safe during
 * transition windows (e.g. a new host driving an old on-device build whose tools still
 * self-interpolate, or vice versa).
 *
 * Per-type behavior:
 * - [OtherTrailblazeTool] — passed through UNTOUCHED. It's an unresolved placeholder; whichever
 *   executor finally resolves it to a concrete tool re-enters a dispatch boundary with that
 *   concrete tool, and resolving tokens there (host or device) keeps the raw form available to
 *   that executor's log.
 * - [RawArgumentTrailblazeTool] (QuickJS / subprocess scripted tools) — passed through
 *   UNTOUCHED. These instances hold live runtime handles (a QuickJS engine, an MCP subprocess)
 *   that a serializer round-trip cannot reconstruct. Their `execute()` resolves the args JSON at
 *   the engine boundary while `rawToolArguments` stays token-bearing for logs — the pattern this
 *   function generalizes to class-backed tools.
 * - [YamlDefinedTrailblazeTool] — params tree interpolated directly (the class has no
 *   `@Serializable` constructor; its params ARE the JSON tree already).
 * - Everything else — encode via the tool's concrete class serializer, interpolate the tree,
 *   decode back. Tools that can't round-trip (no serializer, contextual nested fields) pass
 *   through unchanged with a once-per-class diagnostic; they keep whatever behavior they had.
 */
fun <T : TrailblazeTool> interpolateMemoryInTool(
  tool: T,
  memory: AgentMemory,
  disabled: Boolean = isBoundaryMemoryInterpolationDisabled(),
): T {
  if (disabled || memory.variables.isEmpty()) return tool
  return when (tool) {
    is OtherTrailblazeTool -> tool
    is RawArgumentTrailblazeTool -> tool
    is YamlDefinedTrailblazeTool -> {
      val interpolatedParams = tool.params.mapValues { (_, value) ->
        memory.interpolateVariablesInJson(value)
      }
      if (interpolatedParams == tool.params) {
        tool
      } else {
        @Suppress("UNCHECKED_CAST")
        (YamlDefinedTrailblazeTool(tool.config, interpolatedParams) as T)
      }
    }
    else -> interpolateViaClassSerializer(tool, memory)
  }
}

/**
 * Restores the AUTHORED tool identity on a failure result whose
 * [TrailblazeToolResult.Error.ExceptionThrown.command] is the boundary-RESOLVED instance the
 * failing tool stamped as `this`. That command renders verbatim into LLM-facing error content
 * (`AgentMessages`' exception renderer) and from there into persisted LLM request logs — so, by
 * the same rule that keeps `toolsExecuted` authored, it must carry the token-bearing form, not
 * resolved memory values (a `rememberSensitive` secret in a failing tool's args would otherwise
 * leak). Matched by concrete class: when the boundary rewrote the tool, the dispatched instance
 * differs from [authored] but keeps its class, and tools stamp the executing instance itself
 * (`command = this` / `fromThrowable(e, tool)`). A command of a different class (a wrapper
 * reporting its inner tool) passes through untouched, as does everything when interpolation was
 * a no-op (then `command === authored` already).
 */
fun <R : TrailblazeToolResult> R.withAuthoredCommandIdentity(authored: TrailblazeTool): R {
  if (this !is TrailblazeToolResult.Error.ExceptionThrown) return this
  val embedded = command ?: return this
  if (embedded === authored || embedded::class != authored::class) return this
  @Suppress("UNCHECKED_CAST")
  return copy(command = authored) as R
}

/**
 * Free-form-string analog of [buildLogSafeResolvedPayload]: replaces any resolved sensitive value
 * (a key in [AgentMemory.sensitiveKeys]) that appears in [text] with its `{{key}}` token. The
 * structured-payload scrub can remap tokens because it re-interpolates the authored args; an opaque
 * string a tool already baked (e.g. an error message) has no tokens left, so it is matched by value.
 * That is coarser — a very short sensitive value could match unintended substrings — but it is the
 * only handle on an opaque string, and over-masking a diagnostic is the safe failure. No-op when
 * there are no sensitive keys.
 */
fun scrubSensitiveValues(text: String, memory: AgentMemory): String {
  if (memory.sensitiveKeys.isEmpty()) return text
  var result = text
  memory.sensitiveKeys.forEach { key ->
    val value = memory.variables[key]
    if (!value.isNullOrEmpty()) {
      result = result.replace(value, "{{$key}}")
    }
  }
  return result
}

/**
 * Full failure-result hardening for LLM-facing / persisted content: restores the authored
 * (token-bearing) command identity ([withAuthoredCommandIdentity]) AND scrubs any resolved
 * `rememberSensitive` value a failing tool spliced into its free-form [errorMessage]
 * ([scrubSensitiveValues]). The command swap alone keeps STRUCTURED args safe; the message scrub
 * closes the gap for a tool that interpolates a token into a diagnostic string — e.g. a remember
 * tool's "Failed to find element for prompt: …" on a lookup miss, where the prompt arrives
 * boundary-resolved. Use this (not the bare identity swap) wherever a failure result leaves the
 * dispatch loop toward the LLM / logs.
 */
fun <R : TrailblazeToolResult> R.withAuthoredFailureContent(authored: TrailblazeTool, memory: AgentMemory): R {
  val identityRestored = withAuthoredCommandIdentity(authored)
  if (identityRestored !is TrailblazeToolResult.Error.ExceptionThrown) return identityRestored
  val scrubbed = scrubSensitiveValues(identityRestored.errorMessage, memory)
  if (scrubbed == identityRestored.errorMessage) return identityRestored
  @Suppress("UNCHECKED_CAST")
  return identityRestored.copy(errorMessage = scrubbed) as R
}

/**
 * Kill-switch for the dispatch-boundary memory interpolation. When set, every dispatch sends the
 * tool exactly as authored — memory tokens reach tools literally (tools no longer self-interpolate,
 * so nothing resolves them). Use it to triage a suspected regression in the serialize → interpolate
 * → deserialize round-trip without a re-deploy; it is NOT a "restore old interpolation" switch.
 * Read per call so it flips on a running daemon (host side; on-device instrumentation has no easy
 * env channel). `1` or `true` (case-insensitive) disables. Mirrors the env-read style of
 * `TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION`.
 */
fun isBoundaryMemoryInterpolationDisabled(): Boolean {
  val raw = System.getenv("TRAILBLAZE_DISABLE_BOUNDARY_MEMORY_INTERPOLATION") ?: return false
  return raw == "1" || raw.equals("true", ignoreCase = true)
}

/**
 * The RESOLVED tool-log payload as it is safe to persist: non-sensitive tokens in [rawPayload]
 * resolved to the values the driver actually received, while any token whose key is in
 * [AgentMemory.sensitiveKeys] stays a literal `{{key}}` — so a `rememberSensitive` secret (PIN,
 * password, card number) never enters the log/recording stream even though the dispatched
 * instance carried the real value.
 *
 * Implemented by interpolating with a copy of [memory] whose sensitive keys map to their own
 * token text — identical substitution semantics to what the dispatch boundary applied (same
 * single-pass walk, same unknown-token behavior), differing only on sensitive keys. One cosmetic
 * consequence: a `${key}`-style sensitive token is rendered in the canonical `{{key}}` form.
 *
 * Returns [rawPayload] itself when nothing resolves (callers then treat raw and resolved as the
 * same payload and skip the split).
 */
fun buildLogSafeResolvedPayload(
  rawPayload: OtherTrailblazeTool,
  memory: AgentMemory,
): OtherTrailblazeTool {
  if (memory.variables.isEmpty()) return rawPayload
  val scrubMemory = AgentMemory().apply {
    variables.putAll(memory.variables)
    memory.sensitiveKeys.forEach { key -> variables[key] = "{{$key}}" }
  }
  // warnUnknownTokens = false: this scrub AgentMemory is rebuilt every call, so its per-instance
  // unknown-token throttle always starts empty. The dispatch-boundary pass already logged any
  // unknown token once for the session against the real memory; re-logging here would defeat that
  // throttle and flood a poll loop (e.g. a retrying assertVisible) with a line per dispatch.
  val scrubbed = scrubMemory.interpolateVariablesInJson(rawPayload.raw, warnUnknownTokens = false)
  return if (scrubbed == rawPayload.raw) {
    rawPayload
  } else {
    OtherTrailblazeTool(toolName = rawPayload.toolName, raw = scrubbed as JsonObject)
  }
}

@OptIn(InternalSerializationApi::class)
private fun <T : TrailblazeTool> interpolateViaClassSerializer(tool: T, memory: AgentMemory): T = try {
  val concreteSerializer = @Suppress("UNCHECKED_CAST")
  (tool::class.serializer() as KSerializer<T>)
  val tree = INTERPOLATION_JSON.encodeToJsonElement(concreteSerializer, tool)
  val interpolated = memory.interpolateVariablesInJson(tree)
  if (interpolated == tree) {
    tool
  } else {
    INTERPOLATION_JSON.decodeFromJsonElement(concreteSerializer, interpolated)
  }
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
  throw e
} catch (e: Exception) {
  // No serializer (dynamically-constructed tool), or a field shape the bare Json can't encode
  // (e.g. a @Contextual nested TrailblazeTool — round-tripping it through the contextual
  // serializer would also downgrade the nested concrete tool to OtherTrailblazeTool, so
  // passing through is the CORRECT behavior there, not just the safe one: nested tools hit
  // this boundary themselves when they're dispatched).
  val className = tool::class.simpleName ?: "<anonymous>"
  if (interpolationFailureClassesLogged.add(className)) {
    // Console.info, not log: this is a correctness breadcrumb (a tool silently stopped resolving
    // its memory tokens), and CLI quiet mode suppresses Console.log. Without it, the only field
    // signal is literal {{tokens}} showing up in that tool's logged args.
    Console.info(
      "[ToolMemoryInterpolation] $className can't round-trip through its class serializer " +
        "(${e::class.simpleName}: ${e.message}); dispatching as-authored. Memory tokens in its " +
        "args (if any) will not resolve. Subsequent messages for this class are suppressed.",
    )
  }
  tool
}

/**
 * Once-per-class throttle for the round-trip diagnostic, same rationale as `encodeFailureKeysLogged`.
 * Concurrent set (unlike that commonMain sibling) because the dispatch boundary is JVM-wide and
 * reachable from multiple sessions/agents/tests at once — matching the `ConcurrentHashMap.newKeySet`
 * "once-per-key" sets `AgentMemory` uses on this same path.
 */
private val interpolationFailureClassesLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

// Symmetric encode/decode: keep defaults so a tool field that happens to equal its default
// today doesn't silently take on a different default if that default ever changes. We only
// mutate string scalars on the tree, so explicit defaults add no semantic risk. Deliberately
// NO serializersModule: a module would let @Contextual TrailblazeTool fields encode — and then
// decode back as OtherTrailblazeTool, silently type-changing nested tools. The catch above
// turns that shape into a pass-through instead.
private val INTERPOLATION_JSON = Json {
  encodeDefaults = true
}
