package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.logs.model.getSessionStartedInfo
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.failureCodeOf
import xyz.block.trailblaze.report.models.failurePayloadOf
import xyz.block.trailblaze.report.models.SessionRecordingInfo
import xyz.block.trailblaze.report.models.SkippedTrail
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateUnifiedRecordedYaml

/**
 * Headless generator for the interactive Trailblaze run report — the CLI/CI counterpart to the
 * in-app "Share as HTML" button. It produces the SAME self-contained, dependency-free HTML the
 * Share button does, by reusing the exact same extraction + renderer
 * ([run-report-core.js][CORE_RESOURCE], the build-time bundle of the run-report-*.ts modules) under
 * a thin bun driver ([run-report-cli.ts][DRIVER_RESOURCE]).
 *
 * This is the ONLY report Trailblaze emits — `trailblaze report`, the after-run report, and the CI
 * report step all land this artifact, and the hosted viewer shell is the same renderer with no data
 * baked in.
 *
 * One report can cover one OR many sessions: a single session opens straight on its detail; several
 * open on a pass/fail session index that drills into each run. Per-session it carries the step
 * timeline, the LLM transcript, the recorded `.trail.yaml`, and the run metadata.
 *
 * Requires `bun` on PATH (the same hard prerequisite the scripted-tool analyzer already imposes).
 * When bun can't be resolved, or the subprocess fails, [generate] returns null — there is no
 * fallback artifact, so callers surface that as an error rather than a degraded mode.
 */
