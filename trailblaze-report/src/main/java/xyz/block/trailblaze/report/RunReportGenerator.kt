package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.logs.model.getSessionStartedInfo
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateUnifiedRecordedYaml

/**
 * Headless generator for the interactive Trailblaze run report — the CLI/CI counterpart to the
 * in-app "Share as HTML" button. It produces the SAME self-contained, dependency-free HTML the
 * Share button does, by reusing the exact same extraction + renderer
 * ([run-report-core.js][CORE_RESOURCE], the build-time transpiled artifact of run-report-core.ts) under
 * a thin bun driver ([run-report-cli.ts][DRIVER_RESOURCE]).
 *
 * `trailblaze report` (and the after-run report) generate this artifact ALONGSIDE the legacy WASM
 * report ([WasmReport]) — every run emits both. When this generator can't run (`bun` unavailable,
 * subprocess failure) callers still have the legacy artifact.
 *
 * One report can cover one OR many sessions: a single session opens straight on its detail; several
 * open on a pass/fail session index that drills into each run (parity with the old multi-session
 * WASM index). Per-session it carries the step timeline, the LLM transcript, the recorded
 * `.trail.yaml`, and the run metadata.
 *
 * Requires `bun` on PATH (the same hard prerequisite the scripted-tool analyzer already imposes).
 * When bun can't be resolved, or the subprocess fails, [generate] returns null so the caller can
 * fall back to the legacy report rather than leaving the user with no artifact.
 */
