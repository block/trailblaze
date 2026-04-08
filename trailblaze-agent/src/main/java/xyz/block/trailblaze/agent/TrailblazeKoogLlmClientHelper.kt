@file:OptIn(kotlin.time.ExperimentalTime::class)

package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import xyz.block.trailblaze.util.Console
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.TemplatingUtil
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Data class to hold the result of tool execution along with the actual tools that were executed.
 * This is necessary for delegating tools where the LLM calls one tool but it delegates to others.
 */
data class ToolExecutionResult(
  val result: TrailblazeToolResult,
  val executedTools: List<TrailblazeTool>,
)

class TrailblazeKoogLlmClientHelper(
  var systemPromptTemplate: String,
  val trailblazeLlmModel: TrailblazeLlmModel,
  val llmClient: LLMClient,
  val elementComparator: TrailblazeElementComparator,
  val toolRepo: TrailblazeToolRepo,
  val screenStateProvider: (() -> ScreenState)? = null,
) {

  // This field will be used to determine whether or not our next request to the LLM should require
  // a tool call. This occasionally happens when the LLM returns the tool call in the message
  // instead of actually triggering the tool. In this case we already have the model's reasoning
  // so we can just force it to call a tool next.
  private var shouldForceToolCall = false

  fun setShouldForceToolCall(force: Boolean) {
    shouldForceToolCall = force
  }

  fun getShouldForceToolCall(): Boolean = shouldForceToolCall

  /**
   * Builds a device description string that combines classifiers, platform, and screen dimensions.
   * Only includes the platform in parentheses if it differs from the first classifier.
   * Examples:
   * - "android - 1080x2400" (first classifier matches platform)
   * - "classifier1, classifier2 (android) - 1080x2400" (first classifier differs from platform)
   */
  private fun buildDeviceDescription(screenState: ScreenState): String {
    val classifiers = screenState.deviceClassifiers
    val platform = screenState.trailblazeDevicePlatform.displayName
    val classifierList = classifiers.joinToString(", ") { it.classifier }
    val firstClassifier = classifiers.firstOrNull()?.classifier
    val dimensions = "${screenState.deviceWidth}x${screenState.deviceHeight}"

    val devicePart = if (firstClassifier != null && !firstClassifier.equals(platform, ignoreCase = true)) {
      "$classifierList ($platform)"
    } else {
      classifierList.ifEmpty { platform }
    }

    return "$devicePart - $dimensions"
  }

  fun handleTrailblazeToolForPrompt(
    trailblazeTool: TrailblazeTool,
    traceId: TraceId,
    step: PromptStepStatus,
    agent: TrailblazeAgent,
  ): ToolExecutionResult = when (trailblazeTool) {
    is ObjectiveStatusTrailblazeTool -> {
      step.addObjectiveStatusUpdate(
        status = trailblazeTool.status.name,
      )

      when (trailblazeTool.status) {
        Status.IN_PROGRESS -> ToolExecutionResult(
          result = TrailblazeToolResult.Success(),
          executedTools = listOf(trailblazeTool),
        )

        Status.FAILED -> {
          step.markAsFailed(llmExplanation = trailblazeTool.explanation)
          // Using objective to determine when we're done, not the tool result
          ToolExecutionResult(
            result = TrailblazeToolResult.Success(),
            executedTools = listOf(trailblazeTool),
          )
        }

        Status.COMPLETED -> {
          step.markAsComplete(llmExplanation = trailblazeTool.explanation)
          ToolExecutionResult(
            result = TrailblazeToolResult.Success(),
            executedTools = listOf(trailblazeTool),
          )
        }
      }
    }

    is ConfigTrailblazeTool -> {
      ToolExecutionResult(
        result = trailblazeTool.execute(toolRepo),
        executedTools = listOf(trailblazeTool),
      )
    }

    else -> {
      val runTrailblazeToolsResult: TrailblazeAgent.RunTrailblazeToolsResult = agent.runTrailblazeTools(
        tools = listOf(trailblazeTool),
        traceId = traceId,
        screenState = step.currentScreenState,
        elementComparator = elementComparator,
        screenStateProvider = screenStateProvider,
      )
      Console.log("\u001B[33m\n[ACTION_TAKEN] Tool executed: ${trailblazeTool.javaClass.simpleName}\u001B[0m")

      // Return both the result and the executed tools (which may differ for delegating tools)
      ToolExecutionResult(
        result = runTrailblazeToolsResult.result,
        executedTools = runTrailblazeToolsResult.executedTools,
      )
    }
  }

  fun ToolRegistry.getTrailblazeToolFromToolRegistry(toolName: String, toolArgs: JsonObject): TrailblazeTool {
    val toolRegistry = this

    @Suppress("UNCHECKED_CAST")
    val koogTool: Tool<TrailblazeTool, ToolResult> =
      toolRegistry.getTool(toolName) as Tool<TrailblazeTool, ToolResult>
    @Suppress("UNCHECKED_CAST")
    val trailblazeTool: TrailblazeTool = koogTool.decodeArgs(
      toolArgs.toKoogJSONObject(),
      KotlinxSerializer(TrailblazeJsonInstance),
    ) as TrailblazeTool
    return trailblazeTool
  }

  fun handleLlmResponse(
    llmMessage: String?,
    tool: Message.Tool,
    traceId: TraceId,
    step: PromptStepStatus,
    agent: TrailblazeAgent,
  ) {
    val agentContext = agent as? TrailblazeAgentContext
      ?: error("Agent ${agent::class.simpleName} must implement TrailblazeAgentContext")
    val maestroAgent = agent as? MaestroTrailblazeAgent
    val toolRegistry = toolRepo.asToolRegistry {
      TrailblazeToolExecutionContext(
        screenState = step.currentScreenState,
        traceId = traceId,
        trailblazeDeviceInfo = agentContext.trailblazeDeviceInfoProvider(),
        sessionProvider = agentContext.sessionProvider,
        screenStateProvider = screenStateProvider,
        trailblazeLogger = agentContext.trailblazeLogger,
        memory = agentContext.memory,
        maestroTrailblazeAgent = maestroAgent,
      )
    }
    val toolName = tool.tool
    val toolArgs = TrailblazeJsonInstance.decodeFromString(
      JsonObject.serializer(),
      tool.content,
    )
    val toolExecutionResult = try {
      val trailblazeTool = toolRegistry.getTrailblazeToolFromToolRegistry(
        toolName = toolName,
        toolArgs = toolArgs,
      )
      handleTrailblazeToolForPrompt(
        trailblazeTool = trailblazeTool,
        step = step,
        traceId = traceId,
        agent = agent,
      )
    } catch (executionException: TrailblazeToolExecutionException) {
      ToolExecutionResult(
        result = TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(
          throwable = executionException,
          trailblazeTool = executionException.tool,
        ),
        executedTools = buildList {
          add(executionException.tool)
        },
      )
    } catch (exception: Exception) {
      Console.log("[ERROR] Tool execution failed for '$toolName': ${exception.message}")
      ToolExecutionResult(
        result = TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(
          throwable = exception,
        ),
        executedTools = emptyList(),
      )
    }

    // Fatal errors abort the test immediately — no retry, no LLM feedback loop
    val fatalError = toolExecutionResult.result as? TrailblazeToolResult.Error.FatalError
    if (fatalError != null) {
      val fatalToolMessage =
        buildString {
          append("Fatal tool error: ${fatalError.errorMessage}")
          fatalError.stackTraceString?.takeIf { it.isNotBlank() }?.let { stackTrace ->
            append("\nStack trace:\n")
            append(stackTrace.trimEnd())
          }
        }
      throw TrailblazeException(fatalToolMessage)
    }

    // Serialize each executed tool to get its actual arguments
    val executedToolsWithArgs = toolExecutionResult.executedTools.associate { executedTool ->
      // Serialize the tool to JSON string, then parse back to JsonObject to extract its arguments
      val serializedToolJson = TrailblazeJsonInstance.encodeToString<TrailblazeTool>(executedTool)
      val toolJsonObject = TrailblazeJsonInstance.decodeFromString<JsonObject>(
        JsonObject.serializer(),
        serializedToolJson,
      )

      // Handle OtherTrailblazeTool wrapper - extract the "raw" field if present
      val actualArgs = if (toolJsonObject["class"]?.toString()?.contains("OtherTrailblazeTool") == true) {
        // For OtherTrailblazeTool, use the "raw" field which contains the actual tool parameters
        toolJsonObject["raw"]?.jsonObject ?: toolJsonObject
      } else {
        toolJsonObject
      }

      // Remove the polymorphic type discriminator fields to flatten the message
      // Keep only the actual tool parameters that the LLM should see
      val flattenedArgs = actualArgs.filterKeys { key ->
        key != "type" && key != "class" && key != "@class" && key != "toolName"
      }.let { JsonObject(it) }

      executedTool.getToolNameFromAnnotation() to flattenedArgs
    }

    step.addCompletedToolCallToChatHistory(
      llmResponseContent = llmMessage,
      toolsWithArgs = executedToolsWithArgs,
      commandResult = toolExecutionResult.result,
    )
  }

  suspend fun callLlm(
    llmRequestData: KoogLlmRequestData,
  ): List<Message.Response> {
    var lastException: Exception? = null
    val maxRetries = 3

    for (attempt in 1..maxRetries) {
      try {
        /**
         * Koog Requires each LLM Prompt to have an id
         * We just generate a unique one to satisfy Koog
         */
        @OptIn(ExperimentalUuidApi::class)
        val promptId = Uuid.random().toString()

        val koogLlmResponse: List<Message.Response> = llmClient.execute(
          prompt = Prompt(
            messages = llmRequestData.messages,
            id = promptId,
            params = LLMParams(
              temperature = null,
              speculation = null,
              schema = null,
              toolChoice = llmRequestData.toolChoice,
            ),
          ),
          model = trailblazeLlmModel.toKoogLlmModel(),
          tools = llmRequestData.toolDescriptors,
        )
        return koogLlmResponse
      } catch (e: Exception) {
        lastException = e
        if (attempt < maxRetries) {
          val baseDelayMs = 1000L // 1 second base delay
          val delayMs = baseDelayMs + (attempt - 1) * 3000L // Add 3 seconds per retry
          Console.log("[RETRY] Server error (attempt $attempt/$maxRetries), retrying in ${delayMs}ms...")
          delay(delayMs)
        } else {
          // exhausted retries
          throw e
        }
      }
    }
    throw lastException ?: RuntimeException("Unexpected error in retry logic")
  }

  fun createNextChatRequest(
    stepStatus: PromptStepStatus,
    previouslyCompletedStepDescriptions: List<String> = emptyList(),
  ): List<Message> = buildList {
    add(
      Message.System(
        content = TemplatingUtil.renderTemplate(
          template = systemPromptTemplate,
          values = mapOf(
            "device_description" to buildDeviceDescription(stepStatus.currentScreenState),
          ),
        ),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    // Standalone objective message — keeps the current task prominent and easy for the LLM
    // to focus on, separate from the context/instructions in the reminder message below.
    add(
      Message.User(
        content = "**Objective**\n\n${stepStatus.promptStep.prompt}",
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = TrailblazeAiRunnerMessages.getReminderMessage(
          promptStep = stepStatus.promptStep,
          completedObjectiveDescriptions = previouslyCompletedStepDescriptions,
          latestObjectiveStatus = stepStatus.getLatestObjectiveStatus(),
        ),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )

    // Add previous LLM responses
    addAll(stepStatus.getLimitedHistory())

    // Prefer platform-native text representation (e.g., Playwright's compact ARIA list)
    // over JSON-serializing the generic ViewHierarchyTreeNode tree.
    val viewHierarchyText = stepStatus.currentScreenState.viewHierarchyTextRepresentation
      ?: Json.encodeToString(
        serializer = ViewHierarchyTreeNode.serializer(),
        value = stepStatus.currentScreenState.viewHierarchy,
      )
    add(
      Message.User(
        parts = buildList {
          add(
            ContentPart.Text(
              text = buildString {
                appendLine("Here is the view hierarchy of the user interface at this moment:")
                appendLine("```")
                appendLine(viewHierarchyText)
                appendLine("```")
              },
            ),
          )
          val screenshotBytes = stepStatus.currentScreenState.annotatedScreenshotBytes
          if (screenshotBytes != null &&
            screenshotBytes.isNotEmpty() &&
            trailblazeLlmModel.capabilityIds.contains(
              LLMCapability.Vision.Image.id,
            )
          ) {
            Console.log("[SCREENSHOT] Size: ${screenshotBytes.size} bytes (${screenshotBytes.size / 1024}KB)")
            add(
              ContentPart.Image(
                content = AttachmentContent.Binary.Bytes(screenshotBytes),
                format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
              ),
            )
          }
        },
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }
}
