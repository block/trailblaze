package xyz.block.trailblaze.agent.blaze

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.reflect.asToolType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.DecompositionResult
import xyz.block.trailblaze.agent.ReplanResult
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.Subtask
import xyz.block.trailblaze.agent.TaskPlan
import xyz.block.trailblaze.logs.model.TraceId

// ---------------------------------------------------------------------------
// Planner tool call types — typed results from LLM tool calls
// ---------------------------------------------------------------------------

/**
 * A subtask as returned by the LLM planner tool calls.
 *
 * Used in both [DecomposeObjectiveResult] and [ReplanSubtasksResult].
 */
@Serializable
data class PlannerSubtask(
  val description: String,
  val successCriteria: String,
  val estimatedActions: Int = 5,
) {
  fun toSubtask() = Subtask(
    description = description,
    successCriteria = successCriteria,
    estimatedActions = estimatedActions,
  )
}

/**
 * Typed result of a `decomposeObjective` tool call.
 *
 * Deserialized directly from the LLM's tool call arguments — no manual JSON
 * field extraction needed.
 */
@Serializable
data class DecomposeObjectiveResult(
  val subtasks: List<PlannerSubtask>,
  val reasoning: String,
  val confidence: Confidence = Confidence.MEDIUM,
)

/**
 * Typed result of a `replanSubtasks` tool call.
 *
 * Deserialized directly from the LLM's tool call arguments.
 */
@Serializable
data class ReplanSubtasksResult(
  val newSubtasks: List<PlannerSubtask>,
  val reasoning: String,
  /** True if the planner determines the overall objective is already achieved on the current screen. */
  val objectiveAlreadyAchieved: Boolean = false,
)

/**
 * Typed result of an LLM planner tool call.
 *
 * Each variant corresponds to a specific planner tool and carries properly
 * deserialized, type-safe fields. The raw [toolName] and [rawArguments] are
 * retained for logging only.
 */
sealed interface PlannerToolCallResult {

  /** Tool name as returned by the LLM (for logging). */
  val toolName: String

  /** Raw arguments JSON (for logging / TrailblazeLlmRequestLog). */
  val rawArguments: JsonObject

  /**
   * LLM called `decomposeObjective` — carries the typed decomposition.
   */
  data class Decompose(
    override val rawArguments: JsonObject,
    val result: DecomposeObjectiveResult,
  ) : PlannerToolCallResult {
    override val toolName: String get() = PlannerTools.DECOMPOSE_OBJECTIVE
  }

  /**
   * LLM called `replanSubtasks` — carries the typed replan.
   */
  data class Replan(
    override val rawArguments: JsonObject,
    val result: ReplanSubtasksResult,
  ) : PlannerToolCallResult {
    override val toolName: String get() = PlannerTools.REPLAN_SUBTASKS
  }

