package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMCapability

object LlmCapabilitiesUtil {
  val ALL_LLM_CAPABILITIES: Set<LLMCapability> = setOf(
    // Basic capabilities
    LLMCapability.Speculation,
    LLMCapability.Temperature,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
    LLMCapability.MultipleChoices,
    LLMCapability.Audio,
    LLMCapability.Document,
    LLMCapability.Embed,
    LLMCapability.Completion,
    LLMCapability.PromptCaching,
    LLMCapability.Moderation,

    // Vision capabilities
    LLMCapability.Vision.Image,
    LLMCapability.Vision.Video,

    // Schema capabilities
    LLMCapability.Schema.JSON.Basic,
    LLMCapability.Schema.JSON.Standard,

    // OpenAI endpoint capabilities
    LLMCapability.OpenAIEndpoint.Completions,
    LLMCapability.OpenAIEndpoint.Responses,
  )

  fun capabilityFromId(capabilityId: String): LLMCapability? = ALL_LLM_CAPABILITIES.firstOrNull {
    it.id == capabilityId
  }

  fun capabilitiesFromIds(capabilityIds: List<String>): List<LLMCapability> = capabilityIds.mapNotNull {
    capabilityFromId(it)
  }
}