class RunReportGenerator(
  private val bunBinary: File? = BunBinaryResolver.resolveBunBinary(),
  private val environment: Map<String, String> = System.getenv(),
) {

  /**
   * Generate the interactive HTML report for [sessionIds] into `logsRepo.logsDir/reports/`.
   *
   * @return the report [File], or null if bun is unavailable, no session resolved, or the
   *   subprocess failed (each logged via [Console]).
   */
  fun generate(logsRepo: LogsRepo, sessionIds: List<SessionId>): File? {
    if (sessionIds.isEmpty()) return null
    val bun = bunBinary
    if (bun == null) {
      Console.log(
        "[RunReportGenerator] bun not found on PATH — cannot build the interactive report. " +
          "Install bun (it ships with the repo toolchain via `source bin/activate-hermit`) or " +
          "run `trailblaze report --legacy` for the WASM report.",
      )
      return null
    }

    val sessionsJson = buildJsonArray {
      for (sessionId in sessionIds) {
        val sessionObj = buildSessionJson(logsRepo, sessionId) ?: continue
        add(sessionObj)
      }
    }
    if (sessionsJson.isEmpty()) {
      Console.log("[RunReportGenerator] no resolvable sessions among ${sessionIds.size} requested.")
      return null
    }

    val generatedAt = LocalDateTime.now().format(HUMAN_TS)

    val workDir = Files.createTempDirectory("trailblaze-run-report-").toFile()
    try {
      copyResource(CORE_RESOURCE, File(workDir, "run-report-core.js"))
      copyResource(DRIVER_RESOURCE, File(workDir, "run-report-cli.ts"))
      copyResource(EVENTS_RESOURCE, File(workDir, "run-report-events.ts"))
      val formatterNames = stageEventFormatters(workDir)
      val inputJson = buildJsonObject {
        put("generatedAt", generatedAt)
        if (formatterNames.isNotEmpty()) {
          put("formatters", buildJsonArray { formatterNames.forEach { add(it) } })
        }
        put("sessions", sessionsJson)
      }
      val inputFile = File(workDir, "input.json").apply { writeText(inputJson.toString()) }
      val outputFile = File(workDir, "report.html")

      val exit = runBun(bun, workDir, inputFile, outputFile)
      if (exit != 0 || !outputFile.exists() || outputFile.length() == 0L) {
        Console.error("[RunReportGenerator] report subprocess failed (exit=$exit).")
        return null
      }

      val reportsDir = File(logsRepo.logsDir, "reports").apply { mkdirs() }
      // The timestamp keeps repeated generate() calls from clobbering each other in reports/; the
      // "interactive" token distinguishes this from the legacy WASM report, which ReportMain writes
      // as trailblaze_report.html in the logs-dir root (ReportMain copies the latest of these to the
      // canonical trailblaze_report_interactive.html).
      val dest = File(reportsDir, "trailblaze_report_interactive_${LocalDateTime.now().format(FILE_TS)}.html")
      outputFile.copyTo(dest, overwrite = true)
      Console.log("[RunReportGenerator] report generated at ${dest.absolutePath}")
      return dest
    } finally {
      workDir.deleteRecursively()
    }
  }

  /** Build one session's payload object: meta + recorded YAML + screenshot dir + raw log array. */
  private fun buildSessionJson(logsRepo: LogsRepo, sessionId: SessionId): JsonObject? {
    val logs = logsRepo.getCachedLogsForSession(sessionId)
    // Same gate as the legacy WASM report: a session dir with stray logs but no session-status
    // log isn't a real run (e.g. a one-shot helper session) — without this it would surface as a
    // GUID-titled "UNKNOWN" entry in the session index.
    if (logs.none { it is TrailblazeLog.TrailblazeSessionStatusChangeLog }) return null
    val sessionInfo = logs.getSessionInfo() ?: return null
    val status = logs.getSessionStatus()
    val sessionDir = logsRepo.getSessionDir(sessionId)

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
      put("meta", sessionMetaJson(sessionInfo, status, reportProvenanceJson(environment)))
      if (recordingYaml != null) put("recordingYaml", recordingYaml)
      if (originalYaml != null) put("originalYaml", originalYaml)
      put("sessionDir", sessionDir.absolutePath)
      put("logs", readSessionLogJson(sessionDir))
    }
  }

  /**
   * Read a session dir's raw per-log JSON files into a [JsonArray] — byte-identical to what the
   * daemon serves the web app at `/trailrunner/api/session/{id}/logs` (the same files
   * `TrailblazeJsonInstance` wrote, with discriminator `class`). Mirrors that route's filter:
   * hex-prefixed `*.json` files, sorted by name.
   */
  private fun readSessionLogJson(sessionDir: File): JsonArray = buildJsonArray {
    (sessionDir.listFiles() ?: emptyArray())
      .filter { f ->
        f.extension == "json" &&
          f.name.firstOrNull()?.let { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } == true
      }
      .sortedBy { it.name }
      .forEach { f ->
        runCatching { PARSER.parseToJsonElement(f.readText()) }.getOrNull()?.let { add(stripHeavyLogFields(it)) }
      }
  }

  private fun copyResource(resourcePath: String, dest: File) {
    val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
      ?: error("Missing report resource on classpath: $resourcePath")
    stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
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
    private val PARSER = Json { ignoreUnknownKeys = true; isLenient = true }

    // View-hierarchy fields the interactive renderer reads but slims away before embedding. Stripped
    // at the seam so they never bloat input.json or the bun process-boundary copy. Keep in sync with
    // the field names run-report-core.ts reads: viewHierarchyFiltered || trailblazeNodeTree || viewHierarchy.
    private val HEAVY_LOG_FIELDS = setOf("viewHierarchyFiltered", "trailblazeNodeTree", "viewHierarchy")

    /**
     * Drop the large view-hierarchy fields (hundreds of KB per step) from a raw log record before it
     * crosses into the bun renderer. The interactive report's extractor reads them onto each trace
     * row and then slims them away before embedding — carried across the process boundary they only
     * inflated input.json for no output. Every other field stays byte-identical to the on-disk
     * record, so the payload keeps parity with what the daemon serves the web app. A non-object
     * element, or one carrying none of the heavy fields, is returned unchanged (no reallocation).
     */
    internal fun stripHeavyLogFields(element: JsonElement): JsonElement =
      (element as? JsonObject)?.takeIf { obj -> obj.keys.any { it in HEAVY_LOG_FIELDS } }
        ?.let { obj -> JsonObject(obj.filterKeys { it !in HEAVY_LOG_FIELDS }) }
        ?: element

    /**
     * The run `meta` the viewer renders (title, status badge, device/platform strip, error banner,
     * rerun command). Pure over [SessionInfo]/[SessionStatus] so it's unit-testable without a device
     * or a logs dir. `steps` is intentionally omitted — the renderer derives it from the trace length.
     */
    internal fun sessionMetaJson(
      sessionInfo: xyz.block.trailblaze.logs.model.SessionInfo,
      status: SessionStatus,
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
      sessionInfo.trailFilePath?.takeIf { it.isNotBlank() }?.let { put("cmd", "./trailblaze run $it") }
      failureReason(status)?.let { put("error", it) }
      // Self-heal keeps its pass/fail badge (so tallies stay honest) and gains a separate marker
      // badge in the viewer — the legacy report's SelfHealChip distinction.
      if (status is SessionStatus.Ended.SucceededWithSelfHeal || status is SessionStatus.Ended.FailedWithSelfHeal) {
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
      val repository = githubRepositoryUrl(environment["BUILDKITE_REPO"])
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

    private fun githubRepositoryUrl(raw: String?): String? {
      val value = raw?.trim()?.removeSuffix(".git")?.takeIf { it.isNotEmpty() } ?: return null
      return when {
        value.startsWith("git@github.com:") -> "https://github.com/${value.removePrefix("git@github.com:")}"
        value.startsWith("ssh://git@github.com/") -> "https://github.com/${value.removePrefix("ssh://git@github.com/")}"
        value.startsWith("https://github.com/") -> value
        else -> null
      }
    }

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
