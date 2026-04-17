package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.agent.util.toLlmResponseHistory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.exception.MaxCallsLimitReachedException
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * AI-powered test runner that executes prompts using LLM and tools.
 *
 * Uses explicit session management via sessionProvider for all logging operations.
 * The sessionProvider must be provided to enable logging.
 *
 * @property trailblazeLogger Stateless logger that requires explicit session for each log
 * @property sessionProvider Provides current session for logging operations (required)
 */
class TrailblazeRunner(
  val agent: TrailblazeAgent,
  override val screenStateProvider: () -> ScreenState,
  llmClient: LLMClient,
  val trailblazeLlmModel: TrailblazeLlmModel,
  private val maxSteps: Int = 50,
  private val trailblazeToolRepo: TrailblazeToolRepo,
  val trailblazeLogger: TrailblazeLogger,
  private val sessionProvider: TrailblazeSessionProvider,
  systemPromptTemplate: String? = null,
) : TestAgentRunner {

  private val tracingLlmClient: LLMClient = TracingLlmClient(llmClient)

  private var currentSystemPrompt: String = composeSystemPrompt(
    platformPrompt = systemPromptTemplate,
    toolSetCatalog = trailblazeToolRepo.toolSetCatalog,
  )

  /**
   * Tracks descriptions of previously completed steps across multiple run() calls.
   * This allows subsequent steps to include context about what was already accomplished,
   * so the LLM can reason about the current state relative to previous actions.
   */
  private val completedStepDescriptions = mutableListOf<String>()

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = tracingLlmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = trailblazeToolRepo,
  )

  private val llmClientHelper = TrailblazeKoogLlmClientHelper(
    systemPromptTemplate = currentSystemPrompt,
    trailblazeLlmModel = trailblazeLlmModel,
    llmClient = tracingLlmClient,
    elementComparator = elementComparator,
    toolRepo = trailblazeToolRepo,
    screenStateProvider = screenStateProvider,
  )

  override fun appendToSystemPrompt(context: String) {
    currentSystemPrompt = currentSystemPrompt + "\n" + context
    llmClientHelper.systemPromptTemplate = currentSystemPrompt
  }

  override fun run(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus = runBlocking {
    runSuspend(prompt, stepStatus)
  }

  /**
   * Suspend version of run() that properly handles coroutine cancellation.
   * Checks for cancellation at the start of each LLM loop iteration.
   */
  override suspend fun runSuspend(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus {
    logObjectiveStart(prompt)
    val stepToolStrategy = prompt.getToolStrategy()
    // Sliding window of recent tool call fingerprints (tool:args) for loop detection.
    // Capped at STUCK_IDENTICAL_ACTION_THRESHOLD entries to avoid unbounded growth.
    // Each entry represents one tool call from an LLM response — a single response may
    // contribute multiple entries when the LLM batches tool calls.
    val recentToolFingerprints = ArrayDeque<String>(STUCK_IDENTICAL_ACTION_THRESHOLD)
    do {
      TrailblazeTracer.trace("prepareNextStep", "agent") {
        stepStatus.prepareNextStep()
      }
      val requestStartTimeMs = Clock.System.now()

      val koogLlmRequestMessages: List<Message> = TrailblazeTracer.trace("createNextChatRequest", "agent") {
        llmClientHelper.createNextChatRequest(
          stepStatus = stepStatus,
          previouslyCompletedStepDescriptions = completedStepDescriptions,
        )
      }

      val traceId = TraceId.generate(TraceOrigin.LLM)

      val toolDescriptors = trailblazeToolRepo.getToolDescriptorsForStep(prompt)
      Console.appendInfo("  LLM ")
      val llmCallStartTime = Clock.System.now()
      val koogLlmResponseMessages: List<Message.Response> = coroutineScope {
        val dotJob = launch {
          while (true) {
            delay(1000)
            Console.appendInfo(".")
          }
        }
        try {
          llmClientHelper.callLlm(
            KoogLlmRequestData(
              messages = koogLlmRequestMessages,
              toolDescriptors = toolDescriptors,
              toolChoice = if (llmClientHelper.getShouldForceToolCall()) {
                LLMParams.ToolChoice.Required
              } else {
                LLMParams.ToolChoice.Auto
              },
            ),
          )
        } catch (e: LLMClientException) {
          throw TrailblazeException(
            "LLM call failed for ${trailblazeLlmModel.modelId} (${trailblazeLlmModel.trailblazeLlmProvider.id}) with Exception Message: ${e.message}",
            e
          )
        } finally {
          dotJob.cancel()
        }
      }
      val llmCallDuration = Clock.System.now() - llmCallStartTime

      val session = sessionProvider.invoke()
      trailblazeLogger.logLlmRequest(
        session = session,
        koogLlmRequestMessages = koogLlmRequestMessages,
        stepStatus = stepStatus,
        response = koogLlmResponseMessages,
        startTime = requestStartTimeMs,
        trailblazeLlmModel = trailblazeLlmModel,
        toolDescriptors = toolDescriptors,
        requestContext = TrailblazeLog.LlmRequestContext(
          agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
          llmCallStrategy = LlmCallStrategy.DIRECT,
          agentTier = null, // No tier concept in legacy YAML-based runner
        ),
        traceId = traceId,
        llmRequestLabel = "Screen Analyzer",
      )

      val llmCallSeconds = llmCallDuration.inWholeMilliseconds / 1000.0
      val llmCallTimeStr = if (llmCallSeconds % 1.0 == 0.0) {
        "${llmCallSeconds.toLong()}s"
      } else {
        "${"%.1f".format(llmCallSeconds)}s"
      }
      val toolNames = koogLlmResponseMessages
        .filterIsInstance<Message.Tool>()
        .joinToString(", ") { it.tool }
      val toolInfo = if (toolNames.isNotEmpty()) " -> $toolNames" else ""
      Console.info(" $llmCallTimeStr$toolInfo")
      TrailblazeTracer.trace("processToolMessages", "agent", mapOf("tools" to (toolInfo.removePrefix(" -> ")))) {
        stepToolStrategy.processToolMessages(
          llmResponses = koogLlmResponseMessages,
          stepStatus = stepStatus,
          agent = agent,
          helper = llmClientHelper,
          traceId = traceId,
        )
      }

      // --- Stuck detection: bail out early if the agent is looping ---
      // Track tool fingerprints (tool name + args) in a fixed-size sliding window.
      // If every entry in the window is identical, the agent is stuck and further
      // calls won't help. We only check fully identical actions (same tool AND same
      // args) to avoid false positives on legitimate sequences like tapping different
      // elements or entering repeated PIN digits.
      val toolCalls = koogLlmResponseMessages.filterIsInstance<Message.Tool>()
      for (toolCall in toolCalls) {
        val fingerprint = "${toolCall.tool}:${toolCall.content}"
        if (recentToolFingerprints.size >= STUCK_IDENTICAL_ACTION_THRESHOLD) {
          recentToolFingerprints.removeFirst()
        }
        recentToolFingerprints.addLast(fingerprint)
      }

      if (recentToolFingerprints.size >= STUCK_IDENTICAL_ACTION_THRESHOLD &&
        recentToolFingerprints.toSet().size == 1
      ) {
        val toolName = toolCalls.lastOrNull()?.tool ?: "unknown"
        Console.info(
          "  Agent stuck: $toolName repeated identically " +
            "$STUCK_IDENTICAL_ACTION_THRESHOLD times consecutively",
        )
        throw MaxCallsLimitReachedException(
          maxCalls = stepStatus.currentStep,
          objectivePrompt = "${stepStatus.promptStep.prompt} [stuck: $toolName repeated identically]",
        )
      }

      if (stepStatus.currentStep >= maxSteps) {
        val exception = MaxCallsLimitReachedException(
          maxCalls = maxSteps,
          objectivePrompt = stepStatus.promptStep.prompt,
        )
        throw exception
      }
    } while (!stepStatus.isFinished())

    logObjectiveComplete(stepStatus)

    // Record this step as completed so subsequent steps have context
    val status = stepStatus.currentStatus.value
    if (status is ObjectiveComplete) {
      completedStepDescriptions.add(prompt.prompt)
    }

    return status
  }

  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus {
    val session = sessionProvider.invoke()
    trailblazeLogger.logAttemptAiFallback(session, promptStep, recordingResult)
    val calculatedHistory = recordingResult.toLlmResponseHistory()
    val reconstructedStepStatus = PromptStepStatus(
      promptStep = promptStep,
      koogLlmResponseHistory = calculatedHistory,
      screenStateProvider = screenStateProvider,
    )
    return run(promptStep, reconstructedStepStatus)
  }

  /**
   * Runs a prompt step with AI and handles the result by throwing appropriate exceptions.
   * This centralizes the logic for converting AgentTaskStatus to exceptions.
   *
   * Note: MaxCallsLimitReachedException is thrown directly in the run() method when the limit is reached,
   * so it will never be returned as a status from this method.
   *
   * @param prompt The prompt step to run
   * @param recordingResult If provided, will attempt recovery from a failed recording; otherwise runs normally
   * @throws TrailblazeException if the prompt fails
   */
  fun runAndHandleStatus(
    prompt: PromptStep,
    recordingResult: PromptRecordingResult.Failure? = null,
  ) {
    val status = if (recordingResult != null) {
      recover(prompt, recordingResult)
    } else {
      run(prompt)
    }

    when (status) {
      is ObjectiveComplete -> return
      else -> throw TrailblazeException("Failed to successfully run prompt with AI $prompt")
    }
  }

  private fun logObjectiveStart(prompt: PromptStep) {
    val session = sessionProvider.invoke()
    trailblazeLogger.log(
      session,
      TrailblazeLog.ObjectiveStartLog(
        promptStep = prompt,
        session = session.sessionId,
        timestamp = Clock.System.now(),
      ),
    )
  }

  private fun logObjectiveComplete(stepStatus: PromptStepStatus) {
    val session = sessionProvider.invoke()
    trailblazeLogger.log(
      session,
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = stepStatus.promptStep,
        objectiveResult = stepStatus.currentStatus.value,
        session = session.sessionId,
        timestamp = Clock.System.now(),
      ),
    )
  }

  private fun PromptStepStatus.toAgentTaskStatus() = AgentTaskStatusData(
    taskId = taskId,
    prompt = promptStep.prompt,
    callCount = maxSteps,
    taskStartTime = taskCreatedTimestamp,
    totalDurationMs = (Clock.System.now() - taskCreatedTimestamp).inWholeMilliseconds,
  )

  companion object {
    /**
     * Number of consecutive identical actions (same tool + same args) before
     * declaring the agent stuck. Set high enough to allow legitimate repetition
     * (e.g., entering repeated PIN digits) but low enough to catch true loops.
     */
    private const val STUCK_IDENTICAL_ACTION_THRESHOLD = 10

    val baseSystemPrompt = TemplatingUtil.getResourceAsText(
      "trailblaze_base_system_prompt.md",
    )!!

    val defaultPlatformPrompt = TemplatingUtil.getResourceAsText(
      "trailblaze_system_prompt.md",
    )!!

    /**
     * The base system prompt is always included. The platform/app-specific prompt
     * is appended after it. If no platform prompt is provided, the default mobile
     * prompt is used.
     *
     * When a [toolSetCatalog] is provided, the available toolset summary is inlined
     * into the prompt so the LLM knows what's available without an extra round trip.
     */
    fun composeSystemPrompt(
      platformPrompt: String? = null,
      toolSetCatalog: List<ToolSetCatalogEntry>? = null,
    ): String = buildString {
      append(baseSystemPrompt)
      append("\n\n")
      append(platformPrompt ?: defaultPlatformPrompt)
      if (toolSetCatalog != null) {
        append("\n\n")
        append(TrailblazeToolSetCatalog.formatCatalogSummary(toolSetCatalog).trimEnd())
      }
    }

    @Deprecated("Use composeSystemPrompt() instead", ReplaceWith("composeSystemPrompt()"))
    val defaultSystemPrompt: String get() = composeSystemPrompt()
  }
}

private fun PromptStep.getToolStrategy() = when (this) {
  is DirectionStep -> SingleToolStrategy()
  is VerificationStep -> MultipleToolStrategy()
}
