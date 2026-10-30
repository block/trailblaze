package xyz.block.trailblaze.report.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain

/** Pins the [SessionEventsReader] read path against the [SessionEvents] file contract. */
class SessionEventsReaderTest {

  private fun tempDir(): File = Files.createTempDirectory("events-reader").toFile()

  private fun obj(key: String, value: String): JsonObject =
    buildJsonObject { put(key, JsonPrimitive(value)) }

  @Test
  fun `reads envelope streams and derives the name from the file name`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      sink.append("analytics", 200L, obj("name", "scroll"))
      sink.append("analytics", 100L, obj("name", "tap"))
    }

    val streams = SessionEventsReader().read(dir)
    assertEquals(1, streams.size)
    val s = streams.first()
    assertEquals("analytics", s.name)
    assertEquals(2, s.count)
    assertEquals(listOf(200L, 100L), s.events.map { it.timeMs }, "lines kept in arrival order")
    assertEquals("scroll", (s.events.first().data as JsonObject)["name"]?.jsonPrimitive?.content)
  }

  @Test
  fun `bare rich-schema line orders on timestampMs and exposes the whole object as data`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      // A NetworkEvent-shaped line: no envelope, carries timestampMs.
      sink.appendRaw("network", """{"id":"r1","timestampMs":1234,"phase":"REQUEST_START"}""")
    }

    val s = SessionEventsReader().read(dir).single()
    assertEquals(1234L, s.events.first().timeMs)
    assertEquals("r1", (s.events.first().data as JsonObject)["id"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a session-root network capture reads as the network stream`() {
    val dir = tempDir()
    File(dir, "network.ndjson").writeText("""{"id":"r1","timestampMs":1234,"phase":"REQUEST_START"}""" + "\n")
    File(dir, "network.seller.ndjson").writeText("""{"id":"s1","timestampMs":99,"phase":"REQUEST_START"}""" + "\n")
    File(dir, "visible-strings.ndjson").writeText("""{"timestampMs":1}""" + "\n")

    val streams = SessionEventsReader().read(dir)
    assertEquals(listOf("network", "network.seller"), streams.map { it.name })
    assertEquals(1234L, streams.first().events.single().timeMs)
  }

  @Test
  fun `an events stream wins over the session-root capture of the same name`() {
    val dir = tempDir()
    FileEventSink(dir).use { it.appendRaw("network", """{"id":"e1","timestampMs":1,"phase":"REQUEST_START"}""") }
    File(dir, "network.ndjson").writeText("""{"id":"r1","timestampMs":2,"phase":"REQUEST_START"}""" + "\n")

    val s = SessionEventsReader().read(dir).single()
    assertEquals("e1", (s.events.single().data as JsonObject)["id"]?.jsonPrimitive?.content)
  }

  @Test
  fun `bare rich-schema line with its own data field keeps all fields`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      // A bare line (no envelope timeMs) that happens to carry a top-level `data` field. It must NOT
      // be unwrapped to just `data` — id/phase/timestampMs must survive.
      sink.appendRaw(
        "rich",
        """{"id":"r1","timestampMs":99,"phase":"DONE","data":{"k":"v"}}""",
      )
    }

    val payload = SessionEventsReader().read(dir).single().events.single()
    assertEquals(99L, payload.timeMs)
    val obj = payload.data as JsonObject
    assertEquals("r1", obj["id"]?.jsonPrimitive?.content, "rich-schema fields must be preserved")
    assertEquals("DONE", obj["phase"]?.jsonPrimitive?.content)
    assertTrue(obj.containsKey("data"), "the nested data field itself is kept, not hoisted")
  }

  @Test
  fun `byte cap trips on a malformed prefix even before any valid event`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    // One valid line, then a large run of malformed lines. The malformed lines count toward the
    // scanned-byte cap, so the reader stops instead of scanning the whole file every poll.
    val sb = StringBuilder("""{"timeMs":1,"data":{"k":"v"}}""").append('\n')
    repeat(500) { sb.append("garbage-not-json-").append(it).append('\n') }
    File(eventsDir, SessionEvents.fileName("noisy")).writeText(sb.toString())

    val s = SessionEventsReader(maxBytesPerStream = 200).read(dir).single()
    assertEquals(1, s.count, "only the leading valid event is kept")
    assertTrue(s.truncated, "scanned-byte cap must trip on the malformed prefix")
  }

  @Test
  fun `an over-cap single line is skipped, not retained, and flags truncated`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    // A small valid line, then a line far larger than the cap. The oversized line must never be
    // decoded/retained (it can't OOM the poll); the prior valid event is kept and truncated is set.
    val huge = "x".repeat(5_000)
    val text = """{"timeMs":1,"data":{"k":"v"}}""" + "\n" + """{"timeMs":2,"data":{"big":"$huge"}}""" + "\n"
    File(eventsDir, SessionEvents.fileName("big")).writeText(text)

    val s = SessionEventsReader(maxBytesPerStream = 100).read(dir).single()
    assertEquals(1, s.count, "the over-cap line is dropped; only the small valid event survives")
    assertTrue(s.truncated)
  }

  @Test
  fun `caps the number of streams read per call`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      repeat(5) { i -> sink.append("stream$i", i.toLong(), obj("k", "v")) }
    }

    val streams = SessionEventsReader(maxStreams = 2).read(dir)
    assertEquals(2, streams.size, "only the first maxStreams files (sorted by name) are read")
  }

  @Test
  fun `junk files do not consume the stream budget`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    // Junk files that sort BEFORE the valid streams. They must be filtered out before maxStreams,
    // so the valid streams are not starved.
    repeat(5) { i -> File(eventsDir, "00$i-junk.txt").writeText("not an event file") }
    File(eventsDir, SessionEvents.fileName("aaa")).writeText("""{"timeMs":1,"data":{}}""" + "\n")
    File(eventsDir, SessionEvents.fileName("bbb")).writeText("""{"timeMs":2,"data":{}}""" + "\n")

    val streams = SessionEventsReader(maxStreams = 2).read(dir)
    assertEquals(setOf("aaa", "bbb"), streams.map { it.name }.toSet(), "junk must not crowd out valid streams")
  }

  @Test
  fun `an oversized first line hides the stream rather than retaining it`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    // First (and only) line exceeds the byte cap -> overflow -> stream hidden (empty -> null).
    File(eventsDir, SessionEvents.fileName("huge"))
      .writeText("""{"timeMs":1,"data":{"x":"${"y".repeat(5_000)}"}}""" + "\n")

    assertTrue(SessionEventsReader(maxBytesPerStream = 100).read(dir).isEmpty())
  }

  @Test
  fun `byte budget counts UTF-8 bytes, not characters`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    // A line of multibyte chars: few characters but many UTF-8 bytes. It must trip the BYTE cap
    // (a char-based budget would have let it through).
    val euros = "€".repeat(20) // 20 chars, 60 UTF-8 bytes
    File(eventsDir, SessionEvents.fileName("multi"))
      .writeText("""{"timeMs":1,"data":{"s":"$euros"}}""" + "\n")

    assertTrue(SessionEventsReader(maxBytesPerStream = 30).read(dir).isEmpty(), "60-byte line must exceed a 30-byte cap")
  }

  @Test
  fun `caps a huge stream and flags truncated`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      repeat(10) { i -> sink.append("big", i.toLong(), obj("v", "x")) }
    }

    val s = SessionEventsReader(maxEventsPerStream = 3).read(dir).single()
    assertEquals(3, s.count)
    assertTrue(s.truncated)
  }

  @Test
  fun `ignores non-events files and returns empty when folder absent`() {
    val dir = tempDir()
    assertTrue(SessionEventsReader().read(dir).isEmpty(), "no events dir -> empty")
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    File(eventsDir, "notes.txt").writeText("hello")
    assertTrue(SessionEventsReader().read(dir).isEmpty())
    assertNull(SessionEvents.parseFileName("notes.txt"))
  }

  @Test
  fun `legacy styled file names resolve to the same stream name`() {
    val dir = tempDir()
    val eventsDir = File(dir, SessionEvents.DIR_NAME).apply { mkdirs() }
    File(eventsDir, "com.x.plugin.network.json.ndjson").writeText("""{"timeMs":1,"data":{}}""" + "\n")

    val s = SessionEventsReader().read(dir).single()
    assertEquals("com.x.plugin.network", s.name)
  }

  @Test
  fun `device-clock records land on the host timeline, so buffered app events sort first`() {
    // The device runs 5s ahead. The app replays events buffered at device 6100/6200 after the host
    // logged a tool call at 1500; on the host timeline they happened at 1100/1200.
    val dir = tempDir()
    DeviceClockSyncFile.appendSample(dir, "emulator-5554", DeviceClockSample(1_000, 1_010, 6_005))
    FileEventSink(dir).use { sink ->
      sink.append("tools", 1_500L, obj("tool", "tap"))
      sink.append("app-events", 6_100L, obj("seq", "1"), clock = TrailblazeClockDomain.DEVICE)
      sink.append("app-events", 6_200L, obj("seq", "2"), clock = TrailblazeClockDomain.DEVICE)
    }

    val merged =
      SessionEventsReader().read(dir).flatMap { stream -> stream.events.map { stream.name to it } }
        .sortedBy { it.second.timeMs }
    assertEquals(
      listOf("app-events" to 1_100L, "app-events" to 1_200L, "tools" to 1_500L),
      merged.map { it.first to it.second.timeMs },
    )
    assertTrue(merged.all { it.second.clock == null }, "every record is now on the host clock")
  }

  @Test
  fun `a device-clock record keeps its raw stamp, marked, when the session measured nothing`() {
    val dir = tempDir()
    FileEventSink(dir).use { sink ->
      sink.append("app-events", 6_100L, obj("seq", "1"), clock = TrailblazeClockDomain.DEVICE)
    }
    val event = SessionEventsReader().read(dir).single().events.single()
    assertEquals(6_100L to TrailblazeClockDomain.DEVICE, event.timeMs to event.clock)
  }
}
