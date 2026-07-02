package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailheadDefinition
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
   * given device. The result mirrors what `TrailblazeYaml.decodeTrail` returns
   * for a v1 YAML string: a singleton `ConfigTrailItem`, then — when the unified
   * document declares one — an optional `TrailheadTrailItem` (the deterministic
   * step 0), followed by a single `PromptsTrailItem` containing every step's NL +
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
    // Resolve the per-classifier driver pin (config.devices maps classifier -> driver) for THIS
    // device the same closest-wins way as recordings, collapsing to the single driver the v1
    // executor consumes for the run.
    val resolvedDriver = resolveClosestMatch(unified.config.devices, resolutionChain)
    // Observability, mirroring the per-step recording fall-through below: the trail pins drivers
    // for some classifiers but none match this device's chain, so the driver silently falls back
    // to runtime resolution (--driver > app setting). Surface it so an unexpected default-driver
    // run is debuggable. Same non-empty-chain gate keeps the config-only safe-mode decode quiet.
    if (resolvedDriver == null && !unified.config.devices.isNullOrEmpty() && resolutionChain.isNotEmpty()) {
      Console.log(
        "[unified-resolve] config pins drivers for ${unified.config.devices!!.keys} but none match " +
          "this device's chain $resolutionChain — driver falls back to runtime resolution.",
      )
    }
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
    // The optional trailhead lowers to a TrailheadTrailItem between config and prompts — its
    // per-classifier tools resolve the same closest-wins way as a regular step's recordings.
    val trailheadItem = unified.trailhead?.let { th ->
      val trailheadTools = resolveClosestMatch(th.recordings, resolutionChain)
      // Observability: the trailhead is the deterministic step 0, so a declared-but-unmatched
      // recording that silently drops its bootstrap tools is even worse than the per-step case
      // above — surface it the same way. Same non-empty-chain gate keeps the safe-mode decode quiet.
      if (trailheadTools == null && th.recordings.isNotEmpty() && resolutionChain.isNotEmpty()) {
        Console.log(
          "[unified-resolve] trailhead declares recordings for ${th.recordings.keys} but none " +
            "match this device's chain $resolutionChain — running the trailhead in LLM mode.",
        )
      }
      TrailYamlItem.TrailheadTrailItem(
        trailhead = TrailheadDefinition(
          step = th.step,
          tools = trailheadTools.orEmpty(),
          maxRetries = th.maxRetries,
        ),
      )
    }
    return listOfNotNull(
      TrailYamlItem.ConfigTrailItem(config = lowerConfig(unified.config, resolvedDriver)),
      trailheadItem,
      TrailYamlItem.PromptsTrailItem(promptSteps = promptSteps),
    )
  }

  /**
   * Lower a [UnifiedTrailConfig] to a v1 [TrailConfig], promoting the fields
   * v1 understands and collapsing the unified-only ones.
   *
   * `platform:`, `title:` (retired in the unified format) are left null on the
   * v1 side — the runtime resolves them from the device picker by the time the
   * lowered trail reaches the executor. The unified config's per-classifier
   * `devices:` map is NOT dropped: its keys aren't surfaced to v1 (the executor
   * doesn't need a support list), but the *driver value* for the device under
   * test is resolved closest-wins from that map and passed in as
   * [resolvedDriver] (null when unpinned), so the executor's driver resolution
   * (`--driver` > config driver > app setting) is unchanged. Callers with no
   * device (e.g. static config extraction) pass null.
   */
  fun lowerConfig(unified: UnifiedTrailConfig, resolvedDriver: String? = null): TrailConfig = TrailConfig(
    id = unified.id,
    target = unified.target,
    description = unified.description,
    driver = resolvedDriver,
    context = unified.context,
    metadata = unified.metadata,
    memory = unified.memory,
  )

  /**
   * Resolve the driver a unified [config] pins for the device described by
   * [deviceClassifiers] (the broad-first segment list a
   * [TrailblazeDeviceClassifiersProvider] emits, e.g. `[android, phone]`), using the same
   * closest-wins [TrailblazeClassifierLineage] the recordings use — so an `android:` pin
   * covers `android-phone`/`android-tablet`, and an `android-tablet:` pin wins over
   * `android:` on a tablet.
   *
   * Returns `null` when the config pins no driver reachable from this device's chain (or
   * pins none at all) — the caller then resolves the driver at run time. Unlike
   * [lowerToTrailItems] this touches only `config.devices`, never the step recordings, so it
   * is safe to call for static/pre-flight config extraction with any classifier list
   * (including empty) — it never trips the recordings guard in `decodeTrail`.
   */
  fun resolveDriver(
    config: UnifiedTrailConfig,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): String? {
    val resolutionChain = TrailblazeClassifierLineage.resolutionChain(deviceClassifiers).map { it.classifier }
    return resolveClosestMatch(config.devices, resolutionChain)
  }

  /**
   * Closest-wins lookup shared by recordings and the per-classifier `devices:`
   * driver map: walk [resolutionChain] (most-specific first, as produced by
   * [TrailblazeClassifierLineage]) and return the first entry from
   * [byClassifier] whose key matches. Returns `null` if none match — the caller
   * decides what to do (recordings → LLM mode; driver → runtime resolution).
   */
  private fun <V> resolveClosestMatch(
    byClassifier: Map<String, V>?,
    resolutionChain: List<String>,
  ): V? {
    if (byClassifier.isNullOrEmpty()) return null
    for (classifier in resolutionChain) {
      val match = byClassifier[classifier]
      if (match != null) return match
    }
    return null
  }
}
