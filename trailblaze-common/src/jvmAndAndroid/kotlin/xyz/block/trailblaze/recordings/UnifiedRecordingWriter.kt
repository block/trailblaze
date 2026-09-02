package xyz.block.trailblaze.recordings

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import java.io.File

/**
 * Shared save-back routing + unified read-merge-write for every recording surface: the CLI
 * (`trailblaze run`), MCP trail authoring, and the desktop recording tab. Each surface used to
 * write its own `<classifier>.trail.yaml` unconditionally; this is the single place that decides
 * whether a save merges into the shared unified `trail.yaml` (under one device's classifier slot)
 * or lands in a per-classifier sibling, and the single place that reads-merges-writes the unified
 * file (atomic write + corrupt-file refusal). A future cross-process file lock lands here once,
 * not three times.
 *
 * Every destination holds **unified** YAML — [shouldMergeIntoSharedTrail] chooses a file layout, not a
 * format. The pure merge itself lives in [UnifiedTrailAdapter.mergeRecordedClassifier]
 * ([xyz.block.trailblaze.yaml.unified]); this object adds the JVM file I/O around it.
 *
 * The per-classifier sibling write stays with each surface (its filename and no-classifier
 * handling differ), so this object never writes one — it only tells the caller whether to, and
 * refuses to let one shadow a shared unified trail.
 */
object UnifiedRecordingWriter {

  // ─────────────────────────────────────────────────────────────────────────────
  // Shared user-facing messages — kept here so the MCP and desktop surfaces (which map the same
  // outcomes to their own SaveResult/Result wrappers) can't drift their wording. The CLI keeps its
  // own richer console phrasing.
  // ─────────────────────────────────────────────────────────────────────────────

  /** Refusal message for a per-classifier sibling write that would shadow a unified trail. */
  fun siblingShadowRefusalMessage(siblingFileName: String, dir: File): String =
    "Refusing to write $siblingFileName next to a unified trail.yaml in ${dir.absolutePath} — it " +
      "would shadow the unified trail at run time. Record against the unified trail so this " +
      "device's tools merge into its own classifier slot."

  /** Refusal message for a synthesized cast that a per-classifier sibling would shadow at run time. */
  fun synthesizedCastShadowedMessage(target: File, siblingFileNames: List<String>): String =
    "Multi-device cast not saved to ${target.absolutePath}: that directory already holds " +
      "${siblingFileNames.joinToString(", ")}, and a directory replay resolves per-classifier trail " +
      "files before trail.yaml — the cast would be written and then never run. Fold the existing " +
      "file(s) into a shared trail.yaml first, or save this session under a new trail name."

  /** Refusal message for merging into an unreadable existing unified trail (left untouched). */
  fun corruptRefusalMessage(target: File, reason: String): String =
    "Existing unified trail is unreadable and was left untouched (${target.absolutePath}): $reason. " +
      "Fix or delete that file, then retry."

  /** Message when a merge produced no steps to write. */
  const val EMPTY_MERGE_MESSAGE: String = "Recording has no steps to merge into the unified trail."

  /**
   * Refusal message for a save with no device classifier. A unified recording is keyed by
   * classifier; without one there is no slot to write it to and nothing could ever replay it.
   */
  const val BLANK_CLASSIFIER_MESSAGE: String =
    "Can't save this recording: no device classifier for this session, so there is no unified " +
      "recording slot to write it to. Re-record with a connected device."

  /** Refusal message for merging into a trail that declares a multi-device configuration. */
  fun multiDeviceMergeSkippedMessage(target: File, configurationNames: Set<String>): String =
    "Recording not merged into ${target.absolutePath}: that trail declares multi-device " +
      "configuration(s) ${configurationNames.joinToString(", ") { "`$it`" }}, whose recording legs " +
      "are keyed by configuration name rather than device classifier. Merging a single-device " +
      "classifier leg would duplicate the configuration's steps. The trail on disk is unchanged; " +
      "edit it directly to update a multi-device recording."

