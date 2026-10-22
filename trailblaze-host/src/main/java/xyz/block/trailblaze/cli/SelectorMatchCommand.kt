package xyz.block.trailblaze.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.filterImportantForAccessibility
import xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * `trailblaze selector match`: what does this selector match on these screens?
 *
 * The check an author (or a tool choosing between `selector suggest` candidates) needs before
 * trusting a selector: run it against one or more captured screens and see which nodes it hits
 * on each. Several captures of the same screen — another run, another locale, another device —
 * answer the question one capture can't: does the selector still find the element when the
 * text, the data or the layout moved?
 *
 * Resolution is the runtime's own [TrailblazeNodeSelectorResolver], against the same
 * `trailblazeNodeTree` the runtime matches selectors on. A tap is resolved in the order an Android
 * accessibility device tries it ([resolveImportantForAccessibilityFirst]); a waypoint signal
 * (`present` / `absent`) only in the important-for-accessibility tree a waypoint capture holds. So
 * a count here is the count the runtime would see — on an `--all` capture like `--live` too.
 *
 * `--live` adds the connected device's current screen — the one `trailblaze snapshot --all`
 * shows — to the screens checked, alone or beside captured logs.
 *
 * `--expect` turns the report into a check: `unique` (exactly one match on every screen, what a
 * tap needs), `present` (at least one, what a waypoint's required signal needs), or `absent`
 * (none, what a forbidden signal needs). Exit 1 if any screen fails it.
 */
@Command(
  name = "match",
  mixinStandardHelpOptions = true,
  description = [
    "Show what a selector matches on one or more captured screens, or the device's (--live).",
    "Pass several captures of the same screen (other runs, locales, devices) to check that " +
    "a selector survives them. --expect unique|present|absent exits 1 if any screen fails.",
  ],
)
class SelectorMatchCommand : Callable<Int> {

  @Parameters(
    arity = "0..*",
    paramLabel = "<log>",
    description = ["Screen-state logs to match against (*_TrailblazeLlmRequestLog.json, *_TrailblazeSnapshotLog.json or *_AgentDriverLog.json)."],
  )
  var logs: List<File> = emptyList()

  @Option(
    names = ["--selector"],
    description = [
      "The selector, as YAML or JSON — the same body a waypoint or `tool tap --selector` " +
      "takes, or a `selector` value from `selector suggest --json`.",
    ],
  )
  var selector: String? = null

  @Option(
    names = ["--selector-file"],
    description = ["Read the selector from a YAML or JSON file instead of --selector."],
  )
  var selectorFile: File? = null

  @Option(
    names = ["--session"],
    description = ["Session log directory to match against (repeatable). Uses --step, else its last step."],
  )
  var sessions: List<File> = emptyList()

  @Option(
    names = ["--step"],
    description = ["1-based step within each --session (default: last step)"],
  )
  var step: Int? = null

  @Option(
    names = ["--live"],
    description = [
      "Also match against the connected device's current screen (the one " +
        "`trailblaze snapshot --all` shows). Combines with log files and --session. With --batch, " +
        "lets lines read it with `live: true`.",
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
    names = ["--expect"],
    description = [
      "Check every screen: unique (exactly 1 match), present (1 or more), absent (none). " +
      "Exits 1 if any screen fails. Without it, the command only reports. present and absent " +
      "match the way a waypoint does; unique and no --expect check the selector as a tap, and " +
      "refuse a web selector that web replay would locate differently.",
    ],
  )
  var expect: SelectorExpectation? = null

  @Option(
    names = ["--migration-tree"],
    description = [
      "Match against a dual-tree capture's accessibility migration tree instead of the " +
      "primary tree. Its nodes have no refs.",
    ],
  )
  var migrationTree: Boolean = false

  @Option(
    names = ["--json"],
    description = ["Print the result as one JSON object."],
  )
  var json: Boolean = false

  @Option(
    names = ["--batch"],
    paramLabel = "<file.jsonl>",
    description = [
      "Answer many questions in one call. Each line is a JSON object: `selector` (an object " +
      "or a YAML string), `logs` (an array), `session` + optional `step`, and/or `live: true` (needs --live), and " +
      "optional `expect` (defaulting to this command's --expect). Prints one JSON object " +
      "per line, in order. Exit 1 if any line fails its expectation; a line that can't be " +
      "answered prints {\"line\":N,\"error\":...} and makes the exit 3 (2 if it failed unexpectedly).",
    ],
  )
  var batch: File? = null

  override fun call(): Int {
    batch?.let { return runBatch(it) }
    val screens = screenCache()
    return try {
      val request = MatchRequest(
        selector = resolveSelector(),
        screens = logs.map { SelectorScreen.Log(requireLogFile(it)) } +
          sessions.map { SelectorScreen.Log(screens.sessionStep(it, step)) } +
          listOfNotNull(SelectorScreen.Live.takeIf { live }),
        expect = expect,
        migrationTree = migrationTree,
      )
      val result = match(request, screens)
      if (json) {
        Console.log(selectorCliJson.encodeToString(MatchJson.serializer(), result.toJson(line = null)))
      } else {
        printReport(result)
      }
      if (result.passed == false) TrailblazeExitCode.ASSERTION_FAILED.code else TrailblazeExitCode.SUCCESS.code
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

  private fun resolveSelector(): TrailblazeNodeSelector {
    val inline = selector
    val file = selectorFile
    return when {
      inline != null && file != null -> throw SelectorInputException("Pass --selector or --selector-file, not both.")
      inline != null -> parseNodeSelector(inline)
      file != null -> {
        val resolved = callerFile(file)
        if (!resolved.isFile) {
          throw SelectorInputException("--selector-file does not exist or is not a file: ${resolved.absolutePath}")
        }
        parseNodeSelector(resolved.readText(), label = "--selector-file ${resolved.name}")
      }
      else -> throw SelectorInputException("Provide the selector with --selector or --selector-file.")
    }
  }

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
        val result = match(parsed.getOrThrow().toMatchRequest(cache), cache)
        if (result.passed == false) {
          exit = chooseWorseExitCode(exit, TrailblazeExitCode.ASSERTION_FAILED.code)
        }
        result.toJson(line = lineNo)
      } catch (e: LiveScreenUnavailable) {
        // Why is already on stderr; the cache keeps the failure, so later live lines fail fast.
        exit = chooseWorseExitCode(exit, e.exitCode)
        MatchJson(line = lineNo, error = LIVE_SCREEN_UNAVAILABLE)
      } catch (e: SelectorInputException) {
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.MISUSE.code)
        MatchJson(line = lineNo, error = e.message)
      } catch (e: IncompleteCaptureException) {
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.INFRA_FAILED.code)
        MatchJson(line = lineNo, error = e.message)
      } catch (e: Exception) {
        // One line's unexpected failure must not cost the lines after it their answers.
        exit = chooseWorseExitCode(exit, TrailblazeExitCode.INFRA_FAILED.code)
        MatchJson(line = lineNo, error = "unexpected failure: $e")
      }
      Console.log(selectorCliJson.encodeToString(MatchJson.serializer(), out))
    }
    return exit
  }

  private fun JsonObject.toMatchRequest(screens: ScreenStateCache): MatchRequest {
    val selectorElement = field("selector") ?: throw SelectorInputException("each line needs \"selector\"")
    val selectorText = (selectorElement as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?: selectorElement.toString()
    val lineLogs = when (val l = field("logs")) {
      null -> emptyList()
      is JsonArray -> l.map { (it as? JsonPrimitive)?.content ?: throw SelectorInputException("\"logs\" must be strings") }
      else -> throw SelectorInputException("\"logs\" must be an array of paths")
    }
    val lineExpect = stringField("expect")?.let { value ->
      SelectorExpectation.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw SelectorInputException("\"expect\" must be one of unique, present, absent; got '$value'")
    } ?: expect
    return MatchRequest(
      selector = parseNodeSelector(selectorText, label = "\"selector\""),
      screens = lineLogs.map { SelectorScreen.Log(requireLogFile(File(it))) } +
        listOfNotNull(
          stringField("session")?.let { SelectorScreen.Log(screens.sessionStep(File(it), intField("step"))) },
          SelectorScreen.Live.takeIf { wantsLiveScreen(liveAllowed = live) },
        ),
      expect = lineExpect,
      migrationTree = migrationTree,
    )
  }

  private fun printReport(result: MatchResult) {
    for (screen in result.screens) {
      val verdict = when (screen.passed) {
        null -> ""
        true -> "  ok"
        false -> "  FAIL (expected ${result.request.expect!!.name.lowercase()})"
      }
      Console.log("${screen.screen.displayName}: matches ${screen.nodes.size}$verdict")
      screen.nodes.take(MAX_NODES_PRINTED).forEach { node ->
        val ref = node.ref?.let { "[$it] " }.orEmpty()
        val bounds = node.bounds?.let { "  @ ${it.left},${it.top} ${it.width}x${it.height}" }.orEmpty()
        Console.log("  $ref${describeNode(node)}$bounds")
      }
      if (screen.nodes.size > MAX_NODES_PRINTED) {
        Console.log("  … ${screen.nodes.size - MAX_NODES_PRINTED} more (--json lists them all)")
      }
    }
    result.request.expect?.let { expectation ->
      val failed = result.screens.count { it.passed == false }
      Console.log(
        if (failed == 0) {
          "expect ${expectation.name.lowercase()}: passed on ${result.screens.size} screen(s)"
        } else {
          "expect ${expectation.name.lowercase()}: FAILED on $failed of ${result.screens.size} screen(s)"
        },
      )
    }
  }

  private companion object {
    const val MAX_NODES_PRINTED = 20
  }
}

