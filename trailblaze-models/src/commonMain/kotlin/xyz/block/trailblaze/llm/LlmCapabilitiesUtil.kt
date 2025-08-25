package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMCapability

object LlmCapabilitiesUtil {
  val ALL_LLM_CAPABILITIES: Set<LLMCapability> = setOf(
    LLMCapability.Audio,
    LLMCapability.Completion,
    LLMCapability.Document,
    LLMCapability.Embed,
    LLMCapability.Moderation,
    LLMCapability.MultipleChoices,
    LLMCapability.PromptCaching,
    LLMCapability.Schema.JSON.Full,
    LLMCapability.Schema.JSON.Simple,
    LLMCapability.Speculation,
    LLMCapability.Temperature,
    LLMCapability.ToolChoice,
    LLMCapability.Tools,
    LLMCapability.Vision.Image,
    LLMCapability.Vision.Video,
  )

  fun capabilityFromString(capabilityId: String): LLMCapability? = ALL_LLM_CAPABILITIES.firstOrNull {
    it.id == capabilityId
  }
}
