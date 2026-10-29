package xyz.block.trailblaze.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.filterImportantForAccessibility
import xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst
import xyz.block.trailblaze.cli.yaml.TrailblazeNodeSelectorYamlEmitter
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.escapeForIdentifier
import xyz.block.trailblaze.viewmatcher.TapSelectorV2
import xyz.block.trailblaze.yaml.TrailblazeYaml
import java.io.File
import java.util.concurrent.Callable

/**
 * `trailblaze selector suggest`: suggest selectors for one element of a captured screen.
 *
 * The hard part of authoring any selector by hand — for a `*.waypoint.yaml`, a trailhead, an
 * ad-hoc `tool tap --selector`, wherever a [TrailblazeNodeSelector] is accepted — is translating
 * "this thing on the screen" into "the right selector YAML." `./trailblaze snapshot --all` shows
 * every element with a short ref like `[a812]` next to its label, but the ref-to-selector
 * translation has historically been done by hand, with two failure modes that have actually
 * shipped:
 *
 *  1. Picking a selector that uniquely resolves on the recorded screen but is fragile across
 *     runs — e.g. matching by `text=` when the field that's actually populated for the node
 *     is `contentDescription=`, or missing the parent `isSelected: true` anchor that makes
 *     bottom-nav waypoints stable.
 *  2. Reaching for non-Trailblaze tools (raw `adb shell uiautomator dump`) for "speed" and
 *     ending up with a Maestro-shaped tree whose attributes are subtly different from the
 *     accessibility-driver tree the matcher actually reads (Pitfall 5 in the waypoints skill).
 *
 * This command short-circuits both. It loads the captured `trailblazeNodeTree` from a session
 * log, finds the node by ref, and runs the same `TrailblazeNodeSelectorGenerator` strategies
 * the runtime uses to identify an element. Rather than pick one "best" selector, it prints the
 * **whole menu**: every strategy that computes a selector resolving to the target, each with
 * its strategy name and how many nodes it currently matches. The author reads the menu and
 * uses whichever candidate fits — pasted into a waypoint's `required:`/`forbidden:` list, a
 * trailhead, or any other selector-accepting spot. `trailblaze selector match` then checks a
 * pick against other captures of the same screen.
 *
 * ## Why the whole menu, not just "the best"
 *
 * `findBestSelector` / `findAllValidSelectors` are tuned to pin down exactly **one** element —
 * the right call for a tap recording (the machine consumes it), but the wrong framing for a
 * human authoring a selector. Which selector is "best" depends on the author's intent, and
 * only they know it:
 *
 *  - A tap wants a **unique** match (`matches 1`).
 *  - A waypoint asserts a signal is **present** (`matches >= 1`) — so a semantic label
 *    that repeats on screen ("Add money" twice) is a perfectly good waypoint signal, where the
 *    tap cascade would have discarded it in favor of a positional `index`.
 *
 * So the command enumerates *everything computable* and labels each candidate with its strategy
 * and live match count, ranked most-stable first. See
 * [TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates] for the strategies and ranking.
 *
 * ## Inputs
 *
 *  - Exactly one of `--ref <X>` (the short ref `snapshot` shows), `--at x,y` (alias `--xy`;
 *    hit-tested the way the runtime routes a tap), or `--maestro-selector <yaml>`.
 *  - One screen: a positional log file argument, `--session DIR --step N` (default: last step),
 *    or `--live` for the connected device's current screen — the one `snapshot --all` prints,
 *    refs included.
 *  - `--batch <file.jsonl>` instead, to ask many questions in one call: one JSON object per
 *    line with the same keys (`log`, `session`/`step`, or `live: true`, then `ref` / `at` / `maestroSelector`,
 *    optional `max` / `anchor`), answered with one JSON object per line. Each screen is decoded
 *    once however many lines name it.
 *
 * ## Output
 *
 * One bare [TrailblazeNodeSelector] YAML block per computed candidate, most-stable first, each
 * with a comment naming the strategy and the live match count. `--json` (and every `--batch`
 * line) prints the same menu as one JSON object instead, whose `selector` values feed straight
 * back into `selector match --selector`.
 */
