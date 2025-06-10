package xyz.block.trailblaze.openai

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.agent.AgentTask
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.LlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveCompleteTrailblazeTool
import xyz.block.trailblaze.util.TemplatingUtil
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

class TrailblazeLenientRunner(
  private val agent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState,
  openAiApiKey: String,
  private val llmModel: LlmModel = LlmModel.GPT_4_1,
  private val trailblazeToolRepo: TrailblazeToolRepo = TrailblazeToolRepo(),
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

  private val openAi = OpenAI(
    token = openAiApiKey,
    timeout = Timeout(socket = 180.seconds),
    logging = LoggingConfig(logLevel = LogLevel.None),
  )

  /* This function accepts an instructional prompt which details the goal
  // of the agent. The agent will review the instructions, view hierarchy,
  // and historical actions to determine the appropriate actions to take
  // to achieve the instructions.
   */
  override fun run(instructions: String): AgentTaskStatus {
    val task = agent.setUpTask(instructions)

    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = task.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      ),
    )

    do {
      val screenStateForLlmRequest = screenStateProvider()
      val requestStartTimeMs = System.currentTimeMillis()
      val openAiRequest: ChatCompletionRequest = createNextChatRequest(
        screenState = screenStateForLlmRequest,
        task = task,
      )
      val openAiResponse: ChatCompletion = runBlocking {
        openAi.chatCompletion(openAiRequest)
      }
      val llmResponseId = openAiResponse.id
      task.increaseLlmCallCount()
      TrailblazeLogger.logLlmRequest(
        agentTaskStatus = task.currentStatus.value,
        screenState = screenStateForLlmRequest,
        instructions = instructions,
        request = openAiRequest,
        response = openAiResponse,
        startTime = requestStartTimeMs,
        llmRequestId = llmResponseId,
      )
      val (llmMessage, action) = parseResponse(openAiResponse)

      if (action != null) {
        val trailblazeTool: TrailblazeTool? = trailblazeToolRepo.toolCallToTrailblazeTool(action)
        val trailblazeToolResult = if (trailblazeTool == null) {
          unknownToolError(action)
        } else {
          if (trailblazeTool is ObjectiveCompleteTrailblazeTool) {
            println("### Have objective complete $trailblazeTool")
            val success = trailblazeTool.status == "completed"
            task.llmReportedCompletion(success, trailblazeTool.explanation)
            TrailblazeToolResult.Success
          } else {
            // Using -1 because the Maestro Command is immediately after and sometimes has the same timestamp
            val startTime = System.currentTimeMillis() - 1
            val (updatedTools, trailblazeToolResult) =
              agent.runTrailblazeTools(listOf(trailblazeTool), llmResponseId, screenStateForLlmRequest)
            for (tool in updatedTools) {
              TrailblazeLogger.log(
                TrailblazeLog.TrailblazeToolLog(
                  agentTaskStatus = task.currentStatus.value,
                  command = tool,
                  exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
                  successful = trailblazeToolResult == TrailblazeToolResult.Success,
                  duration = System.currentTimeMillis() - startTime,
                  timestamp = startTime,
                  instructions = instructions,
                  llmResponseId = llmResponseId,
                  session = TrailblazeLogger.getCurrentSessionId(),
                ),
              )
            }
            trailblazeToolResult
          }
        }

        task.addToolCall(
          llmResponseContent = llmMessage,
          function = action.function,
          commandResult = trailblazeToolResult,
        )
      } else {
        task.addEmptyToolCall(
          llmResponseContent = llmMessage,
          result = TrailblazeToolResult.Error.EmptyToolCall,
        )
        shouldForceToolCall = true
      }
    } while (!task.isFinished())

    // Log the final task completion status
    val taskCompletionTime = System.currentTimeMillis()
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = task.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = taskCompletionTime,
      ),
    )
    return task.currentStatus.value
  }

  private fun createNextChatRequest(
    screenState: ScreenState,
    task: AgentTask,
  ) = chatCompletionRequest {
    model = ModelId(llmModel.id)

    messages = toOpenAiChatMessages(
      screenState = screenState,
      instructions = task.instructions,
      previousLlmResponses = task.llmResponseHistory,
      task = task,
    )
    tools {
      // Register all tools normally
      trailblazeToolRepo.registerManualTools(this)
      toolChoice = getToolChoice()
    }
  }

  /**
   * Construct the messages to send to OpenAI
   */
  private fun toOpenAiChatMessages(
    screenState: ScreenState,
    instructions: String,
    previousLlmResponses: List<ChatMessage>,
    task: AgentTask,
  ): List<ChatMessage> = buildList {
    add(
      chatMessage {
        role = ChatRole.System
        content = systemPromptTemplate
      },
    )
    add(
      chatMessage {
        role = ChatRole.User
        content = TemplatingUtil.renderTemplate(
          template = userObjectiveTemplate,
          values = mapOf(
            "objective" to instructions,
          ),
        )
      },
    )

    addAll(previousLlmResponses)
    add(
      chatMessage {
        role = ChatRole.User
        content {
          text(
            TemplatingUtil.renderTemplate(
              template = userMessageTemplate,
              values = mapOf(
                "view_hierarchy" to Json.encodeToString<ViewHierarchyTreeNode>(screenState.viewHierarchy),
              ),
            ),
          )
          screenState.screenshotBytes?.let { screenshotBytes: ByteArray ->
            @OptIn(ExperimentalEncodingApi::class)
            val base64EncodedScreenshot = Base64.encode(screenshotBytes)
            image("data:image/png;base64,$base64EncodedScreenshot")
          }
        }
      },
    )
  }

  // This field will be used to determine whether or not our next request to the LLM should require
  // a tool call. This occasionally happens when the LLM returns the tool call in the message
  // instead of actually triggering the tool. In this case we already have the model's reasoning
  // so we can just force it to call a tool next.
  private var shouldForceToolCall = false
  private fun getToolChoice(): ToolChoice = if (shouldForceToolCall) {
    shouldForceToolCall = false
    ToolChoice.Mode("required")
  } else {
    ToolChoice.Auto
  }

  // Convenience function to pull out the LLMs content message and the action we should perform
  private fun parseResponse(openAiResponse: ChatCompletion): Pair<String?, ToolCall.Function?> {
    val firstOpenAiResponseMessage: ChatMessage = openAiResponse.choices.first().message
    val llmMessage = firstOpenAiResponseMessage.content
    val actions = firstOpenAiResponseMessage.toolCalls?.filterIsInstance<ToolCall.Function>() ?: emptyList()
    val action = actions.firstOrNull()
    return llmMessage to action
  }

  private fun unknownToolError(action: ToolCall.Function): TrailblazeToolResult {
    val function = action.function
    val functionName = function.name
    val functionArgs = function.argumentsAsJson()
    return TrailblazeToolResult.Error.UnknownTool(functionName, functionArgs)
  }
}
