package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Query persisted test results from a flat-file index repo.
 *
 * The index repo is a key-value store on GitHub: two JSON files per (TestRail case, device
 * profile) cell at
 *   `results/testrail/<caseId>/<device>/latest.json`        (every terminal run)
 *   `results/testrail/<caseId>/<device>/latest_success.json` (passing runs only)
 *
 * The file is self-contained. Two schema versions are supported transparently:
 *   v1 (legacy):  { "testCaseId": "C12345", "device": "android-phone", "schemaVersion": 1, "run": { … } }
 *   v2 (current): { "schema_version": 2, "test_case_id": "C12345", "device": "android-phone",
 *                   "consecutive_failures": N, "metadata": {…}, "result": {…} }
 * The reader normalizes both shapes into a [CellView] before rendering; new cells are written
 * in v2 (see [xyz.block.trailblaze.report.models.TestResultCell]) and old v1 cells are still
 * readable during the migration window.
 *
 * The repo to query is resolved from `--repo` or the `TRAILBLAZE_RESULTS_REPO` env var
 * (in that order). Distributions that ship with a default repo do so by setting the env
 * var in their launcher script — the OSS CLI itself stays neutral.
 *
 *   trailblaze results show C12345 --device android-phone           - last passing run on this device
 *   trailblaze results show C12345 --device ios-iphone --latest      - last terminal run (pass or fail)
 *   trailblaze results show C12345 --all-devices                     - one-line summary per device
 *   trailblaze results show C12345 --device android-phone --json     - raw JSON for the cell
 */
@Command(
  name = "results",
  mixinStandardHelpOptions = true,
  description = [
    "Query the persisted test-result index for a TestRail case. " +
      "Passing a positional `<case-id>` (e.g. `trailblaze results C12345 --device android-phone`) " +
      "is equivalent to the explicit `trailblaze results show <case-id>` form — picocli routes " +
      "the bare case-id straight to the `show` subcommand.",
  ],
  subcommands = [ResultsShowCommand::class],
)
class ResultsCommand : Callable<Int> {
  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  /**
   * Injected by picocli so [call] can render the live parsed-tree usage (which carries the
   * full `trailblaze results` qualifier) instead of building a detached `CommandLine(this)`
   * (which renders the bare `Usage: results …` and loses the parent context).
   */
  @CommandLine.Spec
  internal lateinit var spec: CommandLine.Model.CommandSpec

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<case-id>",
    description = [
      "TestRail case ID. When supplied without a subcommand, this routes to `show <case-id>`. " +
        "Case-insensitive; the leading `C` is required (e.g. C12345).",
    ],
  )
  var caseId: String? = null

  @Option(
    names = ["--device"],
    paramLabel = "<profile>",
    description = ["Forwarded to `show --device`. See `trailblaze results show --help` for the full flag set."],
  )
  var device: String? = null

  @Option(
    names = ["--all-devices"],
    description = ["Forwarded to `show --all-devices`."],
  )
  var allDevices: Boolean = false

  @Option(
    names = ["--latest"],
    description = ["Forwarded to `show --latest`."],
  )
  var latest: Boolean = false

  @Option(
    names = ["--json"],
    description = ["Forwarded to `show --json`."],
  )
  var jsonOnly: Boolean = false

  @Option(
    names = ["--repo"],
    description = ["Forwarded to `show --repo`."],
  )
  var repo: String? = null

  /**
   * True when the user passed any of the forwarded options that only make sense alongside a
   * case-id (`--device`, `--all-devices`, `--latest`, `--json`, `--repo`). The two-way
   * routing makes a silent no-op the worst failure mode: a stray `trailblaze results
   * --device foo` (forgot the case-id) would otherwise print help + exit SUCCESS, masking
   * a broken automation. Surfacing it as MISUSE turns the typo into a loud failure.
   * Internal so call() can read it from outside this companion / this class.
   */
  internal fun anyForwardedOptionSet(): Boolean =
    device != null || allDevices || latest || jsonOnly || repo != null

  override fun call(): Int {
    if (caseId == null) {
      if (anyForwardedOptionSet()) {
        reportCliError(
          verb = "Results lookup",
          reason = "missing <case-id>",
          hint = "pass the case ID (e.g. `trailblaze results C12345 --device android-phone`) " +
            "or drop the forwarded flags to see the full help",
        )
        return TrailblazeExitCode.MISUSE.code
      }
      spec.commandLine().usage(System.out)
      return TrailblazeExitCode.SUCCESS.code
    }
    val show = ResultsShowCommand().also {
      it.caseId = caseId!!
      it.device = device
      it.allDevices = allDevices
      it.latest = latest
      it.jsonOnly = jsonOnly
      it.repo = repo
    }
    return show.call()
  }
}