@Command(
  name = "suggest",
  mixinStandardHelpOptions = true,
  description = [
    "Suggest selectors for one element of a captured screen, or the device's (--live) — the whole menu.",
    "Name the element by --ref (from `trailblaze snapshot --all`), by --at x,y, or by a " +
    "legacy --maestro-selector. Prints EVERY strategy that computes a selector resolving to " +
    "the element, each with its strategy name + live match count, ranked most-stable first.",
  ],
)
open class SelectorSuggestCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    description = [
      "Path to a *_TrailblazeLlmRequestLog.json (required unless --session or --batch is given).",
    ],
  )
  var positionalLogFile: File? = null

  @Option(
    names = ["--ref"],
    description = [
      "Element ref from the captured tree (e.g. 'a812'). " +
      "Exactly one of --ref, --at, or --maestro-selector must be provided.",
    ],
  )
  var ref: String? = null

  @Option(
    names = ["--at", "--xy"],
    description = [
      "Screen-coordinate pair `x,y` (in device pixels) identifying the target element. " +
      "The frontmost interactive node whose bounds contain the point is selected via " +
      "the same hit-test the runtime uses for taps. Useful for the deterministic " +
      "Maestro-selector → accessibility-selector migration: resolve the Maestro selector " +
      "to a center coordinate, then ask this command for an accessibility selector that " +
      "covers the same node. Mutually exclusive with --ref / --maestro-selector.",
    ],
  )
  var at: String? = null

  @Option(
    names = ["--maestro-selector"],
    description = [
      "Inline TrailblazeElementSelector YAML (the legacy flat selector with fields like " +
      "`textRegex`, `idRegex`, `accessibilityTextRegex`, `index`, `enabled`, etc.) — the " +
      "shape used by the older Maestro-driver tap recordings. The selector is resolved " +
      "against the captured `viewHierarchy` (Maestro tree) using the same matcher the " +
      "runtime taps use; the resulting node's CENTER coordinate is then hit-tested " +
      "against the captured `trailblazeNodeTree` (accessibility tree) to find the " +
      "accessibility node that covers the same on-screen element. The output is the " +
      "same selector cascade as `--ref` / `--at`, but starting from a Maestro selector. " +
      "This is the deterministic Maestro→accessibility migration primitive. " +
      "Mutually exclusive with --ref / --at.",
    ],
  )
  var maestroSelector: String? = null

  @Option(
    names = ["--session"],
    description = ["Session log directory. Steps count every screen capture in it that has an element tree, oldest first."],
  )
  var session: File? = null

  @Option(
    names = ["--step"],
    description = ["1-based step within --session (default: last step)"],
  )
  var step: Int? = null

  @Option(
    names = ["--live"],
    description = [
      "Read the connected device's current screen instead of a log: the screen " +
        "`trailblaze snapshot --all` shows, so its refs resolve here. With --batch, lets lines " +
        "read it with `live: true`.",
    ],
  )
  var live: Boolean = false

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  /** How `--live` reads the device. Tests swap in a fake screen. */
  internal var liveScreenProvider: LiveScreenProvider = DaemonLiveScreenProvider

  @Option(
    names = ["--max"],
    description = ["Maximum candidate selectors to print (default: 25 — enough to show them all)"],
  )
  var max: Int = 25

  @Option(
    names = ["--anchor"],
    description = [
      "Compose the leaf selector with an ancestor predicate. Currently supported: " +
      "  parent-selected — find the nearest ancestor with isSelected=true and emit a " +
      "    selector that matches that ancestor as a `View` with `isSelected: true`, " +
      "    using the leaf as `containsChild`. This is the canonical bottom-nav-tab " +
      "    waypoint pattern: any app with selectable bottom-nav tabs uses this to " +
      "    pin identity to the *currently active* tab rather than to any tab with the " +
      "    given label. Without the anchor, the leaf selector matches a tab regardless " +
      "    of selection state — fine for tap targets, wrong for waypoint identity, " +
      "    because we want to know WHICH tab is currently active.",
    ],
  )
  var anchor: String? = null

  @Option(
    names = ["--json"],
    description = ["Print the menu as one JSON object instead of commented YAML."],
  )
  var json: Boolean = false

  @Option(
    names = ["--batch"],
    paramLabel = "<file.jsonl>",
    description = [
      "Answer many questions in one call. Each line is a JSON object with the same keys as " +
      "the flags: `log` (or `session` + optional `step`, or `live: true` with --live), one of `ref` / `at` / " +
      "`maestroSelector`, and optional `max` / `anchor` (defaulting to this command's " +
      "--max / --anchor). Prints one JSON object per line, in order; a line that can't be " +
      "answered prints {\"line\":N,\"error\":...} and the rest still run (exit 3 at the end, 2 if it failed unexpectedly).",
    ],
  )
  var batch: File? = null

  override fun call(): Int {
    batch?.let { return runBatch(it) }
    val screens = screenCache()
    return try {
      if (live && (positionalLogFile != null || session != null)) {
        throw SelectorInputException("Pass one screen: a log file, --session, or --live.")
      }
      val screen = if (live) {
        SelectorScreen.Live
      } else {
        positionalLogFile?.let { SelectorScreen.Log(requireLogFile(it)) }
          ?: session?.let { SelectorScreen.Log(screens.sessionStep(it, step)) }
          ?: throw SelectorInputException("Provide a positional log file argument, --session [--step], or --live.")
      }
      val result = suggest(
        SuggestRequest(screen, ref, at, maestroSelector, max, anchor),
        screens,
      )
      if (json) {
        Console.log(selectorCliJson.encodeToString(SuggestJson.serializer(), result.toJson(line = null)))
      } else {
        printMenu(result)
      }
      TrailblazeExitCode.SUCCESS.code
    } catch (e: SelectorInputException) {
      reportSelectorInputError(e)
      TrailblazeExitCode.MISUSE.code
    } catch (e: IncompleteCaptureException) {
      e.message.orEmpty().lines().forEach { Console.error(it) }
      TrailblazeExitCode.INFRA_FAILED.code
    } catch (e: LiveScreenUnavailable) {
      e.exitCode
    }
  }

  private fun screenCache() = ScreenStateCache(live = { liveScreenProvider.capture(device) })

  private fun runBatch(batchFile: File): Int {
    val lines = try {
      readBatchLines(batchFile)
    } catch (e: SelectorInputException) {
      reportSelectorInputError(e)
      return TrailblazeExitCode.MISUSE.code
    }
    val cache = screenCache()
    var exit = TrailblazeExitCode.SUCCESS.code
    for ((lineNo, parsed) in lines) {
      val out = try {
        val request = parsed.getOrThrow().toSuggestRequest(cache)
        suggest(request, cache).toJson(line = lineNo)
      } catch (e: LiveScreenUnavailable) {
        // Why is already on stderr; the cache keeps the failure, so later live lines fail fast.
        exit = chooseWorseExitCode(exit, e.exitCode)
        SuggestJson(line = lineNo, error = LIVE_SCREEN_UNAVAILABLE)
      } catch (e: SelectorInputException) {
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.MISUSE.code)
        SuggestJson(line = lineNo, error = e.message)
      } catch (e: IncompleteCaptureException) {
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.INFRA_FAILED.code)
        SuggestJson(line = lineNo, error = e.message)
      } catch (e: Exception) {
        // One line's unexpected failure must not cost the lines after it their answers.
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.INFRA_FAILED.code)
        SuggestJson(line = lineNo, error = "unexpected failure: $e")
      }
      Console.log(selectorCliJson.encodeToString(SuggestJson.serializer(), out))
    }
    return exit
  }

  private fun JsonObject.toSuggestRequest(screens: ScreenStateCache): SuggestRequest {
    val wantsLive = wantsLiveScreen(liveAllowed = live)
    val named = listOf("log", "session").filter { field(it) != null } + listOfNotNull("live".takeIf { wantsLive })
    if (named.size > 1) {
      throw SelectorInputException("each line names one screen; this one has ${named.joinToString(" and ")}")
    }
    val screen = stringField("log")?.let { SelectorScreen.Log(requireLogFile(File(it))) }
      ?: stringField("session")?.let { SelectorScreen.Log(screens.sessionStep(File(it), intField("step"))) }
      ?: SelectorScreen.Live.takeIf { wantsLive }
      ?: throw SelectorInputException("each line needs \"log\", \"session\" or \"live\": true")
    return SuggestRequest(
      screen = screen,
      ref = stringField("ref"),
      at = stringField("at") ?: stringField("xy"),
      maestroSelector = stringField("maestroSelector"),
      max = intField("max") ?: max,
      anchor = stringField("anchor") ?: anchor,
    )
  }

  private fun printMenu(result: SuggestResult) {
    val request = result.request
    val sourceDesc = request.ref?.let { "ref: $it" }
      ?: request.at?.let { "at: $it" }
      ?: "maestro-selector (resolved → coords)"
    Console.log("# Element $sourceDesc")
    Console.log("# Source: ${request.screen.displayName}")
    Console.log("# ${describeNode(result.target)}")
    result.target.ref?.let { Console.log("# Resolved ref: $it") }
    result.notes.forEach { Console.log("# $it") }
    Console.log("")

    val candidates = result.candidates
    if (candidates.isEmpty()) {
      // A truly empty result is a rare edge case (even an attribute-less node still gets a
      // bare global-index candidate) — handled with guidance rather than erroring.
      Console.log("# No paste-worthy selector could be computed for this element.")
      Console.log("# It has no stable identity (resource id), no usable semantic label")
      Console.log("# (blank, editable, or a run-variable amount/count with no stable head),")
      Console.log("# and no matchable type. Pick a different ref: a labeled or id-bearing element.")
    } else {
      Console.log("# ${candidates.size} selector(s) computed for this element, most-stable first.")
      Console.log("# Each line shows the strategy that produced it and how many nodes it matches now.")
      Console.log("# A waypoint wants presence (matches >= 1); a tap wants a unique match (matches 1).")
      Console.log("# If a selector matches >1 node, an index- or hierarchy-qualified variant that")
      Console.log("# resolves it uniquely usually appears further down — that's not a separate bug,")
      Console.log("# it's this list telling you disambiguation was needed.")
      Console.log("")
      candidates.forEachIndexed { idx, c ->
        val marker = if (c.named.isBest) " (best)" else ""
        val count = when (c.matches) {
          1 -> "matches 1 (unique)"
          else -> "matches ${c.matches}"
        }
        Console.log("# [${idx + 1}] Strategy: ${c.named.strategy} · $count$marker")
        printSelectorYaml(c.named.selector)
        Console.log("")
      }
    }

    if (request.anchor != null) {
      Console.log("")
      val anchored = result.anchored
      if (anchored == null) {
        val whichTarget = request.ref?.let { "ref '$it'" } ?: request.at?.let { "point ($it)" } ?: "target"
        Console.log("# --anchor=parent-selected: no ancestor with isSelected=true above $whichTarget.")
        Console.log("# Drop the flag, or pick a target whose tab is currently active.")
      } else {
        Console.log("# Anchored: parent isSelected + this as containsChild")
        Console.log("# This is the canonical bottom-nav-tab pattern — only matches when this tab is the active one.")
        printSelectorYaml(anchored)
      }
    }
  }

  /**
   * Prints just the [TrailblazeNodeSelector] body — no `WaypointCondition` wrapper
   * (`description:` / `selector:` keys), so it pastes into a waypoint, a trailhead, an ad-hoc
   * `tool tap --selector`, or anywhere else a [TrailblazeNodeSelector] is accepted.
   */
  private fun printSelectorYaml(selector: TrailblazeNodeSelector) {
    TrailblazeNodeSelectorYamlEmitter.emit(selector, indent = 4) { Console.log(it) }
  }
}

