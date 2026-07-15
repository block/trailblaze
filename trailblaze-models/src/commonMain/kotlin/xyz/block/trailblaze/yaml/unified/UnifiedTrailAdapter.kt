package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.VerificationStep

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
    // Resolve the per-classifier skip reason the same closest-wins way, lowering to the single v1
    // `TrailConfig.skip` the runner/CLI consult before executing.
    val resolvedSkip = resolveSkip(unified.config, classifiers)
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
      // `tools == null` means no classifier in the chain matched — blaze via AI. A matched-but-empty
      // list (an explicit `classifier: []` no-op) must NOT collapse into that same null — it carries
      // forward as a zero-tool ToolRecording, a deterministic no-op (see ToolRecording's 3-state doc).
      val recording = tools?.let { ToolRecording(tools = it) }
      // A `verify:` step lowers to the v1 VerificationStep so the runtime keeps verify semantics
      // (assertion-scoped tool surface, auto-terminate + assertion ledger, never self-healed).
      if (step.verify) {
        VerificationStep(
          verify = step.step,
          recordable = step.recordable,
          recording = recording,
          maxRetries = step.maxRetries,
        )
      } else {
        DirectionStep(
          step = step.step,
          recordable = step.recordable,
          recording = recording,
          maxRetries = step.maxRetries,
        )
      }
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
          // Pass the resolution through as-is (null vs empty are both meaningful — see
          // ToolRecording's 3-state doc); collapsing to `.orEmpty()` here would turn "no
          // classifier matched" into "explicitly declared zero tools."
          tools = trailheadTools,
          maxRetries = th.maxRetries,
        ),
      )
    }
    return listOfNotNull(
      TrailYamlItem.ConfigTrailItem(config = lowerConfig(unified.config, resolvedDriver, resolvedSkip)),
      trailheadItem,
      TrailYamlItem.PromptsTrailItem(promptSteps = promptSteps),
    )
  }

  /**
   * `true` if [unified] carries a recording for the device described by [classifiers] (its
   * broad-first segment list, e.g. `[android, phone]`), resolved with the SAME closest-wins
   * [TrailblazeClassifierLineage] lowering [lowerToTrailItems] uses — so a family `android:`
   * recording covers `android-phone`/`android-tablet`, and a CI `requireRecordings` gate that calls
   * this selects exactly the cases the executor would actually replay for the device.
   *
   * "Recorded" here means the device's chain resolves to a DECLARED classifier entry, matching the
   * 3-state model in [lowerToTrailItems] / [TrailblazeYaml.hasRecordedSteps]:
   *  - no classifier in the chain matches → [resolveClosestMatch] is `null` → the step runs in LLM
   *    mode (blaze via AI). This is the "no recording" case, and the only one that returns `false`.
   *  - a matched non-empty list → a captured deterministic recording.
   *  - a matched empty list (an explicit `android: []`) → a NON-NULL zero-tool [ToolRecording], a
   *    deterministic no-op that replays with zero tools (NOT AI). [lowerToTrailItems] carries it
   *    forward as `recording != null` and [TrailblazeYaml.hasRecordedSteps] counts it, so this gate
   *    counts it too — otherwise plan-time selection would drop a case the executor deterministically
   *    replays (the false-negative this gate exists to prevent), and the two "is it recorded?"
   *    predicates would disagree.
   *
   * Returns `false` only when NOTHING on the device's chain resolves to a declared entry — the trail
   * declares recordings for other classifiers only, or none at all.
   */
  fun hasRecordingForDevice(
    unified: UnifiedTrail,
    classifiers: List<TrailblazeDeviceClassifier>,
  ): Boolean {
    val resolutionChain =
      TrailblazeClassifierLineage.resolutionChain(classifiers).map { it.classifier }
    val stepHasRecording = unified.trail.any { step ->
      resolveClosestMatch(step.recordings, resolutionChain) != null
    }
    if (stepHasRecording) return true
    return unified.trailhead?.let { resolveClosestMatch(it.recordings, resolutionChain) != null }
      ?: false
  }

  /**
   * Lower a [UnifiedTrailConfig] to a v1 [TrailConfig], promoting the fields
   * v1 understands and collapsing the unified-only ones.
   *
   * `platform:` (retired in the unified format) is left null on the v1 side —
   * the runtime resolves it from the device picker by the time the lowered
   * trail reaches the executor. The unified config's per-classifier
   * `devices:` map is NOT dropped: its keys aren't surfaced to v1 (the executor
   * doesn't need a support list), but the *driver value* for the device under
   * test is resolved closest-wins from that map and passed in as
   * [resolvedDriver] (null when unpinned), so the executor's driver resolution
   * (`--driver` > config driver > app setting) is unchanged. Callers with no
   * device (e.g. static config extraction) pass null.
   *
   * [resolvedSkip] is the closest-wins skip reason for the device under test (see [resolveSkip]);
   * like [resolvedDriver] it's resolved by the caller so this stays a pure field-mapper. `tags`
   * are trail-level, so they lower verbatim (no per-device resolution).
   */
  fun lowerConfig(
    unified: UnifiedTrailConfig,
    resolvedDriver: String? = null,
    resolvedSkip: String? = null,
  ): TrailConfig {
    val (bridgedSource, plainMetadata) = splitBridgedMetadata(unified.metadata)
    return TrailConfig(
      id = unified.id,
      target = unified.target,
      title = unified.title,
      description = unified.description,
      priority = unified.priority,
      source = bridgedSource,
      driver = resolvedDriver,
      skip = resolvedSkip,
      tags = unified.tags,
      context = unified.context,
      metadata = plainMetadata,
      memory = unified.memory,
      args = unified.args,
    )
  }

  /**
   * Seed a [UnifiedTrailConfig] from a v1 [TrailConfig], carrying every device-agnostic field:
   * `id`, `target`, `title`, `description`, `priority`, `context`, `memory`, `metadata` map
   * one-to-one, while the informational `source` is bridged into the unified `metadata` under
   * the reserved keys (see [UnifiedTrailConfig.metadata]) — [lowerConfig] lifts it back, so
   * v1 readers of `TrailConfig.source` see identical values from either format. The
   * per-classifier maps (`devices`, `skip`) and the trail-level `tags` are left null for the
   * caller to fill from every contributing file/recording — a single v1 config can't express
   * them. v1's `platform` is dropped (retired — the device set derives from the classifier
   * slots), and a v1 `electron:` block is REFUSED (fail loud, never silently dropped): it is
   * driver-specific structured launch config with zero corpus usage, and its unified home
   * (likely target-level, not per-trail) is deferred until a real Electron trail needs one.
   *
   * Single source of truth for the v1→unified identity mapping, shared by the recorder's
   * [mergeRecordedClassifier] first-write seed and [xyz.block.trailblaze.migration.UnifiedTrailMigrator],
   * so adding a config field can't silently drift the two apart.
   */
  fun v1ConfigToUnifiedConfig(v1: TrailConfig): UnifiedTrailConfig {
    require(v1.electron == null) {
      "Refusing to convert a v1 config with an `electron:` block to the unified format: the " +
        "unified config deliberately has no electron field (driver-specific launch config, " +
        "zero corpus usage — its home is deferred until a real Electron trail needs one). " +
        "Silently dropping it would change how the trail launches. Keep this trail in the v1 " +
        "format, or move the launch config out of the trail file."
    }
    return UnifiedTrailConfig(
      id = v1.id,
      target = v1.target,
      title = v1.title,
      description = v1.description,
      priority = v1.priority,
      context = v1.context,
      memory = v1.memory,
      args = v1.args,
      metadata = bridgeMetadata(v1),
    )
  }

  /**
   * The unified `metadata` map for a v1 config: the v1 `metadata` entries (order preserved)
   * plus the reserved bridge keys carrying `source:`. A bare `source: {}` marker
   * bridges as an empty-string [UnifiedTrailConfig.METADATA_KEY_SOURCE] so it round-trips.
   * On a key collision the first-class v1 field wins (none exist in the corpus).
   */
  private fun bridgeMetadata(v1: TrailConfig): Map<String, String>? {
    val bridged = linkedMapOf<String, String>()
    v1.metadata?.let { bridged.putAll(it) }
    v1.source?.let { source ->
      bridged[UnifiedTrailConfig.METADATA_KEY_SOURCE] = source.type?.name.orEmpty()
      source.reason?.let { bridged[UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON] = it }
    }
    return bridged.ifEmpty { null }
  }

  /**
   * Inverse of [bridgeMetadata]: lift the reserved bridge keys back into the v1 `source` and
   * return the remaining plain metadata (null when emptied, so a v1 config that had no
   * `metadata:` round-trips byte-equal). A [UnifiedTrailConfig.METADATA_KEY_SOURCE] value that
   * is neither empty nor a known [TrailSourceType] name is NOT treated as a bridge — the key
   * stays in metadata untouched rather than being destroyed by a failed parse.
   */
  private fun splitBridgedMetadata(
    metadata: Map<String, String>?,
  ): Pair<TrailSource?, Map<String, String>?> {
    if (metadata == null) return null to null
    val sourceTypeRaw = metadata[UnifiedTrailConfig.METADATA_KEY_SOURCE]
    val parsedSourceType = sourceTypeRaw
      ?.takeIf { it.isNotEmpty() }
      ?.let { raw -> TrailSourceType.entries.firstOrNull { it.name == raw } }
    val sourceBridges = sourceTypeRaw != null && (sourceTypeRaw.isEmpty() || parsedSourceType != null)
    val source = if (sourceBridges) {
      TrailSource(type = parsedSourceType, reason = metadata[UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON])
    } else {
      null
    }
    val remaining = metadata
      .let { if (sourceBridges) it - UnifiedTrailConfig.METADATA_KEY_SOURCE - UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON else it }
      .ifEmpty { null }
    return source to remaining
  }

  /**
   * Fill the device-agnostic scalar fields [base] lacks from [fallback] — the merge primitive
   * for folding multiple v1 configs into one canonical unified config (the migrator folds
   * platform files in filename order, then `blaze.yaml`). [base]'s value wins whenever it is
   * *meaningfully* declared; a field counts as absent when it is null, a blank string, or an
   * empty map, so a placeholder in an earlier file never shadows a populated value in a later
   * one. An empty marker is still kept when no file declares better.
   * [UnifiedTrailConfig.metadata] merges per-key (see [mergeMetadata]). The per-classifier maps
   * (`devices`, `skip`) and the trail-level `tags` are merged by the caller and left untouched
   * here.
   *
   * Lives next to [v1ConfigToUnifiedConfig] so the scalar field list has one home — the
   * round-trip completeness test covers both, so a new `TrailConfig` field can't be carried by
   * one and silently dropped by the other.
   */
  fun fillMissingConfigScalars(
    base: UnifiedTrailConfig,
    fallback: UnifiedTrailConfig,
  ): UnifiedTrailConfig = base.copy(
    id = base.id.orIfAbsent(fallback.id),
    target = base.target.orIfAbsent(fallback.target),
    title = base.title.orIfAbsent(fallback.title),
    description = base.description.orIfAbsent(fallback.description),
    priority = base.priority.orIfAbsent(fallback.priority),
    context = base.context.orIfAbsent(fallback.context),
    memory = base.memory.orIfAbsent(fallback.memory),
    // Whole-map (not per-key) like `memory`: a trail's parameter contract is atomic — unioning two
    // files' arg declarations could fabricate a signature no single source declared.
    args = base.args.orArgsIfAbsent(fallback.args),
    metadata = mergeMetadata(base.metadata, fallback.metadata),
  )

  /**
   * Metadata merges per-KEY (union; [base]'s value wins on a shared key), unlike the other
   * fields' whole-value fill: the reserved bridge keys mean one file's `source:` and another
   * file's plain metadata land in the same map, and an atomic first-map-wins would re-drop
   * whichever the first file lacked. `memory` deliberately stays whole-map — it is
   * runtime-load-bearing, and unioning two platforms' seeds could fabricate a combination no
   * file declared.
   */
  private fun mergeMetadata(
    base: Map<String, String>?,
    fallback: Map<String, String>?,
  ): Map<String, String>? = when {
    base.isNullOrEmpty() -> fallback ?: base
    fallback.isNullOrEmpty() -> base
    else -> fallback + base
  }

  private fun String?.orIfAbsent(fallback: String?): String? =
    takeUnless { it.isNullOrBlank() } ?: fallback ?: this

  private fun Map<String, String>?.orIfAbsent(fallback: Map<String, String>?): Map<String, String>? =
    takeUnless { it.isNullOrEmpty() } ?: fallback ?: this

  // Distinct name (not an `orIfAbsent` overload): the two Map overloads erase to the same JVM
  // signature, and `@JvmName` isn't available in commonMain (wasmJs target).
  private fun Map<String, xyz.block.trailblaze.yaml.TrailArgConfig>?.orArgsIfAbsent(
    fallback: Map<String, xyz.block.trailblaze.yaml.TrailArgConfig>?,
  ): Map<String, xyz.block.trailblaze.yaml.TrailArgConfig>? =
    takeUnless { it.isNullOrEmpty() } ?: fallback ?: this

  /**
   * Resolve the skip reason a unified [config] declares for the device described by
   * [deviceClassifiers], using the same closest-wins [TrailblazeClassifierLineage] the recordings
   * and [resolveDriver] use — so an `android:` skip covers `android-phone`/`android-tablet`.
   *
   * Returns `null` when nothing applies. Two cases where nothing applies: the config declares no
   * skip at all, or it skips only classifiers this device's chain doesn't reach (skip is
   * per-platform — a trail skipped on `ios:` still runs on Android). The one exception is a
   * **device-agnostic** caller (empty [deviceClassifiers], e.g. a pre-flight with no device yet):
   * with no chain to resolve against, the trail counts as skipped if *any* classifier declares a
   * non-blank reason, so the CLI's skip gate still fires. Blank reasons are ignored (v1 semantics:
   * `skip: ""` is not a skip).
   */
  fun resolveSkip(
    config: UnifiedTrailConfig,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): String? {
    val skipMap = config.skip
    if (skipMap.isNullOrEmpty()) return null
    val resolutionChain = TrailblazeClassifierLineage.resolutionChain(deviceClassifiers).map { it.classifier }
    for (classifier in resolutionChain) {
      skipMap[classifier]?.takeIf { it.isNotBlank() }?.let { return it }
    }
    // No device chain (device-agnostic caller) → skipped if any classifier declares a reason.
    // Pick deterministically (sort by classifier key) since `skip` is a plain Map with no
    // guaranteed iteration order, so the reason surfaced here doesn't depend on decode order.
    if (resolutionChain.isEmpty()) {
      return skipMap.entries.sortedBy { it.key }.firstOrNull { it.value.isNotBlank() }?.value
    }
    return null
  }

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

  /**
   * Fold a freshly-recorded single-device trail into an [existing] unified trail's per-classifier
   * slots and return the merged [UnifiedTrail]. This is the recorder's write-back primitive: a
   * session recorded on one device contributes ONLY its own [classifier]'s recordings and driver
   * pin; every other classifier already in [existing] is preserved untouched, so re-recording on
   * Android never disturbs the iOS slot.
   *
   * The recorded side is passed as the v1 [recordedItems] that [generateRecordedYaml] already
   * produces (this keeps the recording-generation pipeline unchanged — merge just lowers its output
   * into the unified file). The driver pin comes from the recorded config's `driver:`.
   *
   * **Replace, not append, per classifier.** This device's prior contribution (its recordings in
   * every step + trailhead, and its `config.devices` driver pin) is stripped first, then the fresh
   * recording is overlaid — so re-recording the same device on the same trail updates its slot in
   * place instead of duplicating tools.
   *
   * **Step alignment is by index** (like [xyz.block.trailblaze.migration.UnifiedTrailMigrator]).
   * Existing NL wins where a step already exists — it is the device-agnostic canonical intent, so a
   * re-record on one device never rewrites the shared prose; a recorded NL that disagrees at the
   * same index is logged as drift, not applied. Recorded steps beyond [existing]'s length are
   * appended, carrying only this classifier's recording plus the recorded NL. A recorded step with
   * no tools leaves this classifier ABSENT from that step's map (runs in LLM mode) rather than
   * writing `classifier: []` (which the model reserves for a deliberate no-op).
   *
   * The step KIND (`step:` vs `verify:`) is preserved: an appended step takes `verify: true` when
   * the recorded step is a [VerificationStep]; on merge into an existing step the EXISTING kind
   * wins (it is device-agnostic canon, like the NL), with kind disagreement logged as drift.
   *
   * @param existing the current on-disk unified trail, or `null` for the first write of this trail.
   * @param recordedItems the v1 items from [generateRecordedYaml] for this one device.
   * @param classifier the recorded device's classifier slot (e.g. `android`, `ios-iphone`).
   */
  fun mergeRecordedClassifier(
    existing: UnifiedTrail?,
    recordedItems: List<TrailYamlItem>,
    classifier: String,
  ): UnifiedTrail {
    val recordedConfig = recordedItems
      .filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config
    val recordedPrompts = recordedItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }
    val recordedTrailhead = recordedItems
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>()
      .firstOrNull()?.trailhead

    // Base config: keep the existing file's config when merging; on a first write seed from the
    // recording's own config. Carry every v1 field the unified config can model so the first write
    // is lossless: the scalar fields via the shared seed helper (identity/title/priority/context/
    // memory verbatim; source bridged into metadata's reserved keys), trail-level `tags`
    // verbatim, and the v1 scalar `skip` lifted into this classifier's slot of the per-classifier
    // skip map (blank reasons dropped — v1 semantics). The driver is handled per-classifier just
    // below. (v1 `platform` is the one field with no unified home and is not seeded here.)
    val baseConfig = existing?.config
      ?: recordedConfig?.let {
        v1ConfigToUnifiedConfig(it).copy(
          tags = it.tags,
          skip = it.skip?.takeIf { reason -> reason.isNotBlank() }?.let { reason -> mapOf(classifier to reason) },
        )
      }
      ?: UnifiedTrailConfig()

    // Replace this classifier's driver pin: strip it, then re-add if the recording carried one.
    // Collapse an emptied map back to null so an unpinned trail stays unpinned.
    val devicesStripped = baseConfig.devices?.minus(classifier)?.ifEmpty { null }
    val mergedDevices = recordedConfig?.driver
      ?.let { (devicesStripped ?: emptyMap()) + (classifier to it) }
      ?: devicesStripped
    val mergedConfig = baseConfig.copy(devices = mergedDevices)

    // Strip this classifier everywhere first (replace semantics), then overlay the recording.
    val baseSteps = existing?.trail.orEmpty().map { it.withoutClassifier(classifier) }
    val mergedSteps = (0 until maxOf(baseSteps.size, recordedPrompts.size)).mapNotNull { i ->
      val base = baseSteps.getOrNull(i)
      val recorded = recordedPrompts.getOrNull(i)
      val recordedTools = recorded?.recording?.tools.orEmpty()
      when {
        base != null && recorded != null -> {
          if (base.step.trim() != recorded.prompt.trim()) {
            // info (not log): a re-record whose NL diverged from the on-disk step keeps the existing
            // prose — the user should see that their recorded tools were bound to different wording,
            // even on a normal (non-verbose) run where Console.log is suppressed.
            Console.info(
              "[unified-record] step ${i + 1} NL drift on classifier `$classifier`: keeping existing " +
                "\"${base.step.take(60)}\" over recorded \"${recorded.prompt.take(60)}\".",
            )
          }
          if (base.verify != (recorded is VerificationStep)) {
            // Same policy as NL drift: the existing kind is device-agnostic canon and wins; the
            // user should see that this device recorded the step under the other keyword.
            Console.info(
              "[unified-record] step ${i + 1} kind drift on classifier `$classifier`: keeping " +
                "existing `${if (base.verify) "verify" else "step"}:` over recorded " +
                "`${if (recorded is VerificationStep) "verify" else "step"}:`.",
            )
          }
          // An always-LLM step (recordable:false) must never carry recordings — the two are
          // mutually exclusive and the unified parser rejects the combination. Preserve the
          // author's "never record" intent by dropping the recorded tools rather than corrupting
          // the file.
          if (recordedTools.isNotEmpty() && base.recordable) {
            base.withClassifier(classifier, recordedTools)
          } else {
            if (recordedTools.isNotEmpty()) logDroppedRecordableFalse(i, classifier)
            base
          }
        }
        base != null -> base // existing step this device didn't reach — classifier already stripped
        recorded != null -> {
          // Same invariant on an appended step: a recordable:false step keeps no recordings.
          val attach = recordedTools.isNotEmpty() && recorded.recordable
          if (recordedTools.isNotEmpty() && !recorded.recordable) logDroppedRecordableFalse(i, classifier)
          UnifiedTrailStep(
            step = recorded.prompt,
            verify = recorded is VerificationStep,
            recordings = if (attach) mapOf(classifier to recordedTools) else emptyMap(),
            recordable = recorded.recordable,
            maxRetries = recorded.maxRetries,
          )
        }
        else -> null
      }
    }

    return UnifiedTrail(
      config = mergedConfig,
      trailhead = mergeRecordedTrailhead(existing?.trailhead?.withoutClassifier(classifier), recordedTrailhead, classifier),
      trail = mergedSteps,
    )
  }

  /**
   * Merge one device's recorded [recorded] trailhead into the [base] trailhead (this classifier
   * already stripped from [base]). A trailhead is one tool per platform, so this classifier's slot
   * is the recorded trailhead's single tool list. Existing trailhead NL wins; falls back to the
   * recorded NL, then to [TrailheadDefinition.DEFAULT_STEP] (the shorthand-trailhead placeholder) so
   * the required `step:` is never blank. Returns [base] unchanged when the recording had no
   * trailhead, and `null` only when neither side has one.
   */
  private fun mergeRecordedTrailhead(
    base: UnifiedTrailStep?,
    recorded: TrailheadDefinition?,
    classifier: String,
  ): UnifiedTrailStep? {
    if (recorded == null) return base
    val step = base?.step ?: recorded.step ?: TrailheadDefinition.DEFAULT_STEP
    val recordedTools = recorded.tools
    val recordings = if (!recordedTools.isNullOrEmpty()) {
      (base?.recordings ?: emptyMap()) + (classifier to recordedTools)
    } else {
      base?.recordings ?: emptyMap()
    }
    return UnifiedTrailStep(
      step = step,
      recordings = recordings,
      maxRetries = base?.maxRetries ?: recorded.maxRetries,
    )
  }

  private fun logDroppedRecordableFalse(stepIndex: Int, classifier: String) {
    // info (not log): dropping recorded tools is a data-affecting decision the user should see even
    // on a normal run, not just under --verbose.
    Console.info(
      "[unified-record] step ${stepIndex + 1} is recordable:false (always-LLM); dropping the recorded " +
        "`$classifier` tools to preserve that intent (recordings and recordable:false are mutually exclusive).",
    )
  }

  private fun UnifiedTrailStep.withoutClassifier(classifier: String): UnifiedTrailStep =
    if (classifier in recordings) copy(recordings = recordings - classifier) else this

  private fun UnifiedTrailStep.withClassifier(
    classifier: String,
    tools: List<TrailblazeToolYamlWrapper>,
  ): UnifiedTrailStep = copy(recordings = recordings + (classifier to tools))
}