@Command(
  name = "show",
  mixinStandardHelpOptions = true,
  description = ["Show the recorded result for a TestRail case ID"],
)
class ResultsShowCommand : Callable<Int> {

  /**
   * Set by picocli when this command is invoked via `trailblaze results show …` (the real
   * subcommand chain). Stays null when [ResultsCommand.call] instantiates this class
   * manually for the bare-positional routing (`trailblaze results <case-id>`) — that path
   * copies the forwarded fields directly, so no parent fallback is needed.
   *
   * Without this field, options passed BEFORE the subcommand name (e.g.
   * `trailblaze results --repo owner/name show C12345 --device android-phone`) bind to
   * the parent's [ResultsCommand] and never reach this command's own fields, silently
   * dropping the user's intent. Per-field fallbacks in [call] consult this parent so
   * either spelling works.
   */
  @CommandLine.ParentCommand
  internal var parent: ResultsCommand? = null

  @Parameters(
    index = "0",
    arity = "1",
    paramLabel = "<case-id>",
    description = [
      "TestRail case ID, e.g. C12345. Case-insensitive; the leading `C` is required.",
    ],
  )
  lateinit var caseId: String

  @Option(
    names = ["--device"],
    paramLabel = "<profile>",
    description = [
      "Device profile to look up (e.g. android-phone, android-tablet, " +
        "ios-iphone, ios-ipad, web). Required unless --all-devices is passed. " +
        "The same case runs on multiple devices and produces materially " +
        "different results per device, so this command refuses to guess.",
    ],
  )
  var device: String? = null

  @Option(
    names = ["--all-devices"],
    description = [
      "Print a one-line summary for every device profile that has a recorded result " +
        "for this case. Enumerates the case directory in the index repo. Mutually " +
        "exclusive with --device / --latest / --json (single-device flags). Exit " +
        "code: 0 iff every listed device's latest run is a pass AND a recorded " +
        "success file is present; 1 if any device is currently failing OR its latest " +
        "state could not be determined (e.g. 'data unavailable' shown when the cell's " +
        "latest.json couldn't be read — check stderr for the underlying HTTP error). " +
        "Usable as a CI gate, e.g. `results show C12345 --all-devices && deploy`.",
    ],
  )
  var allDevices: Boolean = false

  @Option(
    names = ["--latest"],
    description = [
      "Show the most recent terminal run instead of the most recent successful run. " +
        "Reads latest.json instead of latest_success.json for the chosen device cell.",
    ],
  )
  var latest: Boolean = false

  @Option(
    names = ["--json"],
    description = ["Print the raw JSON document instead of a pretty summary."],
  )
  var jsonOnly: Boolean = false

  @Option(
    names = ["--repo"],
    description = [
      "owner/name of the results repo to query. Falls back to the " +
        "TRAILBLAZE_RESULTS_REPO env var when not supplied. The OSS CLI ships with " +
        "no default; distributions point at their own index repo via the launcher.",
    ],
  )
  var repo: String? = null