/**
 * `trailblaze waypoint suggest-selector` — the command's original name, kept hidden so existing
 * scripts and skills keep working.
 */
@Command(
  name = "suggest-selector",
  hidden = true,
  mixinStandardHelpOptions = true,
  description = ["Renamed to `trailblaze selector suggest`; this name still works."],
)
class WaypointSuggestSelectorCommand : SelectorSuggestCommand()

/** One `selector suggest` question: which screen, which element, and how much to print. */
internal data class SuggestRequest(
  val screen: SelectorScreen,
  val ref: String?,
  val at: String?,
  val maestroSelector: String?,
  val max: Int = 25,
  val anchor: String? = null,
)

internal data class SuggestCandidate(
  val named: TrailblazeNodeSelectorGenerator.NamedSelector,
  val matches: Int,
)

internal data class SuggestResult(
  val request: SuggestRequest,
  val target: TrailblazeNode,
  val candidates: List<SuggestCandidate>,
  /** The `--anchor` composition; null when requested but no selected ancestor exists. */
  val anchored: TrailblazeNodeSelector?,
  /** Extra context for the reader, e.g. where a Maestro selector resolved to. */
  val notes: List<String>,
)

@Serializable
internal data class SuggestCandidateJson(
  val strategy: String,
  val matches: Int,
  val best: Boolean = false,
  val selector: TrailblazeNodeSelector,
)