/** What `selector match --expect` checks on every screen. */
enum class SelectorExpectation {
  /** Exactly one match: what a tap needs. */
  UNIQUE,

  /** One or more matches: what a waypoint's required signal needs. */
  PRESENT,

  /** No match: what a waypoint's forbidden signal needs. */
  ABSENT,
  ;

  fun accepts(matches: Int): Boolean = when (this) {
    UNIQUE -> matches == 1
    PRESENT -> matches >= 1
    ABSENT -> matches == 0
  }
}

internal data class MatchRequest(
  val selector: TrailblazeNodeSelector,
  val screens: List<SelectorScreen>,
  val expect: SelectorExpectation? = null,
  val migrationTree: Boolean = false,
)

internal data class ScreenMatch(
  val screen: SelectorScreen,
  val nodes: List<TrailblazeNode>,
  /** Null when no expectation was set. */
  val passed: Boolean?,
)

internal data class MatchResult(val request: MatchRequest, val screens: List<ScreenMatch>) {
  /** Null when no expectation was set; else whether every screen met it. */
  val passed: Boolean? get() = request.expect?.let { screens.all { it.passed == true } }
}

@Serializable
internal data class ScreenMatchJson(
  val log: String? = null,
  val live: Boolean? = null,
  val matches: Int,
  val ok: Boolean? = null,
  val nodes: List<SelectorNodeJson>,
)

