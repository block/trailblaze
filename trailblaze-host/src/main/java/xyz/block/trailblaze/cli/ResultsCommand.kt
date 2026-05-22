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
 * Each file is self-contained:
 *   { "testCaseId": "C12345", "device": "android-phone", "schemaVersion": 1, "run": { … } }
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
  description = ["Query the persisted test-result index for a TestRail case"],
  subcommands = [ResultsShowCommand::class],
)
class ResultsCommand : Callable<Int> {
  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}

@Command(
  name = "show",
  mixinStandardHelpOptions = true,
  description = ["Show the recorded result for a TestRail case ID"],
)
class ResultsShowCommand : Callable<Int> {

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
    val normalizedCaseId = normalizeCaseId(caseId) ?: run {
      Console.log("Invalid case ID '$caseId' — expected format Cxxxxx (e.g. C12345).")
      return 1
    }

    val resolvedRepo = repo
      ?: System.getenv(RESULTS_REPO_ENV_VAR)?.takeIf { it.isNotBlank() }
      ?: run {
        Console.log(
          "Results repo not configured — pass --repo <owner/name> or set $RESULTS_REPO_ENV_VAR.",
        )
        return 1
      }

    val token = resolveGithubToken()
    if (token == null) {
      Console.log(
        "GITHUB_TOKEN (or GH_TOKEN) is not set — most results repos are private and require a token.",
      )
      return 1
    }

    if (allDevices) {
      if (device != null || latest || jsonOnly) {
        Console.log("--all-devices is mutually exclusive with --device / --latest / --json.")
        return 1
      }
      return showAllDevices(normalizedCaseId, resolvedRepo, token)
    }

