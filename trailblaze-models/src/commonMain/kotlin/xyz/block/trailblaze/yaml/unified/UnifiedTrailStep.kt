package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * A single step in a unified trail. Shape:
 *
 * ```yaml
 * - step: <natural-language intent — required>
 *   recording:
 *     <classifier>: [tool, tool, ...]
 *     <classifier>: [tool, tool, ...]
 *   recordable: false   # mutually exclusive with non-empty recordings
 * ```
 *
 * `step` is the canonical NL — exactly one prose string per step across all
 * devices, and it is **required** (natural language is forced so every step
 * carries its intent and a trail stays legible / self-healing). Per-classifier
 * tool lists live **under `recording:`** (a singular key, optional), so a step's
 * top-level keys are reserved schema only (`step`, `recording`, `recordable`,
 * `maxRetries`) and never collide with the dynamic device-classifier vocabulary
 * (e.g. `android-phone`, `ios`, `android`). A step is therefore NL-only (blazed
 * live) or NL + per-device recording — never recording-only.
 *
 * An empty classifier list (`<classifier>: []`) is an explicit no-op for that
 * device class — distinguishable in coverage reports from accidental absence.
 *
 * Custom (de)serialization lives in `UnifiedTrailStepSerializer` because the
 * per-classifier keys under `recording:` are dynamic and can't be modeled with
 * stock kotlinx serialization.
 */
data class UnifiedTrailStep(
  /** Natural-language description — the test's intent at this step. Required (NL is forced). */
  val step: String,
  /**
   * Per-classifier tool lists (authored under the `recording:` key). Key is the
   * classifier name (e.g. `android-phone`, `ios`); value is the ordered list of
   * tool calls. Empty list means explicit no-op for that classifier.
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
