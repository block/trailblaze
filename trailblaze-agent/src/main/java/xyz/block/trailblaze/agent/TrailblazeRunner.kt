package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Failure.MaxCallsLimitReached
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.PromptStep
import java.util.UUID

// TODO remove screen state from any functions since we have it in the constructor
class TrailblazeRunner(
  val agent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState,
  llmClient: LLMClient,
  val trailblazeLlmModel: TrailblazeLlmModel,
  private val maxSteps: Int = 50,
  private val trailblazeToolRepo: TrailblazeToolRepo,
  systemPromptTemplate: String? = null,
  userObjectiveTemplate: String = TemplatingUtil.getResourceAsText(
    "trailblaze_user_objective_template.md",
  )!!,
  userMessageTemplate: String = TemplatingUtil.getResourceAsText(
    "trailblaze_current_screen_user_prompt_template.md",
  )!!,
) : TestAgentRunner {

  private val tracingLlmClient: LLMClient = TracingLlmClient(llmClient)

  private var currentSystemPrompt: String = systemPromptTemplate ?: TemplatingUtil.getResourceAsText(
    "trailblaze_system_prompt.md",
  )!!

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = tracingLlmClient,
    trailblazeLlmModel = trailblazeLlmModel,
  )

  private val trailblazeKoogLlmClientHelper = TrailblazeKoogLlmClientHelper(
    systemPromptTemplate = currentSystemPrompt,
    userObjectiveTemplate = userObjectiveTemplate,
    userMessageTemplate = userMessageTemplate,
    trailblazeLlmModel = trailblazeLlmModel,
    llmClient = tracingLlmClient,
    elementComparator = elementComparator,
  )

  override fun appendToSystemPrompt(context: String) {
    currentSystemPrompt = currentSystemPrompt + "\n" + context
    trailblazeKoogLlmClientHelper.systemPromptTemplate = currentSystemPrompt
  }

  override fun run(prompt: PromptStep): AgentTaskStatus {
    TrailblazeLogger.log(
      TrailblazeLog.ObjectiveStartLog(
        promptStep = prompt,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
    val stepStatus = PromptStepStatus(
      promptStep = prompt,
    )
    trailblazeKoogLlmClientHelper.setForceStepStatusUpdate(false)
    val stepStartTime = Clock.System.now()
    var currentStep = 0
    do {
      val screenStateForLlmRequest = screenStateProvider()
      val requestStartTimeMs = Clock.System.now()

      val llmResponseId = UUID.randomUUID().toString()

      val toolRegistry = trailblazeToolRepo.asToolRegistry {
        TrailblazeToolExecutionContext(
          trailblazeAgent = agent as MaestroTrailblazeAgent,
          screenState = screenStateForLlmRequest,
          llmResponseId = llmResponseId,
        )
      }

      val koogAiRequestMessages: List<Message> = trailblazeKoogLlmClientHelper.createNextChatRequestKoog(
        limitedHistory = stepStatus.getLimitedHistory(),
        screenState = screenStateForLlmRequest,
        step = prompt,
        forceStepStatusUpdate = trailblazeKoogLlmClientHelper.getForceStepStatusUpdate(),
      )

      val toolDescriptors = trailblazeToolRepo.getToolDescriptorsForStep(prompt)
      val koogLlmResponseMessages: List<Message.Response> = runBlocking {
        trailblazeKoogLlmClientHelper.callLlm(
          KoogLlmRequestData(
            callId = llmResponseId,
            messages = koogAiRequestMessages,
            toolDescriptors = toolDescriptors,
            toolChoice = if (trailblazeKoogLlmClientHelper.getShouldForceToolCall()) {
              LLMParams.ToolChoice.Required
            } else {
              LLMParams.ToolChoice.Auto
            },
          ),
        )
      }

      val toolMessage: Message.Tool? = koogLlmResponseMessages.filterIsInstance<Message.Tool>().firstOrNull()
      val assistantMessage: Message.Assistant? = koogLlmResponseMessages
        .filterIsInstance<Message.Assistant>()
        .firstOrNull()
      println(toolMessage)

      TrailblazeLogger.logLlmRequest(
        agentTaskStatus = stepStatus.currentStatus.value,
        screenState = screenStateForLlmRequest,
        instructions = prompt.prompt,
        llmMessages = koogAiRequestMessages.map { messageFromHistory ->
          TrailblazeLlmMessage(
            role = messageFromHistory.role.name.lowercase(),
            message = messageFromHistory.content,
          )
        }.plus(
          TrailblazeLlmMessage(
            role = Message.Role.Assistant.name.lowercase(),
            message = koogLlmResponseMessages.filterIsInstance<Message.Assistant>().firstOrNull()?.content,
          ),
        ),
        response = koogLlmResponseMessages,
        startTime = requestStartTimeMs,
        llmRequestId = llmResponseId,
        trailblazeLlmModel = trailblazeLlmModel,
        toolDescriptors = toolDescriptors,
      )

      val llmMessage = assistantMessage?.content
      if (toolMessage != null) {
        trailblazeKoogLlmClientHelper.handleLlmResponse(
          toolRegistry = toolRegistry,
          llmMessage = llmMessage,
          toolName = toolMessage.tool,
          toolArgs = TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), toolMessage.content),
          llmResponseId = llmResponseId,
          step = stepStatus,
          screenStateForLlmRequest = screenStateForLlmRequest,
          agent = agent,
        )
      } else {
        println("[WARNING] No tool call detected - forcing tool call on next iteration")
        stepStatus.addEmptyToolCallToChatHistory(
          llmResponseContent = llmMessage,
          result = TrailblazeToolResult.Error.EmptyToolCall,
        )
        trailblazeKoogLlmClientHelper.setShouldForceToolCall(true)
      }

      if (currentStep >= maxSteps) {
        return MaxCallsLimitReached(
          statusData = AgentTaskStatusData(
            taskId = stepStatus.taskId,
            prompt = prompt.prompt,
            callCount = maxSteps,
            taskStartTime = stepStartTime,
            totalDurationMs = (Clock.System.now() - stepStartTime).inWholeMilliseconds,
          ),
        )
      } else {
        currentStep++
      }
    } while (!stepStatus.isFinished())

    TrailblazeLogger.log(
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = prompt,
        objectiveResult = stepStatus.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )

    return stepStatus.currentStatus.value
  }
}
