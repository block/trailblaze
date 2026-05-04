package xyz.block.trailblaze.util

object TemplatingUtil {
  /**
   * Reads a resource file from the classpath and returns its content as a string.
   */
  fun getResourceAsText(resourcePath: String): String? {
    Console.log("Reading resource: $resourcePath")
    return object {}.javaClass.classLoader.getResource(resourcePath)?.readText()
  }

  /**
   * Determine if a resource exists
   */
  fun doesResourceExist(resourcePath: String): Boolean = object {}.javaClass.classLoader.getResource(resourcePath) != null

  /**
   * Reads a resource file from the classpath, replaces variables in the template with values from the map,
   */
  fun renderTemplate(template: String, values: Map<String, String> = mapOf()): String = replaceVariables(template, values)

  /**
   * Replaces `{{key}}` and `{{ key }}` in the template string with the corresponding values from the map.
   *
   * Variables in [deferred] are left as `{{var}}` literals in the output and are treated as
   * satisfied by [assertRequiredTemplateVariablesAvailable]. This lets a load-time resolver
   * defer session-memory variables (populated by host-local tools at exec time) for the runtime
   * to substitute via [xyz.block.trailblaze.AgentMemory.interpolateVariables].
   *
   * `@JvmOverloads` so externally-compiled Java consumers of the previous 2-arg signature keep
   * resolving without `NoSuchMethodError`.
   */
  @JvmOverloads
  fun replaceVariables(template: String, values: Map<String, String>, deferred: Set<String> = emptySet()): String {
    // Ensure we have all required template variables (deferred ones count as satisfied)
    assertRequiredTemplateVariablesAvailable(template, values, deferred)

    var result = template
    values.forEach { (key, value) ->
      // A key explicitly in the deferred set is never substituted at this layer, even if the
      // caller also placed it in `values`. Defensive — TrailYamlTemplateResolver builds the two
      // sets disjoint by construction, but other callers may not.
      if (key in deferred) return@forEach
      // Handles {{key}} and {{ key }}
      // Use literal replacement to avoid treating $ and \ as special characters
      result = result.replace(
        """\{\{\s*$key\s*\}\}""".toRegex(),
        value.replace("\\", "\\\\").replace("$", "\\$"),
      )
    }
    return result
  }

  /**
   * Ensures we can satisfy all required template variables in the template.
   *
   * Variables in [deferred] count as satisfied — useful when a downstream runtime (e.g.
   * [xyz.block.trailblaze.AgentMemory.interpolateVariables]) will substitute them later.
   *
   * `@JvmOverloads` preserves the previous 2-arg signature for externally-compiled Java consumers.
   */
  @JvmOverloads
  fun assertRequiredTemplateVariablesAvailable(template: String, values: Map<String, String>, deferred: Set<String> = emptySet()) {
    val requiredVariables = getRequiredTemplateVariables(template)
    val availableKeys = values.keys + deferred
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

    // Only `values` keys can be "unused" — deferred keys are conditional and never required to be in `values`.
    val unusedKeys = values.keys.subtract(requiredVariables)
    if (unusedKeys.isNotEmpty()) {
      Console.log(
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
    val regex = Regex("""\{\{\s*([a-zA-Z0-9_]+)\s*\}\}""")
    return regex.findAll(template)
      .mapNotNull { it.groups[1]?.value }
      .toSet()
  }
}
