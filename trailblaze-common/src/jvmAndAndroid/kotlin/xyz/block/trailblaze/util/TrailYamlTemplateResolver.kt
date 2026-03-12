package xyz.block.trailblaze.util

import java.io.File

/**
 * Resolves template variables in trail YAML content.
 *
 * Trail files can use `{{CWD}}` to reference the current working directory, making
 * file:// URLs portable across machines and CI environments. For example:
 *
 * ```yaml
 * - step: Navigate to file://{{CWD}}/sample-app/index.html
 * ```
 *
 * Supported variables:
 * - `CWD` — the current working directory
 */
object TrailYamlTemplateResolver {

  /**
   * Resolves template variables in the given YAML content.
   *
   * @param yaml the raw YAML content that may contain `{{CWD}}` and other template variables
   * @param trailFile optional trail file reference (available for future variables)
   * @param additionalValues extra template variables to provide beyond the built-in ones
   * @return the YAML with all template variables resolved
   */
  fun resolve(
    yaml: String,
    trailFile: File? = null,
    additionalValues: Map<String, String> = emptyMap(),
  ): String {
    val requiredVariables = TemplatingUtil.getRequiredTemplateVariables(yaml)
    if (requiredVariables.isEmpty()) return yaml

    val values = buildMap {
      putAll(additionalValues)
      if ("CWD" in requiredVariables && "CWD" !in this) {
        put("CWD", System.getProperty("user.dir"))
      }
    }

    return TemplatingUtil.replaceVariables(yaml, values)
  }
}
