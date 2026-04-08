package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import java.io.File
import java.util.jar.JarFile

internal const val PROVIDERS_PATH = TrailblazeConfigPaths.PROVIDERS_DIR

/** Core providers shipped with trailblaze-models — used as fallback if discovery fails. */
private val CORE_PROVIDERS = setOf(
  TrailblazeLlmProvider.ANTHROPIC.id,
  TrailblazeLlmProvider.GOOGLE.id,
  TrailblazeLlmProvider.OLLAMA.id,
  TrailblazeLlmProvider.OPENAI.id,
  TrailblazeLlmProvider.OPEN_ROUTER.id,
)

/**
 * Shared JVM/Android implementation: discovers and loads all provider YAML files
 * from `trailblaze-config/providers/` across the entire classpath. Any module can contribute
 * a `{provider_id}.yaml` file at that path and it will be picked up automatically.
 *
 * Falls back to loading only the core providers if classpath directory scanning
 * is not supported (e.g., on some Android runtimes).
 */
internal fun readBuiltInProviderYamlResourcesFromClasspath(): Map<String, String> {
  val contextClassLoader = Thread.currentThread().contextClassLoader
  val classClassLoader = BuiltInLlmModelRegistry::class.java.classLoader
  val classLoader = contextClassLoader ?: classClassLoader ?: return emptyMap()
  val discovered = discoverYamlFiles(classLoader)
  // If discovery found files, use them. Otherwise fall back to known core list.
  val providerNames = if (discovered.isNotEmpty()) {
    discovered.map { it.removeSuffix(".yaml") }
  } else {
    CORE_PROVIDERS
  }
  return providerNames.mapNotNull { name ->
    val resource = "$PROVIDERS_PATH/$name.yaml"
    val content = (classLoader.getResource(resource)
      ?: classClassLoader?.getResource(resource))?.readText()
      ?: return@mapNotNull null
    name to content
  }.toMap()
}

/**
 * Loads a single provider's YAML by provider_id. No discovery needed —
 * just a direct classpath resource lookup. Works on JVM and Android.
 *
 * Tries the thread's context classloader first (standard on JVM), then falls back to
 * this class's own classloader (needed on Android instrumentation tests where the
 * context classloader may not have access to library resources).
 */
internal fun readBuiltInProviderYamlFromClasspath(providerId: String): String? {
  val resource = "$PROVIDERS_PATH/$providerId.yaml"
  return (Thread.currentThread().contextClassLoader?.getResource(resource)
    ?: BuiltInLlmModelRegistry::class.java.classLoader?.getResource(resource))
    ?.readText()
}

/**
 * Scans all classpath entries that contain `trailblaze-config/providers/` and collects
 * every `.yaml` filename found there. Returns empty if the runtime doesn't
 * support directory listing (e.g., some Android environments).
 */
private fun discoverYamlFiles(classLoader: ClassLoader): Set<String> {
  val names = mutableSetOf<String>()
  try {
    val urls = classLoader.getResources(PROVIDERS_PATH)
    for (url in urls.asSequence()) {
      when (url.protocol) {
        "file" -> {
          // Development / filesystem classpath entry
          File(url.toURI()).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".yaml") }
            ?.forEach { names.add(it.name) }
        }
        "jar" -> {
          // Packaged JAR classpath entry
          val jarPath = url.path.substringAfter("file:").substringBefore("!")
          try {
            JarFile(jarPath).use { jar ->
              jar.entries().asSequence()
                .filter { it.name.startsWith("$PROVIDERS_PATH/") && it.name.endsWith(".yaml") }
                .forEach { names.add(it.name.removePrefix("$PROVIDERS_PATH/")) }
            }
          } catch (_: Exception) {
            // Skip unreadable JARs
          }
        }
      }
    }
  } catch (_: Exception) {
    // Fall back gracefully if classpath scanning fails
  }
  return names
}
