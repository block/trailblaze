package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.util.escapeForIdentifier
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.cli.yaml.TrailblazeNodeSelectorYamlEmitter
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.yaml.TrailblazeYaml
import java.io.File
import java.util.concurrent.Callable

/**
 * Suggest selectors for a specific element ref captured in a session log.
 *
 * The hard part of authoring any selector by hand — for a `*.waypoint.yaml`, a shortcut, an
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
 * shortcut, or any other selector-accepting spot.
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
 * So the command enumerates *everything computable* — resource id, each text field, text+class,
 * structural class, `childOf` a labeled parent, `containsChild` a labeled descendant, spatial,
 * index-qualified variants — labels each with its strategy and live match count, and ranks them
 * stable-first (identity → text → structural → childOf → containsChild → spatial, with any
 * index-qualified selector sorting after its non-indexed sibling). It also adds one option the
 * raw cascade never produces: the semantic text with its run-variable tail wildcarded
 * (`Balance: $0.00` -> `Balance:.*`), so a text signal survives the next run.
 *
 * Nothing computable is hidden — when a semantic label repeats on screen, the plain selector
 * (`matches 2`) shows up right next to an index- or hierarchy-qualified variant that resolves it
 * uniquely (`matches 1`); the mere presence of that qualified variant in the menu is the signal
 * that the plain one wasn't unique. The author picks whichever fits their intent — presence for
 * a waypoint, uniqueness for a tap. See
 * [TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates].
 *
 * ## Inputs
 *
 *  - `--ref <X>` — the short ref shown by `snapshot` / the LLM context (`a812`, `n220b`, …).
 *    Required (or `--at` / `--maestro-selector`).
 *  - Either a positional log file argument or `--session DIR --step N`, mirroring the input
 *    surface of `waypoint validate`. Same loader — `SessionLogScreenState.loadStep` — so any
 *    file that command accepts works here too. The default `--step` is "last step in the
 *    session," matching `waypoint validate`.
 *  - `--max <N>` — cap the number printed (default 25 — enough to show them all).
 *
 * ## Output
 *
 * One bare [TrailblazeNodeSelector] YAML block per computed candidate, most-stable first, each
 * with a comment naming the strategy and the live match count — just the selector body
 * (`iosMaestro:`, `androidAccessibility:`, `index:`, …), no `WaypointCondition` wrapper
 * (`description:` / `selector:` keys). The author drops the block in wherever a selector goes;
 * a waypoint's `required:`/`forbidden:` entry additionally wants a `description:` and optional
 * `minCount:` around it, which the author adds by hand for whichever candidate they picked.
 */