@Serializable
internal data class MatchJson(
  val line: Int? = null,
  val selector: TrailblazeNodeSelector? = null,
  val expect: String? = null,
  val ok: Boolean? = null,
  val screens: List<ScreenMatchJson>? = null,
  val error: String? = null,
)

internal fun MatchResult.toJson(line: Int?) = MatchJson(
  line = line,
  selector = request.selector,
  expect = request.expect?.name?.lowercase(),
  ok = passed,
  screens = screens.map { s ->
    ScreenMatchJson(
      log = (s.screen as? SelectorScreen.Log)?.file?.path,
      live = true.takeIf { s.screen == SelectorScreen.Live },
      matches = s.nodes.size,
      ok = s.passed,
      nodes = s.nodes.map { it.toSelectorNodeJson() },
    )
  },
)

/** Resolves [MatchRequest.selector] on every screen. Throws [SelectorInputException] on bad input. */
internal fun match(request: MatchRequest, screens: ScreenStateCache): MatchResult {
  if (request.screens.isEmpty()) {
    throw SelectorInputException("Name at least one screen: a log file argument, --session, or --live.")
  }
  // A waypoint signal (present / absent) is matched on captures by this same resolver, so it is
  // checked exactly as written. A tap (unique, or no --expect) is located on web by replay's
  // Playwright locator instead, so it is checked only when that locator reads it the same way.
  val asTap = request.expect == null || request.expect == SelectorExpectation.UNIQUE
  if (asTap) refuseWebSelectorsReplayReadsDifferently(request.selector)
  // Replay looks a web role up case-insensitively (`AriaRole.valueOf(role.uppercase())`), while
  // captures store it lowercase, so a tap selector spelled `BUTTON` must be checked as `button`.
  // Replay looks an exact name up with Playwright's exact match (whole name, whitespace trimmed,
  // an unnamed element's name is empty), where this resolver also reads the value as a regex and
  // skips unnamed nodes: `^Pay$` is "Pay" to replay, and `^$` finds an unnamed button. So a tap
  // is resolved without its name, and replay's exact-name test is applied to what that finds.
  val replayExactName = request.selector.web?.ariaNameRegex?.takeIf { asTap }
    ?.let { PlaywrightAriaSnapshot.exactAriaName(it) }
  val resolvable = request.selector.web?.takeIf { asTap }
    ?.let { web ->
      request.selector.copy(
        web = web.copy(
          ariaRole = web.ariaRole?.lowercase(),
          ariaNameRegex = web.ariaNameRegex?.takeIf { replayExactName == null },
        ),
      )
    }
    ?: request.selector
  val results = request.screens.map { source ->
    val screen = screens.load(source)
    val tree = if (request.migrationTree) {
      (screen as? MigrationScreenState)?.driverMigrationTreeNode
        ?: throw unanswerable(source, "${source.displayName} has no migration tree (it isn't a dual-tree capture).")
    } else {
      val primary = screen.trailblazeNodeTree
        ?: throw unanswerable(source, "${source.displayName} has no trailblazeNodeTree.")
      refuseIncompleteCapture(source, screen)
      primary
    }
    if (asTap && (tree.driverDetail is DriverNodeDetail.AndroidMaestro || tree.driverDetail is DriverNodeDetail.IosMaestro)) {
      refuseForTap(
        "${source.displayName} is a Maestro driver capture, where replay can hand a tap to Maestro's own " +
          "matcher: it drops fields such as classNameRegex and clickable and matches textRegex " +
          "against hint and accessibility text too, so a count here could differ from what it taps.",
      )
    }
    if (asTap && tree.driverDetail is DriverNodeDetail.Web && request.selector.web == null) {
      refuseForTap(
        "${source.displayName} is a web capture, and web replay locates an element only by the selector's " +
          "own web fields (any inside childOf, containsChild, ... are unused). This selector has none, " +
          "so replay can't find it.",
      )
    }
    // A tap falls back to the whole tree on the device; a waypoint signal is matched on a capture,
    // which holds only the important-for-accessibility tree (filtering again is a no-op there).
    val resolved = if (asTap) {
      resolveImportantForAccessibilityFirst(tree, resolvable)
    } else {
      TrailblazeNodeSelectorResolver.resolve(tree.filterImportantForAccessibility(), resolvable)
    }
    val nodes = when (val r = resolved) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(r.node)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> r.nodes
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
    }.let { found ->
      replayExactName?.let { name -> replayNameMatches(found, name, textRun = request.selector.web?.ariaRole == TEXT_RUN_ROLE, source) }
        ?: found
    }
    ScreenMatch(source, nodes, request.expect?.accepts(nodes.size))
  }
  return MatchResult(request, results)
}

