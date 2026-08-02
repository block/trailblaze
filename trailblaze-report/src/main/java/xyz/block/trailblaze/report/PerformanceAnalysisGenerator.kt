package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.util.Console

/**
 * Headless generator for the Trailblaze performance-analysis report — an Instruments-style time
 * profiler over a build's trail sessions, emitted as a sibling of the interactive run report.
 * Where the run report serves product developers (what happened, step by step), this one serves
 * TRAIL developers optimizing trails: where the wall-clock time went (a zoomable multi-track
 * timeline), which tools burned their whole timeout budget, where the session sat idle, and how
 * two runs of the same trail compare.
 *
 * Same machinery as [RunReportGenerator]: the extraction + renderer is the build-time bundle of
 * the perf-*.ts modules ([perf-core.js][CORE_RESOURCE]) run under a thin bun driver
 * ([perf-report-cli.ts][DRIVER_RESOURCE]). Requires `bun` on PATH; when bun can't be resolved or
 * the subprocess fails, [generate] returns null so callers keep their other report artifacts.
 */
class PerformanceAnalysisGenerator(
  private val bunBinary: File? = BunBinaryResolver.resolveBunBinary(),
  private val environment: Map<String, String> = System.getenv(),
) {

  /** Whether the report can be generated at all (bun resolved). */
  val isBunAvailable: Boolean get() = bunBinary != null

  /**
   * Generate the performance-analysis HTML report for [sessionIds] into `<logsDir>/reports/`.
   * Takes the bare logs directory (not a `LogsRepo` — a single-read repo would parse every
   * session at construction, doubling the disk work) and reads each session's log files exactly
   * once via [SessionLogSnapshot.captureAll]. Callers that already hold snapshots use
   * [generateFromSnapshots] directly.
   */
  fun generate(logsDir: File, sessionIds: List<SessionId>): File? {
    if (sessionIds.isEmpty()) return null
    if (bunBinary == null) {
      logBunUnavailable()
      return null
    }
    return generateFromSnapshots(logsDir, SessionLogSnapshot.captureAll(logsDir, sessionIds))
  }

  /** Generate the report from already-captured session [snapshots] — no log file re-read. */
  fun generateFromSnapshots(logsDir: File, snapshots: List<SessionLogSnapshot>): File? {
    if (snapshots.isEmpty()) return null
    val bun = bunBinary
    if (bun == null) {
      logBunUnavailable()
      return null
    }

    val generateStart = TimeSource.Monotonic.markNow()
    val sessionsJson = buildJsonArray {
      for (snapshot in snapshots) {
        val logs = snapshot.logs
        // Same gate as the run report: a session dir with stray logs but no session-status log
        // isn't a real run.
        if (logs.none { it is TrailblazeLog.TrailblazeSessionStatusChangeLog }) continue
        val sessionInfo = logs.getSessionInfo() ?: continue
        add(
          buildJsonObject {
            put(
              "meta",
              RunReportGenerator.sessionMetaJson(
                sessionInfo,
                logs.getSessionStatus(),
                RunReportGenerator.reportProvenanceJson(environment),
              ),
            )
            // The raw per-log records for the bun extractor, heavy view-hierarchy fields already
            // stripped at snapshot capture (the profiler only reads timestamps, durations, and
            // tool/LLM metadata).
            put("logs", snapshot.rawLogsJson)
          },
        )
      }
    }
    if (sessionsJson.isEmpty()) {
      Console.log("[PerformanceAnalysisGenerator] no resolvable sessions among ${snapshots.size} requested.")
      return null
    }

    val workDir = Files.createTempDirectory("trailblaze-perf-report-").toFile()
    try {
      copyResource(CORE_RESOURCE, File(workDir, "perf-core.js"))
      copyResource(DRIVER_RESOURCE, File(workDir, "perf-report-cli.ts"))
      val inputJson = buildJsonObject {
        put("generatedAt", LocalDateTime.now().format(HUMAN_TS))
        put("sessions", sessionsJson)
      }
      val inputFile = File(workDir, "input.json").apply { writeText(inputJson.toString()) }
      val outputFile = File(workDir, "report.html")

      val exit = runBun(bun, workDir, inputFile, outputFile)
      if (exit != 0 || !outputFile.exists() || outputFile.length() == 0L) {
        Console.error("[PerformanceAnalysisGenerator] report subprocess failed (exit=$exit).")
        return null
      }

      val reportsDir = File(logsDir, "reports").apply { mkdirs() }
      val dest = File(reportsDir, "trailblaze_performance_analysis_${LocalDateTime.now().format(FILE_TS)}.html")
      outputFile.copyTo(dest, overwrite = true)
      Console.log("[PerformanceAnalysisGenerator] report generated at ${dest.absolutePath}")
      return dest
    } finally {
      workDir.deleteRecursively()
      ReportTiming.log("PerformanceAnalysisGenerator.generate", generateStart)
    }
  }

  private fun logBunUnavailable() {
    Console.log(
      "[PerformanceAnalysisGenerator] bun not found on PATH — cannot build the performance-analysis " +
        "report. Install bun (it ships with the repo toolchain via `source bin/activate-hermit`).",
    )
  }

  private fun copyResource(resourcePath: String, dest: File) {
    val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
      ?: error("Missing report resource on classpath: $resourcePath")
    stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
  }

  /** Run `bun perf-report-cli.ts <input> <output>`, draining output, bounded by a timeout. */
  private fun runBun(bun: File, workDir: File, input: File, output: File): Int {
    val proc = ProcessBuilder(
      bun.absolutePath,
      "perf-report-cli.ts",
      input.absolutePath,
      output.absolutePath,
    ).directory(workDir).redirectErrorStream(true).start()

    val sink = StringBuilder()
    val drain = Thread {
      proc.inputStream.bufferedReader().forEachLine { line -> synchronized(sink) { sink.appendLine(line) } }
    }.apply { isDaemon = true; start() }

    val finished = proc.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      Console.error("[PerformanceAnalysisGenerator] report subprocess timed out after ${SUBPROCESS_TIMEOUT_SECONDS}s.")
      return -1
    }
    drain.join(1_000)
    val out = synchronized(sink) { sink.toString() }.trim()
    if (proc.exitValue() != 0 && out.isNotEmpty()) Console.error("[PerformanceAnalysisGenerator] $out")
    return proc.exitValue()
  }

  companion object {
    // Packaged into this module's JAR by bundlePerfReportCore / processResources — the same
    // "trailrunner" path-segment convention as RunReportGenerator's resources (see its note).
    private const val CORE_RESOURCE = "xyz/block/trailblaze/trailrunner/web/app/perf-core.js"
    private const val DRIVER_RESOURCE = "xyz/block/trailblaze/report/perf-report-cli.ts"
    private const val SUBPROCESS_TIMEOUT_SECONDS = 120L
    private val HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
  }
}
