package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Generate reports from session recordings.
 *
 * Every invocation writes an HTML index (humans) and a JSON `CiSummaryReport` (CI).
 * With `--id` or `--current`, the report narrows to a single session and the timeline
 * exports (`--video`, `--gif`, `--webp`) become available.
 *
 * Examples:
 *   trailblaze report                              - HTML + JSON for all sessions
 *   trailblaze report --open                       - ...and open the HTML in a browser
 *   trailblaze report --id abc123                  - HTML + JSON for one session
 *   trailblaze report --current                    - HTML + JSON for the currently active session
 *   trailblaze report --current --gif              - HTML + JSON + GIF + animated-WebP (one bare
 *                                                    --gif or --webp flag triggers a shared frame
 *                                                    capture and auto-emits both formats; pass
 *                                                    explicit paths to limit which file is written)
 *   trailblaze report --current --webp --no-gif    - WebP only (suppress the auto-GIF companion)
 *   trailblaze report --id abc --gif out.gif       - Only out.gif (explicit path = single-format)
 *   trailblaze report --id abc123 --output-dir out - Drop report.html / summary.json /
 *                                                    (timeline.{mp4,gif,webp}) into `out/`
 */
@Command(
  name = "report",
  mixinStandardHelpOptions = true,
  description = [
    "Generate an HTML report for session recordings, plus a best-effort JSON summary, and " +
      "optionally MP4/GIF/WebP exports for a single session. JSON-only failures log a warning " +
      "and still exit 0 — HTML is the primary artifact and is what gates the exit code.",
  ]
)
class ReportCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--id"],
    description = [
      "Narrow to a single session (defaults to all sessions). " +
        "Use `trailblaze session list` to find IDs. Prefix matching is supported.",
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
      "Write all artifacts into this directory with canonical names (report.html, " +
        "summary.json, timeline.mp4, timeline.gif, timeline.webp). Created if it " +
        "doesn't exist. If omitted, artifacts land in the default `logs/reports/` " +
        "location with timestamped names.",
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
        "is set). Typically 25–50% smaller than the equivalent --gif (24-bit color, " +
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

  @Option(
    names = ["--max-size"],
    description = [
      "Cap each exported timeline artifact (--gif / --video / --webp) at the given byte " +
        "size. Accepts plain bytes (1024000) or human-readable suffixes (10MB, 5M, 1.5G). " +
        "After the initial encode, the exporter iteratively re-encodes at smaller " +
        "viewport widths (1280→1024→720→640→480) until the artifact fits, then stops. " +
        "If even the readability floor (480px) is still over the cap, the export fails " +
        "with an actionable error — drop GIF for --webp or --video (both compress " +
        "dramatically better), or shorten the recorded session (fewer trail steps, or " +
        "split into multiple sessions). The flag is applied per artifact, so " +
        "`--gif --webp --max-size=10MB` caps each one independently.",
    ],
  )
  var maxSize: String? = null

  override fun call(): Int {
    if (id != null && current) {
      Console.error("--id and --current are mutually exclusive.")
      return CommandLine.ExitCode.USAGE
    }
    val resolvedId: String? = when {
      id != null -> id
      current -> resolveActiveSessionId() ?: run {
        Console.error(
          "--current: no active session found. Start a session with `trailblaze session start`, " +
            "or pass --id explicitly.",
        )
        return CommandLine.ExitCode.SOFTWARE
      }
      else -> null
    }
    if ((videoOutput != null || gifOutput != null || webpOutput != null) && resolvedId == null) {
      Console.error(
        "--video / --gif / --webp require --id or --current. The all-sessions index has nothing " +
          "to play, so the exporter has nothing to record.",
      )
      return CommandLine.ExitCode.USAGE
    }
    // --no-gif / --no-webp guards. The opt-outs only suppress *auto-emission* under the
    // shared-capture model; pairing them with the corresponding format (in any form) is
    // contradictory, and stacking both opt-outs leaves nothing to produce.
    if (noGif && noWebp) {
      Console.error("--no-gif --no-webp together leaves nothing to produce. Drop one.")
      return CommandLine.ExitCode.USAGE
    }
    if (noGif && gifOutput != null) {
      Console.error("--no-gif contradicts --gif. Drop one of them.")
      return CommandLine.ExitCode.USAGE
    }
    if (noWebp && webpOutput != null) {
      Console.error("--no-webp contradicts --webp. Drop one of them.")
      return CommandLine.ExitCode.USAGE
    }
    if (noGif && gifOutput == null && webpOutput == null) {
      Console.error(
        "--no-gif only has an effect when --webp triggers auto-emission. Add --webp.",
      )
      return CommandLine.ExitCode.USAGE
    }
    if (noWebp && gifOutput == null && webpOutput == null) {
      Console.error(
        "--no-webp only has an effect when --gif triggers auto-emission. Add --gif.",
      )
      return CommandLine.ExitCode.USAGE
    }
    if (maxSize != null && videoOutput == null && gifOutput == null && webpOutput == null) {
      Console.error(
        "--max-size has no effect without an artifact to cap. Add --gif, --video, or " +
          "--webp — e.g. `trailblaze report --id <id> --webp --max-size=$maxSize`.",
      )
      return CommandLine.ExitCode.USAGE
    }
    val maxBytes: Long? = maxSize?.let {
      try {
        MaxArtifactSize.parseSize(it)
      } catch (e: IllegalArgumentException) {
        Console.error("--max-size: ${e.message}")
        return CommandLine.ExitCode.USAGE
      }
    }
    return generateSessionReport(
      parent.appProvider(),
      resolvedId,
      open,
      outputDir = outputDir,
      videoSpec = videoOutput,
      gifSpec = gifOutput,
      webpSpec = webpOutput,
      suppressGif = noGif,
      suppressWebp = noWebp,
      maxBytes = maxBytes,
    )
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
  }
}