@Serializable
internal data class SuggestJson(
  val line: Int? = null,
  val log: String? = null,
  val live: Boolean? = null,
  val ref: String? = null,
  val at: String? = null,
  val maestroSelector: String? = null,
  val target: SelectorNodeJson? = null,
  val candidates: List<SuggestCandidateJson>? = null,
  val anchored: TrailblazeNodeSelector? = null,
  val notes: List<String>? = null,
  val error: String? = null,
)

internal fun SuggestResult.toJson(line: Int?) = SuggestJson(
  line = line,
  log = (request.screen as? SelectorScreen.Log)?.file?.path,
  live = true.takeIf { request.screen == SelectorScreen.Live },
  ref = request.ref,
  at = request.at,
  maestroSelector = request.maestroSelector,
  target = target.toSelectorNodeJson(),
  candidates = candidates.map {
    SuggestCandidateJson(it.named.strategy, it.matches, it.named.isBest, it.named.selector)
  },
  anchored = anchored,
  notes = notes.takeIf { it.isNotEmpty() },
)

/**
 * Answers one [SuggestRequest]: loads the screen, resolves the element, and computes the menu.
 * Throws [SelectorInputException] for any input it can't answer.
 */
internal fun suggest(request: SuggestRequest, screens: ScreenStateCache): SuggestResult {
  val provided = listOfNotNull(
    request.ref?.let { "--ref" },
    request.at?.let { "--at" },
    request.maestroSelector?.let { "--maestro-selector" },
  )
  if (provided.size != 1) {
    throw SelectorInputException(
      if (provided.isEmpty()) {
        "Provide exactly one of --ref, --at, or --maestro-selector (none given)."
      } else {
        "Provide exactly one of --ref, --at, or --maestro-selector (got: ${provided.joinToString(", ")})."
      },
    )
  }
  if (request.max < 1) {
    throw SelectorInputException("--max must be at least 1, got ${request.max}.")
  }
  if (request.anchor != null && request.anchor != ANCHOR_PARENT_SELECTED) {
    throw SelectorInputException("Unknown --anchor mode: '${request.anchor}'. Supported: $ANCHOR_PARENT_SELECTED.")
  }

  val source = request.screen.displayName
  val screen = screens.load(request.screen)
  // Prefer the dedicated migration tree from migration-mode captures
  // (`trailblaze.captureSecondaryTree=true`) — that's always accessibility-shape regardless of
  // which driver actually ran the test (mirrors WaypointMigrateTrailCommand.tryResolveInLog).
  // --ref is the exception; see [selectResolutionTree].
  val migrationTree = (screen as? MigrationScreenState)?.driverMigrationTreeNode
  val tree = selectResolutionTree(migrationTree, screen.trailblazeNodeTree, refRequested = request.ref != null)
    ?: throw if (request.screen is SelectorScreen.Live) {
      unanswerable(request.screen, "$source has no trailblazeNodeTree.")
    } else {
      SelectorInputException(
        "$source has no trailblazeNodeTree. Pick a log file from a step that captured an accessibility tree.",
      )
    }

  val notes = mutableListOf<String>()
  // Completeness describes the primary capture only; a migration tree is captured separately.
  if (tree === screen.trailblazeNodeTree) refuseIncompleteCapture(request.screen, screen)
  val target = when {
    request.ref != null -> tree.findFirstByRef(request.ref) ?: throw SelectorInputException(
      "Ref not found in tree: '${request.ref}' ($source)\n" +
        if (tree.aggregate().none { it.ref != null }) {
          // Web snapshots print the browser's own refs, which no capture tree records.
          "Hint: this capture's tree carries no refs at all (a web page's never does); " +
            "name the element with --at x,y --json instead (web selectors print only as JSON)."
        } else {
          "Hint: cross-check with `trailblaze snapshot --all` — refs are " +
            "tree-capture-local and don't survive across captures."
        },
    )
    request.at != null -> targetAt(tree, screen, source, request.at)
    else -> targetFromMaestroSelector(request.maestroSelector!!, tree, screen, source, notes)
  }

  // The device resolves against the important-for-accessibility tree first and the whole tree
  // only when nothing matches there. So build candidates in that tree when the target is in it
  // (an index then counts the siblings the device sees), add the whole tree's, keep only those
  // that reach the target the device's way, rank the merged list, and only then apply --max.
  val filtered = tree.filterImportantForAccessibility()
  val targetInFiltered = filtered.aggregate().firstOrNull { it.nodeId == target.nodeId }
  val scopes = buildList {
    if (filtered != tree) targetInFiltered?.let { add(filtered to it) }
    add(tree to target)
  }
  val enumerated = scopes
    .flatMap { (root, node) ->
      TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root = root, target = node, maxResults = Int.MAX_VALUE)
    }
    .distinctBy { it.selector }
  val reachTarget = enumerated.mapNotNull { named ->
    val matched = matchedNodes(tree, named.selector)
    SuggestCandidate(named, matched.size).takeIf { matched.any { it.nodeId == target.nodeId } }
  }
  if (reachTarget.size < enumerated.size) {
    notes += "${enumerated.size - reachTarget.size} selector(s) left out: on the device they resolve to a different element."
  }
  val matchCounts = reachTarget.associate { it.named.selector to it.matches }
  val candidates = TrailblazeNodeSelectorGenerator.rankByStability(reachTarget.map { it.named })
    .take(request.max)
    .map { SuggestCandidate(it, matchCounts.getValue(it.selector)) }

  // The anchor is a waypoint selector, and a waypoint capture holds only the
  // important-for-accessibility tree, so the anchor is built there.
  val anchored = request.anchor?.let {
    if (targetInFiltered == null) {
      notes += "No --anchor: this element is not important for accessibility, so a waypoint capture leaves it out."
      null
    } else {
      parentSelectedAnchor(filtered, targetInFiltered)
    }
  }

  return SuggestResult(
    request = request,
    target = target,
    candidates = candidates,
    anchored = anchored,
    notes = notes,
  )
}

