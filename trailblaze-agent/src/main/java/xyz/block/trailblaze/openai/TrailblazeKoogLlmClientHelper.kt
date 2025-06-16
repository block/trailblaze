package xyz.block.trailblaze.openai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MediaContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.agent.model.TrailblazePromptStep
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.util.TemplatingUtil
import java.io.File
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi

class TrailblazeKoogLlmClientHelper(
  val systemPromptTemplate: String,
  val userObjectiveTemplate: String,
  val userMessageTemplate: String,
  val llmModel: LLModel,
  val llmClient: LLMClient,
) {
  suspend fun callLlm(
    llmRequestData: KoogLlmRequestData,
  ): List<Message.Response> {
    var lastException: Exception? = null
    val maxRetries = 3

    for (attempt in 1..maxRetries) {
      try {
        val koogLlmResponse: List<Message.Response> = llmClient.execute(
          prompt = Prompt(
            messages = llmRequestData.messages,
            id = llmRequestData.callId,
            params = LLMParams(
              temperature = null,
              speculation = null,
              schema = null,
              toolChoice = llmRequestData.toolChoice,
            ),
          ),
          model = llmModel,
          tools = llmRequestData.toolDescriptors,
        )
        return koogLlmResponse
      } catch (e: Exception) {
        lastException = e
        if (attempt < maxRetries) {
          val baseDelayMs = 1000L // 1 second base delay
          val delayMs = baseDelayMs + (attempt - 1) * 3000L // Add 3 seconds per retry
          println("[RETRY] OpenAI server error (attempt $attempt/$maxRetries), retrying in ${delayMs}ms...")
          delay(delayMs)
        } else {
          // exhausted retries
          throw e
        }
      }
    }
    throw lastException ?: RuntimeException("Unexpected error in retry logic")
  }

  fun createNextChatRequestKoog(
    screenState: ScreenState,
    step: TrailblazePromptStep,
    forceStepStatusUpdate: Boolean,
    limitedHistory: List<Message>,
  ) = buildList {
    add(
      Message.System(
        content = systemPromptTemplate,
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = TemplatingUtil.renderTemplate(
          template = userObjectiveTemplate,
          values = mapOf(
            "objective" to step.fullPrompt,
          ),
        ),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = TrailblazeAiRunnerMessages.getReminderMessage(step, forceStepStatusUpdate),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )

    // Add previous LLM responses
    addAll(limitedHistory)

    val viewHierarchyJson = Json.encodeToString(ViewHierarchyTreeNode.serializer(), screenState.viewHierarchy)
    add(
      Message.User(
        content = TemplatingUtil.renderTemplate(
          template = userMessageTemplate,
          values = mapOf(
            "view_hierarchy" to viewHierarchyJson,
          ),
        ),
        mediaContent = mutableListOf<MediaContent>().apply {
          screenState.screenshotBytes?.let { screenshotBytes ->
            val screenshotFile = File.createTempFile("screenshot", ".png").apply {
              writeBytes(screenshotBytes)
            }
            @OptIn(ExperimentalEncodingApi::class)
            MediaContent.Image(screenshotFile.canonicalPath)
          }
        },
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }

  data class KoogLlmRequestData(
    val messages: List<Message>,
    val toolDescriptors: List<ToolDescriptor>,
    val toolChoice: LLMParams.ToolChoice,
    val callId: String = UUID.randomUUID().toString(),
  )
}
