package xyz.block.trailblaze.llm.config

import ai.koog.prompt.llm.LLMCapability
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.util.Console

/**
 * Registry of all built-in (shipped) LLM models.
 *
 * Models are loaded from YAML resource files at `trailblaze-config/providers/{provider_id}.yaml` on the
 * classpath. Individual lookups via [find] lazy-load a single provider file on demand —
 * no classpath scanning required, works on JVM and Android. Full catalog methods like
 * [allModelLists] use classpath discovery (JVM) with a core-provider fallback (Android).
 *
 * On wasmJs, the registry is empty (models come from user/project YAML config instead).
 */
object BuiltInLlmModelRegistry {

  private val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
      encodeDefaults = false,
    ),
  )

  private data class LoadedProvider(
    val config: BuiltInProviderConfig,
    val provider: TrailblazeLlmProvider,
    val modelList: TrailblazeLlmModelList,
  )

  // --- Lazy per-provider cache (for find / modelListForProvider / authForProvider) ---

  /** Cache of provider_id → loaded provider. Null value means "tried and not found." */
  private val providerCache = mutableMapOf<String, LoadedProvider?>()

  private fun getOrLoadProvider(providerId: String): LoadedProvider? {
    return providerCache.getOrPut(providerId) { loadSingleProvider(providerId) }
  }

  private fun loadSingleProvider(providerId: String): LoadedProvider? {
    val content = readBuiltInProviderYaml(providerId) ?: return null
    return try {
      parseProviderYaml(content)
    } catch (e: Exception) {
      Console.log("Warning: Failed to parse built-in provider YAML '$providerId': ${e.message}")
      null
    }
  }

  // --- Full catalog (for allModelLists / all) ---

  private val allProviders: List<LoadedProvider> by lazy { loadAllFromDiscovery() }

  /**
   * All built-in model lists. Uses classpath discovery on JVM; falls back to a core
   * provider list on Android. Intended for desktop UI / model pickers, not on-device.
   */
  fun allModelLists(): List<TrailblazeLlmModelList> = allProviders.map { it.modelList }

  fun all(): List<TrailblazeLlmModel> = allProviders.flatMap { it.modelList.entries }

  // --- Lookups (lazy per-provider, no discovery needed) ---

  /**
   * Finds a built-in model by key, or null if not found. Accepts either:
   * - `"provider_id/model_id"` (e.g. `"anthropic/claude-sonnet-4-6"`) — loads only that provider
   * - `"model_id"` (e.g. `"claude-sonnet-4-6"`) — searches all providers (needs discovery)
   */
  fun find(key: String): TrailblazeLlmModel? {
    val slashIndex = key.indexOf('/')
    if (slashIndex > 0) {
      // Exact: provider_id/model_id — lazy-load just that one provider
      val providerId = key.substring(0, slashIndex)
      val modelId = key.substring(slashIndex + 1)
      return getOrLoadProvider(providerId)?.modelList?.entries?.firstOrNull { it.modelId == modelId }
    }
    // Bare model_id — must search all providers (host/desktop path)
    return allProviders
      .flatMap { it.modelList.entries }
      .firstOrNull { it.modelId == key }
  }

  /**
   * Finds a built-in model by key, throwing a descriptive error if not found.
   *
   * The error message includes:
   * - What was looked up and why it failed (provider YAML missing vs. model not in provider)
   * - Available models for the provider (if the provider was found)
   * - Hint about adding a YAML resource file if the provider is missing
   */
  fun require(key: String): TrailblazeLlmModel {
    find(key)?.let { return it }

    val slashIndex = key.indexOf('/')
    if (slashIndex > 0) {
      val providerId = key.substring(0, slashIndex)
      val modelId = key.substring(slashIndex + 1)
      val loaded = getOrLoadProvider(providerId)
      if (loaded == null) {
        error(
          "Provider '$providerId' not found. " +
            "No YAML resource at trailblaze-config/providers/$providerId.yaml on the classpath. " +
            "Add a $providerId.yaml file to src/main/resources/trailblaze-config/providers/ in your module."
        )
      }
      val available = loaded.modelList.entries.map { it.modelId }
      error(
        "Model '$modelId' not found in provider '$providerId'. " +
          "Available models: $available"
      )
    }
    error("Model '$key' not found in any built-in provider.")
  }

  /**
   * Returns the built-in model list for the given provider, or null if not found.
   * Lazy-loads just that provider's YAML — no discovery needed.
   */
  fun modelListForProvider(provider: TrailblazeLlmProvider): TrailblazeLlmModelList? =
    getOrLoadProvider(provider.id)?.modelList

  /**
   * Returns the default model ID for the given provider, or null if not set.
   * Reads the `default_model` field from the provider's built-in YAML.
   */
  fun defaultModelForProvider(provider: TrailblazeLlmProvider): String? =
    getOrLoadProvider(provider.id)?.config?.defaultModel

  /**
   * Returns the built-in auth config for the given provider, or null if not found.
   * This is the source of truth for which env var each provider uses.
   */
  fun authForProvider(provider: TrailblazeLlmProvider): LlmAuthConfig? =
    getOrLoadProvider(provider.id)?.config?.auth

  /**
   * Returns the built-in config for the given provider, or null if not found.
   * Used by [LlmAuthResolver] to populate headers and base_url from built-in YAML.
   */
  fun configForProvider(provider: TrailblazeLlmProvider): BuiltInProviderConfig? =
    getOrLoadProvider(provider.id)?.config

  // --- Loading ---

  private fun loadAllFromDiscovery(): List<LoadedProvider> {
    val yamlContents = readBuiltInProviderYamlResources()
    return yamlContents.mapNotNull { (_, content) ->
      try {
        val loaded = parseProviderYaml(content)
        // Also populate the per-provider cache
        if (loaded.config.providerId !in providerCache) {
          providerCache[loaded.config.providerId] = loaded
        }
        loaded
      } catch (e: Exception) {
        Console.log("Warning: Failed to parse built-in provider YAML: ${e.message}")
        null
      }
    }
  }

  private fun parseProviderYaml(content: String): LoadedProvider {
    val config = yaml.decodeFromString(BuiltInProviderConfig.serializer(), content)
    val provider = resolveProvider(config.providerId, config.name, config.description)
    val providerType = inferProviderType(config.providerId)
    val models = config.models.map { entry -> buildModel(entry, provider, providerType) }
    return LoadedProvider(
      config = config,
      provider = provider,
      modelList = ConfiguredLlmModelList(provider = provider, entries = models),
    )
  }

  /**
   * Maps a provider_id to the corresponding [TrailblazeLlmProvider].
   * Known IDs map to well-known providers; unknown IDs create a custom provider.
   * When a [name] is provided (from YAML), it overrides the default display name.
   */
  private fun resolveProvider(
    providerId: String,
    name: String? = null,
    description: String? = null,
  ): TrailblazeLlmProvider {
    val base = when (providerId) {
      TrailblazeLlmProvider.OPENAI.id -> TrailblazeLlmProvider.OPENAI
      TrailblazeLlmProvider.ANTHROPIC.id -> TrailblazeLlmProvider.ANTHROPIC
      TrailblazeLlmProvider.GOOGLE.id -> TrailblazeLlmProvider.GOOGLE
      TrailblazeLlmProvider.OLLAMA.id -> TrailblazeLlmProvider.OLLAMA
      TrailblazeLlmProvider.OPEN_ROUTER.id -> TrailblazeLlmProvider.OPEN_ROUTER
      TrailblazeLlmProvider.DATABRICKS.id -> TrailblazeLlmProvider.DATABRICKS
      else -> TrailblazeLlmProvider(
        id = providerId,
        display = name ?: providerId.replace("_", " ").replaceFirstChar { it.uppercase() },
        description = description,
      )
    }
    return base.copy(
      display = name ?: base.display,
      description = description ?: base.description,
    )
  }

  private fun inferProviderType(providerId: String): LlmProviderType? =
    LlmProviderType.inferFromProviderId(providerId)

  /**
   * Default capabilities for built-in models, matching Koog's standard model definitions.
   * Vision is included by default and can be toggled per model with `vision: false`.
   */
  private val DEFAULT_CAPABILITY_IDS: List<String> = listOf(
    LLMCapability.Completion,
    LLMCapability.Document,
    LLMCapability.MultipleChoices,
    LLMCapability.Schema.JSON.Basic,
    LLMCapability.Schema.JSON.Standard,
    LLMCapability.Speculation,
    LLMCapability.Temperature,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
    LLMCapability.Vision.Image,
    LLMCapability.OpenAIEndpoint.Completions,
    LLMCapability.OpenAIEndpoint.Responses,
  ).map { it.id }

  private fun buildModel(
    entry: LlmModelConfigEntry,
    provider: TrailblazeLlmProvider,
    providerType: LlmProviderType?,
  ): TrailblazeLlmModel {
    val visionId = LLMCapability.Vision.Image.id
    val capabilityIds = when (entry.vision) {
      false -> DEFAULT_CAPABILITY_IDS.filter { it != visionId }
      true ->
        if (visionId in DEFAULT_CAPABILITY_IDS) DEFAULT_CAPABILITY_IDS
        else DEFAULT_CAPABILITY_IDS + visionId
      null -> DEFAULT_CAPABILITY_IDS
    }
    return TrailblazeLlmModel(
      trailblazeLlmProvider = provider,
      modelId = entry.id,
      inputCostPerOneMillionTokens = entry.cost?.inputPerMillion ?: 0.0,
      outputCostPerOneMillionTokens = entry.cost?.outputPerMillion ?: 0.0,
      cachedInputCostPerOneMillionTokens =
        entry.cost?.cachedInputPerMillion ?: entry.cost?.inputPerMillion ?: 0.0,
      imageTokenFormula = entry.cost?.imageTokenFormula
        ?: LlmConfigResolver.defaultImageTokenFormulaForType(providerType),
      contextLength = entry.contextLength ?: 131_072L,
      maxOutputTokens = entry.maxOutputTokens ?: 8_192L,
      capabilityIds = capabilityIds,
      defaultTemperature = entry.temperature,
    )
  }
}
