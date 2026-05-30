package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * A single step in a unified trail. Shape:
 *
 * ```yaml
 * - step: <natural-language intent — required>
 *   <classifier>: [tool, tool, ...]
 *   <classifier>: [tool, tool, ...]
 *   recordable: false   # mutually exclusive with classifier keys
 * ```
 *
 * `step` is the canonical NL — exactly one prose string per step across all
 * devices. Per-classifier tool lists live alongside it as dynamic keys whose
 * names come from the device-classifier vocabulary (e.g. `android-phone`,
 * `ios`, `android`).
 *
 * Reserved step-level keys: `step`, `recordable`. Any other key is treated as
 * a device classifier name. An empty classifier list (`<classifier>: []`) is
 * an explicit no-op for that device class — distinguishable in coverage
 * reports from accidental absence.
 *
 * Custom (de)serialization lives in `UnifiedTrailStepSerializer` because the
 * per-classifier keys are dynamic and can't be modeled with stock kotlinx
 * serialization.
 */
data class UnifiedTrailStep(
  /** Natural-language description — the test's intent at this step. */
  val step: String,
  /**
   * Per-classifier tool lists. Key is the classifier name (e.g.
   * `android-phone`, `ios`); value is the list of tool calls. Empty list
   * means explicit no-op for that classifier.
   */
  val recordings: Map<String, List<TrailblazeToolYamlWrapper>> = emptyMap(),
  /**
   * When false, this step is always handled by the LLM at run time — no
   * per-device recordings exist. Mutually exclusive with any [recordings]
   * entry being non-empty.
   */
  val recordable: Boolean = true,
  /**
   * Optional per-step retry-budget override. When set, the runtime uses
   * `maxRetries + 1` as the per-step attempt cap for THIS step only, instead
   * of `TrailblazeConfig.maxRetries`. Null = use the trail-wide default.
   *
   * Lowered to the v1 `DirectionStep.maxRetries` field at adapter time, so
   * the runtime executor sees the same value regardless of whether the trail
   * came in via the unified or legacy format.
   */
  val maxRetries: Int? = null,
)
