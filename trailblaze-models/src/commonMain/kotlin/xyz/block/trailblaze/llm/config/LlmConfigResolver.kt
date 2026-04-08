package xyz.block.trailblaze.llm.config

import ai.koog.prompt.llm.LLMCapability
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.llm.ImageTokenFormula
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Resolves an [LlmConfig] into concrete [TrailblazeLlmModelList] instances.
 *
 * For each model entry:
 * - If the `id` matches a built-in model, the built-in specs are used as the base
 *   and any explicitly specified fields override them.
 * - If the `id` is not in the built-in registry, a new model is constructed from
 *   the entry's fields with reasonable defaults for unspecified values.
 */
object LlmConfigResolver {

  /**
   * Default capabilities for custom models not in the built-in registry,
   * matching Koog's standard model definitions. Users only need to override
   * `vision` to disable it.
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

  /**
   * The result of resolving an [LlmConfig].
   */
  data class ResolvedLlmConfig(
    val modelLists: List<TrailblazeLlmModelList>,
    val defaults: LlmDefaultsConfig,
  )

  /**
   * Resolves the given config into model lists. If the config has no providers,
   * returns the built-in model lists unchanged (backward-compatible default).
   */
  fun resolve(
    config: LlmConfig,
    registry: BuiltInLlmModelRegistry = BuiltInLlmModelRegistry,
  ): ResolvedLlmConfig {
    if (config.providers.isEmpty()) {
      return ResolvedLlmConfig(
        modelLists = registry.allModelLists(),
        defaults = config.defaults,
      )
    }

    val defaultScreenshot = resolveScreenshotConfig(config.defaults.screenshot)
    val modelLists = config.providers.mapNotNull { (key, providerConfig) ->
      if (providerConfig.enabled == false) return@mapNotNull null
      val provider = resolveProvider(key, providerConfig)
      val models = providerConfig.models.map { entry ->
        resolveModelEntry(entry, provider, providerConfig, registry, defaultScreenshot ?: ScreenshotScalingConfig.DEFAULT)
      }
      ConfiguredLlmModelList(provider = provider, entries = models)
    }

    return ResolvedLlmConfig(
      modelLists = modelLists,
      defaults = config.defaults,
    )
  }

  private fun resolveProvider(key: String, config: LlmProviderConfig): TrailblazeLlmProvider {
    val type = config.type ?: inferProviderType(key)
    return when (type) {
      LlmProviderType.OPENAI -> TrailblazeLlmProvider.OPENAI
      LlmProviderType.ANTHROPIC -> TrailblazeLlmProvider.ANTHROPIC
      LlmProviderType.GOOGLE -> TrailblazeLlmProvider.GOOGLE
      LlmProviderType.OLLAMA -> TrailblazeLlmProvider.OLLAMA
      LlmProviderType.OPEN_ROUTER -> TrailblazeLlmProvider.OPEN_ROUTER
      LlmProviderType.OPENAI_COMPATIBLE -> TrailblazeLlmProvider(
        id = key,
        display = key.replace("_", " ").replaceFirstChar { it.uppercase() },
      )
      null -> TrailblazeLlmProvider(
        id = key,
        display = key.replace("_", " ").replaceFirstChar { it.uppercase() },
      )
    }
  }

  private fun inferProviderType(key: String): LlmProviderType? {
    return when (key) {
      TrailblazeLlmProvider.OPENAI.id -> LlmProviderType.OPENAI
      TrailblazeLlmProvider.ANTHROPIC.id -> LlmProviderType.ANTHROPIC
      TrailblazeLlmProvider.GOOGLE.id -> LlmProviderType.GOOGLE
      TrailblazeLlmProvider.OLLAMA.id -> LlmProviderType.OLLAMA
      TrailblazeLlmProvider.OPEN_ROUTER.id -> LlmProviderType.OPEN_ROUTER
      else -> null
    }
  }

  private fun resolveModelEntry(
    entry: LlmModelConfigEntry,
    provider: TrailblazeLlmProvider,
    providerConfig: LlmProviderConfig,
    registry: BuiltInLlmModelRegistry,
    defaultScreenshot: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
  ): TrailblazeLlmModel {
    val baseModel = registry.find(entry.id)
    val screenshot = resolveScreenshotConfig(entry.screenshot) ?: defaultScreenshot
    return if (baseModel != null) {
      applyOverrides(baseModel, entry, provider, providerConfig, screenshot)
    } else {
      buildFromDefinition(entry, provider, providerConfig, screenshot)
    }
  }