private const val ANCHOR_PARENT_SELECTED = "parent-selected"

private fun matchedNodes(tree: TrailblazeNode, selector: TrailblazeNodeSelector): List<TrailblazeNode> =
  when (val result = resolveImportantForAccessibilityFirst(tree, selector)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
  }

private fun targetAt(tree: TrailblazeNode, screen: ScreenState, source: String, coord: String): TrailblazeNode {
  val (x, y) = parseCoord(coord)
  // Bounds-check before the hit-test: the matcher returns null on miss either way, but an
  // out-of-range coord is almost always a user error (negative, off-screen, swapped axis), so
  // surface it with a clearer message than the generic "no node contains".
  val w = screen.deviceWidth
  val h = screen.deviceHeight
  if (w > 0 && h > 0 && (x < 0 || y < 0 || x >= w || y >= h)) {
    throw SelectorInputException(
      "Coordinate ($x, $y) is outside device bounds (${w}x$h) for $source. " +
        "Verify x,y are device pixels, not normalised units, and not swapped.",
    )
  }
  return tree.hitTest(x, y) ?: throw SelectorInputException(
    "No node contains point ($x, $y) in tree from $source.\n" +
      "Verify the coordinates are in device pixels, not normalised units.",
  )
}

/**
 * Two-tree pipeline:
 *
 *  1. Parse the inline YAML into a `TrailblazeElementSelector` (the legacy flat selector
 *     shape used by historical Maestro-driver tap recordings).
 *  2. Resolve it against the captured `viewHierarchy` (Maestro tree) using the same matcher
 *     the runtime uses for taps — `TapSelectorV2.findNodeCenterUsingSelector` returns the
 *     CENTER coordinate of the matched element, which is exactly what we'd tap.
 *  3. Hit-test that coordinate against the captured `trailblazeNodeTree` (accessibility
 *     tree) to find the accessibility node covering the same on-screen element. Re-using
 *     the same `hitTest` semantics the accessibility runtime applies for routing taps means
 *     the resulting node is the one a runtime tap at the SAME coordinate would hit.
 *
 * That coordinate handoff is what makes the migration deterministic: it doesn't depend on
 * the LLM's interpretation of the screen, it only depends on the two trees agreeing on
 * "what's at point (x, y)" — which both drivers do by construction, since both derive from
 * the underlying accessibility service.
 */
