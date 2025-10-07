package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams

/**
 * Data class representing a request to the Koog LLM.
 */
data class KoogLlmRequestData(
  val messages: List<Message>,
  val toolDescriptors: List<ToolDescriptor>,
  val toolChoice: LLMParams.ToolChoice,
)
