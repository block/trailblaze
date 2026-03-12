package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.mcp.RecordedToolCall
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolLoadingStrategy
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console

/**
 * Primary MCP tools for UI automation:
 * - blaze: Take an action toward a goal
 * - verify: Assert something is true (pass/fail)
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
) : ToolSet {

  @LLMDescription(
    """
    Take a step toward your goal.

    blaze(goal="Tap the login button")
    blaze(goal="Enter email test@example.com")
    blaze(goal="Open Settings app", toolHint="NAVIGATION")

    Returns what happened. If uncertain, returns options for you to decide.
    """
  )
  @Tool
  suspend fun blaze(
    @LLMDescription("What you want to accomplish (e.g., 'Tap the login button')")
    goal: String,
    @LLMDescription("Context from previous steps (optional)")
    context: String? = null,
    @LLMDescription("Tool hint: MINIMAL (tap/swipe/type), NAVIGATION (+launchApp), STANDARD (all). Default: MINIMAL for speed.")
    toolHint: String? = null,
  ): String {
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

    val screenState = screenStateProvider()
      ?: return StepResult(
        executed = false,
        error = "No device connected. Use device(action=ANDROID) or device(action=IOS) first.",
      ).toJson()

    val recommendationContext = RecommendationContext(
      objective = goal,
      progressSummary = context,
      hint = null,
      attemptNumber = 1,
    )

    // Select tools based on hint from outer agent (defaults to MINIMAL for speed)
    val tools = selectToolsForHint(toolHint)
    
    val analysis = try {
      // Pass selected tools so the LLM can call them directly (type-safe)
      screenAnalyzer.analyze(
        context = recommendationContext,
        screenState = screenState,
        traceId = traceId,
        availableTools = tools,
      )
    } catch (e: Exception) {
      return StepResult(
        executed = false,
        error = "Failed to analyze screen: ${e.message}",
      ).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [blaze] Goal: $goal")
    Console.log("│ Screen: ${analysis.screenSummary}")
    Console.log("│ Confidence: ${analysis.confidence}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    // Already done?
    if (analysis.objectiveAppearsAchieved) {
      return StepResult(
        executed = false,
        done = true,
        result = "Goal already achieved",
        screenSummary = analysis.screenSummary,
      ).toJson()
    }

    // Impossible? Check if inner agent suggests different tools
    if (analysis.objectiveAppearsImpossible) {
      return StepResult(
        executed = false,
        error = "Cannot achieve goal: ${analysis.reasoning}",
        screenSummary = analysis.screenSummary,
        suggestedToolHint = analysis.suggestedToolHint, // Inner agent may suggest different tools
      ).toJson()
    }

    // HIGH or MEDIUM confidence → execute
    if (analysis.confidence == Confidence.HIGH || analysis.confidence == Confidence.MEDIUM) {
      val executionResult = try {
        executor.execute(analysis.recommendedTool, analysis.recommendedArgs, traceId)
      } catch (e: Exception) {
        return StepResult(
          executed = false,
          error = "Action failed: ${e.message}",
          screenSummary = analysis.screenSummary,
        ).toJson()
      }

      val (result, success) = when (executionResult) {
        is ExecutionResult.Success -> {
          Console.log("│ ✓ Executed: ${analysis.recommendedTool}")
          StepResult(
            executed = true,
            result = analysis.reasoning.take(200),
            screenSummary = executionResult.screenSummaryAfter,
          ) to true
        }
        is ExecutionResult.Failure -> {
          Console.log("│ ✗ Failed: ${executionResult.error}")
          StepResult(
            executed = true,
            error = executionResult.error,
            screenSummary = analysis.screenSummary,
          ) to false
        }
      }

      // Record the step (success or failure)
      sessionContext?.recordStep(
        RecordedStep(
          type = RecordedStepType.STEP,
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
      return result.toJson()
    }

    // LOW confidence → return options (inner agent may also suggest different tools)
    Console.log("│ ? Low confidence - needs guidance")
    return StepResult(
      executed = false,
      needsInput = true,
      result = "Uncertain: ${analysis.reasoning}",
      screenSummary = analysis.screenSummary,
      suggestion = analysis.recommendedTool,
      suggestedToolHint = analysis.suggestedToolHint, // Inner agent may suggest different tools
    ).toJson()
  }

  @LLMDescription(
    """
    Check if something is true.
    
    verify(assertion="The login button is visible")
    verify(assertion="The welcome message shows 'Hello John'")
    
    Returns passed (true/false) with confidence level.
    """
  )
  @Tool
  suspend fun verify(
    @LLMDescription("What to verify (e.g., 'The login button is visible')")
    assertion: String,
  ): String {
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

    val screenState = screenStateProvider()
      ?: return VerifyResult(
        passed = false,
        error = "No device connected.",
      ).toJson()

    // Use the analyzer to check the assertion
    val recommendationContext = RecommendationContext(
      objective = "Verify: $assertion",
      progressSummary = null,
      hint = "Just check if this is true, don't take any action",
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
      return VerifyResult(
        passed = false,
        error = "Failed to analyze screen: ${e.message}",
      ).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [verify] $assertion")
    Console.log("│ Result: ${if (analysis.objectiveAppearsAchieved) "PASSED" else "FAILED"}")
    Console.log("│ Confidence: ${analysis.confidence}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    val result = VerifyResult(
      passed = analysis.objectiveAppearsAchieved,
      confidence = analysis.confidence.name,
      details = analysis.reasoning,
      screenSummary = analysis.screenSummary,
    )

    // Record the verification
    sessionContext?.recordStep(
      RecordedStep(
        type = RecordedStepType.VERIFY,
        input = assertion,
        toolCalls = emptyList(), // Verify doesn't execute tools
        result = if (result.passed) "PASSED" else "FAILED",
        success = result.passed,
      ),
    )

    return result.toJson()
  }

  @LLMDescription(
    """
    Ask a question, get an answer.
    
    ask(question="What's the current balance?")
    ask(question="What buttons are visible?")
    ask(question="Is there an error message?")
    
    Unlike verify(), this returns information - not pass/fail.
    """
  )
  @Tool
  suspend fun ask(
    @LLMDescription("Your question (e.g., 'What's the current balance?')")
    question: String,
  ): String {
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

    val screenState = screenStateProvider()
      ?: return AskResult(
        answer = null,
        error = "No device connected.",
      ).toJson()

    val recommendationContext = RecommendationContext(
      objective = "Answer this question: $question",
      progressSummary = null,
      hint = "Describe what you see that answers this question. Don't take any action.",
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
      return AskResult(
        answer = null,
        error = "Failed to analyze screen: ${e.message}",
      ).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [ask] $question")
    Console.log("│ Answer: ${analysis.screenSummary}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    val result = AskResult(
      answer = analysis.reasoning,
      screenSummary = analysis.screenSummary,
    )

    // Record the question
    sessionContext?.recordStep(
      RecordedStep(
        type = RecordedStepType.ASK,
        input = question,
        toolCalls = emptyList(), // Ask doesn't execute tools
        result = result.answer ?: "",
        success = true,
      ),
    )

    return result.toJson()
  }

  /**
   * Selects tools based on the hint from the outer agent and the loading strategy.
   *
   * When [ToolLoadingStrategy.ALL_TOOLS] (default):
   * - No hint → all tools (same as STANDARD/ALL)
   * - Explicit hints still respected for when callers want to override
   *
   * When [ToolLoadingStrategy.PROGRESSIVE]:
   * - No hint → MINIMAL (~6 tools, ~600 tokens)
   * - NAVIGATION: +launchApp, openUrl, scrollUntilVisible
   * - STANDARD/ALL: Full tool set (~17 tools, ~3,200 tokens)
   */
  private fun selectToolsForHint(toolHint: String?): List<TrailblazeToolDescriptor> {
    val hint = toolHint?.uppercase()?.trim()
    val strategy = sessionContext?.toolLoadingStrategy ?: ToolLoadingStrategy.ALL_TOOLS

    val toolClasses = when (hint) {
      "MINIMAL" -> {
        // Explicitly requested minimal tools
        ToolSetCategoryMapping.getInnerAgentMinimalTools()
      }
      null -> {
        // No hint: behavior depends on loading strategy
        when (strategy) {
          ToolLoadingStrategy.ALL_TOOLS -> ToolSetCategoryMapping.getToolClasses(ToolSetCategory.STANDARD)
          ToolLoadingStrategy.PROGRESSIVE -> ToolSetCategoryMapping.getInnerAgentMinimalTools()
        }
      }
      "NAVIGATION" -> {
        // MINIMAL + navigation tools (launchApp, openUrl, etc.)
        ToolSetCategoryMapping.getInnerAgentMinimalTools() +
          ToolSetCategoryMapping.getToolClasses(ToolSetCategory.NAVIGATION)
      }
      "STANDARD", "ALL" -> {
        // Full tool set (slower but more capable)
        ToolSetCategoryMapping.getToolClasses(ToolSetCategory.STANDARD)
      }
      else -> {
        // Try to parse as a category name
        val category = ToolSetCategory.entries.find { it.name.equals(hint, ignoreCase = true) }
        if (category != null) {
          ToolSetCategoryMapping.getToolClasses(category)
        } else {
          // Unknown hint, fall back to minimal
          Console.log("[StepTool] Unknown toolHint '$toolHint', using MINIMAL")
          ToolSetCategoryMapping.getInnerAgentMinimalTools()
        }
      }
    }

    return toolClasses.mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
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
   * If the inner agent couldn't find an appropriate tool, it suggests a different tool set.
   * The outer agent can retry with `toolHint=suggestedToolHint`.
   * 
   * Examples: "NAVIGATION" (needs launchApp), "VERIFICATION" (needs assert tools)
   */
  val suggestedToolHint: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class VerifyResult(
  val passed: Boolean,
  val confidence: String? = null,
  val details: String? = null,
  val error: String? = null,
  val screenSummary: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class AskResult(
  val answer: String?,
  val error: String? = null,
  val screenSummary: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