/**
 * The [found] nodes replay's exact-name lookup for [name] would act on.
 *
 * A text run is matched on its printed text as a whole, which the capture stores as is. Any other
 * role is matched on its accessible name the way Playwright compares one, or, for an element a
 * snapshot prints as `paragraph: Some text` because it has no name, on that printed text. The
 * capture stores both as the node's name, so a non-empty [name] matches either; an empty one
 * finds every unnamed element, and a capture can't show whether a stored name is printed text
 * of an unnamed element, so that check is refused when one is present.
 */
private fun replayNameMatches(found: List<TrailblazeNode>, name: String, textRun: Boolean, source: SelectorScreen): List<TrailblazeNode> {
  val names = found.associateWith { (it.driverDetail as? DriverNodeDetail.Web)?.ariaName }
  if (textRun) return found.filter { names[it] == name }
  if (name != name.normalizedLikePlaywright()) {
    refuseForTap(
      "Replay compares the name \"$name\" to an accessible name with its spacing normalized, but to " +
        "the printed text of an element with no name as written, and a capture can't show which " +
        "an element has. Pass the name with single spaces and no leading or trailing space.",
    )
  }
  if (name.normalizedLikePlaywright().isEmpty() && names.values.any { !it.isNullOrEmpty() }) {
    refuseForTap(
      "In ${source.displayName}, an element of this role has text the capture stores as its name, and " +
        "the capture can't show whether that is its accessible name or printed text on an element " +
        "with no name, which replay counts as unnamed. Check an empty-name tap with a live run.",
    )
  }
  return found.filter { names[it].orEmpty().normalizedLikePlaywright() == name.normalizedLikePlaywright() }
}

