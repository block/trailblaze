package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.yaml.TrailblazeYaml
import java.io.File
import java.util.concurrent.Callable

/**
 * Suggest waypoint-ready selectors for a specific element ref captured in a session log.
 *
 * The hard part of authoring a `*.waypoint.yaml` is translating "this thing on the screen"
 * into "the right selector YAML." `./trailblaze snapshot --all` shows every element with a
 * short ref like `[a812]` next to its label, but the ref-to-selector translation has historically
 * been done by hand, with two failure modes that have actually shipped:
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
 * log, finds the node by ref, and runs the same `TrailblazeNodeSelectorGenerator` cascade the
 * runtime uses to pick a selector for a tap. The generator returns up to N **named** candidates
 * (text, content-description, resource id, hierarchy-anchored, etc.) — each verified against
 * the resolver to produce exactly one match. The author picks the candidate that best
 * communicates *why* this signal identifies the screen, then pastes its YAML into a
 * `required:` / `forbidden:` entry.
 *
 * ## Why a list, not just "the best"
 *
 * `findBestSelector` returns whichever simplest strategy uniquely resolves — usually the
 * resource id when one exists. That's the right call for tap recordings (machine consumes
 * it, stability matters) but the wrong call for waypoint authoring, where the human consumer
 * cares about semantic meaning. A bottom-nav tab might have an opaque resource id like
 * `com.squareup.development:id/nav_tab_0` and a content description like "Banking" — both
 * resolve uniquely, but only the latter carries meaning when a future agent reads the YAML.
 * The author should see both options and choose. (Per the same logic, the structural-only
 * candidate at the bottom of the output is the right pick for `forbidden:` clauses on sibling
 * waypoints — it doesn't depend on locale-bound text.)
 *
 * ## Inputs
 *
 *  - `--ref <X>` — the short ref shown by `snapshot` / the LLM context (`a812`, `n220b`, …).
 *    Required.
 *  - Either a positional log file argument or `--session DIR --step N`, mirroring the input
 *    surface of `waypoint validate`. Same loader — `SessionLogScreenState.loadStep` — so any
 *    file that command accepts works here too. The default `--step` is "last step in the
 *    session," matching `waypoint validate`.
 *  - `--max <N>` — cap the candidate count (default 5). Useful when the generator finds
 *    many valid candidates and the author only wants the top few.
 *
 * ## Output
 *
 * One pasteable `required:` block per candidate, with a comment line explaining the strategy
 * the generator used to produce it. The author trims to the entries they want and edits the
 * surrounding context (description, minCount). The structural-only candidate prints last so
 * authors who skim the bottom land on it for forbidden-clause use.
 */
