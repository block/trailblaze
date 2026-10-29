package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.serializer
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.MalformedArgTokenException
import xyz.block.trailblaze.yaml.TrailArgTokens
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
 * - Everything else — encode via the tool's concrete class serializer, interpolate the tree,
 *   decode back. Tools that can't round-trip (no serializer, contextual nested fields) pass
 *   through unchanged with a once-per-class diagnostic; they keep whatever behavior they had.
 */
fun <T : TrailblazeTool> interpolateMemoryInTool(
  tool: T,
  memory: AgentMemory,
  disabled: Boolean = isBoundaryMemoryInterpolationDisabled(),
): T {
  if (disabled || (memory.variables.isEmpty() && memory.args.isEmpty())) return tool
  return when (tool) {
    is OtherTrailblazeTool -> tool
    is RawArgumentTrailblazeTool -> tool
    else -> interpolateViaClassSerializer(tool, memory)
  }
}

/**
 * Restores the AUTHORED tool identity on a failure result whose
 * [TrailblazeToolResult.Error.ExceptionThrown.command] is the boundary-RESOLVED instance the
 * failing tool stamped as `this`. That command renders verbatim into LLM-facing error content
 * (the agent's tool-result text) and from there into persisted LLM request logs — so, by
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
 * only handle on an opaque string, and over-masking a diagnostic is the safe failure.
 *
 * An `args.` value that captured a secret ([AgentMemory.sensitiveArgNames]) is masked to its
 * `{{args.name}}` token too. That taint outlives the memory key it came from, so the value stays
 * masked after that key is deleted. A token whose name holds a secret is masked as
 * [REDACTED_TOOL_ARG_PLACEHOLDER] instead. No-op when nothing is sensitive.
 */
fun scrubSensitiveValues(text: String, memory: AgentMemory): String =
  scrubSensitiveValues(text, listOf(memory))

/**
 * Match all invocation candidates against the original text; never scrub generated tokens. Each of
 * [declaredSecrets] (secrets a tool gives by value) is masked as [REDACTED_TOOL_ARG_PLACEHOLDER] in
 * the same pass, so a secret overlapping another is masked whole rather than around the other's token.
 */
internal fun scrubSensitiveValues(
  text: String,
  memories: List<AgentMemory>,
  declaredSecrets: Collection<String> = emptyList(),
): String {
  val replacements = (
    memories.flatMap { memory ->
      memory.sensitiveKeys.mapNotNull { key ->
        memory.variables[key]?.takeIf { it.isNotEmpty() }?.let { it to "{{$key}}" }
      } + sensitiveArgTexts(memory).map { (name, value) ->
        value to "{{${TrailArgTokens.ARGS_PREFIX}$name}}"
      }
    } + declaredSecrets.filter { it.isNotBlank() }.map { it to REDACTED_TOOL_ARG_PLACEHOLDER }
    ).flatMap { (value, token) ->
    // A secret inside a JSON string reads escaped (a quote as `\"`), so mask that form too.
    val escaped = Json.encodeToString(String.serializer(), value).removeSurrounding("\"")
    if (escaped == value) listOf(value to token) else listOf(value to token, escaped to token)
  }.sortedByDescending { it.first.length }.distinctBy { it.first }.toMap().let { byValue ->
    // A key can be named after a secret (`token-1234` beside `pin`), so its token would show one.
    byValue.mapValues { (_, token) -> if (byValue.keys.any { it in token }) REDACTED_TOOL_ARG_PLACEHOLDER else token }
  }
  if (replacements.isEmpty()) return text
  return maskMatches(text, replacements)
}

/**
 * [text] with every occurrence of each key of [replacements] replaced by its token. Matches that
 * intersect (`1234` and `34abcd` in `1234abcd`) are masked as one span, so neither secret shows
 * around the other's token: the span takes the token of a match that covers it whole, else
 * [REDACTED_TOOL_ARG_PLACEHOLDER].
 */
