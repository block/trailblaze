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
  private val _sensitiveKeys = mutableSetOf<String>()
  val sensitiveKeys: Set<String> get() = _sensitiveKeys

  // Keys explicitly removed via `delete` this session. Concurrent-set because tool execution may
  // be parallelized (see class doc). Carried back to the host per-RPC so an explicit deletion of a
  // host-seeded key propagates as a removal — a key merely absent from the snapshot is preserved.
  private val _deletedKeys = ConcurrentHashMap.newKeySet<String>()
  val deletedKeys: Set<String> get() = _deletedKeys

  fun clear() {
    variables.clear()
    _sensitiveKeys.clear()
    _deletedKeys.clear()
  }

  fun has(key: String): Boolean = variables.containsKey(key)

  fun delete(key: String) {
    variables.remove(key)
    _sensitiveKeys.remove(key)
    _deletedKeys.add(key)
  }

  fun remember(key: String, value: String) {
    Console.log("Remembering for current test: $key and value: $value")
    variables[key] = value
    _deletedKeys.remove(key)
  }

  /** Like [remember] but redacts the value in logs and excludes it from scripting envelopes. */
  fun rememberSensitive(key: String, value: String) {
    Console.log("Remembering for current test: $key and value: [REDACTED]")
    variables[key] = value
    _sensitiveKeys.add(key)
    _deletedKeys.remove(key)
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
   * `Started.sensitiveMemoryKeys`), never with their values.
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
    return resolved
  }

  /**
   * Replaces `${varName}` or `{{varName}}` tokens in [input] with their remembered values.
   *
   * Single-pass per pattern: a remembered value that itself contains a token (e.g. `a={{b}}`)
   * is NOT recursively resolved — the resolved string is returned as-is. Unknown tokens
   * resolve to the empty string.
   */
  fun interpolateVariables(input: String): String {
    var result = input
    // Support both ${varName} and {{varName}}
    val patterns = listOf(
      Regex("\\$\\{([^}]+)\\}"),
      Regex("\\{\\{([^}]+)\\}\\}"),
    )
    for (pattern in patterns) {
      pattern.findAll(result).forEach { matchResult ->
        val variableName = matchResult.groupValues[1]
        val variableValue = variables[variableName] ?: ""
        result = result.replace(matchResult.value, variableValue)
      }
    }
    return result
  }

  /**
   * Recursively resolves `${key}`/`{{key}}` tokens in every string scalar of [element] via
   * [interpolateVariables]. No-op when [variables] is empty; idempotent on a tree with no
   * remaining tokens, so it is safe to call on args already resolved upstream (the AI path
   * interpolates before the tool is built). Non-string scalars pass through unchanged.
   */
  fun interpolateVariablesInJson(element: JsonElement): JsonElement = when {
    variables.isEmpty() -> element
    element is JsonPrimitive && element.isString -> JsonPrimitive(interpolateVariables(element.content))
    element is JsonObject -> JsonObject(element.mapValues { interpolateVariablesInJson(it.value) })
    element is JsonArray -> JsonArray(element.map { interpolateVariablesInJson(it) })
    else -> element
  }
}
