package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.blaze.IterationStaleRefSummary
import xyz.block.trailblaze.agent.blaze.StaleRefTracker
import xyz.block.trailblaze.agent.blaze.buildStaleRefRecoveryMessage
import xyz.block.trailblaze.agent.blaze.detectActionCycleHint
import xyz.block.trailblaze.agent.blaze.detectDominantActionHint
import xyz.block.trailblaze.agent.blaze.summarizeIterationStaleRefs
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.agent.model.VerifyAssertionLedger
import xyz.block.trailblaze.agent.util.toLlmResponseHistory
import xyz.block.trailblaze.TrailblazeAgentContext
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
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
  private val maxSteps: Int = DEFAULT_MAX_STEPS,
  private val trailblazeToolRepo: TrailblazeToolRepo,
  val trailblazeLogger: TrailblazeLogger,
  private val sessionProvider: TrailblazeSessionProvider,
  systemPromptTemplate: String? = null,
  /**
   * When true, multi-target verify steps are auto-terminated as soon as every required
   * assertion (one per quoted target on a non-blank line) has been satisfied by a successful
   * verification tool call. This breaks the rotating-assertion loop where the LLM never emits
   * `objectiveStatus(COMPLETED)` and burns the full LLM-call budget until MAX_CALLS_REACHED.
   *
   * Defaults to the env-var-driven value for safety; flip on per-run via constructor wiring
   * once the structural fix has been canaried. Single-bullet verify steps and verify text
   * without quoted targets are unaffected — the ledger reports zero required targets and
   * `allSatisfied()` returns false, so behavior matches the legacy flow.
   */
  private val autoTerminateVerifySteps: Boolean = isAutoTerminateVerifyStepsEnabledByDefault(),
) : TestAgentRunner {

  private val tracingLlmClient: LLMClient = TracingLlmClient(llmClient)

  private var currentSystemPrompt: String = composeSystemPrompt(
    platformPrompt = systemPromptTemplate,
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
    // Attach the verify-step ledger before the LLM loop starts. The helper checks for it on
    // each tool response — null ledger means legacy behavior. Skipping when one is already
    // present preserves any ledger an upstream caller set manually (e.g., recover()).
    if (autoTerminateVerifySteps && prompt is VerificationStep && stepStatus.verifyAssertionLedger == null) {
      val ledger = VerifyAssertionLedger(verifyText = prompt.verify)
      stepStatus.attachVerifyAssertionLedger(ledger)
      Console.info(
        "  [VERIFY_AUTO_COMPLETE] Ledger attached: ${ledger.requiredTargets.size} parsed " +
          "target(s) + behavioral rotating-loop detection.",
      )
    }
    // Sliding window of recent tool call fingerprints (tool:args) for loop detection.
    // Capped at STUCK_FINGERPRINT_WINDOW entries to avoid unbounded growth.
    // Each entry represents one tool call from an LLM response — a single response may
    // contribute multiple entries when the LLM batches tool calls.
    val recentToolFingerprints = ArrayDeque<String>(STUCK_FINGERPRINT_WINDOW)
    // Per-step tracker for the "stale-ref hallucination loop" where the LLM repeatedly
    // taps the same ref that no longer exists on the current screen. Reset on every
    // non-stale-ref tool outcome so we only fire on *consecutive* failures. See
    // [StaleRefRecovery.kt] for the failure-mode forensics.
    val staleRefTracker = StaleRefTracker()
    do {
      TrailblazeTracer.trace("prepareNextStep", "agent") {
        stepStatus.prepareNextStep()
      }
      val requestStartTimeMs = Clock.System.now()

      // Filter `sensitiveKeys` (e.g. PINs, passwords) before exposing memory to the LLM —
      // matches the guard already applied by ScriptTrailblazeTool.buildInput and
      // TrailblazeContextEnvelope. Sensitive values remain available for ${var} interpolation
      // in tool args, just not surfaced into the LLM's per-step reminder text.
      // Bound trail args join the same reminder keyed by token spelling (`args.<name>`) — prompt
      // text is never interpolated, so this list is how the LLM resolves a literal `{{args.x}}`
      // in an objective, exactly as it resolves `{{memory.x}}` today.
      val rememberedValues = (agent as? TrailblazeAgentContext)?.memory?.let { mem ->
        mem.variables.filterKeys { it !in mem.sensitiveKeys } + mem.argsForLlmContext()
      } ?: emptyMap()
      val koogLlmRequestMessages: List<Message> = TrailblazeTracer.trace("createNextChatRequest", "agent") {
        llmClientHelper.createNextChatRequest(
          stepStatus = stepStatus,
          previouslyCompletedStepDescriptions = completedStepDescriptions,
          rememberedValues = rememberedValues,
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
      val koogLlmResponseMessages: Message.Assistant = coroutineScope {
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
      val toolNames = koogLlmResponseMessages.parts
        .filterIsInstance<MessagePart.Tool.Call>()
        .joinToString(", ") { it.tool }
      val toolInfo = if (toolNames.isNotEmpty()) " -> $toolNames" else ""
      Console.info(" $llmCallTimeStr$toolInfo")
      TrailblazeTracer.trace("processToolMessages", "agent", mapOf("tools" to (toolInfo.removePrefix(" -> ")))) {
        stepToolStrategy.processToolMessages(
          llmResponse = koogLlmResponseMessages,
          stepStatus = stepStatus,
          agent = agent,
          helper = llmClientHelper,
          traceId = traceId,
        )
      }

      val toolCalls = koogLlmResponseMessages.parts.filterIsInstance<MessagePart.Tool.Call>()

      // --- Stale-ref recovery: catch the "keep tapping a ref that no longer exists" loop ---
      // [TapTrailblazeTool] / [AssertVisibleTrailblazeTool] validate the LLM's ref against the
      // live view hierarchy BEFORE dispatching, so a stale ref returns an error tool_result
      // with `callCount=0` (no underlying action ran). Chat-history truncation hides older
      // successful snapshot observations, so the LLM has no signal that its memorized refs
      // are stale and burns the entire LLM-call budget retrying. Forensic source: case_5380770.
      //
      // We scan the tool_result outputs from THIS iteration's response (just appended to
      // chat history by `processToolMessages`) for the stale-ref error pattern, key on the
      // ref name, and latch a recovery message after N consecutive hits on the same ref.
      // A successful (or different-error) call this iteration breaks the streak first via
      // [resetStreak] — even when stale-ref hits also land in the same response, because
      // [MultipleToolStrategy] (verification steps) can mix outcomes in one LLM turn and
      // any progress should clear the consecutive-failure counter.
      val iterationSummary = summarizeStaleRefsFromLastIteration(
        history = stepStatus.getLimitedHistory(),
        toolCallsCount = toolCalls.size,
      )
      if (iterationSummary.hadNonStaleRefResult) {
        staleRefTracker.resetStreak()
      }
      for (ref in iterationSummary.staleRefs) {
        if (staleRefTracker.recordStaleRef(ref)) {
          val recoveryMessage = buildStaleRefRecoveryMessage(
            ref = ref,
            repeatCount = staleRefTracker.currentCount,
          )
          stepStatus.setPendingStaleRefRecovery(recoveryMessage)
          Console.info(
            "  [STALE_REF_RECOVERY] Latching recovery message: ref='$ref' " +
              "consecutiveFailures=${staleRefTracker.currentCount}",
          )
          // Only fire once per iteration even if multiple stale-ref errors land in
          // the same response — the recovery message itself names the dominant ref.
          break
        }
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
      for (toolCall in toolCalls) {
        val fingerprint = "${toolCall.tool}:${stripAnalysisFromContent(toolCall.args)}"
        if (recentToolFingerprints.size >= STUCK_FINGERPRINT_WINDOW) {
          recentToolFingerprints.removeFirst()
        }
        recentToolFingerprints.addLast(fingerprint)
      }

      val cycleHint = detectActionCycleHint(recentToolFingerprints.toList())
        ?: detectDominantActionHint(recentToolFingerprints.toList())
      if (cycleHint != null) {
        if (cycleHint.startsWith("CRITICAL:")) {
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
        } else {
          // WARNING tier — latch onto the step status; the LLM helper consumes it on the next call.
          Console.info("  Stuck-detection: $cycleHint")
          stepStatus.setPendingCycleWarning(cycleHint)
        }
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
     * Default per-objective LLM call cap. The single source of truth — `RunYamlRequest.maxLlmCalls`
     * resolves to this when the caller didn't specify one, and host/on-device wiring reads it from
     * here so the in-process, daemon, and on-device paths agree.
     */
    const val DEFAULT_MAX_STEPS: Int = 50

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
     */
    fun composeSystemPrompt(
      platformPrompt: String? = null,
    ): String = buildString {
      append(baseSystemPrompt)
      append("\n\n")
      append(platformPrompt ?: defaultPlatformPrompt)
    }

    @Deprecated("Use composeSystemPrompt() instead", ReplaceWith("composeSystemPrompt()"))
    val defaultSystemPrompt: String get() = composeSystemPrompt()

    /**
     * Reads the verify-step auto-termination default from the `TRAILBLAZE_AUTO_TERMINATE_VERIFY_STEPS`
     * environment variable or the JVM system property of the same name. Accepts `1`/`true`/`yes`
     * (case-insensitive) as on; anything else is off. The runner's constructor flag overrides this
     * for callers that want explicit control (e.g., unit tests).
     */
    internal fun isAutoTerminateVerifyStepsEnabledByDefault(): Boolean {
      val envName = "TRAILBLAZE_AUTO_TERMINATE_VERIFY_STEPS"
      val raw = System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(envName)?.takeIf { it.isNotBlank() }
        ?: return false
      return raw.trim().lowercase() in setOf("1", "true", "yes")
    }
  }
}

private fun PromptStep.getToolStrategy() = when (this) {
  is DirectionStep -> SingleToolStrategy()
  is VerificationStep -> MultipleToolStrategy()
}

/**
 * Walks the tail of [history] looking at up to [toolCallsCount] most-recent
 * [MessagePart.Tool.Result] entries (the tool_results just appended for this iteration's
 * tool_calls) and classifies them into stale-ref refs vs other outcomes via
 * [summarizeIterationStaleRefs].
 *
 * Returns an empty summary when [toolCallsCount] is 0 (LLM emitted no tool calls) so the
 * caller treats a zero-call iteration as "no signal either way."
 *
 * Implementation note: the history window is post-truncation (`getLimitedHistory()`),
 * so newer entries always live at the tail. We only inspect the last [toolCallsCount]
 * Tool.Result entries — not the whole window — to avoid double-counting older stale-ref
 * errors that were already accounted for in previous iterations.
 */
private fun summarizeStaleRefsFromLastIteration(
  history: List<Message>,
  toolCallsCount: Int,
): IterationStaleRefSummary {
  if (toolCallsCount <= 0) {
    return IterationStaleRefSummary(emptyList(), hadNonStaleRefResult = false)
  }
  // Walk the history newest-first so we can take exactly the LAST `toolCallsCount`
  // Tool.Result entries (the ones the current iteration just produced) without scanning
  // the older windows. After taking those, restore chronological order so the
  // state-machine sees the same sequence the LLM actually emitted — the tracker's
  // streak counter is order-sensitive (a ref switch resets the count), so reversing
  // newest-first across the same iteration's mixed stale refs would distort fire
  // semantics for batched MultipleToolStrategy responses.
  val toolResults = history
    .asReversed()
    .asSequence()
    .filterIsInstance<Message.User>()
    .flatMap { it.parts.asSequence() }
    .filterIsInstance<MessagePart.Tool.Result>()
    .take(toolCallsCount)
    .toList()
    .reversed()
  return summarizeIterationStaleRefs(toolResults.map { it.output })
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
