package xyz.block.trailblaze.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.models.CiRunMetadata
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SOURCE_TYPE_HANDWRITTEN
import xyz.block.trailblaze.report.models.SessionResult

/**
 * The result-row → link-out-stub mapping, which is the whole contract between a CI results file
 * and the report viewer's matrix. Pure: no bun, no logs dir, no network — the rendering half is
 * covered by the `link-out index stubs` suite in run-report-core.test.ts.
 */
class RunIndexGeneratorTest {

  private fun row(
    testKey: String = "checkout/pay",
    deviceClassifier: String = "android-phone",
    platform: String = "android",
    outcome: Outcome = Outcome.PASSED,
    zipUrl: String? = "https://cdn.example/results/C1/$deviceClassifier/runs/9224-job-sess.zip",
    llmCalls: Int? = 12,
    llmCost: Double? = 0.42,
  ) = SessionResult(
    session_id = SessionId("$testKey-$deviceClassifier"),
    title = testKey,
    test_key = testKey,
    platform = platform,
    outcome = outcome,
    execution_mode = ExecutionMode.RECORDING_ONLY,
    trail_source = SOURCE_TYPE_HANDWRITTEN,
    device_classifier = deviceClassifier,
    duration_ms = 35_400,
    llm_call_count = llmCalls,
    llm_cost_usd = llmCost,
    logs_zip_url = zipUrl,
  )

  private fun report(vararg results: SessionResult, targetApp: String = "square") = CiSummaryReport(
    metadata = CiRunMetadata(
      target_app = targetApp,
      ci_build_number = "9224",
      ci_build_url = "https://ci.example/builds/9224",
      git_commit = "abc123",
      git_branch = "main",
    ),
    results = results.toList(),
  )

  private fun metaOf(session: JsonObject) = session["meta"]!!.jsonObject
  private fun string(meta: JsonObject, key: String) = meta[key]?.jsonPrimitive?.content

  @Test
  fun `a row becomes a stub carrying the four fields the matrix is keyed on`() {
    val sessions = RunIndexGenerator.stubSessions(listOf(report(row())), "https://cdn.example/viewer/index.html")
    assertEquals(1, sessions.size)
    val meta = metaOf(sessions[0].jsonObject)
    // Row identity (trail + target) and column identity (platform + classifier): without all four
    // the viewer renders a flat list of unrelated runs instead of one row of per-device cells.
    assertEquals("checkout/pay", string(meta, "trailId"))
    assertEquals("square", string(meta, "target"))
    assertEquals("android", string(meta, "platform"))
    assertEquals("android-phone", string(meta, "deviceClassifier"))
    assertEquals("passed", string(meta, "status"))
    assertEquals("35.4s", string(meta, "duration"))
  }

  @Test
  fun `a stub carries no logs, which is what keeps the index small`() {
    val sessions = RunIndexGenerator.stubSessions(listOf(report(row())), null)
    val session = sessions[0].jsonObject
    assertEquals(0, session["logs"]!!.jsonArray.size)
    // The driver reads sessionDir off disk; nothing under it exists, and nothing may point outside
    // its temp working directory.
    val sessionDir = session["sessionDir"]!!.jsonPrimitive.content
    assertFalse(sessionDir.contains("/"), "session dir must be a bare name; got $sessionDir")
  }

  @Test
  fun `the cell links to the viewer carrying this run's archive as an encoded zip param`() {
    val sessions = RunIndexGenerator.stubSessions(listOf(report(row())), "https://cdn.example/viewer/index.html")
    assertEquals(
      "https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fresults%2FC1%2Fandroid-phone%2Fruns%2F9224-job-sess.zip",
      string(metaOf(sessions[0].jsonObject), "reportUrl"),
    )
  }

  @Test
  fun `a viewer URL that already carries a query gains the zip param rather than a second question mark`() {
    val url = RunIndexGenerator.reportUrl(row(), "https://cdn.example/viewer/index.html?theme=light")
    assertTrue(url!!.startsWith("https://cdn.example/viewer/index.html?theme=light&zip="), url)
  }

  @Test
  fun `a run with no archive and a build with no viewer both render unlinked instead of broken`() {
    // Either half missing means there is nothing to open — the cell must show its outcome and stop,
    // not link to a viewer with no archive to load.
    assertNull(RunIndexGenerator.reportUrl(row(zipUrl = null), "https://cdn.example/viewer/index.html"))
    assertNull(RunIndexGenerator.reportUrl(row(), null))
    assertNull(RunIndexGenerator.reportUrl(row(), "   "))
    val sessions = RunIndexGenerator.stubSessions(listOf(report(row(zipUrl = null))), "https://cdn.example/viewer/index.html")
    assertNull(metaOf(sessions[0].jsonObject)["reportUrl"])
  }