/**
 * The role a snapshot gives a run of text, which web replay matches on the text alone. Replay
 * checks for it case-sensitively; `TEXT` is looked up as an ARIA role instead.
 */
private const val TEXT_RUN_ROLE = "text"

/** Roles a snapshot prints only in a few cases (named, say), where replay finds every element. */
private val ROLES_SNAPSHOTS_OMIT = setOf("generic", "none", "presentation")

/**
 * Refuses a tap selector whose web part replay's Playwright locator would read differently from
 * this resolver, so a count here is never certified for an element replay wouldn't act on.
 */
private fun refuseWebSelectorsReplayReadsDifferently(selector: TrailblazeNodeSelector) {
  if (usesBrowserEvaluatedWebFields(selector)) {
    refuseForTap(
      "The selector uses web cssSelector or nthIndex. The browser runs these as a CSS query and an " +
        "index into its matches; a capture only stores the values recorded for each element, so a " +
        "result here could disagree with the browser. Check it with a live run instead.",
    )
  }
  if (hasConstraintsWebReplayIgnores(selector)) {
    refuseForTap(
      "The selector is a web selector with index or a relative constraint (childOf, below, ...). " +
        "Web replay finds the element from the web fields alone and acts on the first match, so a " +
        "result here could name a different element than the one replay clicks.",
    )
  }
  val web = selector.web ?: return
  val driverMatchers = with(selector) {
    listOfNotNull(androidAccessibility, androidView, androidMaestro, web, compose, iosMaestro, iosAxe)
  }
  if (driverMatchers.size > 1) {
    refuseForTap(
      "The selector sets web alongside another driver's fields. Web replay uses only the web " +
        "fields, while this check would use the other driver's, so pass one driver's fields.",
    )
  }
  val replayed = webFieldsReplayUses(web)
    ?: refuseForTap(
      "Web replay can't locate an element from this selector: it needs dataTestId, ariaRole, or " +
        "cssSelector (ariaNameRegex is only used together with ariaRole).",
    )
  if (replayed != web) {
    refuseForTap(
      "Web replay uses only { ${replayed.description()} } from this selector and ignores the rest, " +
        "so a match here could name a different element. Pass just those fields.",
    )
  }
  if (replayed.dataTestId != null) {
    refuseForTap(
      "Web replay finds a test id with a DOM query, which also finds elements a capture leaves out " +
        "(aria-hidden or hidden ones), and acts on the first. A capture can't show how many it finds; " +
        "check a test-id tap with a live run.",
    )
  }
  replayed.headingLevel?.let { level ->
    if (level !in 1..6) {
      refuseForTap("Web replay finds no element for headingLevel $level: it looks up heading levels 1 to 6 only.")
    }
  }
  web.ariaRole?.takeUnless { it == TEXT_RUN_ROLE && replayed.ariaNameRegex != null }?.let { role ->
    if (!PlaywrightAriaSnapshot.isPlaywrightAriaRole(role)) {
      refuseForTap(
        "\"$role\" isn't an ARIA role web replay knows; it would look the element up as role " +
          "\"generic\" instead, so a match here could name a different element.",
      )
    }
    if (role.lowercase() in ROLES_SNAPSHOTS_OMIT) {
      refuseForTap(
        "A snapshot leaves out most elements with role \"$role\" (it prints their content in their " +
          "place), while replay still finds them, so a capture can't show how many replay finds.",
      )
    }
  }
  replayed.ariaNameRegex?.let { name ->
    if (PlaywrightAriaSnapshot.exactAriaName(name) == null) {
      refuseForTap(
        "Web replay matches ariaNameRegex \"$name\" with the browser's regex engine, which reads " +
          "patterns differently from this check's (Unicode case folding, characters outside the BMP, " +
          "backreferences, Java-only syntax), so a capture can't show what it finds. A web tap is " +
          "checked only with an exact name, which is what the recorder writes.",
      )
    }
  }
}

