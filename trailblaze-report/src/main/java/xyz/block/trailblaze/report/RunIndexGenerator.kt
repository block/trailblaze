package xyz.block.trailblaze.report

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SessionResult

/**
 * Builds a RUN INDEX: the interactive report's device-classifier matrix over a CI run's results,
 * with each cell linking OUT to that run's own report instead of embedding it.
 *
 * The embedded report can't do this job at CI scale. It inlines every screenshot of every session,
 * so a nightly config's report is hundreds of megabytes — past the artifact size cap, which drops
 * it silently, leaving the build with no viewable report at all. The index carries no session
 * payload: it is built from `test_report.json` ([CiSummaryReport]) alone, so its size tracks the
 * number of tests rather than the number of screenshots, and it always publishes.
 *
 * Each result row becomes a LINK-OUT STUB — `meta` only, no logs — carrying `meta.reportUrl`
 * pointing at a viewer that loads the run's session archive from its `logs_zip_url`. The viewer
 * renders those stubs as links rather than as in-document detail views (grep `isLinkOut` in
 * run-report-viewer.ts). Everything else — the matrix columns, retry grouping, the pass/fail
 * sections, search — comes from the ordinary report renderer, so the index looks and behaves like
 * the report a reader already knows.
 *
 * Takes several reports at once: a build shards one config per device and a nightly runs several
 * configs, so the whole build's matrix only exists once their result files are read together.
 */