private fun maskMatches(text: String, replacements: Map<String, String>): String {
  class Match(val start: Int, val end: Int, val token: String)
  val matches = replacements.flatMap { (value, token) ->
    generateSequence(text.indexOf(value).takeIf { it >= 0 }) { from ->
      text.indexOf(value, from + 1).takeIf { it >= 0 }
    }.map { Match(it, it + value.length, token) }.toList()
  }.sortedWith(compareBy<Match> { it.start }.thenByDescending { it.end })
  if (matches.isEmpty()) return text
  return buildString {
    var copiedTo = 0
    var i = 0
    while (i < matches.size) {
      val first = matches[i]
      var end = first.end
      var j = i + 1
      while (j < matches.size && matches[j].start < end) {
        end = maxOf(end, matches[j].end)
        j++
      }
      append(text, copiedTo, first.start)
      // Sorted by start then longest, so [first] is the one match that can cover the whole span.
      append(if (first.end == end) first.token else REDACTED_TOOL_ARG_PLACEHOLDER)
      copiedTo = end
      i = j
    }
    append(text, copiedTo, text.length)
  }
}

/**
 * Each sensitive arg's value as text, for value matching. Null and boolean args are skipped: they
 * can't hold a secret, and masking every `true` in a payload would only hide diagnostics. Sensitive
 * object args also retain scalar values reachable through supported dotted args paths.
 */
private fun sensitiveArgTexts(memory: AgentMemory): List<Pair<String, String>> {
  if (memory.sensitiveArgNames.isEmpty()) return emptyList()
  val args = memory.args
  return buildList {
    fun addObjectScalars(path: String, value: JsonObject) {
      value.forEach { (key, child) ->
        if (!TrailArgTokens.isValidArgName(key)) return@forEach
        val childPath = "$path.$key"
        when (child) {
          is JsonObject -> addObjectScalars(childPath, child)
          is JsonPrimitive -> {
            if (child != JsonNull && (child.isString || child.booleanOrNull == null)) {
              child.content.takeIf { it.isNotEmpty() }?.let { add(childPath to it) }
            }
          }
          else -> Unit
        }
      }
    }

    memory.sensitiveArgNames.forEach { name ->
      val value = args[name]
      val text =
        when (value) {
          null,
          JsonNull -> null
          is JsonPrimitive ->
            value.content.takeUnless { value.booleanOrNull != null && !value.isString }
          else -> value.toString()
        }
      text?.takeIf { it.isNotEmpty() }?.let { add(name to it) }
      // Only object paths supported by args interpolation can name a scalar replacement.
      if (value is JsonObject) addObjectScalars(name, value)
    }
  }
}

/**
 * Structured-payload analog of [scrubSensitiveValues]: scrubs a tool's error payload
 * (`ExceptionThrown.structuredPayload`) the same way the free-form message is scrubbed — a tool
 * could splice a resolved `rememberSensitive` value into its payload just as easily as into its
 * message. The scrub traverses the tree and matches DECODED string contents, never the serialized
 * form — a secret containing a JSON-escaped character (quote, backslash, newline) appears as
 * `\"`/`\\`/`\n` in encoded text, where a literal value match would silently miss it. Object member
 * NAMES are scrubbed too: payload schemas are repo-owned and can legally key a map by a resolved
 * value (two keys collapsing to one token drops an entry — over-masking is the safe failure). A
 * non-string primitive whose text matches a secret (e.g. a numeric PIN) is masked as a string
 * token: the type change is visible, the secret is not.
 */
fun scrubSensitivePayload(payload: JsonElement, memory: AgentMemory): JsonElement =
  scrubSensitivePayload(payload, listOf(memory))

/**
 * [scrubSensitivePayload] that also masks each of [declaredSecrets] in string values, in the same
 * pass as memory's secrets (see [scrubSensitiveValues]). Keys are scrubbed against memory only.
 */
internal fun scrubSensitivePayload(
  payload: JsonElement,
  memories: List<AgentMemory>,
  declaredSecrets: Collection<String> = emptyList(),
): JsonElement {
  if (declaredSecrets.isEmpty() && memories.all { it.sensitiveKeys.isEmpty() && it.sensitiveArgNames.isEmpty() }) {
    return payload
  }
  return when (payload) {
    is JsonObject -> JsonObject(
      payload.entries.associate { (key, value) ->
        scrubSensitiveValues(key, memories) to scrubSensitivePayload(value, memories, declaredSecrets)
      },
    )
    is JsonArray -> JsonArray(payload.map { scrubSensitivePayload(it, memories, declaredSecrets) })
    is JsonPrimitive -> {
      if (payload is JsonNull) return payload
      val scrubbed = scrubSensitiveValues(payload.content, memories, declaredSecrets)
      when {
        scrubbed == payload.content -> payload
        else -> JsonPrimitive(scrubbed)
      }
    }
  }
}

