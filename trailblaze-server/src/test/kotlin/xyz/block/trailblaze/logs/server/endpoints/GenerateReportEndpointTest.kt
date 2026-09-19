package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * HTTP-contract tests for `/report`: what it serves, which sessions it asks the generator for,
 * and how it fails. The report generator is injected, so nothing here spawns a bun subprocess —
 * the interactive report's own rendering is covered by `RunReportGeneratorTest`.
 */
class GenerateReportEndpointTest {

  /**
   * Hang containment, not a performance budget: `a slow report generation does not block other
   * requests` would hang forever rather than fail if generation ever moved back onto the thread
   * serving the request. This turns that regression into an attributable failure.
   */
  @get:Rule
  val perTestHangGuard: Timeout = Timeout(120, TimeUnit.SECONDS)

  @Test
  fun `serves the generated report as html for the requested session`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    createSession(logsRepo, "session-b")
    val source = FakeReportSource(reportHtml = "<html><body>interactive report</body></html>")
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report?session=session-b")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertTrue("interactive report" in response.bodyAsText(), "expected the generated report body")
    assertEquals(listOf(listOf(SessionId("session-b"))), source.generatedFor)
  }

  @Test
  fun `a session-scoped report is told where the all-runs report lives`() = testApplication {
    // The single-session document has nothing to Compare against; the daemon holds other
    // sessions, so the document gets the unscoped route to link its Compare into.
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    createSession(logsRepo, "session-b")
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?session=session-b").status)

    // The plain route, because this run is inside the window it shows. `limit=all` is the most
    // expensive report the daemon can build, so it is asked for only when it is the only way to
    // reach the run — see the next test.
    assertEquals(listOf<String?>("/report"), source.allRunsUrls.toList())
  }

  @Test
  fun `a run older than the default window is sent to the report that goes back far enough`() = testApplication {
    // The unscoped route shows only the most recent DEFAULT_SESSION_LIMIT sessions, so a link
    // without `limit=all` would drop a run older than that and the viewer would compare two other
    // runs in its place. The oldest run on disk is exactly that case.
    val logsRepo = createTestLogsRepo()
    val limit = GenerateReportEndpoint.DEFAULT_SESSION_LIMIT
    val newestFirst = (0 until limit + 1).map { createSession(logsRepo, "session-%02d".format(it), ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?session=${newestFirst.last().value}").status)
    assertEquals(HttpStatusCode.OK, client.get("/report?session=${newestFirst.first().value}").status)

    // Outside the window, then inside it: the same endpoint answers with the report that will
    // actually hold the run being read.
    assertEquals(listOf<String?>("/report?limit=all", "/report"), source.allRunsUrls.toList())
  }

  @Test
  fun `a logs directory of unreadable sessions is answered as empty rather than as a broken report`() = testApplication {
    // Session directories exist, but not one has a status log the daemon can read, so the
    // all-runs report covers nothing. Answered like an empty logs dir: handing the renderer an
    // empty session list produces a generation-failed page, which reads as a daemon bug.
    val logsRepo = createTestLogsRepo()
    File(logsRepo.logsDir, "session-unreadable").mkdirs()
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report")

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertTrue("Run a trail first" in response.bodyAsText(), "expected the no-runs-yet answer")
    assertEquals(emptyList(), source.generatedFor)
  }

  @Test
  fun `a session directory with no run in it is answered as not found, not as a broken report`() = testApplication {
    // The directory is real, so the traversal allowlist admits it — but it holds no
    // session-status log, so the generator drops the session, finds nothing to render and fails.
    // Answering that as "generation failed" blames the daemon for a run that was never there.
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    File(logsRepo.logsDir, "session-unreadable").mkdirs()
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report?session=session-unreadable")

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertTrue(response.bodyAsText().contains("no run to report on"), response.bodyAsText())
    // And generation was never attempted, so nothing spawned bun to find that out.
    assertEquals(emptyList<List<SessionId>>(), source.generatedFor.toList())
  }

  @Test
  fun `a run whose only company is an unreadable session directory is not sent anywhere to Compare`() = testApplication {
    // Two session dirs on disk, but only one of them is a run the all-runs report can show. The
    // hand-off has to be decided on THAT list, not on a count of directories: pointing Compare at
    // a report with nothing to pair against lands the reader on an index and no comparison.
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    File(logsRepo.logsDir, "session-unreadable").mkdirs()
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?session=session-a").status)

    assertEquals(listOf<String?>(null), source.allRunsUrls.toList())
  }

  @Test
  fun `the only session on the daemon gets no all-runs link, and neither does the all-runs report itself`() = testApplication {
    // One session on disk: the all-runs report would hold this same run and nothing else, so a
    // Compare link there would land on an index with nothing to pair. The unscoped report IS the
    // all-runs report, so it never links to itself.
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?session=session-a").status)
    createSession(logsRepo, "session-b")
    assertEquals(HttpStatusCode.OK, client.get("/report").status)

    assertEquals(listOf<String?>(null, null), source.allRunsUrls.toList())
  }

  @Test
  fun `an unfiltered report is generated for every session`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    createSession(logsRepo, "session-b")
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report").status)

    assertEquals(
      setOf(SessionId("session-a"), SessionId("session-b")),
      source.generatedFor.single().toSet(),
    )
  }

  @Test
  fun `the unfiltered report covers the most recent sessions up to the default limit`() = testApplication {
    val logsRepo = createTestLogsRepo()
    val limit = GenerateReportEndpoint.DEFAULT_SESSION_LIMIT
    val newestFirst = (0 until limit + 5).map { createSession(logsRepo, "session-%02d".format(it), ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report").status)

    assertEquals(newestFirst.take(limit), source.generatedFor.single())
  }

  @Test
  fun `an explicit limit widens the report back to every session`() = testApplication {
    val logsRepo = createTestLogsRepo()
    val limit = GenerateReportEndpoint.DEFAULT_SESSION_LIMIT
    val all = (0 until limit + 5).map { createSession(logsRepo, "session-%02d".format(it), ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?limit=all").status)

    assertEquals(all.toSet(), source.generatedFor.single().toSet())
  }

  @Test
  fun `an explicit numeric limit narrows the report`() = testApplication {
    val logsRepo = createTestLogsRepo()
    val newestFirst = (0 until 5).map { createSession(logsRepo, "session-$it", ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?limit=2").status)

    assertEquals(newestFirst.take(2), source.generatedFor.single())
  }

  @Test
  fun `a truncated report says how many sessions it covers and how to widen it`() = testApplication {
    val logsRepo = createTestLogsRepo()
    repeat(4) { createSession(logsRepo, "session-$it", ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val truncated = client.get("/report?limit=2").bodyAsText()
    assertTrue("2 most recent of 4" in truncated, "expected the scope notice, got: ${truncated.take(400)}")
    assertTrue("/report?limit=all" in truncated, "expected a link to the full report")

    // Nothing was left out of this one, so there is nothing to disclose.
    val complete = client.get("/report?limit=all").bodyAsText()
    assertFalse("most recent of" in complete, "a complete report must not claim to be truncated")
  }

  @Test
  fun `a capped all-runs report is told where the wider one lives, and a complete one is not`() = testApplication {
    // The document cannot work this out for itself: its own address is `/report?limit=2` whether it
    // is served live or copied onto a build server, and only the daemon knows whether asking again
    // would actually turn up more runs. Compare's "the run your link asked for isn't here" notice
    // offers a retry only when this field is set, so setting it on a report that already covers
    // everything would offer a trip back to the same set of runs.
    val logsRepo = createTestLogsRepo()
    repeat(4) { createSession(logsRepo, "session-$it", ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report?limit=2").status)
    assertEquals(HttpStatusCode.OK, client.get("/report?limit=all").status)
    assertEquals(HttpStatusCode.OK, client.get("/report?limit=4").status)
    // `?limit=1` omits runs like any cap, but the document it produces holds one run — so the
    // reader that would pick the field up is the single-run header's Compare button, not the
    // compare retry, and it would offer the daemon's most expensive report with no mention of
    // the cost. A one-run document has no compare view to put a retry in, so nothing is lost.
    assertEquals(HttpStatusCode.OK, client.get("/report?limit=1").status)

    assertEquals(listOf<String?>("/report?limit=all", null, null, null), source.allRunsUrls.toList())
  }

  @Test
  fun `a session-scoped report ignores the limit`() = testApplication {
    val logsRepo = createTestLogsRepo()
    repeat(4) { createSession(logsRepo, "session-$it", ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report?session=session-3&limit=1")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(listOf(listOf(SessionId("session-3"))), source.generatedFor)
    assertFalse("most recent of" in response.bodyAsText(), "a single-session report is not truncated")
  }

  @Test
  fun `reports of different scopes do not overwrite each other`() = testApplication {
    val logsRepo = createTestLogsRepo()
    repeat(4) { createSession(logsRepo, "session-$it", ageMinutes = it) }
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report").status)
    assertEquals(HttpStatusCode.OK, client.get("/report?limit=2").status)
    assertEquals(HttpStatusCode.OK, client.get("/report?session=session-1").status)

    val reports = File(logsRepo.logsDir, "reports").listFiles()?.map { it.name }.orEmpty()
    assertEquals(3, reports.size, "expected one file per scope, found $reports")
  }

  @Test
  fun `an unknown session is not found and nothing is generated`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.NotFound, client.get("/report?session=nope").status)
    assertTrue(source.generatedFor.isEmpty(), "must not generate a report for an unknown session")
  }

  @Test
  fun `an empty logs directory is not found`() = testApplication {
    val logsRepo = createTestLogsRepo()
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report")

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertTrue("No sessions found" in response.bodyAsText(), "expected the run-a-trail-first message")
    assertTrue(source.generatedFor.isEmpty(), "nothing to generate without sessions")
  }

  @Test
  fun `without bun the endpoint explains the cause instead of generating`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val source = FakeReportSource(isAvailable = false)
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report")

    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertTrue("bun" in response.bodyAsText(), "the page must name bun as the missing dependency")
    assertTrue(source.generatedFor.isEmpty(), "must not attempt generation without bun")
  }

  @Test
  fun `a failed generation is reported as a server error`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val source = FakeReportSource(reportHtml = null)
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    val response = client.get("/report")

    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertEquals(1, source.generatedFor.size, "generation should have been attempted")
  }

  /**
   * The property that keeps a daemon usable: report generation shells out to bun and can take
   * a while, so it must not run on the thread serving the request. While one generation is
   * parked mid-flight, every other route still answers.
   */
  @Test
  fun `a slow report generation does not block other requests`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val generationStarted = CompletableDeferred<Unit>()
    val finishGeneration = CompletableDeferred<Unit>()
    val source = FakeReportSource(
      onGenerate = {
        generationStarted.complete(Unit)
        runBlocking { finishGeneration.await() }
      },
    )
    application {
      routing {
        GenerateReportEndpoint.register(this, logsRepo, source)
        get("/probe") { call.respondText("ok") }
      }
    }

    coroutineScope {
      val report = async { client.get("/report") }
      generationStarted.await()

      assertEquals(HttpStatusCode.OK, client.get("/probe").status, "other routes must stay served")

      finishGeneration.complete(Unit)
      assertEquals(HttpStatusCode.OK, report.await().status)
    }
  }

  @Test
  fun `the served report is kept under the logs directory`() = testApplication {
    val logsRepo = createTestLogsRepo()
    createSession(logsRepo, "session-a")
    val source = FakeReportSource()
    application { routing { GenerateReportEndpoint.register(this, logsRepo, source) } }

    assertEquals(HttpStatusCode.OK, client.get("/report").status)
    assertEquals(HttpStatusCode.OK, client.get("/report").status)

    // Repeated requests reuse one output file rather than growing logs/reports/ per request.
    val reports = File(logsRepo.logsDir, "reports").listFiles()?.map { it.name }.orEmpty()
    assertEquals(1, reports.size, "expected a single report file, found $reports")
    assertNull(source.leakedFiles.firstOrNull { it.exists() }, "the generator's output should be moved, not copied")
  }

  /**
   * The base URL `/report` tells the generator to link images at must be one the daemon actually
   * serves. The two are declared independently — the constant here, `staticFiles("/static", …)` in
   * `ServerEndpoints` — so nothing but this stops them drifting into a report whose every
   * screenshot 404s. Resolves a real file through the real route rather than comparing literals.
   */
  @Test
  fun `the image base url the report links against is served by the daemon`() = testApplication {
    val logsRepo = createTestLogsRepo()
    val sessionId = createSession(logsRepo, "linked-images-session")
    File(logsRepo.getSessionDir(sessionId), "step.png").writeBytes(ONE_BY_ONE_PNG)
    application { logsServerKtorEndpoints(logsRepo) }

    val url = "${GenerateReportEndpoint.STATIC_IMAGE_BASE_URL}${sessionId.value}/step.png"
    val response = client.get(url)

    assertEquals(HttpStatusCode.OK, response.status, "expected $url to serve the screenshot")
    assertEquals(ONE_BY_ONE_PNG.size, response.readRawBytes().size)
  }

  private fun createTestLogsRepo(): LogsRepo {
    val tempDir = File.createTempFile("generate-report-endpoint-test", "").apply {
      delete()
      mkdirs()
    }
    return LogsRepo(tempDir)
  }

  /**
   * Seeds a real session: a session-status log on disk, which is what makes `LogsRepo` (and the
   * report renderer) treat the directory as a session at all. [ageMinutes] backdates the status
   * log so the expected "most recent first" order is pinned by recorded time, not by how fast
   * the test wrote the files.
   */
  private fun createSession(logsRepo: LogsRepo, sessionId: String, ageMinutes: Int = 0): SessionId {
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1_000),
        session = SessionId(sessionId),
        timestamp = Clock.System.now().minus(ageMinutes.minutes),
      ),
    )
    return SessionId(sessionId)
  }

  /**
   * Stands in for the bun-backed generator. Records what it was asked to generate and hands back
   * a fresh file each call (the endpoint moves its output, so the file is consumed).
   *
   * The file is created under the logs dir's `reports/`, where the real generator writes — the
   * endpoint's move is then the same same-directory rename it performs in production, rather
   * than a system-temp-to-logs-dir move that fails on a host whose temp dir is another volume.
   */
  private class FakeReportSource(
    override val isAvailable: Boolean = true,
    private val reportHtml: String? = "<html><body>report</body></html>",
    private val onGenerate: () -> Unit = {},
  ) : GenerateReportEndpoint.InteractiveReportSource {

    val generatedFor: MutableList<List<SessionId>> = CopyOnWriteArrayList()
    /** The all-runs URL each generation was handed, in call order (null = none). */
    val allRunsUrls: MutableList<String?> = CopyOnWriteArrayList()
    val leakedFiles: MutableList<File> = CopyOnWriteArrayList()

    override fun generate(logsRepo: LogsRepo, sessionIds: List<SessionId>, allRunsUrl: String?): File? {
      generatedFor.add(sessionIds)
      allRunsUrls.add(allRunsUrl)
      onGenerate()
      val html = reportHtml ?: return null
      val reportsDir = File(logsRepo.logsDir, "reports").apply { mkdirs() }
      return File.createTempFile("fake-run-report", ".html", reportsDir)
        .apply { writeText(html) }
        .also { leakedFiles.add(it) }
    }
  }

  private companion object {
    /** A 1×1 transparent PNG — a real on-disk screenshot for the `/static` route to serve. */
    val ONE_BY_ONE_PNG: ByteArray = java.util.Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )
  }
}
