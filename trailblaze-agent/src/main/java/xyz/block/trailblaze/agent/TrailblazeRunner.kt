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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.blaze.detectActionCycleHint
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
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
    // Capped at STUCK_FINGERPRINT_WINDOW entries to avoid unbounded growth.
    // Each entry represents one tool call from an LLM response — a single response may
    // contribute multiple entries when the LLM batches tool calls.
    val recentToolFingerprints = ArrayDeque<String>(STUCK_FINGERPRINT_WINDOW)
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

      // Fail fast before the LLM call. A planner with zero tools is a critical misconfiguration
      // (toolset catalog discovery returned nothing, or a YAML tool migration broke registration) —
      // not something to paper over by relaxing tool_choice. Surface the root cause with an
      // actionable message instead of letting the provider return an opaque "tool_choice is only
      // allowed when tools are specified" 400.
      if (toolDescriptors.isEmpty()) {
        throw TrailblazeException(
          "No tool descriptors registered for step type ${prompt::class.simpleName}. " +
            "This is a critical misconfiguration — the LLM cannot act without tools. " +
            "Check toolset catalog discovery (TrailblazeToolSetCatalog.defaultEntries()) and " +
            "that YAML tool/toolset resources are reachable in this runtime (e.g. packaged as " +
            "Android assets when running on-device).",
        )
      }

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
              // Trailblaze is tools-only: every LLM response is a tool call. Never Auto,
              // never a text reply. Mixing messages and tools confuses models.
              toolChoice = LLMParams.ToolChoice.Required,
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
      // Track tool fingerprints (tool name + tool-specific args) in a fixed-size
      // sliding window and look for repeating cycles in the suffix. This catches
      // both consecutive identical actions (length-1 cycle) AND alternating loops
      // like tap(A) → tap(B) → tap(A) → tap(B) … (length-2), plus three-step cycles.
      //
      // Strip analysis-only fields (reasoning, screenSummary, confidence, etc.)
      // from the args before fingerprinting — those vary per call by design and
      // would otherwise mask a real loop where the underlying action is identical
      // but the LLM's free-text reasoning differs each time.
      val toolCalls = koogLlmResponseMessages.filterIsInstance<Message.Tool>()
      for (toolCall in toolCalls) {
        val fingerprint = "${toolCall.tool}:${stripAnalysisFromContent(toolCall.content)}"
        if (recentToolFingerprints.size >= STUCK_FINGERPRINT_WINDOW) {
          recentToolFingerprints.removeFirst()
        }
        recentToolFingerprints.addLast(fingerprint)
      }

      val cycleHint = detectActionCycleHint(recentToolFingerprints.toList())
      if (cycleHint != null && cycleHint.startsWith("CRITICAL:")) {
        Console.info("  Agent stuck: $cycleHint")
        // Surface the full pattern (tool + args + repeat count) in the exception so
        // post-mortem readers see *what* was looping, not just that something was. The
        // previous form collapsed to "[stuck: <toolname> in repeating cycle]" which
        // dropped the cycle pattern and left no breadcrumb for triaging.
        val cycleSummary = cycleHint.removePrefix("CRITICAL: ").trim()
        throw MaxCallsLimitReachedException(
          maxCalls = stepStatus.currentStep,
          objectivePrompt = "${stepStatus.promptStep.prompt} [stuck: $cycleSummary]",
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
     * Sliding window over the last N tool fingerprints, used by `detectActionCycleHint`
     * to spot repeating cycles (length 1, 2, or 3) in the suffix.
     *
     * Sized to fit any of the per-cycle-length CRITICAL thresholds in `ProgressTracking.kt`:
     * length-1 needs `LENGTH_1_CRITICAL_REPEATS = 30` entries to fire, length-2 needs
     * `LENGTH_2_CRITICAL_REPEATS = 15` × 2 = 30 entries, length-3 needs
     * `LENGTH_3_CRITICAL_REPEATS = 10` × 3 = 30 entries. So 30 is the smallest window
     * that lets every length reach its CRITICAL threshold without falling off the front.
     *
     * Memory cost is small: ~200 bytes per fingerprint × 30 = ~6 KB held during a
     * running step.
     */
    private const val STUCK_FINGERPRINT_WINDOW = 30

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

/**
 * Strips analysis-only fields ([ToolCallAnalysisResponse.fieldNames] — `reasoning`,
 * `screenSummary`, `confidence`, etc.) from a koog [Message.Tool.content] JSON
 * payload before it is fingerprinted for cycle detection. Those fields are written
 * fresh by the LLM on every call and would otherwise mask real loops where the
 * underlying tool action is identical but the surrounding rationale differs.
 *
 * Falls back to the raw content when the payload isn't a JSON object — the only
 * cost of a non-stripped fingerprint is potentially missing a cycle hint, which
 * is no worse than the pre-strip behaviour.
 */
private fun stripAnalysisFromContent(content: String): String = try {
  val parsed = TrailblazeJsonInstance.parseToJsonElement(content)
  if (parsed is JsonObject) {
    val analysisKeys = ToolCallAnalysisResponse.fieldNames
    JsonObject(parsed.filterKeys { it !in analysisKeys }).toString()
  } else {
    content
  }
} catch (_: SerializationException) {
  content
}
