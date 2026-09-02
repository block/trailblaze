package xyz.block.trailblaze.host.golden

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A snapshot baseline is fetched from wherever the reference points — a CI artifact store, any
 * URL, any zip on disk — and then written to the disk of the machine running the comparison. Both
 * of those writes need a ceiling: without one, a reference that turns out to serve something
 * enormous, or an archive that expands far past its compressed size, fills that disk before
 * anything gets compared.
 */
class SnapshotBaselineBudgetTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** A zip holding [entries] files of [bytesEach] compressible bytes apiece. */
  private fun zipOf(name: String, entries: Int, bytesEach: Int): File {
    val zipFile = File(tmp.newFolder("zips-$name"), "$name.zip")
    val payload = ByteArray(bytesEach) // zeros: compresses to almost nothing, expands to bytesEach
    ZipOutputStream(zipFile.outputStream()).use { out ->
      repeat(entries) { index ->
        out.putNextEntry(ZipEntry("session/file$index.bin"))
        out.write(payload)
        out.closeEntry()
      }
    }
    return zipFile
  }

  /** Serves [body] with [status] on a loopback port, for the duration of [block]. */
  private fun serving(status: Int, body: ByteArray, block: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/baseline.zip") { exchange ->
      exchange.sendResponseHeaders(status, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      block("http://127.0.0.1:${server.address.port}/baseline.zip")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `extraction stops at the byte budget instead of filling the disk`() {
    // 8 entries of 64 KB each expand to 512 KB from a zip of a few hundred bytes.
    val zipFile = zipOf("bomb", entries = 8, bytesEach = 64 * 1024)
    val destination = tmp.newFolder("extract-bomb")

    val error = assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.extractZip(zipFile, destination, maxBytes = 100 * 1024)
    }

    assertTrue(error.message.orEmpty().contains("larger than"), "message should name the limit, was: ${error.message}")
  }

  @Test
  fun `the byte budget is cumulative, not per entry`() {
    // No single entry is over the budget; together they are. A per-entry check would let this pass.
    val zipFile = zipOf("many-small", entries = 40, bytesEach = 16 * 1024)
    val destination = tmp.newFolder("extract-many-small")

    assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.extractZip(zipFile, destination, maxBytes = 100 * 1024)
    }
  }

  @Test
  fun `an archive within the budget still extracts`() {
    val zipFile = zipOf("normal", entries = 3, bytesEach = 1024)
    val destination = tmp.newFolder("extract-normal")

    SnapshotBaselineSource.extractZip(zipFile, destination, maxBytes = 100 * 1024)

    assertEquals(3, File(destination, "session").listFiles().orEmpty().size)
  }

  @Test
  fun `an archive of more entries than the cap is refused`() {
    val zipFile = zipOf("swarm", entries = 20, bytesEach = 1)
    val destination = tmp.newFolder("extract-swarm")

    val error = assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.extractZip(zipFile, destination, maxEntries = 5)
    }

    assertTrue(error.message.orEmpty().contains("more than 5 entries"), "was: ${error.message}")
  }

  @Test
  fun `a download past the budget is refused and leaves nothing behind`() {
    val destination = File(tmp.newFolder("download-big"), "baseline-session.zip")

    serving(200, ByteArray(200 * 1024)) { url ->
      assertFailsWith<IllegalStateException> {
        SnapshotBaselineSource.download(url, destination, maxBytes = 100 * 1024)
      }
    }

    assertFalse(destination.exists(), "a refused download must not leave a partial file to be read as a baseline")
  }

  @Test
  fun `an error response is rejected without its body becoming the baseline`() {
    val destination = File(tmp.newFolder("download-404"), "baseline-session.zip")

    serving(404, "not found".toByteArray()) { url ->
      val error = assertFailsWith<IllegalStateException> {
        SnapshotBaselineSource.download(url, destination)
      }
      assertTrue(error.message.orEmpty().contains("HTTP 404"), "was: ${error.message}")
    }

    assertFalse(destination.exists())
  }

  @Test
  fun `a download within the budget lands intact`() {
    val destination = File(tmp.newFolder("download-ok"), "baseline-session.zip")
    val body = ByteArray(4096) { (it % 251).toByte() }

    serving(200, body) { url ->
      SnapshotBaselineSource.download(url, destination, maxBytes = 100 * 1024)
    }

    assertTrue(destination.readBytes().contentEquals(body))
  }

  // `HttpRequest.timeout` bounds the wait for response HEADERS only. A server that answers 200 and
  // then stops sending leaves a streaming read blocked with nothing to end it — the run neither
  // fails nor finishes, which is worse than either.
  @Test
  fun `a server that answers and then stalls fails instead of hanging`() {
    val destination = File(tmp.newFolder("download-stall"), "baseline-session.zip")
    val release = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/baseline.zip") { exchange ->
      // Promise a megabyte, send 16 bytes, then go quiet.
      exchange.sendResponseHeaders(200, 1024L * 1024)
      exchange.responseBody.write(ByteArray(16))
      exchange.responseBody.flush()
      release.await(30, TimeUnit.SECONDS)
      exchange.close()
    }
    server.start()
    try {
      val url = "http://127.0.0.1:${server.address.port}/baseline.zip"
      val error = assertFailsWith<IllegalStateException> {
        SnapshotBaselineSource.download(url, destination, bodyTimeout = Duration.ofMillis(500))
      }
      assertTrue(error.message.orEmpty().contains("stalled"), "was: ${error.message}")
    } finally {
      release.countDown()
      server.stop(0)
    }

    assertFalse(destination.exists(), "a stalled download must not leave a partial file behind")
  }

  // A directory reference skips extraction entirely, and the archive budget is a total rather than
  // a per-entry cap — so nothing else stands between one hostile log file and a `readText()` of it.
  @Test
  fun `a session log past the per-entry cap is skipped rather than read`() {
    val sessionDir = tmp.newFolder("session")
    File(sessionDir, "shot.png").writeBytes(ByteArray(8))
    val snapshotJson = """
      {
        "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSnapshotLog",
        "displayName": "home-tab",
        "screenshotFile": "shot.png",
        "timestamp": "2026-01-01T00:00:01Z"
      }
    """.trimIndent()
    File(sessionDir, "small.json").writeText(snapshotJson)
    // Same record, padded past the cap with an unread field.
    File(sessionDir, "big.json").writeText(
      snapshotJson.dropLast(1) + ",\n  \"pad\": \"${"x".repeat(4096)}\"\n}",
    )

    val entries = SnapshotBaselineSource.readSnapshotEntries(sessionDir, maxEntryBytes = 1024)

    assertEquals(1, entries.getValue("home-tab").size, "only the entry within the cap may be read")
  }
}