@Command(
  name = "suggest-selector",
  mixinStandardHelpOptions = true,
  description = [
    "Suggest waypoint-ready selector YAML for a specific element ref in a captured screen.",
    "Pair with `./trailblaze snapshot --all` to see refs, then run this on the matching",
    "session log to translate ref → selector. Returns up to --max named candidates (the",
    "TrailblazeNodeSelectorGenerator strategies that uniquely resolve to the target),",
    "plus one structural-only candidate at the bottom for forbidden-clause use.",
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
    description = ["Element ref from the captured tree (e.g. 'a812'). Required."],
    required = true,
  )
  lateinit var ref: String

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
    description = ["Maximum candidate selectors to return (default: 5)"],
  )
  var max: Int = 5

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
    val logFile = resolveLogFile() ?: return CommandLine.ExitCode.USAGE
    val screen = SessionLogScreenState.loadStep(logFile)
    val tree = screen.trailblazeNodeTree ?: run {
      Console.error("Log has no trailblazeNodeTree: ${logFile.name}")
      Console.error("Pick a log file from a step that captured an accessibility tree.")
      return 1
    }

    val target = tree.findFirstByRef(ref) ?: run {
      Console.error("Ref not found in tree: '$ref' (log: ${logFile.name})")
      Console.error(
        "Hint: cross-check with `./trailblaze snapshot --all` — refs are " +
          "tree-capture-local and don't survive across captures.",
      )
      return 1
    }

    Console.log("# Element ref: $ref")
    Console.log("# Source: ${logFile.name}")
    Console.log("# ${describeNode(target)}")
    Console.log("")

    val candidates = TrailblazeNodeSelectorGenerator.findAllValidSelectors(
      root = tree,
      target = target,
      maxResults = max,
    )

    if (candidates.isEmpty()) {
      // findAllValidSelectors guarantees at least the index fallback, so this
      // path is mostly defensive — but we want the failure mode to be loud rather
      // than emitting silently-empty output.
      Console.error("No selectors generated for ref '$ref'. This shouldn't happen.")
      return 1
    }

    Console.log("# ${candidates.size} candidate selector(s), best-first.")
    Console.log("# Pick the one that best expresses *why* this signal identifies the screen.")
    Console.log("# Resource ids are most stable; text / contentDescription are most readable.")
    Console.log("")

    candidates.forEachIndexed { idx, named ->
      val marker = if (named.isBest) " (best)" else ""
      Console.log("# [${idx + 1}] Strategy: ${named.strategy}$marker")
      printSelectorYaml(named.selector)
      Console.log("")
    }

    // Structural-only candidate as a separate section. It's not in `candidates` because
    // findAllValidSelectors uses the full strategy cascade (which prefers text), while
    // findBestStructuralSelector deliberately excludes text/content. Different question,
    // different answer; both worth showing.
    val structural = TrailblazeNodeSelectorGenerator.findBestStructuralSelector(tree, target)
    Console.log("# Structural-only (text-independent — useful for `forbidden:` clauses on sibling")
    Console.log("# waypoints, or when locale changes the visible text):")
    Console.log("# Strategy: ${structural.strategy}")
    printSelectorYaml(structural.selector)

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

    return CommandLine.ExitCode.OK
  }

  private fun emitAnchorSelector(tree: TrailblazeNode, target: TrailblazeNode) {
    when (anchor) {
      "parent-selected" -> {
        val parentMap = buildParentMap(tree)
        val ancestor = walkUp(target, parentMap).firstOrNull { node ->
          (node.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.isSelected == true
        }
        if (ancestor == null) {
          Console.log("# --anchor=parent-selected: no ancestor with isSelected=true above ref '$ref'.")
          Console.log("# Drop the flag, or pick a ref whose tab is currently active.")
          return
        }
        val ancestorDetail = ancestor.driverDetail as DriverNodeDetail.AndroidAccessibility
        val leafDetail = target.driverDetail as? DriverNodeDetail.AndroidAccessibility ?: return
        Console.log("# Anchored: parent isSelected + this as containsChild")
        Console.log("# This is the canonical bottom-nav-tab pattern — only matches when this tab is the active one.")
        Console.log("- description: \"\"")
        Console.log("  selector:")
        Console.log("    androidAccessibility:")
        ancestorDetail.className?.let {
          Console.log("      classNameRegex: \"${escapeYamlString(escapeForYamlRegex(it))}\"")
        }
        Console.log("      isSelected: true")
        Console.log("    containsChild:")
        Console.log("      androidAccessibility:")
        leafDetail.className?.let {
          Console.log("        classNameRegex: \"${escapeYamlString(escapeForYamlRegex(it))}\"")
        }
        when {
          leafDetail.text != null -> Console.log(
            "        textRegex: \"${escapeYamlString("^" + Regex.escape(leafDetail.text!!) + "$")}\"",
          )
          leafDetail.contentDescription != null -> Console.log(
            "        contentDescriptionRegex: \"${escapeYamlString("^" + Regex.escape(leafDetail.contentDescription!!) + "$")}\"",
          )
        }
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

  /**
   * Escape a literal string for inclusion in a regex pattern (Java regex `\Q...\E`
   * equivalent of [Regex.escape] — kept identical to what
   * `TrailblazeNodeSelectorGeneratorAndroidAccessibility.escapeForSelector` produces so
   * anchored selectors round-trip through the same matcher.
   */
  private fun escapeForYamlRegex(s: String): String = "\\Q$s\\E"

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

  private fun printSelectorYaml(selector: TrailblazeNodeSelector) {
    // Emit a pasteable WaypointSelectorEntry — `- description: ""\n  selector:\n    ...`.
    //
    // Hand-format rather than using the kaml `TrailblazeYaml` instance because:
    //  1. We want to control indent precisely (4-space child indent, matching the existing
    //     hand-authored waypoint files in the repo).
    //  2. We want `\Qfoo\E` literal blocks emitted verbatim (`\Q` / `\E` escape chars
    //     are valid YAML scalars but kaml's default scalar style escapes them in a way
    //     that makes the regex hard to read).
    //  3. The selector shape is shallow — driver match + optional spatial / hierarchy
    //     children — so the formatting recursion stays tiny and reads cleanly.
    Console.log("- description: \"\"")
    Console.log("  selector:")
    emitSelectorBody(selector, indent = 4)
  }

  private fun emitSelectorBody(selector: TrailblazeNodeSelector, indent: Int) {
    val pad = " ".repeat(indent)
    val childPad = " ".repeat(indent + 2)
    selector.androidAccessibility?.let { match ->
      Console.log("${pad}androidAccessibility:")
      match.classNameRegex?.let { Console.log("$childPad" + "classNameRegex: \"${escapeYamlString(it)}\"") }
      match.resourceIdRegex?.let { Console.log("$childPad" + "resourceIdRegex: \"${escapeYamlString(it)}\"") }
      match.textRegex?.let { Console.log("$childPad" + "textRegex: \"${escapeYamlString(it)}\"") }
      match.contentDescriptionRegex?.let { Console.log("$childPad" + "contentDescriptionRegex: \"${escapeYamlString(it)}\"") }
      match.hintTextRegex?.let { Console.log("$childPad" + "hintTextRegex: \"${escapeYamlString(it)}\"") }
      match.labeledByTextRegex?.let { Console.log("$childPad" + "labeledByTextRegex: \"${escapeYamlString(it)}\"") }
      match.stateDescriptionRegex?.let { Console.log("$childPad" + "stateDescriptionRegex: \"${escapeYamlString(it)}\"") }
      match.paneTitleRegex?.let { Console.log("$childPad" + "paneTitleRegex: \"${escapeYamlString(it)}\"") }
      match.roleDescriptionRegex?.let { Console.log("$childPad" + "roleDescriptionRegex: \"${escapeYamlString(it)}\"") }
      match.composeTestTagRegex?.let { Console.log("$childPad" + "composeTestTagRegex: \"${escapeYamlString(it)}\"") }
      match.uniqueId?.let { Console.log("$childPad" + "uniqueId: \"${escapeYamlString(it)}\"") }
      match.isSelected?.takeIf { it }?.let { Console.log("$childPad" + "isSelected: true") }
      match.isHeading?.takeIf { it }?.let { Console.log("$childPad" + "isHeading: true") }
      match.isClickable?.takeIf { it }?.let { Console.log("$childPad" + "isClickable: true") }
      match.isCheckable?.takeIf { it }?.let { Console.log("$childPad" + "isCheckable: true") }
      match.isChecked?.takeIf { it }?.let { Console.log("$childPad" + "isChecked: true") }
      match.isEditable?.takeIf { it }?.let { Console.log("$childPad" + "isEditable: true") }
      match.isPassword?.takeIf { it }?.let { Console.log("$childPad" + "isPassword: true") }
      match.isScrollable?.takeIf { it }?.let { Console.log("$childPad" + "isScrollable: true") }
      match.isEnabled?.takeIf { it }?.let { Console.log("$childPad" + "isEnabled: true") }
      match.isFocused?.takeIf { it }?.let { Console.log("$childPad" + "isFocused: true") }
      match.inputType?.takeIf { it != 0 }?.let { Console.log("$childPad" + "inputType: $it") }
      match.collectionItemRowIndex?.let { Console.log("$childPad" + "collectionItemRowIndex: $it") }
      match.collectionItemColumnIndex?.let { Console.log("$childPad" + "collectionItemColumnIndex: $it") }
    }
    selector.containsChild?.let {
      Console.log("${pad}containsChild:")
      emitSelectorBody(it, indent + 2)
    }
    selector.childOf?.let {
      Console.log("${pad}childOf:")
      emitSelectorBody(it, indent + 2)
    }
    selector.containsDescendants?.takeIf { it.isNotEmpty() }?.let { list ->
      Console.log("${pad}containsDescendants:")
      list.forEach { d ->
        Console.log("$pad  -")
        emitSelectorBody(d, indent + 4)
      }
    }
    selector.above?.let {
      Console.log("${pad}above:")
      emitSelectorBody(it, indent + 2)
    }
    selector.below?.let {
      Console.log("${pad}below:")
      emitSelectorBody(it, indent + 2)
    }
    selector.leftOf?.let {
      Console.log("${pad}leftOf:")
      emitSelectorBody(it, indent + 2)
    }
    selector.rightOf?.let {
      Console.log("${pad}rightOf:")
      emitSelectorBody(it, indent + 2)
    }
    selector.index?.let { Console.log("${pad}index: $it") }
  }

  /**
   * Minimal YAML string escape: backslashes and double-quotes only, since these are the
   * two characters that break a `"..."`-quoted scalar. Regex literals like `\Qfoo\E` need
   * the backslash doubled so YAML parses them back as the literal `\Q...\E` for the regex
   * engine. Newlines and control characters don't appear in selector regex strings (the
   * generator escapes input via `escapeForSelector`), so this stays simple.
   */
  private fun escapeYamlString(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
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
