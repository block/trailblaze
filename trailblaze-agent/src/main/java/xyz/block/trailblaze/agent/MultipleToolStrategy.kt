package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.util.Console

/**
 * Strategy for processing multiple tool calls - intended for VerificationStep
 */
class MultipleToolStrategy : ToolProcessingStrategy {

  override fun processToolMessages(
    llmResponse: Message.Assistant,
    stepStatus: PromptStepStatus,
    traceId: TraceId,
    agent: TrailblazeAgent,
    helper: TrailblazeKoogLlmClientHelper,
  ) {
    val llmMessage = llmResponse.textMessage()
    val toolCalls = llmResponse.toolCalls()
    if (toolCalls.isEmpty()) {
      // Should be unreachable under ToolChoice.Required, but log + record defensively if the
      // provider ever returns a tools-less response anyway.
      Console.log("[WARNING] No tool call detected from LLM despite tool_choice=Required")
      stepStatus.handleEmptyToolCall(llmMessage)
    } else {
      toolCalls.forEach { tool ->
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
