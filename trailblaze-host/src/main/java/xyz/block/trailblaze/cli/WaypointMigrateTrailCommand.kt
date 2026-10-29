package xyz.block.trailblaze.cli

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SelectorDialect
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.selectorPatternRegexMatches
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import java.io.File
import java.util.concurrent.Callable

/**
 * Mechanically migrate a trail file's selectors from one [SelectorDialect] to another — the
 * deterministic half of moving a recorded trail onto a different driver.
 *
 * The pair is not fixed. Any two dialects on the same platform work, as long as the session logs
 * carry a capture of both trees: the source dialect's tree is what the recorded selectors were
 * matched against, and the target dialect's tree is what the new driver will match against.
 * `androidMaestro` → `androidAccessibility` and `iosMaestro` → `iosAxe` are the two pairs with a
 * producer today; the command reads the pair off the capture rather than assuming one.
 *
 * ## Why mechanical
 *
 * An earlier large-scale Maestro→accessibility migration was carried out trail-by-trail using
 * natural-language prompts to an LLM, which produced selectors that mostly worked but with
 * brittle wording in places — matching by `text=` when the populated field is
 * `contentDescription=`, or missing the parent `isSelected: true` anchor that pins
 * bottom-nav waypoints to the *currently active* tab. Re-running the migration mechanically
 * removes the LLM from the loop entirely:
 *
 *  1. The source-dialect selector is resolved against the captured screen to the SAME on-screen
 *     coordinate the recording runtime resolved it to. Selectors in a Maestro-derived dialect
 *     ([SelectorDialect.resolvesViaMaestroPipeline]) are lowered to a [TrailblazeElementSelector]
 *     and run through Maestro's own filter pipeline against the captured `viewHierarchy`, because
 *     that is the matcher that produced them; every other dialect resolves natively through
 *     [TrailblazeNodeSelectorResolver] against the primary node tree, which keeps per-field
 *     precision the lowering would flatten.
 *  2. That coordinate is hit-tested against the captured target-dialect tree. The result is the
 *     node a runtime tap at the same coordinate would have hit on the new driver.
 *  3. [TrailblazeNodeSelectorGenerator.findBestSelector] picks the cleanest selector for that
 *     node in the target dialect — typically an id match, falling back to text, and finally to
 *     spatial/structural anchors when nothing identifying is available.
 *
 * The output is a YAML where every selector-bearing tool's `nodeSelector` is now target-dialect
 * shape, derived from the SAME on-screen element the source selector resolved to, with no LLM
 * interpretation in the loop. The generated selector REPLACES the whole source selector tree
 * (leaf and combinators) — leaving a mixed-dialect selector behind would make it ambiguous which
 * driver owns resolution at runtime.
 *
 * ## Pairing tools to session logs
 *
 * The pairing strategy is **in-order alignment**: the Nth selector-bearing tool in the trail
 * YAML aligns with the Nth `*_TrailblazeLlmRequestLog.json` step in the session log
 * directory that has both `viewHierarchy` and `trailblazeNodeTree` populated. Each LLM
 * request log captures the screen state *before* the tool it produced fired, so the
 * pre-tool snapshot is the right one to resolve selectors against.
 *
 * This breaks down if the trail and the logs are from different runs (e.g. the trail has
 * been edited since the log was captured). The command logs a warning when the count of
 * selector-bearing tools doesn't match the count of usable logs and skips trailing tools
 * with no log partner.
 *
 * ## Inputs
 *
 *  - Positional `<trail.yaml>` — the trail file to migrate.
 *  - `--session <dir>` — directory containing dual-tree logs from a recorded run of the same
 *    trail (captured with the secondary-tree switch on).
 *  - `--from` / `--to` — optional assertions about the dialect pair; the command infers the pair
 *    from the capture and fails if an override disagrees with it.
 *  - `--write` — overwrite the trail file in place. Default is dry-run: print the proposed
 *    migration as a unified diff for review.
 *
 * ## Output
 *
 * Default: per-tool migration status to stdout, then a unified diff between the original
 * YAML and the migrated YAML. With `--write`: the trail file is updated in place; the diff
 * section is suppressed.
 */
@Command(
  name = "migrate-trail",
  mixinStandardHelpOptions = true,
  description = [
    "Mechanically migrate a trail's selectors from one driver's dialect to another's.",
    "Every `tapOnElementBySelector` / `assertVisibleBySelector` whose `nodeSelector` is still",
    "written in the source dialect is rewritten into the target dialect, using a recorded",
    "session that captured BOTH drivers' trees to resolve each selector the same way the",
    "runtime resolved it, then describe the element it landed on in the new dialect.",
    "The pair is read from the capture (e.g. androidMaestro -> androidAccessibility,",
    "iosMaestro -> iosAxe); `--from` / `--to` assert it rather than choose it.",
    "Defaults to dry-run (unified diff on stdout). Use `--write` to apply the migration in",
    "place. Pair with a recorded session log directory (`--session`) for the same trail.",
  ],
)
class WaypointMigrateTrailCommand : Callable<Int> {

  @Parameters(
    arity = "1",
    description = ["Path to the trail YAML file to migrate."],
  )
  lateinit var trailFile: File

  @Option(
    names = ["--session"],
    description = [
      "Session log directory from a dual-tree recorded run of this trail",
      "(trailblaze.captureSecondaryTree=true). Accepts *_TrailblazeLlmRequestLog.json,",
      "*_TrailblazeSnapshotLog.json, and *_AgentDriverLog.json files; only logs carrying",
      "viewHierarchy + trailblazeNodeTree + driverMigrationTreeNode are usable.",
    ],
    required = true,
  )
  lateinit var sessionDir: File

  @Option(
    names = ["--write"],
    description = [
      "Overwrite the trail file in place with the migrated YAML. Default is dry-run:",
      "print a unified diff for review without changing the file.",
    ],
  )
  var write: Boolean = false

  @Option(
    names = ["--from"],
    description = [
      "Assert the SOURCE selector dialect (e.g. `androidMaestro`, `iosMaestro`). The pair is",
      "always inferred from the session capture; this fails the run when the capture says",
      "something else, so a batch migration can't quietly rewrite the wrong dialect.",
    ],
  )
  var fromDialectKey: String? = null

  @Option(
    names = ["--to"],
    description = [
      "Assert the TARGET selector dialect (e.g. `androidAccessibility`, `iosAxe`). Same",
      "contract as `--from`: an assertion about the capture, not a choice.",
    ],
  )
  var toDialectKey: String? = null

  @Option(
    names = ["--classifier"],
    description = [
      "For a unified-format trail file only: which classifier's recordings to",
      "migrate (e.g. `android-phone`, `ios-tablet`). A session is captured",
      "against one device at a time, so migrate-trail always operates on one",
      "classifier per invocation — unified format just changes where that",
      "classifier's tool list lives (nested under this trail's `recording:`",
      "map instead of its own file). If omitted, inferred from the session",
      "logs' device classifier when exactly one available classifier matches;",
      "otherwise this is required. Ignored for legacy-format trail files.",
    ],
  )
  var classifier: String? = null

