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
 * ### Runtime variables (session memory)
 *
 * Any `{{VAR}}` that cannot be resolved is left as a `{{var}}` literal — no error is thrown.
 * The trail runner substitutes these at execution time via
 * [xyz.block.trailblaze.AgentMemory.interpolateVariables], which understands both `${var}` and
 * `{{var}}` syntax. This covers any variable populated at runtime (e.g. `rememberWithAi`,
 * custom scripted tools) without requiring a per-tool allowlist in the resolver.
 *
 * The `TRAILBLAZE_DEFERRED_VARIABLES` env var (comma-separated) names variables that should be
 * excluded from env-var lookup even when set in the process environment. Use this when a variable
 * name collides with a real env var that must not be substituted at load time.
 *
 * Example:
 * ```bash
 * export TRAILBLAZE_DEFERRED_VARIABLES="HOME"  # prevent {{HOME}} from being env-expanded
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
   * Variables that cannot be resolved (not in [additionalValues], not a built-in, not an env var)
   * are left as `{{var}}` literals for the trail runner to substitute at execution time.
   *
   * @param yaml the raw YAML content that may contain `{{VARIABLE}}` placeholders
   * @param trailFile optional trail file reference (reserved for future path-relative variables)
   * @param additionalValues default values for template variables; environment variables override these
   * @param deferredVariables variables to skip env-var lookup for, even if set in the environment;
   *        their `{{var}}` placeholders pass through unconditionally; defaults to [deferredVariablesFromEnv]
   * @return the YAML with all resolvable template variables substituted; unresolvable ones unchanged
   */
  fun resolve(
    yaml: String,
    trailFile: File? = null,
    additionalValues: Map<String, String> = emptyMap(),
    deferredVariables: Set<String> = deferredVariablesFromEnv(),
  ): String {
    val requiredVariables = TemplatingUtil.getRequiredTemplateVariables(yaml)
    if (requiredVariables.isEmpty()) return yaml

    val resolvable = requiredVariables - deferredVariables

    val values = buildMap<String, String> {
      // 1. Caller-supplied defaults (lowest priority)
      putAll(additionalValues.filterKeys { it in resolvable })
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

    // Any variable that remains unresolved passes through as a {{var}} literal.
    val effectiveDeferredVariables = deferredVariables + (requiredVariables - values.keys)
    return TemplatingUtil.replaceVariables(yaml, values, deferred = effectiveDeferredVariables)
  }
}
