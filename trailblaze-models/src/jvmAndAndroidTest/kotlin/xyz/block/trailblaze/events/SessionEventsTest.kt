package xyz.block.trailblaze.events

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Pins the [SessionEvents] filename contract and the [FileEventSink] write behavior. */
class SessionEventsTest {

  @Test
  fun `fileName round-trips through parseFileName`() {
    val fileName = SessionEvents.fileName("network", EventStyle.NETWORK)
    assertEquals("network.network.ndjson", fileName)
    assertEquals(EventStreamId("network", "network"), SessionEvents.parseFileName(fileName))
  }

  @Test
  fun `dotted name round-trips because style is the dot-free final segment`() {
    val fileName = SessionEvents.fileName("com.x.plugin.network", EventStyle.JSON)
    assertEquals("com.x.plugin.network.json.ndjson", fileName)
    val id = SessionEvents.parseFileName(fileName)
    assertEquals("com.x.plugin.network", id?.name)
    assertEquals("json", id?.style)
  }

  @Test
  fun `parseFileName rejects non-events files`() {
    assertNull(SessionEvents.parseFileName("network.ndjson")) // no style segment
    assertNull(SessionEvents.parseFileName("video.mp4"))
    assertNull(SessionEvents.parseFileName("notes.txt"))
  }

  @Test
  fun `append writes one envelope line per record under events dir`() {
    val sessionDir = createTempDir()
    val sink = FileEventSink(sessionDir)
    sink.append("analytics", EventStyle.ANALYTICS, 1000L, buildJsonObject { put("name", JsonPrimitive("tap")) })
    sink.append("analytics", EventStyle.ANALYTICS, 2000L, buildJsonObject { put("name", JsonPrimitive("scroll")) })
    sink.close()

    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/analytics.analytics.ndjson")
    assertTrue(file.isFile, "expected $file to exist")
    val lines = file.readLines().filter { it.isNotBlank() }
    assertEquals(2, lines.size)
    val first = Json.decodeFromString(SessionEvent.serializer(), lines[0])
    assertEquals(1000L, first.timeMs)
    assertEquals("tap", (first.data as JsonObject)["name"]?.let { (it as JsonPrimitive).content })
  }

  @Test
  fun `appendRaw writes the line verbatim for rich schemas`() {
    val sessionDir = createTempDir()
    val sink = FileEventSink(sessionDir)
    val raw = """{"id":"r1","timestampMs":1234,"phase":"REQUEST_START"}"""
    sink.appendRaw("network", EventStyle.NETWORK, raw)
    sink.close()

    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/network.network.ndjson")
    assertEquals(raw, file.readLines().first())
  }

  @Test
  fun `appendRaw rejects a line with an embedded newline`() {
    val sessionDir = createTempDir()
    val sink = FileEventSink(sessionDir)
    // A newline would split one record into multiple NDJSON lines; such input is dropped, not written.
    sink.appendRaw("net", EventStyle.NETWORK, "{\"timestampMs\":1}\n{\"injected\":true}")
    sink.appendRaw("net", EventStyle.NETWORK, """{"timestampMs":2}""")
    sink.close()

    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/net.network.ndjson")
    val lines = file.readLines().filter { it.isNotBlank() }
    assertEquals(1, lines.size, "only the clean single-record line is written")
    assertTrue(lines.first().contains("\"timestampMs\":2"))
  }

  @Test
  fun `appendRaw also rejects a carriage return`() {
    val sessionDir = createTempDir()
    val sink = FileEventSink(sessionDir)
    sink.appendRaw("net", EventStyle.NETWORK, "{\"timestampMs\":1}\r{\"injected\":true}")
    sink.close()

    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/net.network.ndjson")
    assertFalse(file.exists() && file.readLines().any { it.isNotBlank() }, "a line with \\r is dropped")
  }

  @Test
  fun `writes after close are dropped`() {
    val sessionDir = createTempDir()
    val sink = FileEventSink(sessionDir)
    sink.append("x", EventStyle.JSON, 1L, JsonPrimitive("a"))
    sink.close()
    sink.append("x", EventStyle.JSON, 2L, JsonPrimitive("b"))

    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/x.json.ndjson")
    assertEquals(1, file.readLines().filter { it.isNotBlank() }.size)
  }

  private fun createTempDir(): File =
    File.createTempFile("session-events-test", "").let {
      it.delete()
      it.mkdirs()
      it
    }
}
