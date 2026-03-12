package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.TrailblazeLlmModel

/**
 * Configuration for trail execution mode.
 *
 * Trail mode executes predefined steps from .trail.yaml files. The execution
 * mode determines how steps are executed and when to fall back to AI.
 *
 * ## Execution Modes
 *
 * - **DETERMINISTIC**: Only use recordings, fail if no recording exists
 * - **RECORDING_WITH_FALLBACK**: Try recordings first, fall back to AI on failure
 * - **HYBRID**: Use recordings where available, AI for unrecorded steps
 * - **AI_ONLY**: Always use AI, ignore recordings (useful for debugging/recording)
 *
 * ## Example Usage
 *
 * ```kotlin
 * val config = TrailConfig(
 *   mode = TrailExecutionMode.RECORDING_WITH_FALLBACK,
 *   fallbackModel = TrailblazeLlmModels.GPT_4O_MINI,
 *   maxRetries = 3,
 *   stepTimeoutMs = 30_000L,
 * )
 * ```
 *
 * @property mode The execution mode determining how steps are executed
 * @property fallbackModel LLM model to use when falling back from recordings to AI
 * @property maxRetries Maximum retry attempts per step before failing
 * @property stepTimeoutMs Timeout in milliseconds for each step execution
 *
 * @see TrailExecutionMode
 */
@Serializable
data class TrailConfig(
  /**
   * Execution mode determining how trail steps are executed.
   *
   * Controls the balance between deterministic recordings and AI-based execution.
   * Default is RECORDING_WITH_FALLBACK for reliability with graceful degradation.
   */
  val mode: TrailExecutionMode = TrailExecutionMode.RECORDING_WITH_FALLBACK,

  /**
   * LLM model used for AI fallback when recordings fail or don't exist.
   *
   * Should be a vision-capable model for screen analysis. Uses GPT-4o-mini
   * by default as a cost-effective choice with good vision capabilities.
   */
  val fallbackModel: TrailblazeLlmModel? = null,

  /**
   * Maximum number of retry attempts per step before marking it as failed.
   *
   * Retries apply to both recording playback and AI execution. A value of 0
   * means no retries (fail immediately). Default is 2 for balanced reliability.
   */
  val maxRetries: Int = 2,

  /**
   * Timeout in milliseconds for each step execution.
   *
   * Applies to both recording playback and AI execution. Steps exceeding
   * this timeout will be retried or failed based on maxRetries. Default
   * is 30 seconds which handles most UI interactions.
   */
  val stepTimeoutMs: Long = 30_000L,
) {
  companion object {
    /**
     * Default configuration for reliable trail execution.
     *
     * Uses recordings with AI fallback, suitable for production test runs
     * where reliability is important but some flexibility is acceptable.
     */
    val DEFAULT = TrailConfig()

    /**
     * Strict configuration that only uses recordings.
     *
     * Fails immediately if recordings don't exist or fail to execute.
     * Use this for validating recording quality or in environments
     * where AI calls are not permitted.
     */
    val DETERMINISTIC = TrailConfig(
      mode = TrailExecutionMode.DETERMINISTIC,
      fallbackModel = null,
      maxRetries = 1,
    )

    /**
     * Configuration for AI-only execution (no recordings).
     *
     * Useful for initial test development, debugging, or generating
     * new recordings. All steps will use AI-based execution.
     */
    val AI_ONLY = TrailConfig(
      mode = TrailExecutionMode.AI_ONLY,
      maxRetries = 3,
    )

    /**
     * CI/CD configuration for deterministic trail execution.
     *
     * Optimized for continuous integration environments where determinism,
     * reproducibility, and cost are critical. Executes only recorded steps
     * with minimal retries and no fallback to AI.
     *
     * Guarantees:
     * - Zero LLM calls (no API costs)
     * - Zero network dependencies on AI services
     * - Maximum execution speed
     * - Fully reproducible results
     *
     * Use this preset in CI/CD pipelines where you have pre-recorded trail
     * files and need fast, reliable, deterministic test execution.
     */
    val CI_DETERMINISTIC = TrailConfig(
      mode = TrailExecutionMode.DETERMINISTIC,
      fallbackModel = null,
      maxRetries = 1,
      stepTimeoutMs = 30_000L,
    )
  }
}

