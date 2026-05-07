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

  /**
   * Recursively discovers all files matching [suffix] anywhere under [directoryPath] and returns
   * their contents as a map of `relativePath -> content`. The relative path is rooted at
   * [directoryPath] using `/` separators and **preserves [suffix]** so callers can derive both
   * the leaf name and any intermediate directories (e.g. `<pack>/tools/foo.tool.yaml`).
   *
   * Mirrors [ClasspathResourceDiscovery.discoverAndLoadRecursive] semantics so the two sources
   * stay interchangeable for hierarchical layouts (pack-bundled tools, pack manifests).
   *
   * Default implementation returns the flat (non-recursive) result so existing implementations
   * keep compiling — concrete sources that can recurse override this.
   */
  fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> =
    discoverAndLoad(directoryPath, suffix).mapKeys { (key, _) -> "$key$suffix" }
}