private fun targetFromMaestroSelector(
  yaml: String,
  tree: TrailblazeNode,
  screen: ScreenState,
  source: String,
  notes: MutableList<String>,
): TrailblazeNode {
  val selector = try {
    TrailblazeYaml.defaultYamlInstance.decodeFromString(TrailblazeElementSelector.serializer(), yaml)
  } catch (e: Exception) {
    throw SelectorInputException(
      "Could not parse --maestro-selector YAML: ${e.message}\n" +
        "Expected a TrailblazeElementSelector body, e.g.:\n" +
        "  --maestro-selector '{ idRegex: \"^foo$\", textRegex: \"^Bar$\" }'",
    )
  }

  if (screen.deviceWidth <= 0 || screen.deviceHeight <= 0) {
    throw SelectorInputException(
      "Log has no device dimensions (width=${screen.deviceWidth}, height=${screen.deviceHeight}); " +
        "cannot resolve Maestro selector.\nPick a log step whose deviceWidth/deviceHeight are populated.",
    )
  }

  // The matcher throws, rather than returning null, for some misses (a `childOf` parent that
  // isn't on screen), so both shapes of "not found" become the same input error.
  val center = try {
    TapSelectorV2.findNodeCenterUsingSelector(
      root = screen.viewHierarchy,
      selector = selector,
      trailblazeDevicePlatform = screen.trailblazeDevicePlatform,
      widthPixels = screen.deviceWidth,
      heightPixels = screen.deviceHeight,
    )
  } catch (e: Exception) {
    throw SelectorInputException(
      "Maestro selector could not be resolved in viewHierarchy: ${e.message}\nSource: $source\nSelector: $yaml",
    )
  }
  val (cx, cy) = center ?: throw SelectorInputException(
    "Maestro selector did not match any element in viewHierarchy.\nSource: $source\nSelector: $yaml",
  )
  notes += "Maestro selector resolved to viewHierarchy node at ($cx, $cy)"

  return tree.hitTest(cx, cy) ?: throw SelectorInputException(
    "Maestro selector resolved to ($cx, $cy) but no accessibility node covers that point. " +
      "The two trees may have drifted (e.g. one was captured before a layout change). " +
      "Verify the same step produced both trees.",
  )
}

