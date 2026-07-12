package xyz.block.trailblaze

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.util.Console

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

  fun clear() {
    variables.clear()
    _sensitiveKeys.clear()
    _deletedKeys.clear()
    warnedUnknownTokens.clear()
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
    // Support both ${varName} and {{varName}}
    val patterns = listOf(
      Regex("\\$\\{([^}]+)\\}"),
      Regex("\\{\\{([^}]+)\\}\\}"),
    )
    for (pattern in patterns) {
      pattern.findAll(result).forEach { matchResult ->
        val variableName = matchResult.groupValues[1]
        val variableValue = lookupVariable(variableName)
        if (variableValue != null) {
          result = result.replace(matchResult.value, variableValue)
        } else {
          if (warnUnknownTokens) {
            warnUnknownTokenOnce(matchResult.value, variableName, blankUnknownTokens)
          }
          if (blankUnknownTokens) {
            result = result.replace(matchResult.value, "")
          }
        }
      }
    }
    return result
  }

  private fun warnUnknownTokenOnce(token: String, variableName: String, blanked: Boolean) {
    if (!warnedUnknownTokens.add(token)) return
    val resolution = if (blanked) {
      "resolving to \"\" ($BLANK_UNKNOWN_TOKENS_ENV_VAR is set)"
    } else {
      "leaving the literal in place — set $BLANK_UNKNOWN_TOKENS_ENV_VAR=1 to restore the legacy blank substitution"
    }
    Console.log(
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
   */
  fun interpolateVariablesInJson(element: JsonElement, warnUnknownTokens: Boolean = true): JsonElement = when {
    variables.isEmpty() -> element
    element is JsonPrimitive && element.isString ->
      JsonPrimitive(interpolateVariables(element.content, blankUnknownTokensRequestedViaEnv(), warnUnknownTokens))
    element is JsonObject -> JsonObject(element.mapValues { interpolateVariablesInJson(it.value, warnUnknownTokens) })
    element is JsonArray -> JsonArray(element.map { interpolateVariablesInJson(it, warnUnknownTokens) })
    else -> element
  }
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
