package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.reflect.asToolType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlin.reflect.full.starProjectedType
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.util.Console

/**
 * Koog-based agent that executes UI automation tasks using native tool calling.
 *
 * This agent is designed to work both on-host (via MCP bridge) and on-device
 * (via direct UI Automator). The key abstraction is:
 *
 * - [SamplingSource]: Provides LLM tool calls (local or via MCP sampling)
 * - [TrailblazeAgent]: Executes tools (tap, swipe, input, etc.)
 * - [ScreenStateProvider]: Captures current screen state
 *
 * ## Tool-Only Pattern
 *
 * The agent uses a tool-only pattern where every LLM response is a tool call.
 * This includes special control flow tools:
 * - `completeObjective`: Signals the objective is accomplished
 * - `failObjective`: Signals the objective cannot be completed
 *
 * Reasoning is captured in each tool's `reasoning` parameter.
 *
 * ## Usage
 *
 * ```kotlin
 * val agent = DirectMcpAgent(
 *   samplingSource = localLlmSamplingSource,
 *   trailblazeAgent = androidMaestroAgent,
 *   screenStateProvider = { captureScreenState() },
 * )
 * val result = agent.run("Tap the login button")
 * ```
 */
class DirectMcpAgent(
  private val samplingSource: SamplingSource,
  private val trailblazeAgent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState?,
  private val elementComparator: ElementComparator,
  private val maxIterations: Int = MAX_ITERATIONS,
  private val includeScreenshots: Boolean = true,
  private val trailblazeLogger: TrailblazeLogger? = null,
  private val sessionProvider: TrailblazeSessionProvider? = null,
  private val trailblazeLlmModel: TrailblazeLlmModel? = null,
  /** Which agent architecture this agent is part of */
  private val agentImplementation: AgentImplementation = AgentImplementation.TWO_TIER_AGENT,
  /** How LLM calls are made (DIRECT or MCP_SAMPLING) */
  private val llmCallStrategy: LlmCallStrategy = LlmCallStrategy.DIRECT,
  /** Which tier this agent represents (OUTER for planning, INNER would use ScreenAnalyzer) */
  private val agentTier: AgentTier = AgentTier.OUTER,
  /**
   * Tool repository that provides the available tools to the LLM. Uses the same tool set
   * as TrailblazeRunner/KoogLlmClientHelper — typically getLlmToolSet(setOfMarkEnabled=true)
   * which includes tapOnElementByNodeId, verification tools, and all standard UI tools.
   * When null, falls back to getLlmToolSet(setOfMarkEnabled=true).
   */
  private val trailblazeToolRepo: TrailblazeToolRepo? = null,
) {

  companion object {
    /** Maximum number of iterations before giving up */
    const val MAX_ITERATIONS = 50

    /** Maximum length of objective text to prevent prompt injection via oversized input */
    private const val MAX_OBJECTIVE_LENGTH = 2000

    /**
     * Tool name for objective status - uses ObjectiveStatusTrailblazeTool from TrailblazeToolSet.
     * This tool has 3 states: IN_PROGRESS, COMPLETED, FAILED
     */
    const val TOOL_OBJECTIVE_STATUS = "objectiveStatus"

    /** System prompt for the agent - uses tool-only pattern */
    fun getSystemPrompt(platform: String) = """You are a mobile UI automation assistant for $platform.

You MUST respond with a tool call. Available tools include:
- Tap: tapOnElementByNodeId (use nodeIds from the view hierarchy, shown as [nodeId: X])
- Other UI: swipe, inputText, pressBack, pressKey (for HOME/ENTER), hideKeyboard, eraseText
- Navigation: launchApp, openUrl, scrollUntilTextIsVisible
- Verification: assertVisibleWithNodeId (to check if an element is visible)
- Control flow: objectiveStatus (to report COMPLETED, IN_PROGRESS, or FAILED status)

GUIDELINES:
- ALWAYS use tapOnElementByNodeId with the nodeId of the element you want to tap
- Before tapping buttons, call hideKeyboard if a keyboard may be covering them
- CRITICAL COMPLETION RULES:
  1. After performing the requested action (tap, type, swipe, etc.), call objectiveStatus(COMPLETED) on the NEXT iteration.
  2. If an assertion call succeeds (shown as "→ SUCCESS" in history), the verification is done — call objectiveStatus(COMPLETED).
  3. Do NOT call the same tool repeatedly. If "ACTIONS TAKEN SO FAR" shows a tool already succeeded, move on.
  4. Do NOT repeat assertions for the same element — one successful check is enough.
- Call objectiveStatus with status=FAILED only if you determine the objective cannot be completed after reasonable attempts"""
  }

  /**
   * Runs the agent to accomplish the given objective.
   *
   * Uses the tool-only pattern where every LLM response is a tool call,
   * including control flow (completeObjective, failObjective).
   *
   * @param objective The task to accomplish (e.g., "Tap the login button")
   * @return Result describing success/failure and actions taken
   */
  suspend fun run(objective: String): AgentResult {
    val actions = mutableListOf<String>()
    var iteration = 0
    var lastSuccessfulToolSignature: String? = null
    var consecutiveSuccessCount = 0

    while (iteration < maxIterations) {
      // Fetch tool descriptors each iteration so setActiveToolSets changes take effect
      val tools = getAvailableToolDescriptors()
      iteration++

      // 1. Capture current screen state
      val screenState = screenStateProvider() ?: return AgentResult.Error(
        message = "Failed to capture screen state at iteration $iteration",
        iterations = iteration,
        actionsTaken = actions,
      )

      // 2. Build view hierarchy description
      val vhFilter = ViewHierarchyFilter.create(
        screenWidth = screenState.deviceWidth,
        screenHeight = screenState.deviceHeight,
        platform = screenState.trailblazeDevicePlatform,
      )
      val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchyOriginal)
      val viewHierarchyDescription = buildViewHierarchyDescription(filtered)

      // 3. Build user message with screen state and action history
      val userMessage = buildUserMessage(objective, viewHierarchyDescription, iteration, actions)

      // 4. Get LLM tool call (tool-only pattern)
      // Generate trace ID for this iteration - links LLM request to resulting tool calls
      val iterationTraceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
      val requestStartTime = Clock.System.now()
      val systemPrompt = getSystemPrompt(screenState.trailblazeDevicePlatform.name)
      val samplingResult = samplingSource.sampleToolCall(
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        tools = tools,
        screenshotBytes = if (includeScreenshots) screenState.screenshotBytes else null,
        maxTokens = 1024,
      )

      // Log LLM request if logger is available
      logLlmRequest(
        objective = objective,
        userMessage = userMessage,
        screenState = screenState,
        samplingResult = samplingResult,
        requestStartTime = requestStartTime,
        iteration = iteration,
        systemPrompt = systemPrompt,
        tools = tools,
        traceId = iterationTraceId,
      )

      // 5. Handle the tool call result
      when (samplingResult) {
        is SamplingResult.ToolCall -> {
          val toolName = samplingResult.toolName
          val args = samplingResult.arguments

          // Check for control flow tools (objectiveStatus with COMPLETED/FAILED terminates the loop)
          if (toolName == TOOL_OBJECTIVE_STATUS) {
            actions.add("$toolName($args)")
            val status = args["status"]?.jsonPrimitive?.content?.uppercase()
            val explanation = args["explanation"]?.jsonPrimitive?.content ?: "No explanation provided"

            when (status) {
              "COMPLETED" -> {
                return AgentResult.Success(
                  summary = explanation,
                  iterations = iteration,
                  actionsTaken = actions,
                )
              }
              "FAILED" -> {
                return AgentResult.Failed(
                  reason = explanation,
                  iterations = iteration,
                  actionsTaken = actions,
                )
              }
              // IN_PROGRESS or unknown status - continue the loop
              else -> {
                Console.log("[DirectMcpAgent] Objective status: $status - continuing")
                continue
              }
            }
          } else {
              // Execute UI tool via TrailblazeAgent
              val tool = mapToTrailblazeTool(toolName, args)
              if (tool == null) {
                actions.add("$toolName($args) → UNKNOWN TOOL")
                Console.log("[DirectMcpAgent] Unknown tool: $toolName")
                continue
              }

              // Handle tool-config tools (e.g. setActiveToolSets) that modify the
              // agent's available tool set. These are intercepted here because they
              // operate on the TrailblazeToolRepo, not the device driver.
              if (tool is ConfigTrailblazeTool) {
                val result: TrailblazeToolResult = trailblazeToolRepo?.let { tool.execute(it) }
                  ?: TrailblazeToolResult.Success(message = "Dynamic toolsets not configured.")
                actions.add("$toolName($args) → $result")
                Console.log("[DirectMcpAgent] ToolConfig $toolName: $result")
                continue
              }

              // Wrap the nullable screenStateProvider to satisfy the non-null return type
              // expected by runTrailblazeTools. If the provider returns null (device
              // disconnected), fall back to the cached screenState from this iteration.
              val wrappedScreenStateProvider: (() -> ScreenState)? = screenState?.let { cached ->
                { screenStateProvider() ?: cached }
              }

              // Pass the same trace ID to link tool calls back to the LLM request
              val result = trailblazeAgent.runTrailblazeTools(
                tools = listOf(tool),
                traceId = iterationTraceId,
                screenState = screenState,
                elementComparator = elementComparator,
                screenStateProvider = wrappedScreenStateProvider,
              )

              // Build a stable signature for duplicate detection — strip the
              // free-text "reasoning" field so that tapOnPoint(x=360,y=128)
              // matches even when the reasoning text differs between calls.
              val stableArgs = args.filterKeys { it != "reasoning" }
              val toolSignature = "$toolName($stableArgs)"
              when (val toolResult = result.result) {
                is TrailblazeToolResult.Success -> {
                  actions.add("$toolSignature → SUCCESS")
                  Console.log("[DirectMcpAgent] Tool $toolName succeeded")

                  // Detect repeated successful calls to the same tool — the LLM is
                  // stuck in a loop (common with assertVisible* tools). Auto-complete
                  // if the same tool succeeds 2+ times consecutively.
                  if (toolSignature == lastSuccessfulToolSignature) {
                    consecutiveSuccessCount++
                    if (consecutiveSuccessCount >= 2) {
                      Console.log(
                        "[DirectMcpAgent] Auto-completing: $toolName succeeded " +
                          "$consecutiveSuccessCount times consecutively"
                      )
                      return AgentResult.Success(
                        summary = "Auto-completed after $toolName succeeded repeatedly",
                        iterations = iteration,
                        actionsTaken = actions,
                      )
                    }
                  } else {
                    lastSuccessfulToolSignature = toolSignature
                    consecutiveSuccessCount = 1
                  }
                }
                is TrailblazeToolResult.Error -> {
                  actions.add("$toolSignature → FAILED: ${toolResult.errorMessage}")
                  Console.log("[DirectMcpAgent] Tool $toolName failed: ${toolResult.errorMessage}")
                  lastSuccessfulToolSignature = null
                  consecutiveSuccessCount = 0
                }
              }
            }
        }

        is SamplingResult.Text -> {
          // Shouldn't happen with sampleToolCall, but handle gracefully
          Console.log("[DirectMcpAgent] Unexpected text response: ${samplingResult.completion.take(100)}...")
          // Continue to next iteration
        }

        is SamplingResult.Error -> {
          return AgentResult.Error(
            message = "LLM sampling failed: ${samplingResult.message}",
            iterations = iteration,
            actionsTaken = actions,
          )
        }
      }
    }

    return AgentResult.Error(
      message = "Max iterations ($maxIterations) reached without completing objective",
      iterations = maxIterations,
      actionsTaken = actions,
    )
  }

  /**
   * Maps a tool name and JSON args to a TrailblazeTool using the existing JSON deserialization infrastructure.
   * Uses "toolName" field which is recognized by OtherTrailblazeToolSerializer (see TrailblazeJson.kt).
   */
  private fun mapToTrailblazeTool(toolName: String, args: JsonObject): TrailblazeTool? {
    return try {
      // OtherTrailblazeToolSerializer looks for "toolName" to match tool classes by their ToolName
      // (see OtherTrailblazeToolSerializer.deserialize() which checks jsonObject["toolName"])
      val toolJson = buildJsonObject {
        put("toolName", toolName)
        args.entries.forEach { (key, value) ->
          put(key, value)
        }
      }
      
      // Use the existing JSON deserializer infrastructure
      TrailblazeJsonInstance.decodeFromString<TrailblazeTool>(toolJson.toString())
    } catch (e: Exception) {
      Console.log("[DirectMcpAgent] Failed to deserialize tool '$toolName': ${e.message}")
      null
    }
  }

  private fun buildUserMessage(
    objective: String,
    viewHierarchy: String,
    iteration: Int,
    previousActions: List<String> = emptyList(),
  ): String = buildString {
    // Sanitize objective to prevent prompt injection via embedded control characters
    val sanitizedObjective = objective.take(MAX_OBJECTIVE_LENGTH).replace(Regex("[\r\n]+"), " ")
    appendLine("OBJECTIVE: $sanitizedObjective")
    appendLine()
    appendLine("ITERATION: $iteration of $maxIterations")
    appendLine()
    if (previousActions.isNotEmpty()) {
      appendLine("ACTIONS TAKEN SO FAR:")
      previousActions.forEach { appendLine("  - $it") }
      appendLine()
    }
    appendLine("CURRENT SCREEN STATE:")
    appendLine(viewHierarchy)
    appendLine()
    appendLine("Call a tool to make progress toward the objective.")
  }

  private fun buildViewHierarchyDescription(
    node: ViewHierarchyTreeNode,
    depth: Int = 0,
  ): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine = if (selectorDescription != null) {
      val nodeIdPrefix = "[nodeId:${node.nodeId}] "
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      "$indent$nodeIdPrefix$selectorDescription$positionSuffix"
    } else {
      null
    }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildViewHierarchyDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  /**
   * Logs the LLM request to TrailblazeLogger if available.
   * This provides visibility into what the agent is thinking and which tools are available.
   *
   * @param traceId The trace ID for this iteration - links LLM request to resulting tool calls
   */
  private fun logLlmRequest(
    objective: String,
    userMessage: String,
    screenState: ScreenState,
    samplingResult: SamplingResult,
    requestStartTime: Instant,
    iteration: Int,
    systemPrompt: String,
    tools: List<TrailblazeToolDescriptor>,
    traceId: TraceId,
  ) {
    // Only log if logger, session provider, and LLM model are available
    val logger = trailblazeLogger ?: return
    val provider = sessionProvider ?: return
    val llmModel = trailblazeLlmModel ?: return

    try {
      val session = provider.invoke()
      val metaInfo = RequestMetaInfo(kotlin.time.Clock.System.now())

      // Build messages for the log (system + user)
      val messages = listOf(
        Message.System(content = systemPrompt, metaInfo = metaInfo),
        Message.User(content = userMessage, metaInfo = metaInfo),
      )

      // NOTE: DirectMcpAgent uses tool calls from SamplingSource, not full Koog LLM responses
      // We pass an empty response list - the logging will still show the request side
      val responses = emptyList<Message.Response>()

      // Build PromptStepStatus for logging (requires non-null screen state provider)
      val nonNullScreenStateProvider: () -> ScreenState = {
        screenStateProvider() ?: screenState
      }
      
      val promptStepStatus = PromptStepStatus(
        promptStep = DirectionStep(
          step = objective,
          recording = null,
        ),
        screenStateProvider = nonNullScreenStateProvider,
      )
      
      // Initialize the screen state for logging
      promptStepStatus.prepareNextStep()

      // Convert to Koog ToolDescriptor format
      val stringType = String::class.starProjectedType.asToolType()
      val koogToolDescriptors = tools.map { trailblazeDesc: TrailblazeToolDescriptor ->
        ToolDescriptor(
          name = trailblazeDesc.name,
          description = trailblazeDesc.description ?: "",
          requiredParameters = trailblazeDesc.requiredParameters.map { param ->
            ToolParameterDescriptor(
              name = param.name,
              type = stringType,
              description = param.description ?: "",
            )
          },
          optionalParameters = trailblazeDesc.optionalParameters.map { param ->
            ToolParameterDescriptor(
              name = param.name,
              type = stringType,
              description = param.description ?: "",
            )
          },
        )
      }

      // Log the request with token usage from sampling result
      // Use the passed-in traceId to link this LLM request to resulting tool calls
      logger.logLlmRequest(
        session = session,
        koogLlmRequestMessages = messages,
        stepStatus = promptStepStatus,
        trailblazeLlmModel = llmModel,
        response = responses,
        startTime = requestStartTime,
        traceId = traceId,
        toolDescriptors = koogToolDescriptors,
        requestContext = TrailblazeLog.LlmRequestContext(
          agentImplementation = agentImplementation,
          llmCallStrategy = llmCallStrategy,
          agentTier = agentTier,
        ),
        tokenUsage = samplingResult.tokenUsage,
        llmRequestLabel = when (agentTier) {
          AgentTier.INNER -> "Screen Analyzer"
          AgentTier.OUTER -> "Outer Agent"
        },
      )
    } catch (e: Exception) {
      // Log errors but don't fail the agent execution
      Console.error("[DirectMcpAgent] Failed to log LLM request: ${e.message}")
      Console.error(e.stackTraceToString())
    }
  }

  /**
   * Returns tool descriptors for all tools available to this agent.
   * Includes UI interaction tools and objectiveStatus for control flow (COMPLETED/FAILED terminates the loop).
   */
  private fun getAvailableToolDescriptors(): List<TrailblazeToolDescriptor> {
    // Use the tool repo if provided (matches the tool set configured by the caller, e.g.
    // getLlmToolSet(setOfMarkEnabled) which includes tapOnElementByNodeId, verification tools,
    // and all standard UI tools). Falls back to getLlmToolSet(true) for Set-of-Mark tools.
    if (trailblazeToolRepo != null) {
      return trailblazeToolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() }
    }
    return TrailblazeToolSet.getLlmToolSet(setOfMarkEnabled = true).asTools().mapNotNull { toolClass ->
      toolClass.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
    }
  }
}

/** Result of running the direct agent */
sealed interface AgentResult {
  val iterations: Int
  val actionsTaken: List<String>

  data class Success(
    val summary: String,
    override val iterations: Int,
    override val actionsTaken: List<String>,
  ) : AgentResult

  data class Failed(
    val reason: String,
    override val iterations: Int,
    override val actionsTaken: List<String>,
  ) : AgentResult

  data class Error(
    val message: String,
    override val iterations: Int,
    override val actionsTaken: List<String> = emptyList(),
  ) : AgentResult
}