/**
 * Anchor composition. The default cascade returns selectors that uniquely identify the *leaf*
 * (e.g. the View with `contentDescription="Money"`). For bottom-nav tab waypoints we want a
 * stronger predicate: "the bottom-nav Money tab is currently selected." That requires composing
 * the leaf with an ancestor's `isSelected: true` — `findAllValidSelectors` won't emit it because
 * the leaf is already unique without it (and isSelected is deliberately excluded from the
 * structural generator on the grounds that selection is transient — true for tap-recording, but
 * exactly the signal we want for a waypoint that should ONLY match when the user is on this tab).
 *
 * Returns null when no ancestor is selected (or the nodes aren't accessibility-shape).
 */
private fun parentSelectedAnchor(tree: TrailblazeNode, target: TrailblazeNode): TrailblazeNodeSelector? {
  val parentMap = mutableMapOf<Long, TrailblazeNode>()
  fun rec(parent: TrailblazeNode) {
    for (c in parent.children) {
      parentMap[c.nodeId] = parent
      rec(c)
    }
  }
  rec(tree)
  val ancestor = generateSequence(parentMap[target.nodeId]) { parentMap[it.nodeId] }.firstOrNull { node ->
    (node.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.isSelected == true
  } ?: return null
  val ancestorDetail = ancestor.driverDetail as DriverNodeDetail.AndroidAccessibility
  val leafDetail = target.driverDetail as? DriverNodeDetail.AndroidAccessibility ?: return null
  // Prefer textRegex if present, fall back to contentDescriptionRegex.
  val leafTextRegex = leafDetail.text?.let { "^" + Regex.escape(it) + "$" }
  val leafContentDescriptionRegex = leafDetail.contentDescription
    ?.takeIf { leafDetail.text == null }
    ?.let { "^" + Regex.escape(it) + "$" }
  return TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(
      classNameRegex = ancestorDetail.className?.let { escapeForIdentifier(it) },
      isSelected = true,
    ),
    containsChild = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        classNameRegex = leafDetail.className?.let { escapeForIdentifier(it) },
        textRegex = leafTextRegex,
        contentDescriptionRegex = leafContentDescriptionRegex,
      ),
    ),
  )
}