/**
 * A name as Playwright compares it for an exact match (`normalizeWhiteSpace`): zero-width spaces
 * and soft hyphens removed, then trimmed and whitespace runs collapsed, where whitespace is
 * JavaScript's `\s`, which unlike Java's includes Unicode spaces such as the no-break space.
 */
private fun String.normalizedLikePlaywright(): String =
  replace(PLAYWRIGHT_INVISIBLE, "").replace(JS_WHITESPACE_RUN, " ").trim(' ')

private val PLAYWRIGHT_INVISIBLE = Regex("[\u200B\u00AD]")

private val JS_WHITESPACE_RUN =
  Regex("[\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+")

/** Refuses a tap check, pointing at the waypoint checks, which match captures as written. */
private fun refuseForTap(reason: String): Nothing = throw SelectorInputException(
  "$reason To check it as a waypoint signal instead, pass --expect present or absent: waypoints " +
    "match captures the way this command does.",
)

/**
 * True when [selector], or any selector nested in it, sets a web field the snapshot resolver
 * can only compare to the captured value: the browser evaluates `cssSelector` as a query (so
 * `[id="pay"]` hits an element captured as `#pay`) and applies `nthIndex` to the query's own
 * matches rather than to the per-descriptor index the capture stores.
 */
internal fun usesBrowserEvaluatedWebFields(selector: TrailblazeNodeSelector): Boolean =
  selector.web?.let { it.cssSelector != null || (it.nthIndex != null && !isNamedTextRun(it)) } == true ||
    listOfNotNull(
      selector.below, selector.above, selector.leftOf, selector.rightOf,
      selector.childOf, selector.containsChild,
    ).any(::usesBrowserEvaluatedWebFields) ||
    selector.containsDescendants.orEmpty().any(::usesBrowserEvaluatedWebFields)

/**
 * A text run with an exact name. Replay counts its nthIndex over the runs with that text in
 * snapshot order, which is the per-descriptor occurrence a capture stores.
 */
private fun isNamedTextRun(web: DriverNodeMatch.Web): Boolean =
  web.ariaRole == TEXT_RUN_ROLE &&
    web.ariaNameRegex?.takeIf { it.isNotBlank() }?.let { PlaywrightAriaSnapshot.exactAriaName(it) } != null

/**
 * True when a top-level web selector also sets a constraint the resolver applies but web replay
 * does not: replay builds its Playwright locator from the `web` fields only and takes `.first()`.
 */
internal fun hasConstraintsWebReplayIgnores(selector: TrailblazeNodeSelector): Boolean =
  selector.web != null && (
    selector.index != null ||
      listOfNotNull(
        selector.below, selector.above, selector.leftOf, selector.rightOf,
        selector.childOf, selector.containsChild,
      ).isNotEmpty() ||
      !selector.containsDescendants.isNullOrEmpty()
    )

/**
 * The part of [web] that web replay builds its Playwright locator from, in replay's order of
 * preference: data-testid, then ARIA role + name, then CSS, then role alone; heading level and
 * nth index narrow whichever is chosen. Null when replay can build no locator at all. Mirrors
 * `PlaywrightExecutableTool.nodeSelectorToReadinessLocator`.
 */
internal fun webFieldsReplayUses(web: DriverNodeMatch.Web): DriverNodeMatch.Web? {
  val narrowing = DriverNodeMatch.Web(headingLevel = web.headingLevel, nthIndex = web.nthIndex)
  web.dataTestId?.takeIf { it.isNotBlank() }?.let { return narrowing.copy(dataTestId = it) }
  val name = web.ariaNameRegex?.takeIf { it.isNotBlank() }
  // A text run is matched on its text with nthIndex counted among those runs; heading level is unused.
  if (web.ariaRole == TEXT_RUN_ROLE && name != null) {
    return DriverNodeMatch.Web(ariaRole = web.ariaRole, ariaNameRegex = name, nthIndex = web.nthIndex)
  }
  if (web.ariaRole != null && name != null) return narrowing.copy(ariaRole = web.ariaRole, ariaNameRegex = name)
  web.cssSelector?.let { return narrowing.copy(cssSelector = it) }
  web.ariaRole?.let { return narrowing.copy(ariaRole = it) }
  return null
}