    val chosenDevice = device ?: run {
      Console.log(
        "Pass --device <profile> (e.g. android-phone, ios-iphone, ios-ipad, web) " +
          "or --all-devices to enumerate.",
      )
      return 1
    }
    if (!chosenDevice.matches(DEVICE_SEGMENT_REGEX)) {
      Console.log("Invalid device '$chosenDevice' — expected path-segment characters only (alphanumerics, '-', '_').")
      return 1
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

    val rawJson = runBlocking {
      fetchContents(repo = repo, path = path, token = token)
    } ?: run {
      if (latest) {
        Console.log("No latest run recorded for $caseId on $device (looked for $path).")
        Console.log("This (case, device) cell may not have run yet.")
      } else {
        Console.log("No passing run recorded for $caseId on $device (looked for $path).")
        Console.log("Use --latest to see the most recent terminal run, or pass --all-devices to enumerate.")
      }
      return 1
    }

    if (jsonOnly) {
      println(rawJson)
      return 0
    }

    val parsed: JsonObject = try {
      RESULTS_JSON.parseToJsonElement(rawJson).jsonObject
    } catch (e: Exception) {
      Console.log("Failed to parse result document at $path: ${e.message}")
      return 1
    }

    val run = parsed["run"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: run {
      Console.log("$path: missing `run` block (file shape may be stale).")
      return 1
    }

    prettyPrint(caseId, device, isLatestFile = latest, run = run)
    return 0
  }

  private fun showAllDevices(caseId: String, repo: String, token: String): Int {
    val devicesPath = "results/testrail/$caseId"
    val listingJson = runBlocking {
      fetchContents(repo = repo, path = devicesPath, token = token, raw = false)
    } ?: run {
      Console.log("No recorded results for $caseId at $repo:$devicesPath.")
      Console.log("This case may not have run on any device yet.")
      return 1
    }

    val listing: JsonArray = try {
      RESULTS_JSON.parseToJsonElement(listingJson).jsonArray
    } catch (e: Exception) {
      Console.log("Failed to parse device listing for $caseId: ${e.message}")
      return 1
    }

    val deviceDirs = listing
      .mapNotNull { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.contentOrNull == "dir" }
      .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
      .sorted()

    if (deviceDirs.isEmpty()) {
      Console.log("$caseId: case directory exists but no device subdirs found.")
      return 1
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
    // conservatively. The prior shape silently swallowed unknowns as "0 = clean."
    return if (anyNonPassing) 1 else 0
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
    val raw = runBlocking { fetchContents(repo = repo, path = path, token = token) }
    return parseRunBlock(raw)
  }

  private fun prettyPrint(caseId: String, device: String, isLatestFile: Boolean, run: JsonObject) {
    val status = run["status"]?.jsonPrimitive?.contentOrNull ?: "?"
    val timestamp = run["timestamp"]?.jsonPrimitive?.contentOrNull ?: "?"
    val sessionId = run["sessionId"]?.jsonPrimitive?.contentOrNull
    val executionMs = run["executionTimeMs"]?.jsonPrimitive?.contentOrNull
    val llmCalls = run["llmCallCount"]?.jsonPrimitive?.contentOrNull
    val trailPath = run["trailPath"]?.jsonPrimitive?.contentOrNull
    val sha = run["trailblazeInternalGitSha"]?.jsonPrimitive?.contentOrNull
    val appVersion = run["appVersionUnderTest"]?.jsonPrimitive?.contentOrNull
    val cluster = run["failureClusterTag"]?.jsonPrimitive?.contentOrNull
    val consecFails = run["consecutiveFailures"]?.jsonPrimitive?.contentOrNull
    val buildUrl = run["buildUrl"]?.jsonPrimitive?.contentOrNull
    val reportUrl = run["reportUrl"]?.jsonPrimitive?.contentOrNull
    val logsZipUrl = run["logsZipUrl"]?.jsonPrimitive?.contentOrNull

    val header = if (isLatestFile) {
      "Most recent run of $caseId on $device"
    } else {
      "Last successful run of $caseId on $device"
    }
    println(header)
    println("=".repeat(header.length))
    println("  Status:        $status")
    println("  When:          $timestamp")
    if (sessionId != null) println("  Session id:    $sessionId")
    if (executionMs != null) println("  Duration ms:   $executionMs")
    if (llmCalls != null) println("  LLM calls:     $llmCalls")
    if (trailPath != null) println("  Trail path:    $trailPath")
    if (sha != null) println("  Repo SHA:      $sha")
    if (appVersion != null) println("  App version:   $appVersion")
    if (cluster != null) println("  Cluster tag:   $cluster")
    if (consecFails != null && isLatestFile) {
      println("  Consec. fails: $consecFails")
    }
    if (buildUrl != null || reportUrl != null || logsZipUrl != null) {
      println()
      println("Links:")
      if (buildUrl != null) println("  Build:         $buildUrl")
      if (reportUrl != null) println("  Report:        $reportUrl")
      if (logsZipUrl != null) println("  Logs zip:      $logsZipUrl")
    }
  }

  /**
   * Fetch the contents of a path from the index repo. When [raw] is true (default), uses
   * the `application/vnd.github.raw+json` accept header — the response body IS the file
   * contents. When false, uses the standard `application/vnd.github+json` accept header,
   * appropriate for directory listings (which return a JSON array of entries).
   */
  private suspend fun fetchContents(repo: String, path: String, token: String, raw: Boolean = true): String? {
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
      // caller turns it into a user-facing "no result recorded" message.
      response.status == HttpStatusCode.NotFound -> null
      response.status.isSuccess() -> response.bodyAsText()
      else -> {
        // 401/403/429/5xx all need to surface to the operator with the status code —
        // a revoked token (403) or rate limit (429) looks identical to a 404 if we swallow
        // it, and triage gets miserable. Routed through `Console.error` so it lands on
        // stderr and doesn't pollute `--json` consumers piping stdout into `jq`. Body is
        // included but truncated to keep noisy GitHub HTML error pages from drowning the log.
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        Console.error(
          "GitHub contents API ${response.status.value} ${response.status.description} " +
            "for $repo/$path${if (body.isNotBlank()) ": ${body.take(200)}" else ""}",
        )
        null
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
     * or a document that doesn't contain a `run` object. The lenient handling intentionally
     * keeps the table-rendering path robust against partial / future-shape documents — the
     * exit-code logic in `showAllDevices` treats EMPTY conservatively (non-passing) so a
     * silent parse failure doesn't fake a clean state.
     */
    internal fun parseRunBlock(rawJson: String?): CellSummary {
      if (rawJson == null) return CellSummary.EMPTY
      val parsed = try {
        RESULTS_JSON.parseToJsonElement(rawJson).jsonObject
      } catch (_: Exception) {
        return CellSummary.EMPTY
      }
      val run = parsed["run"]?.let { runCatching { it.jsonObject }.getOrNull() }
        ?: return CellSummary.EMPTY
      return CellSummary(
        status = run["status"]?.jsonPrimitive?.contentOrNull,
        timestamp = run["timestamp"]?.jsonPrimitive?.contentOrNull,
        consecutiveFailures = run["consecutiveFailures"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
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