  /**
   * Refusal message for a configuration session saving into a trail that doesn't declare its cast.
   *
   * A configuration's legs are keyed by the configuration NAME, and replay only resolves a name the
   * target's own `config.devices` declares. Nothing in a recording can supply that cast — a
   * recording lowers to v1 items, and v1 `TrailConfig` has no `devices:` — so seeding a fresh
   * document from one produces a file whose legs no run can reach, reported as a success.
   */
  fun configurationNotDeclaredMessage(
    target: File,
    configurationName: String,
    declaredConfigurationNames: Set<String>,
  ): String = buildString {
    append(
      "Recording not saved to ${target.absolutePath}: this session bound multi-device " +
        "configuration `$configurationName`, whose legs are keyed by that name, but ",
    )
    append(
      if (declaredConfigurationNames.isEmpty()) {
        "that trail declares no multi-device configuration. A recording can't supply the cast " +
          "(`config.devices`) itself, so saving would write legs no replay can resolve."
      } else {
        "that trail declares ${declaredConfigurationNames.joinToString(", ") { "`$it`" }} instead."
      },
    )
    append(
      " Save back to the trail that declares `$configurationName`, or declare the cast in the " +
        "destination first.",
    )
  }

  /** Refusal message for a partial recording whose window names steps the trail no longer has. */
  fun stepWindowOutOfRangeMessage(target: File, window: IntRange, existingStepCount: Int): String =
    "Can't save this partial recording: it covered steps ${window.first + 1}-${window.last + 1}, " +
      "but ${target.name} now has $existingStepCount step(s). The trail changed while the run was " +
      "in flight. Re-run the range against the current trail."

  /**
   * Refusal message for a partial recording that produced a different number of steps than its
   * window covers.
   *
   * Recorded steps carry no provenance, so alignment is positional. A run that added or dropped a
   * step (self-healing, or an objective that split) leaves no way to tell which recorded step
   * belongs to which authored step, and guessing shifts every later step's recording by one. Refuse
   * instead: the trail on disk stays correct and the user re-records deliberately.
   */
  fun stepWindowMismatchMessage(
    target: File,
    window: IntRange,
    expectedStepCount: Int,
    recordedStepCount: Int,
  ): String =
    "Can't save this partial recording into ${target.name}: it covered steps " +
      "${window.first + 1}-${window.last + 1} ($expectedStepCount step(s)) but recorded " +
      "$recordedStepCount. Aligning them would shift every later step's recording, so the trail " +
      "was left unchanged. Re-record the whole trail to pick up the changed step count."

  /**
   * Refusal message for a recording whose trail was rewritten under it while it ran. [changed] is a
   * clause naming what moved ("the steps it covered were edited"), so the reader learns which edit
   * invalidated the recording rather than just that one did.
   *
   * Same root cause as [stepWindowMismatchMessage] - recorded steps carry no provenance, so
   * alignment is positional - but no count moves, so nothing else can catch it. The recording still
   * describes the trail as it was when the run started, and the file now says something else.
   */
  fun trailChangedUnderRunMessage(target: File, changed: String): String =
    "Can't save this recording into ${target.name}: $changed while the run was in flight, so its " +
      "tool calls no longer describe what the trail asks for. The trail was left unchanged. " +
      "Re-record against the trail as it is now."

