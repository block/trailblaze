package xyz.block.trailblaze.config

import xyz.block.trailblaze.util.Console

/**
 * Shared batch-loading logic for YAML config files.
 *
 * Iterates over [yamlContents], calls [transform] for each entry, counts successes and failures,
 * and logs a summary warning when any entries fail to parse.
 *
 * @param yamlContents map of stripped filename to YAML content
 * @param loaderLabel human-readable label for log messages (e.g., "App target", "ToolSet")
 * @param transform produces a result from each entry; exceptions are caught and counted as failures
 */
inline fun <T : Any> loadAllYamlWithErrorHandling(
  yamlContents: Map<String, String>,
  loaderLabel: String,
  transform: (name: String, content: String) -> T,
): List<T> {
  var loaded = 0
  var failed = 0
  val results =
    yamlContents.mapNotNull { (name, content) ->
      try {
        transform(name, content).also { loaded++ }
      } catch (e: Exception) {
        Console.log("Warning: Failed to load $loaderLabel '$name': ${e.message}")
        failed++
        null
      }
    }
  if (failed > 0) {
    Console.log("$loaderLabel YAML loader: loaded $loaded, $failed failed (see warnings above)")
  }
  return results
}
