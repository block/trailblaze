package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * The daemon serves a session's files, recordings included, from `/static/<session>/<file>`, and the
 * report points its `<video>` elements there. A browser can only seek a recording it reaches over
 * HTTP when the server answers `Range` requests; otherwise the video stays on its first frame
 * however far the viewer scrubs. These tests pin that contract.
 */
class StaticMediaRangeTest {

  private val recording = ByteArray(4096) { (it % 251).toByte() }

  private fun logsRepoWithRecording(): LogsRepo {
    val logsDir = File.createTempFile("static-media-range-test", "").apply {
      delete()
      mkdirs()
    }
    File(logsDir, "session-a").apply { mkdirs() }.resolve("video.webm").writeBytes(recording)
    return LogsRepo(logsDir)
  }

  @Test
  fun `a byte range of a recording is answered with just those bytes`() = testApplication {
    val logsRepo = logsRepoWithRecording()
    application { logsServerKtorEndpoints(logsRepo) }

    val response = client.get("/static/session-a/video.webm") { header(HttpHeaders.Range, "bytes=1000-1999") }

    assertEquals(HttpStatusCode.PartialContent, response.status)
    assertEquals("bytes 1000-1999/${recording.size}", response.headers[HttpHeaders.ContentRange])
    assertContentEquals(recording.copyOfRange(1000, 2000), response.readRawBytes())
  }

  @Test
  fun `an open-ended range reads from the offset to the end of the recording`() = testApplication {
    val logsRepo = logsRepoWithRecording()
    application { logsServerKtorEndpoints(logsRepo) }

    val response = client.get("/static/session-a/video.webm") { header(HttpHeaders.Range, "bytes=3000-") }

    assertEquals(HttpStatusCode.PartialContent, response.status)
    assertContentEquals(recording.copyOfRange(3000, recording.size), response.readRawBytes())
  }

  @Test
  fun `a plain request still returns the whole recording and says ranges are accepted`() = testApplication {
    val logsRepo = logsRepoWithRecording()
    application { logsServerKtorEndpoints(logsRepo) }

    val response = client.get("/static/session-a/video.webm")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
    assertContentEquals(recording, response.readRawBytes())
  }
}
