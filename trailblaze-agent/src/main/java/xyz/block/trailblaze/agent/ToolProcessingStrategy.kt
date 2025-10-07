package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Strategy interface for processing tool messages from LLM responses.
 * Different implementations handle single vs multiple tool call scenarios.
 */
interface ToolProcessingStrategy {

  /**
   * Process tool messages from an LLM response
   *
   * @param stepStatus Status tracker for the current prompt step
   * @param agent Trailblaze agent for executing tools
   * @param helper Helper for LLM client operations
   */
  fun processToolMessages(
    llmResponses: List<Message.Response>,
    stepStatus: PromptStepStatus,
    traceId: TraceId,
    agent: TrailblazeAgent,
    helper: TrailblazeKoogLlmClientHelper,
  )

  fun List<Message.Response>.firstToolMessage() = filterIsInstance<Message.Tool>().firstOrNull()
  fun List<Message.Response>.toolMessages() = filterIsInstance<Message.Tool>()
  fun List<Message.Response>.llmMessage() = filterIsInstance<Message.Assistant>()
    .firstOrNull()
    ?.content
}
