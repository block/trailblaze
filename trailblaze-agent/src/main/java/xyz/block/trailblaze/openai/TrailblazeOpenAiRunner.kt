package xyz.block.trailblaze.openai

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptStep
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.LlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolAsLlmTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.TemplatingUtil
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

class TrailblazeOpenAiRunner(
  val agent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState,
  openAiApiKey: String,
  llmModel: LlmModel? = null,
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

  val llmModel = llmModel ?: LlmModel.GPT_4_1

  private val openAi = OpenAI(
    token = openAiApiKey,
    timeout = Timeout(socket = 180.seconds),
    logging = LoggingConfig(logLevel = LogLevel.None),
  )

  override fun run(prompt: TrailblazePrompt): AgentTaskStatus {
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = prompt.steps.first().currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
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
        timestamp = System.currentTimeMillis(),
      ),
    )
    return exitStatus
  }

  override fun run(step: PromptStep): AgentTaskStatus {
    TrailblazeLogger.log(
      TrailblazeLog.ObjectiveStartLog(
        description = step.description,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      ),
    )
    forceStepStatusUpdate = false
    do {
      println("\n[LOOP_STATUS] Status: ${step.currentStatus.value.javaClass.simpleName} | Call: ${step.llmResponseHistory.size + 1}")
      val screenStateForLlmRequest = screenStateProvider()
      val requestStartTimeMs = System.currentTimeMillis()
      val openAiRequest: ChatCompletionRequest = createNextChatRequest(
        screenState = screenStateForLlmRequest,
        step = step,
      )

      // Use retry logic for OpenAI calls
      val openAiResponse: ChatCompletion = runBlocking {
        openAiChatCompletionWithRetry(openAiRequest)
      }

      val llmResponseId = openAiResponse.id
      TrailblazeLogger.logLlmRequest(
        agentTaskStatus = step.currentStatus.value,
        screenState = screenStateForLlmRequest,
        instructions = step.fullPrompt,
        request = openAiRequest,
        response = openAiResponse,
        startTime = requestStartTimeMs,
        llmRequestId = llmResponseId,
      )

      val (llmMessage, action) = parseResponse(openAiResponse)
      println("[LLM_RESPONSE] Has tool call: ${action != null} | Tool: ${action?.function?.name ?: "None"}")

      if (action != null) {
        val trailblazeTool: TrailblazeTool? = trailblazeToolRepo.toolCallToTrailblazeTool(action)
        val trailblazeToolResult = if (trailblazeTool == null) {
          unknownToolError(action)
        } else if (TrailblazeToolAsLlmTool(trailblazeTool::class).properties
            .filter { it.isRequired }
            .any { prop ->
              (Json.parseToJsonElement(action.function.arguments) as? JsonObject)?.containsKey(prop.name) != true
            }
        ) {
          missingRequiredArgsError(
            action,
            TrailblazeToolAsLlmTool(trailblazeTool::class).properties
              .filter { it.isRequired }
              .map { it.name },
          )
        } else {
          handleTrailblazeToolForPrompt(trailblazeTool, llmResponseId, step, screenStateForLlmRequest)
        }

        step.addToolCall(
          llmResponseContent = llmMessage,
          function = action.function,
          commandResult = trailblazeToolResult,
        )
      } else {
        println("[WARNING] No tool call detected - forcing tool call on next iteration")
        step.addEmptyToolCall(
          llmResponseContent = llmMessage,
          result = TrailblazeToolResult.Error.EmptyToolCall,
        )
        shouldForceToolCall = true
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
        timestamp = System.currentTimeMillis(),
      ),
    )

    return step.currentStatus.value
  }

  private var forceStepStatusUpdate = false
  private fun createNextChatRequest(
    screenState: ScreenState,
    step: PromptStep,
  ) = chatCompletionRequest {
    model = ModelId(llmModel.id)

    // Limit message history to reduce memory usage
    val limitedHistory = step.llmResponseHistory.takeLast(5) // Only keep recent messages

    messages = toOpenAiChatMessages(
      screenState = screenState,
      step = step,
      previousLlmResponses = limitedHistory,
    )
    tools {
      if (step.llmStatusChecks && forceStepStatusUpdate) {
        // When we need to force a task status update, only register the objectiveStatus tool
        // This ensures only this tool can be used
        trailblazeToolRepo.registerSpecificToolOnly(this, ObjectiveStatusTrailblazeTool::class)
        toolChoice = ToolChoice.Mode("required")
      } else {
        // Register all tools that have been registered with the trailblazeToolRepo
        // Including any custom commands directly in the same call
        trailblazeToolRepo.registerManualTools(this)
        toolChoice = getToolChoice()
      }
    }
  }

  /**
   * Construct the messages to send to OpenAI
   */
  private fun toOpenAiChatMessages(
    screenState: ScreenState,
    step: PromptStep,
    previousLlmResponses: List<ChatMessage>,
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
            "objective" to step.fullPrompt,
          ),
        )
      },
    )

    // Add objectives information if available
    // Reminder message - depending on whether we need to force a task status update
    val reminderMessage = if (forceStepStatusUpdate) {
      """
        ## ACTION REQUIRED: REPORT TASK STATUS
        
        You just performed an action using a tool.
        You MUST now report the status of the current objective item by calling the objectiveStatus tool with one of the following statuses:
        - status="in_progress" if you're still working on this specific objective item and need to take more actions
        - status="completed" if you've fully accomplished all instructions in the current objective.
        - status="failed" if this specific objective item cannot be completed after multiple attempts.
        
        Include a detailed message explaining what you just did and your assessment of the situation.
        
        IMPORTANT: You CANNOT perform any other action until you report progress using objectiveStatus.
        Carefully assess if the action you just took completely fulfilled this specific objective item before deciding the status.
        
        ## CURRENT TASK
        
        Current objective item to focus on is:
        
        > Task #${step.taskIndex}: ${step.description}
      """.trimIndent()
    } else {
      """
        ## CURRENT TASK
        
        Your current objective item to focus on is:
        
        > Task #${step.taskIndex}: ${step.description}
        
        IMPORTANT: Focus ONLY on completing this specific objective item. 
        After you complete this objective item, call the objectiveStatus tool IMMEDIATELY.
        DO NOT proceed to the next objective item until this one is complete and you've called objectiveStatus.
      """.trimIndent()
    }

    add(
      chatMessage {
        role = ChatRole.User
        content = reminderMessage
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

  private fun missingRequiredArgsError(action: ToolCall.Function, requiredArgs: List<String>): TrailblazeToolResult {
    val function = action.function
    val functionName = function.name
    val functionArgs = function.argumentsAsJson()
    return TrailblazeToolResult.Error.MissingRequiredArgs(functionName, functionArgs, requiredArgs)
  }

  fun handleTrailblazeToolForPrompt(
    trailblazeTool: TrailblazeTool,
    llmResponseId: String?,
    step: PromptStep,
    screenStateForLlmRequest: ScreenState,
  ): TrailblazeToolResult = when (trailblazeTool) {
    is ObjectiveStatusTrailblazeTool -> {
      forceStepStatusUpdate = false
      when (trailblazeTool.status) {
        "in_progress" -> TrailblazeToolResult.Success
        "failed" -> {
          step.markAsFailed()
          // Using objective to determine when we're done, not the tool result
          TrailblazeToolResult.Success
        }
        "completed" -> {
          step.markAsComplete()
          TrailblazeToolResult.Success
        }
        else -> TrailblazeToolResult.Error.UnknownTrailblazeTool(trailblazeTool)
      }
    }

    else -> {
      val startTime = System.currentTimeMillis() - 1
      val (updatedTools, trailblazeToolResult) = agent.runTrailblazeTools(
        tools = listOf(trailblazeTool),
        llmResponseId = llmResponseId,
        screenState = screenStateForLlmRequest,
      )
      for (command in updatedTools) {
        TrailblazeLogger.log(
          TrailblazeLog.TrailblazeToolLog(
            agentTaskStatus = step.currentStatus.value,
            command = command,
            toolName = command.getToolNameFromAnnotation(),
            exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
            successful = trailblazeToolResult == TrailblazeToolResult.Success,
            duration = System.currentTimeMillis() - startTime,
            timestamp = startTime,
            instructions = step.fullPrompt,
            llmResponseId = llmResponseId,
            session = TrailblazeLogger.getCurrentSessionId(),
          ),
        )
      }
      forceStepStatusUpdate = true
      println("\u001B[33m\n[ACTION_TAKEN] Tool executed: ${trailblazeTool.javaClass.simpleName}\u001B[0m")
      trailblazeToolResult
    }
  }

  /**
   * Calls OpenAI with retry logic and exponential backoff for server errors
   */
  private suspend fun openAiChatCompletionWithRetry(request: ChatCompletionRequest): ChatCompletion {
    var lastException: Exception? = null
    val maxRetries = 3

    for (attempt in 1..maxRetries) {
      try {
        return openAi.chatCompletion(request)
      } catch (e: OpenAIServerException) {
        if (attempt < maxRetries) {
          val baseDelayMs = 1000L // 1 second base delay
          val delayMs = baseDelayMs + (attempt - 1) * 3000L // Add 3 seconds per retry
          println("[RETRY] OpenAI server error (attempt $attempt/$maxRetries), retrying in ${delayMs}ms...")
          delay(delayMs)
        } else {
          // exhausted retries
          throw e
        }
      } catch (e: Exception) {
        throw e
      }
    }
    throw lastException ?: RuntimeException("Unexpected error in retry logic")
  }
}
