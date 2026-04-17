package xyz.block.trailblaze.llm.config

import java.io.File
import java.util.jar.JarFile

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
}
