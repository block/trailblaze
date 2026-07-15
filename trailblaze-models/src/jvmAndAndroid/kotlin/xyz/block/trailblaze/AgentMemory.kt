package xyz.block.trailblaze

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.MalformedArgTokenException
import xyz.block.trailblaze.yaml.TrailArgTokens

/**
 * This class allows the trailblaze agent to remember data from the screen for reference later.
 * Values are kept in a map of the variable name to the value being remembered.
 *
 * Backed by [ConcurrentHashMap] so the host's shared instance — see
 * `TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml` — stays safe even if tool
 * execution is ever parallelized. Single-key reads/writes are atomic; `interpolateVariables`
 * does N independent gets, which is the safe pattern for a concurrent hash map.
 */
class AgentMemory {
  val variables: MutableMap<String, String> = ConcurrentHashMap()

  // Keys whose values must not appear in logs or scripting envelopes (e.g. passwords, PINs,
  // credit-card numbers). Downstream consumers stash PII through `rememberSensitive` and rely
  // on those keys being filtered out of the scripting envelope, the LLM context, and
  // `dumpMemory` traces.
  //
  // The marker is STICKY for the session: `delete` drops the value but keeps the marker, and a
  // plain `remember` of a marked key routes through `rememberSensitive`. `--secret` /
  // `rememberSensitive` is a session-lifetime redaction promise — no tool sequence may silently
  // revoke it (same rule `applyScriptedToolMemoryDelta` enforces for scripted tools). Only
  // `clear` — the whole-memory session reset — drops markers. Concurrent-set because `remember`
  // branches on membership while envelope filters read it (see class doc).
  private val _sensitiveKeys = ConcurrentHashMap.newKeySet<String>()
  val sensitiveKeys: Set<String> get() = _sensitiveKeys

  // Keys explicitly removed via `delete` this session. Concurrent-set because tool execution may
  // be parallelized (see class doc). Carried back to the host per-RPC so an explicit deletion of a
  // host-seeded key propagates as a removal — a key merely absent from the snapshot is preserved.
  private val _deletedKeys = ConcurrentHashMap.newKeySet<String>()
  val deletedKeys: Set<String> get() = _deletedKeys

  // Unknown tokens already warned about, so a poll loop re-resolving the same assertion doesn't
  // flood the log — one diagnostic per distinct token per instance (i.e. per session).
  private val warnedUnknownTokens = ConcurrentHashMap.newKeySet<String>()

  // The parameterized-trail `args.` namespace: declared, typed, bound-once-and-immutable per run
  // (see xyz.block.trailblaze.yaml.TrailArgConfig / TrailArgBinder). Distinct from [variables]
  // (imperative undeclared string memory) — args carry a native JSON type, so a whole-scalar
  // `{{args.count}}` token substitutes the native integer/boolean, not its string form. Seeded
  // once at run start via [seedArgs] and never mutated by tools.
  private val _args: MutableMap<String, JsonElement> = ConcurrentHashMap()
  val args: Map<String, JsonElement> get() = _args.toMap()

  // Deferred (array/object) arg tokens already warned about — once per distinct token per session.
  private val warnedDeferredArgTokens = ConcurrentHashMap.newKeySet<String>()

  // Arg names whose value captured a sensitive memory value (laundered in via a token-valued arg
  // like `--arg x='{{memory.pin}}'`). Sticky for the session, exactly like [_sensitiveKeys]: a
  // later `delete` of the source memory key drops the value that containment matching tests
  // against, but the arg's copy outlives that revocation — so taint is recorded when the value
  // lands ([seedArgs] / [putArg]) and is never recomputed away. Crosses the RPC boundary via
  // `RunYamlRequest.sensitiveArgNames` + [markArgSensitive], mirroring `sensitiveMemoryKeys` +
  // [markSensitive] (the device builds a fresh AgentMemory per RPC, so device-local stickiness
  // alone would not survive a mid-run delete).
  private val _sensitiveArgNames = ConcurrentHashMap.newKeySet<String>()
  val sensitiveArgNames: Set<String> get() = _sensitiveArgNames

