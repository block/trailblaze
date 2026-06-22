package xyz.block.trailblaze.mcp.agent

import ai.koog.prompt.executor.clients.LLMClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.PromptStep

/**
 * Adapter that exposes the Koog strategy-graph agent behind the [TestAgentRunner] interface, so the
 * `KOOG_STRATEGY_GRAPH` agent participates in the **same** per-step trail loop as the legacy
 * [xyz.block.trailblaze.agent.TrailblazeRunner] and [xyz.block.trailblaze.agent.MultiAgentV3Runner].
 *
 * ## Why this exists — uniform deterministic replay
 *
 * Trailblaze's value prop is "blaze once, trail forever": a recorded prompt step replays its captured
 * tool sequence with **zero LLM calls**, and only unrecorded steps need an agent. That replay lives in
 * [xyz.block.trailblaze.rules.TrailblazeRunnerUtil.runPromptSuspend] and is **agent-agnostic** — it
 * replays when a recording exists and only delegates the *unrecorded* step to whatever [TestAgentRunner]
 * is configured. By implementing that interface (instead of the earlier item-level "run the whole block
 * live through Koog" bypass), KOOG gets deterministic replay for free and handles recordings identically
 * to every other agent. The agent is purely the natural-language brain for unrecorded steps.
 *
 * Each call delegates to [runPromptsWithKoogStrategyGraph] with a **single** step — the Koog graph's own
 * minimal-context pruning (latest-screen-only) means there's negligible loss versus running a whole block
 * as one conversation, and it matches how the legacy/V3 runners are driven per-step.
 *
 * @param agent the driver agent (any [BaseTrailblazeAgent]) that executes tools against its device.
 * @param toolRepo the session tool repo the Koog graph builds its registry from.
 * @param screenStateProvider live screen state (the agent's perception + `{{device_description}}`).
 * @param elementComparator selector/element matching used by the driver dispatch.
 * @param llmClient the Koog LLM client for completions.
 * @param trailblazeLlmModel the model to use (and for cost/token reporting).
 * @param logger session logger for the per-request `TrailblazeLlmRequestLog`.
 * @param sessionProvider resolves the active session at call time (it may not exist at construction).
 * @param maxLlmCalls optional per-step iteration cap.
 * @param systemPromptTemplate the platform system prompt template (rendered + augmented by the helper);
 *   [appendToSystemPrompt] appends trail `config.context` to it, matching the legacy runner.
 */
