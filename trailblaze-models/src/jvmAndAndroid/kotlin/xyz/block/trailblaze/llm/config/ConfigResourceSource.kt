package xyz.block.trailblaze.llm.config

/**
 * Abstraction for discovering and loading YAML configuration files from a resource source.
 *
 * On JVM desktop, [ClasspathConfigResourceSource] scans the classpath. On Android, callers provide
 * an implementation backed by `AssetManager`.
 */
fun interface ConfigResourceSource {
  /**
   * Discovers all files matching [suffix] under [directoryPath] and returns their contents as a map
   * of `strippedFilename -> content` (filename with [suffix] removed).
   */
  fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String>
}
