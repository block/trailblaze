package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.agent.model.TrailblazePromptStep
import xyz.block.trailblaze.agent.util.LogHelper
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.LlmMessage
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.TemplatingUtil
import java.util.UUID

class TrailblazeRunner(
  val agent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState,
  val llmClient: LLMClient,
  val llmModel: LLModel,
  private val trailblazeToolRepo: TrailblazeToolRepo,
  private val systemPromptTemplate: String = TemplatingUtil.getResourceAsText(
    "trailblaze_system_prompt.md",
  )!!,
  private val userObjectiveTemplate: String = TemplatingUtil.getResourceAsText(
    "trailblaze_user_objective_template.md",
  )!!,
  private val userMessageTemplate: String = TemplatingUtil.getResourceAsText(
    "trailblaze_current_screen_user_prompt_template.md",
  )!!,
) : TestAgentRunner {

  private val trailblazeKoogLlmClientHelper = TrailblazeKoogLlmClientHelper(
    systemPromptTemplate = systemPromptTemplate,
    userObjectiveTemplate = userObjectiveTemplate,
    userMessageTemplate = userMessageTemplate,
    llmModel = this.llmModel,
    llmClient = llmClient,
  )

  override fun run(prompt: TrailblazePrompt): AgentTaskStatus {
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = prompt.steps.first().currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
    LogHelper.logPromptStart(prompt)
    prompt.steps.forEachIndexed { index, step ->
      println("\n[LOOP_STATUS] Objective: ${index + 1}/${prompt.steps.size}")
      // TODO: Separate Objective results from TestCase results
      when (val objectiveResult: AgentTaskStatus = run(step)) {
        is AgentTaskStatus.Success.ObjectiveComplete -> {
          // do nothing, move on to the next objective
        }

        is AgentTaskStatus.InProgress -> {
          throw IllegalStateException("In Progress should never be returned as the final objective result")
        }
        // In the failure cases return them to fail the entire test case
        else -> return objectiveResult
      }
    }
    // This is dumb but it's just returning the last status
    // If we get here we've processed through all of the objectives so all should have a status
    val exitStatus = prompt.steps.last().currentStatus.value
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = exitStatus,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
    return exitStatus
  }

  override fun run(step: TrailblazePromptStep): AgentTaskStatus {
    TrailblazeLogger.log(
      TrailblazeLog.ObjectiveStartLog(
        description = step.description,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
    trailblazeKoogLlmClientHelper.setForceStepStatusUpdate(false)
    do {
      println("\n[LOOP_STATUS] Status: ${step.currentStatus.value.javaClass.simpleName} | Call: ${step.getHistorySize() + 1}")
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

      // Limit message history to reduce context window
      val limitedHistory: List<Message> = step.getKoogLlmResponseHistory().takeLast(5) // Only keep recent messages

      val koogAiRequestMessages: List<Message> = trailblazeKoogLlmClientHelper.createNextChatRequestKoog(
        limitedHistory = limitedHistory,
        screenState = screenStateForLlmRequest,
        step = step,
        forceStepStatusUpdate = trailblazeKoogLlmClientHelper.getForceStepStatusUpdate(),
      )

      val koogLlmResponseMessages: List<Message.Response> = runBlocking {
        trailblazeKoogLlmClientHelper.callLlm(
          KoogLlmRequestData(
            callId = llmResponseId,
            messages = koogAiRequestMessages,
            toolDescriptors = trailblazeToolRepo.getCurrentToolDescriptors(),
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
        agentTaskStatus = step.currentStatus.value,
        screenState = screenStateForLlmRequest,
        instructions = step.fullPrompt,
        llmMessages = koogAiRequestMessages.map { messageFromHistory ->
          LlmMessage(
            role = messageFromHistory.role.name.lowercase(),
            message = messageFromHistory.content,
          )
        }.plus(
          LlmMessage(
            role = Message.Role.Assistant.name.lowercase(),
            message = koogLlmResponseMessages.filterIsInstance<Message.Assistant>().firstOrNull()?.content,
          ),
        ),
        response = koogLlmResponseMessages,
        startTime = requestStartTimeMs,
        llmRequestId = llmResponseId,
        llmModelId = llmModel.id,
      )

      val llmMessage = assistantMessage?.content
      if (toolMessage != null) {
        trailblazeKoogLlmClientHelper.handleLlmResponse(
          toolRegistry = toolRegistry,
          llmMessage = llmMessage,
          toolName = toolMessage.tool,
          toolArgs = TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), toolMessage.content),
          llmResponseId = llmResponseId,
          step = step,
          screenStateForLlmRequest = screenStateForLlmRequest,
          agent = agent,
        )
      } else {
        println("[WARNING] No tool call detected - forcing tool call on next iteration")
        step.addEmptyToolCallToChatHistory(
          llmResponseContent = llmMessage,
          result = TrailblazeToolResult.Error.EmptyToolCall,
        )
        trailblazeKoogLlmClientHelper.setShouldForceToolCall(true)
      }

      LogHelper.logStepStatus(step)
    } while (!step.isFinished())

    val exitStatus = step.currentStatus.value
    if (exitStatus is AgentTaskStatus.Failure) {
      println("[FAILURE] Type: ${exitStatus.javaClass.simpleName}")
    }

    TrailblazeLogger.log(
      TrailblazeLog.ObjectiveCompleteLog(
        description = step.description,
        objectiveResult = step.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )

    return step.currentStatus.value
  }

  fun handleTrailblazeToolForPrompt(
    trailblazeTool: TrailblazeTool,
    llmResponseId: String?,
    step: TrailblazePromptStep,
    screenStateForLlmRequest: ScreenState,
  ) {
    trailblazeKoogLlmClientHelper.handleTrailblazeToolForPrompt(
      trailblazeTool = trailblazeTool,
      llmResponseId = llmResponseId,
      step = step,
      screenStateForLlmRequest = screenStateForLlmRequest,
      agent = agent,
    )
  }
}
