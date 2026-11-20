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
   * the leaf name and any intermediate directories (e.g. `<trailmap>/tools/foo.tool.yaml`).
   *
   * Mirrors [ClasspathResourceDiscovery.discoverAndLoadRecursive] semantics so the two sources
   * stay interchangeable for hierarchical layouts (trailmap-bundled tools, trailmap manifests).
   *
   * Default implementation returns the flat (non-recursive) result so existing implementations
   * keep compiling — concrete sources that can recurse override this.
   */
  fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> =
    discoverAndLoad(directoryPath, suffix).mapKeys { (key, _) -> "$key$suffix" }

  /**
   * Reads the single text resource at [path] (full resource path, e.g.
   * `trails/config/trailmaps/<id>/waypoints/home.waypoint.yaml`), or returns null if it doesn't
   * exist. Lets a caller that already knows a file's path read it without enumerating a directory.
   *
   * Default returns null so existing implementations keep compiling — sources that can read a
   * single file override this.
   */
  fun readResource(path: String): String? = null

  /**
   * Whether a resource exists at [path]. Override when presence can be checked without reading the
   * file — a scripted tool's `.bundle.js` can be a large bundle.
   */
  fun resourceExists(path: String): Boolean = readResource(path) != null

  /**
   * The relative paths [discoverAndLoadRecursive] would key its result by, without the contents.
   * Override when names can be listed without reading every file.
   */
  fun discoverFilenamesRecursive(directoryPath: String, suffix: String): Set<String> =
    discoverAndLoadRecursive(directoryPath, suffix).keys
}
