package xyz.block.trailblaze.openai

import com.aallam.openai.api.chat.ChatCompletionRequest
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.LlmModel
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool.LocatorResponse
import xyz.block.trailblaze.toolcalls.commands.ElementRetrieverTrailblazeTool.LocatorType
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.util.TemplatingUtil
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

/**
 * Service that identifies element locators and evaluates UI elements.
 */
internal class TrailblazeElementComparator(
  private val screenStateProvider: () -> ScreenState,
  openAiApiKey: String,
  private val llmModel: LlmModel = LlmModel.GPT_4_1,
  private val systemPromptToolTemplate: String = TemplatingUtil.getResourceAsText("trailblaze_locator_tool_system_prompt.md")!!,
  private val userPromptTemplate: String = TemplatingUtil.getResourceAsText("trailblaze_locator_user_prompt_template.md")!!,
) {
  private val openAi = OpenAI(
    token = openAiApiKey,
    timeout = Timeout(socket = 60.seconds),
    logging = LoggingConfig(logLevel = LogLevel.None),
  )

  // Repo specific for locator identification
  private val locatorToolRepo = TrailblazeToolRepo().apply {
    removeAllTrailblazeTools()
    addTrailblazeTools(
      ElementRetrieverTrailblazeTool::class,
    )
  }

  // Regex to extract any positive or negative integer or float from a string
  private val numberRegex = """(-?\d+(\.\d+)?)""".toRegex()

  /**
   * Gets the value of an element based on a prompt description.
   */
  fun getElementValue(prompt: String): String? {
    val screenState = prepareScreenState()
    println("Getting element value for prompt: '$prompt'")

    val locatorResponse = identifyElementLocator(screenState, prompt)

    if (!locatorResponse.success || locatorResponse.locatorType == null || locatorResponse.value == null) {
      return null
    }

    val locatorValue = locatorResponse.value!!
    val locatorIndex = locatorResponse.index ?: 0

    return try {
      when (locatorResponse.locatorType) {
        LocatorType.RESOURCE_ID -> ElementRetriever.getTextByResourceId(locatorValue, locatorIndex)
        LocatorType.CONTENT_DESCRIPTION -> ElementRetriever.getTextByContentDescription(locatorValue, locatorIndex)
        LocatorType.TEXT -> ElementRetriever.getTextByText(locatorValue, locatorIndex)
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
    val screenState = prepareScreenState()
    println("Evaluating boolean assertion: '$statement'")

    // Use LLM with boolean assertion tool
    val request = createToolRequest(
      prompt = statement,
      toolType = "booleanAssertion",
      systemPrompt = """
        You are a UI testing assistant that evaluates statements about the current screen.
        Use the booleanAssertion tool to analyze and respond.
        Analyze the screenshot and view hierarchy to determine if the statement is true or false.
        Provide a clear explanation for your reasoning.
      """.trimIndent(),
    )

    val openAiResponse = runBlocking { openAi.chatCompletion(request) }
    val toolCalls =
      openAiResponse.choices.first().message.toolCalls?.filterIsInstance<ToolCall.Function>() ?: emptyList()
    val assertionToolCall = toolCalls.firstOrNull() ?: return BooleanAssertionTrailblazeTool(
      result = false,
      reason = "Failed to get tool response from LLM",
    )

    try {
      val booleanCommand =
        evaluationToolRepo.toolCallToTrailblazeTool(assertionToolCall) as? BooleanAssertionTrailblazeTool
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
    val screenState = prepareScreenState()
    println("Evaluating string query: '$query'")

    // Use LLM with string evaluation tool
    val request = createToolRequest(
      prompt = query,
      toolType = "stringEvaluation",
      systemPrompt = """
        You are a UI testing assistant that answers questions about the current screen.
        Use the stringEvaluation tool to respond with accurate information.
        Provide brief, direct responses with just the specific information requested.
        Provide a clear explanation for how you determined your answer.
      """.trimIndent(),
    )

    val openAiResponse = runBlocking { openAi.chatCompletion(request) }

    val toolCalls =
      openAiResponse.choices.first().message.toolCalls?.filterIsInstance<ToolCall.Function>() ?: emptyList()

    val evalToolCall = toolCalls.firstOrNull()
    if (evalToolCall == null) {
      return StringEvaluationTrailblazeTool(
        result = "",
        reason = "Failed to get tool response from LLM",
      )
    }

    println("Tool call found: ${evalToolCall.function.name}, arguments: ${evalToolCall.function.argumentsAsJson()}")

    try {
      val stringCommand = evaluationToolRepo.toolCallToTrailblazeTool(evalToolCall) as? StringEvaluationTrailblazeTool
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
   * Helper to prepare screen state and set view hierarchy
   */
  private fun prepareScreenState(): ScreenState {
    val screenState = screenStateProvider()
    ElementRetriever.setViewHierarchy(screenState.viewHierarchy)
    return screenState
  }

  /**
   * Extract all numbers from a prompt text.
   */
  private fun extractNumbersFromPrompt(prompt: String): List<Double> = numberRegex.findAll(prompt)
    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
    .toList()

  /**
   * Extracts a number from a string using regex.
   */
  fun extractNumberFromString(input: String): Double? = numberRegex.find(input)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

  /**
   * Uses LLM to identify the best locator for an element based on description.
   */
  private fun identifyElementLocator(screenState: ScreenState, prompt: String): LocatorResponse {
    println("Identifying element locator for: $prompt")

    val request = createLocatorRequest(screenState, prompt)
    val openAiResponse = runBlocking { openAi.chatCompletion(request) }

    val toolCalls =
      openAiResponse.choices.first().message.toolCalls?.filterIsInstance<ToolCall.Function>() ?: emptyList()
    val locatorToolCall = toolCalls.firstOrNull() ?: return LocatorResponse(
      success = false,
      locatorType = null,
      value = null,
      index = null,
      reason = "No tool call found in LLM response",
    )

    try {
      val elementRetrieverCommand = locatorToolRepo.toolCallToTrailblazeTool(locatorToolCall)
        ?: return parseToolCallManually(locatorToolCall)

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
   * Parse tool call manually as a fallback method
   */
  private fun parseToolCallManually(toolCall: ToolCall.Function): LocatorResponse {
    try {
      val jsonObject = Json.parseToJsonElement(toolCall.function.argumentsAsJson().toString()).jsonObject
      val identifier = jsonObject["identifier"]?.jsonPrimitive?.content
      val locatorTypeStr = jsonObject["locatorType"]?.jsonPrimitive?.content
      val value = jsonObject["value"]?.jsonPrimitive?.content
      val indexStr = jsonObject["index"]?.jsonPrimitive?.content
      val successStr = jsonObject["success"]?.jsonPrimitive?.content
      val reason = jsonObject["reason"]?.jsonPrimitive?.content

      if (identifier != null && locatorTypeStr != null && value != null) {
        try {
          val locatorType = LocatorType.valueOf(locatorTypeStr)
          val index = indexStr?.toIntOrNull() ?: 0
          val success = successStr?.toBoolean() ?: true

          return LocatorResponse(
            success = success,
            locatorType = locatorType,
            value = value,
            index = index,
            reason = reason ?: "",
          )
        } catch (e: Exception) {
          // Failed to create manual command
        }
      }
    } catch (e: Exception) {
      // Manual JSON parse failed
    }

    return LocatorResponse(
      success = false,
      locatorType = null,
      value = null,
      index = null,
      reason = "Could not parse tool call to ElementRetrieverCommand",
    )
  }

  /**
   * Creates a chat completion request for locator identification.
   */
  private fun createLocatorRequest(screenState: ScreenState, prompt: String): ChatCompletionRequest {
    val viewHierarchyJson = Json.encodeToString(ViewHierarchyTreeNode.serializer(), screenState.viewHierarchy)

    val renderedTemplate = TemplatingUtil.renderTemplate(
      template = userPromptTemplate,
      values = mapOf(
        "identifier" to prompt,
        "view_hierarchy" to viewHierarchyJson,
      ),
    )

    return chatCompletionRequest {
      model = ModelId(llmModel.id)
      messages = listOf(
        chatMessage {
          role = ChatRole.System
          content = systemPromptToolTemplate
        },
        chatMessage {
          role = ChatRole.User
          content {
            text(renderedTemplate)
            getBase64EncodedScreenshot(screenState)?.let { base64EncodedScreenshot ->
              image("data:image/png;base64,$base64EncodedScreenshot")
            }
          }
        },
      )
      tools {
        locatorToolRepo.registerManualTools(this)
        toolChoice = ToolChoice.Mode("required") // Force tool usage
      }
    }
  }

  // Setup the tool repo for assertions and evaluations
  private val evaluationToolRepo = TrailblazeToolRepo().apply {
    removeAllTrailblazeTools()
    addTrailblazeTools(
      BooleanAssertionTrailblazeTool::class,
      StringEvaluationTrailblazeTool::class,
    )
  }

  /**
   * Creates a tool-based request for evaluation commands.
   */
  private fun createToolRequest(prompt: String, toolType: String, systemPrompt: String): ChatCompletionRequest {
    val screenState = screenStateProvider()

    return chatCompletionRequest {
      model = ModelId(llmModel.id)
      messages = listOf(
        chatMessage {
          role = ChatRole.System
          content = systemPrompt
        },
        chatMessage {
          role = ChatRole.User
          content {
            text("Evaluate this on the current screen: $prompt")
            getBase64EncodedScreenshot(screenState)?.let { base64EncodedScreenshot ->
              image("data:image/png;base64,$base64EncodedScreenshot")
            }
          }
        },
      )
      tools {
        evaluationToolRepo.registerManualTools(this)
        toolChoice = ToolChoice.Mode("required") // Force tool usage
      }
    }
  }

  /**
   * Helper method to convert screenshot bytes to Base64 string
   */
  @OptIn(ExperimentalEncodingApi::class)
  private fun getBase64EncodedScreenshot(screenState: ScreenState): String? = screenState.screenshotBytes?.let { Base64.encode(it) }
}