/**
 * Generate HTML + JSON reports for one or all sessions.
 *
 * @param sessionId When non-null, narrows to a single session. Prefix matching is
 *   applied so callers can pass an abbreviated ID.
 * @param outputDir When non-null, all artifacts are moved into this directory with
 *   canonical names (`report.html`, `summary.json`, `timeline.{mp4,gif,webp}`).
 * @param videoSpec / [gifSpec] / [webpSpec] When non-null, an animation export is requested.
 *   The string is either an explicit destination path or [ReportCommand.USE_DEFAULT_PATH]
 *   meaning "drop the artifact at the conventional location."
 * @param suppressGif / [suppressWebp] When true, suppress the auto-emitted companion file
 *   under the shared-capture model. Setting either when the corresponding spec is
 *   non-null is a usage error rejected upstream by [ReportCommand.call].
 */
internal fun generateSessionReport(
  app: TrailblazeDesktopApp,
  sessionId: String?,
  open: Boolean,
  outputDir: File? = null,
  videoSpec: String? = null,
  gifSpec: String? = null,
  webpSpec: String? = null,
  suppressGif: Boolean = false,
  suppressWebp: Boolean = false,
  maxBytes: Long? = null,
): Int {
  val logsRepo = app.deviceManager.logsRepo
  val allIds = logsRepo.getSessionIds()
  if (allIds.isEmpty()) {
    Console.log("No sessions found in logs directory.")
    return CommandLine.ExitCode.OK
  }

  val sessionIds = if (sessionId != null) {
    val matches = allIds.filter { it.value == sessionId || it.value.startsWith(sessionId) }
    if (matches.isEmpty()) {
      Console.error("Error: No session matching '$sessionId' found.")
      return CommandLine.ExitCode.SOFTWARE
    }
    if (matches.size > 1) {
      Console.error("Error: Session prefix '$sessionId' is ambiguous: ${matches.joinToString(", ") { it.value }}")
      return CommandLine.ExitCode.SOFTWARE
    }
    matches
  } else {
    allIds
  }

  Console.log("Generating HTML + JSON report for ${sessionIds.size} session(s)...")

  val reportGenerator = app.createCliReportGenerator()
  val initialHtml = reportGenerator.generateReport(logsRepo, sessionIds)
  if (initialHtml == null) {
    Console.error("Failed to generate HTML report. No report template found.")
    Console.error("Ensure trailblaze_report_template.html is bundled or at the git root.")
    return CommandLine.ExitCode.SOFTWARE
  }
  val initialJson = reportGenerator.generateJsonReport(logsRepo, sessionIds)
  if (initialJson == null) {
    // Partial failure: HTML succeeded. Better to ship that than fail the whole command.
    Console.error("Warning: failed to generate JSON report — HTML produced anyway.")
  }

  // If --output-dir is set, relocate the auto-generated artifacts to canonical names there.
  // We move (rather than copy) so the auto-named originals don't accumulate in logs/reports/.
  val (htmlFile, jsonFile) = if (outputDir != null) {
    outputDir.mkdirs()
    val htmlDest = File(outputDir, "report.html")
    val jsonDest = File(outputDir, "summary.json")
    relocate(initialHtml, htmlDest)
    initialJson?.let { relocate(it, jsonDest) }
    htmlDest to (initialJson?.let { jsonDest })
  } else {
    initialHtml to initialJson
  }

  Console.info("\nHTML: file://${htmlFile.absolutePath}")
  if (jsonFile != null) Console.info("JSON: file://${jsonFile.absolutePath}")

  val (effectiveGifSpec, effectiveWebpSpec) =
    resolveSharedCaptureSpecs(gifSpec, webpSpec, suppressGif, suppressWebp)

  // Defaults are only needed when an export was requested AND the user didn't supply an
  // explicit path. Computing `sessionIds.single()` unconditionally crashed the
  // all-sessions report path (no --id, many sessions in the logs dir). The exports
  // require --id/--current — already enforced upstream in ReportCommand.call() — so
  // when we do reach the single-session branch, the list is guaranteed to be size 1.
  val needsDefaultName = videoSpec == ReportCommand.USE_DEFAULT_PATH ||
    effectiveGifSpec == ReportCommand.USE_DEFAULT_PATH ||
    effectiveWebpSpec == ReportCommand.USE_DEFAULT_PATH
  val defaultNames = when {
    !needsDefaultName -> ExportDefaults("", "", "")
    outputDir != null -> ExportDefaults("timeline.mp4", "timeline.gif", "timeline.webp")
    else -> {
      val stem = sessionIds.single().value
      ExportDefaults("$stem.mp4", "$stem.gif", "$stem.webp")
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
      ReportVideoExporter.export(reportHtml = htmlFile, outputMp4 = videoFile, maxBytes = maxBytes)
      Console.info("Video: ${videoFile.absolutePath} (${videoFile.length() / 1024}KB)")
    } catch (e: Exception) {
      Console.error("Failed to export report video: ${e.message}")
      cleanupOutputsOnFailure(outputsToCleanupOnFailure)
      return CommandLine.ExitCode.SOFTWARE
    }
  }

  // Shared frame capture for GIF + WebP. The capture loop is ~90% of the wall-clock
  // cost (30s+ of headless screenshotting); running it once instead of twice halves the
  // export time when both formats are produced. Each encoder runs against the same
  // frames-on-disk source — see the `encode()` entry points on each exporter object.
  val gifFile = resolveExportPath(effectiveGifSpec, htmlFile, defaultNames.gif)
  val webpFile = resolveExportPath(effectiveWebpSpec, htmlFile, defaultNames.webp)
  if (gifFile != null || webpFile != null) {
    // Preflight ffmpeg + libwebp_anim BEFORE capture if we're going to encode WebP.
    // Same fail-fast rationale as the single-format path: avoid burning 30s on
    // screenshotting only to discover the encoder isn't installed.
    if (webpFile != null) {
      try {
        ReportWebpExporter.requireLibwebpAnim()
      } catch (e: IllegalStateException) {
        Console.error("Failed to export report WebP: ${e.message}")
        cleanupOutputsOnFailure(outputsToCleanupOnFailure)
        return CommandLine.ExitCode.SOFTWARE
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
        return CommandLine.ExitCode.SOFTWARE
      }
      if (gifFile != null) {
        try {
          Console.log("Exporting timeline autoplay to ${gifFile.absolutePath} ...")
          outputsToCleanupOnFailure.add(gifFile)
          ReportGifExporter.encode(ws.framesDir, capture, gifFile, maxBytes)
          Console.info("GIF: ${gifFile.absolutePath} (${gifFile.length() / 1024}KB)")
        } catch (e: Exception) {
          Console.error("Failed to export report GIF: ${e.message}")
          cleanupOutputsOnFailure(outputsToCleanupOnFailure)
          return CommandLine.ExitCode.SOFTWARE
        }
      }
      if (webpFile != null) {
        try {
          Console.log("Exporting timeline autoplay to ${webpFile.absolutePath} ...")
          outputsToCleanupOnFailure.add(webpFile)
          ReportWebpExporter.encode(ws.framesDir, capture, webpFile, maxBytes)
          Console.info("WebP: ${webpFile.absolutePath} (${webpFile.length() / 1024}KB)")
        } catch (e: Exception) {
          Console.error("Failed to export report WebP: ${e.message}")
          cleanupOutputsOnFailure(outputsToCleanupOnFailure)
          return CommandLine.ExitCode.SOFTWARE
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

  if (open) {
    TrailblazeDesktopUtil.openInDefaultBrowser("file://${htmlFile.absolutePath}")
  }

  // Background threads spawned by the report generator keep the JVM alive after
  // a successful run; force exit to match the prior `trailblaze report` behavior.
  exitProcess(CommandLine.ExitCode.OK)
}

private data class ExportDefaults(val mp4: String, val gif: String, val webp: String)

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
