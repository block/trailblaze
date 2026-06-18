package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep

/**
 * A delegating [LLMClient] wrapper that emits a [TrailblazeLog.TrailblazeLlmRequestLog] for every
 * `execute(...)` call the agent makes, then returns the real response unchanged.
 *
 * ## Why this exists
 *
 * The opt-in [KoogStrategyGraphAgent] drives the device through Koog's [ai.koog.agents.core.agent.AIAgent],
 * which calls the underlying [LLMClient] directly (via the prompt executor) instead of routing
 * through Trailblaze's [TrailblazeLogger.logLlmRequest]. So the Koog path produced tool / snapshot
 * logs but **no** `TrailblazeLlmRequestLog` — meaning token usage / cost, the prompt + response
 * messages, and the `toolOptions` available to the LLM were all missing from the session log,
 * unlike the legacy [xyz.block.trailblaze.agent.TrailblazeRunner] path.
 *
 * This decorator closes that gap without changing the reasoning loop: it sits between the agent's
 * prompt executor and the real client. On each [execute] it (1) delegates to the real client, then
 * (2) builds a fresh [PromptStepStatus] capturing the current screen and logs a
 * `TrailblazeLlmRequestLog` mirroring the fields the [xyz.block.trailblaze.agent.DirectMcpAgent]
 * logging path emits. The request prompt's messages and the assistant response (which carries the
 * Koog `metaInfo` token counts) come straight from the real call, so token usage / cost populate
 * exactly as they do for the legacy path.
 *
 * Logging is wrapped in a try/catch — a logging failure is reported via [Console.error] but never
 * breaks agent execution.
 *
 * Only [execute] (the call the [ai.koog.agents.core.agent.AIAgent] uses) is intercepted; every
 * other [LLMClient] method delegates straight through.
 *
 * @param delegate The real Koog LLM client to delegate every call to.
 * @param logger The session logger that writes the `TrailblazeLlmRequestLog`.
 * @param session The current Trailblaze session the log is attributed to.
 * @param trailblazeLlmModel The model used (for cost / token-breakdown computation).
 * @param objective The objective driving this agent run (the `instructions` shown on the log card).
 * @param screenStateProvider Captures the current screen for the per-request view hierarchy +
 *   annotated screenshot. Mirrors the legacy path's [PromptStepStatus.screenStateProvider].
 * @param traceId The step trace id; reused so each LLM request links to the tool calls it triggers.
 */
class LoggingLlmClient(
  private val delegate: LLMClient,
  private val logger: TrailblazeLogger,
  private val session: TrailblazeSession,
  private val trailblazeLlmModel: TrailblazeLlmModel,
  private val objective: String,
  private val screenStateProvider: () -> ScreenState,
  private val traceId: TraceId,
) : LLMClient() {

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant {
    val startTime = Clock.System.now()
    val response = delegate.execute(prompt = prompt, model = model, tools = tools)
    logLlmRequest(
      requestMessages = prompt.messages,
      response = response,
      toolDescriptors = tools,
      startTime = startTime,
    )
    return response
  }

  /**
   * Emits a [TrailblazeLog.TrailblazeLlmRequestLog] for one [execute] round-trip. Mirrors
   * [xyz.block.trailblaze.agent.DirectMcpAgent]'s logging block. Never throws — a logging error is
   * reported and swallowed so it can't break the agent run.
   */
  private fun logLlmRequest(
    requestMessages: List<Message>,
    response: Message.Assistant,
    toolDescriptors: List<ToolDescriptor>,
    startTime: kotlinx.datetime.Instant,
  ) {
    try {
      val stepStatus = PromptStepStatus(
        promptStep = DirectionStep(step = objective, recording = null),
        screenStateProvider = screenStateProvider,
      )
      // Capture the current screen for the per-request view hierarchy + annotated screenshot.
      stepStatus.prepareNextStep()

      logger.logLlmRequest(
        session = session,
        koogLlmRequestMessages = requestMessages,
        stepStatus = stepStatus,
        trailblazeLlmModel = trailblazeLlmModel,
        response = response,
        startTime = startTime,
        traceId = traceId,
        toolDescriptors = toolDescriptors,
        requestContext = TrailblazeLog.LlmRequestContext(
          agentImplementation = AgentImplementation.KOOG_STRATEGY_GRAPH,
          llmCallStrategy = LlmCallStrategy.DIRECT,
        ),
        // null → logLlmRequest extracts token counts from response.metaInfo and the
        // CachedTokenCaptureInterceptor, exactly like every other DIRECT-path caller.
        tokenUsage = null,
        llmRequestLabel = "Koog Strategy Graph",
      )
    } catch (e: Exception) {
      // Log errors but don't fail the agent execution.
      Console.error("[KoogStrategyGraphAgent] Failed to log LLM request: ${e.message}")
      Console.error(e.stackTraceToString())
    }
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools)

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = delegate.moderate(prompt = prompt, model = model)

  override fun close() = delegate.close()
}
