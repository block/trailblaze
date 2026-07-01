package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
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
 * the closest-wins classifier hierarchy for the device under test. The unified
 * document remains the source of truth on disk; this adapter is just the
 * transition-period bridge so call sites that already speak v1 can keep
 * working without a full executor rewrite.
 *
 * **Closest-wins resolution is driven by [TrailblazeClassifierLineage]**, not
 * by a caller-supplied chain. [classifiers] is the device's broad-first
 * classifier segment list as a [TrailblazeDeviceClassifiersProvider] emits it
 * (`[ios, iphone]`, `[android, phone]`); the lineage joins those into the
 * device's compound identity (`ios-iphone`) and expands it into the total,
 * most-specific-first chain (`[ios-iphone, ios]`). For each step's
 * `recordings: Map<String, …>`, the first chain entry present in the map wins.
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
   *
   * @param classifiers the device's broad-first classifier segments (e.g.
   *   `[ios, iphone]`), as emitted by a `TrailblazeDeviceClassifiersProvider`.
   *   Resolved into a most-specific-first lineage chain internally.
   */
  fun lowerToTrailItems(
    unified: UnifiedTrail,
    classifiers: List<TrailblazeDeviceClassifier>,
  ): List<TrailYamlItem> {
    val resolutionChain = TrailblazeClassifierLineage.resolutionChain(classifiers).map { it.classifier }
    val promptSteps = unified.trail.map { step ->
      val tools = resolveClosestMatch(step.recordings, resolutionChain)
      // Observability: a step that DECLARES recordings but matches none on this device's chain
      // lowers to LLM mode indistinguishably from an intentional `recordable`/no-recording step.
      // Surface it so a mis-keyed classifier or a genuine coverage gap is debuggable. Gated on a
      // non-empty chain so the config-only safe-mode decode (empty classifiers) stays quiet.
      if (tools == null && step.recordings.isNotEmpty() && resolutionChain.isNotEmpty()) {
        Console.log(
          "[unified-resolve] step \"${step.step.take(60)}\" declares recordings for " +
            "${step.recordings.keys} but none match this device's chain $resolutionChain — " +
            "running it in LLM mode.",
        )
      }
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
   * Closest-wins lookup: walk [resolutionChain] (most-specific first, as
   * produced by [TrailblazeClassifierLineage]) and return the first matching
   * entry from [recordings]. Returns `null` if no entry matches — caller
   * decides what to do (the lowering treats this as "no recording → LLM mode").
   */
  private fun resolveClosestMatch(
    recordings: Map<String, List<TrailblazeToolYamlWrapper>>,
    resolutionChain: List<String>,
  ): List<TrailblazeToolYamlWrapper>? {
    if (recordings.isEmpty()) return null
    for (classifier in resolutionChain) {
      val match = recordings[classifier]
      if (match != null) return match
    }
    return null
  }
}
