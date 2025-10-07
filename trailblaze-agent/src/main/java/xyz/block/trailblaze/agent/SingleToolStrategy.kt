package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId

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
      println("[WARNING] No tool call detected - forcing tool call on next iteration")
      stepStatus.handleEmptyToolCall(llmMessage)
      helper.setShouldForceToolCall(true)
    }
  }
}
