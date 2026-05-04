package xyz.block.trailblaze.llm.config

import java.io.File
import java.util.jar.JarFile
import xyz.block.trailblaze.util.Console

/**
 * Shared utility for discovering and loading YAML resource files from the classpath.
 *
 * Used by [BuiltInLlmModelRegistry], `ToolSetYamlLoader`, and `AppTargetYamlLoader`
 * to scan both filesystem and JAR classpath entries for configuration files.
 */
object ClasspathResourceDiscovery {

  /**
   * Discovers files matching [suffix] under [directoryPath] across all classpath entries.
   * Returns the set of matching filenames (not full paths).
   *
   * @param directoryPath classpath directory to scan (e.g., `"trailblaze-config/providers"`)
   * @param suffix file extension filter (e.g., `".yaml"`, `".toolset.yaml"`)
   * @param anchorClass class to use for classloader resolution (defaults to this object)
   */
  fun discoverFilenames(
    directoryPath: String,
    suffix: String,
    anchorClass: Class<*> = ClasspathResourceDiscovery::class.java,
  ): Set<String> {
    val classLoader = Thread.currentThread().contextClassLoader
      ?: anchorClass.classLoader
      ?: return emptySet()

    val filenames = mutableSetOf<String>()
    try {
      val urls = classLoader.getResources(directoryPath)
      for (url in urls.asSequence()) {
        when (url.protocol) {
          "file" -> {
            File(url.toURI()).listFiles()
              ?.filter { it.isFile && it.name.endsWith(suffix) }
              ?.forEach { filenames.add(it.name) }
          }
          "jar" -> {
            val jarPath = url.path.substringAfter("file:").substringBefore("!")
            try {
              JarFile(jarPath).use { jar ->
                jar.entries().asSequence()
                  .filter {
                    it.name.startsWith("$directoryPath/") && it.name.endsWith(suffix)
                  }
                  .forEach { filenames.add(it.name.removePrefix("$directoryPath/")) }
              }
            } catch (_: Exception) { /* Skip unreadable JARs */ }
          }
        }
      }
    } catch (_: Exception) { /* Fall back gracefully */ }
    return filenames
  }

  /**
   * Loads a single classpath resource as text, or null if not found.
   *
   * Tries the thread's context classloader first, then falls back to [anchorClass]'s
   * classloader (needed on Android instrumentation tests).
   */
  fun loadResource(
    path: String,
    anchorClass: Class<*> = ClasspathResourceDiscovery::class.java,
  ): String? {
    return (Thread.currentThread().contextClassLoader?.getResource(path)
      ?: anchorClass.classLoader?.getResource(path))
      ?.readText()
  }

  /**
   * Discovers all files matching [suffix] under [directoryPath] and loads their contents.
   * Returns a map of `strippedFilename → content`.
   *
   * The filename key has [suffix] removed (e.g., `"anthropic"` for `"anthropic.yaml"`).
   */
  fun discoverAndLoad(
    directoryPath: String,
    suffix: String,
    anchorClass: Class<*> = ClasspathResourceDiscovery::class.java,
  ): Map<String, String> {
    val filenames = discoverFilenames(directoryPath, suffix, anchorClass)
    return filenames.mapNotNull { filename ->
      val resource = "$directoryPath/$filename"
      val content = loadResource(resource, anchorClass) ?: return@mapNotNull null
      filename.removeSuffix(suffix) to content
    }.toMap()
  }

  /**
   * Recursively discovers files matching [suffix] anywhere under [directoryPath]. Returns
   * paths relative to [directoryPath] (e.g. `clock/pack.yaml`, `wikipedia/pack.yaml`).
   *
   * Used for hierarchical layouts like `trailblaze-config/packs/<id>/pack.yaml` where the
   * non-recursive [discoverFilenames] would miss subdirectory contents on `file:` classpath
   * entries. The `jar:` branch of [discoverFilenames] is already recursive, but the `file:`
   * branch was direct-child only — this method makes both protocols behave uniformly.
   *
   * The [suffix] filter is matched against the **relative path** under [directoryPath] (with
   * `/` separators) so callers can pass `"/pack.yaml"` to require that match be a `pack.yaml`
   * file inside a subdirectory, mirroring the jar-branch semantics where the suffix is
   * matched against the full entry name.
   */
  fun discoverFilenamesRecursive(
    directoryPath: String,
    suffix: String,
    anchorClass: Class<*> = ClasspathResourceDiscovery::class.java,
  ): Set<String> {
    val classLoader = Thread.currentThread().contextClassLoader
      ?: anchorClass.classLoader
      ?: return emptySet()

    val results = mutableSetOf<String>()
    try {
      val urls = classLoader.getResources(directoryPath)
      for (url in urls.asSequence()) {
        when (url.protocol) {
          "file" -> {
            val rootDir = File(url.toURI())
            if (!rootDir.isDirectory) continue
            val rootPath = rootDir.toPath()
            rootDir.walkTopDown()
              .filter { it.isFile }
              .forEach { file ->
                val rel = rootPath.relativize(file.toPath()).toString()
                  .replace(File.separatorChar, '/')
                if (rel.endsWith(suffix)) {
                  results.add(rel)
                }
              }
          }
          "jar" -> {
            val jarPath = url.path.substringAfter("file:").substringBefore("!")
            try {
              JarFile(jarPath).use { jar ->
                jar.entries().asSequence()
                  .filter {
                    it.name.startsWith("$directoryPath/") && it.name.endsWith(suffix) && !it.isDirectory
                  }
                  .forEach { results.add(it.name.removePrefix("$directoryPath/")) }
              }
            } catch (e: Exception) {
              // Skip unreadable JARs but log the cause — corrupted artifacts, permission
              // issues, or zip-format errors can mask "missing pack on prod" bugs that are
              // otherwise undebuggable.
              Console.log(
                "Warning: ClasspathResourceDiscovery failed to scan jar '$jarPath' for " +
                  "$directoryPath/*$suffix: ${e.message}",
              )
            }
          }
        }
      }
    } catch (e: Exception) {
      // The outer try wraps `getResources()` which can throw on misconfigured classpaths.
      // Fall through to whatever partial results we collected so far rather than going to
      // empty silently — and log so the operator knows discovery was incomplete.
      Console.log(
        "Warning: ClasspathResourceDiscovery aborted scan of '$directoryPath' for *$suffix " +
          "(${results.size} matches collected before failure): ${e.message}",
      )
    }
    return results
  }

  /**
   * Recursively discovers files under [directoryPath] matching [suffix] and loads their
   * contents. Map keys are paths relative to [directoryPath] (suffix preserved so callers
   * can derive both filename and intermediate directories).
   */
  fun discoverAndLoadRecursive(
    directoryPath: String,
    suffix: String,
    anchorClass: Class<*> = ClasspathResourceDiscovery::class.java,
  ): Map<String, String> {
    val relativePaths = discoverFilenamesRecursive(directoryPath, suffix, anchorClass)
    return relativePaths.mapNotNull { relPath ->
      val resource = "$directoryPath/$relPath"
      val content = loadResource(resource, anchorClass) ?: return@mapNotNull null
      relPath to content
    }.toMap()
  }
}
