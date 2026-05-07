package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.util.concurrent.Callable

/**
 * Mechanically migrate a trail file's legacy [TrailblazeElementSelector] selectors
 * (Maestro-shape) into rich [TrailblazeNodeSelector]s (accessibility-shape) by replaying
 * the deterministic two-tree resolution against captured session logs.
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
 *  1. The legacy Maestro selector is resolved against the captured `viewHierarchy` (Maestro
 *     tree) using the same matcher the runtime uses for taps. This yields the SAME on-screen
 *     coordinate the legacy runtime would have tapped.
 *  2. That coordinate is hit-tested against the captured `trailblazeNodeTree` (accessibility
 *     tree). The result is the accessibility node a runtime tap at the same coordinate would
 *     have hit.
 *  3. [TrailblazeNodeSelectorGenerator.findBestSelector] picks the cleanest selector for that
 *     node — typically a resource-id match, falling back to text/content-description, and
 *     finally to spatial/structural anchors when nothing identifying is available.
 *
 * The output is a YAML where every selector-bearing tool now carries a `nodeSelector` field
 * derived from the SAME on-screen element the legacy `selector` resolved to, with no LLM
 * interpretation in the loop.
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
 * with no log partner. For 100% fidelity to the legacy Maestro driver, capture the logs
 * with the dual-tree instrumentation arg on (a follow-up phase) so `viewHierarchy` is the
 * true UiAutomator tree rather than the accessibility-derived projection.
 *
 * ## Inputs
 *
 *  - Positional `<trail.yaml>` — the trail file to migrate.
 *  - `--session <dir>` — directory containing `*_TrailblazeLlmRequestLog.json` files from
 *    a recorded run of the same trail.
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
    "Mechanically migrate legacy `selector` (Maestro-shape) → `nodeSelector` (accessibility",
    "shape) for every selector-bearing tool in a trail YAML, using the captured session",
    "logs to deterministically resolve each selector through the same matcher the runtime",
    "uses for taps.",
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
      "Session log directory containing *_TrailblazeLlmRequestLog.json files captured",
      "during a recorded run of this trail. The Nth selector-bearing tool in the YAML",
      "is paired with the Nth log step that carries both viewHierarchy and",
      "trailblazeNodeTree.",
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

  override fun call(): Int {
    if (!trailFile.exists() || !trailFile.isFile) {
      Console.error("Trail file not found: ${trailFile.absolutePath}")
      return CommandLine.ExitCode.USAGE
    }
    if (!sessionDir.isDirectory) {
      Console.error("Session log directory not found: ${sessionDir.absolutePath}")
      return CommandLine.ExitCode.USAGE
    }

    val originalYaml = trailFile.readText()
    val trailblazeYaml = createTrailblazeYaml()
    val items = try {
      trailblazeYaml.decodeTrail(originalYaml)
    } catch (e: Exception) {
      Console.error("Could not decode trail YAML ${trailFile.name}: ${e.message}")
      return 1
    }

    // Pass 1 — collect the legacy Maestro selectors in YAML order. The list index doubles
    // as the alignment key against the session-log list in pass 2 / 3.
    val maestroSelectors = collectMaestroSelectors(items)
    if (maestroSelectors.isEmpty()) {
      Console.log("# No selector-bearing tools found in ${trailFile.name}; nothing to migrate.")
      return CommandLine.ExitCode.OK
    }

    // Pass 2 — drive the deterministic two-tree resolution per (selector, log) pair. The
    // result is an indexed map: index → migrated nodeSelector. Indexes with no migration
    // (skipped, no log, hit-test miss) simply omit an entry, and pass 3 leaves the
    // corresponding tool unchanged.
    val logs = listSnapshotLogs(sessionDir)
    if (logs.isEmpty()) {
      Console.error(
        "No usable session logs in ${sessionDir.absolutePath}. " +
          "Need *_TrailblazeLlmRequestLog.json or *_TrailblazeSnapshotLog.json files that " +
          "contain both `viewHierarchy` and `trailblazeNodeTree`. Pre-tool snapshot logs " +
          "are written when the recording-mode runner is started with " +
          "`-e trailblaze.captureSecondaryTree true` (the migration capture mode).",
      )
      return 1
    }
    Console.log(
      "# Scanning ${logs.size} logs for ${maestroSelectors.size} selector-bearing tools",
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
    val migrations: Map<Int, TrailblazeNodeSelector> = buildMap {
      val usedLogs = mutableSetOf<File>()
      var fallbackCursor = 0
      val total = maestroSelectors.size
      maestroSelectors.forEachIndexed { idx, sel ->
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
            f !in usedLogs && readDisplayName(f)?.startsWith("$phase: $toolClassName") == true
          }
        }
        val matchingSnapshot = matchedPhase?.let { phase ->
          logs.firstOrNull { f ->
            f !in usedLogs && readDisplayName(f)?.startsWith("$phase: $toolClassName") == true
          }
        }
        if (matchingSnapshot != null) {
          val nodeSelector = tryResolveInLog(sel.maestroSelector, matchingSnapshot)
          if (nodeSelector != null) {
            Console.log(
              "# [${idx + 1}/${sel.toolName}] migrated via ${matchingSnapshot.name} " +
                "($matchedPhase match) → ${shortDescribeSelector(nodeSelector)}",
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
          maestroSelector = sel.maestroSelector,
          logs = logs,
          startIdx = fallbackCursor,
        ) ?: run {
          Console.log(
            "# [${idx + 1}/${sel.toolName}] SKIPPED: Maestro selector did not resolve in any " +
              "remaining log (${logs.size - fallbackCursor} scanned from idx $fallbackCursor)",
          )
          return@forEachIndexed
        }
        Console.log(
          "# [${idx + 1}/${sel.toolName}] migrated via ${hit.logFile.name} (fallback) → " +
            shortDescribeSelector(hit.nodeSelector),
        )
        put(idx, hit.nodeSelector)
        fallbackCursor = hit.logIdx
      }
    }

    // Pass 3 — re-walk the YAML, substituting the Nth selector-bearing tool's nodeSelector
    // with migrations[N] when present. Comments and blank-line groupings in the source file
    // are dropped on round-trip — that's an acknowledged trade-off; a follow-up LLM pass
    // can re-add commentary where it carries semantic value.
    val cursor = IndexedCursor()
    val migratedItems = items.map { migrateItem(it, migrations, cursor) }
    val migratedYaml = trailblazeYaml.encodeToString(migratedItems)

    val migratedCount = migrations.size
    val skippedCount = maestroSelectors.size - migratedCount

    Console.log("")
    Console.log("# === Summary: ${trailFile.name} ===")
    Console.log("# Selector-bearing tools: ${maestroSelectors.size}")
    Console.log("# Migrated:               $migratedCount")
    Console.log("# Skipped:                $skippedCount")

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
    return CommandLine.ExitCode.OK
  }

  /** Cursor that walks the trail YAML in deterministic order — same traversal as collect. */
  private class IndexedCursor(var index: Int = 0)

  internal data class MaestroSelectorAtIndex(
    val maestroSelector: TrailblazeElementSelector,
    val toolName: String,
  )

  // ----- Pass 1: enumerate selector-bearing tools in YAML order -------------------

  internal fun collectMaestroSelectors(items: List<TrailYamlItem>): List<MaestroSelectorAtIndex> {
    val out = mutableListOf<MaestroSelectorAtIndex>()
    fun visit(wrapper: TrailblazeToolYamlWrapper) {
      when (val tool = wrapper.trailblazeTool) {
        is TapOnByElementSelector ->
          tool.selector?.let { out += MaestroSelectorAtIndex(it, wrapper.name) }
        is AssertVisibleBySelectorTrailblazeTool ->
          // Skip already-migrated tools (selector dropped, only nodeSelector remains).
          // Cursor positioning is consistent across walks because every selector-bearing
          // tool — migrated or not — flows through here only when `selector` is present.
          tool.selector?.let { out += MaestroSelectorAtIndex(it, wrapper.name) }
        else -> { /* not a migration target */ }
      }
    }
    items.forEach { item ->
      when (item) {
        is TrailYamlItem.PromptsTrailItem -> item.promptSteps.forEach { step ->
          step.recording?.tools?.forEach { visit(it) }
        }
        is TrailYamlItem.ToolTrailItem -> item.tools.forEach { visit(it) }
        is TrailYamlItem.ConfigTrailItem -> { /* no tools */ }
      }
    }
    return out
  }

  // ----- Pass 3: re-walk and substitute tools by index ----------------------------

  private fun migrateItem(
    item: TrailYamlItem,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
  ): TrailYamlItem = when (item) {
    is TrailYamlItem.PromptsTrailItem ->
      item.copy(promptSteps = item.promptSteps.map { migrateStep(it, migrations, cursor) })
    is TrailYamlItem.ToolTrailItem ->
      item.copy(tools = item.tools.map { migrateWrapper(it, migrations, cursor) })
    is TrailYamlItem.ConfigTrailItem -> item
  }

  private fun migrateStep(
    step: PromptStep,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
  ): PromptStep {
    val recording = step.recording ?: return step
    val migrated = ToolRecording(
      tools = recording.tools.map { migrateWrapper(it, migrations, cursor) },
      autoSatisfied = recording.autoSatisfied,
    )
    return when (step) {
      is DirectionStep -> step.copy(recording = migrated)
      is VerificationStep -> step.copy(recording = migrated)
    }
  }

  private fun migrateWrapper(
    wrapper: TrailblazeToolYamlWrapper,
    migrations: Map<Int, TrailblazeNodeSelector>,
    cursor: IndexedCursor,
  ): TrailblazeToolYamlWrapper {
    val tool = wrapper.trailblazeTool
    // When a migration succeeds for a tool, the legacy `selector:` is REPLACED
    // wholesale by the new `nodeSelector:`. The goal of migrate-trail is to leave
    // ZERO Maestro-shape selectors on migrated tools — having both fields is a smell
    // (which is the source-of-truth at runtime?). Tools whose migration didn't
    // resolve keep their original selector intact and pass through unchanged.
    val updatedTool: xyz.block.trailblaze.toolcalls.TrailblazeTool? = when (tool) {
      is TapOnByElementSelector -> {
        if (tool.selector != null) {
          val idx = cursor.index++
          migrations[idx]?.let { tool.copy(selector = null, nodeSelector = it) }
        } else {
          null
        }
      }
      is AssertVisibleBySelectorTrailblazeTool -> {
        // Skip already-migrated tools (selector already null) so the cursor stays in
        // sync with [collectMaestroSelectors], which also skips those.
        if (tool.selector != null) {
          val idx = cursor.index++
          migrations[idx]?.let { tool.copy(selector = null, nodeSelector = it) }
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

  // ----- Pass 2: run the deterministic two-tree resolution per pair ---------------

  private data class ResolveHit(
    val logFile: File,
    val logIdx: Int,
    val nodeSelector: TrailblazeNodeSelector,
  )

  /**
   * Forward-scan [logs] starting at [startIdx], returning the first log where
   * [maestroSelector] resolves to a coordinate that hit-tests to an accessibility node, plus
   * the resulting `findBestSelector` output. Returns null if no log in the suffix resolves.
   *
   * The forward-only scan is what gives "two `^Next$` taps on different screens bind to
   * distinct logs" — once a selector matches, the cursor advances past that log so the next
   * occurrence has to find a fresh one.
   */
  private fun findFirstResolvingLog(
    maestroSelector: TrailblazeElementSelector,
    logs: List<File>,
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
      val hit = tryResolveInLog(maestroSelector, logs[i])
      val perLogMs = System.currentTimeMillis() - perLogStart
      // Slow-log alert — one log taking >2s means we're either re-parsing a huge AgentDriverLog
      // or stuck inside findBestSelector. Surface it explicitly so the operator knows which
      // file is the suspect.
      if (perLogMs > 2_000) {
        Console.log("#   slow: ${logs[i].name} took ${perLogMs}ms")
      }
      if (hit != null) return ResolveHit(logFile = logs[i], logIdx = i, nodeSelector = hit)
    }
    return null
  }

  /**
   * Visible-for-testing entry point: resolves a single Maestro selector against a single
   * captured session log and returns the migrated nodeSelector (or null on miss).
   * Exposed (not `internal`) so a downstream test module can exercise it against
   * captured-session fixtures that live outside this module's resources.
   */
  fun tryResolveInLog(
    maestroSelector: TrailblazeElementSelector,
    logFile: File,
  ): TrailblazeNodeSelector? {
    val tLoad = System.currentTimeMillis()
    val screen = try {
      SessionLogScreenState.loadStep(logFile)
    } catch (e: Exception) {
      return null
    }
    val loadMs = System.currentTimeMillis() - tLoad
    // Prefer the dedicated migration tree from migration-mode captures
    // (`trailblaze.captureSecondaryTree=true`) — that's always accessibility-shape
    // regardless of which driver actually ran the test. Fall back to `trailblazeNodeTree`
    // for legacy logs (no migration capture) and accessibility-driver runs (where the
    // primary tree IS already the right shape, so no wrap is needed).
    val tree = (screen as? xyz.block.trailblaze.api.MigrationScreenState)?.driverMigrationTreeNode
      ?: screen.trailblazeNodeTree
      ?: return null
    if (screen.deviceWidth <= 0 || screen.deviceHeight <= 0) return null

    // The Maestro matcher (`ElementMatcherUsingMaestro`) reflects into Maestro's internal
    // Orchestra filter pipeline and can throw on malformed selectors or corner cases that
    // its parent never exercises (e.g. an empty `containsChild` regex). A crash here would
    // abort the whole batch migration mid-run; treat it as "no match in this log" so the
    // forward-cursor scan continues. The legitimate "selector resolves but no accessibility
    // node covers the coord" case is already handled by the `tree.hitTest(cx, cy)` null
    // return below — we don't want to swallow real bugs in OUR code, but we DO need
    // to swallow Maestro's reflection-driven exception path during a forward scan.
    val tResolve = System.currentTimeMillis()
    val center = try {
      TapSelectorV2.findNodeCenterUsingSelector(
        root = screen.viewHierarchy,
        selector = maestroSelector,
        trailblazeDevicePlatform = screen.trailblazeDevicePlatform,
        widthPixels = screen.deviceWidth,
        heightPixels = screen.deviceHeight,
      )
    } catch (e: Exception) {
      return null
    } ?: return null
    val resolveMs = System.currentTimeMillis() - tResolve
    val (cx, cy) = center
    val hitNode = tree.hitTest(cx, cy) ?: return null
    // Anchor refinement: hit-test routinely lands on a parent click container that wraps
    // multiple labeled descendants (the canonical case is a tab View wrapping both the
    // tab label "Orders" and a notification badge "3"). Or it lands on one of two
    // sibling TextViews that share a resourceId (Square's `checkout_button_title` is
    // labeled "Review sale" on one screen view and "Charge $X.XX" on another, both
    // overlapping the tap). When the hit node alone doesn't carry the original
    // selector's text/id intent, [findBestSelector] picks an arbitrary node nearby —
    // sometimes the wrong one.
    //
    // We carry forward the user's intent by extracting every text/id anchor present in
    // the original Maestro selector tree, then searching the WHOLE accessibility tree
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
    val originalAnchors = collectMaestroAnchors(maestroSelector)
    val target = if (originalAnchors.isNotEmpty()) {
      val refined = findAnchorMatchingNode(tree, originalAnchors, cx, cy)
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
    return result
  }

  /**
   * Collect every text-bearing anchor present anywhere in the Maestro selector tree.
   *
   * The migration's job is to preserve the user's *intent* across the shape change. The
   * intent is encoded in the `textRegex` / `idRegex` fields the user (or the LLM that
   * recorded the trail) put on the selector — not in the selector's structure. So when
   * looking for a refined target in the accessibility tree, we want any descendant whose
   * text matches *any* of these anchors, regardless of where in the original selector
   * tree they appeared (top-level, inside `containsChild`, inside `childOf`, etc).
   *
   * Returns regex strings — callers compile and match against accessibility-tree node
   * text/contentDescription/resourceId.
   */
  internal fun collectMaestroAnchors(selector: TrailblazeElementSelector?): List<String> {
    if (selector == null) return emptyList()
    val out = mutableListOf<String>()
    selector.textRegex?.takeIf { it.isNotBlank() }?.let { out += it }
    selector.idRegex?.takeIf { it.isNotBlank() }?.let { out += it }
    out += collectMaestroAnchors(selector.containsChild)
    out += collectMaestroAnchors(selector.childOf)
    out += collectMaestroAnchors(selector.above)
    out += collectMaestroAnchors(selector.below)
    out += collectMaestroAnchors(selector.leftOf)
    out += collectMaestroAnchors(selector.rightOf)
    selector.containsDescendants?.forEach { out += collectMaestroAnchors(it) }
    return out
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
   * "Match" is strict regex — the same shape the runtime uses — so a sibling whose
   * text is "3" can't be picked when the anchor is `Orders`.
   *
   * Returns null when no node in the tree matches any anchor — caller treats that as
   * "skip with warning".
   */
  internal fun findAnchorMatchingNode(
    tree: TrailblazeNode,
    anchors: List<String>,
    tapX: Int,
    tapY: Int,
  ): TrailblazeNode? {
    if (anchors.isEmpty()) return null
    val regexes = anchors.mapNotNull {
      // Maestro selector regexes are sometimes whole-string anchored, sometimes substring;
      // we go permissive (containsMatchIn) since the original Maestro matcher does the
      // same and over-strict matching would drop legitimate matches.
      try {
        Regex(it)
      } catch (e: Exception) {
        null
      }
    }
    if (regexes.isEmpty()) return null

    data class Scored(
      val node: TrailblazeNode,
      val matchCount: Int,
      val distanceSq: Long,
      val area: Long,
    )

    val scored = tree.aggregate().mapNotNull { node ->
      val detail = node.driverDetail
      val text = detail.matchableText().orEmpty()
      val desc = detail.matchableContentDescription().orEmpty()
      val resourceId = detail.matchableResourceId().orEmpty()
      val matchCount = regexes.count { rx ->
        (text.isNotEmpty() && rx.containsMatchIn(text)) ||
          (desc.isNotEmpty() && rx.containsMatchIn(desc)) ||
          (resourceId.isNotEmpty() && rx.containsMatchIn(resourceId))
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
   * Driver-shape-agnostic accessor for the "primary text" field. AndroidAccessibility
   * is the only shape we expect during Maestro→accessibility migration today, but
   * abstracting keeps the helper robust to future tree shapes (Compose desktop, iOS Axe).
   */
  private fun DriverNodeDetail.matchableText(): String? = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> text
    is DriverNodeDetail.AndroidMaestro -> text
    is DriverNodeDetail.Compose -> text
    is DriverNodeDetail.IosMaestro -> text
    is DriverNodeDetail.IosAxe -> label
    is DriverNodeDetail.Web -> ariaName
  }

  private fun DriverNodeDetail.matchableContentDescription(): String? = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> contentDescription
    is DriverNodeDetail.AndroidMaestro -> accessibilityText
    else -> null
  }

  private fun DriverNodeDetail.matchableResourceId(): String? = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> resourceId
    is DriverNodeDetail.AndroidMaestro -> resourceId
    else -> null
  }

  /**
   * One-line summary for the per-step status. The full selector body lands in the YAML
   * diff; this is just a "did the right kind of selector come out" sniff test for the
   * operator scanning the run.
   */
  private fun shortDescribeSelector(selector: TrailblazeNodeSelector): String {
    val a = selector.androidAccessibility ?: return "(non-androidAccessibility selector)"
    val parts = mutableListOf<String>()
    a.resourceIdRegex?.let { parts += "id=$it" }
    a.textRegex?.let { parts += "text=$it" }
    a.contentDescriptionRegex?.let { parts += "desc=$it" }
    a.classNameRegex?.let { parts += "class=$it" }
    return parts.joinToString(" ").ifEmpty { "(structural)" }
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
   * Sort by JSON `timestamp` field rather than filename: ATF-produced logs use hex hashes
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
   * Map a YAML tool name (e.g. "tapOnElementBySelector") to the runtime class name
   * (e.g. "TapOnByElementSelector") that the pre-tool capture hook writes into the
   * snapshot log's displayName. This is the precise list of selector-bearing tools the
   * migration touches, so the mapping stays explicit.
   */
  internal fun classNameFromYamlToolName(toolName: String): String = when (toolName) {
    "tapOnElementBySelector" -> "TapOnByElementSelector"
    "assertVisibleBySelector" -> "AssertVisibleBySelectorTrailblazeTool"
    else -> toolName // fallback — shouldn't happen given collectMaestroSelectors's filter
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