  fun clear() {
    variables.clear()
    _sensitiveKeys.clear()
    _deletedKeys.clear()
    warnedUnknownTokens.clear()
    _args.clear()
    warnedDeferredArgTokens.clear()
    _sensitiveArgNames.clear()
  }

  fun has(key: String): Boolean = variables.containsKey(key)

  /**
   * Removes [key]'s value. The sensitivity marker deliberately survives: a later [remember] of
   * the same key still gets sensitive semantics, so `delete` + `remember` cannot re-log a
   * `--secret` value in cleartext or re-expose it to scripting envelopes.
   */
  fun delete(key: String) {
    variables.remove(key)
    _deletedKeys.add(key)
  }

  /**
   * Stores [key]=[value]. A key currently marked sensitive keeps sensitive semantics: the value
   * is redacted in the log line and stays excluded from scripting envelopes — a plain remember
   * can never downgrade a `--secret` key back to cleartext (see [rememberSensitive]).
   */
  fun remember(key: String, value: String) {
    if (key in _sensitiveKeys) {
      Console.log(
        "Remembering for current test: $key and value: [REDACTED] " +
          "(key already marked sensitive; redaction is sticky for the session)",
      )
      storeSensitive(key, value)
      return
    }
    Console.log("Remembering for current test: $key and value: $value")
    variables[key] = value
    _deletedKeys.remove(key)
  }

  /** Like [remember] but redacts the value in logs and excludes it from scripting envelopes. */
  fun rememberSensitive(key: String, value: String) {
    Console.log("Remembering for current test: $key and value: [REDACTED]")
    storeSensitive(key, value)
  }

  // Marker BEFORE value: a concurrent reader that snapshots `variables` and filters against
  // `sensitiveKeys` must never observe the value in the window before the marker lands.
  private fun storeSensitive(key: String, value: String) {
    _sensitiveKeys.add(key)
    variables[key] = value
    _deletedKeys.remove(key)
  }

  /**
   * Marks [key] sensitive without touching its value (which may already be in [variables], or
   * arrive later). Used when sensitivity marking crosses a process boundary separately from the
   * value itself — the RPC memory snapshot is a plain string map, so the host/device re-marks
   * keys from the sibling `sensitiveMemoryKeys` list after merging the snapshot. Quiet on
   * purpose: this runs per RPC round-trip, where [rememberSensitive]'s console line would spam.
   */
  fun markSensitive(key: String) {
    _sensitiveKeys.add(key)
  }

  /**
   * Seed this memory from the (YAML defaults → CLI seeds → CLI sensitive seeds) composition
   * used at trail start.
   *
   * Precedence (later tiers override on the same key):
   *
   *  1. `yamlDefaults` — the trail YAML's `config.memory:` block.
   *  2. `cliSeeds` — `--memory KEY=VAL` entries; override yaml on collision.
   *  3. `cliSensitiveSeeds` — `--secret KEY=VAL` entries; override both yaml and cli on
   *     collision AND are routed through [rememberSensitive] so values are redacted in
   *     logs and excluded from the scripting envelope.
   *
   * Returns the resolved NON-sensitive snapshot — exactly what
   * [xyz.block.trailblaze.logs.model.SessionStatus.Started.resolvedInitialMemory] should
   * carry. Sensitive keys appear only via [sensitiveKeys] (or the parallel
   * `Started.sensitiveMemoryKeys`), never with their values. That includes keys marked
   * sensitive BEFORE this call: a pre-marked key re-supplied through a non-sensitive tier is
   * stored with sensitive semantics and excluded from the returned snapshot.
   */
  fun seedFrom(
    yamlDefaults: Map<String, String>?,
    cliSeeds: Map<String, String>,
    cliSensitiveSeeds: Map<String, String>,
  ): Map<String, String> {
    val resolved = LinkedHashMap<String, String>()
    yamlDefaults?.let { resolved.putAll(it) }
    resolved.putAll(cliSeeds)
    // Sensitive seeds win on a same-key collision but are NOT included in the returned
    // resolved snapshot — they're applied to memory via rememberSensitive instead.
    val sensitiveKeysCollision = cliSensitiveSeeds.keys.intersect(resolved.keys)
    sensitiveKeysCollision.forEach { resolved.remove(it) }
    resolved.forEach { (key, value) -> remember(key, value) }
    cliSensitiveSeeds.forEach { (key, value) -> rememberSensitive(key, value) }
    // A key marked sensitive before this call may arrive again via a non-sensitive tier;
    // remember() stored it redacted, so it must not ride the returned snapshot into the
    // persisted session log either.
    resolved.keys.removeAll { it in _sensitiveKeys }
    return resolved
  }