class RunReportGenerator(
  private val bunBinary: File? = BunBinaryResolver.resolveBunBinary(),
  private val environment: Map<String, String> = System.getenv(),
) {

  /**
   * Whether the interactive report can be generated at all (bun resolved). Lets callers skip
   * work that only this generator would consume — e.g. `CliReportGenerator` skips its shared
   * snapshot capture when this is false and the WASM leg is off too.
   */
  val isBunAvailable: Boolean get() = bunBinary != null

  /**
   * Generate the interactive HTML report for [sessionIds] into `logsRepo.logsDir/reports/`.
   *
   * Convenience over the [SessionLogSnapshot] overload: captures a fresh snapshot of each
   * session (after the cheap bun check, so a bun-less environment pays no parse). Callers
   * that already hold snapshots — e.g. `CliReportGenerator`, which shares one capture across
   * the interactive and WASM legs — use the overload directly.
   *
   * @return the report [File], or null if bun is unavailable, no session resolved, or the
   *   subprocess failed (each logged via [Console]).
   */
  @JvmOverloads
  fun generate(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    shareUrl: String? = null,
    fullEventPayloads: Boolean = false,
    imageBaseUrl: String? = null,
    skips: List<SkippedTrail> = emptyList(),
  ): File? {
    if (sessionIds.isEmpty()) return null
    if (bunBinary == null) {
      logBunUnavailable()
      return null
    }
    return generateFromSnapshots(
      logsRepo,
      SessionLogSnapshot.captureAll(logsRepo, sessionIds),
      shareUrl,
      fullEventPayloads,
      imageBaseUrl,
      skips,
    )
  }

  /**
   * Generate the interactive HTML report from already-captured session [snapshots] — no log
   * file is read or decoded here; the report is built entirely from the snapshot (only
   * non-log session assets, e.g. the `.ndjson` files under `events/`, are read by the bun
   * driver).
   *
   * @param shareUrl optional canonical hosted URL baked into the report; its Copy link then
   *   produces deep links against that URL regardless of where the file is opened from.
   * @param fullEventPayloads when true (the `--full-report-payloads` CLI flag), event formatters
   *   embed full payloads even for passed sessions instead of applying their report size budgets
   *   (grep REPORT_SIZE_BUDGET). Failed sessions always embed full payloads regardless.
   * @param imageBaseUrl when non-null, screenshots and video sprite sheets that live on disk are
   *   REFERENCED at `<imageBaseUrl><sessionId>/<file>` instead of base64-embedded, so the report is
   *   a small document plus images the browser fetches. The two hosts that already serve that
   *   layout pass their own prefix: the daemon `/static/`, CI `""` (document-relative against the
   *   report's own artifact URL). Null — the default — embeds, which is what makes the report a
   *   portable single file; a report generated with a base URL only renders where that base
   *   resolves. Screenshots that are already absolute URLs (device-farm legs) are referenced
   *   either way and are unaffected by this.
   * @param skips trails the runner declined to run, which the caller has scoped to the same work
   *   this report covers. The logs directory outlives any one run and accumulates skip records
   *   from every run before it, so this is a parameter rather than a read of that directory:
   *   a report over ten sessions must not list a trail some earlier invocation held back. Only a
   *   caller reporting on a WHOLE logs directory (CI's `ReportMain`, `trailblaze report` with no
   *   `--id`) passes `SkippedTrails.read(...)`.
   */
  @JvmOverloads
  fun generateFromSnapshots(
    logsRepo: LogsRepo,
    snapshots: List<SessionLogSnapshot>,
    shareUrl: String? = null,
    fullEventPayloads: Boolean = false,
    imageBaseUrl: String? = null,
    skips: List<SkippedTrail> = emptyList(),
  ): File? {
    if (snapshots.isEmpty()) return null
    if (bunBinary == null) {
      logBunUnavailable()
      return null
    }

    val sessionsJson = ReportTiming.stage("RunReportGenerator.buildSessionJson") {
      buildJsonArray {
        for (snapshot in snapshots) {
          val sessionObj = buildSessionJson(logsRepo, snapshot) ?: continue
          add(sessionObj)
        }
        // Trails the runner held back. They opened no session, so they have no snapshot to build
        // from; without these entries the report shows a run's passing trails and silently omits
        // the ones a `config.skip:` disabled, which reads as "that trail doesn't exist here".
        for (skip in skips) {
          add(skipSessionJson(skip))
        }
      }
    }
    if (sessionsJson.isEmpty()) {
      Console.log("[RunReportGenerator] no resolvable sessions among ${snapshots.size} requested.")
      return null
    }

    val reportsDir = File(logsRepo.logsDir, "reports").apply { mkdirs() }
    // The timestamp keeps repeated generate() calls from clobbering each other in reports/; the
    // "interactive" token distinguishes this from the legacy WASM report, which ReportMain writes
    // as trailblaze_report.html in the logs-dir root (ReportMain copies the latest of these to the
    // canonical trailblaze_report_interactive.html).
    val dest = File(reportsDir, "trailblaze_report_interactive_${LocalDateTime.now().format(FILE_TS)}.html")
    return render(sessionsJson, dest, shareUrl, fullEventPayloads, imageBaseUrl)
  }

  /**
   * Render an already-built driver `sessions` array to [destination] — the shared back half of
   * every report this class produces: stage the renderer + driver + formatters into a temp dir,
   * write input.json, run bun, copy the result out.
   *
   * Split from [generateFromSnapshots] because not every report is built from session LOGS.
   * [RunIndexGenerator] builds its sessions from CI result rows, which carry no logs at all — the
   * runs they describe live in other reports entirely.
   *
   * @param sessionsJson one entry per session, in the driver's input shape (see the `DriverInput`
   *   interface in run-report-cli.ts): `meta`, `sessionDir`, `logs`, and the optional YAML fields.
   * @return [destination], or null when bun is unavailable or the subprocess failed.
   */
  @JvmOverloads
  fun render(
    sessionsJson: JsonArray,
    destination: File,
    shareUrl: String? = null,
    fullEventPayloads: Boolean = false,
    imageBaseUrl: String? = null,
  ): File? {
    val bun = bunBinary
    if (bun == null) {
      logBunUnavailable()
      return null
    }

    val generateStart = TimeSource.Monotonic.markNow()
    val generatedAt = LocalDateTime.now().format(HUMAN_TS)

    val workDir = Files.createTempDirectory("trailblaze-run-report-").toFile()
    try {
      copyResource(CORE_RESOURCE, File(workDir, "run-report-core.js"))
      copyResource(DRIVER_RESOURCE, File(workDir, "run-report-cli.ts"))
      copyResource(EVENTS_RESOURCE, File(workDir, "run-report-events.ts"))
      copyResource(SPRITES_RESOURCE, File(workDir, "run-report-sprites.ts"))
      // The Kotlin/JS selector engine for the UI Inspector's suggestions. OPTIONAL by design: the
      // resource is only in the JAR when :trailblaze-selector-engine-js's bundle task ran at build
      // time, and an older/bundle-less JAR must keep producing reports — the driver embeds it (once
      // per report, gz+base64) only when staged AND a session carries hierarchies, and the viewer
      // degrades to no suggestions section when it's absent.
      val selectorEngineStaged = copyOptionalResource(SELECTOR_ENGINE_RESOURCE, File(workDir, SELECTOR_ENGINE_FILE_NAME))
      val formatterNames = stageEventFormatters(workDir)
      val inputJson = buildJsonObject {
        put("generatedAt", generatedAt)
        shareUrl?.takeIf { it.isNotBlank() }?.let { put("shareUrl", it) }
        if (formatterNames.isNotEmpty()) {
          put("formatters", buildJsonArray { formatterNames.forEach { add(it) } })
        }
        if (fullEventPayloads) put("fullEventPayloads", true)
        // Emitted even when EMPTY (CI's document-relative case) — the driver switches on
        // present-vs-absent, so "" is a meaningful base here, not "unset". Deliberately unlike
        // `shareUrl` two lines up, where blank does mean unset.
        if (imageBaseUrl != null) put("imageBaseUrl", imageBaseUrl)
        if (selectorEngineStaged) put("selectorEngine", SELECTOR_ENGINE_FILE_NAME)
        put("sessions", sessionsJson)
      }
      val inputFile = ReportTiming.stage("RunReportGenerator.serializeAndWriteInputJson") {
        File(workDir, "input.json").apply { writeText(inputJson.toString()) }
      }
      val outputFile = File(workDir, "report.html")

      val exit = ReportTiming.stage("RunReportGenerator.bunSubprocess") {
        runBun(bun, workDir, inputFile, outputFile)
      }
      if (exit != 0 || !outputFile.exists() || outputFile.length() == 0L) {
        Console.error("[RunReportGenerator] report subprocess failed (exit=$exit).")
        return null
      }

      destination.parentFile?.mkdirs()
      ReportTiming.stage("RunReportGenerator.outputCopy") {
        outputFile.copyTo(destination, overwrite = true)
      }
      // Name the image mode on every generation. A report whose screenshots don't load is the
      // failure this switch can cause, and "was it even linking?" is the first question — without
      // this line there is nothing to grep, since the driver's own stdout is only surfaced when
      // the subprocess fails.
      Console.log(
        "[RunReportGenerator] images: " +
          if (imageBaseUrl == null) "embedded" else "linked at '$imageBaseUrl<sessionId>/<file>'",
      )
      Console.log("[RunReportGenerator] report generated at ${destination.absolutePath}")
      return destination
    } finally {
      workDir.deleteRecursively()
      ReportTiming.log("RunReportGenerator.generate", generateStart)
    }
  }

  private fun logBunUnavailable() {
    // bun is a hard prerequisite of Trailblaze, not an optional accelerator — so a missing bun
    // is an actionable error about a broken install, NOT a reason to quietly serve the
    // deprecated legacy report in place of the standard one.
    Console.error(
      "[RunReportGenerator] bun not found on PATH — cannot build the Trailblaze report. " +
        "bun is a required dependency; install it (`source bin/activate-hermit` in this repo, " +
        "or https://bun.sh/install) and re-run.",
    )
  }

  /** Build one session's payload object: meta + recorded YAML + screenshot dir + raw log array. */
  private fun buildSessionJson(logsRepo: LogsRepo, snapshot: SessionLogSnapshot): JsonObject? {
    val logs = snapshot.logs
    // Same gate as the legacy WASM report: a session dir with stray logs but no session-status
    // log isn't a real run (e.g. a one-shot helper session) — without this it would surface as a
    // GUID-titled "UNKNOWN" entry in the session index.
    if (logs.none { it is TrailblazeLog.TrailblazeSessionStatusChangeLog }) return null
    val sessionInfo = logs.getSessionInfo() ?: return null
    val status = logs.getSessionStatus()
    val sessionDir = logsRepo.getSessionDir(snapshot.sessionId)

    // Render the recording in the unified `trail.yaml` shape (`config:`/`trailhead:`/`trail:` with
    // per-classifier `recordings:`) — the format the save path writes to disk — so the report
    // preview matches the saved artifact rather than the legacy v1 list. Falls back to v1 for a
    // session with no resolvable device classifier.
    val recordingYaml = runCatching {
      logs.generateUnifiedRecordedYaml(createTrailblazeYaml())
    }.getOrNull()?.takeIf { it.isNotBlank() }
    // Use only the immutable source captured at session start. Reading trailFilePath here would
    // both expose an arbitrary local file to the report and show edited content for an older run.
    val originalYaml = logs.getSessionStartedInfo()?.rawYaml?.takeIf { it.isNotBlank() }

    return buildJsonObject {
      put("meta", sessionMetaJson(sessionInfo, status, SessionRecordingInfo.fromLogs(logs), reportProvenanceJson(environment)))
      if (recordingYaml != null) put("recordingYaml", recordingYaml)
      if (originalYaml != null) put("originalYaml", originalYaml)
      put("sessionDir", sessionDir.absolutePath)
      // The raw per-log records for the bun renderer, straight from the snapshot — byte-identical
      // to what the daemon serves the web app at `/trailrunner/api/session/{id}/logs` (the same
      // files `TrailblazeJsonInstance` wrote, with discriminator `class`), except that redundant
      // view-hierarchy fields were deduped to the one the renderer reads at snapshot capture
      // (see [slimViewHierarchyFields]) — it feeds the report's UI Inspector.
      put("logs", snapshot.rawLogsJson)
    }
  }

  private fun copyResource(resourcePath: String, dest: File) {
    val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
      ?: error("Missing report resource on classpath: $resourcePath")
    stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
  }

  /** [copyResource] for a resource the report degrades gracefully without; true when staged. */
  private fun copyOptionalResource(resourcePath: String, dest: File): Boolean {
    val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return false
    stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    return true
  }

  /**
   * Stage every classpath-provided event-formatter file beside the driver (preserving relative
   * paths, so formatter modules can import shared support files like `lib/…`) and return the
   * formatter module names (listed in input.json; the driver `require`s each one — see
   * run-report-events.ts). Formatters are an optional rendering upgrade, so any discovery/copy
   * failure only logs.
   */
  private fun stageEventFormatters(workDir: File): List<String> = try {
    discoverEventFormatterResources(javaClass.classLoader).mapNotNull { (path, url) ->
      val dest = File(workDir, path)
      dest.parentFile.mkdirs()
      url.openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
      path.takeIf { isFormatterModule(it) }
    }
  } catch (e: Exception) {
    Console.log("[RunReportGenerator] skipping event formatters: $e")
    emptyList()
  }

  /** Run `bun run-report-cli.ts <input> <output>`, draining output, bounded by a timeout. */
  private fun runBun(bun: File, workDir: File, input: File, output: File): Int {
    val proc = ProcessBuilder(
      bun.absolutePath,
      "run-report-cli.ts",
      input.absolutePath,
      output.absolutePath,
    ).directory(workDir).redirectErrorStream(true).start()

    // Drain stdout/stderr on a daemon thread so the subprocess can't deadlock on a full pipe.
    val sink = StringBuilder()
    val drain = Thread {
      proc.inputStream.bufferedReader().forEachLine { line -> synchronized(sink) { sink.appendLine(line) } }
    }.apply { isDaemon = true; start() }

    val finished = proc.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      Console.error("[RunReportGenerator] report subprocess timed out after ${SUBPROCESS_TIMEOUT_SECONDS}s.")
      return -1
    }
    drain.join(1_000)
    val out = synchronized(sink) { sink.toString() }.trim()
    if (proc.exitValue() != 0 && out.isNotEmpty()) Console.error("[RunReportGenerator] $out")
    return proc.exitValue()
  }

  companion object {
    // Both resources are packaged into THIS (:trailblaze-report) module's JAR — see
    // transpileRunReportCore in trailblaze-report/build.gradle.kts — despite CORE_RESOURCE's
    // "trailrunner" path segment. That segment is a historical artifact of where this class
    // used to live (:trailblaze-host); it's kept as-is so :trailblaze-host's Trail Runner web
    // app can keep serving run-report-core.js at the same URL/classpath path it always has.
    private const val CORE_RESOURCE = "xyz/block/trailblaze/trailrunner/web/app/run-report-core.js"
    private const val DRIVER_RESOURCE = "xyz/block/trailblaze/report/run-report-cli.ts"
    private const val EVENTS_RESOURCE = "xyz/block/trailblaze/report/run-report-events.ts"
    private const val SPRITES_RESOURCE = "xyz/block/trailblaze/report/run-report-sprites.ts"

    /**
     * The Kotlin/JS selector engine (built by `:trailblaze-selector-engine-js:bundleSelectorEngine`,
     * staged into this JAR by `copySelectorEngineResource` — see build.gradle.kts). Optional on the
     * classpath: the bundle task skips cleanly when `bun` is unavailable, and reports generated
     * without it simply carry no selector suggestions in the UI Inspector.
     */
    private const val SELECTOR_ENGINE_RESOURCE = "xyz/block/trailblaze/report/trailblaze-selector-engine.min.js"
    private const val SELECTOR_ENGINE_FILE_NAME = "trailblaze-selector-engine.min.js"

    /**
     * Classpath directory scanned for event-formatter modules. Any module on the runtime classpath
     * (this JAR, a downstream distribution's JARs, a plain resources dir) can contribute per-stream
     * formatters for the report's Events rendering by dropping `<name>.formatter.ts|js` files here —
     * the OSS generator stays producer-agnostic while distributions add their own formatters without
     * code changes (see EventStreamFormatter in run-report-types.d.ts for the module contract).
     * Files in subdirectories (e.g. `lib/…`) are staged alongside the modules so a formatter can
     * `import` shared support code; only top-level `*.formatter.ts|js` files are loaded as modules.
     */
    const val EVENT_FORMATTERS_RESOURCE_DIR: String = "xyz/block/trailblaze/report/event-formatters"

    /**
     * Staged files become paths under the driver's working directory, so every relative-path
     * segment must be a plain, path-safe name (no leading dot, so no `.`/`..` traversal); anything
     * else is ignored rather than staged.
     */
    private val SAFE_PATH_SEGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
    private val FORMATTER_FILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.formatter\.(ts|js)""")

    private fun isSafeRelativePath(path: String): Boolean =
      path.split('/').all { SAFE_PATH_SEGMENT.matches(it) }

    /** A top-level `*.formatter.ts|js` — the `require()` entry points among the staged files. */
    private fun isFormatterModule(relativePath: String): Boolean =
      '/' !in relativePath && FORMATTER_FILE_NAME.matches(relativePath)

    /**
     * Find every path-safe file under [EVENT_FORMATTERS_RESOURCE_DIR] across the classpath
     * (formatter modules at the top level plus their support files in subdirectories), keyed by
     * relative path, deduplicated (first classpath occurrence wins), sorted for a deterministic
     * driver load order. Handles both resource-URL shapes: a plain directory (dev/test classpath)
     * and a JAR entry (packaged distribution).
     */
    internal fun discoverEventFormatterResources(classLoader: ClassLoader): Map<String, java.net.URL> {
      val found = sortedMapOf<String, java.net.URL>()
      for (dirUrl in classLoader.getResources(EVENT_FORMATTERS_RESOURCE_DIR)) {
        when (dirUrl.protocol) {
          "file" -> {
            val base = File(dirUrl.toURI())
            base.walkTopDown()
              .filter { it.isFile }
              .forEach { file ->
                val path = file.relativeTo(base).invariantSeparatorsPath
                if (isSafeRelativePath(path)) found.putIfAbsent(path, file.toURI().toURL())
              }
          }
          "jar" -> {
            val connection = dirUrl.openConnection() as java.net.JarURLConnection
            connection.useCaches = false
            connection.jarFile.use { jar ->
              jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("$EVENT_FORMATTERS_RESOURCE_DIR/") }
                .forEach { entry ->
                  val path = entry.name.removePrefix("$EVENT_FORMATTERS_RESOURCE_DIR/")
                  if (isSafeRelativePath(path)) {
                    found.putIfAbsent(path, java.net.URL("jar:${connection.jarFileURL}!/${entry.name}"))
                  }
                }
            }
          }
        }
      }
      return found
    }
    private const val SUBPROCESS_TIMEOUT_SECONDS = 120L
    private val HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    // View-hierarchy fields a log record may carry, in the priority order the interactive report's
    // extractor reads them (run-report-extract.ts: viewHierarchyFiltered || trailblazeNodeTree ||
    // viewHierarchy). Only the first present field is rendered (a web trailblazeNodeTree also
    // consumes its viewHierarchy sibling's bounds — see slimViewHierarchyFields) — the rest are
    // redundant weight in input.json and are dropped at the seam.
    private val VIEW_HIERARCHY_LOG_FIELDS = listOf("viewHierarchyFiltered", "trailblazeNodeTree", "viewHierarchy")

    /**
     * Keep exactly the view-hierarchy field(s) the interactive renderer reads (the first present
     * in [VIEW_HIERARCHY_LOG_FIELDS]) and drop the redundant siblings — a record commonly carries
     * both `trailblazeNodeTree` and the legacy `viewHierarchy`, and shipping both across the bun
     * process boundary only inflates input.json for no output. The kept field is what the report
     * embeds for the UI Inspector (per-step tree + bounds overlay). Every other field stays
     * byte-identical to the on-disk record, so the payload keeps parity with what the daemon
     * serves the web app. A JSON-null hierarchy field counts as absent, mirroring the extractor's
     * `||` fallthrough — a record with `viewHierarchyFiltered: null` keeps its populated
     * `trailblazeNodeTree` (and the dead null key is dropped alongside the redundant siblings).
     * A non-object element, or one with no other hierarchy field to drop, is returned unchanged
     * (no reallocation).
     *
     * One deliberate exception to "exactly one": a WEB `trailblazeNodeTree` also keeps its legacy
     * `viewHierarchy` sibling. The two are parallel views of one ARIA snapshot with bounds from
     * different DOM correlations — the ARIA tree's fuzzy role+name walk leaves most nodes with no
     * geometry, while the legacy tree's ref-resolved pass covers 3–10x more nodes — and the
     * extractor grafts the dense legacy bounds onto the ARIA tree for the inspector's hit-testing
     * (`mergeWebHierarchyBounds` in run-report-extract.ts). Only the merged tree is embedded in
     * report.html, so the extra weight is confined to input.json and only on web records.
     */
    internal fun slimViewHierarchyFields(element: JsonElement): JsonElement {
      val obj = element as? JsonObject ?: return element
      val usable = VIEW_HIERARCHY_LOG_FIELDS.filter { obj[it].let { v -> v != null && v != JsonNull } }
      if (usable.isEmpty()) return element
      val kept = buildSet {
        add(usable.first())
        if (usable.first() == "trailblazeNodeTree" && "viewHierarchy" in usable && isWebNodeTree(obj["trailblazeNodeTree"])) {
          add("viewHierarchy")
        }
      }
      val redundant = VIEW_HIERARCHY_LOG_FIELDS.filter { it !in kept && it in obj.keys }
      if (redundant.isEmpty()) return element
      return JsonObject(obj.filterKeys { it !in redundant })
    }

    /** Whether a serialized TrailblazeNode tree carries the web driver detail at its root. */
    private fun isWebNodeTree(tree: JsonElement?): Boolean {
      val detail = (tree as? JsonObject)?.get("driverDetail") as? JsonObject ?: return false
      return (detail[TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR] as? JsonPrimitive)?.contentOrNull == "web"
    }

    /**
     * Drop ALL view-hierarchy fields from a raw log record — the profiler variant of
     * [slimViewHierarchyFields]. [PerformanceAnalysisGenerator] reads only timestamps, durations,
     * and tool/LLM metadata, so even the one field the run report keeps for its UI Inspector is
     * dead payload crossing that bun boundary. A record with no hierarchy field is returned
     * unchanged (no reallocation).
     */
    internal fun dropViewHierarchyFields(element: JsonElement): JsonElement {
      val obj = element as? JsonObject ?: return element
      if (VIEW_HIERARCHY_LOG_FIELDS.none { it in obj.keys }) return element
      return JsonObject(obj.filterKeys { it !in VIEW_HIERARCHY_LOG_FIELDS })
    }

    /**
     * The run `meta` the viewer renders (title, status badge, device/platform strip, error banner,
     * rerun command). Pure over [SessionInfo]/[SessionStatus] so it's unit-testable without a device
     * or a logs dir. `steps` is intentionally omitted — the renderer derives it from the trace length.
     */
    /**
     * A held-back trail as a driver-input session: metadata only, no logs.
     *
     * Same link-out stub shape [RunIndexGenerator] uses for a run whose evidence lives elsewhere -
     * `linkOut` with no `reportUrl` is what tells the viewer this row has nothing to open, so it
     * renders inert instead of offering a detail view that is empty by construction. Here the reason
     * is stronger than "elsewhere": there is no run at all.
     */
    internal fun skipSessionJson(skip: SkippedTrail): JsonObject = buildJsonObject {
      put(
        "meta",
        buildJsonObject {
          put("title", skip.title)
          put("status", "skipped")
          put("linkOut", true)
          // The viewer keys a matrix row on `trailId` + `target` and gives a session carrying no
          // trailId a row of its own. Both are emitted under exactly the rules [sessionMetaJson]
          // applies to a session that ran - the declared `config.id`, never a short-name fallback -
          // so a skipped device's cell lands on the same row as the devices that ran the trail
          // instead of in a parallel row beside it.
          skip.trail_id?.takeIf { it.isNotBlank() }?.let { put("trailId", it) }
          skip.target?.takeIf { it.isNotBlank() }?.let { put("target", it) }
          skip.platform?.takeIf { it.isNotBlank() }?.let { put("platform", it) }
          skip.device_classifier?.takeIf { it.isNotBlank() }?.let { put("deviceClassifier", it) }
          put("skipReason", skip.reason)
          // Same `config.metadata` passthrough [sessionMetaJson] gives a session that ran: the
          // report renders these as Info rows and folds them into the index search text, and
          // `owner` gets first-class index treatment. A skipped row omitting them would drop out
          // of a search for its own owner while the same trail's runs still matched.
          skip.metadata?.takeIf { it.isNotEmpty() }?.let { metadata ->
            put("metadata", buildJsonObject { metadata.forEach { (key, value) -> put(key, value) } })
          }
          put(
            "ranAt",
            LocalDateTime.ofInstant(
              java.time.Instant.ofEpochMilli(skip.recorded_at_epoch_ms),
              ZoneId.systemDefault(),
            ).format(HUMAN_TS),
          )
          put("cmd", "./trailblaze run ${skip.trail_path}")
        },
      )
      put("sessionDir", RunIndexGenerator.stubSessionDir(SkippedTrails.syntheticSessionId(skip).value))
      put("logs", JsonArray(emptyList()))
    }

    internal fun sessionMetaJson(
      sessionInfo: xyz.block.trailblaze.logs.model.SessionInfo,
      status: SessionStatus,
      recordingInfo: SessionRecordingInfo,
      provenance: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = buildJsonObject {
      put("title", sessionInfo.displayName)
      put("status", statusLabel(status))
      sessionInfo.trailConfig?.target?.let { put("target", it) }
      sessionInfo.targetAppInfo?.let { app ->
        put("appId", app.appId)
        // "5.58.0.0 (67500009)" — user-visible version first, internal build/version code in
        // parens. Same display rule as the Trail Runner Info tab and share-export.tsx.
        val build = app.buildNumber ?: app.versionCode
        val display = when {
          app.versionName != null && build != null -> "${app.versionName} ($build)"
          app.versionName != null -> app.versionName
          else -> build
        }
        display?.let { put("appVersion", it) }
      }
      sessionInfo.trailblazeDeviceInfo?.platform?.name?.lowercase()?.let { put("platform", it) }
      sessionInfo.trailblazeDeviceId?.instanceId?.let { put("device", it) }
      sessionInfo.trailblazeDeviceInfo?.let { device ->
        // The device's SPECIFIC compound classifier — `android-phone`, `ios-ipad`, `android-kiosk` —
        // rather than the broad platform family it falls back to. The head of
        // [TrailblazeClassifierLineage.resolutionChain] IS that identity, and it's the same string
        // the results model stores as `SessionResult.device_classifier`, the same key the trail
        // files its `recordings:` under, and the same key a CI config names its device by. The
        // report keys its matrix columns on it, so a column heading is a string the reader already
        // greps for elsewhere.
        TrailblazeClassifierLineage.resolutionChain(device.classifiers)
          .firstOrNull()
          ?.classifier
          ?.takeIf { it.isNotBlank() }
          ?.let { put("deviceClassifier", it) }
        device.classifiers
          .map { it.classifier }
          .filterNot { it.equals(device.platform.name, ignoreCase = true) }
          .takeIf { it.isNotEmpty() }
          ?.joinToString(" · ")
          ?.let { put("deviceType", it) }
      }
      put("duration", formatDuration(sessionInfo.durationMs))
      put("ranAt", LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(sessionInfo.timestamp.toEpochMilliseconds()),
        ZoneId.systemDefault(),
      ).format(HUMAN_TS))
      sessionInfo.trailConfig?.id?.let { put("trailId", it) }
      // Consumer-injected key/values from the trail's `config.metadata` — the report's generic
      // injection point (Info-tab rows, index search; `owner` gets first-class index treatment).
      sessionInfo.trailConfig?.metadata?.takeIf { it.isNotEmpty() }?.let { metadata ->
        put("metadata", buildJsonObject { metadata.forEach { (key, value) -> put(key, value) } })
      }
      sessionInfo.trailFilePath?.takeIf { it.isNotBlank() }?.let { put("cmd", "./trailblaze run $it") }
      failureReason(status)?.let { put("error", it) }
      failureCodeOf(failurePayloadOf(status))?.let { put("failureCode", it) }
      // Self-heal keeps its pass/fail badge (so tallies stay honest) and gains a separate marker
      // badge in the viewer — the legacy report's SelfHealChip distinction. Asking
      // ExecutionMode.selfHealed keeps this key in step with the `SELF_HEAL` execution mode the
      // results JSON reports for the same run.
      if (ExecutionMode.selfHealed(status, recordingInfo)) {
        put("selfHeal", true)
      }
      provenance.forEach { (key, value) -> put(key, value) }
    }

    /** CI/source provenance for shareable report links. Empty for ordinary local runs. */
    internal fun reportProvenanceJson(environment: Map<String, String>): JsonObject = buildJsonObject {
      val buildUrl = environment.firstValue("CI_BUILD_URL", "BUILDKITE_BUILD_URL")
        ?: run {
          val server = environment["GITHUB_SERVER_URL"]
          val repo = environment["GITHUB_REPOSITORY"]
          val run = environment["GITHUB_RUN_ID"]
          if (server != null && repo != null && run != null) "$server/$repo/actions/runs/$run" else null
        }
      val buildNumber = environment.firstValue("CI_BUILD_NUMBER", "BUILDKITE_BUILD_NUMBER", "GITHUB_RUN_NUMBER")
      val commit = environment.firstValue("GIT_COMMIT", "BUILDKITE_COMMIT", "GITHUB_SHA")
      val branch = environment.firstValue("GIT_BRANCH", "BUILDKITE_BRANCH", "GITHUB_REF_NAME")
      val repository = GitRepoUrls.webBaseUrl(environment["BUILDKITE_REPO"])
        ?: environment["GITHUB_REPOSITORY"]?.let { repo ->
          "${environment["GITHUB_SERVER_URL"] ?: "https://github.com"}/$repo"
        }

      buildUrl?.takeIf { it.isNotBlank() }?.let { put("buildUrl", it) }
      buildNumber?.takeIf { it.isNotBlank() }?.let { put("buildNumber", it) }
      commit?.takeIf { it.isNotBlank() }?.let { sha ->
        put("commitSha", sha)
        repository?.let { put("commitUrl", "$it/commit/$sha") }
      }
      branch?.takeIf { it.isNotBlank() }?.let { put("branch", it) }
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? =
      keys.firstNotNullOfOrNull { key -> get(key)?.takeIf { it.isNotBlank() } }

    /** Map a [SessionStatus] to the badge class the viewer expects (passed/failed/cancelled/running/unknown). */
    internal fun statusLabel(status: SessionStatus): String = when (status) {
      is SessionStatus.Ended.Succeeded,
      is SessionStatus.Ended.SucceededWithSelfHeal -> "passed"
      is SessionStatus.Ended.Failed,
      is SessionStatus.Ended.FailedWithSelfHeal,
      is SessionStatus.Ended.TimeoutReached,
      is SessionStatus.Ended.MaxCallsLimitReached -> "failed"
      is SessionStatus.Ended.Cancelled -> "cancelled"
      is SessionStatus.Started -> "running"
      is SessionStatus.Unknown -> "unknown"
    }

    internal fun failureReason(status: SessionStatus): String? = when (status) {
      is SessionStatus.Ended.Failed -> status.exceptionMessage
      is SessionStatus.Ended.FailedWithSelfHeal -> status.exceptionMessage
      is SessionStatus.Ended.Cancelled -> status.cancellationMessage
      is SessionStatus.Ended.TimeoutReached -> status.message
      is SessionStatus.Ended.MaxCallsLimitReached ->
        "Max LLM calls limit reached (${status.maxCalls}) for: ${status.objectivePrompt}"
      else -> null
    }

    internal fun formatDuration(ms: Long): String = when {
      ms < 1000 -> "${ms}ms"
      ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
      else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
  }
}
