package xyz.block.trailblaze.mcp.agent

import ai.koog.prompt.executor.clients.LLMClient
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.PromptStep

/**
 * Runs a block of prompt steps through the in-process [KoogStrategyGraphAgent], driver-agnostically.
 *
 * This is the shared seam behind [xyz.block.trailblaze.mcp.AgentImplementation.KOOG_STRATEGY_GRAPH].
 * It was originally inlined in the web (`BasePlaywrightNativeTest`) runner; it's factored out here
 * so every host driver — web (Playwright), Revyl cloud (Android + iOS), and on-device Android RPC —
 * can opt into the Koog strategy-graph agent through the same code by passing its own [agent],
 * [screenStateProvider], [toolRepo], etc. The default agent ([AgentImplementation.TRAILBLAZE_RUNNER])
 * never reaches this function, so wiring a new driver in is a behavior-neutral addition.
 *
 * The objective is the prompt steps' text joined on newlines (a multi-prompt block becomes a single
 * multi-line objective, matching how an author reads it). Tool execution routes through
 * [agent].runTrailblazeTools via the executor-aware [TrailblazeToolRepo.asToolRegistry] overload, so
 * every tool the graph calls runs through the same driver dispatch (settle, node-selector
 * enrichment, session logging) the legacy runner uses — only the reasoning loop differs.
 *
 * Returns a [TrailblazeToolResult] so the caller treats Koog like any other item result. A thrown
 * exception (LLM error, max-iterations stall, tool failure) propagates to the caller's existing
 * try/catch, which maps it to a failed session.
 *
 * @param agent the driver agent (any [BaseTrailblazeAgent]) that executes tools against its device.
 * @param screenStateProvider returns the live screen state (its `viewHierarchyTextRepresentation` is
 *   what the agent perceives; its device fields render the `{{device_description}}` placeholder).
 * @param systemPromptTemplate the platform's system prompt template (with `{{device_description}}`).
 * @param traceId optional step trace id; a fresh one is generated when null.
 * @param maxLlmCalls optional iteration cap; defaults to [KoogStrategyGraphAgent.DEFAULT_MAX_ITERATIONS].
 */
