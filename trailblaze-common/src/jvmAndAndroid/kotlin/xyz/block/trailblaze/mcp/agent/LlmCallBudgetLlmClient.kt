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
import xyz.block.trailblaze.decision.AnsweredWithoutLlm
import xyz.block.trailblaze.exception.MaxCallsLimitReachedException
import xyz.block.trailblaze.util.Console
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enforces the per-objective **LLM-call** budget at the one seam every model request passes through.
 *
 * Koog's own `maxAgentIterations` counts graph *node* executions — every hop, including tool
 * execution and the in-memory prune pass — so a graph with three nodes per LLM turn burns that
 * limit three times faster than the number reads, and the ratio changes every time a node is added.
 * This decorator counts LLM calls instead: the request that would exceed [maxLlmCalls] throws
 * [MaxCallsLimitReachedException] *before* it reaches the model, and the session manager maps that
 * exception to `SessionStatus.Ended.MaxCallsLimitReached`.
 *
 * Every generation request counts — including the history-compression summarization call — because
 * the budget exists to bound cost and a summarization round-trip costs the same as a reasoning one.
 * [moderate] is not counted: it is not a generation, and the agent never calls it.
 *
 * A move the decision engine made instead ([AnsweredWithoutLlm]) spends a separate budget of the same
 * size: engine moves are quick and cheap, and must not shorten the LLM's turns, but an engine stuck
 * repeating itself still ends the objective. It gives its LLM call back only when no LLM request was
 * sent for the turn; in race mode one was, and was dropped but usually still billed. A turn still
 * needs an LLM call left to start, since the engine may hand it to the LLM.
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
  private val engineMoves = AtomicInteger(0)

  /** Label carried by the exception so the session end status names the objective that ran out. */
  @Volatile
  private var objective: String = ""

  /** Requests a model answered for the current objective. */
  val llmCallsMade: Int get() = callsMade.get()

  /** Moves the decision engine made for the current objective, without a model. */
  val engineMovesMade: Int get() = engineMoves.get()

  /**
   * Starts a fresh budget for [objective]. The agent is built once per objective, but calling this
   * from `run(objective)` keeps the exception's label honest even if an agent is ever reused.
   */
  fun beginObjective(objective: String) {
    this.objective = objective
    callsMade.set(0)
    engineMoves.set(0)
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
    val response = delegate.execute(prompt, model, tools)
    if (AnsweredWithoutLlm.of(response) != null) {
      if (!AnsweredWithoutLlm.llmRequestSent(response)) callsMade.decrementAndGet()
      if (engineMoves.incrementAndGet() > maxLlmCalls) {
        Console.log("[KOOG_BUDGET] decision engine budget of $maxLlmCalls moves exhausted; failing the objective")
        throw MaxCallsLimitReachedException(
          maxCalls = maxLlmCalls,
          objectivePrompt = objective,
          message = "Decision engine move limit of $maxLlmCalls reached for objective: $objective",
        )
      }
    }
    return response
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
