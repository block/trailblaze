package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeEndpointTest {

  private fun createTestLogsRepo(): LogsRepo {
    val tempDir = File.createTempFile("home-endpoint-test", "").apply {
      delete()
      mkdirs()
    }
    return LogsRepo(tempDir)
  }

  @Test
  fun `home page lists every session with a link to its per-session report`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    postStatusLog(this, "passing-session", SessionStatus.Ended.Succeeded(durationMs = 1_500))
    postStatusLog(this, "failing-session", SessionStatus.Ended.Failed(durationMs = 2_500, exceptionMessage = "boom"))

    val response = client.get("/")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue("passing-session" in body, "expected passing-session row")
    assertTrue("failing-session" in body, "expected failing-session row")
    assertTrue("/report?session=passing-session" in body, "expected per-session link for passing session")
    assertTrue("/report?session=failing-session" in body, "expected per-session link for failing session")
    assertTrue("Passed" in body, "expected status pill for passing session")
    assertTrue("Failed" in body, "expected status pill for failing session")
    assertTrue("/report\"" in body || "href=\"/report\"" in body, "expected unfiltered /report fallback link")
  }

  @Test
  fun `home page renders a live storyboard link for every session and no dead label`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    postStatusLog(this, "passing-session", SessionStatus.Ended.Succeeded(durationMs = 1_500))

    val body = client.get("/").bodyAsText()
    assertTrue(
      "/storyboard?session=passing-session" in body,
      "expected a working per-session storyboard link",
    )
    // The old behavior rendered a permanently grayed-out `Storyboard —` span with no
    // href when no pre-generated file existed. Regression guard for issue #172.
    assertTrue("Storyboard —" !in body, "storyboard should never render as a dead label")
  }

  @Test
  fun `storyboard endpoint returns 404 when filtered to an unknown session`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    postStatusLog(this, "real-session", SessionStatus.Ended.Succeeded(durationMs = 100))

    val response = client.get("/storyboard?session=does-not-exist")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `storyboard endpoint returns 400 when the session parameter is missing`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    val response = client.get("/storyboard")
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `storyboard endpoint reports unprocessable for a session with no screenshot-bearing steps`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    // A status-only session has no executed actions/snapshots, so there's nothing to
    // tile into a storyboard — the builder rejects it with an actionable message.
    postStatusLog(this, "status-only-session", SessionStatus.Ended.Succeeded(durationMs = 100))

    val response = client.get("/storyboard?session=status-only-session")
    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    val body = response.bodyAsText()
    assertTrue("No screenshot-bearing logs" in body)
    // The message must name the session it's about (it's surfaced verbatim to the user).
    assertTrue("status-only-session" in body, "expected the session id in the error message")
  }

  @Test
  fun `storyboard endpoint renders a session as html with static image urls, not base64`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    // Seed a session with one screenshot-bearing action, and drop the referenced image
    // on disk where /static serves it from (<logsDir>/<session>/<file>).
    postAgentDriverLog(this, "with-shots", "step.png")
    File(logsRepo.getSessionDir(SessionId("with-shots")), "step.png").writeBytes(ONE_BY_ONE_PNG)

    val response = client.get("/storyboard?session=with-shots")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(
      response.headers[HttpHeaders.ContentType]?.startsWith("text/html") == true,
      "expected an HTML content type",
    )
    val body = response.bodyAsText()
    assertTrue("/static/with-shots/step.png" in body, "expected a relative /static image URL")
    assertTrue("data:image" !in body, "server-served storyboard should not base64-inline screenshots")

    // The yaml=false branch should still render (no tool log here, so it just falls back
    // to the synthesized label — we only assert it serves successfully).
    val noYaml = client.get("/storyboard?session=with-shots&yaml=false")
    assertEquals(HttpStatusCode.OK, noYaml.status)
  }

  @Test
  fun `home page renders an empty state when there are no sessions`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    val response = client.get("/")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue("No sessions yet" in response.bodyAsText())
  }

  @Test
  fun `report endpoint returns 404 when filtered to an unknown session`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application { logsServerKtorEndpoints(logsRepo) }

    postStatusLog(this, "real-session", SessionStatus.Ended.Succeeded(durationMs = 100))

    val response = client.get("/report?session=does-not-exist")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  private suspend fun postStatusLog(
    testApp: io.ktor.server.testing.ApplicationTestBuilder,
    sessionId: String,
    status: SessionStatus,
  ) {
    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = status,
      session = SessionId(sessionId),
      timestamp = Clock.System.now(),
    )
    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    val response = testApp.client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }
    assertEquals(HttpStatusCode.OK, response.status, "seeding session log failed")
  }

  private suspend fun postAgentDriverLog(
    testApp: io.ktor.server.testing.ApplicationTestBuilder,
    sessionId: String,
    screenshotFile: String,
  ) {
    val log = TrailblazeLog.AgentDriverLog(
      viewHierarchy = null,
      screenshotFile = screenshotFile,
      action = AgentDriverAction.TapPoint(x = 10, y = 20),
      durationMs = 0L,
      session = SessionId(sessionId),
      timestamp = Clock.System.now(),
      deviceHeight = 874,
      deviceWidth = 402,
      traceId = null,
    )
    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    val response = testApp.client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }
    assertEquals(HttpStatusCode.OK, response.status, "seeding agent-driver log failed")
  }

  private companion object {
    /** A 1×1 transparent PNG — enough for the storyboard builder to resolve and reference
     *  a real on-disk screenshot file without caring about its pixels. */
    val ONE_BY_ONE_PNG: ByteArray = java.util.Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )
  }
}