  companion object {
    private val lenientJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /**
     * Deserializes a raw tool call into the appropriate typed variant.
     *
     * This is the **single deserialization boundary** — callers of `PlannerLlmCall`
     * should use this to convert the raw JSON into a type-safe result.
     *
     * @param toolName The tool name from `Message.Tool.Call.tool`
     * @param arguments The parsed JSON arguments from `Message.Tool.Call.content`
     */
    fun fromRaw(toolName: String, arguments: JsonObject): PlannerToolCallResult {
      return when (toolName) {
        PlannerTools.DECOMPOSE_OBJECTIVE -> Decompose(
          rawArguments = arguments,
          result = lenientJson.decodeFromJsonElement(arguments),
        )

        PlannerTools.REPLAN_SUBTASKS -> Replan(
          rawArguments = arguments,
          result = lenientJson.decodeFromJsonElement(arguments),
        )

        else -> {
          // Unknown tool — try replan first (most likely a misspelled replanSubtasks),
          // then decompose as fallback. Wrap in try/catch so deserialization failures
          // don't crash the caller.
          try {
            Replan(
              rawArguments = arguments,
              result = lenientJson.decodeFromJsonElement(arguments),
            )
          } catch (_: Exception) {
            try {
              Decompose(
                rawArguments = arguments,
                result = lenientJson.decodeFromJsonElement(arguments),
              )
            } catch (_: Exception) {
              // Neither schema matched — return a minimal decompose with fallback data
              Decompose(
                rawArguments = arguments,
                result = DecomposeObjectiveResult(
                  subtasks = emptyList(),
                  reasoning = "Failed to deserialize unknown tool '$toolName'",
                  confidence = Confidence.LOW,
                ),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Function type for making LLM calls with tool calling from the planner.
 *
 * The implementor is responsible for:
 * 1. Building the Koog prompt with `ToolChoice.Required`
 * 2. Executing the LLM call with the provided tool descriptors
 * 3. Extracting the `Message.Tool.Call` from the response
 * 4. Calling [PlannerToolCallResult.fromRaw] to deserialize into the typed variant
 * 5. Logging the request/response
 *
 * @param systemPrompt System prompt for the LLM
 * @param userMessage User message for the LLM
 * @param tools Available tool descriptors (the LLM must call one)
 * @param traceId Trace ID to correlate this LLM request with its tool result in the log viewer
 * @param screenshotBytes Optional screenshot of the current screen. When provided, the
 *   implementor should attach it as an image alongside the user message so the LLM can
 *   make visually-informed planning decisions (inspired by Mobile-Agent-v3).
 * @return The typed tool call result
 */
typealias PlannerLlmCall = suspend (
  systemPrompt: String,
  userMessage: String,
  tools: List<ToolDescriptor>,
  /** Trace ID to correlate this LLM request with its tool result in the log viewer. */
  traceId: TraceId,
  /** Optional screenshot bytes for visual context during planning. */
  screenshotBytes: ByteArray?,
) -> PlannerToolCallResult

// ---------------------------------------------------------------------------
// PlanningNode — task decomposition and replanning
// ---------------------------------------------------------------------------

/**
 * Planning node for task decomposition in blaze exploration.
 *
 * Inspired by Mobile-Agent-v3's dynamic task decomposition, this component
 * breaks complex objectives into manageable subtasks before exploration begins.
 * Each subtask has clear success criteria, making it easier to track progress
 * and determine when to advance or replan.
 *
 * ## How It Works
 *
 * 1. **Decomposition**: Given a high-level objective, the planning node analyzes
 *    the goal and generates 3-10 ordered subtasks.
 *
 * 2. **Subtask Execution**: The blaze loop executes subtasks sequentially,
 *    using the current subtask's description as a focused objective.
 *
 * 3. **Replanning**: If a subtask is blocked or takes too many actions, the
 *    planning node can generate alternative subtasks to work around the blocker.
 *
 * ## Example
 *
 * ```
 * Objective: "Order a large pepperoni pizza from Domino's"
 *
 * Subtasks:
 * 1. Open Domino's app → Success: "Pizza ordering screen visible"
 * 2. Navigate to menu → Success: "Menu with pizza options visible"
 * 3. Select large pepperoni → Success: "Large pepperoni pizza selected"
 * 4. Add to cart → Success: "Item added to cart notification"
 * 5. Proceed to checkout → Success: "Checkout screen with order summary"
 * 6. Confirm order → Success: "Order confirmation displayed"
 * ```
 *
 * @param config Blaze configuration with task decomposition settings
 * @param decomposer Function that calls LLM to decompose objectives (dependency injected)
 * @param replanner Function that calls LLM to replan when blocked (dependency injected)
 *
 * @see TaskPlan for the plan data structure
 * @see Subtask for individual subtask structure
 * @see BlazeGoalPlanner for integration with the blaze loop
 */
class PlanningNode(
  private val config: BlazeConfig,
  private val decomposer: TaskDecomposer,
  private val replanner: TaskReplanner,
) {

  /**
   * Decomposes a high-level objective into subtasks.
   *
   * Analyzes the objective and current screen context to generate a plan
   * with 3-N ordered subtasks (where N is config.maxSubtasks). Each subtask is atomic enough
   * to complete in a few actions but meaningful enough to represent progress.
   *
   * ## Guidelines for Decomposition
   *
   * - Each subtask should be completable in 3-15 actions
   * - Success criteria should be observable on-screen
   * - Subtasks should be ordered for natural app flow
   * - Skip obvious prerequisites (e.g., "unlock phone" if already unlocked)
   *
   * @param objective The high-level goal to achieve
   * @param screenContext Current screen summary for context-aware decomposition
   * @return Decomposition result with task plan and reasoning
   */
  suspend fun decompose(
    objective: String,
    screenContext: String?,
    traceId: TraceId,
    screenshotBytes: ByteArray? = null,
  ): DecompositionResult {
    // Skip decomposition for simple objectives
    if (shouldSkipDecomposition(objective)) {
      return createSimplePlan(objective)
    }

    // Call LLM to decompose the objective
    val decomposition = decomposer.decompose(
      objective = objective,
      screenContext = screenContext,
      maxSubtasks = config.maxSubtasks,
      traceId = traceId,
      screenshotBytes = screenshotBytes,
    )

    // Validate and normalize the result
    return normalizeDecomposition(objective, decomposition)
  }

  /**
   * Replans when the current subtask is blocked.
   *
   * When a subtask cannot be completed (e.g., expected element not found,
   * unexpected state, too many failed actions), this method generates
   * alternative subtasks to work around the blocker.
   *
   * ## Replanning Strategies
   *
   * - **Alternative Path**: Find different way to achieve same goal
   * - **Workaround**: Skip blocked step if possible
   * - **Prerequisite**: Add missing prerequisite steps
   * - **Simplify**: Break current subtask into smaller pieces
   *
   * @param state Current blaze state with task plan
   * @param blockReason Description of why the current subtask is blocked
   * @param currentScreen Current screen analysis for context
   * @return Replan result with new subtasks and reasoning
   */
  suspend fun replan(
    state: BlazeState,
    blockReason: String,
    currentScreen: ScreenAnalysis,
    traceId: TraceId,
    screenshotBytes: ByteArray? = null,
  ): ReplanResult {
    val currentPlan = state.taskPlan
      ?: return ReplanResult(
        originalPlan = TaskPlan(state.objective, emptyList()),
        newSubtasks = listOf(
          Subtask(
            description = state.objective,
            successCriteria = "Objective achieved",
            estimatedActions = config.maxIterations,
          )
        ),
        reasoning = "No task plan exists, creating single-task fallback",
        blockReason = blockReason,
      )

    // Check if we've exceeded replan attempts
    if (currentPlan.replanCount >= config.maxReplanAttempts) {
      return ReplanResult(
        originalPlan = currentPlan,
        newSubtasks = emptyList(),
        reasoning = "Maximum replan attempts (${config.maxReplanAttempts}) exceeded",
        blockReason = blockReason,
      )
    }

    // Call LLM to generate alternative approach
    val replanResult = replanner.replan(
      originalPlan = currentPlan,
      blockReason = blockReason,
      currentScreenSummary = currentScreen.screenSummary,
      progressIndicators = currentScreen.progressIndicators,
      potentialBlockers = currentScreen.potentialBlockers,
      traceId = traceId,
      screenshotBytes = screenshotBytes,
    )

    return replanResult
  }

  /**
   * Determines if the current subtask appears complete.
   *
   * Uses the screen analysis to check if the success criteria for the
   * current subtask have been met. This is a heuristic check; the LLM
   * should confirm completion.
   *
   * @param state Current blaze state
   * @param analysis Current screen analysis
   * @return True if the subtask appears complete
   */
  fun isSubtaskComplete(
    state: BlazeState,
    analysis: ScreenAnalysis,
  ): Boolean {
    val currentSubtask = state.currentSubtask ?: return false

    // Check if screen summary indicates success criteria met
    val screenMatchesCriteria = analysis.screenSummary.contains(
      currentSubtask.successCriteria,
      ignoreCase = true,
    )

    // Check progress indicators
    val hasProgressIndicators = analysis.progressIndicators.any { indicator ->
      indicator.contains(currentSubtask.successCriteria, ignoreCase = true) ||
        currentSubtask.successCriteria.contains(indicator, ignoreCase = true)
    }

    return screenMatchesCriteria || hasProgressIndicators
  }

  /**
   * Determines if the current subtask appears stuck.
   *
   * A subtask is considered stuck if:
   * - Too many actions without progress
   * - Multiple low-confidence actions in a row
   * - Same action repeated multiple times
   * - Objective appears impossible
   *
   * @param state Current blaze state
   * @param analysis Current screen analysis
   * @return True if the subtask appears stuck
   */
  fun isSubtaskStuck(
    state: BlazeState,
    analysis: ScreenAnalysis,
  ): Boolean {
    // Check action count threshold
    if (state.currentSubtaskActions >= config.maxActionsPerSubtask) {
      return true
    }

    // Check if objective appears impossible
    if (analysis.objectiveAppearsImpossible) {
      return true
    }

    // Scope all history checks to the current subtask's actions only.
    // actionHistory is global across subtasks — without scoping, actions from a
    // previous subtask would bleed into the current one's stuck detection.
    val subtaskActions = state.actionHistory.takeLast(state.currentSubtaskActions)

    // Check for consecutive identical actions (same tool + same args).
    // Tool-agnostic: works for any tool, including dynamically provided ones.
    // 3 consecutive identical calls means the action isn't making progress.
    if (subtaskActions.size >= REPETITIVE_ACTION_THRESHOLD) {
      val recentActions = subtaskActions.takeLast(REPETITIVE_ACTION_THRESHOLD)
      val allSameTool = recentActions.map { it.toolName }.toSet().size == 1
      val allSameArgs = recentActions.map { it.toolArgs.toString() }.toSet().size == 1
      if (allSameTool && allSameArgs) {
        return true
      }
    }

    // Check for repeated actions (potential loop) — less strict check.
    // Catches cases where the same tool type is used with slightly different args.
    if (subtaskActions.size >= 5) {
      val recentActions = subtaskActions.takeLast(5)
      val uniqueTools = recentActions.map { it.toolName }.distinct()
      if (uniqueTools.size == 1) {
        // Same tool 5 times in a row - likely stuck
        return true
      }
    }

    // Check for consecutive low-confidence actions (scoped to subtask)
    val lowConfidenceStreak = subtaskActions.takeLastWhile {
      it.confidence == Confidence.LOW
    }.size
    if (lowConfidenceStreak >= 3) {
      return true
    }

    return false
  }

  /**
   * Determines if decomposition should be skipped for simple objectives.
   */
  private fun shouldSkipDecomposition(objective: String): Boolean {
    // Very short objectives are likely simple
    if (objective.length < 30) {
      return true
    }

    // Check for simple action keywords
    val simpleKeywords = listOf(
      "tap ", "click ", "press ", "type ", "enter ",
      "scroll to", "find ", "verify ", "check ",
    )
    if (simpleKeywords.any { objective.lowercase().startsWith(it) }) {
      return true
    }

    return false
  }

  /**
   * Creates a simple plan for objectives that don't need decomposition.
   */
  private fun createSimplePlan(objective: String): DecompositionResult {
    val subtask = Subtask(
      description = objective,
      successCriteria = "Objective achieved",
      estimatedActions = 3,
    )

    return DecompositionResult(
      plan = TaskPlan(
        objective = objective,
        subtasks = listOf(subtask),
      ),
      reasoning = "Simple objective does not require decomposition",
      confidence = Confidence.HIGH,
    )
  }

  /**
   * Normalizes and validates the decomposition result.
   */
  private fun normalizeDecomposition(
    objective: String,
    decomposition: DecompositionResult,
  ): DecompositionResult {
    val normalizedSubtasks = decomposition.plan.subtasks
      .take(config.maxSubtasks)
      .map { subtask ->
        subtask.copy(
          estimatedActions = subtask.estimatedActions.coerceIn(1, config.maxActionsPerSubtask),
        )
      }

    // Ensure at least one subtask
    val finalSubtasks = if (normalizedSubtasks.isEmpty()) {
      listOf(
        Subtask(
          description = objective,
          successCriteria = "Objective achieved",
          estimatedActions = config.maxIterations / 2,
        )
      )
    } else {
      normalizedSubtasks
    }

    return decomposition.copy(
      plan = decomposition.plan.copy(subtasks = finalSubtasks),
    )
  }
}

// ---------------------------------------------------------------------------
// TaskDecomposer / TaskReplanner interfaces
// ---------------------------------------------------------------------------

/**
 * Interface for task decomposition via LLM.
 *
 * Implementations should call an LLM with appropriate prompting to decompose
 * high-level objectives into ordered subtasks.
 */
interface TaskDecomposer {
  /**
   * Decomposes an objective into subtasks.
   *
   * @param objective The high-level goal to decompose
   * @param screenContext Current screen context for informed decomposition
   * @param maxSubtasks Maximum number of subtasks to generate
   * @param traceId Trace ID to correlate this LLM request with its tool result log
   * @param screenshotBytes Optional screenshot for visual context during planning
   * @return Decomposition result with task plan
   */
  suspend fun decompose(
    objective: String,
    screenContext: String?,
    maxSubtasks: Int,
    traceId: TraceId,
    screenshotBytes: ByteArray? = null,
  ): DecompositionResult
}

/**
 * Interface for replanning via LLM when subtasks are blocked.
 *
 * Implementations should call an LLM to generate alternative subtasks
 * when the current approach isn't working.
 */
interface TaskReplanner {
  /**
   * Generates alternative subtasks when current path is blocked.
   *
   * @param originalPlan The plan that's being replanned
   * @param blockReason Why the current subtask is blocked
   * @param currentScreenSummary What's currently on screen
   * @param progressIndicators Signs of progress made so far
   * @param potentialBlockers Known blockers from screen analysis
   * @param traceId Trace ID to correlate this LLM request with its tool result log
   * @param screenshotBytes Optional screenshot for visual context during replanning
   * @return Replan result with alternative subtasks
   */
  suspend fun replan(
    originalPlan: TaskPlan,
    blockReason: String,
    currentScreenSummary: String,
    progressIndicators: List<String>,
    potentialBlockers: List<String>,
    traceId: TraceId,
    screenshotBytes: ByteArray? = null,
  ): ReplanResult
}

// ---------------------------------------------------------------------------
// Koog tool descriptors — proper typed schemas
// ---------------------------------------------------------------------------

/**
 * Koog tool descriptors for planner operations.
 *
 * Tool parameter schemas use proper [ToolParameterType.Object] and
 * [ToolParameterType.List] types so the LLM receives a real JSON schema
 * (not "pass a JSON string"). This means the LLM returns structured data
 * that deserializes directly into [DecomposeObjectiveResult] and
 * [ReplanSubtasksResult].
 *
 * Used with [ToolChoice.Required][ai.koog.prompt.params.LLMParams.ToolChoice.Required].
 */
object PlannerTools {

  /** Tool name for decomposing an objective into subtasks. */
  const val DECOMPOSE_OBJECTIVE = "decomposeObjective"

  /** Tool name for replanning when a subtask is blocked. */
  const val REPLAN_SUBTASKS = "replanSubtasks"

  private val subtaskObjectType = PlannerSubtask::class.starProjectedType.asToolType()
  private val stringType = String::class.starProjectedType.asToolType()

  /** Tool descriptor for [DECOMPOSE_OBJECTIVE]. */
  val decomposeObjectiveTool = ToolDescriptor(
    name = DECOMPOSE_OBJECTIVE,
    description = "Decompose a high-level objective into ordered subtasks for a mobile UI automation agent.",
    requiredParameters = listOf(
      ToolParameterDescriptor(
        name = "subtasks",
        description = "Ordered list of subtasks to achieve the objective",
        type = ToolParameterType.List(subtaskObjectType),
      ),
      ToolParameterDescriptor(
        name = "reasoning",
        description = "Brief explanation of the decomposition plan",
        type = stringType,
      ),
      ToolParameterDescriptor(
        name = "confidence",
        description = "Confidence level in the decomposition plan",
        type = ToolParameterType.Enum(Confidence.entries),
      ),
    ),
    optionalParameters = emptyList(),
  )

  /** Tool descriptor for [REPLAN_SUBTASKS]. */
  val replanSubtasksTool = ToolDescriptor(
    name = REPLAN_SUBTASKS,
    description = "Generate alternative subtasks to work around a blocked subtask, " +
      "or signal that the overall objective is already achieved.",
    requiredParameters = listOf(
      ToolParameterDescriptor(
        name = "newSubtasks",
        description = "Alternative subtasks to work around the blocker. " +
          "Pass an empty list if the overall objective is already achieved.",
        type = ToolParameterType.List(subtaskObjectType),
      ),
      ToolParameterDescriptor(
        name = "reasoning",
        description = "Why this alternative approach should work, " +
          "or why the overall objective is already achieved",
        type = stringType,
      ),
    ),
    optionalParameters = listOf(
      ToolParameterDescriptor(
        name = "objectiveAlreadyAchieved",
        description = "Set to true if the current screen shows the OVERALL OBJECTIVE " +
          "has already been achieved (e.g. via a side-effect of a previous action), " +
          "even though the specific subtask was not explicitly completed.",
        type = ToolParameterType.Boolean,
      ),
    ),
  )
}

// ---------------------------------------------------------------------------
// LLM-backed implementations
// ---------------------------------------------------------------------------

/**
 * Implementation of [TaskDecomposer] that uses LLM tool calling.
 *
 * Uses [ToolChoice.Required][ai.koog.prompt.params.LLMParams.ToolChoice.Required] via
 * [PlannerLlmCall] to force the LLM to return structured tool calls. The response
 * is deserialized into [DecomposeObjectiveResult] automatically — no manual
 * `args["subtasks"]?.toString()` JSON extraction.
 *
 * @param llmCall Tool-calling function that takes system/user prompts + tools
 */
class LlmTaskDecomposer(
  private val llmCall: PlannerLlmCall,
) : TaskDecomposer {

  override suspend fun decompose(
    objective: String,
    screenContext: String?,
    maxSubtasks: Int,
    traceId: TraceId,
    screenshotBytes: ByteArray?,
  ): DecompositionResult {
    val systemPrompt = "You are a mobile UI automation expert. " +
      "Decompose objectives into ordered subtasks by calling the decomposeObjective tool." +
      if (screenshotBytes != null) " A screenshot of the current screen is attached." else ""
    val userMessage = buildDecomposeUserMessage(objective, screenContext, maxSubtasks)

    return when (val result = llmCall(systemPrompt, userMessage, listOf(PlannerTools.decomposeObjectiveTool), traceId, screenshotBytes)) {
      is PlannerToolCallResult.Decompose -> toDecompositionResult(objective, result.result)
      is PlannerToolCallResult.Replan -> fallbackDecomposition(objective, "Unexpected replan tool call")
    }
  }

  private fun buildDecomposeUserMessage(
    objective: String,
    screenContext: String?,
    maxSubtasks: Int,
  ): String = buildString {
    appendLine("OBJECTIVE: $objective")
    screenContext?.let {
      appendLine()
      appendLine("CURRENT SCREEN: $it")
    }
    appendLine()
    appendLine("INSTRUCTIONS:")
    appendLine("- Generate $maxSubtasks or fewer ordered subtasks")
    appendLine("- Each subtask should be completable in 3-15 UI actions")
    appendLine("- Success criteria must be observable on-screen")
    appendLine("- Skip prerequisites already satisfied based on the CURRENT SCREEN context")
    appendLine("- Do NOT include steps to launch or navigate to an app that is already open")
    appendLine()
    appendLine("Call the decomposeObjective tool with your plan.")
  }

  private fun toDecompositionResult(
    objective: String,
    result: DecomposeObjectiveResult,
  ): DecompositionResult {
    return DecompositionResult(
      plan = TaskPlan(
        objective = objective,
        subtasks = result.subtasks.map { it.toSubtask() },
      ),
      reasoning = result.reasoning,
      confidence = result.confidence,
    )
  }

  private fun fallbackDecomposition(
    objective: String,
    reason: String,
  ): DecompositionResult {
    return DecompositionResult(
      plan = TaskPlan(
        objective = objective,
        subtasks = listOf(
          Subtask(
            description = objective,
            successCriteria = "Objective achieved",
            estimatedActions = 10,
          )
        ),
      ),
      reasoning = reason,
      confidence = Confidence.LOW,
    )
  }
}

/**
 * Implementation of [TaskReplanner] that uses LLM tool calling.
 *
 * Uses [ToolChoice.Required][ai.koog.prompt.params.LLMParams.ToolChoice.Required] via
 * [PlannerLlmCall] to get structured replanning results, deserialized into
 * [ReplanSubtasksResult] automatically.
 *
 * @param llmCall Tool-calling function that takes system/user prompts + tools
 */
class LlmTaskReplanner(
  private val llmCall: PlannerLlmCall,
) : TaskReplanner {

  override suspend fun replan(
    originalPlan: TaskPlan,
    blockReason: String,
    currentScreenSummary: String,
    progressIndicators: List<String>,
    potentialBlockers: List<String>,
    traceId: TraceId,
    screenshotBytes: ByteArray?,
  ): ReplanResult {
    val systemPrompt = "You are a mobile UI automation expert. " +
      "Generate alternative subtasks to work around a blocked subtask by calling the replanSubtasks tool." +
      if (screenshotBytes != null) " A screenshot of the current screen is attached." else ""
    val userMessage = buildReplanUserMessage(
      originalPlan, blockReason, currentScreenSummary,
      progressIndicators, potentialBlockers,
    )

    return when (val result = llmCall(systemPrompt, userMessage, listOf(PlannerTools.replanSubtasksTool), traceId, screenshotBytes)) {
      is PlannerToolCallResult.Replan -> toReplanResult(originalPlan, blockReason, result.result)
      is PlannerToolCallResult.Decompose -> fallbackReplan(originalPlan, blockReason, "Unexpected decompose tool call")
    }
  }

  private fun buildReplanUserMessage(
    originalPlan: TaskPlan,
    blockReason: String,
    currentScreenSummary: String,
    progressIndicators: List<String>,
    potentialBlockers: List<String>,
  ): String = buildString {
    appendLine("ORIGINAL OBJECTIVE: ${originalPlan.objective}")
    appendLine()
    appendLine("COMPLETED SUBTASKS:")
    originalPlan.subtasks.take(originalPlan.currentSubtaskIndex).forEachIndexed { i, subtask ->
      appendLine("  ${i + 1}. ${subtask.description} ✓")
    }
    appendLine()
    appendLine("BLOCKED SUBTASK:")
    originalPlan.currentSubtask?.let { subtask ->
      appendLine("  ${originalPlan.currentSubtaskIndex + 1}. ${subtask.description}")
      appendLine("  Success criteria: ${subtask.successCriteria}")
    }
    appendLine()
    appendLine("BLOCK REASON: $blockReason")
    appendLine()
    appendLine("CURRENT SCREEN: $currentScreenSummary")
    if (progressIndicators.isNotEmpty()) {
      appendLine("PROGRESS SIGNS: ${progressIndicators.joinToString(", ")}")
    }
    if (potentialBlockers.isNotEmpty()) {
      appendLine("POTENTIAL BLOCKERS: ${potentialBlockers.joinToString(", ")}")
    }
    appendLine()
    appendLine("IMPORTANT: Before generating alternative subtasks, check whether the ORIGINAL OBJECTIVE " +
      "has ALREADY been achieved based on the CURRENT SCREEN description. A previous action may have " +
      "achieved the goal as a side-effect (e.g. tapping an autocomplete suggestion navigated to the " +
      "destination). If the overall objective is already satisfied, set objectiveAlreadyAchieved=true " +
      "and pass an empty newSubtasks list.")
    appendLine()
    appendLine("Otherwise, call the replanSubtasks tool with alternative subtasks to work around this blocker.")
  }

  private fun toReplanResult(
    originalPlan: TaskPlan,
    blockReason: String,
    result: ReplanSubtasksResult,
  ): ReplanResult {
    return ReplanResult(
      originalPlan = originalPlan,
      newSubtasks = result.newSubtasks.map { it.toSubtask() },
      reasoning = result.reasoning,
      blockReason = blockReason,
      objectiveAlreadyAchieved = result.objectiveAlreadyAchieved,
    )
  }

  private fun fallbackReplan(
    originalPlan: TaskPlan,
    blockReason: String,
    reason: String,
  ): ReplanResult {
    return ReplanResult(
      originalPlan = originalPlan,
      newSubtasks = emptyList(),
      reasoning = reason,
      blockReason = blockReason,
    )
  }
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

/**
 * Creates a PlanningNode with LLM tool-calling decomposer and replanner.
 *
 * @param config Blaze configuration
 * @param plannerLlmCall Tool-calling LLM function (system prompt, user message, tools → typed result)
 * @return Configured PlanningNode
 */
fun createPlanningNode(
  config: BlazeConfig,
  plannerLlmCall: PlannerLlmCall,
): PlanningNode = PlanningNode(
  config = config,
  decomposer = LlmTaskDecomposer(plannerLlmCall),
  replanner = LlmTaskReplanner(plannerLlmCall),
)
