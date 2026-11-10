package xyz.block.trailblaze.cli

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.config.project.findWorkspaceRoot
import xyz.block.trailblaze.report.SkippedTrails
import xyz.block.trailblaze.scripting.RunWarningSurveys
import xyz.block.trailblaze.scripting.WorkspaceSurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import xyz.block.trailblaze.util.fileUrl

/**
 * Generate reports from session recordings.
 *
 * Every invocation writes an HTML index (humans) and a JSON `CiSummaryReport` (CI).
 * With `--id` or `--current`, the report narrows to a single session and the timeline
 * exports (`--video`, `--gif`, `--webp`) become available.
 *
 * The HTML is the Trailblaze report — the same artifact the Trail Runner app's "Share as HTML"
 * button produces and the same renderer the hosted viewer serves, so a report generated here and
 * one opened through the viewer are the same page with the same features. A missing report means
 * a broken `bun`, which Trailblaze requires.
 *
 * The animated exports (`--video`/`--gif`/`--webp`) capture that report's timeline.
 *
 * Examples:
 *   trailblaze report                              - HTML + JSON for all sessions
 *   trailblaze report --open                       - ...and open the HTML in a browser
 *   trailblaze report --id abc123                  - reports for one session
 *   trailblaze report --current                    - reports for the currently active session
 *   trailblaze report --current --gif              - reports + GIF + animated-WebP (one bare
 *                                                    --gif or --webp flag triggers a shared frame
 *                                                    capture and auto-emits both formats; pass
 *                                                    explicit paths to limit which file is written)
 *   trailblaze report --current --webp --no-gif    - WebP only (suppress the auto-GIF companion)
 *   trailblaze report --id abc --gif out.gif       - Only out.gif (explicit path = single-format)
 *   trailblaze report --id abc123 --output-dir out - Drop report-interactive.html /
 *                                                    summary.json / (timeline.{mp4,gif,webp})
 *                                                    into `out/`
 */
