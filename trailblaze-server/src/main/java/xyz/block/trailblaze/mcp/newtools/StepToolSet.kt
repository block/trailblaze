package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
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
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * Primary MCP tools for UI automation:
 * - blaze: Take an action toward a goal (or verify with hint="VERIFY")
 * - ask: Ask a question and get an answer
 */
@Suppress("unused")
class StepToolSet(
  private val screenAnalyzer: ScreenAnalyzer,
  private val executor: UiActionExecutor,
  private val screenStateProvider: () -> ScreenState?,
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
) : ToolSet {

  @LLMDescription(
    """
    Take a step toward your goal, or verify an assertion.

    Each blaze() call should be a COMPLETE USER-FACING ACTION with all relevant
    parameters — not individual UI taps, but not multi-screen journeys either.
    The inner agent has specialized tools that handle multi-step flows (login,
    setup, onboarding, etc.) and needs full context to select the right one.

    GOOD scope — one action with all its details:
      blaze(goal="Login with test@example.com")
      blaze(goal="Search flights from Paris to London on October 4")
      blaze(goal="Enter shipping address: 123 Main St, Springfield, IL 62701")

    BAD scope — too granular, strips context the inner agent needs:
      blaze(goal="Launch the app")  ← inner agent can't tell this is part of a login
      blaze(goal="Tap the email field")
      blaze(goal="Type test@example.com")

    Trailblaze can handle larger objectives autonomously — it will break them down
    internally and use specialized tools. Larger goals mean less back-and-forth but
    slower feedback. Smaller goals give faster feedback but risk losing context.
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
    goal: String,
    @LLMDescription("Context from previous steps (optional)")
    context: String? = null,
    @LLMDescription("hint=\"VERIFY\" to check an assertion using read-only tools (returns passed: true/false). Omit for normal action.")
    hint: String? = null,
  ): String {
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    val isVerify = BlazeHint.from(hint) == BlazeHint.VERIFY

    val screenState = screenStateProvider()
      ?: return StepResult(
        executed = false,
        error = driverStatusProvider?.invoke()
          ?: "No device connected. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first.",
      ).toMarkdown()

    val promptStep = if (isVerify) VerificationStep(verify = goal) else DirectionStep(step = goal)
    val stepStartTime = Clock.System.now()

    val recommendationContext = RecommendationContext(
      objective = goal,
      progressSummary = context,
      hint = if (isVerify) "Verify this assertion using read-only tools only. Do not tap, swipe, or type." else null,
      attemptNumber = 1,
    )

    val tools = selectToolsForHint(hint)

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
      Console.log("│ [verify] $goal")
      Console.log("│ Result: ${if (analysis.objectiveAppearsAchieved) "PASSED" else if (analysis.objectiveAppearsImpossible) "FAILED" else "UNCERTAIN"}")
    } else {
      Console.log("│ [blaze] Goal: $goal")
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
        result = if (isVerify) "Assertion passed" else "Goal already achieved",
        screenSummary = analysis.screenSummary,
      ).toMarkdown()
    }

    // Impossible / assertion failed?
    if (analysis.objectiveAppearsImpossible) {
      val failureReason = if (isVerify) "Assertion failed: ${analysis.reasoning}" else "Cannot achieve goal: ${analysis.reasoning}"
      emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = failureReason)
      return StepResult(
        executed = false,
        passed = if (isVerify) false else null,
        error = failureReason,
        screenSummary = analysis.screenSummary,
        suggestedHint = if (!isVerify) analysis.suggestedHint else null,
      ).toMarkdown()
    }

    // HIGH or MEDIUM confidence → execute
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
          input = goal,
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

    // LOW confidence → return options (inner agent may also suggest different tools)
    Console.log("│ ? Low confidence - needs guidance")
    emitObjectiveComplete(promptStep, stepStartTime, success = false, failureReason = "Low confidence: ${analysis.reasoning}")
    return StepResult(
      executed = false,
      needsInput = true,
      result = "Uncertain: ${analysis.reasoning}",
      screenSummary = analysis.screenSummary,
      suggestion = analysis.recommendedTool,
      suggestedHint = analysis.suggestedHint,
    ).toMarkdown()
  }

  @LLMDescription(
    """
    Ask a question, get an answer.

    ask(question="What's the current balance?")
    ask(question="What buttons are visible?")
    ask(question="Is there an error message?")

    Unlike blaze(hint="VERIFY"), this returns information - not pass/fail.
    """
  )
  @Tool(McpToolProfile.TOOL_ASK)
  suspend fun ask(
    @LLMDescription("Your question (e.g., 'What's the current balance?')")
    question: String,
  ): String {
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    val startTime = Clock.System.now()

    val screenState = screenStateProvider()
    if (screenState == null) {
      val driverStatus = driverStatusProvider?.invoke()
        ?: "No device connected. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first."
      Console.error("[ask] Screen state is null — driverStatus=$driverStatus")
      return AskResult(
        answer = null,
        error = driverStatus,
      ).toMarkdown()
    }
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
    )

    // Ask is for the outer agent's situational awareness only — not recorded in trails.
    // Only blaze() calls (including hint="VERIFY") are tracked as trail steps.
    emitAskLog(question, result.answer, result.screenSummary, null, traceId, startTime)

    return result.toMarkdown()
  }

  /**
   * Selects tools for the inner agent based on the hint.
   *
   * - hint="VERIFY": read-only assertion tools (OBSERVATION + VERIFICATION)
   * - everything else: all available tools
   *
   * When [toolClassesOverrideProvider] returns non-null, those classes are used instead of
   * the default [ToolSetCategoryMapping] selection. This allows driver-specific tool sets
   * (e.g., Compose tools) to replace native tools that don't work with that driver.
   */
  private fun selectToolsForHint(hint: String?): List<TrailblazeToolDescriptor> {
    val overrideClasses = toolClassesOverrideProvider?.invoke()
    val toolClasses = if (overrideClasses != null) {
      overrideClasses
    } else if (BlazeHint.from(hint) == BlazeHint.VERIFY) {
      ToolSetCategoryMapping.getToolClasses(ToolSetCategory.OBSERVATION) +
        ToolSetCategoryMapping.getToolClasses(ToolSetCategory.VERIFICATION)
    } else {
      ToolSetCategoryMapping.getToolClasses(ToolSetCategory.ALL)
    }
    val tools = availableToolsProvider()
    if (tools.isNotEmpty()) {
      return tools
    }
    // Fallback when no device-specific tools are available (e.g. no device connected yet).
    return ToolSetCategoryMapping.getToolClasses(ToolSetCategory.ALL)
      .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
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
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)

  /** Human-readable markdown format for MCP tool responses. */
  fun toMarkdown(): String = buildString {
    if (error != null) {
      append("**❌ Error** — $error")
    } else if (answer != null) {
      append("**Answer:** $answer")
    }

    if (screenSummary != null) {
      append("\n\n**Screen:** $screenSummary")
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
