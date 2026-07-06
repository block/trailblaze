package xyz.block.trailblaze.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.ListSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
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
 * 2. Canonicalize config from the first file: keep `id` / `target` / `context`
 *    / `memory` / `metadata` verbatim, drop `platform:` / `title:`.
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
 * 3. For each step index, gather per-classifier NL and tool recordings.
 *    Disagreeing NL becomes a drift warning surfaced in the output's leading
 *    comments; canonical NL is the first platform's.
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
    require(platformFiles.isNotEmpty()) {
      "No `*.trail.yaml` files found in $inputDir; nothing to migrate."
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
    // ignored (v1 semantics: `skip: ""` means "not skipped").
    val skipByClassifier = linkedMapOf<String, String>()
    // Trail-level tags. Unlike driver/skip these aren't device-specific, so they're unioned across
    // every file (a tag on any platform describes the whole test) rather than keyed by classifier.
    // LinkedHashSet keeps first-seen order and de-duplicates a tag shared by multiple files.
    val tagsUnion = linkedSetOf<String>()
    // blaze.yaml's own skip reason (device-agnostic — see the propagation comment below).
    var blazeSkip: String? = null
    var canonicalConfig: UnifiedTrailConfig? = null
    for (file in platformFiles) {
      val classifier = file.name.removeSuffix(TRAIL_YAML_SUFFIX)
      val items = trailblazeYaml.decodeTrail(file.readText())
      assertNoTopLevelTools(items, file.name)
      assertNoTrailhead(items, file.name)
      val v1Config = trailblazeYaml.extractTrailConfig(items)
      if (canonicalConfig == null && v1Config != null) {
        canonicalConfig = v1ConfigToUnified(v1Config)
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
    val blazeFile = File(inputDir, BLAZE_FILENAME)
    val blazePrompts: List<PromptStep> = if (blazeFile.isFile) {
      val items = trailblazeYaml.decodeTrail(blazeFile.readText())
      assertNoTopLevelTools(items, blazeFile.name)
      assertNoTrailhead(items, blazeFile.name)
      val cfg = trailblazeYaml.extractTrailConfig(items)
      if (canonicalConfig == null && cfg != null) {
        canonicalConfig = v1ConfigToUnified(cfg)
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
    val steps = mutableListOf<UnifiedTrailStep>()

    for (i in 0 until maxSteps) {
      val nlByClassifier = linkedMapOf<String, String>()
      val toolsByClassifier = linkedMapOf<String, List<TrailblazeToolYamlWrapper>>()
      for ((classifier, prompts) in perClassifier) {
        if (i < prompts.size) {
          val step = prompts[i]
          nlByClassifier[classifier] = step.prompt
          val tools = step.recording?.tools.orEmpty()
          if (tools.isNotEmpty()) {
            toolsByClassifier[classifier] = tools
          }
        }
      }
      val blazeNl: String? = if (i < blazePrompts.size) blazePrompts[i].prompt else null
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

      // recordable stays at its default (true): a step with no recording just runs via the
      // agent and can be recorded later. We deliberately do NOT emit `recordable: false` for
      // no-recording steps — that would mean "never record", which isn't the intent and is noise.
      steps.add(
        UnifiedTrailStep(
          step = canonicalNl,
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

    return Result(
      trail = UnifiedTrail(config = mergedConfig, trail = collapsedSteps),
      report = Report(
        platformFilesLoaded = platformFiles.map { it.name },
        drift = driftReports,
        memoryDrift = memoryDrift,
        familyCollapses = collapseReports,
        unrecordableSteps = collapsedSteps.withIndex().count { !it.value.recordable },
      ),
    )
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

  private fun v1ConfigToUnified(v1: xyz.block.trailblaze.yaml.TrailConfig): UnifiedTrailConfig =
    UnifiedTrailConfig(
      id = v1.id,
      target = v1.target,
      // Preserve the human description — it's runtime-surfaced (a display label), so dropping
      // it would be silent information loss.
      description = v1.description,
      // `devices:` / `skip:` (per-classifier maps) and `tags:` (a trail-level union) are all
      // populated by the caller from every file — this single first-file config can't express them.
      devices = null,
      skip = null,
      tags = null,
      context = v1.context,
      memory = v1.memory,
      metadata = v1.metadata,
    )

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
  )

  data class DriftEntry(
    val stepIndex: Int,
    /** NL string keyed by classifier — at least 2 entries that differ. */
    val nlByClassifier: Map<String, String>,
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
    private const val BLAZE_FILENAME = "blaze.yaml"
    private const val BLAZE_KEY = "blaze.yaml"
    private const val REASON_KEY = "reason"

    /** Build leading-comment lines summarizing drift for inclusion in the migrated file. */
    fun driftComments(drift: List<DriftEntry>): List<String> {
      if (drift.isEmpty()) return emptyList()
      val lines = mutableListOf<String>()
      lines += "WARNING: ${drift.size} step(s) had divergent NL across platforms during migration."
      lines += "Canonical NL preference: blaze.yaml when present, otherwise the first platform. Review the diff:"
      for (entry in drift.take(MAX_DRIFT_DETAIL_LINES)) {
        lines += "  step ${entry.stepIndex + 1}:"
        for ((classifier, nl) in entry.nlByClassifier) {
          val snippet = nl.take(SNIPPET_MAX_LEN).replace('\n', ' ')
          lines += "    $classifier: \"$snippet\""
        }
      }
      if (drift.size > MAX_DRIFT_DETAIL_LINES) {
        lines += "  ... and ${drift.size - MAX_DRIFT_DETAIL_LINES} more"
      }
      return lines
    }

    /**
     * Leading-comment lines summarizing cross-file `config.memory:` drift. Same shape as
     * [driftComments] but for the memory block — surfaces every per-platform memory map
     * so the user can pick the right canonical set after migration (the migrator picks
     * first-file-wins, which may not be what the user wants for memory).
     */
    fun memoryDriftComments(memoryDrift: List<MemoryDriftEntry>): List<String> {
      if (memoryDrift.isEmpty()) return emptyList()
      val entry = memoryDrift.first()
      val lines = mutableListOf<String>()
      lines += "WARNING: per-platform v1 files declared divergent `config.memory:` blocks."
      lines += "The first file's memory was used as canonical. Review and reconcile:"
      for ((classifier, memory) in entry.memoryByClassifier) {
        val rendered = if (memory.isEmpty()) "{}" else memory.entries.joinToString(", ") {
          "${it.key}=${it.value.take(SNIPPET_MAX_LEN)}"
        }
        lines += "  $classifier: $rendered"
      }
      return lines
    }

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
