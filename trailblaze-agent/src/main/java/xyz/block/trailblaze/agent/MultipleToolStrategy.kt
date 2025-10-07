package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Strategy for processing multiple tool calls - intended for VerificationStep
 */
class MultipleToolStrategy : ToolProcessingStrategy {

  override fun processToolMessages(
    llmResponses: List<Message.Response>,
    stepStatus: PromptStepStatus,
    traceId: TraceId,
    agent: TrailblazeAgent,
    helper: TrailblazeKoogLlmClientHelper,
  ) {
    val llmMessage = llmResponses.llmMessage()
    val toolMessages = llmResponses.toolMessages()
    if (toolMessages.isEmpty()) {
      println("[WARNING] No tool call detected - forcing tool call on next iteration")
      stepStatus.handleEmptyToolCall(llmMessage)
      helper.setShouldForceToolCall(true)
    } else {
      toolMessages.forEach { tool ->
        helper.handleLlmResponse(
          llmMessage = llmMessage,
          tool = tool,
          step = stepStatus,
          agent = agent,
          traceId = traceId,
        )
      }
    }
  }
}
