package xyz.block.trailblaze.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Strategy interface for processing tool messages from LLM responses.
 * Different implementations handle single vs multiple tool call scenarios.
 */
interface ToolProcessingStrategy {

  /**
   * Process tool calls from an LLM response.
   *
   * @param llmResponse The Assistant message returned by the LLM (Koog 1.0.0 collapsed the
   *   former `List<Message.Response>` return into a single `Message.Assistant` whose parts
   *   carry the text, reasoning, and tool-call content blocks).
   * @param stepStatus Status tracker for the current prompt step
   * @param agent Trailblaze agent for executing tools
   * @param helper Helper for LLM client operations
   */
  fun processToolMessages(
    llmResponse: Message.Assistant,
    stepStatus: PromptStepStatus,
    traceId: TraceId,
    agent: TrailblazeAgent,
    helper: TrailblazeKoogLlmClientHelper,
  )

  fun Message.Assistant.firstToolCall(): MessagePart.Tool.Call? =
    parts.filterIsInstance<MessagePart.Tool.Call>().firstOrNull()

  fun Message.Assistant.toolCalls(): List<MessagePart.Tool.Call> =
    parts.filterIsInstance<MessagePart.Tool.Call>()

  fun Message.Assistant.textMessage(): String? =
    parts.filterIsInstance<MessagePart.Text>().joinToString(separator = "\n") { it.text }
      .takeIf { it.isNotBlank() }
}
