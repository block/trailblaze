package xyz.block.trailblaze.mcp.sampling

import io.ktor.util.encodeBase64
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.SamplingResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable
import kotlin.reflect.KClass

/**
 * Orchestrates multi-step UI automation using MCP Sampling.
 *
 * This implements the subagent pattern:
 * 1. Accept a high-level objective from the MCP client
 * 2. Loop:
 *    a. Capture current screen state
 *    b. Send screen state + objective to client's LLM via sampling/createMessage
 *    c. Parse LLM response for the next action
 *    d. Execute the action using Trailblaze tools
 *    e. Check if objective is complete or failed
 * 3. Return final result
 *
 * Each iteration uses a fresh LLM context (no accumulated history),
 * preventing context window exhaustion on long automation tasks.
 */
class SubagentOrchestrator(
  private val sessionContext: TrailblazeMcpSessionContext,
  private val mcpBridge: TrailblazeMcpBridge,
  private val screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
) {
  private val samplingClient = McpSamplingClient(sessionContext)

  /** Tool classes available for subagent orchestration */
  private val availableToolClasses: Set<KClass<out TrailblazeTool>> =
    ToolSetCategoryMapping.getToolClasses(ToolSetCategory.CORE_INTERACTION) +
      ToolSetCategoryMapping.getToolClasses(ToolSetCategory.NAVIGATION)

  /** Map of tool name -> tool class for deserialization */
  private val toolClassByName: Map<String, KClass<out TrailblazeTool>> =
    availableToolClasses.associateBy { it.trailblazeToolClassAnnotation().name }

  companion object {
    /** Maximum number of LLM requests before giving up (safety valve) */
    const val MAX_ITERATIONS = 50

    /** Base system prompt - tool descriptions are appended dynamically */
    private const val SYSTEM_PROMPT_BASE = """You are a mobile UI automation assistant. You can see the current screen state and must determine the single best action to accomplish the given objective.

RESPONSE FORMAT:
You MUST respond with valid JSON in one of these formats:

For tool calls:
{"tool": "toolName", "args": {"param1": "value1", "param2": "value2"}}

For completion:
{"complete": true, "summary": "Description of what was accomplished"}

For failure:
{"failed": true, "reason": "Why the objective cannot be achieved"}

RULES:
- Respond with EXACTLY ONE JSON object
- Look at the view hierarchy to find element coordinates for tapOnPoint
- Call complete/failed when the objective is achieved or cannot be achieved
- If stuck after several attempts, respond with failed"""
  }

  /** Generate the full system prompt with tool descriptions from actual tool classes */
  private fun generateSystemPrompt(): String = buildString {
    appendLine(SYSTEM_PROMPT_BASE)
    appendLine()
    appendLine("AVAILABLE TOOLS:")
    appendLine()

    availableToolClasses.forEach { toolClass ->
      val descriptor = toolClass.toKoogToolDescriptor() ?: return@forEach
      appendLine("## ${descriptor.name}")
      appendLine(descriptor.description)
      if (descriptor.requiredParameters.isNotEmpty()) {
        appendLine("Required parameters:")
        descriptor.requiredParameters.forEach { param ->
          appendLine("  - ${param.name} (${param.type}): ${param.description}")
        }
      }
      if (descriptor.optionalParameters.isNotEmpty()) {
        appendLine("Optional parameters:")
        descriptor.optionalParameters.forEach { param ->
          appendLine("  - ${param.name} (${param.type}): ${param.description}")
        }
      }
      appendLine()
    }
  }

  /**
   * Runs a multi-step automation task using the client's LLM for reasoning.
   *
   * @param objective The high-level objective to accomplish (e.g., "Log in and check my balance")
   * @param includeScreenshots Whether to include screenshots in sampling requests
   * @return Result describing success/failure and actions taken
   */
  suspend fun runObjective(
    objective: String,
    includeScreenshots: Boolean = true,
  ): OrchestrationResult {
    // Check prerequisites
    if (!samplingClient.isSamplingSupported()) {
      return OrchestrationResult.Error(
        "MCP client does not support sampling. Cannot use subagent mode.",
      )
    }

    val actions = mutableListOf<String>()
    var iteration = 0

    while (iteration < MAX_ITERATIONS) {
      iteration++

      // 1. Capture current screen state
      val screenState = captureScreenState()
      if (screenState == null) {
        return OrchestrationResult.Error(
          "Failed to capture screen state at iteration $iteration",
          actionsTaken = actions,
        )
      }

      // 2. Build screen state for sampling
      val screenStateForSampling = buildScreenStateForSampling(
        screenState = screenState,
        includeScreenshot = includeScreenshots,
      )

      // 3. Request next action from client's LLM
      val userMessage = buildUserMessage(objective, screenStateForSampling, iteration)
      val samplingResult = samplingClient.requestCompletion(
        systemPrompt = generateSystemPrompt(),
        userMessage = userMessage,
        screenshotBase64 = screenStateForSampling.screenshotBase64,
      )

      // 4. Handle sampling result
      @Suppress("DEPRECATION")
      when (samplingResult) {
        is SamplingResult.Error -> {
          return OrchestrationResult.Error(
            "Sampling failed at iteration $iteration: ${samplingResult.message}",
            actionsTaken = actions,
          )
        }
        is SamplingResult.ToolCall -> {
          // SubagentOrchestrator uses text-based parsing, not native tool calls
          // This shouldn't happen with the deprecated requestCompletion method
          actions.add("[$iteration] Unexpected tool call: ${samplingResult.toolName}")
          // Continue loop - this is an unexpected state
        }
        is SamplingResult.Text -> {
          val response = samplingResult.completion.trim()
          actions.add("[$iteration] LLM: $response")

          // 5. Parse and execute the action
          val parseResult = parseAction(response)
          when (parseResult) {
            is ParsedAction.Complete -> {
              return OrchestrationResult.Success(
                summary = parseResult.summary,
                actionsTaken = actions,
                iterations = iteration,
              )
            }
            is ParsedAction.Failed -> {
              return OrchestrationResult.Failed(
                reason = parseResult.reason,
                actionsTaken = actions,
                iterations = iteration,
              )
            }
            is ParsedAction.Tool -> {
              // Execute the tool
              val toolResult = executeAction(parseResult)
              actions.add("[$iteration] Executed: ${parseResult.name} -> $toolResult")

              if (toolResult.startsWith("[ERROR]")) {
                // Tool execution failed, continue to let LLM see the error
                actions.add("[$iteration] Tool error, continuing...")
              }
              // Continue loop for next iteration
            }
            is ParsedAction.Unknown -> {
              actions.add("[$iteration] Could not parse response, asking LLM to clarify...")
              // Continue loop - LLM will see same screen and hopefully respond better
            }
          }
        }
      }
    }

    return OrchestrationResult.Error(
      "Exceeded maximum iterations ($MAX_ITERATIONS). Agent may be stuck.",
      actionsTaken = actions,
    )
  }

  /**
   * Captures the current screen state using the shared utility.
   */
  private suspend fun captureScreenState(): ScreenState? =
    ScreenStateCaptureUtil.captureScreenState(
      mcpBridge = mcpBridge,
      screenshotScalingConfig = screenshotScalingConfig
    )

  private fun buildScreenStateForSampling(
    screenState: ScreenState,
    includeScreenshot: Boolean,
  ): ScreenStateForSampling {
    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

    return ScreenStateForSampling(
      viewHierarchy = buildViewHierarchyText(filtered),
      screenshotBase64 = if (includeScreenshot) screenState.screenshotBytes?.encodeBase64() else null,
      deviceWidth = screenState.deviceWidth,
      deviceHeight = screenState.deviceHeight,
    )
  }

  private fun buildViewHierarchyText(node: ViewHierarchyTreeNode): String {
    val elements = mutableListOf<String>()
    collectInteractableElements(node, elements)
    return if (elements.isEmpty()) {
      "No interactable elements found on screen."
    } else {
      elements.joinToString("\n")
    }
  }

  private fun collectInteractableElements(
    node: ViewHierarchyTreeNode,
    elements: MutableList<String>,
  ) {
    if (node.isInteractable()) {
      val selector = node.asTrailblazeElementSelector()
      val description = selector?.description() ?: node.className
      val position = node.centerPoint?.let { "@($it)" } ?: ""
      elements.add("- $description $position")
    }
    node.children.forEach { child -> collectInteractableElements(child, elements) }
  }

  private fun buildUserMessage(
    objective: String,
    screenState: ScreenStateForSampling,
    iteration: Int,
  ): String = buildString {
    appendLine("OBJECTIVE: $objective")
    appendLine()
    appendLine("ITERATION: $iteration of $MAX_ITERATIONS")
    appendLine()
    appendLine("CURRENT SCREEN (${screenState.deviceWidth}x${screenState.deviceHeight}):")
    appendLine(screenState.viewHierarchy)
    appendLine()
    appendLine("What is the single best action to take next?")
  }

  /** Exposed for testing - parses an action from LLM response */
  internal fun parseAction(response: String): ParsedAction {
    // Extract JSON from response (LLM might include extra text)
    val jsonString = extractJsonFromResponse(response) ?: return ParsedAction.Unknown(response)

    return try {
      val json = TrailblazeJsonInstance.decodeFromString<JsonObject>(jsonString)

      // Check for completion
      if (json["complete"]?.jsonPrimitive?.content == "true") {
        val summary = json["summary"]?.jsonPrimitive?.content ?: "Objective completed"
        return ParsedAction.Complete(summary)
      }

      // Check for failure
      if (json["failed"]?.jsonPrimitive?.content == "true") {
        val reason = json["reason"]?.jsonPrimitive?.content ?: "Unknown failure reason"
        return ParsedAction.Failed(reason)
      }

      // Check for tool call
      val toolName = json["tool"]?.jsonPrimitive?.content
      if (toolName != null) {
        val args = json["args"]?.jsonObject ?: JsonObject(emptyMap())
        return ParsedAction.Tool(name = toolName, args = args)
      }

      ParsedAction.Unknown(response)
    } catch (e: Exception) {
      ParsedAction.Unknown(response)
    }
  }

  /** Exposed for testing - extracts JSON object from LLM response (handles markdown code blocks, etc.) */
  internal fun extractJsonFromResponse(response: String): String? {
    val trimmed = response.trim()

    // Try direct JSON parse first
    if (trimmed.startsWith("{")) {
      return trimmed
    }

    // Try extracting from markdown code block
    val codeBlockMatch = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""").find(trimmed)
    if (codeBlockMatch != null) {
      return codeBlockMatch.groupValues[1]
    }

    // Try finding a JSON object anywhere in the response
    val jsonMatch = Regex("""\{[^{}]*\}""").find(trimmed)
    return jsonMatch?.value
  }

  private suspend fun executeAction(action: ParsedAction.Tool): String {
    val toolClass = toolClassByName[action.name]
      ?: return "[ERROR] Unknown tool: ${action.name}. Available: ${toolClassByName.keys}"

    return try {
      // Build JSON with the tool discriminator and deserialize
      val toolJson = buildToolJsonString(action.name, action.args)
      val tool = TrailblazeJsonInstance.decodeFromString<TrailblazeTool>(toolJson)
      // Guard against silent fallback to OtherTrailblazeTool — the polymorphic serializer
      // catches typed deserialization failures and falls back to OtherTrailblazeTool with
      // potentially wrong/empty params. Reject so the error surfaces.
      if (tool is OtherTrailblazeTool) {
        return "[ERROR] Tool '${action.name}' deserialized as OtherTrailblazeTool — typed serializer likely failed. Args: ${action.args}"
      }
      mcpBridge.executeTrailblazeTool(tool)
    } catch (e: Exception) {
      "[ERROR] Failed to deserialize or execute tool '${action.name}': ${e.message}"
    }
  }

  /** Build a JSON string for tool deserialization with the discriminator */
  private fun buildToolJsonString(toolName: String, args: JsonObject): String {
    // OtherTrailblazeToolSerializer looks for "toolName" to match tool classes by their ToolName
    // (see OtherTrailblazeToolSerializer.deserialize() which checks jsonObject["toolName"])
    val mutableMap = args.toMutableMap()
    mutableMap["toolName"] = JsonPrimitive(toolName)
    return JsonObject(mutableMap).toString()
  }
}

/** Parsed action from LLM response */
sealed class ParsedAction {
  data class Tool(val name: String, val args: JsonObject) : ParsedAction()
  data class Complete(val summary: String) : ParsedAction()
  data class Failed(val reason: String) : ParsedAction()
  data class Unknown(val rawResponse: String) : ParsedAction()
}

/** Result of orchestration */
sealed class OrchestrationResult {
  data class Success(
    val summary: String,
    val actionsTaken: List<String>,
    val iterations: Int,
  ) : OrchestrationResult()

  data class Failed(
    val reason: String,
    val actionsTaken: List<String>,
    val iterations: Int,
  ) : OrchestrationResult()

  data class Error(
    val message: String,
    val actionsTaken: List<String> = emptyList(),
  ) : OrchestrationResult()
}