  /**
   * True when a unified `trail.yaml` is present for [trailFileOrDir] — the gate-OFF refusal guard.
   * A save surface must never drop a legacy `<classifier>.trail.yaml` sibling into (or overwrite a
   * `trail.yaml` in) a migrated directory, because the legacy write can't update the unified file
   * and would only shadow it. "Present" means either the executed file IS itself unified (a named
   * file whose CONTENT is the unified format), or the directory holds a `trail.yaml`.
   */
  fun unifiedTrailPresent(trailFileOrDir: File): Boolean {
    if (executedFileIsUnified(trailFileOrDir)) return true
    val dir = dirOf(trailFileOrDir) ?: return false
    return File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).isFile
  }

  /**
   * True when [file] is itself a unified trail: the bare `trail.yaml` by name, or any other file
   * whose content parses as a unified document. `{{var}}` templates are resolved before parsing
   * (mirroring the run path), so a unified file whose unquoted template is invalid as raw YAML —
   * but which the runner resolves and executes — is still detected. Guard-safe: a v1 file, an
   * unreadable file, or a directory returns false.
   */
  fun executedFileIsUnified(file: File): Boolean {
    if (!file.isFile) return false
    if (TrailRecordings.isUnifiedTrailFile(file.name)) return true
    return runCatching {
      val resolved = TrailYamlTemplateResolver.resolve(file.readText(), file)
      createTrailblazeYaml().decodeTrailDocument(resolved)
    }.getOrNull() is TrailDocument.Unified
  }

  /**
   * The unified file a UNIFIED save reads and writes for [trailFileOrDir]: the executed file itself
   * when it is a unified document (bare `trail.yaml` by name, or a named unified file by content —
   * returned as-is even for a parentless path), otherwise the directory's shared `trail.yaml`. Null
   * only when the executed file is not itself unified and no directory resolves (orphan path with no
   * parent). Single source of truth for the writer and the re-run skip guard so they never disagree.
   */
  fun unifiedRecordingTarget(trailFileOrDir: File): File? {
    if (executedFileIsUnified(trailFileOrDir)) return trailFileOrDir
    val dir = dirOf(trailFileOrDir) ?: return null
    return File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
  }

  /**
   * Whether a save-back for [trailFileOrDir] under [classifier] merges into the shared unified
   * `trail.yaml` (`true`) or writes a per-classifier `<classifier>.trail.yaml` sibling (`false`).
   *
   * This is purely a **file-layout** decision — both destinations hold unified YAML. It exists
   * because a directory can legitimately keep one file per device instead of one shared trail:
   *
   *  - A blank [classifier] (no key for a unified slot) → sibling.
   *  - The executed file IS a unified trail (bare or named-by-content) → merge into it.
   *  - The directory already has a bare `trail.yaml` → merge into it (never drop a sibling beside it).
   *  - The directory holds per-classifier sibling(s) and no shared `trail.yaml` → sibling, so a
   *    re-record updates the device's own file instead of forking a second copy of the trail.
   *  - Greenfield (neither present) → shared `trail.yaml`.
   *
   * The unified file this decision reads/writes is [unifiedRecordingTarget] — consult the two
   * together so the router and writer never disagree on the target.
   */
  fun shouldMergeIntoSharedTrail(trailFileOrDir: File, classifier: String): Boolean {
    if (classifier.isBlank()) return false
    if (executedFileIsUnified(trailFileOrDir)) return true
    val dir = dirOf(trailFileOrDir) ?: return false
    if (File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).isFile) return true
    return perClassifierSiblingFileNames(trailFileOrDir).isEmpty()
  }

  /**
   * Names of the per-classifier `<classifier>.trail.yaml` siblings in [trailFileOrDir]'s directory,
   * sorted; empty when the directory holds none.
   *
   * Two callers that must agree. [shouldMergeIntoSharedTrail] routes a re-record to the device's own
   * sibling rather than forking a second layout beside it. [mergeIntoUnified] refuses to synthesize a
   * cast into a `trail.yaml` one of these would shadow: directory replay resolves per-classifier
   * names BEFORE `trail.yaml` (see [TrailRecordings.computePossibleFileNamesForDeviceClassifiers]),
   * so such a cast is written and then never run.
   */
  fun perClassifierSiblingFileNames(trailFileOrDir: File): List<String> {
    val dir = dirOf(trailFileOrDir) ?: return emptyList()
    return dir.listFiles { f: File ->
      f.isFile &&
        f.name.endsWith(TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX) &&
        !TrailRecordings.isUnifiedTrailFile(f.name)
    }.orEmpty().map { it.name }.sorted()
  }

  /**
   * True when the unified trail this save-back would write ([unifiedRecordingTarget]) already
   * carries a non-empty recording for [classifier]'s slot (in any step or the trailhead) — so a
   * non-self-heal re-run can skip rather than replace it. False when the file is absent
   * (greenfield), unreadable, or the slot has no recording yet.
   */
  fun unifiedClassifierAlreadyRecorded(trailFileOrDir: File, classifier: String): Boolean {
    val unifiedFile = unifiedRecordingTarget(trailFileOrDir) ?: return false
    if (!unifiedFile.isFile) return false
    val unified = runCatching { createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()) }
      .getOrNull() ?: return false
    val stepHit = unified.trail.any { it.recordings[classifier]?.isNotEmpty() == true }
    val trailheadHit = unified.trailhead?.recordings?.get(classifier)?.isNotEmpty() == true
    return stepHit || trailheadHit
  }

  /** Outcome of a [mergeIntoUnified] read-merge-write. */
  sealed interface MergeOutcome {
    /** The classifier slot was merged and the unified file written atomically to [target]. */
    data class Merged(val target: File) : MergeOutcome

    /**
     * The existing unified [target] is unreadable ([reason] is the parse error). Left untouched —
     * an unreadable trail must never be silently clobbered by a merge.
     */
    data class RefusedCorrupt(val target: File, val reason: String) : MergeOutcome

    /** The merge produced no steps (degenerate recording); nothing written (an empty `trail:` is invalid). */
    object SkippedEmpty : MergeOutcome

    /** No unified target resolved (orphan path with no parent); nothing written. */
    object NoTarget : MergeOutcome

    /**
     * The recording captured no objective at all (a raw interactive capture), but [target] already
     * holds [existingStepCount] steps. Nothing written — see [STEPLESS_INTO_EXISTING_MESSAGE].
     */
    data class SteplessIntoExistingTrail(val target: File, val existingStepCount: Int) : MergeOutcome

    /**
     * The existing [target] declares a multi-device configuration ([configurationNames]), whose
     * recording legs are keyed by the configuration NAME — and this merge's classifier is neither
     * one of those names nor a slot the trail declares for a single device, so writing would
     * duplicate the configuration's legs under a classifier key (and add a driver pin that is
     * illegal on a configuration entry). Nothing written. A configuration session's own save-back
     * passes the configuration name as its classifier and merges normally.
     */
    data class SkippedMultiDeviceTrail(val target: File, val configurationNames: Set<String>) : MergeOutcome

    /**
     * The merge is a configuration session's save-back ([configurationName]), but [target] does not
     * declare that configuration — it declares [declaredConfigurationNames] (empty when the file
     * doesn't exist yet, or declares no cast at all). Nothing written.
     *
     * The converse of [SkippedMultiDeviceTrail], and the reason it can't be folded into it: that
     * gate asks whether a CLASSIFIER-keyed merge would corrupt a configuration trail, and it never
     * fires when the target declares nothing. This one asks whether a CONFIGURATION-keyed merge has
     * a cast to key against at all. Writing anyway seeds a document with configuration-keyed legs
     * and no `config.devices` — replay resolves the name to nothing, so the file is unreachable
     * while the save reports success. A recording can't supply the missing cast: it lowers to v1
     * items and v1 `TrailConfig` has no `devices:`.
     */
    data class ConfigurationNotDeclared(
      val target: File,
      val configurationName: String,
      val declaredConfigurationNames: Set<String>,
    ) : MergeOutcome

    /**
     * A cast synthesized from a session's named-device roster was refused because [target]'s
     * directory holds per-classifier [siblingFileNames]. Nothing written.
     *
     * Distinct from every other refusal here in what it reads: those inspect the destination
     * DOCUMENT, and the file that makes this one wrong is a different file. A directory replay
     * resolves `<classifier>.trail.yaml` before `trail.yaml`
     * ([TrailRecordings.computePossibleFileNamesForDeviceClassifiers]), so writing the cast would
     * report a saved multi-device trail that every directory-addressed run then ignores in favor of
     * the sibling.
     */
    data class SynthesizedCastWouldBeShadowed(
      val target: File,
      val siblingFileNames: List<String>,
    ) : MergeOutcome

    /**
     * A partial recording was asked to replace steps [window] of [target], but [target] holds
     * [existingStepCount] steps, so the window names steps that don't exist. Nothing written -
     * reachable when the trail was edited between dispatching the partial run and its save-back.
     */
    data class StepWindowOutOfRange(
      val target: File,
      val window: IntRange,
      val existingStepCount: Int,
    ) : MergeOutcome

    /**
     * A partial recording covering steps [window] produced [recordedStepCount] steps instead of the
     * window's [expectedStepCount]. Nothing written - see [stepWindowMismatchMessage].
     */
    data class StepWindowMismatch(
      val target: File,
      val window: IntRange,
      val expectedStepCount: Int,
      val recordedStepCount: Int,
    ) : MergeOutcome

    /**
     * The trail this recording ran against is not the trail on disk: [target] was edited while the
     * run was in flight, and [changed] is the clause naming what moved. Nothing written - see
     * [trailChangedUnderRunMessage]. The count guards can't see this one, because rewriting a step
     * in place (or retargeting the trail at another app) keeps every count intact.
     */
    data class TrailChangedUnderRun(val target: File, val changed: String) : MergeOutcome
  }

  /**
   * Refusal message for merging an objective-less raw capture into a trail that already has steps.
   *
   * The merge is replace-per-classifier and aligns recorded steps to existing steps positionally. A
   * capture with no objectives is one placeholder step, so aligning it would bind the whole capture
   * to step 1's prose and strip this device's recordings from every step after it. A raw capture has
   * no step structure to align by, so there is no correct alignment — only a lossy one.
   */
  const val STEPLESS_INTO_EXISTING_MESSAGE: String =
    "Can't merge this recording: it captured tools but no objectives, and the trail it would merge " +
      "into already has steps. Saving it would attach the whole capture to step 1 and drop this " +
      "device's recordings from the rest. Use \"Generate Trail\" to turn the capture into per-step " +
      "objectives first, or record against the trail's steps."

  /**
   * Read the existing unified target for [trailFileOrDir], merge [recordedItems] (the v1 items a
   * recording generates) under [classifier], and write the result atomically. Preserves every other
   * classifier already on disk; existing NL and `recordable:false` intent win on drift (enforced by
   * [UnifiedTrailAdapter.mergeRecordedClassifier]). Fails loud rather than corrupting: an unreadable
   * target is refused untouched, and an empty merge is skipped (an empty `trail:` is unparseable).
   * A run that recorded several tools for the trailhead is not refused — the merge keeps the first
   * as the trailhead and moves the rest into the first step.
   *
   * Pass [selectedDeviceConfiguration] when [classifier] is the multi-device configuration the run
   * bound (they are the same string): a configuration's legs are keyed by its name, and the merge
   * primitive needs to know that from the caller rather than inferring it from the target file,
   * which a first write doesn't have.
   *
   * [castToDeclare] is the configuration definition (the inner `devices:` map of named devices) to
   * DECLARE under [selectedDeviceConfiguration] when the target declares no device layout of its
   * own — how an interactive roster session (devices bound by name, no trail-declared cast) saves a
   * replayable multi-device trail. A recording alone can't supply the cast (it lowers to v1 items,
   * and v1 `TrailConfig` has no `devices:`), so without this the save is refused as
   * [MergeOutcome.ConfigurationNotDeclared]. A target that ALREADY declares any device — a cast, a
   * single-device `config.devices:` entry, or a classifier-keyed `recordings:` leg — wins and still
   * refuses ([declaredDeviceKeys] says why); the synthesized cast is never written over or beside
   * an authored layout.
   *
   * Pass [stepWindow] when [recordedItems] came from a partial run ("record from step N"): the
   * 0-based inclusive range of the target's steps the run covered. The merge then replaces only that
   * window and leaves this classifier's recordings on every other step alone. Without it, a partial
   * recording aligns from step 1 and strips the classifier from everything it didn't cover.
   *
   * Pass [expectedDispatched] - the document this run actually executed - to hold the merge to it.
   * It is compared with the file under the same lock as the write, so a trail edited at any point up
   * to the merge is refused rather than handed this run's tool calls under someone else's
   * instruction. Three things are compared, and passing the whole document rather than a few fields
   * is what makes each comparison symmetric: a field that was absent at dispatch and is present now
   * is drift just as much as one that changed value. All of it applies only against a document that
   * is already on disk: a first write has nothing to have drifted from, and comparing the run's steps
   * against an absent file's empty ones would refuse every greenfield save-back as "edited".
   *
   * - The covered steps, in order. Recordings are excluded (`instruction()`), or a second device
   *   recording the same window would refuse itself over the first device's leg.
   * - The trailhead, for a whole-trail merge only. A windowed run never ran it ([sliceTrail] drops
   *   it) and the merge leaves it alone, so it is not something this recording claims anything about.
   * - `config.target`, the app the run drove. Steps can be word-for-word identical while the trail
   *   now points at a different application, and selectors captured in one app describe nothing in
   *   another.
   *
   * This classifier's driver pin is deliberately NOT an expectation: re-pinning it is the recording's
   * own documented output, so comparing it would refuse every ordinary save-back.
   *
   * No user-facing logging — the caller maps the returned [MergeOutcome] to its own output/return so
   * each surface keeps its own UX (CLI console lines, MCP/desktop result objects).
   */
  fun mergeIntoUnified(
    trailFileOrDir: File,
    recordedItems: List<TrailYamlItem>,
    classifier: String,
    selectedDeviceConfiguration: String? = null,
    castToDeclare: TrailblazeDeviceDefinition? = null,
    stepWindow: IntRange? = null,
    expectedDispatched: UnifiedTrail? = null,
  ): MergeOutcome {
    val unifiedFile = unifiedRecordingTarget(trailFileOrDir) ?: return MergeOutcome.NoTarget

    // Serialize the whole read-merge-write per target file so two concurrent IN-PROCESS writers
    // (e.g. two MCP daemon sessions saving the same trail) can't both read the pre-merge file and
    // race their writes — the second write would otherwise drop the first's classifier slot.
    // `writeFileAtomically` prevents torn files; this prevents lost updates. A cross-PROCESS lock is
    // still deferred (the CLI fan-out is sequential, so it's the one path that's already safe).
    return synchronized(lockFor(unifiedFile)) {
      val yaml = createTrailblazeYaml()

      // Read the existing file up front so a parse failure fails loud HERE, leaving both the target
      // and the caller's recording untouched, rather than after we've committed to writing.
      val existing = if (unifiedFile.isFile) {
        runCatching { yaml.decodeUnifiedTrail(unifiedFile.readText()) }.getOrElse { e ->
          return@synchronized MergeOutcome.RefusedCorrupt(unifiedFile, e.message ?: e.toString())
        }
      } else {
        null
      }

      // A multi-device configuration's legs are keyed by its configuration NAME. A classifier-keyed
      // merge of a configuration-selected replay would duplicate those legs under a second key and
      // pin a driver on a key the parser reserves for the configuration — refuse rather than
      // corrupt. Two merges pass through: one keyed by the configuration name itself (the
      // legitimate multi-device save-back), and one whose classifier resolves — through the same
      // lineage every other classifier lookup uses — to a single-device slot the trail ALSO
      // declares. The parser explicitly allows a configuration and ordinary single-device entries
      // to coexist in one document, and re-recording such a trail's single-device leg is an
      // ordinary classifier merge, not a configuration replay. See SkippedMultiDeviceTrail.
      val configurationNames = existing?.config?.multiDeviceConfigurationNames.orEmpty()

      // A configuration's legs are keyed by its NAME, and replay only resolves a name the target
      // declares. Seeding a fresh (or foreign) document with those legs writes a trail no run can
      // reach while reporting success, so refuse instead. Reachable two ways: saving a configuration
      // session to a NEW destination (the MCP save-under-a-new-name path — a recording lowers to v1
      // items and v1 `TrailConfig` carries no `devices:`, so the cast cannot come along), and saving
      // into a directory that holds only per-classifier siblings, which would fork a second layout
      // beside them and leave the siblings permanently stale.
      // A caller-supplied cast opens exactly one case: the target declares NO device layout at all
      // (a fresh file, or one whose config and legs name no device) and the caller can declare one —
      // the interactive roster save. Anything already there refuses, for two distinct reasons:
      //  - a DECLARED CAST is canon; a save must never add a second one beside or over it.
      //  - a SINGLE-DEVICE layout would be silently converted. Planting a cast beside authored
      //    single-device entries leaves the file declaring exactly one configuration, which
      //    `MultiDeviceConfigurationResolver.selectConfigurationName` then auto-selects on EVERY
      //    later replay — orphaning the classifier legs that worked before. And when the cast's name
      //    collides with such an entry, `+` REPLACES it, dropping its driver pin outright.
      // Re-saving the same roster session over its own earlier save is unaffected: that file
      // declares the configuration, so no synthesis is needed and the ordinary name-keyed merge runs.
      val declaresSynthesizedCast = castToDeclare != null && declaredDeviceKeys(existing).isEmpty()
      // The cast lands in the shared `trail.yaml`, but a directory replay resolves per-classifier
      // siblings BEFORE that file, so a cast synthesized beside one is written and then never run —
      // a save reported as a success over a trail that cannot replay as multi-device. The
      // declared-layout checks above cannot see this: they read the destination document, and the
      // shadowing file is a different one.
      if (declaresSynthesizedCast) {
        val shadowingSiblings = perClassifierSiblingFileNames(unifiedFile)
        if (shadowingSiblings.isNotEmpty()) {
          return@synchronized MergeOutcome.SynthesizedCastWouldBeShadowed(unifiedFile, shadowingSiblings)
        }
      }
      if (selectedDeviceConfiguration != null && selectedDeviceConfiguration !in configurationNames &&
        !declaresSynthesizedCast
      ) {
        return@synchronized MergeOutcome.ConfigurationNotDeclared(
          target = unifiedFile,
          configurationName = selectedDeviceConfiguration,
          declaredConfigurationNames = configurationNames,
        )
      }

      if (configurationNames.isNotEmpty() && classifier !in configurationNames) {
        // A trail declares a single-device slot two ways: a `config.devices` entry that isn't a
        // configuration, or an existing `recording:` leg keyed by the classifier. Both must stay
        // re-recordable — keying off `config.devices` alone made a leg on a trail that declares
        // ONLY its configuration permanently un-re-recordable, since the gate refused and the CLI
        // only logged the skip.
        //
        // A configuration's MEMBER classifiers are deliberately absent: a member's steps live under
        // the configuration's leg, so merging one under its own classifier duplicates that leg —
        // the corruption this gate exists to refuse.
        val singleDeviceKeys = buildSet {
          existing?.config?.devices.orEmpty().filterValues { !it.isConfiguration }.keys.let(::addAll)
          existing?.trailhead?.recordings?.keys?.let { addAll(it - configurationNames) }
          existing?.trail?.forEach { step -> addAll(step.recordings.keys - configurationNames) }
        }
        val resolvesToDeclaredSingleDevice = TrailblazeClassifierLineage
          .chainFor(TrailblazeDeviceClassifier(classifier))
          // `all` ends every chain, so honoring it here would open the gate to every classifier on
          // any trail that carries an `all:` leg — the wildcard says which leg a device READS, not
          // that the trail declares a slot for this device.
          .any { it.classifier != TrailblazeClassifierLineage.UNIVERSAL_ROOT && it.classifier in singleDeviceKeys }
        if (!resolvesToDeclaredSingleDevice) {
          return@synchronized MergeOutcome.SkippedMultiDeviceTrail(unifiedFile, configurationNames)
        }
      }

      // A capture with no objectives is one placeholder step. Aligning that against an existing
      // multi-step trail binds the whole capture to step 1 and strips this classifier from every
      // step after it — silent data loss, so refuse before writing anything.
      if (existing != null && existing.trail.isNotEmpty() &&
        UnifiedTrailAdapter.isSteplessRecording(recordedItems)
      ) {
        return@synchronized MergeOutcome.SteplessIntoExistingTrail(unifiedFile, existing.trail.size)
      }

      // Both partial-recording refusals, checked here so the caller gets a message instead of the
      // merge primitive's exception. The window is only meaningful against the file as it is NOW:
      // the run was dispatched against an earlier read of it, and a trail edited in between makes
      // every alignment below a guess.
      if (stepWindow != null) {
        val existingStepCount = existing?.trail?.size ?: 0
        if (stepWindow.first < 0 || stepWindow.last >= existingStepCount) {
          return@synchronized MergeOutcome.StepWindowOutOfRange(unifiedFile, stepWindow, existingStepCount)
        }
        val recordedCount = UnifiedTrailAdapter.recordedStepCount(recordedItems)
        if (recordedCount != stepWindow.count()) {
          return@synchronized MergeOutcome.StepWindowMismatch(
            target = unifiedFile,
            window = stepWindow,
            expectedStepCount = stepWindow.count(),
            recordedStepCount = recordedCount,
          )
        }
      }

      // The last thing checked before the write, and the only one that can see a step rewritten in
      // place: every count still matches, and the recording would land under an instruction it never
      // ran. Recordings are stripped from both sides, so a step is "the same step" when it asks for
      // the same thing - the prose, whether it's a `step:` or a `verify:`, its retry budget - and a
      // second device's leg landing on it meanwhile is not drift.
      // Only against a document that is already there: with no file on disk there is nothing this
      // run could have drifted from, and the absent file's empty step list would otherwise read as
      // "the steps it covered were edited" and refuse every first write.
      if (expectedDispatched != null && existing != null) {
        val coveredNow = existing.trail
          .filterIndexed { i, _ -> stepWindow == null || i in stepWindow }
          .map { it.instruction() }
        if (coveredNow != expectedDispatched.trail.map { it.instruction() }) {
          return@synchronized MergeOutcome.TrailChangedUnderRun(unifiedFile, "the steps it covered were edited")
        }
        if (stepWindow == null && existing.trailhead?.instruction() != expectedDispatched.trailhead?.instruction()) {
          return@synchronized MergeOutcome.TrailChangedUnderRun(unifiedFile, "its trailhead was edited")
        }
        if (existing.config.target != expectedDispatched.config.target) {
          return@synchronized MergeOutcome.TrailChangedUnderRun(unifiedFile, "the app it targets was changed")
        }
      }

      val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = existing,
        recordedItems = recordedItems,
        classifier = classifier,
        selectedDeviceConfiguration = selectedDeviceConfiguration,
        stepWindow = stepWindow,
      )
      // Declare the synthesized cast so the configuration-keyed legs written above are resolvable
      // at replay. Only for the no-declared-cast case gated above — when the target already
      // declares the configuration, its authored cast is canon and stays untouched.
      val synthesizedCast = castToDeclare
        ?.takeIf { declaresSynthesizedCast }
        ?.let { cast -> selectedDeviceConfiguration?.let { name -> name to cast } }
      val withCast = if (synthesizedCast != null) {
        merged.copy(config = merged.config.copy(devices = merged.config.devices.orEmpty() + synthesizedCast))
      } else {
        merged
      }
      // A merge with no steps would emit an empty `trail:`, which the unified parser rejects — skip
      // rather than write an unreadable file (only reachable from a degenerate recording with no
      // prompt steps and no existing trail to preserve).
      if (withCast.trail.isEmpty()) {
        MergeOutcome.SkippedEmpty
      } else {
        writeFileAtomically(unifiedFile, yaml.encodeUnifiedTrailToString(withCast))
        MergeOutcome.Merged(unifiedFile)
      }
    }
  }

  /**
   * Every key [existing] already declares a device (or cast) under — the `config.devices:` entries
   * plus every classifier its trailhead and steps hold a `recordings:` leg for.
   *
   * The union, not just `config.devices:`, because a trail declares a single-device slot both ways:
   * a legs-only trail (recorded, never hand-authored a `config.devices:` block) is just as much an
   * authored single-device layout, and planting a cast into it silently converts it. Empty means the
   * document declares no device at all — the only state a synthesized cast may be written into.
   */
  private fun declaredDeviceKeys(existing: UnifiedTrail?): Set<String> = buildSet {
    existing?.config?.devices?.keys?.let(::addAll)
    existing?.trailhead?.recordings?.keys?.let(::addAll)
    existing?.trail?.forEach { step -> addAll(step.recordings.keys) }
  }

  /**
   * Render [recordedItems] for one device as a standalone unified document — the per-classifier
   * sibling counterpart of [mergeIntoUnified], seeded empty so the recording lands entirely under
   * [classifier]'s slot.
   *
   * Applies the same invariants as the merge route (a classifier to key the slot by, a non-empty
   * trail) so the same recording is refused identically whichever file layout the directory happens
   * to use. Returns the YAML to write; the caller owns the write.
   *
   * Pass [selectedDeviceConfiguration] (equal to [classifier]) when the session bound a multi-device
   * configuration, so the leg is keyed by the configuration name without pinning the LAUNCH device's
   * driver on it. The rendered document still carries no cast — a v1 recording has no `devices:` map
   * to seed one from — so a configuration's standalone render is a preserved recording to fold into
   * the real trail, not a replayable multi-device trail on its own.
   */
  fun renderStandalone(
    recordedItems: List<TrailYamlItem>,
    classifier: String,
    selectedDeviceConfiguration: String? = null,
  ): Result<String> {
    if (classifier.isBlank()) {
      return Result.failure(IllegalStateException(BLANK_CLASSIFIER_MESSAGE))
    }
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = null,
      recordedItems = recordedItems,
      classifier = classifier,
      selectedDeviceConfiguration = selectedDeviceConfiguration,
    )
    if (merged.trail.isEmpty()) {
      return Result.failure(IllegalStateException(EMPTY_MERGE_MESSAGE))
    }
    return Result.success(createTrailblazeYaml().encodeUnifiedTrailToString(merged))
  }

  /**
   * Write [content] to [target] via a temp file in the same directory followed by an atomic rename,
   * so a partial write never leaves a truncated (unreadable) file — the single file that now holds
   * every device's slot. Creates the parent directory if needed. Falls back to a plain replace if
   * the filesystem doesn't support atomic moves.
   */
  fun writeFileAtomically(target: File, content: String) {
    // Resolve the parent from the ABSOLUTE path so the temp file lands in the same directory (and
    // thus the same filesystem) as the target even when [target] is a relative/parentless path —
    // otherwise the temp would land in the system temp dir and the cross-filesystem ATOMIC_MOVE
    // would always fall through to the non-atomic branch. A fixed ≥3-char prefix also avoids
    // File.createTempFile's minimum-prefix-length requirement for very short target names.
    val dir = target.absoluteFile.parentFile
    dir?.mkdirs()
    val tmp = File.createTempFile("unified-recording-", ".tmp", dir)
    try {
      tmp.writeText(content)
      try {
        java.nio.file.Files.move(
          tmp.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      tmp.delete() // no-op once the move succeeded; cleans up the temp on any failure
    }
  }

  /**
   * The directory a save targets: [f] itself when it's a directory, else its parent (null for an
   * orphan path). PRECONDITION: when passing a directory, it must already exist on disk — a
   * not-yet-created directory path reports `isDirectory == false` and would resolve to its PARENT
   * here, so callers create the trail directory (`mkdirs`) before routing/target resolution.
   */
  private fun dirOf(f: File): File? = if (f.isDirectory) f else f.parentFile

  /**
   * What a step asks the device to do, with every recording of it removed — the identity a recording
   * is aligned to. Two steps are the same step when this matches, so re-recording one classifier
   * never reads as the step having changed.
   */
  private fun UnifiedTrailStep.instruction(): UnifiedTrailStep = copy(recordings = emptyMap())

  // Per-target-path lock registry for [mergeIntoUnified]'s in-process read-merge-write. Keyed by
  // canonical path so two File instances pointing at the same unified trail share one monitor. The
  // map is small and bounded by the number of distinct trail files a process ever writes.
  private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

  private fun lockFor(file: File): Any =
    pathLocks.computeIfAbsent(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)) { Any() }
}
