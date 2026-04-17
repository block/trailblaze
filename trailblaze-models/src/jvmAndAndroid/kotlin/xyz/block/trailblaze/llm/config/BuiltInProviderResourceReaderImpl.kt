package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.llm.TrailblazeLlmProvider

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
  val discovered = ClasspathResourceDiscovery.discoverAndLoad(
    directoryPath = PROVIDERS_PATH,
    suffix = ".yaml",
    anchorClass = BuiltInLlmModelRegistry::class.java,
  )
  if (discovered.isNotEmpty()) return discovered

  // Fall back to loading only the known core providers
  return CORE_PROVIDERS.mapNotNull { name ->
    val content = ClasspathResourceDiscovery.loadResource(
      path = "$PROVIDERS_PATH/$name.yaml",
      anchorClass = BuiltInLlmModelRegistry::class.java,
    ) ?: return@mapNotNull null
    name to content
  }.toMap()
}

/**
 * Loads a single provider's YAML by provider_id. No discovery needed —
 * just a direct classpath resource lookup. Works on JVM and Android.
 */
internal fun readBuiltInProviderYamlFromClasspath(providerId: String): String? {
  return ClasspathResourceDiscovery.loadResource(
    path = "$PROVIDERS_PATH/$providerId.yaml",
    anchorClass = BuiltInLlmModelRegistry::class.java,
  )
}
