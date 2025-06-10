package xyz.block.trailblaze.util

object TemplatingUtil {
  /**
   * Reads a resource file from the classpath and returns its content as a string.
   */
  fun getResourceAsText(resourcePath: String): String? {
    println("Reading resource: $resourcePath")
    return object {}.javaClass.classLoader.getResource(resourcePath)?.readText()
  }

  /**
   * Reads a resource file from the classpath, replaces variables in the template with values from the map,
   */
  fun renderTemplate(template: String, values: Map<String, String> = mapOf()): String = replaceVariables(template, values)

  /**
   * Replaces `${key}` and `$key` in the template string with the corresponding values from the map.
   */
  fun replaceVariables(template: String, values: Map<String, String>): String {
    // Ensure we have all required template variables
    assertRequiredTemplateVariablesAvailable(template, values)

    var result = template
    values.forEach { (key, value) ->
      result = result.replace("\${$key}", value) // Handles ${key}
      result = result.replace("$$key", value) // Handles $key
    }
    return result
  }

  /**
   * Ensures we can satisfy all required template variables in the template.
   */
  fun assertRequiredTemplateVariablesAvailable(template: String, values: Map<String, String>) {
    val requiredVariables = getRequiredTemplateVariables(template)
    val availableKeys = values.keys
    val missingRequiredKeys = requiredVariables.subtract(availableKeys)
    if (missingRequiredKeys.isNotEmpty()) {
      error(
        buildString {
          appendLine("For template:\n$template")
          appendLine("---")
          appendLine("Missing required template variables: ${missingRequiredKeys.joinToString(", ")}")
        },
      )
    }

    val unusedKeys = availableKeys.subtract(requiredVariables)
    if (unusedKeys.isNotEmpty()) {
      println(
        buildString {
          appendLine("For template: $template")
          appendLine("---")
          appendLine("WARNING: Unused template variables: ${unusedKeys.joinToString(", ")}")
        },
      )
    }
  }

  /**
   * Extracts all required template variables from the template string.
   */
  fun getRequiredTemplateVariables(template: String): Set<String> {
    val regex = Regex("""\$\{([a-zA-Z0-9_]+)\}|\$([a-zA-Z0-9_]+)""")
    return regex.findAll(template)
      .mapNotNull { it.groups[1]?.value ?: it.groups[2]?.value }
      .toSet()
  }
}
