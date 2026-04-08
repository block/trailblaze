package xyz.block.trailblaze.llm.config

/**
 * Merges two [LlmConfig] instances. Later (overlay) sources override earlier (base) ones.
 * Merging is deep and field-level, not wholesale replacement.
 */
object LlmConfigMerger {

  fun merge(base: LlmConfig, overlay: LlmConfig): LlmConfig {
    val mergedProviders = buildMap {
      putAll(base.providers)
      for ((key, overlayProvider) in overlay.providers) {
        val baseProvider = base.providers[key]
        put(key, if (baseProvider != null) mergeProvider(baseProvider, overlayProvider) else overlayProvider)
      }
    }

    return LlmConfig(
      providers = mergedProviders,
      defaults = mergeDefaults(base.defaults, overlay.defaults),
    )
  }

  private fun mergeProvider(base: LlmProviderConfig, overlay: LlmProviderConfig): LlmProviderConfig {
    return LlmProviderConfig(
      enabled = overlay.enabled ?: base.enabled,
      type = overlay.type ?: base.type,
      description = overlay.description ?: base.description,
      baseUrl = overlay.baseUrl ?: base.baseUrl,
      chatCompletionsPath = overlay.chatCompletionsPath ?: base.chatCompletionsPath,
      headers = base.headers + overlay.headers,
      auth = mergeAuth(base.auth, overlay.auth),
      models = mergeModels(base.models, overlay.models),
    )
  }

  private fun mergeAuth(base: LlmAuthConfig, overlay: LlmAuthConfig): LlmAuthConfig {
    return LlmAuthConfig(
      envVar = overlay.envVar ?: base.envVar,
      required = overlay.required ?: base.required,
    )
  }

  private fun mergeModels(
    base: List<LlmModelConfigEntry>,
    overlay: List<LlmModelConfigEntry>,
  ): List<LlmModelConfigEntry> {
    val baseById = base.associateBy { it.id }.toMutableMap()
    for (overlayModel in overlay) {
      val baseModel = baseById[overlayModel.id]
      baseById[overlayModel.id] =
        if (baseModel != null) mergeModelEntry(baseModel, overlayModel) else overlayModel
    }
    return baseById.values.toList()
  }

  private fun mergeModelEntry(
    base: LlmModelConfigEntry,
    overlay: LlmModelConfigEntry,
  ): LlmModelConfigEntry {
    return LlmModelConfigEntry(
      id = overlay.id,
      tier = overlay.tier ?: base.tier,
      vision = overlay.vision ?: base.vision,
      temperature = overlay.temperature ?: base.temperature,
      contextLength = overlay.contextLength ?: base.contextLength,
      maxOutputTokens = overlay.maxOutputTokens ?: base.maxOutputTokens,
      cost = mergeCost(base.cost, overlay.cost),
      screenshot = overlay.screenshot ?: base.screenshot,
    )
  }

  private fun mergeCost(base: LlmModelCostConfig?, overlay: LlmModelCostConfig?): LlmModelCostConfig? {
    if (overlay == null) return base
    if (base == null) return overlay
    return LlmModelCostConfig(
      inputPerMillion = overlay.inputPerMillion ?: base.inputPerMillion,
      outputPerMillion = overlay.outputPerMillion ?: base.outputPerMillion,
      cachedInputPerMillion = overlay.cachedInputPerMillion ?: base.cachedInputPerMillion,
    )
  }

  private fun mergeDefaults(base: LlmDefaultsConfig, overlay: LlmDefaultsConfig): LlmDefaultsConfig {
    return LlmDefaultsConfig(
      model = overlay.model ?: base.model,
      screenshot = overlay.screenshot ?: base.screenshot,
    )
  }
}
