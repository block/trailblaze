package xyz.block.trailblaze

import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap

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

  // Keys whose values must not appear in logs or scripting envelopes (e.g. passwords).
  private val _sensitiveKeys = mutableSetOf<String>()
  val sensitiveKeys: Set<String> get() = _sensitiveKeys

  fun clear() {
    variables.clear()
    _sensitiveKeys.clear()
  }

  fun remember(key: String, value: String) {
    Console.log("Remembering for current test: $key and value: $value")
    variables[key] = value
  }

  /** Like [remember] but redacts the value in logs and excludes it from scripting envelopes. */
  fun rememberSensitive(key: String, value: String) {
    Console.log("Remembering for current test: $key and value: [REDACTED]")
    variables[key] = value
    _sensitiveKeys.add(key)
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
}