  override fun call(): Int {
    if (!trailFile.exists() || !trailFile.isFile) {
      Console.error("Trail file not found: ${trailFile.absolutePath}")
      return TrailblazeExitCode.MISUSE.code
    }
    if (!sessionDir.isDirectory) {
      Console.error("Session log directory not found: ${sessionDir.absolutePath}")
      return TrailblazeExitCode.MISUSE.code
    }

    val originalYaml = trailFile.readText()
    val trailblazeYaml = createTrailblazeYaml()
    val doc = try {
      trailblazeYaml.decodeTrailDocument(originalYaml)
    } catch (e: Exception) {
      reportCliError(
        verb = "Trail decode",
        target = trailFile.name,
        reason = describeThrowableForUser(e),
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    // Unified-format trails nest per-classifier tool lists under `recording:`; a session
    // is captured against exactly one device at a time, so migrate-trail always resolves
    // against exactly ONE classifier per invocation.
    val resolvedClassifier: String = when (doc) {
      is TrailDocument.Unified -> {
        val available = availableClassifiers(doc.trail)
        if (available.isEmpty()) {
          // No `recording:` entries anywhere in this unified trail — nothing to migrate,
          // which is a normal outcome (e.g. a trailhead-only or all-prompt trail), not a
          // misuse error.
          Console.log("${trailFile.name}: no recordings in this unified trail — nothing to migrate.")
          return TrailblazeExitCode.SUCCESS.code
        }
        val cls = classifier ?: inferClassifier(available, sessionDir)
        if (cls == null || cls !in available) {
          reportCliError(
            verb = "Trail migrate",
            target = trailFile.name,
            reason = if (classifier != null) {
              "--classifier '$classifier' has no recordings in this unified trail"
            } else {
              "couldn't auto-infer which classifier to migrate from the session logs"
            },
            hint = "pass --classifier explicitly; available classifiers in this trail: " +
              available.sorted().joinToString(),
          )
          return TrailblazeExitCode.MISUSE.code
        }
        cls
      }
    }

    // The session's own capture decides which pair this is. Do it before anything reads a
    // selector, because "is this tool still unmigrated" is a question about the source dialect.
    val logs = listSnapshotLogs(sessionDir)
    if (logs.isEmpty()) {
      reportCliError(
        verb = "Trail migrate",
        target = sessionDir.absolutePath,
        reason = "no usable session logs in the directory — need *_TrailblazeLlmRequestLog.json, *_TrailblazeSnapshotLog.json, or *_AgentDriverLog.json files carrying `viewHierarchy`, `trailblazeNodeTree`, AND the `driverMigrationTreeNode` migration side-channel",
        hint = "rerun the trail with `-e trailblaze.captureSecondaryTree true` (TRAILBLAZE_CAPTURE_SECONDARY_TREE=true on the host) so migration-mode logs are captured",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    val pair = resolveMigrationPair(logs) ?: return TrailblazeExitCode.MISUSE.code
    Console.log("# Migrating $pair selectors (pair inferred from the capture in ${sessionDir.name})")

    // Not-visible asserts can't go through the two-tree resolution — the asserted element
    // is absent from the captured screen, so there is no coordinate to hit-test. They're
    // surfaced loudly instead of silently left behind: a source-dialect selector on the target
    // driver never matches anything, which would make assertNotVisible pass VACUOUSLY (a silent
    // false green) after a driver-marker flip.
    val unmigratableNotVisible = when (doc) {
      is TrailDocument.Unified -> countUnmigratableNotVisibleUnified(doc.trail, resolvedClassifier, pair)
    }
    if (unmigratableNotVisible > 0) {
      Console.log(
        "# WARNING: $unmigratableNotVisible assertNotVisibleBySelector tool(s) still carry " +
          "${pair.source.yamlKey} selectors. The two-tree resolution can't migrate not-visible " +
          "asserts (the target element is absent from the capture) — hand-author their " +
          "${pair.target.yamlKey} selectors before flipping the driver marker, or the asserts " +
          "will pass vacuously on the ${pair.target.yamlKey} driver.",
      )
    }

    // Pass 1 — collect the source-dialect selectors in YAML order. The list index doubles
    // as the alignment key against the session-log list in pass 2 / 3.
    val sourceSelectors = when (doc) {
      is TrailDocument.Unified -> collectSourceSelectorsUnified(doc.trail, resolvedClassifier, pair)
    }
    if (sourceSelectors.isEmpty()) {
      Console.log(
        "# No ${pair.source.yamlKey} selector-bearing tools found in ${trailFile.name}; nothing to migrate.",
      )
      return TrailblazeExitCode.SUCCESS.code
    }

    // Pass 2 — drive the deterministic two-tree resolution per (selector, log) pair. The
    // result is an indexed map: index → migrated nodeSelector. Indexes with no migration
    // (skipped, no log, hit-test miss) simply omit an entry, and pass 3 leaves the
    // corresponding tool unchanged.
    Console.log(
      "# Scanning ${logs.size} logs for ${sourceSelectors.size} selector-bearing tools",
    )

    // Pairing strategy:
    //
    // Two-tier approach. When pre-tool snapshots are available (recordings captured with
    // `-e trailblaze.captureSecondaryTree true`), each snapshot's `displayName` carries
    // the precise tool class it preceded — `preTool: TapOnByElementSelector`. Match YAML
    // selector-bearing tools against snapshots of the matching class name in YAML order.
    // This gives 1:1 alignment, distinct snapshots per tool, and is correct even when two
    // selectors have identical text on different screens (the canonical "two `^Next$` taps"
    // case the LlmRequestLog-only path got wrong).
    //
    // When NO matching snapshot is found for a tool — typical for pre-migration captures
    // that didn't have the dual-tree flag set — fall back to a forward-cursor scan over
    // remaining logs (snapshots, LlmRequestLogs, mixed). The cursor allows same-log re-use
    // because LLM rounds only fire once per prompt step but may produce multiple
    // same-screen tools.
    // First-tier exclusivity is enforced via [usedLogs] — a preTool snapshot is claimed by
    // exactly one selector tool. Second-tier (fallback) cursor is intentionally PERMISSIVE:
    // [fallbackCursor] advances to the matched index (NOT idx+1) so multiple consecutive
    // tools that fired on the same screen can share the single LlmRequestLog that captured
    // their pre-state. The cost is that two distinct selectors with identical text on
    // different screens (rare in practice) may bind to the same fallback log; the operator
    // sees this as an obviously-wrong nodeSelector in the diff and can re-run that trail
    // with the dual-tree flag to get distinct preTool snapshots per tool.
    // displayName is read once per file up front — the per-selector loop below probes it
    // twice per (phase, selector) pair, which on a large session would re-read and
    // regex-scan the same JSON files hundreds of times.
    val displayNames: Map<File, String?> = logs.associateWith { readDisplayName(it) }
    val migrations: Map<Int, TrailblazeNodeSelector> = buildMap {
      val usedLogs = mutableSetOf<File>()
      var fallbackCursor = 0
      val total = sourceSelectors.size
      sourceSelectors.forEachIndexed { idx, sel ->
        Console.log("# [${idx + 1}/$total] Resolving ${sel.toolName}…")
        // First-tier: prefer a per-tool snapshot whose displayName encodes this tool's class.
        // For asserts we look for the postTool snapshot first (captured AFTER the assert
        // succeeded, so the asserted element is reliably on screen). For taps we look for
        // the preTool snapshot (captured BEFORE the tap, where the target is still visible
        // — post-tap is the next screen). Walk in document order and pick the first
        // not-yet-claimed match.
        val toolClassName = classNameFromYamlToolName(sel.toolName)
        val isAssertion = toolClassName == "AssertVisibleBySelectorTrailblazeTool"
        val phasePreference: List<String> = if (isAssertion) {
          listOf("postTool", "preTool") // post-tool first; pre-tool as fallback
        } else {
          listOf("preTool")
        }
        val matchedPhase: String? = phasePreference.firstOrNull { phase ->
          logs.any { f ->
            f !in usedLogs && displayNames[f]?.startsWith("$phase: $toolClassName") == true
          }
        }
        val matchingSnapshot = matchedPhase?.let { phase ->
          logs.firstOrNull { f ->
            f !in usedLogs && displayNames[f]?.startsWith("$phase: $toolClassName") == true
          }
        }
        if (matchingSnapshot != null) {
          val resolved = tryResolveInLogDetailed(
            sel.sourceSelector,
            sel.loweredSelector,
            matchingSnapshot,
            pair,
          )
          if (resolved != null) {
            val nodeSelector = resolved.selector
            Console.log(
              "# [${idx + 1}/${sel.toolName}] migrated via ${matchingSnapshot.name} " +
                "($matchedPhase match, ${resolved.sourceMatcher.label} matcher) " +
                "→ ${shortDescribeSelector(nodeSelector, pair)}",
            )
            put(idx, nodeSelector)
            usedLogs += matchingSnapshot
            return@forEachIndexed
          }
          // The matching snapshot exists but THIS selector didn't resolve — log and keep
          // scanning fallback. Deliberately not added to [usedLogs]: a sibling tool of the
          // same class but with a different selector (e.g. tool A taps "Foo", tool B taps
          // "Bar", both same class) might legitimately resolve in this snapshot. Marking
          // it used here would cause B to skip a viable candidate.
          Console.log(
            "# [${idx + 1}/${sel.toolName}] $matchedPhase snapshot found " +
              "(${matchingSnapshot.name}) but selector didn't resolve there; " +
              "scanning fallback logs",
          )
        }
        // Second-tier fallback: forward cursor scan, allowing same-log re-use for sequences
        // of same-screen tools that share an LLM-round capture.
        val hit = findFirstResolvingLog(
          source = sel,
          logs = logs,
          pair = pair,
          startIdx = fallbackCursor,
        ) ?: run {
          Console.log(
            "# [${idx + 1}/${sel.toolName}] SKIPPED: ${pair.source.yamlKey} selector did not resolve in any " +
              "remaining log (${logs.size - fallbackCursor} scanned from idx $fallbackCursor)",
          )
          return@forEachIndexed
        }
        Console.log(
          "# [${idx + 1}/${sel.toolName}] migrated via ${hit.logFile.name} " +
            "(fallback, ${hit.sourceMatcher.label} matcher) → " +
            shortDescribeSelector(hit.nodeSelector, pair),
        )
        put(idx, hit.nodeSelector)
        // LlmRequestLogs capture one LLM round that may produce several same-screen tools,
        // so the cursor stays ON the hit to allow reuse. SnapshotLog / AgentDriverLog files
        // are one-per-tool/action by construction — reuse there would let a later selector
        // (e.g. the second of two `^Next$` taps) re-bind to an earlier screen, so the
        // cursor advances past them.
        fallbackCursor = if (hit.logFile.name.endsWith("_TrailblazeLlmRequestLog.json")) {
          hit.logIdx
        } else {
          hit.logIdx + 1
        }
      }
    }

    // Pass 3 — re-walk the trail, substituting the Nth selector-bearing tool's nodeSelector
    // with migrations[N] when present. Comments and blank-line groupings in the source file
    // are dropped on round-trip — that's an acknowledged trade-off; a follow-up LLM pass
    // can re-add commentary where it carries semantic value.
    val migratedYaml = when (doc) {
      is TrailDocument.Unified -> {
        val cursor = IndexedCursor()
        val migratedTrail = migrateUnifiedTrail(doc.trail, resolvedClassifier, migrations, cursor, pair)
        trailblazeYaml.encodeUnifiedTrailToString(migratedTrail)
      }
    }

    val migratedCount = migrations.size
    val skippedCount = sourceSelectors.size - migratedCount

    Console.log("")
    Console.log("# === Summary: ${trailFile.name} ===")
    Console.log("# Selector-bearing tools: ${sourceSelectors.size}")
    Console.log("# Migrated:               $migratedCount")
    Console.log("# Skipped:                $skippedCount")
    if (unmigratableNotVisible > 0) {
      Console.log("# Unmigratable (assertNotVisibleBySelector, hand-author): $unmigratableNotVisible")
    }

    if (write) {
      if (originalYaml == migratedYaml) {
        Console.log("# No changes needed; trail file unchanged.")
      } else {
        trailFile.writeText(migratedYaml)
        Console.log("# Wrote migrated YAML to ${trailFile.absolutePath}")
      }
    } else {
      Console.log("")
      Console.log("# === Unified diff (run with --write to apply) ===")
      printUnifiedDiff(trailFile.name, originalYaml, migratedYaml)
    }
    return TrailblazeExitCode.SUCCESS.code
  }

  /** Cursor that walks the trail YAML in deterministic order — same traversal as collect. */
  internal class IndexedCursor(var index: Int = 0)

  /**
   * One selector-bearing tool to migrate, in both forms the resolution needs: the recorded
   * selector as written ([sourceSelector]) and its lowering to the Maestro-shaped
   * [TrailblazeElementSelector] ([loweredSelector]).
   *
   * Both, not one: the lowered form is what a Maestro-dialect source resolves through, while the
   * recorded form is what a native-dialect source resolves through with full per-field precision,
   * and what the text/id anchors are read off (the lowering keeps one text field per leaf).
   */
  internal data class SourceSelectorAtIndex(
    val sourceSelector: TrailblazeNodeSelector,
    val loweredSelector: TrailblazeElementSelector,
    val toolName: String,
  )

  // ----- Pass 1: enumerate selector-bearing tools in YAML order -------------------

  /**
   * True when [selector] is a migration target for [pair]: its tree carries at least one leaf in
   * the source dialect and none in the target dialect. Already-migrated selectors and selectors in
   * an unrelated dialect pass through untouched, and the collect/migrate cursor walks stay in sync
   * because both gate on this same predicate.
   */
  internal fun needsMigration(selector: TrailblazeNodeSelector?, pair: MigrationPair): Boolean =
    selector != null &&
      pair.source.hasLeaf(selector) &&
      !pair.target.hasLeaf(selector)

  /** True when [wrapper] is an unmigratable `assertNotVisibleBySelector` (see below). */
  private fun isUnmigratableNotVisible(wrapper: TrailblazeToolYamlWrapper, pair: MigrationPair): Boolean {
    val tool = wrapper.trailblazeTool
    return tool is AssertNotVisibleBySelectorTrailblazeTool && needsMigration(tool.nodeSelector, pair)
  }

  /**
   * Count `assertNotVisibleBySelector` tools still carrying source-dialect selectors. These
   * are NOT migration targets — the two-tree resolution needs the element on screen to
   * hit-test a coordinate, and a passing not-visible assert's capture has it absent by
   * definition. They must be hand-authored; the caller reports them loudly so a trail
   * isn't treated as fully migrated when source-dialect content remains.
   */
  internal fun countUnmigratableNotVisibleUnified(
    trail: UnifiedTrail,
    classifier: String,
    pair: MigrationPair,
  ): Int {
    var count = 0
    trail.trailhead?.recordings?.get(classifier)?.forEach { if (isUnmigratableNotVisible(it, pair)) count++ }
    trail.trail.forEach { step ->
      step.recordings[classifier]?.forEach { if (isUnmigratableNotVisible(it, pair)) count++ }
    }
    return count
  }

  /** Appends [wrapper] to [out] as a [SourceSelectorAtIndex] iff it's a migration target. */
  private fun collectSelectorVisit(
    wrapper: TrailblazeToolYamlWrapper,
    out: MutableList<SourceSelectorAtIndex>,
    pair: MigrationPair,
  ) {
    // Skip already-migrated tools (target-dialect nodeSelector). Cursor positioning is
    // consistent across walks because every selector-bearing tool — migrated or not — flows
    // through here only when [needsMigration] holds.
    val nodeSelector = when (val tool = wrapper.trailblazeTool) {
      is TapOnByElementSelector -> tool.nodeSelector
      is AssertVisibleBySelectorTrailblazeTool -> tool.nodeSelector
      else -> null // not a migration target
    } ?: return
    if (!needsMigration(nodeSelector, pair)) return
    out += SourceSelectorAtIndex(
      sourceSelector = nodeSelector,
      loweredSelector = nodeSelector.toTrailblazeElementSelector(),
      toolName = wrapper.name,
    )
  }

  internal fun collectSourceSelectorsUnified(
    trail: UnifiedTrail,
    classifier: String,
    pair: MigrationPair,
  ): List<SourceSelectorAtIndex> {
    val out = mutableListOf<SourceSelectorAtIndex>()
    trail.trailhead?.recordings?.get(classifier)?.forEach { collectSelectorVisit(it, out, pair) }
    trail.trail.forEach { step ->
      step.recordings[classifier]?.forEach { collectSelectorVisit(it, out, pair) }
    }
    return out
  }

  /**
   * Resolve which pair this run migrates: inferred from the first usable capture, then checked
   * against `--from` / `--to` when the operator asserted them. Returns null after reporting the
   * error, so the caller can exit.
   */
  private fun resolveMigrationPair(logs: List<File>): MigrationPair? {
    val asserted = mutableListOf<Pair<String, SelectorDialect>>()
    for ((flag, key) in listOf("--from" to fromDialectKey, "--to" to toDialectKey)) {
      if (key == null) continue
      val dialect = SelectorDialect.fromYamlKey(key)
      if (dialect == null) {
        reportCliError(
          verb = "Trail migrate",
          target = trailFile.name,
          reason = "$flag '$key' is not a selector dialect",
          hint = "valid dialects: " + SelectorDialect.entries.joinToString { it.yamlKey },
        )
        return null
      }
      asserted += flag to dialect
    }

    val firstUsable = logs.firstNotNullOfOrNull { log ->
      val screen = try {
        SessionLogScreenState.loadStep(log)
      } catch (e: Exception) {
        null
      } ?: return@firstNotNullOfOrNull null
      val secondary = (screen as? MigrationScreenState)?.driverMigrationTreeNode ?: return@firstNotNullOfOrNull null
      val primary = screen.trailblazeNodeTree ?: return@firstNotNullOfOrNull null
      log to (primary to secondary)
    }
    if (firstUsable == null) {
      reportCliError(
        verb = "Trail migrate",
        target = sessionDir.absolutePath,
        reason = "no session log carries both a primary node tree and a migration side-channel tree, so the migration pair can't be read from the capture",
        hint = "recapture with the secondary-tree switch on (TRAILBLAZE_CAPTURE_SECONDARY_TREE=true)",
      )
      return null
    }
    val (log, trees) = firstUsable
    val inferred = try {
      MigrationPair.infer(trees.first, trees.second)
    } catch (e: IllegalArgumentException) {
      reportCliError(
        verb = "Trail migrate",
        target = log.name,
        reason = "that capture is not a migration pair: ${e.message}",
        hint = "the capture's two trees must be different dialects on the same platform",
      )
      return null
    }
    for ((flag, dialect) in asserted) {
      val actual = if (flag == "--from") inferred.source else inferred.target
      if (dialect != actual) {
        reportCliError(
          verb = "Trail migrate",
          target = trailFile.name,
          reason = "$flag says `${dialect.yamlKey}` but ${log.name} captured `${actual.yamlKey}` there (the capture is $inferred)",
          hint = "drop the override, or point --session at a capture recorded on the driver you named",
        )
        return null
      }
    }
    return inferred
  }

  /**
   * Every classifier that carries at least one `recording:` entry (trailhead or any step) in
   * [trail] — the set of classifiers there's something to migrate for.
   */
  internal fun availableClassifiers(trail: UnifiedTrail): Set<String> =
    (trail.trailhead?.recordings?.keys.orEmpty() + trail.trail.flatMap { it.recordings.keys }).toSet()

  /**
   * Auto-select the classifier to migrate when `--classifier` is omitted: read every
   * `_TrailblazeSessionStatusChangeLog.json` in the session dir and, if exactly one distinct
   * device classifier also appears in [available], use it. A session is always captured
   * against one device, so in practice this carries either zero classifiers (a device with
   * none configured — ambiguous, caller must pass `--classifier`) or exactly one.
   *
   * Deliberately NOT sourced from [listSnapshotLogs]'s per-step logs
   * (`TrailblazeLlmRequestLog` / `TrailblazeSnapshotLog` / `AgentDriverLog`) — none of those
   * types carry a device classifier field; only the session-lifecycle `SessionStatus.Started`
   * event does, via `trailblazeDeviceInfo.classifiers`.
   */
  internal fun inferClassifier(available: Set<String>, sessionDir: File): String? {
    if (available.isEmpty()) return null
    val candidates = listSessionStatusLogs(sessionDir)
      .asSequence()
      .flatMap { log ->
        try {
          val decoded = TrailblazeJson.defaultWithoutToolsInstance
            .decodeFromString<xyz.block.trailblaze.logs.client.TrailblazeLog>(log.readText())
          val started = (decoded as? xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog)
            ?.sessionStatus as? xyz.block.trailblaze.logs.model.SessionStatus.Started
          started?.trailblazeDeviceInfo?.classifiers?.asSequence() ?: emptySequence()
        } catch (e: Exception) {
          emptySequence()
        }
      }
      .map { it.classifier }
      .filter { it in available }
      .toSet()
    return candidates.singleOrNull()
  }

  /** Lists `_TrailblazeSessionStatusChangeLog.json` files in [sessionDir] — the log type that
   * carries device identity at session start. See [inferClassifier]. */
  private fun listSessionStatusLogs(sessionDir: File): List<File> =
    sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeSessionStatusChangeLog.json") }?.toList()
      ?: emptyList()

  // ----- Pass 3: re-walk and substitute tools by index ----------------------------

  private fun migrateWrapper(
    wrapper: TrailblazeToolYamlWrapper,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
    pair: MigrationPair,
  ): TrailblazeToolYamlWrapper {
    val tool = wrapper.trailblazeTool
    // When a migration succeeds for a tool, the source-dialect `nodeSelector:` is REPLACED
    // wholesale by the generated target-dialect one — leaf AND combinators. The goal
    // of migrate-trail is to leave ZERO source-dialect matchers on migrated tools: a mixed
    // selector tree is a smell (which driver owns resolution at runtime?). Tools whose
    // migration didn't resolve keep their original selector intact and pass through
    // unchanged.
    val updatedTool: xyz.block.trailblaze.toolcalls.TrailblazeTool? = when (tool) {
      is TapOnByElementSelector -> {
        if (needsMigration(tool.nodeSelector, pair)) {
          val idx = cursor.index++
          migrations[idx]?.let { tool.copy(nodeSelector = it) }
        } else {
          null
        }
      }
      is AssertVisibleBySelectorTrailblazeTool -> {
        // Skip already-migrated tools so the cursor stays in sync with
        // [collectSourceSelectorsUnified], which also skips those.
        if (needsMigration(tool.nodeSelector, pair)) {
          val idx = cursor.index++
          migrations[idx]?.let { tool.copy(nodeSelector = it) }
        } else {
          null
        }
      }
      else -> null // not a migration target — pass through unchanged
    }
    return if (updatedTool != null) {
      TrailblazeToolYamlWrapper(name = wrapper.name, trailblazeTool = updatedTool)
    } else {
      wrapper
    }
  }

  /**
   * Rebuild [recordings] with only the [classifier] entry migrated — every other classifier's
   * tool list passes through unchanged by construction, since it's never read. Absent for
   * [classifier] (no entry, or an explicit no-op `[]`) passes through unchanged too.
   */
  private fun migrateUnifiedRecordings(
    recordings: Map<String, List<TrailblazeToolYamlWrapper>>,
    classifier: String,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
    pair: MigrationPair,
  ): Map<String, List<TrailblazeToolYamlWrapper>> {
    val tools = recordings[classifier] ?: return recordings
    return recordings + (classifier to tools.map { migrateWrapper(it, migrations, cursor, pair) })
  }

  /** Re-walks [trail], substituting migrated node selectors on [classifier]'s recordings only. */
  internal fun migrateUnifiedTrail(
    trail: UnifiedTrail,
    classifier: String,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
    pair: MigrationPair,
  ): UnifiedTrail = trail.copy(
    trailhead = trail.trailhead?.let {
      it.copy(recordings = migrateUnifiedRecordings(it.recordings, classifier, migrations, cursor, pair))
    },
    trail = trail.trail.map { step ->
      step.copy(recordings = migrateUnifiedRecordings(step.recordings, classifier, migrations, cursor, pair))
    },
  )

  // ----- Pass 2: run the deterministic two-tree resolution per pair ---------------

  private data class ResolveHit(
    val logFile: File,
    val logIdx: Int,
    val nodeSelector: TrailblazeNodeSelector,
    val sourceMatcher: SourceMatcher,
  )

  /**
   * Forward-scan [logs] starting at [startIdx], returning the first log where
   * [source] resolves to a coordinate that hit-tests to a target-dialect node, plus
   * the resulting `findBestSelector` output. Returns null if no log in the suffix resolves.
   *
   * The forward-only scan is what gives "two `^Next$` taps on different screens bind to
   * distinct logs" — once a selector matches, the cursor advances past that log so the next
   * occurrence has to find a fresh one.
   */
  private fun findFirstResolvingLog(
    source: SourceSelectorAtIndex,
    logs: List<File>,
    pair: MigrationPair,
    startIdx: Int,
  ): ResolveHit? {
    val total = logs.size - startIdx
    val scanStart = System.currentTimeMillis()
    for ((iter, i) in (startIdx until logs.size).withIndex()) {
      // Progress heartbeat every 25 logs — without this, large sessions look hung. Helps
      // pinpoint where we are when [tryResolveInLog] enters a slow code path
      // (`findBestSelector` traversal on a pathological tree, deserialization spike, etc.).
      if (iter > 0 && iter % 25 == 0) {
        Console.log(
          "#   …scan progress ${iter}/$total (cursor idx $i, elapsed ${System.currentTimeMillis() - scanStart}ms)",
        )
      }
      val perLogStart = System.currentTimeMillis()
      val hit = tryResolveInLogDetailed(source.sourceSelector, source.loweredSelector, logs[i], pair)
      val perLogMs = System.currentTimeMillis() - perLogStart
      // Slow-log alert — one log taking >2s means we're either re-parsing a huge AgentDriverLog
      // or stuck inside findBestSelector. Surface it explicitly so the operator knows which
      // file is the suspect.
      if (perLogMs > 2_000) {
        Console.log("#   slow: ${logs[i].name} took ${perLogMs}ms")
      }
      if (hit != null) {
        return ResolveHit(
          logFile = logs[i],
          logIdx = i,
          nodeSelector = hit.selector,
          sourceMatcher = hit.sourceMatcher,
        )
      }
    }
    return null
  }

  /**
   * Which matcher produced the coordinate a migrated selector was built from.
   *
   * Reported per step because the three are not equally trustworthy, and a dry-run diff otherwise
   * looks identical either way. [NATIVE] matched the recorded selector field-for-field.
   * [LOWERED] ran it through Maestro's pipeline after collapsing every text-like field onto one
   * `textRegex` — the path this class's own KDoc says can land on a neighbouring element.
   * [RECORDED_COORDINATE] matched nothing and fell back to the coordinate the recording itself
   * logged, so the anchor search is the only thing keeping it honest.
   */
  internal enum class SourceMatcher(val label: String) {
    NATIVE("native"),
    LOWERED("lowered"),
    RECORDED_COORDINATE("recorded coordinate"),
  }

  /** A migrated selector plus the matcher that found the coordinate behind it. */
  internal data class ResolvedSelector(
    val selector: TrailblazeNodeSelector,
    val sourceMatcher: SourceMatcher,
  )

  /** Outcome of resolving the recorded selector against the capture's source-dialect surface. */
  private sealed interface SourceCenter {
    /** The selector named exactly one element; [point] is its center in device coordinates. */
    data class Resolved(val point: Pair<Int, Int>, val matcher: SourceMatcher) : SourceCenter

    /** The matcher ran and named zero elements (or more than one, which is equally unusable). */
    data object NoMatch : SourceCenter

    /** The matcher itself blew up — a different thing from "nothing matched". */
    data object MatcherFailed : SourceCenter
  }

  /**
   * Reproduce the coordinate the recorded selector names on this captured screen.
   *
   * Two matchers, tried in this order:
   *
   *  1. **Native**, when the capture's primary tree speaks the source dialect: the recorded
   *     selector is matched field-for-field by [TrailblazeNodeSelectorResolver], the same resolver
   *     the runtime uses on that tree. This has to come first, because lowering a selector to the
   *     Maestro shape collapses every text-like field onto one `textRegex` — an `iosMaestro`
   *     selector that names a search field by `hintTextRegex: Search` lowers to `textRegex: Search`
   *     and then matches the magnifying-glass icon beside it just as well. Migrating off that
   *     coordinate rewrites the tap onto the wrong element, and it still looks like a success.
   *  2. **Maestro's own filter pipeline**, for the Maestro-derived dialects
   *     ([SelectorDialect.resolvesViaMaestroPipeline]) — against the captured `viewHierarchy`, with
   *     the platform taken from the dialect rather than the log, since session logs don't all carry
   *     a platform field and defaulting one runs an iOS capture under Android matching semantics.
   *     This is the only path when the primary tree is already in the TARGET dialect, which is what
   *     an accessibility-driver capture of an `androidMaestro` trail looks like.
   *
   * An unknown pair takes the Maestro path: that is the legacy two-argument contract, whose caller
   * has only a lowered selector to offer.
   */
  private fun resolveSourceCenter(
    pair: MigrationPair?,
    sourceSelector: TrailblazeNodeSelector?,
    loweredSelector: TrailblazeElementSelector,
    screen: ScreenState,
    primaryTree: TrailblazeNode?,
  ): SourceCenter {
    val primarySpeaksSource = pair != null &&
      primaryTree != null &&
      SelectorDialect.ofTree(primaryTree) == pair.source
    if (primarySpeaksSource && sourceSelector != null) {
      val node = when (val result = TrailblazeNodeSelectorResolver.resolve(primaryTree!!, sourceSelector)) {
        is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
        // An ambiguous selector is not a resolution: picking one of several would invent a
        // coordinate the recording never produced.
        is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> null
        is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
      }
      val bounds = node?.bounds
      if (bounds != null) {
        return SourceCenter.Resolved(bounds.centerX to bounds.centerY, SourceMatcher.NATIVE)
      }
    }
    if (pair != null && !pair.source.resolvesViaMaestroPipeline) return SourceCenter.NoMatch

    // The Maestro matcher (`ElementMatcherUsingMaestro`) reflects into Maestro's internal Orchestra
    // filter pipeline and can throw on malformed selectors or corner cases that its parent never
    // exercises (e.g. an empty `containsChild` regex). A crash here would abort the whole batch
    // migration mid-run, so it is reported as its own outcome rather than propagated.
    val center = try {
      TapSelectorV2.findNodeCenterUsingSelector(
        root = screen.viewHierarchy,
        selector = loweredSelector,
        trailblazeDevicePlatform = pair?.source?.platform ?: screen.trailblazeDevicePlatform,
        widthPixels = screen.deviceWidth,
        heightPixels = screen.deviceHeight,
      )
    } catch (e: Exception) {
      return SourceCenter.MatcherFailed
    }
    return center?.let { SourceCenter.Resolved(it, SourceMatcher.LOWERED) } ?: SourceCenter.NoMatch
  }

  /**
   * Visible-for-testing entry point: resolves a single lowered (Maestro-shaped) selector against a
   * single captured session log and returns the migrated nodeSelector (or null on miss), inferring
   * the migration pair from the log's own two trees.
   *
   * Exposed (not `internal`) so a downstream test module can exercise it against captured-session
   * fixtures that live outside this module's resources.
   */
  fun tryResolveInLog(
    maestroSelector: TrailblazeElementSelector,
    logFile: File,
  ): TrailblazeNodeSelector? = tryResolveInLog(
    sourceSelector = null,
    loweredSelector = maestroSelector,
    logFile = logFile,
    pair = null,
  )

  /**
   * Resolve one recorded selector against one captured log and describe what it landed on in the
   * target dialect.
   *
   * [sourceSelector] is the selector as recorded and [loweredSelector] its Maestro-shaped
   * lowering; a null [pair] is read from the log's own trees. Public for the same reason as the
   * two-argument overload: a downstream test module drives it against captured sessions whose
   * fixtures live outside this module. Returns null when this log can't
   * answer — no usable trees, the selector matching nothing here, or the hit landing on a node
   * that carries none of the original selector's intent — which is the signal the caller's
   * forward-cursor scan uses to move on to the next log.
   */
  fun tryResolveInLog(
    sourceSelector: TrailblazeNodeSelector?,
    loweredSelector: TrailblazeElementSelector,
    logFile: File,
    pair: MigrationPair?,
  ): TrailblazeNodeSelector? =
    tryResolveInLogDetailed(sourceSelector, loweredSelector, logFile, pair)?.selector

  /**
   * [tryResolveInLog] plus which matcher found the coordinate — see [SourceMatcher]. Internal
   * because it exists for the per-step run log, where the three matchers have to be told apart;
   * the public overloads stay selector-only for the downstream fixture tests.
   */
  internal fun tryResolveInLogDetailed(
    sourceSelector: TrailblazeNodeSelector?,
    loweredSelector: TrailblazeElementSelector,
    logFile: File,
    pair: MigrationPair?,
  ): ResolvedSelector? {
    val tLoad = System.currentTimeMillis()
    val screen = try {
      SessionLogScreenState.loadStep(logFile)
    } catch (e: Exception) {
      return null
    }
    val loadMs = System.currentTimeMillis() - tLoad
    // The target-dialect tree is the dedicated migration side-channel from a migration-mode
    // capture (`trailblaze.captureSecondaryTree=true`). Fall back to `trailblazeNodeTree` for
    // legacy logs and for runs where the driver's own tree IS already the target shape.
    val primaryTree = screen.trailblazeNodeTree
    val secondaryTree = (screen as? MigrationScreenState)?.driverMigrationTreeNode
    val tree = secondaryTree ?: primaryTree ?: return null
    if (screen.deviceWidth <= 0 || screen.deviceHeight <= 0) return null
    val resolvedPair = pair ?: MigrationPair.inferOrNull(primaryTree, secondaryTree)

    val tResolve = System.currentTimeMillis()
    val sourceCenter = when (
      val resolved = resolveSourceCenter(
        pair = resolvedPair,
        sourceSelector = sourceSelector,
        loweredSelector = loweredSelector,
        screen = screen,
        primaryTree = primaryTree,
      )
    ) {
      // The matcher threw (malformed selector, or a Maestro reflection corner case). Skip this
      // log so the forward-cursor scan continues — deliberately WITHOUT the coordinate fallback
      // below. An exception signals a real matcher failure we'd rather surface as a skip than
      // mask by migrating off the recorded coordinate alone; the fallback is only for the
      // "matcher ran fine but resolved to nothing" case.
      is SourceCenter.MatcherFailed -> return null
      is SourceCenter.NoMatch -> null
      is SourceCenter.Resolved -> resolved
    }
    // On-device captures (AgentDriverLog) record the EXACT coordinate the recorded tap/assert
    // resolved to at run time in their `action` block. When the resolver runs cleanly but
    // resolves to nothing — the common case is a summary row whose visible text (e.g. "-$5.00", a
    // payment-method label) is a substring of a concatenated container text, so an anchored
    // full-match textRegex misses even though the target tree has a clean leaf for it — fall back
    // to the recorded coordinate. The anchor refinement below still searches the WHOLE target tree
    // for a node carrying the selector's text/id intent (the coordinate is only a proximity
    // tiebreaker), so a log that lacks the anchor still won't resolve — correctness is preserved,
    // only the coordinate source changes.
    val center = sourceCenter?.point ?: readActionCoordinate(logFile) ?: return null
    val matcher = sourceCenter?.matcher ?: SourceMatcher.RECORDED_COORDINATE
    val resolveMs = System.currentTimeMillis() - tResolve
    val (cx, cy) = center
    val hitNode = tree.hitTest(cx, cy) ?: return null
    // Anchor refinement: hit-test routinely lands on a parent click container that wraps
    // multiple labeled descendants (the canonical case is a tab View wrapping both the
    // tab label "Orders" and a notification badge "3"). Or it lands on one of two
    // sibling TextViews that share a resourceId (a `checkout_button_title` id can be
    // labeled "Review sale" on one screen view and "Charge $X.XX" on another, both
    // overlapping the tap). When the hit node alone doesn't carry the original
    // selector's text/id intent, [findBestSelector] picks an arbitrary node nearby —
    // sometimes the wrong one.
    //
    // We carry forward the user's intent by extracting every text/id anchor present in
    // the original selector tree, then searching the WHOLE target-dialect tree
    // for the node that matches the most anchors. Tap coordinate is the proximity
    // tiebreaker — when multiple nodes match equally well, pick the one nearest the
    // captured tap. Searching the whole tree (rather than just hitNode's subtree) is
    // necessary because hit-test is a single-node result: if it lands on the wrong
    // sibling, the right sibling isn't in `hitNode.aggregate()`.
    //
    // If no node in the tree matches any anchor, return null. The forward-cursor scan
    // in [findFirstResolvingLog] continues to other logs; if no log produces an
    // anchor-preserving migration, the tool is reported SKIPPED so the operator knows
    // to hand-author or re-capture rather than silently accept a drifted selector.
    // A caller holding only the lowered selector (the fixture-test entry point) still gets anchors,
    // read off that lowering with the Maestro semantics it is evaluated under.
    val originalAnchors = if (sourceSelector != null) {
      collectSelectorAnchors(sourceSelector)
    } else {
      collectLoweredSelectorAnchors(loweredSelector)
    }
    val target = if (originalAnchors.isNotEmpty()) {
      val refined = findAnchorMatchingNode(
        tree = tree,
        anchors = originalAnchors,
        tapX = cx,
        tapY = cy,
      )
      if (refined == null) {
        // Original selector had explicit text/id anchors but none matched any node in
        // the tree — the migration would drift the user's intent. Skip.
        return null
      }
      refined
    } else {
      // Selectors without text/id anchors (e.g. spatial-only or pure containsChild
      // descendants of unanchored nodes) have no intent text to preserve. Fall back to
      // the original behavior of describing whatever hit-test landed on.
      hitNode
    }
    val tBest = System.currentTimeMillis()
    val result = TrailblazeNodeSelectorGenerator.findBestSelector(tree, target)
    val bestMs = System.currentTimeMillis() - tBest
    // Only log when slow (>500ms in any phase) to keep happy-path output clean. The phase
    // breakdown isolates where time is going:
    //   load   = file read + kotlinx-serialization decode of the (polymorphic) JSON
    //   resolve = TapSelectorV2 / Maestro filter pipeline reflection
    //   best    = findBestSelector strategy cascade + per-strategy isUniqueMatch traversal
    if (loadMs > 500 || resolveMs > 500 || bestMs > 500) {
      Console.log(
        "#     ${logFile.name}: load=${loadMs}ms resolve=${resolveMs}ms findBest=${bestMs}ms",
      )
    }
    return ResolvedSelector(result, matcher)
  }

  /**
   * One text or id pattern from a recorded selector, matched the way the dialect it was recorded
   * in matches it: whole string, regex or literal, and case-insensitive only for a Maestro
   * dialect. That is the runtime's own rule ([DriverNodeMatch]), so an anchor can never claim a
   * node the recorded selector would not have matched — `ok` does not anchor `book`, and
   * Maestro's `search` still anchors `Search`.
   *
   * [isIdentifier] says which of a node's values the anchor is scored against — its identifiers
   * or its text — and, under Maestro semantics, which second form Maestro also tries: an id's
   * suffix after the last `/` (`save` names `com.app:id/save`), or text with newlines read as
   * spaces (`Line one Line two` names a two-line label). Mirrors `PropertyUniqueness`.
   */
  internal data class SelectorAnchor(
    val pattern: String,
    val maestroSemantics: Boolean,
    val isIdentifier: Boolean = false,
  ) {
    fun matches(value: String): Boolean {
      if (matchesWhole(value)) return true
      if (!maestroSemantics) return false
      val alternate = if (isIdentifier) value.substringAfterLast('/') else value.replace('\n', ' ')
      return alternate != value && matchesWhole(alternate)
    }

    private fun matchesWhole(value: String): Boolean =
      selectorPatternRegexMatches(pattern, value, maestroDialect = maestroSemantics) || value == pattern
  }

  /**
   * Collect every text/id anchor present anywhere in the recorded selector tree.
   *
   * The migration's job is to preserve the user's *intent* across the shape change. The
   * intent is encoded in the text and id fields the user (or the LLM that recorded the trail) put
   * on the selector — not in the selector's structure. So when looking for a refined target in
   * the target tree, we want any node whose text matches *any* of these anchors, regardless of
   * where in the original selector tree they appeared (top-level, inside `containsChild`, inside
   * `childOf`, etc).
   *
   * Read off the recorded selector rather than its Maestro lowering, which keeps only the first
   * text field of a leaf: a leaf naming both `textRegex` and `contentDescriptionRegex` gives two
   * anchors, so the node carrying both outranks one carrying either.
   */
  internal fun collectSelectorAnchors(selector: TrailblazeNodeSelector?): List<SelectorAnchor> {
    if (selector == null) return emptyList()
    val out = mutableListOf<SelectorAnchor>()
    for (dialect in SelectorDialect.entries) {
      val leaf = dialect.leafOf(selector) ?: continue
      val maestro = dialect.resolvesViaMaestroPipeline
      val (texts, ids) = anchorPatterns(leaf)
      texts.forEach { out += SelectorAnchor(it, maestro) }
      ids.forEach { out += SelectorAnchor(it, maestro, isIdentifier = true) }
    }
    listOfNotNull(
      selector.containsChild,
      selector.childOf,
      selector.above,
      selector.below,
      selector.leftOf,
      selector.rightOf,
    ).forEach { out += collectSelectorAnchors(it) }
    selector.containsDescendants?.forEach { out += collectSelectorAnchors(it) }
    return out.distinct()
  }

  /**
   * [collectSelectorAnchors] for a caller that has only the Maestro-shaped lowering: its
   * `textRegex` / `idRegex` at every level, matched with the Maestro semantics that lowering is
   * evaluated under.
   */
  internal fun collectLoweredSelectorAnchors(selector: TrailblazeElementSelector?): List<SelectorAnchor> {
    if (selector == null) return emptyList()
    val out = mutableListOf<SelectorAnchor>()
    selector.textRegex?.takeIf { it.isNotBlank() }?.let { out += SelectorAnchor(it, maestroSemantics = true) }
    selector.idRegex?.takeIf { it.isNotBlank() }?.let {
      out += SelectorAnchor(it, maestroSemantics = true, isIdentifier = true)
    }
    listOfNotNull(
      selector.containsChild,
      selector.childOf,
      selector.above,
      selector.below,
      selector.leftOf,
      selector.rightOf,
    ).forEach { out += collectLoweredSelectorAnchors(it) }
    selector.containsDescendants?.forEach { out += collectLoweredSelectorAnchors(it) }
    return out.distinct()
  }

  /**
   * The text patterns and the identifier patterns on [match] — the fields whose values a node
   * carries in [xyz.block.trailblaze.api.DriverNodeDetail.textCandidates] and
   * `identifierCandidates` respectively. Class, role and state fields are left out: they say what
   * kind of node it is, not which one.
   */
  private fun anchorPatterns(match: DriverNodeMatch): Pair<List<String>, List<String>> {
    val (texts, ids) = when (match) {
      is DriverNodeMatch.AndroidAccessibility -> listOf(
        match.textRegex, match.contentDescriptionRegex, match.hintTextRegex, match.labeledByTextRegex,
      ) to listOf(match.resourceIdRegex, match.uniqueId, match.composeTestTagRegex)
      is DriverNodeMatch.AndroidView -> listOf(
        match.textRegex, match.contentDescriptionRegex, match.hintTextRegex,
      ) to listOf(match.resourceIdRegex, match.tagRegex)
      is DriverNodeMatch.AndroidMaestro ->
        listOf(match.textRegex, match.accessibilityTextRegex, match.hintTextRegex) to listOf(match.resourceIdRegex)
      is DriverNodeMatch.IosMaestro ->
        listOf(match.textRegex, match.accessibilityTextRegex, match.hintTextRegex) to listOf(match.resourceIdRegex)
      is DriverNodeMatch.IosAxe -> listOf(match.labelRegex, match.valueRegex, match.titleRegex) to listOf(match.uniqueId)
      is DriverNodeMatch.Web -> listOf(match.ariaNameRegex, match.ariaDescriptorRegex) to listOf(match.dataTestId)
      is DriverNodeMatch.Compose -> listOf(
        match.textRegex, match.editableTextRegex, match.contentDescriptionRegex,
      ) to listOf(match.testTag)
    }
    fun List<String?>.present() = filterNotNull().filter { it.isNotBlank() }
    return texts.present() to ids.present()
  }

  /**
   * Walk the WHOLE accessibility tree for a node whose text / contentDescription /
   * resourceId matches one or more of the [anchors] regex strings. Returns the
   * highest-scoring node — the one matching the MOST anchors — with proximity to the
   * captured tap (tapX, tapY) as the tiebreaker.
   *
   * Why search the whole tree rather than the hit-test result's subtree: hit-test
   * returns a SINGLE node; when two siblings overlap the tap (e.g. two TextViews
   * sharing a resourceId at the same bounds, one labeled "Review sale" and one
   * labeled "Charge $X.XX"), hit-test picks one and the OTHER is not in its
   * `aggregate()`. The right anchor for the migration is the one matching the most
   * original anchors — possibly that other sibling. Looking at the whole tree
   * preserves the option.
   *
   * Why proximity is the tiebreaker rather than smallest area: when scores tie, the
   * geometrically-closest node to where the user originally tapped is the most likely
   * intended target. Two text-bearing nodes elsewhere on screen with the same anchor
   * text shouldn't outrank the one near the tap.
   *
   * "Match" is [SelectorAnchor.matches] — the runtime's own whole-string rule — so a sibling
   * whose text is "3" can't be picked when the anchor is `Orders`, nor `book` when it is `ok`.
   *
   * Returns null when no node in the tree matches any anchor — caller treats that as
   * "skip with warning".
   */
  internal fun findAnchorMatchingNode(
    tree: TrailblazeNode,
    anchors: List<SelectorAnchor>,
    tapX: Int,
    tapY: Int,
  ): TrailblazeNode? {
    if (anchors.isEmpty()) return null

    data class Scored(
      val node: TrailblazeNode,
      val matchCount: Int,
      val distanceSq: Long,
      val area: Long,
    )

    val scored = tree.aggregate().mapNotNull { node ->
      val detail = node.driverDetail
      // Every label and identifier this node carries, in whatever fields ITS dialect uses. The
      // recorded anchors came from a different dialect, which stored the same words elsewhere.
      val texts = detail.textCandidates()
      val ids = detail.identifierCandidates()
      val matchCount = anchors.count { anchor ->
        (if (anchor.isIdentifier) ids else texts).any { anchor.matches(it) }
      }
      if (matchCount == 0) return@mapNotNull null
      val b = node.bounds
      val (cx, cy) = if (b == null) {
        Pair(Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
      } else {
        Pair(b.centerX, b.centerY)
      }
      val dx = (cx - tapX).toLong()
      val dy = (cy - tapY).toLong()
      val area = if (b == null) Long.MAX_VALUE else b.width.toLong() * b.height.toLong()
      Scored(node, matchCount, dx * dx + dy * dy, area)
    }
    if (scored.isEmpty()) return null
    return scored
      .sortedWith(
        compareByDescending<Scored> { it.matchCount }
          .thenBy { it.distanceSq }
          .thenBy { it.area },
      )
      .first()
      .node
  }

  /**
   * One-line summary for the per-step status. The full selector body lands in the YAML
   * diff; this is just a "did the right kind of selector come out" sniff test for the
   * operator scanning the run.
   */
  private fun shortDescribeSelector(selector: TrailblazeNodeSelector, pair: MigrationPair): String {
    val leaf = pair.target.leafOf(selector)
      ?: return "(not a ${pair.target.yamlKey} selector)"
    return leaf.description().ifEmpty { "(structural)" }
  }

  /**
   * List all session-log files that carry a usable pair of trees, in chronological order.
   *
   * Three file types qualify, all treated equivalently for migration purposes:
   *
   *   - `*_TrailblazeLlmRequestLog.json` — emitted on every LLM round during recording or
   *     prompt-mode replay. The screen state is the agent's pre-LLM view of the world.
   *   - `*_TrailblazeSnapshotLog.json` — emitted by the per-tool capture hook in
   *     [TrailblazeRunnerUtil] when the migration mode flag (`trailblaze.captureSecondaryTree`)
   *     is set. One log per recorded tool, immediately before the tool fires.
   *   - `*_AgentDriverLog.json` — emitted by [AccessibilityTrailRunner] in the on-device
   *     accessibility-driver path. One log per low-level action (tap, swipe, assertion). When
   *     `trailblaze.captureSecondaryTree=true` is set, the captured `viewHierarchy` is the
   *     true UiAutomator dump (not the accessibility-derived projection). This is the file
   *     type produced on CI accessibility-driver runs — the dispatch path doesn't go through
   *     [TrailblazeRunnerUtil]'s pre-tool hook, so AgentDriverLog is the only per-action
   *     capture available there.
   *
   * Sort by JSON `timestamp` field rather than filename: device-farm logs use hex hashes
   * (e.g. `7d50895f_AgentDriverLog.json`), so alphabetical order doesn't match emit order.
   * The numeric-prefix convention (`008_…`) used by local CLI runs would still sort correctly
   * by timestamp too, so timestamp-sort is uniformly correct.
   */
  internal fun listSnapshotLogs(sessionDir: File): List<File> {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    val candidates = sessionDir.listFiles { f ->
      f.name.endsWith("_TrailblazeLlmRequestLog.json") ||
        f.name.endsWith("_TrailblazeSnapshotLog.json") ||
        f.name.endsWith("_AgentDriverLog.json")
    } ?: return emptyList()
    return candidates
      .filter { logHasBothTrees(it) }
      .sortedBy { readTimestamp(it) ?: it.name }
  }

  /**
   * Extract the JSON `timestamp` field as an ISO-8601 string for chronological sorting.
   * String comparison on ISO-8601 timestamps is order-preserving, so we don't need to parse
   * to Instant. Returns null if the field is missing — the caller falls back to filename
   * sort for that file (still gives a stable order, just possibly not chronological).
   */
  private fun readTimestamp(logFile: File): String? {
    val raw = logFile.readText()
    val match = Regex("""\"timestamp\"\s*:\s*\"([^\"]*)\"""").find(raw) ?: return null
    return match.groupValues[1]
  }

  /**
   * Quick presence check that doesn't decode the full log JSON — peek for the top-level
   * keys that drive the migration. JSON is small enough at log-step granularity that even a
   * full deserialize would be fine, but the key-presence shortcut keeps `--session` listing
   * snappy on big sessions.
   *
   * Three keys must all be present:
   *
   * - **`viewHierarchy`** — the Maestro selector resolver ([TapSelectorV2]) needs the
   *   UiAutomator XML view hierarchy to find the original tap coordinate.
   * - **`trailblazeNodeTree`** — historic gate; ensures the log carries some node tree at
   *   all, even if just the driver's canonical (possibly Maestro-shape) one.
   * - **`driverMigrationTreeNode`** — proves the capture was made in migration mode
   *   (`trailblaze.captureSecondaryTree=true`). On the accessibility driver this is the
   *   *only* signal that `viewHierarchy` is the true UiAutomator dump rather than the
   *   accessibility-derived projection — the two have different shapes, and feeding an
   *   accessibility-projected hierarchy to TapSelectorV2's Maestro-shape resolver
   *   silently produces wrong-coordinate matches. Logs without this field are skipped
   *   from migrate-trail's candidate set even if they otherwise look "complete".
   *
   * Since kotlinx-serialization elides default-null fields from JSON output, the
   * substring presence of `"driverMigrationTreeNode"` is a sufficient marker — if the
   * field is missing in the JSON, the capture was not in migration mode.
   */
  internal fun logHasBothTrees(logFile: File): Boolean {
    val raw = logFile.readText()
    return raw.contains("\"viewHierarchy\"") &&
      raw.contains("\"trailblazeNodeTree\"") &&
      raw.contains("\"driverMigrationTreeNode\"")
  }

  /**
   * Read the snapshot log's `displayName` field via a regex on the raw JSON. Avoids
   * deserializing the full log just to read one string field. Returns null if the field is
   * absent or the pattern doesn't match (e.g., LlmRequestLog files have no displayName).
   */
  internal fun readDisplayName(logFile: File): String? {
    val raw = logFile.readText()
    val match = Regex("""\"displayName\"\s*:\s*\"([^\"]*)\"""").find(raw) ?: return null
    return match.groupValues[1]
  }

  /**
   * Read the recorded device coordinate from an on-device capture's `action` block, but only
   * when that coordinate points at a real on-screen target. The `AgentDriverLog.action` sealed
   * value (`TapPoint{x,y}`, `AssertCondition{...,x,y,isVisible,succeeded}`, etc.) carries the
   * EXACT coordinate the recorded tool resolved to at capture time — a ground-truth hit-test
   * seed that survives even when the Maestro-tree resolver can't reproduce it (concatenated
   * summary rows, off-by-a-frame captures). Used only as a fallback in [tryResolveInLog] after
   * the Maestro resolver returns null.
   *
   * Returns null unless the coordinate is trustworthy:
   *  - Tap coordinates (`TapPoint`/`LongPressPoint`, no `isVisible` field) are always a real
   *    tap target — accepted.
   *  - An `AssertCondition` is accepted only when it's a SUCCESSFUL VISIBLE assertion. A
   *    not-visible assert (`isVisible=false`) records screen-center coordinates and a failed
   *    assert (`succeeded=false`) records (0,0); either would seed the hit-test with a bogus
   *    proximity point that could mis-rank the anchor-refinement among multiple matches during
   *    the forward-cursor scan. So a later selector never binds off a not-visible/failed
   *    assert's log.
   *
   * Also returns null when there's no action object or it carries no numeric x/y (e.g. an
   * EnterText action). Parses the JSON structurally (rather than regex-slicing the `action`
   * object) so a string field carrying `{`/`}` — or a future nested-object action shape —
   * can't truncate the slice and reintroduce the very selector drops this fallback prevents.
   */
  internal fun readActionCoordinate(logFile: File): Pair<Int, Int>? {
    val root = try {
      TrailblazeJson.defaultWithoutToolsInstance.parseToJsonElement(logFile.readText())
    } catch (e: Exception) {
      return null
    }
    val action = (root as? JsonObject)?.get("action") as? JsonObject ?: return null
    // `isVisible` is present only on AssertCondition — its presence identifies an assert.
    val isVisible = (action["isVisible"] as? JsonPrimitive)?.booleanOrNull
    if (isVisible != null) {
      if (!isVisible) return null // not-visible assert → screen-center coords, not a target
      val succeeded = (action["succeeded"] as? JsonPrimitive)?.booleanOrNull ?: true
      if (!succeeded) return null // failed assert → (0,0), not a target
    }
    val x = (action["x"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    val y = (action["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    return x to y
  }

  /**
   * Map a YAML tool name (e.g. "tapOnElementBySelector") to the runtime class name
   * (e.g. "TapOnByElementSelector") that the pre-tool capture hook writes into the
   * snapshot log's displayName. This is the precise list of selector-bearing tools the
   * migration touches, so the mapping stays explicit.
   */
  internal fun classNameFromYamlToolName(toolName: String): String = when (toolName) {
    "tapOnElementBySelector" -> "TapOnByElementSelector"
    "assertVisibleBySelector" -> "AssertVisibleBySelectorTrailblazeTool"
    else -> toolName // fallback — shouldn't happen given collectSourceSelectorsUnified's filter
  }

  /**
   * Minimal unified-diff renderer. We don't pull in a diff library because (a) only this
   * command needs it and (b) the trail YAMLs are short enough that a per-line LCS isn't
   * necessary — printing both halves with a header and `+`/`-` markers per changed line
   * gives a reviewable patch for the use case.
   */
  private fun printUnifiedDiff(filename: String, before: String, after: String) {
    if (before == after) {
      Console.log("# (no changes)")
      return
    }
    Console.log("--- a/$filename")
    Console.log("+++ b/$filename")
    val beforeLines = before.lines()
    val afterLines = after.lines()
    val maxLen = maxOf(beforeLines.size, afterLines.size)
    for (i in 0 until maxLen) {
      val b = beforeLines.getOrNull(i)
      val a = afterLines.getOrNull(i)
      when {
        b == a -> Console.log(" ${b ?: ""}")
        b != null && a == null -> Console.log("-$b")
        b == null && a != null -> Console.log("+$a")
        else -> {
          Console.log("-$b")
          Console.log("+$a")
        }
      }
    }
  }
}
