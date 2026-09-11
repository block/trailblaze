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
import xyz.block.trailblaze.exception.MaxCallsLimitReachedException
import xyz.block.trailblaze.util.Console
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enforces the per-objective **LLM-call** budget at the one seam every model request passes through.
 *
 * Koog's own `maxAgentIterations` counts graph *node* executions — every hop, including tool
 * execution and the in-memory prune pass — so a graph with three nodes per LLM turn burns that
 * limit three times faster than the number reads, and the ratio changes every time a node is added.
 * The legacy runner's `TrailblazeRunner.DEFAULT_MAX_STEPS` counts LLM calls. This decorator makes the
 * strategy-graph agent count the same thing: the request that would exceed [maxLlmCalls] throws
 * [MaxCallsLimitReachedException] *before* it reaches the model, and the session manager already maps
 * that exception to `SessionStatus.Ended.MaxCallsLimitReached`, exactly as for the legacy runner.
 *
 * Every generation request counts — including the history-compression summarization call — because
 * the budget exists to bound cost and a summarization round-trip costs the same as a reasoning one.
 * [moderate] is not counted: it is not a generation, and the agent never calls it.
 *
 * Sits OUTERMOST in the decorator stack so a refused request never captures a screenshot or writes an
 * LLM-request log — nothing was sent, and the session's end status carries the reason.
 *
 * @param delegate The next client in the stack.
 * @param maxLlmCalls Number of requests allowed per objective. Must be positive.
 */
class LlmCallBudgetLlmClient(
  private val delegate: LLMClient,
  val maxLlmCalls: Int,
) : LLMClient() {

  init {
    require(maxLlmCalls > 0) { "maxLlmCalls must be positive, was $maxLlmCalls" }
  }

  private val callsMade = AtomicInteger(0)

  /** Label carried by the exception so the session end status names the objective that ran out. */
  @Volatile
  private var objective: String = ""

  /** Requests forwarded to [delegate] for the current objective. */
  val llmCallsMade: Int get() = callsMade.get()

  /**
   * Starts a fresh budget for [objective]. The agent is built once per objective, but calling this
   * from `run(objective)` keeps the exception's label honest even if an agent is ever reused.
   */
  fun beginObjective(objective: String) {
    this.objective = objective
    callsMade.set(0)
  }

  private fun reserveCall() {
    val next = callsMade.incrementAndGet()
    if (next > maxLlmCalls) {
      callsMade.decrementAndGet()
      Console.log("[KOOG_BUDGET] LLM call budget of $maxLlmCalls exhausted; failing the objective")
      throw MaxCallsLimitReachedException(maxCalls = maxLlmCalls, objectivePrompt = objective)
    }
  }

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant {
    reserveCall()
    return delegate.execute(prompt, model, tools)
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): LLMChoice {
    reserveCall()
    return delegate.executeMultipleChoices(prompt, model, tools)
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> {
    reserveCall()
    return delegate.executeStreaming(prompt, model, tools)
  }

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = delegate.moderate(prompt = prompt, model = model)

  override fun close() = delegate.close()
}