@Command(
  name = "suggest-selector",
  mixinStandardHelpOptions = true,
  description = [
    "Suggest selector YAML for a specific element ref in a captured screen — the whole menu.",
    "Pair with `./trailblaze snapshot --all` to see refs, then run this on the matching",
    "session log to translate ref → selector. Prints EVERY strategy that computes a selector",
    "resolving to the target (resource id, text, structural, childOf, containsChild, spatial,",
    "index-qualified variants, plus a run-variable-wildcarded text variant), each with its",
    "strategy name + live match count, ranked most-stable first. Nothing computable is hidden.",
  ],
)
class WaypointSuggestSelectorCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    description = [
      "Path to a *_TrailblazeLlmRequestLog.json (required unless --session/--step given).",
      "Same shape as the input to `waypoint validate`.",
    ],
  )
  var positionalLogFile: File? = null

  @Option(
    names = ["--ref"],
    description = [
      "Element ref from the captured tree (e.g. 'a812').",
      "Mutually exclusive with --at; exactly one must be provided.",
    ],
  )
  var ref: String? = null

  @Option(
    names = ["--at"],
    description = [
      "Screen-coordinate pair `x,y` (in device pixels) identifying the target element.",
      "The frontmost interactive node whose bounds contain the point is selected via",
      "the same hit-test the runtime uses for taps. Useful for the deterministic",
      "Maestro-selector → accessibility-selector migration: resolve the Maestro selector",
      "to a center coordinate, then ask this command for an accessibility selector that",
      "covers the same node. Mutually exclusive with --ref / --maestro-selector.",
    ],
  )
  var at: String? = null

  @Option(
    names = ["--maestro-selector"],
    description = [
      "Inline TrailblazeElementSelector YAML (the legacy flat selector with fields like",
      "`textRegex`, `idRegex`, `accessibilityTextRegex`, `index`, `enabled`, etc.) — the",
      "shape used by the older Maestro-driver tap recordings. The selector is resolved",
      "against the captured `viewHierarchy` (Maestro tree) using the same matcher the",
      "runtime taps use; the resulting node's CENTER coordinate is then hit-tested",
      "against the captured `trailblazeNodeTree` (accessibility tree) to find the",
      "accessibility node that covers the same on-screen element. The output is the",
      "same selector cascade as `--ref` / `--at`, but starting from a Maestro selector.",
      "This is the deterministic Maestro→accessibility migration primitive.",
      "Mutually exclusive with --ref / --at.",
    ],
  )
  var maestroSelector: String? = null

  @Option(
    names = ["--session"],
    description = ["Session log directory (containing *_TrailblazeLlmRequestLog.json files)"],
  )
  var session: File? = null

  @Option(
    names = ["--step"],
    description = ["1-based step within --session (default: last step)"],
  )
  var step: Int? = null

  @Option(
    names = ["--max"],
    description = ["Maximum candidate selectors to print (default: 25 — enough to show them all)"],
  )
  var max: Int = 25

  @Option(
    names = ["--anchor"],
    description = [
      "Compose the leaf selector with an ancestor predicate. Currently supported:",
      "  parent-selected — find the nearest ancestor with isSelected=true and emit a",
      "    selector that matches that ancestor as a `View` with `isSelected: true`,",
      "    using the leaf as `containsChild`. This is the canonical bottom-nav-tab",
      "    waypoint pattern: any app with selectable bottom-nav tabs uses this to",
      "    pin identity to the *currently active* tab rather than to any tab with the",
      "    given label. Without the anchor, the leaf selector matches a tab regardless",
      "    of selection state — fine for tap targets, wrong for waypoint identity,",
      "    because we want to know WHICH tab is currently active.",
    ],
  )
  var anchor: String? = null

  override fun call(): Int {
    val logFile = resolveLogFile() ?: return TrailblazeExitCode.MISUSE.code
    val screen = SessionLogScreenState.loadStep(logFile)
    val tree = screen.trailblazeNodeTree ?: run {
      reportCliError(
        verb = "Suggest selector",
        target = logFile.name,
        reason = "log has no trailblazeNodeTree",
        hint = "pick a log file from a step that captured an accessibility tree",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    val target = resolveTarget(tree, screen, logFile) ?: return TrailblazeExitCode.MISUSE.code

    val sourceDesc = ref?.let { "ref: $it" }
      ?: at?.let { "at: $it" }
      ?: maestroSelector?.let { "maestro-selector (resolved → coords)" }
      ?: "?"
    Console.log("# Element $sourceDesc")
    Console.log("# Source: ${logFile.name}")
    Console.log("# ${describeNode(target)}")
    target.ref?.let { Console.log("# Resolved ref: $it") }
    Console.log("")

    emitSelectorCandidates(tree, target)

    // Anchor composition. The default cascade returns selectors that uniquely identify
    // the *leaf* (e.g. the View with `contentDescription="Money"`). For bottom-nav tab
    // waypoints we want a stronger predicate: "the bottom-nav Money tab is currently
    // selected." That requires composing the leaf with an ancestor's `isSelected: true`
    // — `findAllValidSelectors` won't emit it because the leaf is already unique without
    // it (and isSelected is deliberately excluded from the structural generator on the
    // grounds that selection is transient — true for tap-recording, but exactly the
    // signal we want for a waypoint that should ONLY match when the user is on this
    // tab).
    if (anchor != null) {
      Console.log("")
      emitAnchorSelector(tree, target)
    }

    return TrailblazeExitCode.SUCCESS.code
  }

  /**
   * Prints the full menu of selectors the generator can compute for the target — every
   * strategy that resolves to it, ranked most-stable first, each with its strategy name and
   * how many nodes it currently matches. Nothing computable is hidden: the positional `index`
   * fallback and the `containsChild` / `containsDescendants` family both appear when the
   * cascade produces them — they just rank last, and a repeated label's ambiguity is visible
   * as `matches 2` right next to the index-qualified variant that resolves it to `matches 1`.
   * See [TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates].
   *
   * A truly empty result is a rare edge case (even an attribute-less node still gets a bare
   * global-index candidate) — handled with guidance rather than erroring, defensively.
   */
  private fun emitSelectorCandidates(tree: TrailblazeNode, target: TrailblazeNode) {
    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(
      root = tree,
      target = target,
      maxResults = max,
    )

    if (candidates.isEmpty()) {
      Console.log("# No paste-worthy selector could be computed for this element.")
      Console.log("# It has no stable identity (resource id), no usable semantic label")
      Console.log("# (blank, editable, or a run-variable amount/count with no stable head),")
      Console.log("# and no matchable type. Pick a different ref: a labeled or id-bearing element.")
      return
    }

    Console.log("# ${candidates.size} selector(s) computed for this element, most-stable first.")
    Console.log("# Each line shows the strategy that produced it and how many nodes it matches now.")
    Console.log("# A waypoint wants presence (matches >= 1); a tap wants a unique match (matches 1).")
    Console.log("# If a selector matches >1 node, an index- or hierarchy-qualified variant that")
    Console.log("# resolves it uniquely usually appears further down — that's not a separate bug,")
    Console.log("# it's this list telling you disambiguation was needed.")
    Console.log("")

    candidates.forEachIndexed { idx, named ->
      val marker = if (named.isBest) " (best)" else ""
      Console.log("# [${idx + 1}] Strategy: ${named.strategy} · ${matchCountLabel(tree, named.selector)}$marker")
      printSelectorYaml(named.selector)
      Console.log("")
    }
  }

  /** Human label for how many nodes a selector currently resolves to. */
  private fun matchCountLabel(tree: TrailblazeNode, selector: TrailblazeNodeSelector): String =
    when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> "matches 1 (unique)"
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> "matches ${result.nodes.size}"
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> "matches 0"
    }

  private fun emitAnchorSelector(tree: TrailblazeNode, target: TrailblazeNode) {
    when (anchor) {
      "parent-selected" -> {
        val parentMap = buildParentMap(tree)
        val ancestor = walkUp(target, parentMap).firstOrNull { node ->
          (node.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.isSelected == true
        }
        if (ancestor == null) {
          val whichTarget = ref?.let { "ref '$it'" } ?: at?.let { "point ($it)" } ?: "target"
          Console.log("# --anchor=parent-selected: no ancestor with isSelected=true above $whichTarget.")
          Console.log("# Drop the flag, or pick a target whose tab is currently active.")
          return
        }
        val ancestorDetail = ancestor.driverDetail as DriverNodeDetail.AndroidAccessibility
        val leafDetail = target.driverDetail as? DriverNodeDetail.AndroidAccessibility ?: return
        // Build the anchor selector shape and route it through the shared emitter so any
        // future addition to the AndroidAccessibility field set picks up here for free
        // (same forcing-function guarantee as the regular candidate selectors emitted
        // above). The text-OR-contentDescription preference for the leaf matches the
        // previous hand-rolled output: prefer textRegex if present, fall back to
        // contentDescriptionRegex.
        val leafTextRegex = leafDetail.text?.let { "^" + Regex.escape(it) + "$" }
        val leafContentDescriptionRegex = leafDetail.contentDescription
          ?.takeIf { leafDetail.text == null }
          ?.let { "^" + Regex.escape(it) + "$" }
        val anchorSelector = TrailblazeNodeSelector(
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
        Console.log("# Anchored: parent isSelected + this as containsChild")
        Console.log("# This is the canonical bottom-nav-tab pattern — only matches when this tab is the active one.")
        printSelectorYaml(anchorSelector)
      }
      else -> Console.log("# Unknown --anchor mode: '$anchor'. Supported: parent-selected.")
    }
  }

  private fun buildParentMap(root: TrailblazeNode): Map<Long, TrailblazeNode> {
    val map = mutableMapOf<Long, TrailblazeNode>()
    fun rec(parent: TrailblazeNode) {
      for (c in parent.children) {
        map[c.nodeId] = parent
        rec(c)
      }
    }
    rec(root)
    return map
  }

  private fun walkUp(node: TrailblazeNode, parentMap: Map<Long, TrailblazeNode>): Sequence<TrailblazeNode> =
    generateSequence(parentMap[node.nodeId]) { parentMap[it.nodeId] }

  private fun resolveTarget(
    tree: TrailblazeNode,
    screen: ScreenState,
    logFile: File,
  ): TrailblazeNode? {
    // Mutual-exclusion: exactly one of --ref / --at / --maestro-selector must be provided.
    val provided = listOfNotNull(
      ref?.let { "--ref" },
      at?.let { "--at" },
      maestroSelector?.let { "--maestro-selector" },
    )
    if (provided.size != 1) {
      Console.error(
        if (provided.isEmpty()) {
          "Provide exactly one of --ref, --at, or --maestro-selector (none given)."
        } else {
          "Provide exactly one of --ref, --at, or --maestro-selector " +
            "(got: ${provided.joinToString(", ")})."
        },
      )
      return null
    }

    ref?.let { r ->
      val target = tree.findFirstByRef(r)
      if (target == null) {
        Console.error("Ref not found in tree: '$r' (log: ${logFile.name})")
        Console.error(
          "Hint: cross-check with `./trailblaze snapshot --all` — refs are " +
            "tree-capture-local and don't survive across captures.",
        )
      }
      return target
    }

    at?.let { coord ->
      val (x, y) = parseCoord(coord) ?: return null
      // Bounds-check before the hit-test: the matcher returns null on miss either way, but
      // an out-of-range coord is almost always a user error (negative, off-screen, swapped
      // axis), so surface it with a clearer message than the generic "no node contains".
      val w = screen.deviceWidth
      val h = screen.deviceHeight
      if (w > 0 && h > 0 && (x < 0 || y < 0 || x >= w || y >= h)) {
        Console.error(
          "Coordinate ($x, $y) is outside device bounds (${w}x$h) for ${logFile.name}. " +
            "Verify x,y are device pixels, not normalised units, and not swapped.",
        )
        return null
      }
      val target = tree.hitTest(x, y)
      if (target == null) {
        Console.error("No node contains point ($x, $y) in tree from ${logFile.name}.")
        Console.error("Verify the coordinates are in device pixels, not normalised units.")
      }
      return target
    }

    maestroSelector?.let { yaml ->
      return resolveFromMaestroSelector(yaml, tree, screen, logFile)
    }

    return null
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
   * the underlying accessibility service. Once the migration tool runs, every produced
   * accessibility selector is a node that the SAME tap coordinate would have hit on the
   * legacy Maestro path.
   */
  private fun resolveFromMaestroSelector(
    yaml: String,
    tree: TrailblazeNode,
    screen: ScreenState,
    logFile: File,
  ): TrailblazeNode? {
    val selector = try {
      TrailblazeYaml.defaultYamlInstance.decodeFromString(
        TrailblazeElementSelector.serializer(),
        yaml,
      )
    } catch (e: Exception) {
      Console.error("Could not parse --maestro-selector YAML: ${e.message}")
      Console.error("Expected a TrailblazeElementSelector body, e.g.:")
      Console.error("  --maestro-selector '{ idRegex: \"^foo$\", textRegex: \"^Bar$\" }'")
      return null
    }

    if (screen.deviceWidth <= 0 || screen.deviceHeight <= 0) {
      Console.error(
        "Log has no device dimensions (width=${screen.deviceWidth}, " +
          "height=${screen.deviceHeight}); cannot resolve Maestro selector.",
      )
      Console.error("Pick a log step whose deviceWidth/deviceHeight are populated.")
      return null
    }

    val center = TapSelectorV2.findNodeCenterUsingSelector(
      root = screen.viewHierarchy,
      selector = selector,
      trailblazeDevicePlatform = screen.trailblazeDevicePlatform,
      widthPixels = screen.deviceWidth,
      heightPixels = screen.deviceHeight,
    )
    if (center == null) {
      Console.error("Maestro selector did not match any element in viewHierarchy.")
      Console.error("Source log: ${logFile.name}")
      Console.error("Selector: $yaml")
      return null
    }
    val (cx, cy) = center
    Console.log("# Maestro selector resolved to viewHierarchy node at ($cx, $cy)")

    val target = tree.hitTest(cx, cy)
    if (target == null) {
      Console.error(
        "Maestro selector resolved to ($cx, $cy) but no accessibility node " +
          "covers that point. The two trees may have drifted (e.g. one was captured " +
          "before a layout change). Verify the same step produced both trees.",
      )
    }
    return target
  }

  /**
   * Parse a `x,y` coord string. Whitespace tolerated. Returns null and logs the parse
   * error on bad input — the caller treats null as a usage failure.
   */
  private fun parseCoord(s: String): Pair<Int, Int>? {
    val parts = s.split(',').map { it.trim() }
    if (parts.size != 2) {
      Console.error("Invalid --at value: '$s'. Expected `x,y` (e.g. `540,1200`).")
      return null
    }
    val x = parts[0].toIntOrNull()
    val y = parts[1].toIntOrNull()
    if (x == null || y == null) {
      Console.error("Invalid --at value: '$s'. Both x and y must be integers.")
      return null
    }
    return x to y
  }

  private fun resolveLogFile(): File? {
    positionalLogFile?.let { return validateLogFile(it, label = "Log file") }
    session?.let { return resolveFromSession(it) }
    Console.error("Provide either a positional log file argument or --session [--step].")
    return null
  }

  private fun resolveFromSession(sessionDir: File): File? {
    val validated = validateSessionDir(sessionDir) ?: return null
    val logs = SessionLogScreenState.listLlmRequestLogs(validated)
    if (logs.isEmpty()) {
      Console.error("No *_TrailblazeLlmRequestLog.json files found in: ${validated.absolutePath}")
      return null
    }
    val idx = step?.let { it - 1 } ?: (logs.size - 1)
    if (idx !in logs.indices) {
      Console.error("--step out of range: 1..${logs.size}")
      return null
    }
    return logs[idx]
  }

  private fun describeNode(target: TrailblazeNode): String {
    val parts = mutableListOf<String>()
    when (val d = target.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility -> {
        d.className?.substringAfterLast('.')?.let { parts += it }
        d.text?.takeIf { it.isNotBlank() }?.let { parts += "text=\"$it\"" }
        d.contentDescription?.takeIf { it.isNotBlank() }?.let { parts += "desc=\"$it\"" }
        d.resourceId?.let { parts += "id=\"$it\"" }
        if (d.isSelected) parts += "isSelected"
        if (d.isHeading) parts += "isHeading"
        if (d.isClickable) parts += "isClickable"
      }
      else -> parts += d::class.simpleName.orEmpty()
    }
    return if (parts.isEmpty()) "(no identifying properties)" else parts.joinToString(" ")
  }

  /**
   * Prints just the [TrailblazeNodeSelector] body — no `WaypointCondition` wrapper
   * (`description:` / `selector:` keys). The command's output is a menu of selectors for the
   * author to use however they need — pasted into a waypoint's `required:`/`forbidden:` list,
   * a shortcut, an ad-hoc CLI `tool tap --selector`, or anywhere else a
   * [TrailblazeNodeSelector] is accepted — so it prints the reusable core, not a shape tied to
   * one specific consumer.
   *
   * Hand-format rather than using the kaml `TrailblazeYaml` instance because:
   *  1. We want to control indent precisely (4-space, matching hand-authored waypoint files).
   *  2. We want `\Qfoo\E` literal blocks emitted verbatim (`\Q` / `\E` escape chars are valid
   *     YAML scalars but kaml's default scalar style escapes them in a way that makes the
   *     regex hard to read).
   *  3. The selector shape is shallow — driver match + optional spatial / hierarchy children —
   *     so the formatting recursion stays tiny and reads cleanly.
   */
  private fun printSelectorYaml(selector: TrailblazeNodeSelector) {
    TrailblazeNodeSelectorYamlEmitter.emit(selector, indent = 4) { Console.log(it) }
  }
}

/**
 * Walks the tree depth-first and returns the first node whose [TrailblazeNode.ref] equals
 * [ref]. Refs are unique within a single capture (by construction in
 * `CompactElementListUtils`), so first-hit is exact-hit; the function name documents the
 * search style for callers who'd otherwise wonder if they should iterate.
 */
private fun TrailblazeNode.findFirstByRef(ref: String): TrailblazeNode? {
  if (this.ref == ref) return this
  for (child in children) {
    child.findFirstByRef(ref)?.let { return it }
  }
  return null
}