  /**
   * Seed the `args.` namespace from a caller's already-bound, typed values (the output of
   * [xyz.block.trailblaze.yaml.TrailArgBinder]). Bound once at run start; tools never mutate args.
   *
   * A string arg value may itself carry memory tokens (a token-valued default like
   * `default: '{{memory.email}}'`) or sibling `args.` references, so after the raw values land,
   * string args are interpolated against the already-seeded memory until stable — call this
   * AFTER [seedFrom]. Non-string values (native integer/boolean, or deferred array/object) are
   * stored verbatim.
   */
  fun seedArgs(boundArgs: Map<String, JsonElement>) {
    boundArgs.forEach { (name, value) -> _args[name] = value }
    // Resolve tokens inside string arg values (memory tokens in a token-valued default, and any
    // sibling `args.` reference). A sibling reference can resolve to a value that ITSELF still
    // carries a token (subject: '{{args.recipient}}' where recipient defaults to
    // '{{memory.email}}'), and _args iteration is hash-ordered — so repeat until a full pass
    // changes nothing (bounded by the arg count: each pass must fully resolve at least one arg,
    // or it's a reference cycle, whose tokens are left literal like any unresolvable token).
    run {
      repeat(maxOf(1, boundArgs.size)) {
        var changed = false
        boundArgs.keys.forEach { name ->
          val current = _args[name]
          if (current is JsonPrimitive && current.isString) {
            val resolved = interpolateVariables(current.content)
            if (resolved != current.content) {
              _args[name] = JsonPrimitive(resolved)
              changed = true
            }
          }
        }
        if (!changed) return@run
      }
    }
    // A string arg still carrying a token that references a SEEDED sibling STRING arg can only
    // be a reference cycle (self- or mutual): anything acyclic resolved in the passes above, and
    // unknown / deferred-array-object tokens already warned through their own once-per-token
    // sinks during interpolation. The literal is left in place by design (same contract as any
    // unresolvable token) — but silently, it reads as a resolver bug, so say so once per arg.
    boundArgs.keys.forEach { name ->
      val current = _args[name]
      if (current is JsonPrimitive && current.isString && referencesSeededStringArg(current.content)) {
        Console.info(
          "[AgentMemory] ⚠️ Arg '$name' still references a sibling arg after seeding — " +
            "reference cycle; the literal token is left in place: ${current.content}",
        )
      }
    }
    // Record which args captured a sensitive value NOW, while the source value is still in
    // [variables] — see [_sensitiveArgNames] for why read-time recomputation alone is not enough.
    boundArgs.keys.forEach { name ->
      val current = _args[name] ?: return@forEach
      if (containsSensitiveValue(renderArgText(current))) _sensitiveArgNames.add(name)
    }
  }

  /** True when [text] contains an `args.` token whose top-level arg is seeded as a string primitive. */
  private fun referencesSeededStringArg(text: String): Boolean = TOKEN_PATTERNS.any { pattern ->
    pattern.findAll(text).any { match ->
      val body = match.groupValues[1]
      body.startsWith(TrailArgTokens.ARGS_PREFIX) &&
        (_args[TrailArgTokens.topLevelArgName(body)] as? JsonPrimitive)?.isString == true
    }
  }

  /** Set a single arg value directly (used by the RPC crossing that rehydrates args on-device). */
  fun putArg(name: String, value: JsonElement) {
    _args[name] = value
    // Rehydration-time taint: the RPC memory snapshot lands before args, so a sensitive value
    // still present taints here; one already deleted host-side arrives via [markArgSensitive].
    if (containsSensitiveValue(renderArgText(value))) _sensitiveArgNames.add(name)
  }