class RunIndexGenerator(
  private val reportGenerator: RunReportGenerator = RunReportGenerator(),
) {

  /**
   * Render the index for [reports] to [destination].
   *
   * @param viewerBaseUrl the report viewer each cell links to; a run's archive URL is appended as
   *   `?zip=<encoded>`. Null (or a run with no archive URL) leaves that run unlinked — the cell
   *   still shows its outcome, it just has nowhere to go.
   * @return [destination], or null when the reports carry no results or rendering failed.
   */
  fun generate(reports: List<CiSummaryReport>, viewerBaseUrl: String?, destination: File): File? {
    val sessions = stubSessions(reports, viewerBaseUrl)
    if (sessions.isEmpty()) return null
    return reportGenerator.render(sessions, destination)
  }

  companion object {
    private val HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * One driver-input session per result row, in report order. Pure: the whole mapping is
     * testable without bun, a logs dir, or a network.
     */
    internal fun stubSessions(reports: List<CiSummaryReport>, viewerBaseUrl: String?): JsonArray =
      buildJsonArray {
        for (report in reports) {
          for (result in report.results) {
            add(stubSession(result, report, viewerBaseUrl))
          }
        }
      }

    private fun stubSession(
      result: SessionResult,
      report: CiSummaryReport,
      viewerBaseUrl: String?,
    ): JsonObject = buildJsonObject {
      put("meta", stubMeta(result, report, viewerBaseUrl))
      put("sessionDir", stubSessionDir(result.session_id.value))
      // No logs: this session's evidence is in the report meta.reportUrl points at, not here. The
      // driver derives an empty trace and LLM list from it, which is what makes the index small.
      put("logs", JsonArray(emptyList()))
    }

    internal fun stubMeta(
      result: SessionResult,
      report: CiSummaryReport,
      viewerBaseUrl: String?,
    ): JsonObject = buildJsonObject {
      put("title", result.title)
      put("status", statusLabel(result.outcome))
      // What makes this a STUB, stated independently of whether it ended up with a URL. `reportUrl`
      // can't carry that meaning on its own: a row with no `logs_zip_url`, or any row when no
      // `--viewer-base-url` was given, omits it — and the viewer would then read an evidence-less
      // stub as an ordinary embedded run, count its absent payload as "0 tools, $0.00", and offer
      // to open a detail view that is empty by construction.
      put("linkOut", true)
      // The matrix keys rows on (trailId, target) and columns on (platform, deviceClassifier), so
      // these four are what turn a flat list of rows into the grid.
      result.test_key?.takeIf { it.isNotBlank() }?.let { put("trailId", it) }
      report.metadata.target_app.takeIf { it.isNotBlank() }?.let { put("target", it) }
      put("platform", result.platform)
      result.device_classifier?.takeIf { it.isNotBlank() }?.let { put("deviceClassifier", it) }
      // Omitted for a skip rather than formatted: `duration_ms` is 0 there because nothing ran, and
      // "0ms" reads as a run that finished instantly. The viewer renders an absent duration as
      // unknown, which is the truth. `RunReportGenerator.skipSessionJson` omits it for the same
      // reason, so the two generators agree on what a skipped cell shows.
      if (result.outcome != Outcome.SKIPPED) {
        put("duration", RunReportGenerator.formatDuration(result.duration_ms))
      }
      ranAt(result)?.let { put("ranAt", it) }
      result.app_id?.takeIf { it.isNotBlank() }?.let { put("appId", it) }
      appVersion(result)?.let { put("appVersion", it) }
      // A skipped row carries its reason in the same field a failure uses (see
      // `SkippedTrail.toSessionResult`), but it isn't an error and must not read as one - the
      // viewer styles `error` in the failure vocabulary.
      result.failure_reason?.takeIf { it.isNotBlank() }?.let {
        put(if (result.outcome == Outcome.SKIPPED) "skipReason" else "error", it)
      }
      result.failure_code?.takeIf { it.isNotBlank() }?.let { put("failureCode", it) }
      if (result.self_heal_ran) put("selfHeal", true)
      result.metadata?.takeIf { it.isNotEmpty() }?.let { metadata ->
        put("metadata", buildJsonObject { metadata.forEach { (key, value) -> put(key, value) } })
      }
      // LLM figures the index would otherwise have to derive from a payload it doesn't carry.
      // Omitted rather than zeroed when the row didn't record them: the viewer renders an absent
      // count as unknown, and a confident "0 LLM" on an agent-driven run would be a lie.
      result.llm_call_count?.let { put("llmCallCount", it) }
      result.llm_cost_usd?.let { put("llmCostUsd", it) }
      report.metadata.ci_build_url?.takeIf { it.isNotBlank() }?.let { put("buildUrl", it) }
      report.metadata.ci_build_number?.takeIf { it.isNotBlank() }?.let { put("buildNumber", it) }
      report.metadata.git_commit?.takeIf { it.isNotBlank() }?.let { put("commitSha", it) }
      report.metadata.git_branch?.takeIf { it.isNotBlank() }?.let { put("branch", it) }
      reportUrl(result, viewerBaseUrl)?.let { put("reportUrl", it) }
    }

    /**
     * Where this run's own report lives: the viewer, told which session archive to load.
     *
     * Null when either half is missing, which leaves the cell unlinked rather than pointing at a
     * viewer with nothing to show.
     */
    internal fun reportUrl(result: SessionResult, viewerBaseUrl: String?): String? {
      val viewer = viewerBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      val zipUrl = result.logs_zip_url?.takeIf { it.isNotBlank() } ?: return null
      val separator = if (viewer.contains('?')) "&" else "?"
      return "$viewer${separator}zip=${URLEncoder.encode(zipUrl, StandardCharsets.UTF_8.name())}"
    }

    /**
     * The driver reads `sessionDir` as a filesystem path — it takes the session id from the
     * basename and probes the directory for device logs, events, and video frames. An index stub
     * has no session directory, so this names one that cannot exist and cannot escape the driver's
     * temp working dir: a session id is opaque text from a results file, and a `../` in one would
     * otherwise aim those probes at the host filesystem.
     */
    internal fun stubSessionDir(sessionId: String): String =
      sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        .replace("..", "__")
        .ifBlank { "session" }

    /** Same display rule as [RunReportGenerator.sessionMetaJson]: version name, build in parens. */
    private fun appVersion(result: SessionResult): String? {
      val build = result.app_build_number ?: result.app_version_code
      val name = result.app_version_name
      return when {
        name != null && build != null -> "$name ($build)"
        else -> name ?: build
      }
    }

    /**
     * The viewer orders a test's retry attempts by `ranAt`, so this must be parseable — it renders
     * the same `yyyy-MM-dd HH:mm:ss` local stamp the log-backed report writes. Falls back to the
     * row's ISO string when the epoch field is absent (older results files carried only the text).
     */
    private fun ranAt(result: SessionResult): String? = result.started_at_epoch_ms
      ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).format(HUMAN_TS) }
      ?: result.started_at?.takeIf { it.isNotBlank() }

    /**
     * Map a result [Outcome] onto the viewer's badge vocabulary. `RunReportGenerator.statusLabel`
     * does the same for a live session status; the two must agree, or the index would section a
     * run differently from that run's own report.
     */
    internal fun statusLabel(outcome: Outcome): String = when (outcome) {
      Outcome.PASSED -> "passed"
      Outcome.FAILED,
      Outcome.ERROR,
      Outcome.TIMEOUT,
      Outcome.MAX_CALLS_REACHED -> "failed"
      Outcome.CANCELLED -> "cancelled"
      Outcome.SKIPPED -> "skipped"
    }
  }
}
