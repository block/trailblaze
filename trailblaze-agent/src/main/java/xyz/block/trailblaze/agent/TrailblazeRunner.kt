package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
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
  userObjectiveTemplate: String = defaultUserObjective,
  userMessageTemplate: String = defaultUserMessage,
) : TestAgentRunner {

  private val tracingLlmClient: LLMClient = TracingLlmClient(llmClient)

  private var currentSystemPrompt: String = systemPromptTemplate ?: defaultSystemPrompt

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = tracingLlmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = trailblazeToolRepo,
  )

  private val llmClientHelper = TrailblazeKoogLlmClientHelper(
    systemPromptTemplate = currentSystemPrompt,
    userObjectiveTemplate = userObjectiveTemplate,
    userMessageTemplate = userMessageTemplate,
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
    llmClientHelper.setForceStepStatusUpdate(false)
    val stepToolStrategy = prompt.getToolStrategy()
    do {
      stepStatus.prepareNextStep()
      val requestStartTimeMs = Clock.System.now()

      val koogLlmRequestMessages: List<Message> = llmClientHelper.createNextChatRequest(
        stepStatus = stepStatus,
      )

      val traceId = TraceId.generate(TraceOrigin.LLM)

      val toolDescriptors = trailblazeToolRepo.getToolDescriptorsForStep(prompt)
      val koogLlmResponseMessages: List<Message.Response> = try {
        // Call LLM directly (we're already in suspend context)
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
      }

      val session = sessionProvider.invoke()
      trailblazeLogger.logLlmRequest(
        session = session,
        koogLlmRequestMessages = koogLlmRequestMessages,
        stepStatus = stepStatus,
        response = koogLlmResponseMessages,
        startTime = requestStartTimeMs,
        trailblazeLlmModel = trailblazeLlmModel,
        toolDescriptors = toolDescriptors,
        traceId = traceId,
      )

      stepToolStrategy.processToolMessages(
        llmResponses = koogLlmResponseMessages,
        stepStatus = stepStatus,
        agent = agent,
        helper = llmClientHelper,
        traceId = traceId,
      )

      if (stepStatus.currentStep >= maxSteps) {
        // Create exception for logging and throwing
        val exception = MaxCallsLimitReachedException(
          maxCalls = maxSteps,
          objectivePrompt = stepStatus.promptStep.prompt,
        )
        // Session end is now handled by SessionManager in the calling context
        // Throw terminal exception to halt execution
        throw exception
      }
    } while (!stepStatus.isFinished())

    logObjectiveComplete(stepStatus)
    return stepStatus.currentStatus.value
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
    val defaultUserObjective = TemplatingUtil.getResourceAsText(
      "trailblaze_user_objective_template.md",
    )!!
    val defaultUserMessage = TemplatingUtil.getResourceAsText(
      "trailblaze_current_screen_user_prompt_template.md",
    )!!
    val defaultSystemPrompt = TemplatingUtil.getResourceAsText(
      "trailblaze_system_prompt.md",
    )!!
  }
}

private fun PromptStep.getToolStrategy() = when (this) {
  is DirectionStep -> SingleToolStrategy()
  is VerificationStep -> MultipleToolStrategy()
}
