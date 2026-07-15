package xyz.block.trailblaze.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.ListSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import java.io.File

/**
 * Migrates a directory of legacy v1 `*.trail.yaml` files (plus optional
 * `blaze.yaml`) into a single unified `trail.yaml` file.
 *
 * Algorithm:
 *
 * 1. Load every `<classifier>.trail.yaml` in the directory; filename minus
 *    `.trail.yaml` is the classifier.
 * 2. Canonicalize config across every file: for each device-agnostic scalar
 *    (`id` / `target` / `title` / `description` / `priority` / `context` /
 *    `memory`) the first file to declare it wins (platform files in filename
 *    order, then `blaze.yaml`), so a field only one file carries is never
 *    dropped; `metadata` (which also carries the bridged v1 `source:` —
 *    see [xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig.metadata])
 *    merges per-key the same first-wins way. `platform:` is retired (the
 *    device set derives from the classifier slots) and a v1 `electron:` block
 *    is refused by the shared seed helper (fail loud, never silently drop).
 *    Two v1 fields are per-platform-file and become per-classifier maps keyed by
 *    each file's classifier: `driver:` → `devices:`, and `skip:` → `skip:` (v1's
 *    scalar skip reason keyed under that file's classifier, so a trail can be
 *    skipped on one device family while running on others — closest-wins at run
 *    time). Both maps are emitted only when at least one file contributed an
 *    entry; otherwise omitted (the device set is derived from the recorded
 *    classifiers at run time). Blank skip reasons are dropped (v1 semantics:
 *    `skip: ""` is not a skip). A non-blank `skip:` on `blaze.yaml` is
 *    device-agnostic (the CLI runs `blaze.yaml` standalone when no platform
 *    recording matches), so it's copied onto every present classifier that
 *    doesn't already declare its own skip — the unified map has no universal
 *    wildcard key. `tags:` is trail-level (device-agnostic), so every file's
 *    tags are unioned (de-duplicated, first-seen order) — a tag on any file
 *    describes the whole test. `memory:` (the AgentMemory pre-seed block)
 *    round-trips through the migration intact.
 * 3. For each step index, gather per-classifier NL, step kind (`step:` vs
 *    `verify:`) and tool recordings. Disagreeing NL becomes a drift warning
 *    surfaced in the output's leading comments; canonical NL is the first
 *    platform's. The kind is carried the same way (a v1 `verify:` step becomes
 *    a unified `verify:` step) with the same canonical preference, and
 *    platforms disagreeing on kind at an index is surfaced as drift — never
 *    silently flattened.
 * 4. Steps with no recording on any classifier are left at the default
 *    `recordable = true` — "not recorded yet" (runs via the agent, can be
 *    recorded later), not "never record". The migrator never auto-emits
 *    `recordable: false`; that flag is reserved for authors who deliberately
 *    want an LLM-only step.
 * 5. Collapse equivalent sub-classifiers into their family classifier. Sub-
 *    classifiers are inferred from the filename prefixes present in the
 *    directory — any two-or-more classifiers sharing a `<family>-` prefix form
 *    a family. When every present sub-classifier has equivalent recordings
 *    (`reason:` stripped before comparison), the recordings collapse to a
 *    single `<family>:` entry.
 *
 * Idempotent: re-running on already-merged input would already be the unified
 * format (and the CLI command refuses non-v1 inputs). Drift is surfaced via
 * comments, never silently flattened.
 */