suspend fun runPromptsWithKoogStrategyGraph(
  promptSteps: List<PromptStep>,
  agent: BaseTrailblazeAgent,
  toolRepo: TrailblazeToolRepo,
  screenStateProvider: () -> ScreenState,
  elementComparator: ElementComparator,
  llmClient: LLMClient,
  trailblazeLlmModel: TrailblazeLlmModel,
  logger: TrailblazeLogger,
  session: TrailblazeSession,
  traceId: TraceId?,
  maxLlmCalls: Int?,
  systemPromptTemplate: String,
  onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
): TrailblazeToolResult {
  val objective = promptSteps.joinToString(separator = "\n") { it.prompt }
  onStepProgress?.invoke(1, 1, objective)

  // The agent reports completion via objectiveStatus(COMPLETED/FAILED) — mirroring the legacy
  // DirectMcpAgent. Capture that outcome here so the run RESULT reflects it: a FAILED objective
  // fails the step rather than passing hollowly when the agent stops early.
  // These are written by the toolDispatcher below and read after run() returns. No synchronization
  // is needed because Koog's strategy graph dispatches tools serially within a single run() — there
  // is never more than one toolDispatcher invocation in flight at a time.
  var objectiveStatusOutcome: Status? = null
  var objectiveExplanation: String? = null
  // Loop awareness: the prune/compress machinery hides the agent's own repeated actions from it,
  // so it can't tell when it's stuck. This tracker watches consecutive identical tool dispatches
  // and, past a threshold, surfaces a nudge back to the agent via the tool result (signal only —
  // the LLM decides whether to change approach or report FAILED). Fresh per objective.
  val progressTracker = KoogProgressTracker(KoogProgressTracker.resolveThresholdFromEnv())
  // Per-call dispatcher: routes each Koog tool call through the driver agent (driver-correct
  // execution + logging), then returns the tool result PLUS the FRESH post-action screen so the
  // LLM perceives the latest state via Koog's native tool-result channel. The prune node in
  // KoogStrategyGraphAgent then keeps only this latest screen in the prompt (older ones stripped),
  // honoring the minimal-context value prop: exactly one view hierarchy is ever sent.
  val toolDispatcher: suspend (TrailblazeTool) -> String = { tool ->
    if (tool is ObjectiveStatusTrailblazeTool) {
      // objectiveStatus is a non-executable control-flow marker — running it through
      // runTrailblazeTools throws "Unhandled Trailblaze tool". Instead capture the outcome (for the
      // run result), log it as a TrailblazeToolLog via logToolExecution (so completion shows up in
      // the session like the legacy path), and return a summary to the LLM.
      objectiveStatusOutcome = tool.status
      objectiveExplanation = tool.explanation
      try {
        agent.logToolExecution(
          tool = tool,
          timeBeforeExecution = Clock.System.now(),
          traceId = traceId ?: TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
          result = TrailblazeToolResult.Success(message = tool.explanation),
        )
      } catch (e: Exception) {
        Console.log("[KOOG] failed to log objectiveStatus tool: ${e.message}")
      }
      "Recorded objectiveStatus=${tool.status}: ${tool.explanation}"
    } else {
      val result = agent.runTrailblazeTools(
        tools = listOf(tool),
        traceId = traceId,
        screenState = screenStateProvider(),
        elementComparator = elementComparator,
        screenStateProvider = screenStateProvider,
      ).result
      val screen = screenStateProvider().viewHierarchyTextRepresentation
      // Observe this dispatch for loop detection; a non-null nudge is prepended so it's the first
      // thing the agent reads in the result.
      val loopNudge = progressTracker.observe(tool.toString())
      buildString {
        if (loopNudge != null) {
          append(loopNudge)
          append("\n\n")
        }
        append("Executed ${tool::class.simpleName}: $result")
        if (!screen.isNullOrBlank()) {
          append("\n\nCurrent screen:\n")
          append(screen)
        }
      }
    }
  }
  val toolRegistry = toolRepo.asToolRegistry(
    toolDispatcher = toolDispatcher,
    // Only consulted for dynamic (subprocess-MCP) tools. Build a fresh context per call so any such
    // tool still sees live screen state.
    trailblazeToolContextProvider = {
      agent.buildKoogToolExecutionContext(
        traceId = traceId,
        screenStateProvider = screenStateProvider,
      )
    },
  )

  // Wrap the real LLM client so every Koog `execute(...)` call emits a TrailblazeLlmRequestLog
  // (token usage / cost, prompt + response messages, toolOptions) at parity with the legacy
  // runner — the AIAgent calls the client directly, bypassing TrailblazeLogger.logLlmRequest.
  val loggingLlmClient = LoggingLlmClient(
    delegate = llmClient,
    logger = logger,
    session = session,
    trailblazeLlmModel = trailblazeLlmModel,
    objective = objective,
    screenStateProvider = screenStateProvider,
    // Reuse the step trace id so each LLM request links to the tool calls it triggers.
    traceId = traceId ?: TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
  )

  // Render the system prompt the same way the legacy runner does — the template contains a
  // {{device_description}} placeholder, so passing it raw would leak the literal token to the LLM.
  // Mirrors TrailblazeKoogLlmClientHelper.buildDeviceDescription (classifiers/platform + dimensions);
  // rendered once from the run-start screen state (device size is static per run).
  val koogDeviceDescription = screenStateProvider().let { ss ->
    val classifiers = ss.deviceClassifiers
    val platform = ss.trailblazeDevicePlatform.displayName
    val classifierList = classifiers.joinToString(", ") { it.classifier }
    val firstClassifier = classifiers.firstOrNull()?.classifier
    val devicePart = if (firstClassifier != null && !firstClassifier.equals(platform, ignoreCase = true)) {
      "$classifierList ($platform)"
    } else {
      classifierList.ifEmpty { platform }
    }
    "$devicePart - ${ss.deviceWidth}x${ss.deviceHeight}"
  }
  // Append completion guidance so the agent confirms ALL goals are met and reports status via
  // objectiveStatus before ending (instead of stopping after a partial step).
  val koogSystemPrompt = TemplatingUtil.renderTemplate(
    template = systemPromptTemplate,
    values = mapOf("device_description" to koogDeviceDescription),
  ) +
    "\n\n## Reporting completion\n" +
    "Keep working until EVERY goal in the objective is met and verified — do not stop after a " +
    "partial step. When all goals are complete, call `objectiveStatus` with status=COMPLETED and " +
    "a brief explanation, then stop. If the objective cannot be completed after reasonable " +
    "attempts, call `objectiveStatus` with status=FAILED and explain why. Always report status " +
    "via objectiveStatus before ending."
  val koogAgent = KoogStrategyGraphAgent.createInProcess(
    llmClient = loggingLlmClient,
    llmModel = trailblazeLlmModel,
    toolRegistry = toolRegistry,
    systemPrompt = koogSystemPrompt,
    maxAgentIterations = maxLlmCalls ?: KoogStrategyGraphAgent.DEFAULT_MAX_ITERATIONS,
  )
  return try {
    val finalMessage = koogAgent.run(objective)
    // The Koog graph forces a tool call every turn and can only finish via the `objectiveStatus`
    // tool (no free-text termination), matching the legacy runner. So by the time run() returns,
    // the dispatcher has already captured the COMPLETED/FAILED outcome and logged it.
    resolveKoogObjectiveResult(
      outcome = objectiveStatusOutcome,
      explanation = objectiveExplanation,
      finalMessage = finalMessage,
    )
  } finally {
    // Suspend close in a finally (not `use { }`): close() is suspend, so wrapping the underlying
    // AIAgent.close() in runBlocking from this coroutine could deadlock a single-threaded dispatcher.
    koogAgent.close()
  }
}

/**
 * Maps the agent's terminal `objectiveStatus` outcome to a [TrailblazeToolResult]. Pure (no Koog /
 * IO) so it's unit-testable independently of running the graph.
 *
 * - `null` (the agent finished WITHOUT calling objectiveStatus — structurally shouldn't happen,
 *   since the forced-tool graph only ends via objectiveStatus or a max-iterations throw): treated
 *   as a failure rather than a hollow pass.
 * - [Status.FAILED]: fails the step.
 * - [Status.COMPLETED] / [Status.IN_PROGRESS] (and any other non-FAILED status): success.
 */
internal fun resolveKoogObjectiveResult(
  outcome: Status?,
  explanation: String?,
  finalMessage: String,
): TrailblazeToolResult {
  val message = explanation ?: finalMessage
  return when (outcome) {
    null -> TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Agent finished without reporting objectiveStatus — treating as failed " +
        "rather than a hollow pass. Final message: $finalMessage",
    )
    Status.FAILED -> TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Objective reported FAILED by the agent: $message",
    )
    else -> TrailblazeToolResult.Success(message = message)
  }
}
