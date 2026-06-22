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
   * LLM model used for self-heal when recordings fail or don't exist.
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
     * Uses recordings with self-heal, suitable for production test runs
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
     * Recording-first with an AI-level retry budget.
     *
     * Plays the recorded tool calls from each step's `.trail.yaml` first and only falls
     * back to the LLM analyzer when playback actually fails. Keeps [AI_ONLY]'s
     * `maxRetries = 3` so fallback/AI-executed steps get the same attempt budget they
     * would have under pure AI mode.
     *
     * Use this when you have authored recordings but want AI resilience for transient
     * playback failures (e.g. selector drift, timing issues) rather than trading those
     * for [DETERMINISTIC]'s harder failure mode.
     */
    val RECORDING_WITH_AI_RETRIES = TrailConfig(
      mode = TrailExecutionMode.RECORDING_WITH_FALLBACK,
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
