package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.AndroidCompactElementList
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.IosCompactElementList
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ObjectiveLogHelper
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.mcp.RecordedToolCall
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.toolcalls.isVerification
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.KClass
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Primary MCP tools for UI automation:
 * - blaze: Take an action toward an objective (or verify with hint="VERIFY")
 * - ask: Ask a question and get an answer
 */
@Suppress("unused")
class StepToolSet(
  private val screenAnalyzer: ScreenAnalyzer? = null,
  private val executor: UiActionExecutor,
  private val screenStateProvider: (fast: Boolean, includeAnnotated: Boolean, includeAllElements: Boolean) -> ScreenState?,
  private val sessionContext: TrailblazeMcpSessionContext? = null,
  /** Provider for available UI tools. The analyzer uses these for type-safe recommendations. */
  private val availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
  /** Emits objective logs to LogsRepo for log-based trail generation. */
  private val logEmitter: LogEmitter? = null,
  /** Provides the active Trailblaze session ID for log emission. */
  private val sessionIdProvider: (() -> SessionId?)? = null,
  /** Returns driver connection status when the driver is still initializing. */
  private val driverStatusProvider: (() -> String?)? = null,
  /**
   * Optional override for the tool classes used by the inner agent.
   *
   * When provided and returns non-null, these classes replace the default
   * [ToolSetCategoryMapping.getToolClasses] selection. This enables driver-specific
   * tool sets (e.g., Compose tools) to be injected without a direct module dependency.
   */
  private val toolClassesOverrideProvider: (() -> Set<KClass<out TrailblazeTool>>?)? = null,
  /** Executes a raw TrailblazeTool directly on the device, bypassing the AI agent. */
  private val rawToolExecutor: (suspend (TrailblazeTool, TraceId?) -> String)? = null,
  /** Captures the current screen summary after direct tool execution. */
  private val screenSummaryProvider: (suspend (Set<SnapshotDetail>) -> String?)? = null,
  /** Saves a screenshot to disk and returns the absolute file path. */
  private val screenshotSaver: (suspend (ByteArray) -> String?)? = null,
  /** Provides custom tool classes (target-specific) for YAML parsing in direct tool execution. */
  private val customToolClassesProvider: (() -> Set<KClass<out TrailblazeTool>>)? = null,
  /** Provides the current session's dynamic-tool repo, if one is loaded. */
  private val dynamicToolRepoProvider: (suspend () -> TrailblazeToolRepo?)? = null,
) : ToolSet {

  private fun parseSnapshotDetails(value: String?): Set<SnapshotDetail> {
    if (value.isNullOrBlank()) return emptySet()
    return value.split(",").mapNotNull { token ->
      try { SnapshotDetail.valueOf(token.trim().uppercase()) } catch (_: Exception) { null }
    }.toSet()
  }

  companion object {
    /** Max time to wait for the device driver to finish initializing. */
    internal const val DRIVER_INIT_TIMEOUT_MS = 30_000L
    /** Longer timeout for Playwright browser installation (~150MB download). */
    internal const val PLAYWRIGHT_INSTALL_TIMEOUT_MS = 300_000L
    /** Shorter timeout for transient capture failures (driver ready but session warming up). */
    internal const val SCREEN_CAPTURE_RETRY_MS = 5_000L
    private const val POLL_INTERVAL_MS = 1_000L

    /**
     * Chooses the await-screen-state timeout for a given driver status string.
     *
     * Returns `null` when the driver reports a non-transient error (e.g. a real
     * disconnection): the caller should stop polling and surface the error.
     *
     * Branch order matters: `installing` is checked before `initializing` because
     * a compound status message could in principle contain both substrings, and
     * the two states have different timeouts. Do not reorder without updating
     * the ordering test in `StepToolSetDirectToolsTest`.
     *
     * Pure function on a string so it can be unit-tested without running the
     * coroutine loop.
     */
    internal fun resolveAwaitTimeoutMs(driverStatus: String?): Long? = when {
      // Playwright browser installing (~150MB download) — wait longer to cover the download.
      driverStatus != null && "installing" in driverStatus -> PLAYWRIGHT_INSTALL_TIMEOUT_MS
      // Maestro driver still initializing — wait up to 30s for the handshake.
      driverStatus != null && "initializing" in driverStatus -> DRIVER_INIT_TIMEOUT_MS
      // No status → transient capture failure while a ready driver warms up.
      driverStatus == null -> SCREEN_CAPTURE_RETRY_MS
      // Anything else is a real error — caller should return null immediately.
      else -> null
    }
  }

  /**
   * Builds a text summary from a [ScreenState], respecting [SnapshotDetail] options.
   * Used by the fast snapshot path to avoid a second device round-trip.
   */
  private fun describeScreenState(
    screenState: ScreenState,
    details: Set<SnapshotDetail> = emptySet(),
  ): String? {
    // If we have a TrailblazeNode tree, build or rebuild the compact element list.
    // This handles: detail enrichment (BOUNDS, OFFSCREEN) and cases where
    // viewHierarchyTextRepresentation is null (e.g., Android HOST mode).
    val tree = screenState.trailblazeNodeTree
    if (tree != null) {
      val platform = detectPlatformFromTree(tree)
      if (platform != null && (details.isNotEmpty() || screenState.viewHierarchyTextRepresentation == null)) {
        val elements = when (platform) {
          "android" -> AndroidCompactElementList.build(tree, details, screenState.deviceHeight).text
          "ios" -> if (tree.driverDetail is DriverNodeDetail.IosAxe ||
            tree.children.firstOrNull()?.driverDetail is DriverNodeDetail.IosAxe
          ) {
            xyz.block.trailblaze.api.IosAxeCompactElementList.build(
              tree, details, screenState.deviceHeight, screenState.deviceWidth,
            ).text
          } else {
            IosCompactElementList.build(tree, details, screenState.deviceHeight).text
          }
          else -> null
        }
        if (elements != null) {
          val pageContext = screenState.pageContextSummary
          return if (pageContext != null) "$pageContext\n\n$elements" else elements
        }
      }
    }

    // Web/Playwright: re-render the platform's text with the requested details.
    // Default impl returns the cached text unchanged, so this is a no-op on Maestro
    // platforms where the AndroidCompactElementList/IosCompactElementList path above
    // is the canonical detail handler.
    return screenState.viewHierarchyTextRepresentation(details)
  }

  /** Detects platform from the TrailblazeNode tree by checking root and first child. */
  private fun detectPlatformFromTree(tree: xyz.block.trailblaze.api.TrailblazeNode): String? {
    if (tree.driverDetail is DriverNodeDetail.AndroidAccessibility) return "android"
    if (tree.driverDetail is DriverNodeDetail.AndroidMaestro) return "android"
    if (tree.driverDetail is DriverNodeDetail.IosMaestro) return "ios"
    if (tree.driverDetail is DriverNodeDetail.IosAxe) return "ios"
    val firstChild = tree.children.firstOrNull() ?: return null
    if (firstChild.driverDetail is DriverNodeDetail.AndroidAccessibility) return "android"
    if (firstChild.driverDetail is DriverNodeDetail.AndroidMaestro) return "android"
    if (firstChild.driverDetail is DriverNodeDetail.IosMaestro) return "ios"
    if (firstChild.driverDetail is DriverNodeDetail.IosAxe) return "ios"
    return null
  }

  /**
   * Waits for [screenStateProvider] to return a non-null [ScreenState].
   *
   * Screen capture can fail transiently in two scenarios:
   * 1. Driver is still initializing (reported by [driverStatusProvider]) — polls up to 30s.
   * 2. Driver is ready but the Maestro session hasn't warmed up yet — polls up to 5s.
   *
   * Returns the screen state once available, or null if capture never succeeds.
   */
  private suspend fun awaitScreenState(
    fast: Boolean = false,
    includeAnnotated: Boolean = true,
    includeAllElements: Boolean = false,
  ): ScreenState? {
    // Fast path — already ready
    screenStateProvider(fast, includeAnnotated, includeAllElements)?.let { return it }

    val status = driverStatusProvider?.invoke()
    val timeoutMs = resolveAwaitTimeoutMs(status) ?: return null

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      delay(POLL_INTERVAL_MS)
      screenStateProvider(fast, includeAnnotated, includeAllElements)?.let { return it }
    }
    return null
  }

  @LLMDescription(
    """
    Take a step toward your objective, or verify an assertion.

    Each blaze() call should be a COMPLETE USER-FACING ACTION with all relevant
    parameters — not individual UI taps, but not multi-screen journeys either.
    The inner agent has specialized tools that handle multi-step flows (login,
    setup, onboarding, etc.) and needs full context to select the right one.

    GOOD scope — one action with all its details:
      blaze(objective="Login with test@example.com")
      blaze(objective="Search flights from Paris to London on October 4")
      blaze(objective="Enter shipping address: 123 Main St, Springfield, IL 62701")

    BAD scope — too granular, strips context the inner agent needs:
      blaze(objective="Launch the app")  ← inner agent can't tell this is part of a login
      blaze(objective="Tap the email field")
      blaze(objective="Type test@example.com")

    Trailblaze can handle larger objectives autonomously — it will break them down
    internally and use specialized tools. Larger objectives mean less back-and-forth but
    slower feedback. Smaller objectives give faster feedback but risk losing context.
    When in doubt, go bigger — include all details the inner agent might need.

    Returns what happened plus a screenSummary showing visible text and actionable
    elements (e.g. "[button] Login | [input] Email"). This summary is compact and
    does NOT include layout, position, or list structure — use ask() if you need to
    know where elements are on screen or how they relate to each other.
    If uncertain, returns options for you to decide.
    With hint="VERIFY", checks an assertion using read-only tools and returns passed (true/false).
    """
  )
  @Tool(McpToolProfile.TOOL_BLAZE)
  suspend fun blaze(
    @LLMDescription("A complete user-facing action with all relevant details (e.g., 'Login with test@example.com', 'Search flights Paris to London Oct 4'). Include credentials, search terms, and parameters — the inner agent needs this context to select specialized tools.")
    objective: String,
    @LLMDescription("Context from previous steps (optional)")
    context: String? = null,
    @LLMDescription("hint=\"VERIFY\" to check an assertion using read-only tools (returns passed: true/false). Omit for normal action.")
    hint: String? = null,
    @LLMDescription("YAML tool sequence to execute directly, bypassing AI agent. Same format as recording.tools in trail files.")
    tools: String? = null,
    @LLMDescription("Comma-separated snapshot detail levels: BOUNDS, OFFSCREEN, ALL_ELEMENTS. Enriches the screen summary in the response.")
    snapshotDetails: String? = null,
    @LLMDescription("Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens), and skip disk logging.")
    fast: Boolean = false,
    @LLMDescription("Save a screenshot to disk and return the file path. Works with fast mode.")
    screenshot: Boolean = false,
  ): String {
    val traceOrigin = if (tools != null) TraceId.Companion.TraceOrigin.MCP else TraceId.Companion.TraceOrigin.LLM
    val traceId = TraceId.generate(traceOrigin)
    val isVerify = BlazeHint.from(hint) == BlazeHint.VERIFY
    val isFast = fast
    val wantsScreenshot = screenshot

    // Parse snapshot detail options for screen summary enrichment
    val parsedSnapshotDetails = parseSnapshotDetails(snapshotDetails)

    // When screenshot is requested, capture with screenshots even in fast mode.
    val skipScreenshot = isFast && !wantsScreenshot
    // Only the LLM analysis path needs the set-of-mark annotated screenshot.
    // Snapshot short-circuits and direct-tool execution skip the LLM, and without
    // a configured analyzer the LLM path can't run either, so we can tell the
    // on-device agent not to render or transfer the annotated version.
    val needsAnnotation = tools == null && screenAnalyzer != null
    // ALL_ELEMENTS propagates all the way to the on-device accessibility capture so
    // the tree arrives unfiltered — the downstream compact-list bypass alone is not
    // enough once filterImportantForAccessibility runs on-device.
    val wantsAllElements = SnapshotDetail.ALL_ELEMENTS in parsedSnapshotDetails
    val screenState = awaitScreenState(
      fast = skipScreenshot,
      includeAnnotated = needsAnnotation,
      includeAllElements = wantsAllElements,
    )
      ?: return StepResult(
        executed = false,
        error = driverStatusProvider?.invoke()
          ?: "No device connected. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first.",
      ).toMarkdown()

    // Snapshot short-circuit: skip tool execution entirely and return the
    // view hierarchy text from the already-captured screen state. Avoids a second
    // device round-trip (screenshot + hierarchy) and all disk I/O from takeSnapshot.
    // Also used for non-fast snapshot when screenshot/bounds/offscreen are requested,
    // since the screen state was already captured with full detail above.
    if (tools != null && "takeSnapshot" in tools) {
      // Try to build screen summary from the already-captured screen state.
      // Falls back to screenSummaryProvider for platforms where the screen state
      // doesn't carry a pre-built text representation (e.g., Android HOST/Maestro).
      val screenSummary = describeScreenState(screenState, parsedSnapshotDetails)
        ?: try { screenSummaryProvider?.invoke(parsedSnapshotDetails) } catch (_: Exception) { null }

      // Save screenshot to disk if requested
      val screenshotPath = if (wantsScreenshot) {
        val bytes = screenState.screenshotBytes
        if (bytes != null && screenshotSaver != null) {
          try { screenshotSaver.invoke(bytes) } catch (_: Exception) { null }
        } else null
      } else null

      Console.log("")
      Console.log("┌──────────────────────────────────────────────────────────────────────────────")
      Console.log("│ [snapshot] Captured${if (screenshotPath != null) " (screenshot: $screenshotPath)" else ""}")
      Console.log("└──────────────────────────────────────────────────────────────────────────────")

      val result = if (screenshotPath != null) {
        "Snapshot captured\n\n**Screenshot:** $screenshotPath"
      } else {
        "Snapshot captured"
      }
      return StepResult(
        executed = true,
        done = true,
        result = result,
        screenSummary = screenSummary,
      ).toMarkdown()
    }

    // Direct tool execution mode — bypass the AI agent, execute provided tools as-is
    if (tools != null) {
      return executeDirectTools(objective, tools, traceId, isFast, parsedSnapshotDetails)
    }

    // AI agent mode requires an LLM — fail clearly if not configured
    if (screenAnalyzer == null) {
      return StepResult(
        executed = false,
        error = "No AI provider configured. Set one up with 'trailblaze config' or drive the device directly with 'trailblaze tool' (no AI needed).",
      ).toMarkdown()
    }

    val promptStep = if (isVerify) VerificationStep(verify = objective) else DirectionStep(step = objective)
    val stepStartTime = Clock.System.now()

    val recommendationContext = RecommendationContext(
      objective = objective,
      progressSummary = context,
      hint = if (isVerify) "Verify this assertion using read-only tools only. Do not tap, swipe, or type." else null,
      attemptNumber = 1,
      fast = isFast,
    )

    val tools = selectInnerAgentTools()

    // Emit objective start BEFORE analyze so that tool calls made by the inner agent
    // during analysis are correctly associated with this objective in the report.
    emitObjectiveStart(promptStep)

    val analysis = try {
      // Pass selected tools so the LLM can call them directly (type-safe)
      screenAnalyzer.analyze(
        context = recommendationContext,
        screenState = screenState,
        traceId = traceId,
        availableTools = tools,
      )
    } catch (e: Exception) {
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = e.message)
      return StepResult(
        executed = false,
        error = "Failed to analyze screen: ${e.message}",
      ).toMarkdown()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    if (isVerify) {
      Console.log("│ [verify] $objective")
      Console.log("│ Result: ${if (analysis.objectiveAppearsAchieved) "PASSED" else if (analysis.objectiveAppearsImpossible) "FAILED" else "UNCERTAIN"}")
    } else {
      Console.log("│ [blaze${if (isFast) " --no-screenshots" else ""}] Objective: $objective")
    }
    Console.log("│ Screen: ${analysis.screenSummary}")
    Console.log("│ Confidence: ${analysis.confidence}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    // Already done / assertion passed?
    if (analysis.objectiveAppearsAchieved) {
      emitObjectiveComplete(promptStep, stepStartTime, success = true)
      return StepResult(
        executed = false,
        done = true,
        passed = if (isVerify) true else null,
        result = if (isVerify) "Assertion passed" else "Objective already achieved",
        screenSummary = analysis.screenSummary,
      ).toMarkdown()
    }

    // Impossible / assertion failed?
    if (analysis.objectiveAppearsImpossible) {
      val failureReason = if (isVerify) "Assertion failed: ${analysis.reasoning}" else "Cannot achieve objective: ${analysis.reasoning}"
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = failureReason)
      return StepResult(
        executed = false,
        passed = if (isVerify) false else null,
        error = failureReason,
        screenSummary = analysis.screenSummary,
        suggestedHint = if (!isVerify) analysis.suggestedHint else null,
      ).toMarkdown()
    }

    // Verify mode is read-only. If the LLM picked a non-verification tool, executing
    // it could mutate the device, and its success is unrelated to whether the
    // assertion holds — fail the assertion as "not confirmed" without executing.
    // If the LLM picked a verification tool (annotated `isVerification = true`), let
    // the standard execute branch run it: the tool itself validates the condition,
    // so its execution success/failure is the correct verify verdict.
    if (isVerify && !isVerificationTool(analysis.recommendedTool)) {
      Console.log(
        "│ ✗ Verify rejected non-verification tool: '${analysis.recommendedTool}' " +
          "(no @TrailblazeToolClass(isVerification = true) annotation)",
      )
      val failureReason = "Assertion not confirmed: ${analysis.reasoning}"
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = failureReason)
      return StepResult(
        executed = false,
        passed = false,
        error = failureReason,
        screenSummary = analysis.screenSummary,
      ).toMarkdown()
    }

    // HIGH or MEDIUM confidence → execute. In verify mode this branch only runs
    // for verification tools (filtered above), so successful execution means
    // the assertion held.
    if (analysis.confidence == Confidence.HIGH || analysis.confidence == Confidence.MEDIUM) {
      val executionResult = try {
        executor.execute(analysis.recommendedTool, analysis.recommendedArgs, traceId)
      } catch (e: Exception) {
        emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = e.message)
        return StepResult(
          executed = false,
          passed = if (isVerify) false else null,
          error = "Action failed: ${e.message}",
          screenSummary = analysis.screenSummary,
        ).toMarkdown()
      }

      val (result, success) = when (executionResult) {
        is ExecutionResult.Success -> {
          Console.log("│ ✓ Executed: ${analysis.recommendedTool}")
          StepResult(
            executed = true,
            passed = if (isVerify) true else null,
            result = analysis.reasoning.take(200),
            screenSummary = executionResult.screenSummaryAfter,
          ) to true
        }
        is ExecutionResult.Failure -> {
          Console.log("│ ✗ Failed: ${executionResult.error}")
          StepResult(
            executed = true,
            passed = if (isVerify) false else null,
            error = executionResult.error,
            screenSummary = analysis.screenSummary,
          ) to false
        }
      }

      // Emit objective complete log for log-based trail generation
      emitObjectiveComplete(
        step = promptStep,
        stepStartTime = stepStartTime,
        success = success,
        failureReason = if (!success) result.error else null,
      )

      // Record the step (success or failure)
      sessionContext?.recordStep(
        RecordedStep(
          type = if (isVerify) RecordedStepType.VERIFY else RecordedStepType.STEP,
          input = objective,
          toolCalls = listOf(
            RecordedToolCall(
              toolName = analysis.recommendedTool,
              args = analysis.recommendedArgs.mapValues { it.value.toString() },
            ),
          ),
          result = if (success) result.result ?: "" else result.error ?: "Unknown error",
          success = success,
        ),
      )
      return result.toMarkdown()
    }

    // LOW confidence
    Console.log("│ ? Low confidence - needs guidance")
    emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = "Low confidence: ${analysis.reasoning}")

    // Verify mode: LOW confidence means the LLM couldn't tell whether the assertion
    // holds. "Maybe" is not a passing verify verdict, so report FAILED rather than
    // returning needsInput=true (which would render as ⚠️ Needs input and skip the
    // verify recording type). Failing closed matches the read-only contract.
    if (isVerify) {
      val failureReason = "Assertion not confirmed: low confidence — ${analysis.reasoning}"
      return StepResult(
        executed = false,
        passed = false,
        error = failureReason,
        screenSummary = analysis.screenSummary,
      ).toMarkdown()
    }

    // Blaze mode: surface as a needs-input result so the outer agent can retry with
    // a different hint or pick a different tool.
    return StepResult(
      executed = false,
      needsInput = true,
      result = "Uncertain: ${analysis.reasoning}",
      screenSummary = analysis.screenSummary,
      suggestion = analysis.recommendedTool,
      suggestedHint = analysis.suggestedHint,
    ).toMarkdown()
  }

  /**
   * Executes a YAML tool sequence directly, bypassing the AI agent.
   * Records the step with the NL objective paired with the tool execution,
   * so it produces a proper trail step when saved.
   */
  private suspend fun executeDirectTools(
    objective: String,
    toolsYaml: String,
    traceId: TraceId,
    fast: Boolean = false,
    snapshotDetails: Set<SnapshotDetail> = emptySet(),
  ): String {
    val toolExecutor = rawToolExecutor
      ?: return StepResult(executed = false, error = "Direct tool execution not available").toMarkdown()

    val promptStep = DirectionStep(step = objective)
    val stepStartTime = Clock.System.now()
    emitObjectiveStart(promptStep)

    // Parse the user's raw tool list using the type-safe YAML parser.
    // Supports two formats:
    //   Wrapped (trail format):  - tools:\n    - tapOnPoint:\n        x: 100
    //   Unwrapped (raw tools):   - tapOnPoint:\n    x: 100
    val customClasses = customToolClassesProvider?.invoke() ?: emptySet()
    val yaml = createTrailblazeYaml(customClasses)
    val toolWrappers = try {
      // Try trail format first (handles `- tools:` wrapper)
      val trailItems = yaml.decodeTrail(toolsYaml)
      trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>().flatMap { it.tools }
    } catch (_: Exception) {
      try {
        // Fall back to raw tool list format
        yaml.decodeTools(toolsYaml)
      } catch (e: Exception) {
        emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = "Failed to parse tools YAML: ${e.message}")
        return StepResult(executed = false, error = "Failed to parse tools YAML: ${e.message}").toMarkdown()
      }
    }

    if (toolWrappers.isEmpty()) {
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = "No tools found in YAML")
      return StepResult(executed = false, error = "No tools found in YAML").toMarkdown()
    }

    val dynamicRepo = dynamicToolRepoProvider?.invoke()
    val resolvedToolWrappers = toolWrappers.map { wrapper ->
      val otherTool = wrapper.trailblazeTool as? OtherTrailblazeTool ?: return@map wrapper
      val resolved = dynamicRepo?.runCatching {
        toolCallToTrailblazeTool(toolName = wrapper.name, toolContent = otherTool.raw.toString())
      }?.getOrNull() ?: return@map wrapper
      wrapper.copy(trailblazeTool = resolved)
    }

    // Reject unknown tool names — OtherTrailblazeTool means the name still wasn't in the registry
    val unknownTools = resolvedToolWrappers.filter { it.trailblazeTool is OtherTrailblazeTool }.map { it.name }
    if (unknownTools.isNotEmpty()) {
      val msg = "Unknown tool${if (unknownTools.size > 1) "s" else ""}: ${unknownTools.joinToString(", ")}. Use toolbox() to see available tools."
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = msg)
      return StepResult(executed = false, error = msg).toMarkdown()
    }

    // Reject tools that are registered but not applicable to the current driver/target —
    // e.g. invoking `openUrl` (Maestro-mapped) on a Playwright web device. Without this gate
    // the call reaches MapsToMaestroCommands.execute() and surfaces a cryptic
    // "MapsToMaestroCommands requires MaestroTrailblazeAgent" cast error.
    //
    // The empty-provider branch is a transient state — device booting, brief disconnect, or
    // a slow tool-catalog load. Skipping validation in that case keeps the user moving (the
    // request may still succeed), but we log so the daemon log shows when the catalog gate
    // was bypassed, which is useful when debugging "why did I get the cryptic cast error
    // back" reports.
    val availableNames = availableToolsProvider().map { it.name }.toSet()
    if (availableNames.isEmpty()) {
      Console.log("Tool catalog empty for this device/target; skipping per-tool availability check")
    } else {
      val notValidForDevice = resolvedToolWrappers.map { it.name }.filter { it !in availableNames }
      if (notValidForDevice.isNotEmpty()) {
        val msg = "Tool${if (notValidForDevice.size > 1) "s" else ""} not valid for the current device/target: " +
          "${notValidForDevice.joinToString(", ")}. Use toolbox() to see available tools."
        emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = msg)
        return StepResult(executed = false, error = msg).toMarkdown()
      }
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [blaze${if (fast) " --no-screenshots" else ""}] Objective: $objective (${resolvedToolWrappers.size} tools)")

    // Execute each tool sequentially
    val recordedToolCalls = mutableListOf<RecordedToolCall>()
    for (wrapper in resolvedToolWrappers) {
      val toolStartTime = Clock.System.now()
      try {
        toolExecutor(wrapper.trailblazeTool, traceId)
        emitDirectToolLog(
          tool = wrapper.trailblazeTool,
          toolName = wrapper.name,
          traceId = traceId,
          startTime = toolStartTime,
          successful = true,
        )
        recordedToolCalls.add(RecordedToolCall(toolName = wrapper.name, args = emptyMap()))
        Console.log("│ ✓ Executed: ${wrapper.name}")
      } catch (e: Exception) {
        emitDirectToolLog(
          tool = wrapper.trailblazeTool,
          toolName = wrapper.name,
          traceId = traceId,
          startTime = toolStartTime,
          successful = false,
          exceptionMessage = e.message,
        )
        Console.log("│ ✗ Failed: ${wrapper.name} — ${e.message}")
        Console.log("└──────────────────────────────────────────────────────────────────────────────")
        emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = "Tool ${wrapper.name} failed: ${e.message}")
        sessionContext?.recordStep(RecordedStep(
          type = RecordedStepType.STEP,
          input = objective,
          toolCalls = recordedToolCalls,
          result = "Failed at ${wrapper.name}: ${e.message}",
          success = false,
        ))
        return StepResult(executed = true, error = "Tool ${wrapper.name} failed: ${e.message}").toMarkdown()
      }
    }

    // In fast mode, skip post-action screen capture (the expensive part).
    // The next command will capture fresh state if needed.
    val screenSummary = if (fast) {
      null
    } else {
      try {
        screenSummaryProvider?.invoke(snapshotDetails)
      } catch (e: Exception) {
        Console.log("│ Screen summary capture failed: ${e.message}")
        null
      }
    }

    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    emitObjectiveComplete(promptStep, stepStartTime, success = true)
    sessionContext?.recordStep(RecordedStep(
      type = RecordedStepType.STEP,
      input = objective,
      toolCalls = recordedToolCalls,
      result = "Executed ${resolvedToolWrappers.size} tools",
      success = true,
    ))

    return StepResult(
      executed = true,
      done = true,
      result = "Executed ${resolvedToolWrappers.size} tools for: $objective",
      screenSummary = screenSummary,
    ).toMarkdown()
  }

  @LLMDescription(
    """
    Observe the screen and optionally answer a question.

    ask(question="What's the current balance?")
    ask(question="What buttons are visible?")
    ask(question="What's on screen?", includeScreenshot=true)
    ask(question="Show me the layout", viewHierarchy="FULL")

    Returns a screen summary (visible text and elements) by default.
    With an LLM configured, also answers questions about the screen.
    Without an LLM, returns raw screen state — useful for external agents
    that provide their own reasoning.

    Use includeScreenshot=true to save a screenshot to disk and get the file path.
    Use viewHierarchy to get the raw view hierarchy at different detail levels:
      MINIMAL  — interactable elements with coordinates only
      STANDARD — interactable elements with descriptions and hierarchy
      FULL     — complete view tree including non-interactable elements

    Unlike blaze(hint="VERIFY"), this returns information — not pass/fail.
    """
  )
  @Tool(McpToolProfile.TOOL_ASK)
  suspend fun ask(
    @LLMDescription("Your question (e.g., 'What's the current balance?')")
    question: String,
    @LLMDescription("Save a screenshot to disk and return the file path (default: false)")
    includeScreenshot: Boolean = false,
    @LLMDescription("Include raw view hierarchy: MINIMAL, STANDARD, or FULL (default: not included)")
    viewHierarchy: ViewHierarchyVerbosity? = null,
  ): String {
    // Annotation is only needed when the LLM analyzer is going to consume the
    // screenshot; the no-LLM fallback path just returns raw screen state.
    val screenState = awaitScreenState(includeAnnotated = screenAnalyzer != null)
    if (screenState == null) {
      val driverStatus = driverStatusProvider?.invoke()
        ?: "No device connected. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first."
      Console.error("[ask] Screen state is null — driverStatus=$driverStatus")
      return AskResult(
        answer = null,
        error = driverStatus,
      ).toMarkdown()
    }

    // Screenshot: save to disk and return path (never inline base64)
    val screenshotPath = if (includeScreenshot) {
      val bytes = screenState.screenshotBytes
      if (bytes != null && screenshotSaver != null) {
        try {
          screenshotSaver.invoke(bytes)
        } catch (e: Exception) {
          Console.error("[ask] Screenshot save failed: ${e.message}")
          null
        }
      } else null
    } else null

    // View hierarchy at requested verbosity
    val viewHierarchyText = if (viewHierarchy != null) {
      ViewHierarchyFormatter.format(screenState, viewHierarchy)
    } else null

    // With LLM: answer the question using the AI agent
    if (screenAnalyzer != null) {
      val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
      val startTime = Clock.System.now()

      val recommendationContext = RecommendationContext(
        objective = "Answer this question: $question",
        progressSummary = null,
        hint = "Put a direct, concise answer to the question in the `answer` field. If the question is about what's on the screen, the answer should just be the screen summary. Don't take any action.",
        attemptNumber = 1,
      )

      val analysis = try {
        screenAnalyzer.analyze(
          context = recommendationContext,
          screenState = screenState,
          traceId = traceId,
          availableTools = availableToolsProvider(),
        )
      } catch (e: Exception) {
        Console.error("[ask] screenAnalyzer.analyze() FAILED: ${e::class.simpleName}: ${e.message}")
        emitAskLog(question, null, null, e.message, traceId, startTime)
        return AskResult(
          answer = null,
          error = "Failed to analyze screen: ${e.message}",
          screenshotPath = screenshotPath,
          viewHierarchy = viewHierarchyText,
        ).toMarkdown()
      }

      Console.log("")
      Console.log("┌──────────────────────────────────────────────────────────────────────────────")
      Console.log("│ [ask] $question")
      Console.log("│ Answer: ${analysis.screenSummary}")
      Console.log("└──────────────────────────────────────────────────────────────────────────────")

      val result = AskResult(
        answer = analysis.answer ?: analysis.reasoning,
        screenSummary = analysis.screenSummary,
        screenshotPath = screenshotPath,
        viewHierarchy = viewHierarchyText,
      )

      emitAskLog(question, result.answer, result.screenSummary, null, traceId, startTime)
      return result.toMarkdown()
    }

    // No-LLM fallback: return raw screen state for external agents
    // ask() has no snapshotDetails parameter — always use default (no enrichment).
    val screenSummary = try {
      screenSummaryProvider?.invoke(emptySet())
    } catch (_: Exception) { null }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [ask] $question (no LLM — returning raw screen state)")
    Console.log("│ Screen: ${screenSummary?.take(80) ?: "(no summary)"}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return AskResult(
      answer = "No AI provider configured — cannot interpret the screen. Run 'trailblaze config' to set up an AI provider, or use 'trailblaze snapshot' to see the raw UI tree. Raw screen state below.",
      screenSummary = screenSummary,
      screenshotPath = screenshotPath,
      viewHierarchy = viewHierarchyText,
    ).toMarkdown()
  }

  /**
   * True if [toolName] is a verification tool — read-only, self-validating, and safe to
   * execute under `blaze(hint=VERIFY)`. Determined by the
   * [TrailblazeToolClass.isVerification] annotation on the registered tool class, so the
   * source of truth lives next to the tool definition (e.g. `assertVisible`,
   * `web_verify_text_visible`) and individual targets can opt new tools in by setting the
   * flag — no central allowlist to keep in sync.
   *
   * Scans every class-backed tool reachable from the active session:
   *  1. The driver-specific override (if set) — covers Compose/Revyl/Playwright tools that
   *     are wired by their host runner.
   *  2. The session's dynamic tool repo (if available) — covers target-specific tools
   *     registered at runtime that aren't part of
   *     [TrailblazeToolSet.DefaultLlmTrailblazeTools].
   *  3. [ToolSetCategory.ALL] — the framework-level superset of every default class subset.
   *
   * YAML-defined verification tools aren't surfaced here yet — none exist in the catalog
   * today (the `verification.yaml` toolset only references class-backed tools by name) —
   * and adding them later is a strict superset change once a YAML tool wants to opt in
   * via `is_verification: true`.
   *
   * All reflection is wrapped defensively: if annotation reads fail at runtime (extreme
   * classpath corner cases), the call returns `false` rather than crashing the verify
   * call. Returns `false` for unknown / blank names — verify mode treats those as unsafe
   * and fails the assertion rather than executing.
   */
  private suspend fun isVerificationTool(toolName: String): Boolean {
    if (toolName.isBlank()) return false
    val classCandidates: Set<KClass<out TrailblazeTool>> = buildSet {
      toolClassesOverrideProvider?.invoke()?.let { addAll(it) }
      try {
        dynamicToolRepoProvider?.invoke()?.getRegisteredTrailblazeTools()?.let { addAll(it) }
      } catch (e: Exception) {
        Console.error("Failed to read dynamic tool repo for verify gate: ${e.message}")
      }
      addAll(ToolSetCategoryMapping.getToolClasses(ToolSetCategory.ALL))
    }
    return try {
      val match = classCandidates.firstOrNull { kClass ->
        kClass.findAnnotation<TrailblazeToolClass>()?.name == toolName
      } ?: return false
      match.isVerification()
    } catch (e: Exception) {
      Console.error("Failed to resolve verification annotation for '$toolName': ${e.message}")
      false
    }
  }

  /**
   * Selects tools for the inner agent:
   * 1. If [toolClassesOverrideProvider] returns a non-null class set, use those classes
   *    (driver-specific override, e.g. Compose tools).
   * 2. Otherwise, prefer the device-specific [availableToolsProvider] which already
   *    accounts for driver + YAML catalog.
   * 3. Fallback (no device connected yet): full ALL-category class + YAML descriptor list.
   */
  private fun selectInnerAgentTools(): List<TrailblazeToolDescriptor> {
    val overrideClasses = toolClassesOverrideProvider?.invoke()
    if (overrideClasses != null) {
      return overrideClasses.mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
    }
    val tools = availableToolsProvider()
    if (tools.isNotEmpty()) {
      return tools
    }
    // Fallback when no device-specific tools are available (e.g. no device connected yet).
    // Must include YAML-defined tools (e.g. pressBack) alongside class-backed ones so the
    // LLM sees the full toolset even in the no-device state.
    val classDescriptors = ToolSetCategoryMapping.getToolClasses(ToolSetCategory.ALL)
      .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(
      ToolSetCategoryMapping.getYamlToolNames(ToolSetCategory.ALL),
    ).map { it.toTrailblazeToolDescriptor() }
    return classDescriptors + yamlDescriptors
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Objective log emission for log-based trail generation
  // ─────────────────────────────────────────────────────────────────────────────

  private fun emitObjectiveStart(step: xyz.block.trailblaze.yaml.PromptStep) {
    val emitter = logEmitter ?: return
    val sessionId = sessionIdProvider?.invoke() ?: return
    emitter.emit(ObjectiveLogHelper.createStartLog(step, sessionId))
  }

  private fun emitObjectiveComplete(
    step: xyz.block.trailblaze.yaml.PromptStep,
    stepStartTime: kotlinx.datetime.Instant,
    success: Boolean,
    failureReason: String? = null,
  ) {
    val emitter = logEmitter ?: return
    val sessionId = sessionIdProvider?.invoke() ?: return
    emitter.emit(
      ObjectiveLogHelper.createCompleteLog(
        step = step,
        taskId = TaskId.generate(),
        stepStartTime = stepStartTime,
        sessionId = sessionId,
        success = success,
        failureReason = failureReason,
      ),
    )
  }

  private fun emitAskLog(
    question: String,
    answer: String?,
    screenSummary: String?,
    errorMessage: String?,
    traceId: TraceId,
    startTime: kotlinx.datetime.Instant,
  ) {
    val emitter = logEmitter ?: return
    val sessionId = sessionIdProvider?.invoke() ?: return
    val now = Clock.System.now()
    emitter.emit(
      TrailblazeLog.McpAskLog(
        question = question,
        answer = answer,
        screenSummary = screenSummary,
        errorMessage = errorMessage,
        traceId = traceId,
        durationMs = (now - startTime).inWholeMilliseconds,
        session = sessionId,
        timestamp = now,
      ),
    )
  }

  private fun emitDirectToolLog(
    tool: TrailblazeTool,
    toolName: String,
    traceId: TraceId,
    startTime: kotlinx.datetime.Instant,
    successful: Boolean,
    exceptionMessage: String? = null,
  ) {
    val emitter = logEmitter ?: return
    val sessionId = sessionIdProvider?.invoke() ?: return
    val now = Clock.System.now()
    emitter.emit(
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = tool.toLogPayload(),
        toolName = toolName,
        successful = successful,
        traceId = traceId,
        exceptionMessage = exceptionMessage,
        durationMs = (now - startTime).inWholeMilliseconds,
        session = sessionId,
        timestamp = startTime,
        isRecordable = tool.getIsRecordableFromAnnotation(),
        isTopLevelToolCall = true,
      ),
    )
  }
}