  @Test
  fun `a skipped row states its reason as a skip, never as an error`() {
    // The results JSON carries a skip's reason in failure_reason — the one field consumers already
    // read as "why this row is not a plain pass". The viewer must not inherit that framing: it
    // styles `error` in the failure vocabulary and sections a run by its status.
    val skipped = row(outcome = Outcome.SKIPPED, zipUrl = null)
      .copy(failure_reason = "backend outage, see #2194")
    val meta = RunIndexGenerator.stubMeta(skipped, report(skipped), null)

    assertEquals("skipped", string(meta, "status"))
    assertEquals("backend outage, see #2194", string(meta, "skipReason"))
    assertNull(meta["error"])
    // A skip's duration_ms is 0 because nothing ran. Formatted, that is "0ms" - a run that
    // finished instantly, which is the one thing this cell must not claim.
    assertNull(meta["duration"])
  }

  @Test
  fun `a failing row still states its reason as an error`() {
    val failed = row(outcome = Outcome.FAILED).copy(failure_reason = "element not found")
    val meta = RunIndexGenerator.stubMeta(failed, report(failed), null)

    assertEquals("element not found", string(meta, "error"))
    assertNull(meta["skipReason"])
  }

  @Test
  fun `a stub is marked as one even when it has nowhere to link`() {
    // The viewer reads `linkOut` to know a session's evidence isn't in the document. Leaving it to
    // `reportUrl` would make an unlinkable row indistinguishable from an ordinary embedded run:
    // its empty payload would be offered for opening and counted as zero tools and zero LLM spend.
    val linked = RunIndexGenerator.stubMeta(row(), report(row()), "https://cdn.example/viewer/index.html")
    val unlinked = RunIndexGenerator.stubMeta(row(zipUrl = null), report(row()), null)
    assertEquals(true, linked["linkOut"]?.jsonPrimitive?.boolean)
    assertEquals(true, unlinked["linkOut"]?.jsonPrimitive?.boolean)
    assertNull(unlinked["reportUrl"])
  }

  @Test
  fun `LLM figures are carried when the row has them and omitted when it does not`() {
    // The index has no calls to count, so an absent figure must stay absent: the viewer renders
    // that as unknown, while a zero would claim an agent-driven run made no LLM calls.
    val withFigures = metaOf(RunIndexGenerator.stubSessions(listOf(report(row())), null)[0].jsonObject)
    assertEquals("12", withFigures["llmCallCount"]?.jsonPrimitive?.content)
    assertEquals("0.42", withFigures["llmCostUsd"]?.jsonPrimitive?.content)
    val without = metaOf(
      RunIndexGenerator.stubSessions(listOf(report(row(llmCalls = null, llmCost = null))), null)[0].jsonObject,
    )
    assertNull(without["llmCallCount"])
    assertNull(without["llmCostUsd"])
  }

  @Test
  fun `every outcome maps onto a badge the viewer sections on`() {
    // A status the viewer doesn't know sections the run under "other", silently hiding a failure.
    assertEquals("passed", RunIndexGenerator.statusLabel(Outcome.PASSED))
    assertEquals("failed", RunIndexGenerator.statusLabel(Outcome.FAILED))
    assertEquals("failed", RunIndexGenerator.statusLabel(Outcome.ERROR))
    assertEquals("failed", RunIndexGenerator.statusLabel(Outcome.TIMEOUT))
    assertEquals("failed", RunIndexGenerator.statusLabel(Outcome.MAX_CALLS_REACHED))
    assertEquals("cancelled", RunIndexGenerator.statusLabel(Outcome.CANCELLED))
    assertEquals("skipped", RunIndexGenerator.statusLabel(Outcome.SKIPPED))
  }

  @Test
  fun `several reports merge into one matrix`() {
    // A build shards a config per device and a nightly runs several configs, so the whole-build
    // matrix only exists once their result files are read together.
    val android = report(row(deviceClassifier = "android-phone"))
    val ios = report(row(deviceClassifier = "ios-iphone", platform = "ios", outcome = Outcome.FAILED))
    val sessions = RunIndexGenerator.stubSessions(listOf(android, ios), "https://cdn.example/v/index.html")
    assertEquals(2, sessions.size)
    assertEquals(
      listOf("android-phone", "ios-iphone"),
      sessions.map { string(metaOf(it.jsonObject), "deviceClassifier") },
    )
    // Same trail identity across both, which is what folds them into one row of two cells.
    assertEquals(setOf("checkout/pay"), sessions.map { string(metaOf(it.jsonObject), "trailId") }.toSet())
  }

  @Test
  fun `a session id that could escape the driver's working directory is neutralized`() {
    // Session ids are opaque text from a results file; the driver probes sessionDir on disk for
    // device logs, events, and video frames, so neither a separator nor a parent hop may survive.
    val escaped = RunIndexGenerator.stubSessionDir("../../../etc/passwd")
    assertFalse(escaped.contains("/"), escaped)
    assertFalse(escaped.contains(".."), escaped)
    assertEquals("session", RunIndexGenerator.stubSessionDir(""))
    // An ordinary session id is left alone — the sanitizer must not rename every stub to mush.
    assertEquals("normal-session_id.1", RunIndexGenerator.stubSessionDir("normal-session_id.1"))
  }
}
