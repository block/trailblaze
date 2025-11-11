package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Failure.MaxCallsLimitReached
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.agent.util.toLlmResponseHistory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep

class TrailblazeRunner(
  val agent: TrailblazeAgent,
  override val screenStateProvider: () -> ScreenState,
  llmClient: LLMClient,
  val trailblazeLlmModel: TrailblazeLlmModel,
  private val maxSteps: Int = 50,
  private val trailblazeToolRepo: TrailblazeToolRepo,
  val trailblazeLogger: TrailblazeLogger,
  val sessionManager: TrailblazeSessionManager = TrailblazeSessionManager(),
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
  )

  override fun appendToSystemPrompt(context: String) {
    currentSystemPrompt = currentSystemPrompt + "\n" + context
    llmClientHelper.systemPromptTemplate = currentSystemPrompt
  }

  override fun run(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus {
    logObjectiveStart(prompt)
    llmClientHelper.setForceStepStatusUpdate(false)
    val stepToolStrategy = prompt.getToolStrategy()
    do {
      // Check if session has been cancelled before making next LLM call
      val isCancelled = isSessionCancelled()
      if (isCancelled) {
        stepStatus.markAsFailed()
        throw TrailblazeSessionCancelledException()
      }

      stepStatus.prepareNextStep()
      val requestStartTimeMs = Clock.System.now()

      val koogLlmRequestMessages: List<Message> = llmClientHelper.createNextChatRequest(
        stepStatus = stepStatus,
      )

      val traceId = TraceId.generate(TraceOrigin.LLM)

      val toolDescriptors = trailblazeToolRepo.getToolDescriptorsForStep(prompt)
      val koogLlmResponseMessages: List<Message.Response> = runBlocking {
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
      }

      trailblazeLogger.logLlmRequest(
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
        return MaxCallsLimitReached(stepStatus.toAgentTaskStatus())
      }
    } while (!stepStatus.isFinished())

    logObjectiveComplete(stepStatus)
    return stepStatus.currentStatus.value
  }

  /**
   * Check if the current session has been cancelled.
   * The session manager is the single source of truth for cancellation state.
   */
  private fun isSessionCancelled(): Boolean = sessionManager.isCurrentSessionCancelled()

  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus {
    trailblazeLogger.logAttemptAiFallback(promptStep, recordingResult)
    val calculatedHistory = recordingResult.toLlmResponseHistory()
    val reconstructedStepStatus = PromptStepStatus(
      promptStep = promptStep,
      koogLlmResponseHistory = calculatedHistory,
      screenStateProvider = screenStateProvider,
    )
    return run(promptStep, reconstructedStepStatus)
  }

  private fun logObjectiveStart(prompt: PromptStep) {
    trailblazeLogger.log(
      TrailblazeLog.ObjectiveStartLog(
        promptStep = prompt,
        session = trailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  private fun logObjectiveComplete(stepStatus: PromptStepStatus) {
    trailblazeLogger.log(
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = stepStatus.promptStep,
        objectiveResult = stepStatus.currentStatus.value,
        session = trailblazeLogger.getCurrentSessionId(),
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
