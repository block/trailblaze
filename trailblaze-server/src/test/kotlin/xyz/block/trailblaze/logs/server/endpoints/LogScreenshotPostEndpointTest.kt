package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * What a client may leave in a session folder through the screenshot upload. A report links files
 * from that folder and loads some of them as scripts, so only images may be written there.
 */
class LogScreenshotPostEndpointTest {

  private fun logsRepo(name: String): LogsRepo = LogsRepo(
    File.createTempFile(name, "").apply {
      delete()
      mkdirs()
    },
    watchFileSystem = false,
  )

  private val payload = byteArrayOf(1, 2, 3, 4)

  @Test
  fun `an image upload over http is saved under its name`() = testApplication {
    val logsRepo = logsRepo("screenshot-http-accepted")
    application { logsServerKtorEndpoints(logsRepo) }

    listOf("a.png", "b.jpg", "c.jpeg", "d.webp", "E.PNG").forEach { filename ->
      val response = createClient { }.post("/log/screenshot?session=s1&filename=$filename") { setBody(payload) }
      assertEquals(HttpStatusCode.OK, response.status, filename)
      assertContentEquals(payload, logsRepo.getSessionDir(SessionId("s1")).resolve(filename).readBytes(), filename)
    }
  }

  @Test
  fun `a script, page or extensionless upload over http is refused and nothing is written`() = testApplication {
    val logsRepo = logsRepo("screenshot-http-refused")
    application { logsServerKtorEndpoints(logsRepo) }

    listOf(
      "report-events-0123456789abcdef.js",
      "report-events-0123456789abcdef.js.png.js",
      "x.js",
      "x.mjs",
      "x.cjs",
      "x.html",
      "x.htm",
      "x.xhtml",
      "x.svg",
      "x.png.js",
      "png",
    ).forEach { filename ->
      val response = createClient { }.post("/log/screenshot?session=s2&filename=$filename") { setBody(payload) }
      assertEquals(HttpStatusCode.BadRequest, response.status, filename)
      assertFalse(logsRepo.getSessionDir(SessionId("s2")).resolve(filename).exists(), filename)
    }
  }

  @Test
  fun `over the protobuf socket an image is saved and a script is refused`() = testApplication {
    val logsRepo = logsRepo("screenshot-socket")
    application { logsServerKtorEndpoints(logsRepo) }
    val uploadClient = TrailblazeLogServerClient(
      httpClient = createClient { install(WebSockets) },
      baseUrl = "http://localhost",
      useBinaryTransport = true,
    )
    val session = SessionId("socket-session")

    assertTrue(runBlocking { uploadClient.sendScreenshot("home.png", session, payload) })
    assertFalse(runBlocking { uploadClient.sendScreenshot("report-events-0123456789abcdef.js", session, payload) })

    val dir = logsRepo.getSessionDir(session)
    assertContentEquals(payload, dir.resolve("home.png").readBytes())
    assertFalse(dir.resolve("report-events-0123456789abcdef.js").exists())
    uploadClient.close()
  }
}
