package xyz.block.trailblaze.llm.config

import java.io.File

/**
 * [ConfigResourceSource] that reads YAML configuration files from an arbitrary filesystem
 * directory tree.
 *
 * Lets users point Trailblaze at a `trailblaze-config/` directory that lives with their app
 * (e.g. checked into the app's repo) rather than needing every target/toolset/tool to be
 * built into the framework's classpath. Pairs with [ClasspathConfigResourceSource] via
 * [CompositeConfigResourceSource] so framework-shipped config and user-contributed config
 * coexist.
 *
 * Directory layout expected under [rootDir] mirrors the classpath convention documented in
 * `TrailblazeConfigPaths`: `targets/`, `toolsets/`, `tools/`, `providers/` — each containing
 * a flat list of `.yaml` files.
 *
 * Missing subdirectories return empty maps — no error. That keeps the common case (user
 * only ships `targets/` + scripts) working with zero ceremony.
 */
class FilesystemConfigResourceSource(
  private val rootDir: File,
) : ConfigResourceSource {

  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> {
    val subDir = resolveSubDir(rootDir, directoryPath)
    if (!subDir.isDirectory) return emptyMap()
    val files = subDir.listFiles() ?: return emptyMap()
    return files
      .filter { it.isFile && it.name.endsWith(suffix) }
      .associate { it.name.removeSuffix(suffix) to it.readText() }
  }

  companion object {
    /**
     * Strips the conventional `trailblaze-config/` prefix from classpath-style paths so the
     * remaining relative portion resolves under [rootDir], whatever the user named that dir.
     * Paths that don't start with the prefix are used as-is.
     *
     * Example: `directoryPath = "trailblaze-config/targets"`, `rootDir = /app/trailblaze-config`
     * → reads from `/app/trailblaze-config/targets`.
     */
    internal fun resolveSubDir(rootDir: File, directoryPath: String): File {
      val prefix = "${TrailblazeConfigPaths.CONFIG_DIR}/"
      val relative = if (directoryPath.startsWith(prefix)) {
        directoryPath.removePrefix(prefix)
      } else {
        directoryPath
      }
      return File(rootDir, relative)
    }
  }
}
