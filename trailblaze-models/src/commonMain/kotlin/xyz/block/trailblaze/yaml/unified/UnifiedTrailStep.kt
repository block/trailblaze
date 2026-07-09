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
 * A step may be authored as `- verify: <NL>` instead of `- step: <NL>` (exactly
 * one of the two, same optional sibling keys). A verify step is an assertion:
 * at run time it lowers to the v1 `VerificationStep`, which the runtime treats
 * differently from a direction step (verify-scoped tool surface, auto-terminate
 * + assertion ledger, never self-healed). The NL text lands in [step] either
 * way; [verify] records which keyword it was authored under.
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
  /**
   * True when this step was authored as `- verify:` rather than `- step:`. Lowers to the v1
   * [xyz.block.trailblaze.yaml.VerificationStep] so verify semantics (assertion-only tool
   * surface, auto-terminate, no self-heal) survive the unified format. Never true on a
   * trailhead (a trailhead is a deterministic bootstrap, not an assertion).
   *
   * Declared last so positional Kotlin callers and `componentN` destructuring keep their
   * pre-verify meaning (source-compatible). Binary compatibility is NOT preserved — the data
   * class's 4-arg constructor is replaced by a 5-arg one in the ABI — an accepted break under
   * this repo's hard-cut policy (the .api baselines record it).
   */
  val verify: Boolean = false,
)
