package xyz.block.trailblaze.util

import java.io.File

/**
 * Resolves template variables in trail YAML content.
 *
 * Trail files can use `{{VARIABLE}}` to inject values at runtime. For example:
 *
 * ```yaml
 * - step: Navigate to {{BASE_URL}}/dashboard and sign in.
 * ```
 *
 * Resolution priority (highest wins):
 * 1. Environment variables — `BASE_URL=http://localhost:4200` overrides any default
 * 2. Built-in variables — `CWD` is always available
 * 3. Caller-supplied `additionalValues` — used as defaults
 *
 * Supported built-in variables:
 * - `CWD` — the current working directory
 *
 * Any `{{VAR}}` whose name matches an environment variable is automatically resolved.
 * This lets trail files be environment-agnostic: set `BASE_URL` to point at a PR branch
 * dev server; leave it unset to fall back to a caller-provided default (e.g., staging).
 *
 * ### Deferred variables (runtime session memory)
 *
 * Variables in [deferredVariablesFromEnv] (or a caller-supplied `deferredVariables` set) are
 * intentionally NOT resolved at this layer — their `{{var}}` placeholders pass through to the
 * runner. They are populated at execution time by host-local trail tools that write to session
 * memory and substituted at runtime by [xyz.block.trailblaze.AgentMemory.interpolateVariables],
 * which understands both `${var}` and `{{var}}` syntax. This unblocks recording-verification
 * for trails whose values can only be known per-run.
 *
 * The default deferred set is read from the `TRAILBLAZE_DEFERRED_VARIABLES` environment
 * variable (comma-separated). Empty when unset. Callers may also pass an explicit set.
 *
 * Example:
 * ```bash
 * # In a CI script that drives `trailblaze trail …`
 * export TRAILBLAZE_DEFERRED_VARIABLES="user_email,user_pin"
 * ```
 */
object TrailYamlTemplateResolver {

  /** Env var name for configuring the default deferred-variable set. Comma-separated. */
  const val DEFERRED_VARIABLES_ENV_VAR: String = "TRAILBLAZE_DEFERRED_VARIABLES"

  /**
   * Reads the deferred-variable set from [DEFERRED_VARIABLES_ENV_VAR] on every call.
   *
   * Re-reads each call (vs caching) so test code that mutates the environment between runs sees
   * the updated value, and so there is no first-touch ordering hazard relative to when the
   * caller exports the env var.
   *
   * Pre-resolution leaves `{{var}}` placeholders for these names untouched; runtime substitution
   * handles them via [xyz.block.trailblaze.AgentMemory.interpolateVariables]. Configure via env
   * var rather than hard-coding so app-specific session-memory keys stay in the consumers that
   * own them, not in this framework.
   */
  fun deferredVariablesFromEnv(): Set<String> =
    System.getenv(DEFERRED_VARIABLES_ENV_VAR)
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.toSet()
      ?: emptySet()

  /**
   * Resolves template variables in the given YAML content.
   *
   * @param yaml the raw YAML content that may contain `{{VARIABLE}}` placeholders
   * @param trailFile optional trail file reference (reserved for future path-relative variables)
   * @param additionalValues default values for template variables; environment variables override these
   * @param deferredVariables variables to leave untouched at load time so the runtime can substitute
   *        them later via session memory; defaults to [deferredVariablesFromEnv]
   * @return the YAML with all non-deferred template variables resolved
   */
  fun resolve(
    yaml: String,
    trailFile: File? = null,
    additionalValues: Map<String, String> = emptyMap(),
    deferredVariables: Set<String> = deferredVariablesFromEnv(),
  ): String {
    val requiredVariables = TemplatingUtil.getRequiredTemplateVariables(yaml)
    if (requiredVariables.isEmpty()) return yaml

    // Only resolve what the runtime won't inject later.
    val resolvable = requiredVariables - deferredVariables

    val values = buildMap<String, String> {
      // 1. Caller-supplied defaults (lowest priority)
      putAll(additionalValues)
      // 2. Built-in: CWD
      if ("CWD" in resolvable && "CWD" !in this) {
        put("CWD", System.getProperty("user.dir"))
      }
      // 3. Environment variables override defaults
      for (varName in resolvable) {
        val envValue = System.getenv(varName)
        if (envValue != null) put(varName, envValue)
      }
    }

    return TemplatingUtil.replaceVariables(yaml, values, deferred = deferredVariables)
  }
}