@Serializable
data class StepResult(
  val executed: Boolean,
  val done: Boolean = false,
  val needsInput: Boolean = false,
  val result: String? = null,
  val error: String? = null,
  val screenSummary: String? = null,
  val suggestion: String? = null,
  /**
   * If the inner agent couldn't find an appropriate tool, it suggests a different hint.
   * The outer agent can retry with `hint=suggestedHint`.
   */
  val suggestedHint: String? = null,
  /**
   * For blaze(hint="VERIFY"): true = assertion passed, false = assertion failed, null = not a verify call.
   */
  val passed: Boolean? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)

  /** Human-readable markdown format for MCP tool responses. */
  fun toMarkdown(): String = buildString {
    // Status + main message on the first line (always visible even when truncated)
    when {
      passed == true -> append("**✅ PASSED**")
      passed == false -> append("**❌ FAILED**")
      error != null -> append("**❌ Error**")
      done -> append("**✅ Done**")
      needsInput -> append("**⚠️ Needs input**")
      executed -> append("**✓ Executed**")
      else -> append("**→ Analyzed**")
    }

    // Result or error detail
    val message = error ?: result
    if (message != null) {
      append(" — $message")
    }

    // Suggestion for low-confidence / error recovery
    if (suggestion != null || suggestedHint != null) {
      appendLine()
      if (suggestion != null) append("\n**Suggestion:** $suggestion")
      if (suggestedHint != null) append("\n**Hint:** retry with hint=`$suggestedHint`")
    }

    // Screen summary last (longest, least critical for human observer)
    if (screenSummary != null) {
      append("\n\n**Screen:** $screenSummary")
    }
  }
}

@Serializable
data class AskResult(
  val answer: String?,
  val error: String? = null,
  val screenSummary: String? = null,
  val screenshotPath: String? = null,
  val viewHierarchy: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)

  /** Human-readable markdown format for MCP tool responses. */
  fun toMarkdown(): String = buildString {
    if (error != null) {
      append("**Error** — $error")
    } else if (answer != null) {
      append("**Answer:** $answer")
    }

    if (screenSummary != null) {
      append("\n\n**Screen:** $screenSummary")
    }

    if (screenshotPath != null) {
      append("\n\n**Screenshot:** $screenshotPath")
    }

    if (viewHierarchy != null) {
      append("\n\n**View Hierarchy:**\n$viewHierarchy")
    }
  }
}

/** Recognized values for the [StepToolSet.blaze] `hint` parameter. */
enum class BlazeHint {
  /** Read-only assertion check — returns [StepResult.passed]. */
  VERIFY;

  companion object {
    /** Parses a hint string from the LLM, case-insensitively. Returns null for unrecognized values. */
    fun from(value: String?): BlazeHint? =
      value?.trim()?.uppercase()?.let { upper -> entries.firstOrNull { it.name == upper } }
  }
}
