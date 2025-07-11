package xyz.block.trailblaze.agent

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MediaContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.agent.util.ElementRetriever
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool.LocatorResponse
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool.LocatorType
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.utils.getNumberFromString
import java.io.File

/**
 * Service that identifies element locators and evaluates UI elements.
 */
internal class TrailblazeElementComparator(
  private val screenStateProvider: () -> ScreenState,
  llmModel: LLModel,
  llmClient: LLMClient,
  private val systemPromptToolTemplate: String = TemplatingUtil.getResourceAsText("trailblaze_locator_tool_system_prompt.md")!!,
  private val userPromptTemplate: String = TemplatingUtil.getResourceAsText("trailblaze_locator_user_prompt_template.md")!!,
) {

  private val koogLlmClientHelper = TrailblazeKoogLlmClientHelper(
    llmModel = llmModel,
    llmClient = llmClient,
    systemPromptTemplate = systemPromptToolTemplate,
    userMessageTemplate = userPromptTemplate,
    userObjectiveTemplate = userPromptTemplate,
  )

  /**
   * Gets the value of an element based on a prompt description.
   */
  fun getElementValue(prompt: String): String? {
    val screenState = screenStateProvider()
    println("Getting element value for prompt: '$prompt'")

    val locatorResponse = identifyElementLocator(screenState, prompt)

    if (!locatorResponse.success || locatorResponse.locatorType == null || locatorResponse.value == null) {
      return null
    }

    val locatorValue = locatorResponse.value!!
    val locatorIndex = locatorResponse.index ?: 0

    return try {
      when (locatorResponse.locatorType) {
        LocatorType.RESOURCE_ID -> ElementRetriever.getTextByResourceId(
          currentViewHierarchy = screenState.viewHierarchy,
          resourceId = locatorValue,
          index = locatorIndex,
        )

        LocatorType.CONTENT_DESCRIPTION -> ElementRetriever.getTextByContentDescription(
          currentViewHierarchy = screenState.viewHierarchy,
          contentDescription = locatorValue,
          index = locatorIndex,
        )

        LocatorType.TEXT -> ElementRetriever.getTextByText(
          currentViewHierarchy = screenState.viewHierarchy,
          text = locatorValue,
          index = locatorIndex,
        )

        null -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Evaluates a statement about the UI and returns a boolean result with explanation.
   */
  fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool {
    println("Evaluating boolean assertion: '$statement'")

    // Use LLM with boolean assertion tool
    val koogAiRequestMessages = createToolRequest(
      prompt = statement,
      systemPrompt = """
        You are a UI testing assistant that evaluates statements about the current screen.
        Use the booleanAssertion tool to analyze and respond.
        Analyze the screenshot and view hierarchy to determine if the statement is true or false.
        Provide a clear explanation for your reasoning.
      """.trimIndent(),
    )

    val booleanAssertionToolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "Boolean Assertion",
        setOf(BooleanAssertionTrailblazeTool::class),
      ),
    )
    val koogRequestData = KoogLlmRequestData(
      messages = koogAiRequestMessages,
      toolDescriptors = booleanAssertionToolRepo.getCurrentToolDescriptors(),
      toolChoice = LLMParams.ToolChoice.Required,
    )

    val koogLlmChatResponse: List<Message.Response> = runBlocking { koogLlmClientHelper.callLlm(koogRequestData) }
    val toolCalls = koogLlmChatResponse.filterIsInstance<Message.Tool>()
    val assertionToolMessage: Message.Tool = toolCalls.firstOrNull() ?: return BooleanAssertionTrailblazeTool(
      result = false,
      reason = "Failed to get tool response from LLM",
    )

    try {
      val booleanCommand = booleanAssertionToolRepo.toolCallToTrailblazeTool(
        toolName = assertionToolMessage.tool,
        toolContent = assertionToolMessage.content,
      ) as? BooleanAssertionTrailblazeTool
        ?: return BooleanAssertionTrailblazeTool(
          result = false,
          reason = "Failed to parse tool call response",
        )

      return booleanCommand
    } catch (e: Exception) {
      return BooleanAssertionTrailblazeTool(
        result = false,
        reason = "Error processing assertion: ${e.message}",
      )
    }
  }

  /**
   * Evaluates a prompt and returns a descriptive string response with explanation.
   */
  fun evaluateString(query: String): StringEvaluationTrailblazeTool {
    println("Evaluating string query: '$query'")

    // Use LLM with string evaluation tool
    val koogAiRequestMessages: List<Message> = createToolRequest(
      prompt = query,
      systemPrompt = """
        You are a UI testing assistant that answers questions about the current screen.
        Use the stringEvaluation tool to respond with accurate information.
        Provide brief, direct responses with just the specific information requested.
        Provide a clear explanation for how you determined your answer.
      """.trimIndent(),
    )

    val evaluationToolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "String Evaluation",
        setOf(StringEvaluationTrailblazeTool::class),
      ),
    )

    val koogLlmChatResponse: List<Message.Response> = runBlocking {
      koogLlmClientHelper.callLlm(
        KoogLlmRequestData(
          messages = koogAiRequestMessages,
          toolDescriptors = evaluationToolRepo.getCurrentToolDescriptors(),
          toolChoice = LLMParams.ToolChoice.Required,
        ),
      )
    }
    val toolCalls = koogLlmChatResponse.filterIsInstance<Message.Tool>()

    val evalToolCall = toolCalls.firstOrNull()
    if (evalToolCall == null) {
      return StringEvaluationTrailblazeTool(
        result = "",
        reason = "Failed to get tool response from LLM",
      )
    }

    println("Tool call found: ${evalToolCall.tool}, arguments: ${evalToolCall.content}")

    try {
      val stringCommand = evaluationToolRepo.toolCallToTrailblazeTool(
        evalToolCall.tool,
        evalToolCall.content,
      ) as? StringEvaluationTrailblazeTool
      if (stringCommand == null) {
        println("ERROR: Failed to parse tool call as StringEvaluationCommand")
        return StringEvaluationTrailblazeTool(
          result = "",
          reason = "Failed to parse tool call response",
        )
      }

      return stringCommand
    } catch (e: Exception) {
      println("ERROR: Exception processing evaluation: ${e.message}")
      e.printStackTrace()

      return StringEvaluationTrailblazeTool(
        result = "",
        reason = "Error processing evaluation: ${e.message}",
      )
    }
  }

  /**
   * Extracts a number from a string using regex, handling commas as thousands separators.
   */
  fun extractNumberFromString(input: String): Double? = getNumberFromString(input)

  /**
   * Uses LLM to identify the best locator for an element based on description.
   */
  private fun identifyElementLocator(screenState: ScreenState, prompt: String): LocatorResponse {
    println("Identifying element locator for: $prompt")
    val viewHierarchyJson = Json.encodeToString(ViewHierarchyTreeNode.serializer(), screenState.viewHierarchy)

    val koogRequestMessages: List<Message.Request> = buildList {
      add(
        Message.System(
          content = systemPromptToolTemplate,
          metaInfo = RequestMetaInfo.create(Clock.System),
        ),
      )
      add(
        Message.User(
          content = TemplatingUtil.renderTemplate(
            template = userPromptTemplate,
            values = mapOf(
              "identifier" to prompt,
              "view_hierarchy" to viewHierarchyJson,
            ),
          ),
          metaInfo = RequestMetaInfo.create(Clock.System),
          mediaContent = buildList {
            screenState.screenshotBytes?.let { screenshotBytes ->
              val screenshotFile = File.createTempFile("screenshot", ".png").apply {
                writeBytes(screenshotBytes)
              }
              add(MediaContent.Image(screenshotFile.canonicalPath))
            }
          },
        ),
      )
    }

    val elementRetrieverToolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "Element Retriever",
        setOf(ElementRetrieverTrailblazeTool::class),
      ),
    )
    val koogLlmChatResponse: List<Message.Response> = runBlocking {
      koogLlmClientHelper.callLlm(
        KoogLlmRequestData(
          messages = koogRequestMessages,
          toolDescriptors = elementRetrieverToolRepo.getCurrentToolDescriptors(),
          toolChoice = LLMParams.ToolChoice.Required,
        ),
      )
    }
    val toolCalls = koogLlmChatResponse.filterIsInstance<Message.Tool>()

    val locatorToolCall: Message.Tool = toolCalls.firstOrNull() ?: return LocatorResponse(
      success = false,
      locatorType = null,
      value = null,
      index = null,
      reason = "No tool call found in LLM response",
    )

    try {
      // Repo specific for locator identification
      val elementRetrieverCommand = elementRetrieverToolRepo.toolCallToTrailblazeTool(locatorToolCall)
        ?: error("Failed to parse tool call response as ElementRetrieverCommand $locatorToolCall")

      val retrieverCommand = elementRetrieverCommand as ElementRetrieverTrailblazeTool
      return LocatorResponse(
        success = retrieverCommand.success,
        locatorType = retrieverCommand.locatorType,
        value = retrieverCommand.value,
        index = retrieverCommand.index,
        reason = retrieverCommand.reason,
      )
    } catch (e: Exception) {
      return LocatorResponse(
        success = false,
        locatorType = null,
        value = null,
        index = null,
        reason = "Failed to process tool call: ${e.message}",
      )
    }
  }

  /**
   * Creates a tool-based request for evaluation commands.
   */
  private fun createToolRequest(prompt: String, systemPrompt: String): List<Message> = buildList {
    val screenState = screenStateProvider()
    add(
      Message.System(
        content = systemPrompt,
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
    add(
      Message.User(
        content = "Evaluate this on the current screen: $prompt",
        metaInfo = RequestMetaInfo.create(Clock.System),
        mediaContent = buildList {
          screenState.screenshotBytes?.let { screenshotBytes ->
            val screenshotFile = File.createTempFile("screenshot", ".png").apply {
              writeBytes(screenshotBytes)
            }
            add(MediaContent.Image(screenshotFile.canonicalPath))
          }
        },
      ),
    )
  }
}