/**
 * Configuration for blaze exploration mode.
 *
 * Blaze mode explores the application to achieve an objective without
 * predefined steps. The AI analyzes screens and discovers the path
 * dynamically, optionally generating recordings for future trail execution.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val config = BlazeConfig(
 *   analyzerModel = TrailblazeLlmModels.GPT_4O,
 *   maxIterations = 50,
 *   generateRecording = true,
 *   enableReflection = true,
 * )
 * ```
 *
 * @property analyzerModel LLM model for screen analysis and action planning
 * @property maxIterations Maximum iterations before stopping exploration
 * @property generateRecording Whether to record actions for trail generation
 * @property enableReflection Whether to enable reflection/self-correction
 *
 * @see TrailConfig
 */
@Serializable
data class BlazeConfig(
  /**
   * LLM model used for screen analysis and action planning.
   *
   * Should be a capable vision model for accurate screen understanding.
   * More expensive models may provide better exploration results.
   * Default is null, which will use the configured default model.
   */
  val analyzerModel: TrailblazeLlmModel? = null,

  /**
   * Maximum number of analysis-action iterations before stopping.
   *
   * Each iteration consists of: analyze screen → plan action → execute action.
   * Higher values allow more complex explorations but increase cost and time.
   * Default is 30 which handles most common user flows.
   */
  val maxIterations: Int = 30,

  /**
   * Whether to record executed actions for trail generation.
   *
   * When true, successful action sequences are captured and can be
   * exported as .trail.yaml files for deterministic replay. This
   * enables the "blaze once, trail forever" workflow.
   */
  val generateRecording: Boolean = true,

  /**
   * Whether to enable reflection and self-correction.
   *
   * When true, the agent periodically evaluates its progress toward
   * the objective and can backtrack or try alternative approaches
   * if it detects it's stuck or going in the wrong direction.
   *
   * Reflection adds some overhead but improves success rate for
   * complex or ambiguous objectives.
   */
  val enableReflection: Boolean = true,

  /**
   * Confidence threshold for action execution (0.0 to 1.0).
   *
   * Actions with confidence below this threshold may trigger
   * additional analysis or alternative approaches. Lower values
   * allow more aggressive exploration; higher values are more cautious.
   */
  val confidenceThreshold: Double = 0.7,

  /**
   * How often to trigger periodic reflection (in iterations).
   *
   * Lower values mean more frequent reflection checks, which can catch
   * issues earlier but adds some overhead. Higher values reduce overhead
   * but may take longer to detect stuck states.
   *
   * Only applies when [enableReflection] is true.
   */
  val reflectionInterval: Int = 10,

  /**
   * Maximum number of backtrack steps allowed per reflection.
   *
   * Limits how far back the agent can "undo" when reflection determines
   * a different approach is needed. Higher values allow more aggressive
   * course correction but may discard more progress.
   *
   * Only applies when [enableReflection] is true.
   */
  val maxBacktrackSteps: Int = 5,

  // ==========================================================================
  // Task Decomposition Configuration (Phase 3: Mobile-Agent-v3 Integration)
  // ==========================================================================

  /**
   * Whether to enable task decomposition for complex objectives.
   *
   * When true, objectives are broken into subtasks before exploration begins.
   * This improves success rate for complex multi-step goals by providing
   * clearer intermediate targets and progress tracking.
   *
   * Inspired by Mobile-Agent-v3's dynamic task decomposition.
   *
   * @see TaskPlan
   */
  val enableTaskDecomposition: Boolean = true,

  /**
   * LLM model used for task planning and decomposition.
   *
   * This model decomposes high-level objectives into subtasks. It should
   * have strong reasoning capabilities but doesn't need vision. Can be
   * a cheaper model than the analyzer model since it doesn't process images.
   *
   * Default is null, which will use the analyzer model.
   */
  val plannerModel: TrailblazeLlmModel? = null,

  /**
   * Minimum complexity for task decomposition (in estimated actions).
   *
   * Objectives estimated to require fewer actions than this threshold
   * will be executed directly without decomposition. This avoids
   * overhead for simple tasks.
   *
   * Default is 5 actions, meaning tasks estimated to take 4 or fewer
   * actions skip decomposition.
   */
  val decompositionThreshold: Int = 5,

  /**
   * Maximum number of subtasks to generate.
   *
   * Limits the granularity of task decomposition. Very fine-grained
   * plans can be harder to execute and more likely to need replanning.
   *
   * Default is 10 subtasks, suitable for most complex flows.
   */
  val maxSubtasks: Int = 10,

  /**
   * Maximum number of replan attempts when subtasks are blocked.
   *
   * When a subtask cannot be completed, the planner generates alternative
   * approaches. This limits how many times replanning can occur before
   * declaring the exploration stuck.
   *
   * Default is 3 replan attempts.
   */
  val maxReplanAttempts: Int = 3,

  /**
   * Maximum actions per subtask before considering it stuck.
   *
   * If a subtask takes more than this many actions without completing,
   * reflection is triggered to determine if replanning is needed.
   *
   * Default is 15 actions per subtask.
   */
  val maxActionsPerSubtask: Int = 15,
) {
  companion object {
    /**
     * Default configuration for blaze exploration.
     *
     * Balanced settings suitable for most exploration scenarios.
     * Generates recordings, enables reflection, and uses task decomposition
     * for reliability on complex objectives.
     */
    val DEFAULT = BlazeConfig()

    /**
     * Fast exploration configuration without recording.
     *
     * Use this for quick exploration or when recordings aren't needed.
     * Disables reflection and task decomposition for faster iteration.
     */
    val FAST = BlazeConfig(
      maxIterations = 20,
      generateRecording = false,
      enableReflection = false,
      enableTaskDecomposition = false,
    )

    /**
     * Thorough exploration configuration.
     *
     * Higher iteration limit, lower confidence threshold, and enhanced
     * task decomposition for comprehensive exploration of complex applications.
     */
    val THOROUGH = BlazeConfig(
      maxIterations = 100,
      enableReflection = true,
      confidenceThreshold = 0.5,
      enableTaskDecomposition = true,
      maxSubtasks = 15,
      maxReplanAttempts = 5,
    )

    /**
     * Configuration optimized for complex multi-app workflows.
     *
     * Maximizes task decomposition capabilities for objectives that
     * span multiple apps or require many steps.
     */
    val COMPLEX_WORKFLOW = BlazeConfig(
      maxIterations = 150,
      enableReflection = true,
      confidenceThreshold = 0.6,
      reflectionInterval = 8,
      enableTaskDecomposition = true,
      decompositionThreshold = 3,
      maxSubtasks = 20,
      maxReplanAttempts = 5,
      maxActionsPerSubtask = 20,
    )

    /**
     * Simple configuration without task decomposition.
     *
     * For simple objectives where decomposition adds unnecessary overhead.
     * Still enables reflection for error recovery.
     */
    val SIMPLE = BlazeConfig(
      maxIterations = 15,
      enableReflection = true,
      enableTaskDecomposition = false,
    )

    /**
     * Memory-optimized configuration for on-device execution.
     *
     * Reduces memory footprint and iteration count for execution on resource-constrained
     * devices such as Firebase Test Lab and AWS Device Farm. Prioritizes efficient
     * task decomposition with reduced subtask counts and action limits.
     *
     * Key optimizations:
     * - Lower maxIterations (20) to reduce exploration breadth
     * - Smaller maxSubtasks (6) to reduce planning overhead
     * - Limited maxActionsPerSubtask (10) to detect stuck subtasks early
     * - Fewer backtrack steps (3) to minimize memory state tracking
     * - Reflection enabled at longer intervals (every 10 iterations) to save processing
     */
    val ON_DEVICE = BlazeConfig(
      maxIterations = 20,
      enableReflection = true,
      reflectionInterval = 10,
      maxBacktrackSteps = 3,
      enableTaskDecomposition = true,
      maxSubtasks = 6,
      maxActionsPerSubtask = 10,
    )

    /**
     * Cost-optimized configuration with tiered model recommendations.
     *
     * Designed for cost-sensitive deployments where minimizing LLM API calls
     * is a priority. Uses frontier models for vision-based screen analysis
     * and smaller, more efficient models for planning tasks.
     *
     * This preset pairs well with external model configuration that assigns:
     * - Frontier vision models (e.g., GPT-4o) to analyzerModel
     * - Mini/lightweight models (e.g., GPT-4o-mini) to plannerModel
     *
     * Enables task decomposition to reduce total API calls by planning
     * multiple steps upfront rather than deciding actions step-by-step.
     */
    val COST_OPTIMIZED = BlazeConfig(
      enableTaskDecomposition = true,
      enableReflection = true,
    )
  }
}