class KoogTestAgentRunner(
  private val agent: BaseTrailblazeAgent,
  private val toolRepo: TrailblazeToolRepo,
  override val screenStateProvider: () -> ScreenState,
  private val elementComparator: ElementComparator,
  private val llmClient: LLMClient,
  private val trailblazeLlmModel: TrailblazeLlmModel,
  private val logger: TrailblazeLogger,
  private val sessionProvider: () -> TrailblazeSession,
  private val maxLlmCalls: Int? = null,
  systemPromptTemplate: String,
) : TestAgentRunner {

  /** Mutable so trail `config.context` can be folded in via [appendToSystemPrompt], like the legacy runner. */
  private var currentSystemPrompt: String = systemPromptTemplate

  override fun run(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus = runBlocking { runSuspend(prompt, stepStatus) }

  override suspend fun runSuspend(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus = blaze(prompt)

  /**
   * Self-heal entry: a recorded replay failed and the trail asked to recover. KOOG simply re-blazes the
   * step against the *current* screen (where the recording left off). The [recordingResult] action
   * history isn't replayed into the prompt — Koog's perception is the live screen, not a transcript.
   */
  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus = runBlocking { blaze(promptStep) }

  override fun appendToSystemPrompt(context: String) {
    currentSystemPrompt = currentSystemPrompt + "\n" + context
  }

  private suspend fun blaze(prompt: PromptStep): AgentTaskStatus {
    val startTime = Clock.System.now()
    // Emit the objective lifecycle logs the AI path is responsible for — the shared per-step loop
    // (TrailblazeRunnerUtil) only emits these on the RECORDED branch and delegates the unrecorded
    // (AI) branch to the agent, exactly as the legacy TrailblazeRunner does. Without this pair,
    // report/progress builders (which pair Start↔Complete) and recording-generation step grouping
    // can't segment a KOOG-blazed step.
    val session = sessionProvider()
    logger.log(
      session,
      TrailblazeLog.ObjectiveStartLog(promptStep = prompt, session = session.sessionId, timestamp = startTime),
    )
    // One trace id per step so this step's LLM request and every tool call it triggers share it —
    // otherwise runTrailblazeTools generates a fresh id per dispatch and report/storyboard code that
    // joins LLM + tool activity by traceId can't correlate them (or mark the actions AI-generated).
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL)
    var status: AgentTaskStatus? = null
    try {
      val result = runPromptsWithKoogStrategyGraph(
        promptSteps = listOf(prompt),
        agent = agent,
        toolRepo = toolRepo,
        screenStateProvider = screenStateProvider,
        elementComparator = elementComparator,
        llmClient = llmClient,
        trailblazeLlmModel = trailblazeLlmModel,
        logger = logger,
        session = session,
        traceId = traceId,
        maxLlmCalls = maxLlmCalls,
        systemPromptTemplate = currentSystemPrompt,
      )
      status = result.toAgentTaskStatus(prompt, startTime)
      return status
    } finally {
      // Always close the objective lifecycle so the ObjectiveStartLog above is never left dangling
      // for report/progress builders that pair Start↔Complete — even if the run THREW (e.g.
      // max-iterations or an LLM error). On a throw `status` is still null, so synthesize a failed
      // one. (The legacy AI path skips its complete log on a throw; this is strictly better.)
      val completeStatus = status ?: AgentTaskStatus.Failure.ObjectiveFailed(
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = prompt.prompt,
          callCount = 0,
          taskStartTime = startTime,
          totalDurationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
        ),
        llmExplanation = "Koog strategy graph ended without reporting a status (threw before completion)",
      )
      logger.log(
        session,
        TrailblazeLog.ObjectiveCompleteLog(
          promptStep = prompt,
          objectiveResult = completeStatus,
          session = session.sessionId,
          timestamp = Clock.System.now(),
        ),
      )
    }
  }

  private fun TrailblazeToolResult.toAgentTaskStatus(
    prompt: PromptStep,
    startTime: kotlinx.datetime.Instant,
  ): AgentTaskStatus {
    val statusData = AgentTaskStatusData(
      taskId = TaskId.generate(),
      prompt = prompt.prompt,
      // callCount is the legacy runner's per-step LLM round count; the Koog graph runs its own
      // internal loop and we don't surface a count here, so 0. This does NOT affect cost/token
      // reporting — those come from the per-request TrailblazeLlmRequestLog the LoggingLlmClient
      // emits inside runPromptsWithKoogStrategyGraph, independent of statusData.
      callCount = 0,
      taskStartTime = startTime,
      totalDurationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
    )
    return toKoogAgentTaskStatus(statusData)
  }
}

/**
 * Pure mapping from a step's [TrailblazeToolResult] to an [AgentTaskStatus], factored out of
 * [KoogTestAgentRunner] so it's unit-testable without running the graph. A [TrailblazeToolResult.Success]
 * becomes [AgentTaskStatus.Success.ObjectiveComplete]; any [TrailblazeToolResult.Error] becomes
 * [AgentTaskStatus.Failure.ObjectiveFailed] carrying the error message. (The Success/Error decision
 * itself is made upstream by `resolveKoogObjectiveResult`.)
 */
internal fun TrailblazeToolResult.toKoogAgentTaskStatus(statusData: AgentTaskStatusData): AgentTaskStatus =
  when (this) {
    is TrailblazeToolResult.Success -> AgentTaskStatus.Success.ObjectiveComplete(
      statusData = statusData,
      llmExplanation = message ?: "Koog strategy graph completed the objective",
    )
    is TrailblazeToolResult.Error -> AgentTaskStatus.Failure.ObjectiveFailed(
      statusData = statusData,
      llmExplanation = errorMessage,
    )
  }