  /**
   * Marks [name]'s arg as having captured a sensitive value, without re-testing containment —
   * the args-side [markSensitive]. Used when taint crosses a process boundary separately from
   * the values (`RunYamlRequest.sensitiveArgNames` alongside the args snapshot).
   */
  fun markArgSensitive(name: String) {
    _sensitiveArgNames.add(name)
  }

  /**
   * The `args.` namespace rendered for LLM context surfaces (the per-step remembered-values
   * reminder), keyed by TOKEN SPELLING (`args.<name>`) so a literal `{{args.x}}` in an objective's
   * prompt text maps straight to its bound value — the same mechanism by which the LLM resolves
   * `{{memory.x}}` tokens in prompts today (prompt text is never interpolated; the reminder list
   * is the lookup table). Primitives render as their content; deferred array/object values as
   * their compact JSON. Sorted for deterministic prompt output.
   *
   * Args are non-sensitive by design (no `--secret-arg` yet), BUT a token-valued arg can resolve
   * a sensitive memory value into the namespace (`--arg x='{{memory.pin}}'`, or a default carrying
   * that token). Redaction is a session-lifetime promise no path may revoke, so any arg whose
   * value captured a sensitive value is excluded here — exactly how sensitive [variables] are
   * filtered from the same reminder. The sticky [_sensitiveArgNames] taint is the primary gate
   * (it survives a later `delete` of the source memory key); the live containment re-check backs
   * it up for a value that only BECOMES sensitive after seeding (a later `rememberSensitive` of
   * the same value). The value still resolves at the dispatch boundary, so a literal `{{args.x}}`
   * the LLM copies into tool args works as usual.
   */
  fun argsForLlmContext(): Map<String, String> = _args.entries
    .sortedBy { it.key }
    .filterNot { (name, value) -> name in _sensitiveArgNames || containsSensitiveValue(renderArgText(value)) }
    .associate { (name, value) -> "${TrailArgTokens.ARGS_PREFIX}$name" to renderArgText(value) }

  /**
   * The args map as safe to embed in PERSISTED log payloads: any arg whose value captured a
   * sensitive memory value (laundered in via a token-valued arg like `'{{memory.pin}}'`) is
   * replaced by its own literal token — mirroring how sensitive [variables] map to `{{key}}` in
   * the log-safe scrub. Every other arg passes through verbatim, already resolved. Same two-layer
   * gate as [argsForLlmContext]: sticky taint first, live containment as backup.
   */
  fun argsForLogSafePayload(): Map<String, JsonElement> = _args.entries.associate { (name, value) ->
    name to if (name in _sensitiveArgNames || containsSensitiveValue(renderArgText(value))) {
      JsonPrimitive("{{${TrailArgTokens.ARGS_PREFIX}$name}}")
    } else {
      value
    }
  }

  private fun renderArgText(value: JsonElement): String =
    if (value is JsonPrimitive) value.content else value.toString()

  /**
   * True when [text] contains any sensitive value currently in [variables]. Value-containment
   * matching — the same coarseness as the free-form log scrub (`scrubSensitiveValues`): a very
   * short sensitive value can over-match, but over-hiding a value from an LLM/log surface is the
   * safe failure direction.
   */
  private fun containsSensitiveValue(text: String): Boolean = _sensitiveKeys.any { key ->
    val value = variables[key]
    !value.isNullOrEmpty() && text.contains(value)
  }

  /**
   * Replaces `${varName}` or `{{varName}}` tokens in [input] with their remembered values.
   *
   * `{{memory.varName}}` / `${memory.varName}` are the scope-qualified spelling of the same
   * lookup: the `memory.` prefix is stripped and the bare key is resolved from the same store,
   * so `{{memory.x}}` and `{{x}}` are interchangeable for every key. Bare tokens remain fully
   * supported — they are the grammar existing recordings and LLM-authored tokens use. See
   * [lookupVariable] for the edge case of a key literally named `memory.foo`.
   *
   * Single-pass per pattern: a remembered value that itself contains a token (e.g. `a={{b}}`)
   * is NOT recursively resolved — the resolved string is returned as-is. Unknown tokens are
   * left in place as literals, so a typo'd token surfaces as the visible `{{typo}}` in the
   * typed text / assertion instead of silently blanking; a diagnostic is logged once per
   * distinct token per [AgentMemory] instance. Set `TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS=1`
   * (read per call) to restore the legacy resolve-to-empty-string behavior.
   */
  fun interpolateVariables(input: String): String = interpolateVariables(input, blankUnknownTokens = blankUnknownTokensRequestedViaEnv())

