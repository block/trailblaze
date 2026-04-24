package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.util.Console

/**
 * Strategy for processing single tool calls - maintains current behavior for DirectionStep
 * Extracts the first tool message and processes it using the existing logic.
 */
class SingleToolStrategy : ToolProcessingStrategy {

  override fun processToolMessages(
    llmResponses: List<Message.Response>,
    stepStatus: PromptStepStatus,
    traceId: TraceId,
    agent: TrailblazeAgent,
    helper: TrailblazeKoogLlmClientHelper,
  ) {
    val toolMessage = llmResponses.firstToolMessage()
    val llmMessage = llmResponses.llmMessage()

    if (toolMessage != null) {
      helper.handleLlmResponse(
        llmMessage = llmMessage,
        tool = toolMessage,
        step = stepStatus,
        agent = agent,
        traceId = traceId,
      )
    } else {
      // Should be unreachable under ToolChoice.Required, but log + record defensively if the
      // provider ever returns a tools-less response anyway.
      Console.log("[WARNING] No tool call detected from LLM despite tool_choice=Required")
      stepStatus.handleEmptyToolCall(llmMessage)
    }
  }
}