  private fun applyOverrides(
    base: TrailblazeLlmModel,
    entry: LlmModelConfigEntry,
    provider: TrailblazeLlmProvider,
    providerConfig: LlmProviderConfig,
    screenshot: ScreenshotScalingConfig,
  ): TrailblazeLlmModel {
    val inputCost = entry.cost?.inputPerMillion ?: base.inputCostPerOneMillionTokens
    val outputCost = entry.cost?.outputPerMillion ?: base.outputCostPerOneMillionTokens
    val cachedInputCost =
      entry.cost?.cachedInputPerMillion ?: base.cachedInputCostPerOneMillionTokens
    val imageFormula = entry.cost?.imageTokenFormula
      ?: base.imageTokenFormula.takeIf { it != ImageTokenFormula.DEFAULT }
      ?: defaultImageTokenFormula(providerConfig.type)

    return base.copy(
      trailblazeLlmProvider = provider,
      modelId = entry.id,
      inputCostPerOneMillionTokens = inputCost,
      outputCostPerOneMillionTokens = outputCost,
      cachedInputCostPerOneMillionTokens = cachedInputCost,
      imageTokenFormula = imageFormula,
      contextLength = entry.contextLength ?: base.contextLength,
      maxOutputTokens = entry.maxOutputTokens ?: base.maxOutputTokens,
      capabilityIds = applyVisionOverride(base.capabilityIds, entry.vision),
      defaultTemperature = entry.temperature ?: base.defaultTemperature,
      screenshotScalingConfig = screenshot,
    )
  }

  /** Default context length used when not specified and model is not in the built-in registry. */
  private const val DEFAULT_CONTEXT_LENGTH = 131_072L

  /** Default max output tokens used when not specified and model is not in the built-in registry. */
  private const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192L

  /**
   * Builds a model from a YAML definition that doesn't match any built-in model.
   * Uses reasonable defaults for context_length and max_output_tokens when not specified,
   * allowing project configs to list models that may not be installed yet (e.g., Ollama models
   * that haven't been pulled).
   */
  private fun buildFromDefinition(
    entry: LlmModelConfigEntry,
    provider: TrailblazeLlmProvider,
    providerConfig: LlmProviderConfig,
    screenshot: ScreenshotScalingConfig,
  ): TrailblazeLlmModel {
    return TrailblazeLlmModel(
      trailblazeLlmProvider = provider,
      modelId = entry.id,
      inputCostPerOneMillionTokens = entry.cost?.inputPerMillion ?: 0.0,
      outputCostPerOneMillionTokens = entry.cost?.outputPerMillion ?: 0.0,
      cachedInputCostPerOneMillionTokens =
        entry.cost?.cachedInputPerMillion ?: entry.cost?.inputPerMillion ?: 0.0,
      imageTokenFormula = entry.cost?.imageTokenFormula
        ?: defaultImageTokenFormula(providerConfig.type),
      contextLength = entry.contextLength ?: DEFAULT_CONTEXT_LENGTH,
      maxOutputTokens = entry.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
      capabilityIds = applyVisionOverride(DEFAULT_CAPABILITY_IDS, entry.vision),
      defaultTemperature = entry.temperature,
      screenshotScalingConfig = screenshot,
    )
  }

  private fun applyVisionOverride(
    baseCapabilities: List<String>,
    vision: Boolean?,
  ): List<String> {
    if (vision == null) return baseCapabilities
    val visionId = LLMCapability.Vision.Image.id
    return if (vision) {
      if (visionId in baseCapabilities) baseCapabilities
      else baseCapabilities + visionId
    } else {
      baseCapabilities.filter { it != visionId }
    }
  }

  /** Infers a default image token formula from the provider type when not explicitly set. */
  internal fun defaultImageTokenFormulaForType(type: LlmProviderType?): ImageTokenFormula =
    defaultImageTokenFormula(type)

  private fun defaultImageTokenFormula(type: LlmProviderType?): ImageTokenFormula {
    return when (type) {
      LlmProviderType.ANTHROPIC -> ImageTokenFormula.ANTHROPIC
      LlmProviderType.OPENAI -> ImageTokenFormula.OPENAI_TILE
      LlmProviderType.GOOGLE -> ImageTokenFormula.GOOGLE_TILE
      else -> ImageTokenFormula.DEFAULT
    }
  }

  /**
   * Parses an [LlmScreenshotConfig] into a [ScreenshotScalingConfig], or returns null
   * if no screenshot config is specified.
   */
  private fun resolveScreenshotConfig(config: LlmScreenshotConfig?): ScreenshotScalingConfig? {
    val dims = config?.parseDimensions() ?: return null
    return ScreenshotScalingConfig(maxDimension1 = dims.first, maxDimension2 = dims.second)
  }
}