/**
 * Full failure-result hardening for LLM-facing / persisted content: restores the authored
 * (token-bearing) command identity ([withAuthoredCommandIdentity]) AND scrubs any resolved
 * `rememberSensitive` value a failing tool spliced into its free-form [errorMessage]
 * ([scrubSensitiveValues]) or its structured [TrailblazeToolResult.Error.ExceptionThrown.structuredPayload]
 * ([scrubSensitivePayload]). The command swap alone keeps STRUCTURED args safe; the message and
 * payload scrubs close the gap for a tool that interpolates a token into its diagnostics — e.g. a
 * remember tool's "Failed to find element for prompt: …" on a lookup miss, where the prompt arrives
 * boundary-resolved. Use this (not the bare identity swap) wherever a failure result leaves the
 * dispatch loop toward the LLM / logs — downstream carriers (`TrailheadException.payload`,
 * `SessionStatus.Ended.Failed.failurePayload`, the report row) inherit the redaction from here.
 */
fun <R : TrailblazeToolResult> R.withAuthoredFailureContent(authored: TrailblazeTool, memory: AgentMemory): R {
  val identityRestored = withAuthoredCommandIdentity(authored)
  if (identityRestored !is TrailblazeToolResult.Error.ExceptionThrown) return identityRestored
  val scrubbedMessage = scrubSensitiveValues(identityRestored.errorMessage, memory)
  val scrubbedPayload = identityRestored.structuredPayload?.let { scrubSensitivePayload(it, memory) }
  if (scrubbedMessage == identityRestored.errorMessage && scrubbedPayload == identityRestored.structuredPayload) {
    return identityRestored
  }
  @Suppress("UNCHECKED_CAST")
  return identityRestored.copy(errorMessage = scrubbedMessage, structuredPayload = scrubbedPayload) as R
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
  if (memory.variables.isEmpty() && memory.args.isEmpty()) return rawPayload
  val scrubMemory = AgentMemory().apply {
    variables.putAll(memory.variables)
    // putArg (already-resolved values), not seedArgs: re-seeding would re-run token resolution.
    // argsForLogSafePayload masks any arg that laundered a sensitive memory value in via a
    // token-valued default back to its own {{args.name}} token — the args-namespace analog of
    // the sensitive-variables masking below. Everything else resolves in the persisted payload
    // just like the dispatch boundary resolved it.
    memory.argsForLogSafePayload().forEach { (name, value) -> putArg(name, value) }
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
    try {
      INTERPOLATION_JSON.decodeFromJsonElement(concreteSerializer, interpolated)
    } catch (decodeFailure: Exception) {
      if (decodeFailure is kotlin.coroutines.cancellation.CancellationException) throw decodeFailure
      // A whole-scalar {{args.x}} on an integer/boolean arg substitutes the arg's NATIVE value
      // into the tree; when the receiving Kotlin field is a String, the strict decode rejects
      // the unquoted scalar. The tree walk can't know the field type, so retry with every token
      // rendered as text — reachable only when the native-typed tree already failed, so working
      // native substitutions (matching field types) are unaffected.
      // If this also fails, the outer catch's pass-through diagnostics apply as usual.
      val textRendered = memory.interpolateVariablesInJson(tree, renderWholeScalarArgTokensAsText = true)
      INTERPOLATION_JSON.decodeFromJsonElement(concreteSerializer, textRendered)
    }
  }
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
  throw e
} catch (e: MalformedArgTokenException) {
  // A hard-error, not a round-trip incompatibility: an LLM-authored expression inside an
  // `{{args.x}}` token (tokens are dotted paths only, never expressions). The broad catch
  // below would otherwise swallow this identically to "tool can't round-trip" and dispatch
  // the tool as-authored with the broken token literally intact — defeating the whole point
  // of hard-erroring on a malformed token. Must stay ahead of `catch (e: Exception)`.
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