  /**
   * [interpolateVariables] with the unknown-token behavior injected — the env read stays out
   * of this core so tests can pin both modes without process-global env manipulation.
   *
   * [warnUnknownTokens] gates the once-per-token diagnostic. Callers that re-interpolate an
   * already-processed payload purely to remap values (e.g. the log-safe scrub) pass `false` so
   * the diagnostic fires exactly once — from the real dispatch pass — instead of once per re-scrub.
   */
  internal fun interpolateVariables(
    input: String,
    blankUnknownTokens: Boolean,
    warnUnknownTokens: Boolean = true,
  ): String {
    var result = input
    for (pattern in TOKEN_PATTERNS) {
      pattern.findAll(result).forEach { matchResult ->
        val variableName = matchResult.groupValues[1]
        if (TrailArgTokens.isArgsToken(variableName)) {
          // A deferred (array/object) arg is KNOWN, just not yet substitutable — it must never
          // fall into the unknown-token sink below, which would let the blank-kill-switch turn a
          // declared value into a silently-wrong empty string. Only a genuinely unknown arg does.
          when (val resolution = resolveArgToken(variableName)) {
            is ArgTokenResolution.Resolved ->
              result = result.replace(matchResult.value, resolution.text)
            ArgTokenResolution.Deferred -> Unit
            ArgTokenResolution.Unknown ->
              result = leaveOrBlankUnknownToken(result, matchResult.value, variableName, blankUnknownTokens, warnUnknownTokens)
          }
        } else {
          val variableValue = lookupVariable(variableName)
          if (variableValue != null) {
            result = result.replace(matchResult.value, variableValue)
          } else {
            result = leaveOrBlankUnknownToken(result, matchResult.value, variableName, blankUnknownTokens, warnUnknownTokens)
          }
        }
      }
    }
    return result
  }

  private fun leaveOrBlankUnknownToken(
    result: String,
    rawToken: String,
    variableName: String,
    blankUnknownTokens: Boolean,
    warnUnknownTokens: Boolean,
  ): String {
    if (warnUnknownTokens) {
      warnUnknownTokenOnce(rawToken, variableName, blankUnknownTokens)
    }
    return if (blankUnknownTokens) result.replace(rawToken, "") else result
  }

  /** The outcome of resolving an `args.` token body to its embedded-string form. */
  private sealed interface ArgTokenResolution {
    /** [text] is the native scalar rendered as text. */
    data class Resolved(val text: String) : ArgTokenResolution

    /** The top-level arg (or an intermediate dotted-path segment) has no seeded value. */
    object Unknown : ArgTokenResolution

    /** The arg is seeded but its value is an array/object — substitution is not yet executed. */
    object Deferred : ArgTokenResolution
  }

  /**
   * Resolve an `args.` token (already known to start with `args.`) to its [ArgTokenResolution].
   *
   * Hard-errors (throws) on a token that is not a well-formed dotted path — tokens are dotted
   * paths only, never expressions. This is the runtime half of the check-time grammar gate: since
   * tokens are LLM-writable, an expression-bearing token would be evaluating model output.
   */
  private fun resolveArgToken(body: String): ArgTokenResolution {
    if (!TrailArgTokens.isValidDottedPath(body)) {
      throw MalformedArgTokenException(TrailArgTokens.malformedTokenMessage(body))
    }
    val element = lookupArg(body.removePrefix(TrailArgTokens.ARGS_PREFIX)) ?: return ArgTokenResolution.Unknown
    return when {
      element is JsonPrimitive -> ArgTokenResolution.Resolved(element.content)
      else -> {
        warnDeferredArgOnce(body)
        ArgTokenResolution.Deferred
      }
    }
  }