/** Parses a `x,y` coord string. Whitespace tolerated. */
private fun parseCoord(s: String): Pair<Int, Int> {
  val parts = s.split(',').map { it.trim() }
  if (parts.size != 2) {
    throw SelectorInputException("Invalid --at value: '$s'. Expected `x,y` (e.g. `540,1200`).")
  }
  val x = parts[0].toIntOrNull()
  val y = parts[1].toIntOrNull()
  if (x == null || y == null) {
    throw SelectorInputException("Invalid --at value: '$s'. Both x and y must be integers.")
  }
  return x to y
}

/**
 * Walks the tree depth-first and returns the first node whose [TrailblazeNode.ref] equals
 * [ref]. Refs are unique within a single capture (by construction in
 * `CompactElementListUtils`), so first-hit is exact-hit.
 */
private fun TrailblazeNode.findFirstByRef(ref: String): TrailblazeNode? {
  if (this.ref == ref) return this
  for (child in children) {
    child.findFirstByRef(ref)?.let { return it }
  }
  return null
}

/**
 * Picks which captured tree `--ref` / `--at` / `--maestro-selector` resolve against.
 *
 * [migrationTree] (`MigrationScreenState.driverMigrationTreeNode`) is always accessibility-shape
 * regardless of which driver actually ran the test, so it's preferred for `--at` /
 * `--maestro-selector` on a dual-tree migration capture. `--ref` is the exception: refs are
 * assigned only onto [primaryTree] (`ScreenState.trailblazeNodeTree`, via
 * `CompactScreenElements.applyRefsToTree`) — the raw [migrationTree] never gets refs applied, so
 * resolving a ref against it always misses. [refRequested] routes around that.
 */
internal fun selectResolutionTree(
  migrationTree: TrailblazeNode?,
  primaryTree: TrailblazeNode?,
  refRequested: Boolean,
): TrailblazeNode? = migrationTree.takeIf { !refRequested } ?: primaryTree