  override fun call(): Int {
    // Forwarded-flag merge from the parent. picocli binds options *before* the
    // subcommand name to the parent ([ResultsCommand]) and options *after* to this
    // subcommand. Without this merge, `trailblaze results --repo owner/name show C12345`
    // would silently drop `--repo`. Each field falls back to the parent only when the
    // user didn't set it explicitly on the subcommand — explicit-on-show wins.
    parent?.let { p ->
      if (device == null) device = p.device
      if (!allDevices) allDevices = p.allDevices
      if (!latest) latest = p.latest
      if (!jsonOnly) jsonOnly = p.jsonOnly
      if (repo == null) repo = p.repo
    }

    val normalizedCaseId = normalizeCaseId(caseId) ?: run {
      reportCliError(
        verb = "Results lookup",
        reason = "invalid case ID '$caseId' — expected format Cxxxxx (e.g. C12345)",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    val resolvedRepo = repo
      ?: System.getenv(RESULTS_REPO_ENV_VAR)?.takeIf { it.isNotBlank() }
      ?: run {
        reportCliError(
          verb = "Results lookup",
          reason = "results repo not configured",
          hint = "pass --repo <owner/name> or set $RESULTS_REPO_ENV_VAR",
        )
        return TrailblazeExitCode.MISUSE.code
      }

    val token = resolveGithubToken()
    if (token == null) {
      reportCliError(
        verb = "Results lookup",
        reason = "GITHUB_TOKEN (or GH_TOKEN) is not set — most results repos are private",
        hint = "export GITHUB_TOKEN=<a personal access token with `repo` scope>",
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    if (allDevices) {
      if (device != null || latest || jsonOnly) {
        reportCliError(
          verb = "Results lookup",
          reason = "--all-devices is mutually exclusive with --device / --latest / --json",
        )
        return TrailblazeExitCode.MISUSE.code
      }
      return showAllDevices(normalizedCaseId, resolvedRepo, token)
    }

    val chosenDevice = device ?: run {
      reportCliError(
        verb = "Results lookup",
        reason = "missing --device",
        hint = "pass --device <profile> (e.g. android-phone, ios-iphone, web) " +
          "or --all-devices to enumerate every device profile",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    if (!chosenDevice.matches(DEVICE_SEGMENT_REGEX)) {
      reportCliError(
        verb = "Results lookup",
        target = chosenDevice,
        reason = "invalid --device — expected path-segment characters only (alphanumerics, '-', '_')",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    return showSingleDevice(normalizedCaseId, chosenDevice, resolvedRepo, token)
  }

  private fun showSingleDevice(
    caseId: String,
    device: String,
    repo: String,
    token: String,
  ): Int {
    val fileName = if (latest) "latest.json" else "latest_success.json"
    val path = "results/testrail/$caseId/$device/$fileName"

    val rawJson = when (val outcome = runBlocking { fetchContents(repo = repo, path = path, token = token) }) {
      is FetchOutcome.Found -> outcome.body
      is FetchOutcome.NotFound -> {
        if (latest) {
          Console.log("No latest run recorded for $caseId on $device (looked for $path).")
          Console.log("This (case, device) cell may not have run yet.")
        } else {
          Console.log("No passing run recorded for $caseId on $device (looked for $path).")
          Console.log("Use --latest to see the most recent terminal run, or pass --all-devices to enumerate.")
        }
        // Genuine "no recorded result" — ASSERTION_FAILED is the right "CI gate"
        // semantic: if a script chained `results show … && deploy`, the absence
        // of a passing run should block the deploy the same way a recorded
        // failure would.
        return TrailblazeExitCode.ASSERTION_FAILED.code
      }
      is FetchOutcome.TransportError -> {
        // Auth/rate-limit/5xx — we couldn't ask the question, not "the answer
        // is no." Route to INFRA_FAILED so a chained `&& deploy` distinguishes
        // "no recorded pass" (CI gate fires) from "GitHub rejected the lookup"
        // (operator needs to investigate the token/rate-limit/outage).
        reportCliError(
          verb = "Results lookup",
          target = path,
          reason = "GitHub returned HTTP ${outcome.statusCode}",
          hint = "check the token's scopes, watch for rate limit (429), or retry after a known GitHub outage",
        )
        return TrailblazeExitCode.INFRA_FAILED.code
      }
    }

    if (jsonOnly) {
      println(rawJson)
      return TrailblazeExitCode.SUCCESS.code
    }

    val parsed: JsonObject = try {
      RESULTS_JSON.parseToJsonElement(rawJson).jsonObject
    } catch (e: Exception) {
      reportCliError(
        verb = "Results parse",
        target = path,
        reason = describeThrowableForUser(e),
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val view = CellView.fromCellJson(parsed) ?: run {
      reportCliError(
        verb = "Results parse",
        target = path,
        reason = "file shape unrecognized — neither v1 `run` block nor v2 `result` block present",
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    prettyPrint(caseId, device, isLatestFile = latest, view = view)
    return TrailblazeExitCode.SUCCESS.code
  }

  private fun showAllDevices(caseId: String, repo: String, token: String): Int {
    val devicesPath = "results/testrail/$caseId"
    val listingJson = when (val outcome = runBlocking { fetchContents(repo = repo, path = devicesPath, token = token, raw = false) }) {
      is FetchOutcome.Found -> outcome.body
      is FetchOutcome.NotFound -> {
        Console.log("No recorded results for $caseId at $repo:$devicesPath.")
        Console.log("This case may not have run on any device yet.")
        // "No recorded results" is the CI-gate equivalent of a failing assertion:
        // chained as `results show … && deploy` it should block the deploy the
        // same way a recorded failure would.
        return TrailblazeExitCode.ASSERTION_FAILED.code
      }
      is FetchOutcome.TransportError -> {
        reportCliError(
          verb = "Results lookup",
          target = devicesPath,
          reason = "GitHub returned HTTP ${outcome.statusCode}",
          hint = "check the token's scopes, watch for rate limit (429), or retry after a known GitHub outage",
        )
        return TrailblazeExitCode.INFRA_FAILED.code
      }
    }

    val listing: JsonArray = try {
      RESULTS_JSON.parseToJsonElement(listingJson).jsonArray
    } catch (e: Exception) {
      reportCliError(
        verb = "Results parse",
        target = devicesPath,
        reason = describeThrowableForUser(e),
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val deviceDirs = listing
      .mapNotNull { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.contentOrNull == "dir" }
      .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
      .sorted()

    if (deviceDirs.isEmpty()) {
      Console.log("$caseId: case directory exists but no device subdirs found.")
      // Same CI-gate semantic as the "no listing" path above.
      return TrailblazeExitCode.ASSERTION_FAILED.code
    }

    println("Results for $caseId across ${deviceDirs.size} device(s):")
    println("=".repeat(60))
    println(String.format("  %-18s  %-7s  %-22s  %s", "Device", "Latest", "Latest success", "Notes"))
    println("  " + "-".repeat(58))

    var anyNonPassing = false
    for (deviceId in deviceDirs) {
      val latest = fetchSummary(repo, caseId, deviceId, "latest.json", token)
      val latestSuccess = fetchSummary(repo, caseId, deviceId, "latest_success.json", token)
      val notes = summarizeNotes(latest, latestSuccess)
      if (notes.isNonPassing) anyNonPassing = true
      println(
        String.format(
          "  %-18s  %-7s  %-22s  %s",
          deviceId,
          latest.status ?: "?",
          latestSuccess.timestamp ?: "—",
          notes.text,
        ),
      )
    }
    // Non-zero exit when at least one device is not in a clean passing state. "Not passing"
    // covers both "currently failing" AND "data unavailable" (could not read latest.json):
    // the directory listing is authoritative — if the device dir exists, the test ran on it,
    // and a 403/corrupt-file/missing-file on an existing cell is a real "we don't know"
    // condition that a CI gate (`results show … --all-devices && deploy`) should treat
    // conservatively. The prior shape silently swallowed unknowns as "0 = clean." Maps to
    // ASSERTION_FAILED (1) because the verdict is an answer ("not green"), not an
    // infra/transport failure.
    return if (anyNonPassing) TrailblazeExitCode.ASSERTION_FAILED.code else TrailblazeExitCode.SUCCESS.code
  }

  /**
   * Snapshot of the fields we surface from a (case, device) cell file. Any may be null
   * if the file is absent or the field isn't recorded.
   */
  internal data class CellSummary(
    val status: String?,
    val timestamp: String?,
    val consecutiveFailures: Int?,
  ) {
    companion object {
      val EMPTY = CellSummary(null, null, null)
    }
  }

  /**
   * Normalized view of a cell file's contents, shielding the renderer from the v1/v2 schema
   * split. `status` is normalized to the v1 vocabulary ("pass" / "fail" / lowercase outcome
   * for non-terminal states) so a v2 cell prints identically to a v1 cell of the same outcome.
   *
   * Created via [fromCellJson], which detects the schema by looking for the v2 `result` key
   * (or `schema_version` = 2) and falls back to the v1 `run` shape.
   */
  internal data class CellView(
    val status: String?,
    val timestamp: String?,
    val sessionId: String?,
    val executionMs: String?,
    val llmCalls: String?,
    val gitSha: String?,
    val appVersion: String?,
    val consecutiveFailures: Int?,
    val buildUrl: String?,
    val logsZipUrl: String?,
    val logsZipFilename: String?,
  ) {
    companion object {
      /** Highest schema version this reader knows how to interpret. */
      internal const val MAX_SUPPORTED_SCHEMA_VERSION = 2

      fun fromCellJson(doc: JsonObject): CellView? {
        val schemaVersion = doc["schema_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        // Reject schema versions newer than we understand. A future writer that bumps to 3
        // may restructure fields; pretending to read it as v2 silently produces wrong data.
        if (schemaVersion != null && schemaVersion > MAX_SUPPORTED_SCHEMA_VERSION) {
          Console.error(
            "Cell file declares schema_version=$schemaVersion but reader supports at most " +
              "$MAX_SUPPORTED_SCHEMA_VERSION. Upgrade the trailblaze CLI to read this cell.",
          )
          return null
        }
        val explicitV2 = schemaVersion?.let { it >= 2 } == true
        val resultBlock = doc["result"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val runBlock = doc["run"]?.let { runCatching { it.jsonObject }.getOrNull() }
        // Mixed shapes are diagnostic of either a corruption or an incomplete migration —
        // surface but don't fail: v2 wins because that's the authoritative current shape.
        if (resultBlock != null && runBlock != null) {
          Console.error(
            "Cell file contains BOTH legacy `run` block AND current `result` block; " +
              "using `result` (v2) and ignoring `run` (v1). Check for partial-migration artifacts.",
          )
        }
        if (explicitV2 || resultBlock != null) {
          if (resultBlock == null) {
            // Distinct error from "unknown shape" so a v2-but-malformed file shows the right
            // root cause instead of looking identical to a totally unrecognized document.
            Console.error("Cell file declares schema_version=$schemaVersion but is missing the required `result` block.")
            return null
          }
          val metadata = doc["metadata"]?.let { runCatching { it.jsonObject }.getOrNull() }
          return fromV2(resultBlock, metadata, doc)
        }
        if (runBlock == null) return null
        return fromV1(runBlock)
      }

      // Migration note: `fromV1` is the legacy v1 (`run` block) reader. It can be deleted
      // once every cell in the index repo has been re-published in v2 shape — either via a
      // one-off backfill pass that rewrites every existing cell, OR via natural turnover
      // (every test case has run at least once since the v2 writer landed). Until then the
      // legacy `run` block remains the only signal for stale cells that haven't seen a new run.
      private fun fromV1(run: JsonObject): CellView = CellView(
        status = run["status"]?.jsonPrimitive?.contentOrNull,
        timestamp = run["timestamp"]?.jsonPrimitive?.contentOrNull,
        sessionId = run["sessionId"]?.jsonPrimitive?.contentOrNull,
        executionMs = run["executionTimeMs"]?.jsonPrimitive?.contentOrNull,
        llmCalls = run["llmCallCount"]?.jsonPrimitive?.contentOrNull,
        gitSha = run["trailblazeInternalGitSha"]?.jsonPrimitive?.contentOrNull,
        appVersion = run["appVersionUnderTest"]?.jsonPrimitive?.contentOrNull,
        consecutiveFailures = run["consecutiveFailures"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        buildUrl = run["buildUrl"]?.jsonPrimitive?.contentOrNull,
        logsZipUrl = run["logsZipUrl"]?.jsonPrimitive?.contentOrNull,
        logsZipFilename = null, // v1 has no separate filename field
      )

      private fun fromV2(result: JsonObject, metadata: JsonObject?, root: JsonObject): CellView {
        val outcome = result["outcome"]?.jsonPrimitive?.contentOrNull
        return CellView(
          status = normalizeOutcome(outcome),
          timestamp = result["completed_at"]?.jsonPrimitive?.contentOrNull,
          sessionId = result["session_id"]?.jsonPrimitive?.contentOrNull,
          executionMs = result["duration_ms"]?.jsonPrimitive?.contentOrNull,
          llmCalls = result["llm_call_count"]?.jsonPrimitive?.contentOrNull,
          gitSha = metadata?.get("git_commit")?.jsonPrimitive?.contentOrNull,
          appVersion = metadata?.get("android_build_version")?.jsonPrimitive?.contentOrNull
            ?: metadata?.get("ios_build_version")?.jsonPrimitive?.contentOrNull,
          consecutiveFailures = root["consecutive_failures"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
          buildUrl = metadata?.get("ci_build_url")?.jsonPrimitive?.contentOrNull,
          logsZipUrl = result["logs_zip_url"]?.jsonPrimitive?.contentOrNull,
          logsZipFilename = result["logs_zip_filename"]?.jsonPrimitive?.contentOrNull,
        )
      }

      /**
       * Normalize a v2 [xyz.block.trailblaze.report.models.Outcome] enum value to the v1
       * status vocabulary so the renderer and the `--all-devices` exit-code logic stay
       * uniform across both schemas. `PASSED` is the only "pass"; the four terminal failure
       * modes we know about collapse to `"fail"`; `SKIPPED` / `CANCELLED` pass through
       * lowercase; anything else (including future outcome enum values) maps to the
       * `"unknown"` sentinel so `summarizeNotes` (which checks `latest.status != "pass"`)
       * conservatively treats it as not-passing rather than silently green-lighting a new
       * outcome we don't recognize.
       */
      internal fun normalizeOutcome(outcome: String?): String? = when (outcome) {
        null -> null
        "PASSED" -> "pass"
        "FAILED", "TIMEOUT", "MAX_CALLS_REACHED", "ERROR" -> "fail"
        "SKIPPED", "CANCELLED" -> outcome.lowercase()
        else -> {
          Console.error("Unknown outcome '$outcome' in cell file; treating as 'unknown' for CI-gate safety.")
          "unknown"
        }
      }
    }
  }

  /**
   * The text + "is this a non-passing device" verdict for one row in the `--all-devices`
   * table. Extracted as a pure data type so the rendering logic + the exit-code logic
   * can be unit-tested without standing up an HTTP fixture.
   */
  internal data class NotesResult(val text: String, val isNonPassing: Boolean)

  /**
   * Pull `run.status`, `run.timestamp`, `run.consecutiveFailures` from one cell file.
   * Returns [CellSummary.EMPTY] when the file is missing or unparseable.
   */
  private fun fetchSummary(
    repo: String,
    caseId: String,
    device: String,
    fileName: String,
    token: String,
  ): CellSummary {
    val path = "results/testrail/$caseId/$device/$fileName"
    // `fetchSummary` is one row in the --all-devices table; a TransportError on a
    // single cell shouldn't tear the whole table down (other rows might still
    // succeed). Treat it as "data unavailable" (the same shape as NotFound from
    // `summarizeNotes`'s point of view) — the row's verdict logic already maps
    // missing data to "not passing." The actual transport error has already been
    // logged to stderr by `fetchContents`.
    val raw = when (val outcome = runBlocking { fetchContents(repo = repo, path = path, token = token) }) {
      is FetchOutcome.Found -> outcome.body
      is FetchOutcome.NotFound, is FetchOutcome.TransportError -> null
    }
    return parseRunBlock(raw)
  }

  private fun prettyPrint(caseId: String, device: String, isLatestFile: Boolean, view: CellView) {
    val header = if (isLatestFile) {
      "Most recent run of $caseId on $device"
    } else {
      "Last successful run of $caseId on $device"
    }
    println(header)
    println("=".repeat(header.length))
    println("  Status:        ${view.status ?: "?"}")
    println("  When:          ${view.timestamp ?: "?"}")
    if (view.sessionId != null) println("  Session id:    ${view.sessionId}")
    if (view.executionMs != null) println("  Duration ms:   ${view.executionMs}")
    if (view.llmCalls != null) println("  LLM calls:     ${view.llmCalls}")
    if (view.gitSha != null) println("  Repo SHA:      ${view.gitSha}")
    if (view.appVersion != null) println("  App version:   ${view.appVersion}")
    if (view.consecutiveFailures != null && isLatestFile) {
      println("  Consec. fails: ${view.consecutiveFailures}")
    }
    if (view.buildUrl != null || view.logsZipUrl != null || view.logsZipFilename != null) {
      println()
      println("Links:")
      if (view.buildUrl != null) println("  Build:         ${view.buildUrl}")
      if (view.logsZipUrl != null) println("  Logs zip:      ${view.logsZipUrl}")
      if (view.logsZipFilename != null) println("  Logs file:     ${view.logsZipFilename}")
    }
  }

  /**
   * Outcome of a single GitHub contents-API fetch. Distinguishes the three states
   * the caller needs to route on:
   *  - [Found] — 2xx with body. The happy path.
   *  - [NotFound] — 404. The expected "no such (case, device, file) yet" branch.
   *    Maps to [TrailblazeExitCode.ASSERTION_FAILED] at the call site (CI-gate
   *    semantic: "no recorded pass" should block a chained deploy).
   *  - [TransportError] — 401 / 403 / 429 / 5xx. A revoked token, rate limit, or
   *    GitHub outage looks identical to "no data" if we swallow it as null —
   *    routed to [TrailblazeExitCode.INFRA_FAILED] instead so the operator can
   *    actually triage the auth/transport failure.
   */
  internal sealed interface FetchOutcome {
    data class Found(val body: String) : FetchOutcome
    data object NotFound : FetchOutcome
    data class TransportError(val statusCode: Int, val message: String) : FetchOutcome
  }

  /**
   * Fetch the contents of a path from the index repo. When [raw] is true (default), uses
   * the `application/vnd.github.raw+json` accept header — the response body IS the file
   * contents. When false, uses the standard `application/vnd.github+json` accept header,
   * appropriate for directory listings (which return a JSON array of entries).
   */
  private suspend fun fetchContents(repo: String, path: String, token: String, raw: Boolean = true): FetchOutcome {
    val response = resultsHttpClient.get("https://api.github.com/repos/$repo/contents/$path") {
      header("Authorization", "Bearer $token")
      header(
        "Accept",
        if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json",
      )
      header("X-GitHub-Api-Version", "2022-11-28")
    }
    return when {
      // 404 is the expected "no such (case, device, file) yet" path and stays quiet — the
      // caller turns it into a user-facing "no result recorded" message + ASSERTION_FAILED.
      response.status == HttpStatusCode.NotFound -> FetchOutcome.NotFound
      response.status.isSuccess() -> FetchOutcome.Found(response.bodyAsText())
      else -> {
        // 401/403/429/5xx all need to surface to the operator with the status code —
        // a revoked token (403) or rate limit (429) looks identical to a 404 if we swallow
        // it. Routed through `Console.error` so it lands on stderr and doesn't pollute
        // `--json` consumers piping stdout into `jq`. Body is included but truncated to
        // keep noisy GitHub HTML error pages from drowning the log.
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val message = "GitHub contents API ${response.status.value} ${response.status.description} " +
          "for $repo/$path${if (body.isNotBlank()) ": ${body.take(200)}" else ""}"
        Console.error(message)
        FetchOutcome.TransportError(response.status.value, message)
      }
    }
  }

  companion object {
    /**
     * Env var name consulted when `--repo` isn't passed. Distributions that ship with a
     * specific index repo set this in their launcher script so end users don't have to.
     */
    const val RESULTS_REPO_ENV_VAR: String = "TRAILBLAZE_RESULTS_REPO"

    /**
     * Lenient JSON parser: the on-disk schema can grow new fields in additive
     * version bumps and older CLIs should keep working.
     */
    private val RESULTS_JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /**
     * Path-segment-safe device id (alphanumerics, '-', '_'). Internal-visible so unit tests
     * can pin the contract directly.
     *
     * **Keep in sync** with the writer-side `TRAILBLAZE_TEST_RESULTS_DEVICE` validation in the publisher script
     * and with the index repo's JSON Schema `device` field pattern. All three enforce the
     * same shape — change one, change them all, or one side will accept paths the other
     * refuses.
     */
    internal val DEVICE_SEGMENT_REGEX: Regex = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Normalize user-typed case IDs to the canonical `C12345` form. Accepts
     * `c12345` and `12345` for convenience; rejects anything else so a typo
     * doesn't silently fetch the wrong path.
     */
    internal fun normalizeCaseId(input: String): String? {
      val trimmed = input.trim()
      if (trimmed.isEmpty()) return null
      val digits = if (trimmed.startsWith("C", ignoreCase = true)) trimmed.drop(1) else trimmed
      if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
      return "C$digits"
    }

    /**
     * Parse a slice of cell-file JSON into the fields `--all-devices` cares about. Returns
     * [CellSummary.EMPTY] for null input (file missing on the remote), unparseable JSON,
     * or a document that doesn't contain either a v1 `run` block or a v2 `result` block.
     * The lenient handling intentionally keeps the table-rendering path robust against
     * partial / future-shape documents — the exit-code logic in `showAllDevices` treats
     * EMPTY conservatively (non-passing) so a silent parse failure doesn't fake a clean state.
     */
    internal fun parseRunBlock(rawJson: String?): CellSummary {
      if (rawJson == null) return CellSummary.EMPTY
      val parsed = try {
        RESULTS_JSON.parseToJsonElement(rawJson).jsonObject
      } catch (_: Exception) {
        return CellSummary.EMPTY
      }
      val view = CellView.fromCellJson(parsed) ?: return CellSummary.EMPTY
      return CellSummary(
        status = view.status,
        timestamp = view.timestamp,
        consecutiveFailures = view.consecutiveFailures,
      )
    }

    /**
     * Compute the row-notes text + the "this device is not in a clean passing state"
     * verdict for one `--all-devices` row. Extracted as a pure function so the rendering
     * + exit-code logic can be unit-tested without an HTTP fixture.
     *
     * Verdict matrix (latest.status × latestSuccess.timestamp):
     *  - pass × success-timestamp present  → notes empty, NOT non-passing (the clean case)
     *  - pass × null                       → notes "never passed", **non-passing**
     *      (the only way this happens is `latest_success.json` missing / 403 / mid-write —
     *      the publisher always writes both files on pass, so absence is a fetch-side
     *      anomaly, not a real "passing forever without success." Conservative gate.)
     *  - fail × any                        → "FAILING" / "N consecutive fails", non-passing
     *  - null (unknown) × any              → "data unavailable", non-passing
     *      (a 403/corrupt-file/missing-file on a LISTED device dir is a real "can't tell"
     *      state — the directory listing is authoritative for "did the test run here.")
     *  - any × null (no recorded success)  → append " · never passed"
     *
     * Verdict rule: `isNonPassing` iff `latest.status != "pass"` OR
     * `latestSuccess.timestamp == null`. Either side incomplete means CI gate must not
     * pass.
     */
    internal fun summarizeNotes(latest: CellSummary, latestSuccess: CellSummary): NotesResult {
      val text = buildString {
        when (latest.status) {
          "fail" -> {
            if (latest.consecutiveFailures != null) {
              append("${latest.consecutiveFailures} consecutive fails")
            } else {
              append("FAILING")
            }
          }
          "pass" -> { /* clean state — no notes from this branch */ }
          else -> {
            // null / unknown — latest.json couldn't be read or parsed
            append("data unavailable")
          }
        }
        if (latestSuccess.timestamp == null) {
          if (isNotEmpty()) append(" · ")
          append("never passed")
        }
      }
      // Conservative CI-gate semantic: not-clean if either side is incomplete. A pass-status
      // with no recorded success is a real anomaly (the publisher always writes both files
      // on pass) and shouldn't green-light a `results show … && deploy` chain — we don't
      // have enough information to be confident.
      val isNonPassing = latest.status != "pass" || latestSuccess.timestamp == null
      return NotesResult(text = text, isNonPassing = isNonPassing)
    }

    private fun resolveGithubToken(): String? =
      System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }

    /**
     * Single shared client for the whole CLI process. `trailblaze results show` is one-shot
     * and the JVM exits when it returns, so no explicit close is needed — and `by lazy`
     * defers construction until the first command actually fetches (e.g., `--help` doesn't
     * pay the OkHttp init cost).
     *
     * Earlier shape was a `get()` property that built a fresh client on every access and
     * relied on `.use { }` for closing; that's fragile (one missing `.use` leaks an
     * OkHttp dispatcher thread pool) and pointless when the client's lifetime is "until
     * the JVM exits anyway."
     */
    private val resultsHttpClient: HttpClient by lazy {
      HttpClient(OkHttp) {
        install(HttpTimeout) {
          connectTimeoutMillis = 10_000
          requestTimeoutMillis = 30_000
        }
      }
    }
  }
}