@Command(
  name = "report",
  mixinStandardHelpOptions = true,
  // First element is the one-line summary the top-level `trailblaze --help` index shows;
  // the rest only appears in `trailblaze report --help`.
  description = [
    "Generate an HTML report (plus JSON summary, optional MP4/GIF/WebP) for session recordings.",
    "JSON-only failures log a warning and still exit 0 — HTML is the primary artifact and is " +
      "what gates the exit code. Animated exports collapse long idle gaps between steps so " +
      "their length tracks the number of steps, not the session's real wall-clock. The capture " +
      "window for all three (--gif/--webp/--video) is bounded by the MAX_PLAYBACK_WAIT_MS " +
      "environment variable (milliseconds, default 600000); if playback overruns it, a " +
      "best-effort truncated artifact is still written with a warning.",
  ]
)
class ReportCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID to narrow the report to (positional form of --id). " +
        "Prefix matching is supported. Mutually exclusive with --id and --current.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = [
      "Narrow to a single session (defaults to all sessions). " +
        "Use `trailblaze session list` to find IDs. Prefix matching is supported. " +
        "Equivalent to passing the session ID positionally.",
    ],
  )
  var id: String? = null

  @Option(
    names = ["--current"],
    description = [
      "Narrow to the currently active session (resolved via the running daemon). " +
        "Mutually exclusive with --id.",
    ],
  )
  var current: Boolean = false

  @Option(
    names = ["--open"],
    description = ["Open the HTML report in the default browser after generation."],
  )
  var open: Boolean = false

  @Option(
    names = ["--output-dir"],
    description = [
      "Write all artifacts into this directory with canonical names " +
        "(report-interactive.html, summary.json, timeline.mp4, timeline.gif, timeline.webp). " +
        "Created if it doesn't exist. If omitted, artifacts land in the default " +
        "`logs/reports/` location with timestamped names.",
    ],
  )
  var outputDir: File? = null

  @Option(
    names = ["--video"],
    arity = "0..1",
    fallbackValue = USE_DEFAULT_PATH,
    description = [
      "Export the HTML report's timeline autoplay (the scrubbing view with step labels " +
        "and annotations) as an MP4. NOT the raw device recording — that's a separate " +
        "artifact in the session's logs dir. Path defaults to <report-dir>/<session-id>.mp4 " +
        "(or <output-dir>/timeline.mp4 when --output-dir is set). Single-session only — " +
        "pass --id or --current.",
    ],
  )
  var videoOutput: String? = null

  @Option(
    names = ["--gif"],
    arity = "0..1",
    fallbackValue = USE_DEFAULT_PATH,
    description = [
      "Export the HTML report's timeline autoplay (the scrubbing view with step labels " +
        "and annotations) as an animated GIF. NOT the raw device recording. Path defaults " +
        "to <report-dir>/<session-id>.gif (or <output-dir>/timeline.gif when --output-dir " +
        "is set). Smaller and easier to paste into a PR than --video, at the cost of a " +
        "lower frame rate and 256-color palette. Single-session only — pass --id or --current. " +
        "Frame capture is shared with --webp: passing this bare (no path) auto-emits a " +
        "companion .webp at the default path for free — pass --no-webp to suppress. An " +
        "explicit path here limits output to just that file.",
    ],
  )
  var gifOutput: String? = null

  @Option(
    names = ["--webp"],
    arity = "0..1",
    fallbackValue = USE_DEFAULT_PATH,
    description = [
      "Export the HTML report's timeline autoplay (the scrubbing view with step labels " +
        "and annotations) as an animated WebP. NOT the raw device recording. Path defaults " +
        "to <report-dir>/<session-id>.webp (or <output-dir>/timeline.webp when --output-dir " +
        "is set). Typically 25–50%% smaller than the equivalent --gif (24-bit color, " +
        "inter-frame deltas) — useful when the GIF would push past GitHub's 10MB inline " +
        "attachment limit. GitHub renders animated WebP inline the same as GIF. " +
        "Single-session only — pass --id or --current. " +
        "Frame capture is shared with --gif: passing this bare (no path) auto-emits a " +
        "companion .gif at the default path for free — pass --no-gif to suppress. An " +
        "explicit path here limits output to just that file.",
    ],
  )
  var webpOutput: String? = null

  @Option(
    names = ["--storyboard"],
    arity = "0..1",
    fallbackValue = USE_DEFAULT_PATH,
    description = [
      "Export a single-frame WebP storyboard — every step's screenshot tiled into a CSS " +
        "grid (one cell per executed action / taken snapshot, labeled with the action " +
        "verb), then full-page-screenshotted via headless Playwright and encoded to WebP. " +
        "Complements --gif/--webp (which play through the timeline over time) — embed both " +
        "in a PR comment to get glance-overview plus the animated walkthrough. A companion " +
        ".html with the same content is written alongside the WebP for standalone viewing. " +
        "Path defaults to <report-dir>/<session-id>.storyboard.webp (or " +
        "<output-dir>/storyboard.webp when --output-dir is set). Single-session only — " +
        "pass --id or --current.",
    ],
  )
  var storyboardOutput: String? = null

  @Option(
    names = ["--storyboard-columns"],
    description = [
      "Number of columns in the --storyboard grid. When omitted, the exporter auto-picks " +
        "by cell aspect ratio: landscape-majority sessions (desktop / tablet) get 3 " +
        "columns so each cell stays meaningfully large at GitHub's ~760px embed width " +
        "without burning vertical space, portrait-majority sessions (phone) get 4 columns " +
        "for denser inline glance. The auto-pick decision (landscape-cell count, " +
        "threshold) is logged so the chosen value is visible from the CLI tail. Pass an " +
        "explicit value to override (e.g. 6 / 8 for very long sessions that would " +
        "otherwise force the auto-fit cell-width shrink — spreading wider on the page is " +
        "cleaner than thumbnailing the cells).",
    ],
  )
  var storyboardColumns: Int? = null

  @Option(
    names = ["--storyboard-yaml"],
    negatable = true,
    description = [
      "Annotate each --storyboard cell with the YAML form of the recordable tool that " +
        "produced it (looked up by traceId against the session's TrailblazeToolLog " +
        "entries). The YAML strip replaces the synthesized verb/sublabel line — strictly " +
        "more informative for \"what was invoked here\" triage. Cells without a sibling " +
        "tool log fall back to the verb line. CSS Grid aligns rows to their tallest YAML " +
        "so a short YAML doesn't pay the cost of a long one elsewhere. Capped at 20 lines " +
        "per cell as a sanity bound. Default: on. Pass --no-storyboard-yaml to suppress " +
        "(reduces total rendered height by ~20%% on a typical session, at the cost of " +
        "less actionable per-cell labels). Has no effect without --storyboard.",
    ],
  )
  var storyboardYaml: Boolean = true

  @Option(
    names = ["--no-gif"],
    description = [
      "Suppress the auto-emitted .gif companion when --webp is requested with a bare flag. " +
        "Use this on scripts and CI flows that only embed the .webp and want to skip the " +
        "wasted GIF encode. Mutually exclusive with --gif.",
    ],
  )
  var noGif: Boolean = false

  @Option(
    names = ["--no-webp"],
    description = [
      "Suppress the auto-emitted .webp companion when --gif is requested with a bare flag. " +
        "Mutually exclusive with --webp.",
    ],
  )
  var noWebp: Boolean = false

  /**
   * Retired with the Compose/WebAssembly report, and accepted as a no-op rather than removed.
   * picocli aborts the whole command on an unknown option, so a caller that still passes one —
   * `block/trailblaze`'s `.github/pr_generate_report_assets.sh` passes `--no-wasm-report` on both
   * of its invocations — would fail its report step over a flag that now describes the only
   * behavior there is. Same reasoning as [xyz.block.trailblaze.report.ReportCliArgs]'s retired
   * set, which does this for the CI entry point. `hidden` so they stay out of `--help` and the
   * generated CLI docs.
   */
  @Option(names = ["--no-wasm-report"], hidden = true, description = ["Retired; accepted as a no-op."])
  var retiredNoWasmReport: Boolean = false

  /** Retired alongside [retiredNoWasmReport]. Declared as a String so any old value parses. */
  @Option(names = ["--export-from"], hidden = true, description = ["Retired; accepted as a no-op."])
  var retiredExportFrom: String? = null

  @Option(
    names = ["--max-size"],
    description = [
      "Cap each exported artifact (--gif / --video / --webp / --storyboard) at the given " +
        "byte size. Defaults to 10MB — GitHub's inline-attachment limit — so an export " +
        "you paste into a PR fits without having to think about it. Accepts plain bytes " +
        "(1024000) or human-readable suffixes (10MB, 5M, 1.5G); pass `none` (or `0`) for " +
        "a genuinely uncapped export. After the initial encode, the exporter iteratively " +
        "re-encodes at smaller viewport widths (1280→1024→720→640→480) until the artifact " +
        "fits, then stops. If even the readability floor (480px) is still over the cap: " +
        "an explicitly-passed --max-size fails the export with an actionable error, while " +
        "the 10MB default keeps the oversized artifact and warns instead — a default you " +
        "didn't ask for never turns a working export into a failure. Either way the " +
        "remedies are the same: drop GIF for --webp or --video (both compress " +
        "dramatically better), or shorten the recorded session (fewer trail steps, or " +
        "split into multiple sessions). The cap is applied per artifact, so " +
        "`--gif --webp --max-size=10MB` caps each one independently.",
    ],
  )
  var maxSize: String? = null

  @Option(
    names = ["--full-report-payloads"],
    description = [
      "Embed full event payloads in the interactive report even for sessions that " +
        "passed, instead of applying the report size budgets (which truncate large " +
        "successful network bodies and elide repeated intermediate snapshots to keep the " +
        "report small). Failed sessions always embed full payloads regardless. The " +
        "on-disk events/ artifacts are never budgeted, so any session can be " +
        "regenerated in full with this flag at any time.",
    ],
  )
  var fullReportPayloads: Boolean = false

  @Option(
    names = ["--link-images"],
    description = [
      "Link screenshots and session recordings from the session directories instead of " +
        "embedding them, which keeps the report small and fast to build for large runs. The " +
        "links are relative to where the report is written, so it renders only beside the logs " +
        "directory it came from: open it in place, or host the logs directory with its layout " +
        "intact (the report and every session directory, at the same relative paths).",
    ],
  )
  var linkImages: Boolean = false

  @Option(
    names = ["--share-url"],
    paramLabel = "<url>",
    description = [
      "Bake a canonical hosted URL (http/https) into the interactive report. Its Copy " +
        "link button then produces deep links against that URL — with the current view, " +
        "sort, run, and step grafted on as query parameters — no matter where the file is " +
        "opened from (including file://). Use this when the report is published to a known " +
        "location, e.g. a CI artifact URL or an internal report server. Without this flag, " +
        "Copy link uses the browser's own address and only appears on http(s) pages.",
    ],
  )
  var shareUrl: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = [
      "Show the generator's own progress logs — workspace config loading, tool-class discovery, " +
        "per-session clock normalisation, and step timings. Suppressed by default so the report " +
        "paths are the output; turn this on when a report is slow or empty and you need to see " +
        "which session the generator was working on.",
    ],
  )
  var verbose: Boolean = false

  @Option(
    names = ["--surveys"],
    arity = "0..1",
    fallbackValue = "",
    split = ",",
    paramLabel = "<names>",
    description = [
      "Also run surveys over the report's sessions, and write what they found beside the report: " +
        "surveys.md (labels, the findings behind each, and the runs they came from) and " +
        "surveys.json. Bare, every survey the workspace carries runs; or name them, comma-separated " +
        "(--surveys=journeys,jobs). Each session's findings and labels are stored in its " +
        "surveys.json too. Without this flag only the workspace's warning surveys run, so the " +
        "report shows each run's warnings. Put a session id before this flag, or it is read as a " +
        "survey name.",
    ],
  )
  var surveys: List<String>? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error("Positional <session-id> and --id are two ways to spell the same thing — pass only one.")
      return TrailblazeExitCode.MISUSE.code
    }
    if ((positionalId != null || id != null) && current) {
      Console.error("--current is mutually exclusive with --id / positional <session-id>.")
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val resolvedId: String? = when {
      effectiveId != null -> effectiveId
      current -> resolveActiveSessionId() ?: run {
        Console.error(
          "--current: no active session found. Start a session with `trailblaze session start`, " +
            "or pass --id explicitly.",
        )
        return TrailblazeExitCode.INFRA_FAILED.code
      }
      else -> null
    }
    if ((videoOutput != null || gifOutput != null || webpOutput != null || storyboardOutput != null) &&
      resolvedId == null
    ) {
      Console.error(
        "--video / --gif / --webp / --storyboard require --id or --current. The all-sessions " +
          "index has nothing to play, so the exporter has nothing to record.",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    // --no-gif / --no-webp guards. The opt-outs only suppress *auto-emission* under the
    // shared-capture model; pairing them with the corresponding format (in any form) is
    // contradictory, and stacking both opt-outs leaves nothing to produce.
    if (noGif && noWebp) {
      Console.error("--no-gif --no-webp together leaves nothing to produce. Drop one.")
      return TrailblazeExitCode.MISUSE.code
    }
    if (noGif && gifOutput != null) {
      Console.error("--no-gif contradicts --gif. Drop one of them.")
      return TrailblazeExitCode.MISUSE.code
    }
    if (noWebp && webpOutput != null) {
      Console.error("--no-webp contradicts --webp. Drop one of them.")
      return TrailblazeExitCode.MISUSE.code
    }
    if (noGif && gifOutput == null && webpOutput == null) {
      Console.error(
        "--no-gif only has an effect when --webp triggers auto-emission. Add --webp.",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    if (noWebp && gifOutput == null && webpOutput == null) {
      Console.error(
        "--no-webp only has an effect when --gif triggers auto-emission. Add --gif.",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    if (maxSize != null && videoOutput == null && gifOutput == null && webpOutput == null &&
      storyboardOutput == null
    ) {
      Console.error(
        "--max-size has no effect without an artifact to cap. Add --gif, --video, --webp, " +
          "or --storyboard — e.g. `trailblaze report --id <id> --webp --max-size=$maxSize`.",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    storyboardColumns?.let { cols ->
      if (cols !in 1..12) {
        Console.error("--storyboard-columns must be in 1..12, got $cols.")
        return TrailblazeExitCode.MISUSE.code
      }
    }
    // (No guard for --storyboard-yaml without --storyboard: with the default flipped to
    // on, the flag's value is always set even when the user didn't mention it, so
    // gating execution on it would block every non-storyboard run. Silent no-op is the
    // right behavior — the CLI help already documents the "has no effect without
    // --storyboard" semantic.)
    // `maxSize == null` is the signal that the user never typed --max-size, which is what
    // separates the default cap (warn on floor exhaustion) from an explicit one (fail).
    // Deliberately NOT a picocli `defaultValue` — that would populate the field either
    // way and erase the distinction.
    val cap = try {
      MaxArtifactSize.resolveCap(maxSize)
    } catch (e: IllegalArgumentException) {
      Console.error("--max-size: ${e.message}")
      return TrailblazeExitCode.MISUSE.code
    }
    val trimmedShareUrl = shareUrl?.trim()?.takeIf { it.isNotEmpty() }
    // Reject values the viewer would refuse to render (its safeHref requires a parseable
    // http(s) URL), so a typo'd --share-url fails loudly here instead of producing a report
    // whose Copy link silently never appears.
    if (trimmedShareUrl != null && !isValidShareUrl(trimmedShareUrl)) {
      Console.error("--share-url must be a valid http(s) URL with a host, got '$trimmedShareUrl'.")
      return TrailblazeExitCode.MISUSE.code
    }
    // Building the app loads the workspace config and logs every file it read; those lines are
    // daemon-log breadcrumbs, not part of a report the person asked for.
    val app = quietUnlessVerbose(verbose) { parent.appProvider() }
    val exitCode = generateSessionReport(
      app,
      resolvedId,
      open,
      outputDir = outputDir,
      videoSpec = videoOutput,
      gifSpec = gifOutput,
      webpSpec = webpOutput,
      storyboardSpec = storyboardOutput,
      storyboardColumns = storyboardColumns,
      storyboardIncludeYaml = storyboardYaml,
      suppressGif = noGif,
      suppressWebp = noWebp,
      maxBytes = cap.maxBytes,
      maxBytesStrict = cap.strict,
      shareUrl = trimmedShareUrl,
      fullEventPayloads = fullReportPayloads,
      linkImages = linkImages,
      verbose = verbose,
      surveys = surveys?.filter { it.isNotBlank() },
    )
    // Exit for every outcome, not just the successful one. `TrailblazeCli.run` force-exits on
    // non-zero codes but returns normally on 0, so a non-daemon thread left running anywhere in
    // this path wedges the JVM on exactly the runs that went fine — which is the bug this replaced,
    // when the logs-repo file watcher was that thread.
    //
    // That watcher is fixed at the source (FileWatchService now runs its loop on a daemon thread),
    // so this is precaution rather than a known leak: `report` already force-exited on success
    // before this, and covering the other paths costs nothing while the report path keeps driving
    // subprocess-heavy work (headless Playwright, the bun/WASM report builds) whose threads we
    // don't own. Report generation owns the whole JVM — there's nothing left to do once the
    // artifacts are on disk.
    exitProcess(exitCode)
  }

  /**
   * Asks the running daemon (via MCP) for the currently bound session ID. Returns null
   * if the daemon isn't reachable or no session is bound — callers decide how to surface
   * that to the user. We log the underlying exception (type + message) on the way out so
   * "no active session found" diagnostics can be told apart from "daemon unreachable" /
   * "port misconfigured" without re-running with verbose logging.
   */
  private fun resolveActiveSessionId(): String? = runBlocking {
    // Port resolution itself can fail (corrupt or unreadable settings file, invalid int).
    // Bound the try-catch around BOTH the port lookup and the MCP call so neither throws
    // a raw stack trace at the user — "[--current]" diagnostics cover both failure modes.
    var port: Int? = null
    try {
      port = CliConfigHelper.resolveEffectiveHttpPort()
      CliMcpClient.connectReusable(port).use { it.getTrailblazeSessionId() }
    } catch (e: Exception) {
      val portStr = port?.toString() ?: "(unresolved)"
      Console.log("[--current] could not reach daemon on port $portStr: ${e.javaClass.simpleName}: ${e.message}")
      null
    }
  }

  companion object {
    /**
     * Sentinel for `arity = "0..1"` flags: picocli stores this when the user passes `--video`
     * (or `--gif`/`--webp`) bare, without a value. We replace it with a derived default path
     * once we know where the HTML report landed.
     */
    internal const val USE_DEFAULT_PATH = "__USE_DEFAULT_PATH__"

    /**
     * Mirrors the viewer's `safeHref` acceptance (a parseable absolute http(s) URL): a value
     * that merely starts with `http(s)://` but has no host (`https://?jwt=abc`), does not
     * parse, or carries a port a browser's `new URL(...)` would reject (> 65535) is refused,
     * because the viewer would refuse it and silently hide Copy link.
     */
    internal fun isValidShareUrl(value: String): Boolean {
      val uri = try {
        java.net.URI(value)
      } catch (e: java.net.URISyntaxException) {
        return false
      }
      return uri.scheme?.lowercase() in listOf("http", "https") &&
        !uri.host.isNullOrEmpty() &&
        uri.port <= 65535
    }
  }
}

/** A survey report [draftSurveyReport] wrote in [dir], with the surveys CLI's [exit] code. */
internal class SurveyReportDraft(val dir: File, val exit: Int)

/**
 * Writes every derived stream the workspace's survey files declare (`trailblaze.eventStream()`) into each
 * of [sessionDirs] as `events/<name>.ndjson`, on every report, so a session carries its streams
 * wherever it goes next. Nothing runs outside a workspace, or when [hasDerivedStreamWork] finds no
 * stream to write and none to remove. A failure costs only those streams: the report still builds,
 * without them, and exits as it would have; each session's `events/derived.json` records why.
 * Returns the surveys CLI's exit code, or success when nothing ran.
 */
internal fun writeDerivedStreams(sessionDirs: List<File>, configDir: File? = workspaceConfigDir()): Int {
  if (configDir == null || !hasDerivedStreamWork(configDir, sessionDirs)) return TrailblazeExitCode.SUCCESS.code
  val args = buildList {
    add("--streams")
    add("--quiet")
    sessionDirs.forEach { add("--sessions"); add(it.absolutePath) }
  }
  val exit = SurveyCli.run(args, CliCallerContext.callerCwd().toAbsolutePath().toFile())
  if (exit != TrailblazeExitCode.SUCCESS.code) Console.error(derivedStreamFailureMessage(exit, sessionDirs))
  return exit
}

/**
 * What `trailblaze report` says when writing derived streams exited [exit]. It promises a run warning
 * on the Summary only when a session's `events/derived.json` records a stream's error, which is all the
 * report reads warnings from: a stream file that fails to load, or a manifest that cannot be read,
 * records none.
 */
internal fun derivedStreamFailureMessage(exit: Int, sessionDirs: List<File>): String {
  val head = "Warning: derived streams were not all written (exit $exit); see above. The run's result is unchanged, " +
    "so a passing build stays green, but the failed streams' lanes are missing from the report"
  return if (sessionDirs.any(::recordsStreamError)) {
    "$head. Its Summary lists each failed stream whose error a session recorded as a run warning, and the next " +
      "report retries it. Any other error above shows no warning: fix it and run the report again."
  } else {
    "$head, and it shows no warning for them: fix the error above and run the report again."
  }
}

/** As the report caps `events/derived.json` (MAX_DERIVED_MANIFEST_BYTES): past it, the report reads no warning from it. */
private const val MAX_DERIVED_MANIFEST_BYTES = 1024L * 1024

private fun recordsStreamError(sessionDir: File): Boolean = runCatching {
  val manifest = File(sessionDir, "events/derived.json").takeIf { it.isFile && it.length() <= MAX_DERIVED_MANIFEST_BYTES } ?: return false
  val streams = Json.parseToJsonElement(manifest.readText()).jsonObject["streams"]?.jsonObject ?: return false
  // Any string error, even an empty one, is a failure the report lists.
  streams.values.any { entry -> ((entry as? JsonObject)?.get("error") as? JsonPrimitive)?.isString == true }
}.getOrDefault(false)

/**
 * Runs the caller's workspace's warning surveys over [sessionDirs] before a report is built from
 * them, so it shows each run's warnings: every report that builds HTML from sessions calls this.
 * Never throws: a report without warnings beats no report. [streamsReported]: [writeDerivedStreams]
 * ran over them first and reported any stream that failed.
 */
internal fun writeRunWarnings(sessionDirs: List<File>, streamsReported: Boolean) {
  runCatching { RunWarningSurveys.run(sessionDirs, workspaceConfigDir(), streamsReported) }
    .onFailure { Console.error("Warning surveys did not run: ${it.message}. The report is built without warnings.") }
}

/**
 * The config dir of the workspace the caller is in, or null outside one: `TRAILBLAZE_CONFIG_DIR` when
 * it names a directory, else the nearest `trailblaze.yaml` anchor above the caller. The surveys CLI
 * finds its workspace by the same rule (`findWorkspaceConfigDir`), so the two agree on where it is.
 */
private fun workspaceConfigDir(): File? {
  // Relative to the caller, as the surveys CLI resolves it: under `--gradle` the JVM runs elsewhere.
  System.getenv("TRAILBLAZE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
    ?.let { CliCallerContext.callerCwd().resolve(it).toFile() }?.takeIf { it.isDirectory }?.let { return it }
  return (findWorkspaceRoot(CliCallerContext.callerCwd()) as? WorkspaceRoot.Configured)?.configDir?.toFile()
}

/**
 * Whether a report over [sessionDirs] has derived streams to write or remove: a survey file under
 * [configDir], or a local module it imports, declares one, or a session holds some written before.
 * Read from source, so a report with neither starts no surveys runtime.
 */
internal fun hasDerivedStreamWork(configDir: File, sessionDirs: List<File>): Boolean =
  sessionDirs.any { File(it, "events/derived.json").exists() } ||
    WorkspaceSurveyBundleSource.surveyFiles(configDir).any { mayDeclareStreams(it) }

/**
 * Whether [file] or a local module it imports, at any depth, calls `eventStream(`: a survey file can
 * re-export a stream a helper declares. An import in a comment is followed too, which costs only a
 * read; a package import is not followed.
 */
private fun mayDeclareStreams(file: File, seen: MutableSet<File> = mutableSetOf()): Boolean {
  if (!file.isFile || !seen.add(file.canonicalFile)) return false
  val text = file.readText()
  if (SurveyBundles.declaredStreams(text).isNotEmpty()) return true
  return LOCAL_IMPORT.findAll(text).any { match ->
    localModule(file.parentFile, match.groupValues[2])?.let { mayDeclareStreams(it, seen) } == true
  }
}

/** A relative specifier after `from`, `import` or `import(`. */
private val LOCAL_IMPORT = Regex("""(?<![\w$])(?:from|import)\s*\(?\s*(["'])(\.{1,2}/[^"']+)\1""")

private val MODULE_EXTENSIONS = listOf(".ts", ".mts", ".js", ".mjs")

/**
 * The file [specifier] names from [dir], as the bundler finds it: as written, with an extension
 * added, a `.js` written for its `.ts`, or a directory's index.
 */
private fun localModule(dir: File, specifier: String): File? {
  val base = File(dir, specifier)
  val candidates = listOf(base) +
    MODULE_EXTENSIONS.map { File(base.path + it) } +
    listOfNotNull(
      specifier.takeIf { it.endsWith(".js") }?.let { File(base.path.removeSuffix(".js") + ".ts") },
      specifier.takeIf { it.endsWith(".mjs") }?.let { File(base.path.removeSuffix(".mjs") + ".mts") },
    ) +
    MODULE_EXTENSIONS.map { File(base, "index$it") }
  return candidates.firstOrNull { it.isFile }
}

/**
 * Runs [names] (every survey when empty) over [sessionDirs] — session directories, or a directory
 * of them — before the HTML report is built: each session's own `surveys.json` gets their findings,
 * which is where the report reads a run's warnings from. Their report waits in a scratch directory
 * until [placeSurveyReport] moves it beside the HTML.
 */
internal fun draftSurveyReport(sessionDirs: List<File>, names: List<String>): SurveyReportDraft {
  val dir = Files.createTempDirectory("trailblaze-report-surveys").toFile()
  val args = buildList {
    add("--write")
    // The streams pass before this one already recorded any failed stream on its session.
    add("--allow-stream-errors")
    add("--format"); add("markdown")
    add("--out"); add(File(dir, SURVEY_DRAFT_JSON).absolutePath)
    sessionDirs.forEach { add("--sessions"); add(it.absolutePath) }
    names.forEach { add("--survey"); add(it) }
  }
  val exit = SurveyCli.run(args, CliCallerContext.callerCwd().toAbsolutePath().toFile(), stdout = File(dir, SURVEY_DRAFT_MARKDOWN))
  return SurveyReportDraft(dir, exit)
}

/**
 * Moves [draft] beside the HTML report as [markdown] and [json], replacing an earlier run's, prints
 * both paths, and removes the draft's directory. Returns the surveys CLI's exit code.
 */
internal fun placeSurveyReport(draft: SurveyReportDraft, markdown: File, json: File): Int {
  try {
    markdown.delete()
    json.delete()
    File(draft.dir, SURVEY_DRAFT_MARKDOWN).takeIf { it.isFile }?.let { relocate(it, markdown) }
    File(draft.dir, SURVEY_DRAFT_JSON).takeIf { it.isFile }?.let { relocate(it, json) }
    announceSurveyReport(markdown, json, draft.exit)
    return draft.exit
  } catch (e: Exception) {
    // A failure of the survey report alone: the exports after it still run.
    Console.error("Error: could not write the survey report beside the HTML: ${e.message ?: e.javaClass.simpleName}")
    return TrailblazeExitCode.INFRA_FAILED.code
  } finally {
    draft.dir.deleteRecursively()
  }
}

private const val SURVEY_DRAFT_MARKDOWN = "surveys.md"
private const val SURVEY_DRAFT_JSON = "surveys.json"

/** True, with the error printed, when [json] is one of [sessionDirs]' stored survey results. */
internal fun overwritesStoredSurveys(sessionDirs: List<File>, json: File): Boolean {
  if (sessionDirs.none { File(it, "surveys.json").canonicalFile == json.canonicalFile }) return false
  Console.error("Error: ${json.path} is where a session stores its survey results; write the report somewhere else.")
  return true
}

private fun announceSurveyReport(markdown: File, json: File, exit: Int) {
  if (markdown.isFile && markdown.length() > 0) Console.info("Surveys: ${markdown.fileUrl()}")
  if (json.isFile) Console.info("Surveys JSON: ${json.fileUrl()}")
  if (exit != TrailblazeExitCode.SUCCESS.code) Console.error("Surveys did not all run cleanly (exit $exit); see above.")
}

/**
 * The prefix a report written into [reportDir] puts before `<sessionId>/<file>` to reach the
 * session directories under [logsDir]: `../` for the default `logs/reports/`, and empty for a
 * report written into the logs directory itself. Relative rather than absolute, so the report and
 * its images can be moved or hosted together as long as their layout is kept.
 *
 * Both paths are only normalized, never canonicalized: a browser resolves the prefix against the
 * report's URL, which keeps a symlinked directory's own pathname, so following the link here would
 * compute a path from somewhere the browser never is.
 *
 * Null when no relative path exists, as with logs on `C:` and a report on `D:`. An absolute `file:`
 * prefix is no way out: the viewer only accepts relative or http(s) image URLs, so the caller
 * embeds the images instead.
 */
internal fun linkedImageBaseUrl(logsDir: Path, reportDir: Path): String? {
  val relative = try {
    reportDir.normalize().relativize(logsDir.normalize())
  } catch (_: IllegalArgumentException) {
    return null
  }
  return if (relative.toString().isEmpty()) "" else relative.joinToString("/") { percentEncodeSegment(it.toString()) } + "/"
}

/**
 * Encoded here because the renderer only encodes the session id and file name it appends, and the
 * viewer drops a screenshot URL that carries whitespace or a quote. `.` and `..` pass through as
 * the path steps they are.
 */
private fun percentEncodeSegment(segment: String): String = buildString {
  for (byte in segment.toByteArray(Charsets.UTF_8)) {
    val c = byte.toInt().toChar()
    if (byte >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c) else append('%').append("%02X".format(byte.toInt() and 0xFF))
  }
}

/**
 * Generate HTML + JSON reports for one or all sessions.
 *
 * @param sessionId When non-null, narrows to a single session. Prefix matching is
 *   applied so callers can pass an abbreviated ID.
 * @param outputDir When non-null, all artifacts are moved into this directory with
 *   canonical names (`report-interactive.html`, `summary.json`, `timeline.{mp4,gif,webp}`).
 * @param videoSpec / [gifSpec] / [webpSpec] When non-null, an animation export is requested.
 *   The string is either an explicit destination path or [ReportCommand.USE_DEFAULT_PATH]
 *   meaning "drop the artifact at the conventional location."
 * @param suppressGif / [suppressWebp] When true, suppress the auto-emitted companion file
 *   under the shared-capture model. Setting either when the corresponding spec is
 *   non-null is a usage error rejected upstream by [ReportCommand.call].
 * @param maxBytes Byte ceiling for each exported artifact, or null for uncapped.
 * @param maxBytesStrict Whether exhausting the 480px readability floor without fitting
 *   [maxBytes] fails the export (an explicitly-requested cap) or only warns and keeps the
 *   oversized artifact (the 10MB default nobody asked for). See [MaxArtifactSize.Cap].
 * @param shareUrl When non-null (`--share-url`), the canonical hosted URL baked into the
 *   interactive report for its Copy link. Validated as http(s) upstream by
 *   [ReportCommand.call].
 * @param fullEventPayloads When true (`--full-report-payloads`), event formatters embed full
 *   payloads in the interactive report even for passed sessions instead of applying their
 *   report size budgets.
 * @param linkImages When true (`--link-images`), the interactive report links each session's
 *   screenshots and recording instead of embedding them; see [linkedImageBaseUrl].
 * @param verbose When true (`-v`/`--verbose`), the generator's own progress logs reach the
 *   terminal instead of being swallowed. Off by default: those lines are one-per-session
 *   breadcrumbs, and the report paths are what the person asked for.
 * @param surveys When non-null (`--surveys`), the surveys to run over the report's sessions —
 *   empty for all of them; their own report is written beside the HTML; see [draftSurveyReport].
 *   The workspace's warning surveys run either way, before the HTML is built, so it shows the
 *   warnings they find; see [RunWarningSurveys].
 * @return the exit code for the run. Every path returns rather than exiting the process — the
 *   caller ([ReportCommand.call]) owns process termination, so one force-exit covers all of them.
 */
internal fun generateSessionReport(
  app: TrailblazeDesktopApp,
  sessionId: String?,
  open: Boolean,
  outputDir: File? = null,
  videoSpec: String? = null,
  gifSpec: String? = null,
  webpSpec: String? = null,
  storyboardSpec: String? = null,
  storyboardColumns: Int? = null,
  storyboardIncludeYaml: Boolean = false,
  suppressGif: Boolean = false,
  suppressWebp: Boolean = false,
  maxBytes: Long? = null,
  maxBytesStrict: Boolean = true,
  shareUrl: String? = null,
  fullEventPayloads: Boolean = false,
  linkImages: Boolean = false,
  verbose: Boolean = false,
  surveys: List<String>? = null,
): Int {
  val logsRepo = app.deviceManager.logsRepo
  val allIds = logsRepo.getSessionIds()
  if (allIds.isEmpty()) {
    Console.log("No sessions found in logs directory.")
    return TrailblazeExitCode.SUCCESS.code
  }

  // Neither "no such session" nor "ambiguous prefix" is an infra failure — the daemon, network,
  // and logs repo all worked correctly, the user typed an id that does not name one session.
  val sessionIds = if (sessionId != null) {
    SessionIdResolver.resolve(allIds, sessionId) ?: return TrailblazeExitCode.MISUSE.code
  } else {
    allIds
  }

  // Only a whole-directory report is entitled to the directory's skip records. `--id` narrows the
  // report to one session, and a skip belongs to no session at all. Resolved once and given to both
  // artifacts below: the HTML and the JSON are two halves of one report and have to list the same
  // trails, or a reader who diffs them finds rows in one that the other says never existed.
  val directorySkips = if (sessionId == null) SkippedTrails.read(logsRepo.logsDir) else emptyList()

  // The sessions the HTML covers, not the logs directory, whose zips and nested directories the
  // surveys CLI would also read.
  val surveySessionDirs = sessionIds.map { File(logsRepo.logsDir, it.value) }
  // Refused before anything is written.
  if (surveys != null && outputDir != null && overwritesStoredSurveys(surveySessionDirs, File(outputDir, "surveys.json"))) {
    return TrailblazeExitCode.MISUSE.code
  }
  // Before the HTML, which renders every `events/*.ndjson`: a derived stream then shows as its own lane.
  // On every report, not only with --surveys; a stream that fails, or anything else here, leaves the report and its exit alone.
  runCatching { writeDerivedStreams(surveySessionDirs) }
    .onFailure { Console.error("Derived streams were not written: ${it.message}. The report is built without them.") }
  // The surveys run after the streams, which they may read, and before the HTML: each stores its
  // findings in its session's surveys.json, which is where the report reads the run's warnings from.
  // The warning surveys run on every report, unless a bare --surveys runs them with the rest.
  if (surveys?.isEmpty() != true) writeRunWarnings(surveySessionDirs, streamsReported = true)
  // Their own report waits in a scratch directory until the HTML has a name to sit beside.
  val surveyDraft = surveys?.let { names -> draftSurveyReport(surveySessionDirs, names) }

  // Until the draft is placed beside the HTML, a failure on the way there must not leave it behind.
  val (htmlFile, jsonFile) = try {
    Console.log("Generating HTML + JSON report for ${sessionIds.size} session(s)...")

    // The generator narrates its work on Console.log (tool-class discovery, per-session clock
    // normalisation, timings) — one line per session, dozens in a lived-in logs directory. Those are
    // debugging breadcrumbs; the report paths below are the output. Failures still reach the user:
    // RunReportGenerator reports them on Console.error, which quiet mode does not suppress. `-v`
    // restores them for the case where a report is slow or empty and the breadcrumbs are the only
    // way to see how far the generator got.
    val reportGenerator = quietUnlessVerbose(verbose) { app.createCliReportGenerator() }
    // Resolved against where the HTML ends up, not where the generator first writes it: an
    // --output-dir report is moved there below, and its links have to survive the move.
    val imageBaseUrl = if (linkImages) {
      linkedImageBaseUrl(
        logsRepo.logsDir.toPath().toAbsolutePath(),
        (outputDir ?: File(logsRepo.logsDir, "reports")).toPath().toAbsolutePath(),
      ).also {
        if (it == null) {
          Console.error("Warning: --link-images needs the report and the logs on one drive; embedding the images instead.")
        }
      }
    } else {
      null
    }
    val generatedHtml = quietUnlessVerbose(verbose) {
      reportGenerator.generateHtmlReports(
        logsRepo,
        sessionIds,
        shareUrl = shareUrl,
        fullEventPayloads = fullEventPayloads,
        imageBaseUrl = imageBaseUrl,
        skips = directorySkips,
      )
    }
    if (generatedHtml == null) {
      // RunReportGenerator already logged the specific cause (bun missing / subprocess failure).
      Console.error("Failed to generate the HTML report.")
      surveyDraft?.dir?.deleteRecursively()
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val initialJson = quietUnlessVerbose(verbose) {
      reportGenerator.generateJsonReport(logsRepo, sessionIds, skips = directorySkips)
    }
    if (initialJson == null) {
      // Partial failure: HTML succeeded. Better to ship that than fail the whole command.
      Console.error("Warning: failed to generate JSON report — HTML produced anyway.")
    }

    // If --output-dir is set, relocate the auto-generated artifacts to canonical names there.
    // We move (rather than copy) so the auto-named originals don't accumulate in logs/reports/.
    if (outputDir != null) {
      outputDir.mkdirs()
      // Still `report-interactive.html` even though it is now the only report: the docs report
      // gallery publishes this file BY NAME into `docs/report-assets/<slug>/`, and a rename would
      // leave the gallery silently serving its "report pending" placeholder (docs_hooks.py).
      File(outputDir, "report-interactive.html").also { relocate(generatedHtml, it) } to
        initialJson?.let { src -> File(outputDir, "summary.json").also { relocate(src, it) } }
    } else {
      generatedHtml to initialJson
    }
  } catch (e: Exception) {
    surveyDraft?.dir?.deleteRecursively()
    throw e
  }

  Console.info("")
  Console.info("HTML: ${htmlFile.fileUrl()}")
  if (jsonFile != null) Console.info("JSON: ${jsonFile.fileUrl()}")
  // A survey's failure is reported after the exports below, so it never costs the ones asked for.
  var surveyExit = TrailblazeExitCode.SUCCESS.code
  if (surveyDraft != null) {
    surveyExit = placeSurveyReport(
      surveyDraft,
      markdown = File(htmlFile.parentFile, if (outputDir != null) "surveys.md" else "${htmlFile.nameWithoutExtension}.surveys.md"),
      json = File(htmlFile.parentFile, if (outputDir != null) "surveys.json" else "${htmlFile.nameWithoutExtension}.surveys.json"),
    )
  }

  // The animated timeline exports (--video/--gif/--webp) drive headless Playwright over the
  // report's autoplay timeline: --video screen-records `?autoplay=1` playing in real time, and
  // --gif/--webp step `?autoplay=step` one frame at a time (see PlaywrightReportCapture).
  // (--storyboard builds its own grid HTML from logs and only needs a path anchor.)
  if (videoSpec != null || gifSpec != null || webpSpec != null) {
    Console.log("Recording the report's timeline: ${htmlFile.fileUrl()}")
  }

  val (effectiveGifSpec, effectiveWebpSpec) =
    resolveSharedCaptureSpecs(gifSpec, webpSpec, suppressGif, suppressWebp)

  // Defaults are only needed when an export was requested AND the user didn't supply an
  // explicit path. Computing `sessionIds.single()` unconditionally crashed the
  // all-sessions report path (no --id, many sessions in the logs dir). The exports
  // require --id/--current — already enforced upstream in ReportCommand.call() — so
  // when we do reach the single-session branch, the list is guaranteed to be size 1.
  val needsDefaultName = videoSpec == ReportCommand.USE_DEFAULT_PATH ||
    effectiveGifSpec == ReportCommand.USE_DEFAULT_PATH ||
    effectiveWebpSpec == ReportCommand.USE_DEFAULT_PATH ||
    storyboardSpec == ReportCommand.USE_DEFAULT_PATH
  val defaultNames = when {
    !needsDefaultName -> ExportDefaults("", "", "", "")
    outputDir != null -> ExportDefaults(
      mp4 = "timeline.mp4",
      gif = "timeline.gif",
      webp = "timeline.webp",
      storyboard = "storyboard.webp",
    )
    else -> {
      val stem = sessionIds.single().value
      ExportDefaults(
        mp4 = "$stem.mp4",
        gif = "$stem.gif",
        webp = "$stem.webp",
        storyboard = "$stem.storyboard.webp",
      )
    }
  }

  // All-or-nothing semantics across video + GIF + WebP. Files are registered BEFORE the
  // encode call (not after) because an encoder may write a partial file and then throw —
  // e.g. --max-size floor exhaustion in ReportGif/WebpExporter.encode() runs assembleX()
  // first (writing to disk) before the enforce loop calls error(). If we registered after
  // the encode returned, the on-disk partial-encode would slip the cleanup. Validate-
  // migration reads timeline.webp from --output-dir under the assumption its presence
  // means a fresh successful encode; a stale .webp from a failed prior run would shadow
  // that contract.
  val outputsToCleanupOnFailure = mutableListOf<File>()

  val videoFile = resolveExportPath(videoSpec, htmlFile, defaultNames.mp4)
  if (videoFile != null) {
    try {
      Console.log("Exporting timeline autoplay to ${videoFile.absolutePath} ...")
      outputsToCleanupOnFailure.add(videoFile)
      ReportVideoExporter.export(
        reportHtml = htmlFile,
        outputMp4 = videoFile,
        maxBytes = maxBytes,
        maxBytesStrict = maxBytesStrict,
      )
      Console.info("Video: ${videoFile.absolutePath} (${videoFile.length() / 1024}KB)")
    } catch (e: Exception) {
      Console.error("Failed to export report video: ${e.message}")
      cleanupOutputsOnFailure(outputsToCleanupOnFailure)
      return TrailblazeExitCode.INFRA_FAILED.code
    }
  }

  // Shared frame capture for GIF + WebP. The capture loop is ~90% of the wall-clock
  // cost (30s+ of headless screenshotting); running it once instead of twice halves the
  // export time when both formats are produced. Each encoder runs against the same
  // frames-on-disk source — see the `encode()` entry points on each exporter object.
  val gifFile = resolveExportPath(effectiveGifSpec, htmlFile, defaultNames.gif)
  val webpFile = resolveExportPath(effectiveWebpSpec, htmlFile, defaultNames.webp)
  if (gifFile != null || webpFile != null) {
    // Preflight the libwebp tools BEFORE capture if we're going to encode WebP. Same
    // fail-fast rationale as the single-format path: avoid burning 30s on screenshotting
    // only to discover the WebP tooling isn't installed.
    if (webpFile != null) {
      try {
        ReportWebpExporter.requireWebpTools()
      } catch (e: IllegalStateException) {
        Console.error("Failed to export report WebP: ${e.message}")
        cleanupOutputsOnFailure(outputsToCleanupOnFailure)
        return TrailblazeExitCode.INFRA_FAILED.code
      }
    }
    val ws = PlaywrightReportCapture.newFrameWorkspace("report")
    Console.log(
      "[ReportCommand] launching headless Chromium for export " +
        "(one-time ~150MB download on first use; reuses the same browser cache " +
        "as other Trailblaze web commands)",
    )
    try {
      val capture = try {
        PlaywrightReportCapture.captureFrames(
          reportHtml = htmlFile,
          framesDir = ws.framesDir,
          headless = true,
          deviceId = ws.deviceId,
          tag = "ReportCommand",
        )
      } catch (e: Exception) {
        // Single capture failure poisons both encodes — surface once and bail. Video
        // (if it ran) is in outputsToCleanupOnFailure and will be removed here.
        Console.error("Failed to capture report frames: ${e.message}")
        cleanupOutputsOnFailure(outputsToCleanupOnFailure)
        return TrailblazeExitCode.INFRA_FAILED.code
      }
      if (gifFile != null) {
        try {
          Console.log("Exporting timeline autoplay to ${gifFile.absolutePath} ...")
          outputsToCleanupOnFailure.add(gifFile)
          ReportGifExporter.encode(ws.framesDir, capture, gifFile, maxBytes, maxBytesStrict)
          Console.info("GIF: ${gifFile.absolutePath} (${gifFile.length() / 1024}KB)")
        } catch (e: Exception) {
          Console.error("Failed to export report GIF: ${e.message}")
          cleanupOutputsOnFailure(outputsToCleanupOnFailure)
          return TrailblazeExitCode.INFRA_FAILED.code
        }
      }
      if (webpFile != null) {
        try {
          Console.log("Exporting timeline autoplay to ${webpFile.absolutePath} ...")
          outputsToCleanupOnFailure.add(webpFile)
          ReportWebpExporter.encode(ws.framesDir, capture, webpFile, maxBytes, maxBytesStrict)
          Console.info("WebP: ${webpFile.absolutePath} (${webpFile.length() / 1024}KB)")
        } catch (e: Exception) {
          Console.error("Failed to export report WebP: ${e.message}")
          cleanupOutputsOnFailure(outputsToCleanupOnFailure)
          return TrailblazeExitCode.INFRA_FAILED.code
        }
      }
    } finally {
      // Surface cleanup failures rather than swallowing them — accumulation of stale
      // temp dirs under /tmp/trailblaze-report-* otherwise goes silent.
      runCatching { ws.tempDir.deleteRecursively() }
        .onFailure {
          Console.log(
            "[ReportCommand] workspace cleanup failed at ${ws.tempDir.absolutePath}: ${it.message}",
          )
        }
    }
  }

  // Storyboard runs on its OWN Playwright session — it loads a purpose-built grid HTML
  // (not the timeline report) and takes a single fullPage screenshot. Not part of the
  // shared-capture block above because the input HTML is different. The companion
  // .storyboard.html sits next to the .storyboard.webp for standalone viewing.
  val storyboardWebpFile = resolveExportPath(storyboardSpec, htmlFile, defaultNames.storyboard)
  if (storyboardWebpFile != null) {
    val storyboardHtmlFile = File(
      storyboardWebpFile.parentFile ?: File("."),
      storyboardWebpFile.nameWithoutExtension + ".html",
    )
    try {
      Console.log("Exporting storyboard to ${storyboardWebpFile.absolutePath} ...")
      outputsToCleanupOnFailure.add(storyboardWebpFile)
      outputsToCleanupOnFailure.add(storyboardHtmlFile)
      ReportStoryboardExporter.export(
        sessionId = sessionIds.single(),
        logsRepo = logsRepo,
        outputWebp = storyboardWebpFile,
        outputHtml = storyboardHtmlFile,
        columns = storyboardColumns,
        includeYaml = storyboardIncludeYaml,
        headless = true,
        maxBytes = maxBytes,
        maxBytesStrict = maxBytesStrict,
      )
      Console.info("Storyboard: ${storyboardWebpFile.absolutePath} (${storyboardWebpFile.length() / 1024}KB)")
    } catch (e: Exception) {
      Console.error("Failed to export storyboard: ${e.message}")
      cleanupOutputsOnFailure(outputsToCleanupOnFailure)
      return TrailblazeExitCode.INFRA_FAILED.code
    }
  }

  if (open) {
    TrailblazeDesktopUtil.openInDefaultBrowser("${htmlFile.fileUrl()}")
  }

  return surveyExit
}

private data class ExportDefaults(
  val mp4: String,
  val gif: String,
  val webp: String,
  val storyboard: String,
)

/**
 * Apply the auto-emit-both rule for the shared-capture GIF/WebP path. If the user passed
 * bare `--gif` or bare `--webp` (no path) the frame-capture path runs regardless, so we
 * emit the companion format at the default path for free unless `--no-gif` / `--no-webp`
 * suppresses it. Explicit paths do NOT auto-promote: `--gif foo.gif` writes only foo.gif.
 *
 * Returns the effective specs for GIF and WebP respectively. Null means the format will
 * not be produced. Callers should treat [ReportCommand.USE_DEFAULT_PATH] as "resolve to
 * the default location."
 *
 * Internal so [ReportCommandSharedCaptureTest] can pin the matrix without going through
 * a real frame capture.
 */
internal fun resolveSharedCaptureSpecs(
  gifSpec: String?,
  webpSpec: String?,
  suppressGif: Boolean,
  suppressWebp: Boolean,
): Pair<String?, String?> {
  val anyBareRequested = gifSpec == ReportCommand.USE_DEFAULT_PATH ||
    webpSpec == ReportCommand.USE_DEFAULT_PATH
  val effectiveGifSpec = when {
    gifSpec != null -> gifSpec
    anyBareRequested && !suppressGif -> ReportCommand.USE_DEFAULT_PATH
    else -> null
  }
  val effectiveWebpSpec = when {
    webpSpec != null -> webpSpec
    anyBareRequested && !suppressWebp -> ReportCommand.USE_DEFAULT_PATH
    else -> null
  }
  return effectiveGifSpec to effectiveWebpSpec
}

/**
 * Best-effort cleanup of output files we registered before encoding during a multi-format
 * export that subsequently failed. Files are registered BEFORE the encoder runs (not
 * after), so this also removes partial-write artifacts left by an encoder that wrote a
 * file and then threw — e.g. `MaxArtifactSize.enforce()` exhausting the readability
 * floor in `ReportGifExporter.encode()` / `ReportWebpExporter.encode()`. Per-file
 * failures are logged (not just swallowed) so a permission flake on a CI mount leaves a
 * breadcrumb for whoever debugs the stale file.
 */
private fun cleanupOutputsOnFailure(outputs: List<File>) {
  outputs.forEach { file ->
    runCatching { file.delete() }
      .onFailure {
        Console.log(
          "[ReportCommand] failed to delete partial output ${file.absolutePath}: ${it.message}",
        )
      }
  }
}

private fun resolveExportPath(spec: String?, htmlFile: File, defaultName: String): File? = when (spec) {
  null -> null
  ReportCommand.USE_DEFAULT_PATH -> File(htmlFile.parentFile ?: File("."), defaultName)
  else -> File(spec)
}

/**
 * Move `source` to `dest`, overwriting any existing file. Falls back to copy+delete if a
 * direct rename fails (cross-filesystem moves, e.g. `/tmp/...` → user-supplied path on a
 * different volume).
 */
private fun relocate(source: File, dest: File) {
  if (dest.exists()) dest.delete()
  if (!source.renameTo(dest)) {
    source.copyTo(dest, overwrite = true)
    source.delete()
  }
}
