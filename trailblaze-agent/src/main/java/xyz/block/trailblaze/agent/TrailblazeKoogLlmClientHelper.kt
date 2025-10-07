package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.util.TemplatingUtil
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrailblazeKoogLlmClientHelper(
  var systemPromptTemplate: String,
  val userObjectiveTemplate: String,
  val userMessageTemplate: String,
  val trailblazeLlmModel: TrailblazeLlmModel,
  val llmClient: LLMClient,
  val elementComparator: TrailblazeElementComparator,
  val toolRepo: TrailblazeToolRepo,
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

  private var forceStepStatusUpdate = false

  fun setForceStepStatusUpdate(force: Boolean) {
    forceStepStatusUpdate = force
  }

  fun handleTrailblazeToolForPrompt(
    trailblazeTool: TrailblazeTool,
    traceId: TraceId,
    step: PromptStepStatus,
    agent: TrailblazeAgent,
  ): TrailblazeToolResult = when (trailblazeTool) {
    is ObjectiveStatusTrailblazeTool -> {
      setForceStepStatusUpdate(false)
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
      val (updatedTools, trailblazeToolResult) = agent.runTrailblazeTools(
        tools = listOf(trailblazeTool),
        traceId = traceId,
        screenState = step.currentScreenState,
        elementComparator = elementComparator,
      )
      setForceStepStatusUpdate(true)
      println("\u001B[33m\n[ACTION_TAKEN] Tool executed: ${trailblazeTool.javaClass.simpleName}\u001B[0m")
      trailblazeToolResult
    }
  }

  fun ToolRegistry.getTrailblazeToolFromToolRegistry(toolName: String, toolArgs: JsonObject): TrailblazeTool {
    val toolRegistry = this

    @Suppress("UNCHECKED_CAST")
    val koogTool: Tool<TrailblazeTool, ToolResult> =
      toolRegistry.getTool(toolName) as Tool<TrailblazeTool, ToolResult>
    val trailblazeTool: TrailblazeTool = TrailblazeJsonInstance.decodeFromJsonElement(
      deserializer = koogTool.argsSerializer,
      element = toolArgs,
    )
    return trailblazeTool
  }

  fun handleLlmResponse(
    llmMessage: String?,
    tool: Message.Tool,
    traceId: TraceId,
    step: PromptStepStatus,
    agent: TrailblazeAgent,
  ) {
    val toolRegistry = toolRepo.asToolRegistry {
      TrailblazeToolExecutionContext(
        trailblazeAgent = agent as MaestroTrailblazeAgent,
        screenState = step.currentScreenState,
        traceId = traceId,
      )
    }
    val toolName = tool.tool
    val toolArgs = TrailblazeJsonInstance.decodeFromString(
      JsonObject.serializer(),
      tool.content,
    )
    val trailblazeToolResult = try {
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
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(
        throwable = e,
        trailblazeTool = null,
      )
    }

    step.addCompletedToolCallToChatHistory(
      llmResponseContent = llmMessage,
      toolName = toolName,
      toolArgs = toolArgs,
      commandResult = trailblazeToolResult,
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
          println("[RETRY] Server error (attempt $attempt/$maxRetries), retrying in ${delayMs}ms...")
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
  ): List<Message> = buildList {
    add(
      Message.System(
        content = TemplatingUtil.renderTemplate(
          template = systemPromptTemplate,
          values = mapOf(
            "device_platform" to stepStatus.currentScreenState.trailblazeDevicePlatform.displayName,
          ),
        ),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = TemplatingUtil.renderTemplate(
          template = userObjectiveTemplate,
          values = mapOf(
            "objective" to stepStatus.promptStep.prompt,
          ),
        ),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = TrailblazeAiRunnerMessages.getReminderMessage(stepStatus.promptStep, forceStepStatusUpdate),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )

    // Add previous LLM responses
    addAll(stepStatus.getLimitedHistory())

    val viewHierarchyJson = Json.encodeToString(
      serializer = ViewHierarchyTreeNode.serializer(),
      value = stepStatus.currentScreenState.viewHierarchy,
    )
    add(
      Message.User(
        content = TemplatingUtil.renderTemplate(
          template = userMessageTemplate,
          values = mapOf(
            "view_hierarchy" to viewHierarchyJson,
          ),
        ),
        attachments = buildList {
          val screenshotBytes = stepStatus.currentScreenState.screenshotBytes
          if (screenshotBytes != null &&
            screenshotBytes.isNotEmpty() &&
            trailblazeLlmModel.capabilityIds.contains(
              LLMCapability.Vision.Image.id,
            )
          ) {
            add(
              Attachment.Image(
                AttachmentContent.Binary.Bytes(screenshotBytes),
                format = "png",
              ),
            )
          }
        },
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }
}
