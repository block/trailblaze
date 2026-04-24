package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.asToolType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector

/**
 * Implementation of [ScreenAnalyzer] that performs single-shot screen analysis.
 *
 * This implementation:
 * - Makes a single LLM call per [analyze] invocation (no loops)
 * - Uses vision capabilities to analyze screenshots
 * - Returns structured [ScreenAnalysis] with recommendation and context
 *
 * Designed for use as the "inner agent" in the two-tier architecture,
 * where cheap, fast analysis is needed for each screen state.
 *
 * ## Usage
 *
 * ```kotlin
 * val analyzer = InnerLoopScreenAnalyzer(
 *   samplingSource = localLlmSamplingSource,
 *   model = TrailblazeLlmModels.GPT_4O_MINI,
 * )
 *
 * val analysis = analyzer.analyze(
 *   context = RecommendationContext(objective = "Tap the login button"),
 *   screenState = currentScreenState,
 * )
 *
 * // Use analysis.recommendedTool, analysis.recommendedArgs, etc.
 * ```
 *
 * @param samplingSource Source for LLM completions (local or MCP-based)
 * @param model The LLM model to use (should be a cheap vision model for inner agent)
 * @param systemPromptOverride Optional custom system prompt (for testing)
 */
class InnerLoopScreenAnalyzer(
  private val samplingSource: SamplingSource,
  /** Model used for this analyzer - used for cost tracking and metrics */
  @Suppress("unused") // Used for cost tracking via AgentTierCostTracker
  private val model: TrailblazeLlmModel,
  private val systemPromptOverride: String? = null,
) : ScreenAnalyzer {

  companion object {
    /**
     * Loads the system prompt from resources.
     * Falls back to embedded prompt if resource loading fails.
     */
    fun loadSystemPrompt(): String {
      return try {
        InnerLoopScreenAnalyzer::class.java
          .getResourceAsStream("/screen_analyzer_prompt.md")
          ?.bufferedReader()
          ?.readText()
          ?: FALLBACK_SYSTEM_PROMPT
      } catch (_: Exception) {
        FALLBACK_SYSTEM_PROMPT
      }
    }

    /** Maximum tokens for the analysis response */
    private const val MAX_TOKENS = 2048

    /** Matches date strings like "Tue, Feb 10, 2026", "Feb 10, 2026", "02/10/2026", "2026-02-10". */
    private val DATE_PATTERN = Regex(
      """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\s+)?""" +  // optional day name
        """(?:""" +
        """(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2},?\s+\d{4}""" +  // "Feb 10, 2026"
        """|""" +
        """\d{1,2}/\d{1,2}/\d{2,4}""" +  // "02/10/2026"
        """|""" +
        """\d{4}-\d{2}-\d{2}""" +  // "2026-02-10"
        """)"""
    )

    /** Matches time strings like "2:00 PM", "14:00", "4:30 AM". */
    private val TIME_PATTERN = Regex(
      """\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)""" +  // "2:00 PM"
        """|""" +
        """\d{1,2}:\d{2}"""  // "14:00"
    )

    /**
     * Builds an enum values description string from an enum class.
     * 
     * Derives allowed values from the actual enum definition rather than
     * hardcoding strings, ensuring consistency and enabling refactoring.
     */
    private inline fun <reified T : Enum<T>> enumValuesDescription(): String {
      return enumValues<T>().joinToString("|") { it.name }
    }

    /**
     * Analysis parameters added to each tool for structured output.
     *
     * TOKEN OPTIMIZATION: Descriptions are MINIMAL because full descriptions are in the
     * system prompt. This saves ~100 tokens/tool × 17 tools = ~1,700 tokens!
     * The system prompt explains: reasoning, screenSummary, confidence meanings.
     *
     * Field names are derived from [ToolCallAnalysisResponse] property references
     * to ensure they stay in sync with the deserialization target.
     */
    private val ANALYSIS_REQUIRED_PARAMS = listOf(
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::reasoning.name,
        description = "(see system prompt)",
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::screenSummary.name,
        description = "(see system prompt)",
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::confidence.name,
        description = enumValuesDescription<Confidence>(),
        type = String::class.starProjectedType.asToolType(),
      ),
    )

    /**
     * Optional analysis parameters - kept minimal for token efficiency.
     *
     * Field names derived from [ToolCallAnalysisResponse] property references.
     */
    private val ANALYSIS_OPTIONAL_PARAMS = listOf(
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::objectiveAppearsAchieved.name,
        description = "true if done",
        type = Boolean::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::objectiveAppearsImpossible.name,
        description = "true if blocked",
        type = Boolean::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::answer.name,
        description = "Direct answer when objective is a question",
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::suggestedHint.name,
        description = "request different tools: NAVIGATION, VERIFICATION, STANDARD, or specific tool name",
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::screenState.name,
        description = enumValuesDescription<ExceptionalScreenState>(),
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::recoveryAction.name,
        description = "JSON recovery: {type, ...params} - see prompt for format",
        type = String::class.starProjectedType.asToolType(),
      ),
      ToolParameterDescriptor(
        name = ToolCallAnalysisResponse::detectionConfidence.name,
        description = "0.0-1.0 confidence score for exceptional state detection",
        type = String::class.starProjectedType.asToolType(),
      ),
    )

    /**
     * Wraps a tool descriptor with analysis parameters.
     *
     * Takes a tool like `tapOnElementByNodeId(nodeId: Int)` and creates:
     * `tapOnElementByNodeId(nodeId: Int, reasoning: String, confidence: String, ...)`
     *
     * This allows the LLM to call the actual tool while also providing analysis.
     */
    fun wrapToolWithAnalysis(tool: TrailblazeToolDescriptor): ToolDescriptor {
      // Convert TrailblazeToolParameterDescriptor to Koog ToolParameterDescriptor
      val originalRequired = tool.requiredParameters.map { param ->
        ToolParameterDescriptor(
          name = param.name,
          description = param.description ?: "",
          type = String::class.starProjectedType.asToolType(), // Default to string - actual type is in schema
        )
      }
      val originalOptional = tool.optionalParameters.map { param ->
        ToolParameterDescriptor(
          name = param.name,
          description = param.description ?: "",
          type = String::class.starProjectedType.asToolType(),
        )
      }

      return ToolDescriptor(
        name = tool.name,
        description = buildString {
          append(tool.description ?: "Execute this UI action")
          append("\n\nYou MUST also provide reasoning, screenSummary, and confidence.")
        },
        requiredParameters = originalRequired + ANALYSIS_REQUIRED_PARAMS,
        optionalParameters = originalOptional + ANALYSIS_OPTIONAL_PARAMS,
      )
    }

    /**
     * Wraps multiple tools with analysis parameters.
     */
    fun wrapToolsWithAnalysis(tools: List<TrailblazeToolDescriptor>): List<ToolDescriptor> {
      return tools.map { wrapToolWithAnalysis(it) }
    }

    /**
     * Parses a tool call response into a fully populated [ScreenAnalysis].
     *
     * Deserializes analysis metadata from the tool call arguments using
     * [ToolCallAnalysisResponse], then converts to the strongly-typed [ScreenAnalysis].
     * Action-specific parameters (x, y, nodeId, etc.) are automatically separated
     * from analysis fields.
     *
     * @param toolName The name of the tool that was called (e.g., "tapOnPoint")
     * @param arguments The full JSON arguments including both action params and analysis metadata
     * @return A fully populated [ScreenAnalysis] with all fields parsed
     */
    fun parseToolCallWithAnalysis(toolName: String, arguments: JsonObject): ScreenAnalysis {
      val response = ToolCallAnalysisResponse.fromToolCallArguments(arguments)
      return response.toScreenAnalysis(toolName, arguments)
    }

    /**
     * Fallback system prompt used if resource loading fails.
     * The full prompt is in screen_analyzer_prompt.md — keep them in sync.
     */
    private val FALLBACK_SYSTEM_PROMPT = """
# Screen Analyzer

Analyze the screen and call ONE tool to progress toward the objective.

## Rules
1. Call ONE tool based on current screen state
2. When a view hierarchy with nodeIds is provided, use the coordinates from the node annotations to tap precisely
3. Use an app-launch tool to open apps (not tap on icons) - set `suggestedToolHint: "NAVIGATION"` if unavailable
4. Use exact app names from objective (don't autocorrect)
5. If objective is "Answer this question:" → call the status/objective tool with the `answer` field containing a direct answer to the question

## Required Fields (add to every tool call)
- `reasoning`: Why this action achieves the objective
- `screenSummary`: Brief description of current screen
- `confidence`: HIGH / MEDIUM / LOW

## Optional Fields
- `answer`: When the objective is a question, provide a direct answer here (not in reasoning)
- `objectiveAppearsAchieved`: true if objective is already complete
- `objectiveAppearsImpossible`: true if blocked by error/missing feature
- `suggestedToolHint`: NAVIGATION | VERIFICATION | STANDARD | specific tool name
- `screenState`: Set when screen is NOT normal (see below)
- `recoveryAction`: JSON recovery strategy {type, ...params}
- `detectionConfidence`: 0.0-1.0 confidence in exceptional state detection

## Exceptional Screen States

Before acting, check if the screen shows a non-normal state. If so, set `screenState` and `recoveryAction`.

- POPUP_DIALOG: dialog/modal/permission → {"type":"DismissPopup","dismissTarget":"button description"}
- ADVERTISEMENT: ad overlay/skip button → {"type":"SkipAd","skipMethod":"tap X|wait for skip","waitSeconds":0}
- LOADING: spinner/progress/skeleton → {"type":"WaitForLoading","maxWaitSeconds":10}
- ERROR_STATE: error/crash/retry prompt → {"type":"HandleError","strategy":"tap retry|go back"}
- LOGIN_REQUIRED: unexpected login wall → {"type":"RequiresLogin","loginRequired":true}
- CAPTCHA: verification challenge → {"type":"HandleCaptcha","description":"CAPTCHA type"}
- KEYBOARD_VISIBLE: keyboard covering content → {"type":"DismissKeyboard","dismissMethod":"tap empty area"}
- RATE_LIMITED: throttling message → {"type":"HandleRateLimited","waitSeconds":60}
- SYSTEM_OVERLAY: system notification → {"type":"DismissOverlay","dismissMethod":"swipe away|press back"}
- APP_NOT_RESPONDING: ANR/frozen UI → {"type":"RestartApp","packageId":"com.example.app"}
    """.trimIndent()
  }

  override suspend fun analyze(
    context: RecommendationContext,
    screenState: ScreenState,
    traceId: TraceId?,
    availableTools: List<TrailblazeToolDescriptor>,
  ): ScreenAnalysis {
    // 1. Build view hierarchy description
    val viewHierarchyDescription = buildViewHierarchyDescription(screenState)

    // 2. Build user message with context and screen state
    val userMessage = buildUserMessage(context, viewHierarchyDescription)

    // 3. Get system prompt
    val systemPrompt = systemPromptOverride ?: loadSystemPrompt()

    // 4. Build screen context for enhanced logging
    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filteredHierarchy = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)
    val screenContext = ScreenContext(
      viewHierarchy = screenState.viewHierarchy,
      viewHierarchyFiltered = filteredHierarchy,
      deviceWidth = screenState.deviceWidth,
      deviceHeight = screenState.deviceHeight,
    )

    // 5. Wrap available tools with analysis parameters
    // Each tool like tapOnElementByNodeId(nodeId) becomes:
    // tapOnElementByNodeId(nodeId, reasoning, screenSummary, confidence, ...)
    // This gives us TYPE-SAFE tool selection - LLM calls the actual tool!
    val wrappedTools = if (availableTools.isNotEmpty()) {
      wrapToolsWithAnalysis(availableTools)
    } else {
      // Fallback: use default tools if none provided
      Console.log("[InnerLoopScreenAnalyzer] WARNING: No tools provided, using empty list")
      emptyList()
    }

    if (wrappedTools.isEmpty()) {
      return ScreenAnalysis(
        recommendedTool = "wait",
        recommendedArgs = buildJsonObject { put("seconds", 1) },
        reasoning = "No tools available to analyze screen",
        screenSummary = "Error: No tools provided",
        confidence = Confidence.LOW,
        objectiveAppearsImpossible = true,
      )
    }

    // 6. Make single LLM call with actual tools (not a meta-tool!)
    // The LLM MUST call one of the wrapped tools, giving us:
    // - Which tool was selected (type-safe)
    // - Tool arguments (validated against schema)
    // - Analysis metadata (reasoning, confidence, etc.)
    // In fast mode, skip sending screenshots to the LLM — text-only analysis
    // using the compact element list. This saves vision tokens and latency.
    val screenshotBytes = if (context.fast) null
      else screenState.annotatedScreenshotBytes ?: screenState.screenshotBytes

    val samplingResult = samplingSource.sampleToolCallWithKoogTools(
      systemPrompt = systemPrompt,
      userMessage = userMessage,
      koogTools = wrappedTools,
      screenshotBytes = screenshotBytes,
      maxTokens = MAX_TOKENS,
      traceId = traceId,
      screenContext = screenContext,
    )

    // 7. Parse the tool call result into ScreenAnalysis
    // Now we know WHICH tool was called (samplingResult.toolName) and its args!
    return parseAnalysis(samplingResult)
  }

  /**
   * Builds a text description of the view hierarchy for the LLM.
   *
   * For Compose drivers, uses the compact element list with `[eN]` IDs from
   * [ScreenState.viewHierarchyTextRepresentation]. These IDs match what `compose_click`,
   * `compose_type`, etc. expect in their `elementId` parameter.
   *
   * For all other drivers (Maestro, on-device), filters and formats the
   * [ViewHierarchyTreeNode] tree with `[nodeId:X]` for standard tool compatibility.
   */
  private fun buildViewHierarchyDescription(screenState: ScreenState): String {
    // Prefer the platform-native compact text representation when available.
    // This provides hierarchical, indented output with element refs and state annotations:
    // - Web/Playwright: ARIA roles with [eN] refs
    // - Android: native class names with [nID] refs and state annotations
    // - iOS: UIKit class names with [nID] refs and state annotations
    // - Compose: semantics tree with [eN] refs
    screenState.viewHierarchyTextRepresentation?.let { return it }

    // Fallback for drivers that don't provide a text representation (e.g., legacy Maestro):
    // filter to interactable elements and generate description from the tree.
    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val root = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)
    return buildNodeDescription(root, depth = 0)
  }

  /**
   * Recursively builds a text description of a view hierarchy node.
   *
   * Nodes with `hintText` are annotated as text input fields so the LLM knows
   * to use the `type` tool instead of `click` to enter text. Without this,
   * placeholder text like "First name" looks identical to a label.
   *
   * Nodes whose text matches common date/time patterns are annotated with
   * [date] or [time] to help the LLM identify tappable date/time fields
   * that lack resource IDs (e.g., "Tue, Feb 10, 2026" in Google Calendar).
   */
  private fun buildNodeDescription(
    node: ViewHierarchyTreeNode,
    depth: Int,
  ): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine = if (selectorDescription != null) {
      val nodeIdPrefix = "[nodeId:${node.nodeId}] "
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      // Mark nodes with hintText as text input fields — this tells the LLM to use
      // the 'type' tool to enter text rather than just clicking the field.
      val inputHint = if (!node.hintText.isNullOrBlank()) ", text input" else ""
      val semanticHint = detectSemanticType(node.text)
      val boundsHint = buildBoundsHint(node)
      "$indent$nodeIdPrefix$selectorDescription$inputHint$semanticHint$positionSuffix$boundsHint"
    } else {
      null
    }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildNodeDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  /**
   * Builds a clickable bounds annotation for nodes whose tappable area is significantly
   * larger than their text center point suggests.
   *
   * This prevents the LLM from thinking space adjacent to a narrow text label is "empty"
   * when it's actually part of a full-width clickable row. For example, the "Local" calendar
   * picker text is centered at @247,489 but the clickable row spans the full screen width
   * (0,460 to 1080,520). Without this annotation, the LLM might tap at (600, 500) thinking
   * it's empty space, but it actually triggers the calendar picker.
   *
   * Only annotated when the clickable area is meaningfully larger than a tight fit around
   * the center point (>= 1.5x wider or taller than a minimum threshold).
   */
  private fun buildBoundsHint(node: ViewHierarchyTreeNode): String {
    if (!node.clickable) return ""
    val bounds = node.bounds ?: return ""
    val width = bounds.x2 - bounds.x1
    val height = bounds.y2 - bounds.y1
    // Only annotate when the clickable area is large enough to matter:
    // full-width rows (>500px wide) or tall elements (>150px)
    if (width < 500 && height < 150) return ""
    return " [bounds: ${bounds.x1},${bounds.y1} to ${bounds.x2},${bounds.y2}]"
  }

  /**
   * Detects if a node's text represents a date or time value and returns a semantic annotation.
   *
   * Many UI elements display dates/times as plain text without resource IDs (e.g., "Tue, Feb 10,
   * 2026" or "4:00 PM" in Google Calendar). Without annotation, the LLM can't distinguish these
   * tappable date/time fields from static labels, leading to mis-clicks.
   */
  private fun detectSemanticType(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val trimmed = text.trim()
    return when {
      DATE_PATTERN.matches(trimmed) -> " [date]"
      TIME_PATTERN.matches(trimmed) -> " [time]"
      else -> ""
    }
  }



  /**
   * Builds the user message combining context and screen state.
   *
   * `internal` so tests can assert the verification-vs-direction prompt branch directly
   * without wiring up a full `analyze(...)` invocation.
   */
  internal fun buildUserMessage(
    context: RecommendationContext,
    viewHierarchy: String,
  ): String = buildString {
    // When task decomposition is active, show the overall objective for broader context
    if (context.overallObjective != null) {
      appendLine("## Overall Objective")
      appendLine(context.overallObjective)
      appendLine()
      appendLine("## Current Subtask")
      appendLine(context.objective)
    } else {
      appendLine("## Objective")
      appendLine(context.objective)
    }
    appendLine()

    if (context.progressSummary != null) {
      appendLine("## Progress So Far")
      appendLine(context.progressSummary)
      appendLine()
    }

    if (context.hint != null) {
      appendLine("## Hint")
      appendLine(context.hint)
      appendLine()
    }

    if (context.nextSubtaskHint != null) {
      appendLine("## Next Step")
      appendLine(context.nextSubtaskHint)
      appendLine("If the current subtask is already satisfied on screen, take action toward this next step instead of reporting status.")
      appendLine()
    }

    if (context.attemptNumber > 1) {
      appendLine("## Attempt")
      appendLine("This is attempt #${context.attemptNumber}")
      appendLine()
    }

    appendLine("## Current Screen State (View Hierarchy)")
    appendLine(viewHierarchy)
    appendLine()
    appendLine("Analyze the screen and call ONE of the available tools with your chosen action.")
    if (context.isVerification) {
      appendLine("IMPORTANT: This is a verification step — an assertion about state, not an instruction to change it. If the screen matches the objective, call objectiveStatus with objectiveAppearsAchieved=true. Do not tap or input; you may scroll only if needed to locate the target element on screen, never to interact with it.")
    } else {
      appendLine("IMPORTANT: Always prefer taking a UI action (tap, scroll, input) over calling objectiveStatus. Only call objectiveStatus after you have performed the actual actions needed.")
    }
    appendLine("Remember to include reasoning, screenSummary, and confidence in your tool call.")
  }

  /**
   * Parses the LLM response into a [ScreenAnalysis].
   *
   * Now handles ACTUAL tool calls (e.g., tapOnElementByNodeId, swipe) instead of a meta-tool.
   * The tool name tells us which action, and we extract both action args and analysis metadata.
   */
  private fun parseAnalysis(samplingResult: SamplingResult): ScreenAnalysis {
    return when (samplingResult) {
      is SamplingResult.ToolCall -> {
        // Primary case: LLM called one of the actual UI tools!
        // The tool name IS the recommended action (type-safe!)
        parseToolCallWithAnalysis(samplingResult.toolName, samplingResult.arguments)
      }
      is SamplingResult.Text -> {
        // Tool calls are required via ToolChoice.Required — text responses indicate a bug
        // in the SamplingSource implementation, not something we should silently paper over.
        Console.error("[InnerLoopScreenAnalyzer] Expected tool call but got text response")
        ScreenAnalysis(
          recommendedTool = "wait",
          recommendedArgs = buildJsonObject { put("seconds", 1) },
          reasoning = "LLM returned text instead of a tool call. This is unexpected with ToolChoice.Required.",
          screenSummary = "Error: text response instead of tool call",
          confidence = Confidence.LOW,
          potentialBlockers = listOf("SamplingSource returned text instead of tool call"),
        )
      }
      is SamplingResult.Error -> {
        // Return a failure analysis
        ScreenAnalysis(
          recommendedTool = "wait",
          recommendedArgs = buildJsonObject { put("seconds", 1) },
          reasoning = "LLM sampling failed: ${samplingResult.message}",
          screenSummary = "Error during analysis",
          potentialBlockers = listOf("LLM error: ${samplingResult.message}"),
          confidence = Confidence.LOW,
          objectiveAppearsImpossible = false,
        )
      }
    }
  }


}
