package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Lowers a [UnifiedTrail] document into the legacy v1 shape the runtime
 * executor already knows how to consume. Pure functions — no I/O, no parsing.
 *
 * The lowering is intentionally lossy: the unified format's per-classifier
 * recording map gets collapsed to a single per-step recording by resolving
 * the closest-wins classifier hierarchy against [TrailblazeDeviceClassifier]s
 * provided by the caller. The unified document remains the source of truth
 * on disk; this adapter is just the transition-period bridge so call sites
 * that already speak v1 can keep working without a full executor rewrite.
 *
 * Closest-wins resolution: for each step's `recordings: Map<String, …>`,
 * walk the supplied classifier list in order (most-specific first — e.g.
 * `[ios-iphone, ios]`) and pick the first key that's present in the map.
 * If none match, the lowered step has no recording (`recordable=true` but
 * `recording=null` — the executor falls back to LLM mode, same as a v1
 * `step:` with no `recording:` block).
 */
object UnifiedTrailAdapter {

  /**
   * Lower a [UnifiedTrail] document into legacy v1 [TrailYamlItem]s for the
   * given device. The result is exactly what `TrailblazeYaml.decodeTrail`
   * would return for a v1 YAML string: a singleton `ConfigTrailItem` followed
   * by a single `PromptsTrailItem` containing every step's NL +
   * (closest-wins-resolved) tool recording.
   */
  fun lowerToTrailItems(
    unified: UnifiedTrail,
    classifiers: List<TrailblazeDeviceClassifier>,
  ): List<TrailYamlItem> {
    val classifierStrings = classifiers.map { it.classifier }
    val promptSteps = unified.trail.map { step ->
      val tools = resolveClosestMatch(step.recordings, classifierStrings)
      DirectionStep(
        step = step.step,
        recordable = step.recordable,
        recording = tools?.takeIf { it.isNotEmpty() }?.let { ToolRecording(tools = it) },
        maxRetries = step.maxRetries,
      )
    }
    return listOf(
      TrailYamlItem.ConfigTrailItem(config = lowerConfig(unified.config)),
      TrailYamlItem.PromptsTrailItem(promptSteps = promptSteps),
    )
  }

  /**
   * Lower a [UnifiedTrailConfig] to a v1 [TrailConfig], promoting the fields
   * v1 understands and dropping the unified-only ones. `devices:` is
   * intentionally dropped — the executor doesn't read it, and surfacing it
   * through v1's `platform:` or `tags:` would mis-represent the unified
   * format's semantics.
   *
   * `driver:`, `platform:`, `title:` (retired in the unified format) are left
   * null on the v1 side — the runtime resolves them from the device picker
   * by the time the lowered trail reaches the executor.
   */
  fun lowerConfig(unified: UnifiedTrailConfig): TrailConfig = TrailConfig(
    id = unified.id,
    target = unified.target,
    context = unified.context,
    metadata = unified.metadata,
    memory = unified.memory,
  )

  /**
   * Closest-wins lookup: walk [classifiers] (most-specific first) and return
   * the first matching entry from [recordings]. Returns `null` if no entry
   * matches — caller decides what to do (the lowering treats this as "no
   * recording → LLM mode").
   */
  private fun resolveClosestMatch(
    recordings: Map<String, List<TrailblazeToolYamlWrapper>>,
    classifiers: List<String>,
  ): List<TrailblazeToolYamlWrapper>? {
    if (recordings.isEmpty()) return null
    for (classifier in classifiers) {
      val match = recordings[classifier]
      if (match != null) return match
    }
    return null
  }
}