  /**
   * Resolve the top-level arg name and any dotted sub-path (`reply_to.email`) to its stored value,
   * descending through [JsonObject]s. [path] has the `args.` prefix already stripped. Returns null
   * when the top-level arg or any intermediate field is absent.
   */
  private fun lookupArg(path: String): JsonElement? {
    val segments = path.split('.')
    var current: JsonElement = _args[segments.first()] ?: return null
    for (segment in segments.drop(1)) {
      current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
  }

  // Console.info, not log, for the unresolved-token family (deferred / unknown / cycle): these
  // are the only field signal when a user asks "why didn't my {{token}} resolve?", and CLI quiet
  // mode (a default `trailblaze run`) suppresses Console.log. Once-per-token throttled, so
  // surviving quiet mode can't flood a poll loop. Same rationale as ToolMemoryInterpolation's
  // round-trip diagnostic.
  private fun warnDeferredArgOnce(token: String) {
    if (!warnedDeferredArgTokens.add(token)) return
    Console.info(
      "[AgentMemory] ⚠️ Arg token {{$token}} resolves to an array/object value — array/object " +
        "substitution is declared-but-not-yet-executed; the literal token is left in place.",
    )
  }

  private fun warnUnknownTokenOnce(token: String, variableName: String, blanked: Boolean) {
    if (!warnedUnknownTokens.add(token)) return
    val resolution = if (blanked) {
      "resolving to \"\" ($BLANK_UNKNOWN_TOKENS_ENV_VAR is set)"
    } else {
      "leaving the literal in place — set $BLANK_UNKNOWN_TOKENS_ENV_VAR=1 to restore the legacy blank substitution"
    }
    Console.info(
      "[AgentMemory] ⚠️ Unknown memory token $token — no remembered value for '$variableName'; " +
        "$resolution. Remembered keys: ${variables.keys.sorted()}",
    )
  }

  /**
   * Resolves a token body to its remembered value, honoring the `memory.` scope prefix.
   *
   * For a prefixed token `memory.foo`, the stripped key `foo` wins; the literal key
   * `memory.foo` is consulted only when `foo` is absent (a key can legitimately contain dots —
   * nothing stops `remember("memory.foo", …)`). When BOTH exist the collision is logged once
   * per interpolation site so the shadowed literal key doesn't fail silently.
   */
  private fun lookupVariable(variableName: String): String? {
    if (!variableName.startsWith(MEMORY_TOKEN_PREFIX)) return variables[variableName]
    val strippedKey = variableName.removePrefix(MEMORY_TOKEN_PREFIX)
    val strippedValue = variables[strippedKey]
    val literalValue = variables[variableName]
    if (strippedValue != null && literalValue != null) {
      Console.log(
        "[AgentMemory] Token '$variableName' matches both remembered key '$strippedKey' " +
          "(via the memory. prefix) and a key literally named '$variableName'; " +
          "using '$strippedKey' — the prefix-stripped lookup wins.",
      )
    }
    return strippedValue ?: literalValue
  }

  /**
   * Recursively resolves `${key}`/`{{key}}` tokens in every string scalar of [element] via
   * [interpolateVariables]. No-op when [variables] is empty — consistent with the unknown-token
   * leave-literal behavior (every token would be unknown and survive unchanged; only the
   * per-token diagnostic is skipped). Idempotent on a tree with no remaining tokens, so it is
   * safe to call on args already resolved upstream (the AI path interpolates before the tool
   * is built). Non-string scalars pass through unchanged. Pass [warnUnknownTokens] = false to
   * suppress the once-per-token diagnostic when re-interpolating an already-processed payload
   * (the log-safe scrub does this so the diagnostic isn't re-emitted per dispatch).
   *
   * [renderWholeScalarArgTokensAsText] disables the native-typed whole-scalar substitution and
   * renders every resolvable token as text. The tree itself can't know the Kotlin field type a
   * scalar decodes into — the class-serializer dispatch boundary retries with this when the
   * native-typed tree fails to decode (an integer arg whole-scalar in a String field).
   */
  fun interpolateVariablesInJson(
    element: JsonElement,
    warnUnknownTokens: Boolean = true,
    renderWholeScalarArgTokensAsText: Boolean = false,
  ): JsonElement = when {
    variables.isEmpty() && _args.isEmpty() -> element
    element is JsonPrimitive && element.isString ->
      interpolateStringPrimitive(element, warnUnknownTokens, renderWholeScalarArgTokensAsText)
    element is JsonObject -> JsonObject(
      element.mapValues { interpolateVariablesInJson(it.value, warnUnknownTokens, renderWholeScalarArgTokensAsText) },
    )
    element is JsonArray -> JsonArray(
      element.map { interpolateVariablesInJson(it, warnUnknownTokens, renderWholeScalarArgTokensAsText) },
    )
    else -> element
  }

  /**
   * A string primitive whose ENTIRE content is a single `args.` token resolves to the arg's
   * NATIVE typed value (a whole-scalar `{{args.retries}}` on an `integer` arg becomes the JSON
   * number `3`, not the string `"3"`). Any other string — memory tokens, bare tokens, or an
   * `args.` token embedded in surrounding text — resolves through [interpolateVariables], which
   * renders every scalar as text (embedded substitution). Deferred (array/object) and unknown
   * args fall back to the embedded/leave-literal path too.
   */
  private fun interpolateStringPrimitive(
    element: JsonPrimitive,
    warnUnknownTokens: Boolean,
    renderWholeScalarArgTokensAsText: Boolean,
  ): JsonElement {
    val wholeArgBody = if (renderWholeScalarArgTokensAsText) null else wholeScalarArgTokenBody(element.content)
    if (wholeArgBody != null) {
      if (!TrailArgTokens.isValidDottedPath(wholeArgBody)) {
        throw MalformedArgTokenException(TrailArgTokens.malformedTokenMessage(wholeArgBody))
      }
      val resolved = lookupArg(wholeArgBody.removePrefix(TrailArgTokens.ARGS_PREFIX))
      if (resolved is JsonPrimitive) return resolved
      // Unknown / deferred → fall through to the string path, which leaves the literal token in
      // place either way (deferred never blanks, even under the kill-switch) and logs once via
      // resolveArgToken.
    }
    return JsonPrimitive(interpolateVariables(element.content, blankUnknownTokensRequestedViaEnv(), warnUnknownTokens))
  }
}

/** Token spellings recognized by interpolation: `${varName}` and `{{varName}}`. */
private val TOKEN_PATTERNS = listOf(
  Regex("\\$\\{([^}]+)\\}"),
  Regex("\\{\\{([^}]+)\\}\\}"),
)

/** Anchored single-token spellings for [wholeScalarArgTokenBody] — hoisted like [TOKEN_PATTERNS]
 *  (this runs per string scalar on the dispatch path; no per-call recompilation). */
private val WHOLE_ARG_TOKEN_CURLY = Regex("""^\{\{(args\.[^}]*)\}\}$""")
private val WHOLE_ARG_TOKEN_DOLLAR = Regex("""^\$\{(args\.[^}]*)\}$""")

/** If [content] is exactly one `${args.…}` / `{{args.…}}` token (no surrounding text), its body. */
private fun wholeScalarArgTokenBody(content: String): String? {
  val match = WHOLE_ARG_TOKEN_CURLY.matchEntire(content) ?: WHOLE_ARG_TOKEN_DOLLAR.matchEntire(content)
  return match?.groupValues?.get(1)
}

/**
 * Kill-switch (read per call): restore the silent resolve-to-empty-string behavior for unknown
 * `${var}`/`{{var}}` tokens in [AgentMemory.interpolateVariables]. Value `1` or `true`
 * (case-insensitive) enables.
 */
private const val BLANK_UNKNOWN_TOKENS_ENV_VAR = "TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS"

private fun blankUnknownTokensRequestedViaEnv(): Boolean {
  val value = System.getenv(BLANK_UNKNOWN_TOKENS_ENV_VAR) ?: return false
  return value == "1" || value.equals("true", ignoreCase = true)
}

/**
 * Scope prefix that qualifies a token as a memory lookup: `{{memory.x}}` resolves the remembered
 * key `x`. The prefix exists only in token syntax — remembered keys themselves stay unprefixed
 * (`remember("x", …)`), and the store is shared with bare `{{x}}` tokens. Mirrored by the
 * TypeScript SDK's `ctx.memory.interpolate` (memory.ts); both sides must change together.
 */
private const val MEMORY_TOKEN_PREFIX = "memory."
