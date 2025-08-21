package xyz.block.trailblaze

/**
 * This class allows the trailblaze agent to remember data from the screen for reference later.
 * Values are kept in a map of the variable name to the value being remembered.
 */
class AgentMemory {
  // Variable store for remember commands
  val variables = mutableMapOf<String, String>()

  fun clear() {
    variables.clear()
  }

  fun remember(key: String, value: String) {
    println("Remembering for current test: $key and value: $value")
    variables[key] = value
  }

  /**
   * Interpolates variables in a string. Replaces ${varName} or {{varName}} with variable values.
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