class UnifiedTrailMigrator(
  private val trailblazeYaml: TrailblazeYaml = TrailblazeYaml.Default,
) {

  /**
   * Strict-mode sibling of [trailblazeYaml], used only by [TrailRoundTripDropDetector] to detect
   * input content the lenient decode drops. Built from the full classpath-discovered tool set (as
   * [TrailblazeYaml.Default] is) so recorded tool args validate against their real schemas. Lazy so
   * constructing a migrator stays cheap when `migrate()` is never called.
   *
   * Invariant: the detector only reports a drop when this strict schema and the lenient
   * [trailblazeYaml] share the same tool set. That holds because every caller injects
   * [TrailblazeYaml.Default] (also full-classpath), so strict decode = lenient decode + the
   * unknown-key throw. Injecting a narrower custom instance would make strict a superset and could
   * surface false positives — until a `withStrict()`-style affordance lets this derive from the
   * injected instance, keep `trailblazeYaml` `.Default`-compatible.
   */
  private val strictTrailblazeYaml: TrailblazeYaml by lazy { createTrailblazeYaml(strict = true) }

  /**
   * Migrate the per-platform v1 files in [inputDir] to a single [UnifiedTrail]
   * plus a structured [Report]. The caller chooses what to do with the
   * report (typically: print a summary, write the trail to disk).
   */
  fun migrate(inputDir: File): Result {
    require(inputDir.isDirectory) { "Input must be a directory: $inputDir" }
    val platformFiles = inputDir
      .listFiles { f -> f.isFile && f.name.endsWith(TRAIL_YAML_SUFFIX) && f.name != BLAZE_FILENAME }
      ?.sortedBy { it.name }
      .orEmpty()
    // A blaze.yaml-only directory is migratable: blaze.yaml is a v1 trail file with the same
    // schema as a `<classifier>.trail.yaml`, just device-agnostic and recording-less. The
    // blaze.yaml handling below already yields a clean recording-less unified trail, so this
    // guard only needs to refuse a directory with no v1 source at all.
    val blazeFile = File(inputDir, BLAZE_FILENAME)
    require(platformFiles.isNotEmpty() || blazeFile.isFile) {
      "No `*.trail.yaml` or `blaze.yaml` files found in $inputDir; nothing to migrate."
    }

    // Load every platform file as v1.
    val perClassifier = linkedMapOf<String, List<PromptStep>>()
    val memoryByClassifier = linkedMapOf<String, Map<String, String>?>()
    // Per-classifier driver pins. v1's `driver:` is per-platform-file, so each file contributes
    // one entry keyed by its classifier — a multi-platform trail keeps each platform's driver
    // (android accessibility, ios host, …) rather than collapsing to one.
    val driversByClassifier = linkedMapOf<String, String>()
    // Per-classifier skip reasons. v1's `skip:` is a scalar per-platform-file, so each file's
    // reason keys under its classifier — same shape as the driver map above. Blank reasons are
    // ignored (v1 semantics: `skip: ""` means "not skipped"). Divergence across files here is
    // expected by design (a trail can be skipped on one device family but not another), unlike
    // NL/memory below, where divergence signals an authoring bug and gets drift-detected instead.
    val skipByClassifier = linkedMapOf<String, String>()
    // Trail-level tags. Unlike driver/skip these aren't device-specific, so they're unioned across
    // every file (a tag on any platform describes the whole test) rather than keyed by classifier.
    // LinkedHashSet keeps first-seen order and de-duplicates a tag shared by multiple files.
    // Files disagreeing on tags is expected (each just contributes what it knows), not drift.
    val tagsUnion = linkedSetOf<String>()
    // blaze.yaml's own skip reason (device-agnostic — see the propagation comment below).
    var blazeSkip: String? = null
    var canonicalConfig: UnifiedTrailConfig? = null
    // Every file's scalar config, keyed by classifier (or BLAZE_KEY), retained so scalar
    // disagreements can be surfaced as drift after the fold picks a canonical value.
    val configsBySource = linkedMapOf<String, UnifiedTrailConfig>()
    // Input content the lenient decode drops (unknown keys the schema can't carry — e.g. malformed
    // positional anchors). Detected per input file against a strict decode; surfaced as leading
    // comments so a reviewer sees what vanished rather than catching it by eyeballing the diff.
    val droppedContent = mutableListOf<DroppedContentEntry>()
    for (file in platformFiles) {
      val classifier = file.name.removeSuffix(TRAIL_YAML_SUFFIX)
      val fileText = file.readText()
      droppedContent += detectDroppedContent(file.name, fileText)
      val items = trailblazeYaml.decodeTrail(fileText)
      assertNoTopLevelTools(items, file.name)
      assertNoTrailhead(items, file.name)
      val v1Config = trailblazeYaml.extractTrailConfig(items)
      if (v1Config != null) {
        val unified = v1ConfigToUnified(v1Config)
        configsBySource[classifier] = unified
        canonicalConfig = canonicalConfig.foldConfig(unified)
      }
      v1Config?.driver?.let { driversByClassifier[classifier] = it }
      v1Config?.skip?.takeIf { it.isNotBlank() }?.let { skipByClassifier[classifier] = it }
      v1Config?.tags?.let { tagsUnion.addAll(it) }
      // Track each platform's memory block so divergence across files surfaces as a drift
      // warning instead of silently using the first file's values. Mirrors the NL drift
      // pass below — same migrator philosophy: when platforms disagree, the user sees it.
      memoryByClassifier[classifier] = v1Config?.memory
      val prompts = items
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .flatMap { it.promptSteps }
      perClassifier[classifier] = prompts
    }

    // Load blaze.yaml if present. It contributes canonical NL only (no
    // recordings) and is the authoritative NL source when its step is
    // present — that matches the v1-era convention where blaze.yaml is the
    // hand-authored NL definition that platform files were recorded against.
    val blazePrompts: List<PromptStep> = if (blazeFile.isFile) {
      val blazeText = blazeFile.readText()
      droppedContent += detectDroppedContent(blazeFile.name, blazeText)
      val items = trailblazeYaml.decodeTrail(blazeText)
      assertNoTopLevelTools(items, blazeFile.name)
      assertNoTrailhead(items, blazeFile.name)
      val cfg = trailblazeYaml.extractTrailConfig(items)
      // A blaze-only migration (no platform files) has no device classifier, and the unified
      // `skip:`/`devices:` maps are classifier-keyed with no universal-wildcard key. So a
      // blaze.yaml `skip:`/`driver:` can't be represented and would be silently dropped — a
      // skipped trail would start running, or a pinned driver fall back to runtime resolution.
      // Refuse until the case gains a recording (whose classifier the value keys onto) or drops
      // the field. (With platform files present, `blazeSkip` below propagates onto each classifier
      // instead, so this only guards the classifier-less case.)
      if (platformFiles.isEmpty()) {
        require(cfg?.skip.isNullOrBlank()) {
          "Cannot migrate a blaze.yaml-only directory in $inputDir: it declares `skip:`, but a " +
            "recording-less unified trail has no device classifier to key the skip onto, so the " +
            "skip would be lost. Add a `<device>.trail.yaml` recording or remove the skip first."
        }
        require(cfg?.driver.isNullOrBlank()) {
          "Cannot migrate a blaze.yaml-only directory in $inputDir: it pins `driver:`, but a " +
            "recording-less unified trail has no device classifier to key the driver onto, so it " +
            "would fall back to runtime resolution. Add a `<device>.trail.yaml` recording or " +
            "remove the driver first."
        }
      }
      if (cfg != null) {
        val unified = v1ConfigToUnified(cfg)
        configsBySource[BLAZE_KEY] = unified
        canonicalConfig = canonicalConfig.foldConfig(unified)
      }
      // blaze.yaml is device-agnostic, so its tags join the trail-level union too.
      cfg?.tags?.let { tagsUnion.addAll(it) }
      blazeSkip = cfg?.skip?.takeIf { it.isNotBlank() }
      items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().flatMap { it.promptSteps }
    } else emptyList()

    // Classifiers present across the per-platform files — used for the family-collapse pass.
    val presentClassifiers = perClassifier.keys.toList().sorted()
    // blaze.yaml's `skip:` is honored by the CLI as a standalone runnable trail file (the
    // device-agnostic fallback when no platform recording matches), so a non-blank reason there
    // means "skip this trail everywhere" — not just wherever a platform file happens to repeat it.
    // The unified `skip:` map has no universal wildcard key (closest-wins resolves through a
    // classifier's own lineage only), so the only faithful translation is to copy the reason onto
    // every present classifier that doesn't already declare its own (more specific) skip — a
    // platform file's own `skip:` still wins for that platform.
    blazeSkip?.let { reason ->
      for (classifier in presentClassifiers) {
        skipByClassifier.putIfAbsent(classifier, reason)
      }
    }
    // Merge the per-classifier maps onto the canonical config. `devices:` (driver pins) and
    // `skip:` (skip reasons) are each emitted only when at least one classifier contributed an
    // entry; otherwise omitted — the supported classifiers are derivable from the steps'
    // recordings, and a driverless trail resolves the driver at run time.
    val mergedConfig = (canonicalConfig ?: UnifiedTrailConfig())
      .copy(
        devices = driversByClassifier.ifEmpty { null },
        skip = skipByClassifier.ifEmpty { null },
        tags = tagsUnion.ifEmpty { null }?.toList(),
      )

    // Per-step reconciliation across platforms; step count is the max of
    // (any platform's prompts length, blaze.yaml's prompts length).
    val platformMax = perClassifier.values.maxOfOrNull { it.size } ?: 0
    val maxSteps = maxOf(platformMax, blazePrompts.size)
    val driftReports = mutableListOf<DriftEntry>()
    val kindDriftReports = mutableListOf<KindDriftEntry>()
    val steps = mutableListOf<UnifiedTrailStep>()

    for (i in 0 until maxSteps) {
      val nlByClassifier = linkedMapOf<String, String>()
      val verifyByClassifier = linkedMapOf<String, Boolean>()
      val toolsByClassifier = linkedMapOf<String, List<TrailblazeToolYamlWrapper>>()
      for ((classifier, prompts) in perClassifier) {
        if (i < prompts.size) {
          val step = prompts[i]
          nlByClassifier[classifier] = step.prompt
          verifyByClassifier[classifier] = step is VerificationStep
          val tools = step.recording?.tools.orEmpty()
          if (tools.isNotEmpty()) {
            toolsByClassifier[classifier] = tools
          }
        }
      }
      val blazeStep: PromptStep? = blazePrompts.getOrNull(i)
      val blazeNl: String? = blazeStep?.prompt
      if (nlByClassifier.isEmpty() && blazeNl == null) continue

      // Canonical NL preference: blaze.yaml (if present) > first platform's NL.
      // blaze.yaml is the hand-authored intent statement; platform files were
      // recorded against it and may have drifted.
      val canonicalNl = blazeNl ?: nlByClassifier.values.first()
      val nlsForDrift = buildMap<String, String> {
        if (blazeNl != null) put(BLAZE_KEY, blazeNl)
        putAll(nlByClassifier)
      }
      val uniqueNls = nlsForDrift.values.map { it.trim() }.toSet()
      if (uniqueNls.size > 1) {
        driftReports.add(DriftEntry(stepIndex = i, nlByClassifier = nlsForDrift))
      }

      // Step kind (`step:` vs `verify:`) carries through with the same canonical preference as
      // the NL. Files disagreeing on kind at the same index is an authoring bug (verify semantics
      // are load-bearing at run time), so it's surfaced as drift — never silently flattened.
      val kindsForDrift = buildMap<String, Boolean> {
        if (blazeStep != null) put(BLAZE_KEY, blazeStep is VerificationStep)
        putAll(verifyByClassifier)
      }
      val canonicalVerify = kindsForDrift.values.first()
      if (kindsForDrift.values.toSet().size > 1) {
        kindDriftReports.add(KindDriftEntry(stepIndex = i, verifyByClassifier = kindsForDrift))
      }

      // recordable stays at its default (true): a step with no recording just runs via the
      // agent and can be recorded later. We deliberately do NOT emit `recordable: false` for
      // no-recording steps — that would mean "never record", which isn't the intent and is noise.
      steps.add(
        UnifiedTrailStep(
          step = canonicalNl,
          verify = canonicalVerify,
          recordings = toolsByClassifier,
        ),
      )
    }

    // Family-collapse pass.
    val families = inferFamilies(presentClassifiers)
    val collapseReports = mutableListOf<FamilyCollapseEntry>()
    val collapsedSteps = steps.map { step ->
      var current = step
      for ((family, members) in families) {
        val (collapsed, action) = collapseFamily(current, family, members)
        current = collapsed
        when (action) {
          CollapseAction.COLLAPSED -> collapseReports.add(
            FamilyCollapseEntry(family = family, members = members, diverged = false),
          )
          CollapseAction.DIVERGED -> collapseReports.add(
            FamilyCollapseEntry(family = family, members = members, diverged = true),
          )
          CollapseAction.NOT_APPLICABLE -> Unit
        }
      }
      current
    }

    // Detect cross-file memory drift. Normalize null → emptyMap before comparing so a
    // platform that simply omits `config.memory:` (vs one that explicitly sets it) only
    // surfaces as drift when the SETS differ — not when one is "absent" and another is
    // "absent but the YAML had an empty map".
    val normalizedMemories = memoryByClassifier.mapValues { (_, v) -> v.orEmpty() }
    val memoryDrift: List<MemoryDriftEntry> =
      if (normalizedMemories.values.toSet().size > 1) {
        listOf(MemoryDriftEntry(memoryByClassifier = normalizedMemories))
      } else {
        emptyList()
      }

    val migratedTrail = UnifiedTrail(config = mergedConfig, trail = collapsedSteps)

    return Result(
      trail = migratedTrail,
      report = Report(
        platformFilesLoaded = platformFiles.map { it.name },
        blazeLoaded = blazeFile.isFile,
        drift = driftReports,
        memoryDrift = memoryDrift,
        kindDrift = kindDriftReports,
        configDrift = detectConfigDrift(configsBySource),
        familyCollapses = collapseReports,
        unrecordableSteps = collapsedSteps.withIndex().count { !it.value.recordable },
        droppedContent = droppedContent,
        // Confidence check: prove the file we're about to write decodes back to the tools we intended.
        // Behavior-preserving normalization (elided defaults, Maestro scalar re-coercion) round-trips
        // equal and is silent here; only a value that decodes to something different is reported.
        roundTripMismatches = TrailRoundTripFidelityVerifier.verify(trailblazeYaml, migratedTrail),
      ),
    )
  }

  /**
   * Best-effort wrapper around [TrailRoundTripDropDetector.detect]: a detector failure must never
   * break a migration, so any throw degrades to "no drops found" for this file.
   */
  private fun detectDroppedContent(fileName: String, yamlText: String): List<DroppedContentEntry> =
    runCatching { TrailRoundTripDropDetector.detect(strictTrailblazeYaml, fileName, yamlText) }
      .getOrDefault(emptyList())

  /**
   * Detect cross-file scalar-config drift: two files meaningfully declaring DIFFERENT values for
   * the same device-agnostic scalar. The fold resolves these first-file-wins, which is silent —
   * surface the disagreement like NL/memory/kind drift so a divergent title or priority isn't
   * invisibly dropped during a bulk migration. "Meaningfully declared" matches the fold's absence
   * rules (blank strings don't count). `metadata` is compared per-KEY (reported as
   * `metadata.<key>`, e.g. `metadata.source` for the bridged v1 field) to match its per-key
   * merge — files contributing disjoint keys is a clean union, not drift. `memory` has its own
   * dedicated drift pass; per-classifier `devices`/`skip` and the unioned `tags` diverge by
   * design and are not scalars.
   */
  private fun detectConfigDrift(
    configsBySource: Map<String, UnifiedTrailConfig>,
  ): List<ConfigDriftEntry> {
    val extractors: List<Pair<String, (UnifiedTrailConfig) -> String?>> = listOf(
      "id" to { it.id?.takeUnless(String::isBlank) },
      "target" to { it.target?.takeUnless(String::isBlank) },
      "title" to { it.title?.takeUnless(String::isBlank) },
      "description" to { it.description?.takeUnless(String::isBlank) },
      "priority" to { it.priority?.takeUnless(String::isBlank) },
      "context" to { it.context?.takeUnless(String::isBlank) },
    )
    val fieldDrift = extractors.mapNotNull { (field, extract) ->
      val valueBySource = configsBySource.mapNotNull { (source, cfg) ->
        extract(cfg)?.let { source to it }
      }.toMap()
      if (valueBySource.values.toSet().size > 1) ConfigDriftEntry(field, valueBySource) else null
    }
    val metadataKeys = configsBySource.values.flatMap { it.metadata?.keys ?: emptySet() }.toSet()
    val metadataDrift = metadataKeys.sorted().mapNotNull { key ->
      val valueBySource = configsBySource.mapNotNull { (source, cfg) ->
        cfg.metadata?.get(key)?.let { source to it }
      }.toMap()
      if (valueBySource.values.toSet().size > 1) ConfigDriftEntry("metadata.$key", valueBySource) else null
    }
    return fieldDrift + metadataDrift
  }

  /**
   * Fail fast when a v1 input contains a top-level `- tools:` block (a
   * `ToolTrailItem`). Those blocks carry setup tool calls that the migrator
   * doesn't currently know how to translate into the unified format's
   * per-step / per-classifier shape — silently dropping them would change
   * the trail's behavior at runtime (e.g. login state never gets set up).
   *
   * Translating top-level tools into a unified step is a follow-up; for now
   * we refuse migration so the operator notices and either inlines the
   * setup tools into the first step manually or waits for the translation
   * pass to land.
   */
  private fun assertNoTopLevelTools(items: List<TrailYamlItem>, filename: String) {
    val toolItems = items.filterIsInstance<TrailYamlItem.ToolTrailItem>()
    require(toolItems.isEmpty()) {
      "Refusing to migrate $filename: it contains a top-level `- tools:` block " +
        "(${toolItems.sumOf { it.tools.size }} tool call(s)) which the migrator does " +
        "not yet know how to lower into the unified-format per-step / per-classifier " +
        "shape. Silently dropping those tools would change runtime behavior. " +
        "Either inline them into the first step's recording manually or skip this " +
        "directory until the migrator learns to translate top-level tools."
    }
  }

  /**
   * Fail fast when a v1 input contains a `- trailhead:` block. Mapping the per-classifier trailhead
   * into [UnifiedTrail.trailhead] (with NL-drift + family-collapse reconciliation, mirroring the
   * per-step pass) is a follow-up; until then, refuse migration rather than silently drop the
   * deterministic step 0 — the same policy [assertNoTopLevelTools] applies to top-level tools.
   */
  private fun assertNoTrailhead(items: List<TrailYamlItem>, filename: String) {
    require(items.none { it is TrailYamlItem.TrailheadTrailItem }) {
      "Refusing to migrate $filename: it contains a `- trailhead:` block, which this migrator does " +
        "not lower into the unified format's per-classifier `trailhead:`. Silently dropping it " +
        "would lose the trail's deterministic step 0. Author the unified `trailhead:` directly, or " +
        "skip this directory."
    }
  }

  // Identity fields come from the shared [UnifiedTrailAdapter.v1ConfigToUnifiedConfig] mapping (one
  // source of truth with the recorder's first-write seed). `devices:` / `skip:` (per-classifier
  // maps) and `tags:` (a trail-level union) are populated by the caller from every file — this
  // single first-file config can't express them — so the helper leaves them null.
  private fun v1ConfigToUnified(v1: xyz.block.trailblaze.yaml.TrailConfig): UnifiedTrailConfig =
    UnifiedTrailAdapter.v1ConfigToUnifiedConfig(v1)

  // Fold [next] into the canonical config being accumulated: the first file to meaningfully
  // declare a scalar wins, and files seen later only FILL fields the canonical still lacks
  // (blank strings / empty placeholders don't shadow a later populated value; metadata — which
  // carries the bridged v1 `source:` — merges per-key). Without the fill, a field
  // present only in a later file — e.g. `source:` declared in `blaze.yaml` but not in the
  // platform files — was silently dropped because the first config seen became canonical
  // wholesale. The field list lives in the shared [UnifiedTrailAdapter.fillMissingConfigScalars];
  // `devices` / `skip` / `tags` are per-classifier / union-merged by the caller and stay
  // untouched (null) here.
  private fun UnifiedTrailConfig?.foldConfig(next: UnifiedTrailConfig): UnifiedTrailConfig =
    if (this == null) next else UnifiedTrailAdapter.fillMissingConfigScalars(this, next)

  /**
   * Group [classifiers] into families based on shared `<family>-<sub>` prefix.
   * Returns one entry per family that has 2+ sub-classifiers; classifiers with
   * no `-` (or with no siblings under their prefix) are not collapsible and
   * are omitted from the result.
   */
  internal fun inferFamilies(classifiers: List<String>): Map<String, List<String>> {
    val byPrefix = linkedMapOf<String, MutableList<String>>()
    for (c in classifiers) {
      val dashIdx = c.indexOf('-')
      if (dashIdx <= 0 || dashIdx == c.length - 1) continue
      val prefix = c.substring(0, dashIdx)
      // Skip if the prefix itself is already a classifier in the input —
      // that means the family is already declared explicitly and shouldn't
      // be re-collapsed.
      if (prefix in classifiers) continue
      byPrefix.getOrPut(prefix) { mutableListOf() }.add(c)
    }
    return byPrefix.filterValues { it.size >= 2 }
  }

  private fun collapseFamily(
    step: UnifiedTrailStep,
    family: String,
    members: List<String>,
  ): Pair<UnifiedTrailStep, CollapseAction> {
    val present = members.filter { it in step.recordings }
    if (present.size < 2) return step to CollapseAction.NOT_APPLICABLE
    // Require EVERY member of the family to have a recording for this step
    // before collapsing. If we collapsed when only a subset recorded, the
    // emitted `<family>:` entry would be picked up at runtime by closest-wins
    // resolution for the missing members too — silently giving them a
    // recording they were never tested with. In v1 those devices ran the
    // step in LLM mode (no recording); we preserve that by leaving the
    // present members un-collapsed.
    if (present.size < members.size) return step to CollapseAction.NOT_APPLICABLE
    val canonicalForm = canonicalKey(step.recordings[present[0]]!!)
    val allEqual = present.drop(1).all { canonicalKey(step.recordings[it]!!) == canonicalForm }
    if (!allEqual) return step to CollapseAction.DIVERGED

    val newRecordings = linkedMapOf<String, List<TrailblazeToolYamlWrapper>>()
    var inserted = false
    for ((classifier, tools) in step.recordings) {
      if (classifier in present) {
        if (!inserted) {
          newRecordings[family] = step.recordings[present[0]]!!
          inserted = true
        }
        // skip — collapsed into family
      } else {
        newRecordings[classifier] = tools
      }
    }
    return step.copy(recordings = newRecordings) to CollapseAction.COLLAPSED
  }

  /**
   * Produce a canonical representation of [tools] with `reason:` keys
   * removed at any depth, so two recordings that differ only in their
   * recording-time reason annotations compare equal.
   */
  private fun canonicalKey(tools: List<TrailblazeToolYamlWrapper>): JsonElement {
    val yamlText = trailblazeYaml.getInstance().encodeToString(
      ListSerializer(trailblazeYaml.toolWrapperSerializer()),
      tools,
    )
    val node = trailblazeYaml.getInstance().parseToYamlNode(yamlText)
    val asJson = YamlJsonBridge.yamlNodeToJsonElement(node)
    return stripReasonRecursively(asJson)
  }

  private fun stripReasonRecursively(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
      element.entries
        .filter { (k, _) -> k != REASON_KEY }
        .associate { (k, v) -> k to stripReasonRecursively(v) },
    )
    is JsonArray -> JsonArray(element.map { stripReasonRecursively(it) })
    else -> element
  }

  data class Result(
    val trail: UnifiedTrail,
    val report: Report,
  )

  data class Report(
    val platformFilesLoaded: List<String>,
    /**
     * True when a `blaze.yaml` in the input directory contributed to the migration (its
     * device-agnostic NL / config). Distinct from [platformFilesLoaded] because blaze.yaml is
     * recording-less and carries no device classifier — for a blaze-only case this is the only
     * source, so `platformFilesLoaded` is empty while this is true.
     */
    val blazeLoaded: Boolean = false,
    val drift: List<DriftEntry>,
    val familyCollapses: List<FamilyCollapseEntry>,
    val unrecordableSteps: Int,
    /**
     * Cross-file memory drift — non-empty when two or more per-platform v1 files declared
     * `config.memory:` blocks that differ. Always 0 or 1 entries (one entry holds every
     * platform's memory map for comparison). Empty when all files agreed or no file had
     * a memory block.
     */
    val memoryDrift: List<MemoryDriftEntry> = emptyList(),
    /**
     * Step-kind drift — one entry per step index where the files disagree on `step:` vs
     * `verify:`. The canonical kind follows the same preference as NL (blaze.yaml when
     * present, otherwise the first platform); the disagreement is surfaced here so it is
     * never silently flattened (verify semantics are load-bearing at run time).
     */
    val kindDrift: List<KindDriftEntry> = emptyList(),
    /**
     * Scalar-config drift — one entry per device-agnostic scalar (`title`, `priority`, `source`,
     * …) that two or more files meaningfully declare with different values. The fold resolves
     * these first-file-wins; the losers are surfaced here so a divergent value is never
     * invisibly dropped.
     */
    val configDrift: List<ConfigDriftEntry> = emptyList(),
    /**
     * Un-round-trippable content — one entry per input key the lenient decode silently drops (a
     * key the schema doesn't recognize, e.g. a malformed positional anchor). Detected via a strict
     * re-decode of each input file (see [TrailRoundTripDropDetector]). Empty for clean inputs.
     */
    val droppedContent: List<DroppedContentEntry> = emptyList(),
    /**
     * Round-trip fidelity mismatches — non-empty when the emitted unified file does NOT decode back
     * to the tools/config the migrator intended to write (a serializer that can't round-trip a
     * non-default value, a nested arg a concrete tool model drops, a config field lost on re-encode).
     * Produced by [TrailRoundTripFidelityVerifier] by comparing DECODED objects, so behavior-preserving
     * normalization (elided defaults, Maestro scalar re-coercion) round-trips equal and never appears
     * here. Empty for a faithful migration.
     */
    val roundTripMismatches: List<RoundTripFidelityEntry> = emptyList(),
  )

  data class ConfigDriftEntry(
    /** The config field name (e.g. `title`, `priority`, `source`). */
    val field: String,
    /** Meaningfully-declared value keyed by source (classifier or `blaze.yaml`) — at least 2 that differ. */
    val valueBySource: Map<String, String>,
  )

  data class DriftEntry(
    val stepIndex: Int,
    /** NL string keyed by classifier — at least 2 entries that differ. */
    val nlByClassifier: Map<String, String>,
  )

  data class KindDriftEntry(
    val stepIndex: Int,
    /** `true` = `verify:`, `false` = `step:`; keyed by classifier (plus `blaze.yaml` when present). */
    val verifyByClassifier: Map<String, Boolean>,
  )

  data class MemoryDriftEntry(
    /**
     * Normalized memory map keyed by classifier. Null `memory:` blocks are normalized to
     * `emptyMap()` before comparison so "absent" and "explicitly empty" don't show as drift.
     * At least 2 entries that differ when present.
     */
    val memoryByClassifier: Map<String, Map<String, String>>,
  )

  data class FamilyCollapseEntry(
    val family: String,
    val members: List<String>,
    /** True if the family's sub-classifiers diverged at some step and could not be collapsed. */
    val diverged: Boolean,
  )

  private enum class CollapseAction { COLLAPSED, DIVERGED, NOT_APPLICABLE }

  companion object {
    private const val TRAIL_YAML_SUFFIX = ".trail.yaml"
    private const val BLAZE_FILENAME = TrailRecordings.BLAZE_DOT_YAML
    private const val BLAZE_KEY = "blaze.yaml"
    private const val REASON_KEY = "reason"

    /** Build leading-comment lines summarizing drift for inclusion in the migrated file. */
    fun driftComments(drift: List<DriftEntry>): List<String> = stepDriftComments(
      entries = drift,
      warning = "WARNING: ${drift.size} step(s) had divergent NL across platforms during migration.",
      preference = "Canonical NL preference: blaze.yaml when present, otherwise the first platform. Review the diff:",
      stepIndex = { it.stepIndex },
      perClassifierLines = { entry ->
        entry.nlByClassifier.map { (classifier, nl) ->
          "    $classifier: \"${nl.take(SNIPPET_MAX_LEN).replace('\n', ' ')}\""
        }
      },
    )

    /**
     * Leading-comment lines summarizing step-kind drift (`step:` vs `verify:`). Kind drift is rarer
     * but higher-stakes than NL drift — a step that runs as `step:` on one platform and `verify:`
     * on another has different runtime semantics per device.
     */
    fun kindDriftComments(kindDrift: List<KindDriftEntry>): List<String> = stepDriftComments(
      entries = kindDrift,
      warning = "WARNING: ${kindDrift.size} step(s) had divergent step kinds (step: vs verify:) across platforms.",
      preference = "Canonical kind preference: blaze.yaml when present, otherwise the first platform. Review the diff:",
      stepIndex = { it.stepIndex },
      perClassifierLines = { entry ->
        entry.verifyByClassifier.map { (classifier, isVerify) ->
          "    $classifier: ${if (isVerify) "verify" else "step"}"
        }
      },
    )

    /**
     * Shared scaffold for the per-step drift comment blocks: warning header, canonical-preference
     * line, up to [MAX_DRIFT_DETAIL_LINES] step entries, then an overflow tail — so the NL and
     * kind variants can't drift apart in shape.
     */
    private fun <E> stepDriftComments(
      entries: List<E>,
      warning: String,
      preference: String,
      stepIndex: (E) -> Int,
      perClassifierLines: (E) -> List<String>,
    ): List<String> {
      if (entries.isEmpty()) return emptyList()
      val lines = mutableListOf(warning, preference)
      for (entry in entries.take(MAX_DRIFT_DETAIL_LINES)) {
        lines += "  step ${stepIndex(entry) + 1}:"
        lines += perClassifierLines(entry)
      }
      if (entries.size > MAX_DRIFT_DETAIL_LINES) {
        lines += "  ... and ${entries.size - MAX_DRIFT_DETAIL_LINES} more"
      }
      return lines
    }

    /**
     * Leading-comment lines summarizing cross-file `config.memory:` drift. Same shape as
     * [driftComments] but for the memory block — surfaces every per-platform memory map
     * so the user can pick the right canonical set after migration (the migrator picks
     * the first file to declare a memory block, which may not be what the user wants).
     */
    fun memoryDriftComments(memoryDrift: List<MemoryDriftEntry>): List<String> {
      if (memoryDrift.isEmpty()) return emptyList()
      val entry = memoryDrift.first()
      val lines = mutableListOf<String>()
      lines += "WARNING: per-platform v1 files declared divergent `config.memory:` blocks."
      lines += "The first file to declare a memory block was used as canonical. Review and reconcile:"
      for ((classifier, memory) in entry.memoryByClassifier) {
        val rendered = if (memory.isEmpty()) "{}" else memory.entries.joinToString(", ") {
          "${it.key}=${it.value.take(SNIPPET_MAX_LEN)}"
        }
        lines += "  $classifier: $rendered"
      }
      return lines
    }

    /**
     * Leading-comment lines summarizing scalar-config drift — one block per diverging field,
     * listing every file's declared value so the user can fix the canonical pick if the
     * first-file-wins fold chose wrong.
     */
    fun configDriftComments(configDrift: List<ConfigDriftEntry>): List<String> {
      if (configDrift.isEmpty()) return emptyList()
      val lines = mutableListOf<String>()
      lines += "WARNING: input files declared divergent config values; the first file to declare each field won."
      lines += "Review the alternatives below and edit the migrated config if the canonical pick is wrong:"
      for (entry in configDrift) {
        lines += "  ${entry.field}:"
        for ((source, value) in entry.valueBySource) {
          lines += "    $source: \"${value.take(SNIPPET_MAX_LEN).replace('\n', ' ')}\""
        }
      }
      return lines
    }

    /**
     * Leading-comment lines naming input content the migration could not carry — keys the lenient
     * decode silently drops (a malformed positional anchor, a stale/typo'd field, a tool arg the
     * tool doesn't declare). Unlike the drift warnings (which flag a resolved-but-lossy choice),
     * this flags content that is simply GONE, so a reviewer can restore or fix it. Groups by file
     * and names the dropped key, its YAML path, and its source line.
     */
    fun droppedContentComments(droppedContent: List<DroppedContentEntry>): List<String> {
      if (droppedContent.isEmpty()) return emptyList()
      val lines = mutableListOf(
        "WARNING: ${droppedContent.size} input key(s) did not round-trip through migration and were DROPPED.",
        "Each is either a key the schema doesn't recognize (e.g. a malformed positional anchor) or a",
        "sibling key in a tool entry that the tool decoder ignores — silently discarded on decode.",
        "Review each and re-author it in a valid shape:",
      )
      for ((file, entries) in droppedContent.groupBy { it.file }) {
        lines += "  $file:"
        for (entry in entries) {
          lines += "    dropped `${entry.key}` at ${entry.path} (line ${entry.line})"
        }
      }
      return lines
    }

    /**
     * Leading-comment lines naming any place the emitted unified file does not decode back to the
     * intended tools/config — a genuine, behavior-changing fidelity loss (distinct from the
     * behavior-preserving normalization the verifier tolerates). Empty for a faithful migration.
     */
    fun roundTripMismatchComments(mismatches: List<RoundTripFidelityEntry>): List<String> {
      if (mismatches.isEmpty()) return emptyList()
      val lines = mutableListOf(
        "WARNING: ${mismatches.size} location(s) did NOT round-trip — the emitted file decodes back to",
        "different tools/config than the migrator intended. This is a real fidelity loss (not cosmetic default",
        "elision). Review each and fix the tool/config serializer or re-author the affected step:",
      )
      for (entry in mismatches.take(MAX_DRIFT_DETAIL_LINES)) {
        lines += "  ${entry.location}:"
        entry.detail.split('\n').forEach { lines += "    $it" }
      }
      if (mismatches.size > MAX_DRIFT_DETAIL_LINES) {
        lines += "  ... and ${mismatches.size - MAX_DRIFT_DETAIL_LINES} more"
      }
      return lines
    }

    /**
     * A migration is lossy when the emitted unified file no longer carries everything the inputs
     * meant — either input content the decode couldn't round-trip ([Report.droppedContent]) or a
     * value that doesn't survive the migrator's serialize/re-decode reshape ([Report.roundTripMismatches]).
     * Both callers of "was this lossy?" — the CLI's `--fail-on-dropped-content` exit-code gate and the
     * Trail Runner bundle path's decision to RETAIN the v1 inputs rather than delete them — must agree,
     * so the definition lives here once.
     */
    fun isLossyMigration(report: Report): Boolean =
      report.droppedContent.isNotEmpty() || report.roundTripMismatches.isNotEmpty()

    private const val MAX_DRIFT_DETAIL_LINES = 5
    private const val SNIPPET_MAX_LEN = 100
  }
}

/**
 * Helper extension that returns the contextual tool-wrapper serializer
 * registered on the [TrailblazeYaml] instance. [TrailblazeYaml] keeps it
 * private; the migrator pulls it back out via the serializers module so it
 * can re-encode tool lists into YAML for the canonical-form comparison
 * (with `reason:` stripped) used by family collapse.
 */
private fun TrailblazeYaml.toolWrapperSerializer(): kotlinx.serialization.KSerializer<TrailblazeToolYamlWrapper> =
  getInstance().serializersModule.getContextual(TrailblazeToolYamlWrapper::class)
    ?: error("TrailblazeYaml is missing the TrailblazeToolYamlWrapper contextual serializer")
