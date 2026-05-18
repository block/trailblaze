package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import org.junit.Test
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
}
