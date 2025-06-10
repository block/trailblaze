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

  private val llmModel = llmModel ?: LlmModel.GPT_4_1

  // Create an instance of the ObjectiveManager
  private val objectiveManager = ObjectiveManager()

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
    println("\n\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")
    println("\u001B[1;35m🚀 Starting new agent objective: $instructions\u001B[0m")
    println("\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")

    val task = agent.setUpTask(instructions)

    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = task.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      ),
    )

    // Generate objectives by splitting the instructions by line using the ObjectiveManager
    val objectives = objectiveManager.generateObjectivesFromLines(instructions)

    // Save the objectives to the AgentTask object
    task.setObjectives(objectives)

    do {
      val currentObjectiveIndex = task.currentObjectiveIndex
      if (currentObjectiveIndex >= 0 && task.objectives.isNotEmpty()) {
        // Print progress bar showing which objectives are completed/pending using the ObjectiveManager
        objectiveManager.printObjectiveProgressBar(task)
      }

      // Simplified debug output
      println("\n[LOOP_STATUS] Status: ${task.currentStatus.value.javaClass.simpleName} | Call: ${task.agentCallCount + 1} | Objective: ${currentObjectiveIndex + 1}/${task.objectives.size}")

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

      // Simplified response info
      println("[LLM_RESPONSE] Has tool call: ${action != null} | Tool: ${action?.function?.name ?: "None"}")

      if (action != null) {
        val trailblazeTool: TrailblazeTool? = trailblazeToolRepo.toolCallToTrailblazeTool(action)
        val trailblazeToolResult = if (trailblazeTool == null) {
          unknownToolError(action)
        } else {
          handleTrailblazeTool(trailblazeTool, task, llmResponseId, instructions, screenStateForLlmRequest)
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

        // Simplified warning
        println("[WARNING] No tool call detected - forcing tool call on next iteration")
      }

      // Simplified end of iteration status
      println("[END_ITERATION] Status: ${task.currentStatus.value.javaClass.simpleName} | Finished: ${task.isFinished()} | Continue: ${!task.isFinished()}")
    } while (!task.isFinished())

    // Simplified exit reason
    println("\n[LOOP_EXIT] Status: ${task.currentStatus.value.javaClass.simpleName} | Calls: ${task.agentCallCount} | Objectives completed: ${task.currentObjectiveIndex + 1}/${task.objectives.size}")
    if (task.currentStatus.value is AgentTaskStatus.Failure) {
      val failure = task.currentStatus.value as AgentTaskStatus.Failure
      println("[FAILURE] Type: ${failure.javaClass.simpleName}")
    }

    // Log the final task completion status
    val taskCompletionTime = System.currentTimeMillis()
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
        agentTaskStatus = task.currentStatus.value,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = taskCompletionTime,
      ),
    )

    // Print final summary with additional formatting
    println("\n\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")
    println("\u001B[1;35m📊 FINAL TASK SUMMARY\u001B[0m")
    println("\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")
    task.printResultsToConsole()
    println("\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")

    return task.currentStatus.value
  }

  private fun createNextChatRequest(
    screenState: ScreenState,
    task: AgentTask,
  ) = chatCompletionRequest {
    model = ModelId(llmModel.id)

    // Limit message history to reduce memory usage
    val limitedHistory = task.llmResponseHistory.takeLast(5) // Only keep recent messages

    messages = toOpenAiChatMessages(
      screenState = screenState,
      instructions = task.instructions,
      previousLlmResponses = limitedHistory,
      task = task,
    )
    tools {
      if (objectiveManager.getForceTaskStatusUpdate()) {
        // When we need to force a task status update, only register the objectiveComplete tool
        // This ensures only this tool can be used
        trailblazeToolRepo.registerSpecificToolOnly(this, ObjectiveCompleteTrailblazeTool::class)
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

    // Add objectives information if available
    val objectives = task.objectives
    if (objectives.isNotEmpty()) {
      // Get current objective information
      val currentObjectiveIndex = task.currentObjectiveIndex
      if (currentObjectiveIndex >= 0 && currentObjectiveIndex < objectives.size) {
        val currentObjective = objectives[currentObjectiveIndex]

        // Reminder message - depending on whether we need to force a task status update
        val reminderMessage = if (objectiveManager.getForceTaskStatusUpdate()) {
          """
            ## ACTION REQUIRED: REPORT TASK STATUS
            
            You just performed an action using a tool.
            You MUST now report the status of the current objective item by calling the objectiveComplete tool with one of the following statuses:
            - status="completed" if you've fully accomplished this specific objective item (you'll advance to the next item)
            - status="in_progress" if you're still working on this specific objective item and need to take more actions
            - status="failed" if this specific objective item cannot be completed or the action you took didn't succeed
            
            Include a detailed message explaining what you just did and your assessment of the situation.
            
            IMPORTANT: You CANNOT perform any other action until you report progress using objectiveComplete.
            Carefully assess if the action you just took completely fulfilled this specific objective item before deciding the status.
            
            ## CURRENT TASK
            
            Current objective item to focus on is:
            
            > Task #${currentObjectiveIndex + 1}: ${currentObjective.description}
          """.trimIndent()
        } else {
          """
            ## CURRENT TASK
            
            Your current objective item to focus on is:
            
            > Task #${currentObjectiveIndex + 1}: ${currentObjective.description}
            
            IMPORTANT: Focus ONLY on completing this specific objective item. 
            After you complete this objective item, call the objectiveComplete tool IMMEDIATELY.
            DO NOT proceed to the next objective item until this one is complete and you've called objectiveComplete.
          """.trimIndent()
        }

        add(
          chatMessage {
            role = ChatRole.User
            content = reminderMessage
          },
        )
      } else {
        add(
          chatMessage {
            role = ChatRole.User
            content =
              "Thanks for creating this task list. Now let's work through these tasks one by one to achieve the objective."
          },
        )
      }
    }

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

  fun handleTrailblazeTool(
    trailblazeTool: TrailblazeTool,
    task: AgentTask,
    llmResponseId: String?,
    instructions: String,
    screenStateForLlmRequest: ScreenState,
  ): TrailblazeToolResult = when (trailblazeTool) {
    is ObjectiveCompleteTrailblazeTool -> {
      objectiveManager.handleObjectiveCompleteCommand(
        task = task,
        command = trailblazeTool,
        llmResponseId = llmResponseId,
      )
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
            agentTaskStatus = task.currentStatus.value,
            command = command,
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
      objectiveManager.setForceTaskStatusUpdate(true)
      println("\u001B[33m\n[ACTION_TAKEN] Tool executed: ${trailblazeTool.javaClass.simpleName}\u001B[0m")
      trailblazeToolResult
    }
  }

  /**
   * Run a tool step from a YAML map (tool name as key, params as value)
   */
  fun runToolStep(
    step: Map<*, *>,
    llmResponseId: String?,
    instructions: String,
    screenStateForLlmRequest: ScreenState,
  ): TrailblazeToolResult {
    val toolName = step.keys.firstOrNull()?.toString() ?: return TrailblazeToolResult.Error.UnknownTool("<missing>", kotlinx.serialization.json.buildJsonObject { })
    val params = step.values.firstOrNull() as? Map<*, *> ?: emptyMap<String, Any?>()
    val allPossibleTools = TrailblazeToolRepo.ALL + trailblazeToolRepo.getRegisteredTrailblazeTools()
    val toolClass = allPossibleTools.firstOrNull {
      xyz.block.trailblaze.toolcalls.TrailblazeToolAsLlmTool(it).name == toolName
    } ?: return TrailblazeToolResult.Error.UnknownTool(toolName, kotlinx.serialization.json.buildJsonObject { })
    val jsonParams = kotlinx.serialization.json.buildJsonObject {
      params.forEach { (k, v) ->
        when (v) {
          is Boolean -> put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v))
          is Number -> put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v))
          is String -> put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v))
          else -> put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v.toString()))
        }
      }
    }
    val tool = try {
      xyz.block.trailblaze.toolcalls.JsonSerializationUtil.deserializeTrailblazeTool(toolClass, jsonParams)
    } catch (e: Exception) {
      println("[TrailblazeOpenAiRunner] Failed to deserialize tool: $toolName with params $params: " + e.message)
      return TrailblazeToolResult.Error.UnknownTool(toolName, jsonParams)
    }
    return handleTrailblazeTool(
      trailblazeTool = tool,
      task = agent.currentTask,
      llmResponseId = llmResponseId,
      instructions = instructions,
      screenStateForLlmRequest = screenStateForLlmRequest,
    )
  }
}
