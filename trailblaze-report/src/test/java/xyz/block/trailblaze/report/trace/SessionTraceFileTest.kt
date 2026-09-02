package xyz.block.trailblaze.report.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTraceFileTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val traceFile: File get() = File(tempFolder.root, SessionTraceFile.FILE_NAME)

  private fun event(
    name: String,
    ts: Long,
    sid: String? = null,
    trid: String? = null,
    dur: Long = 100,
  ): JsonObject = buildJsonObject {
    put("name", name)
    put("cat", "app")
    put("ph", "X")
    put("ts", ts)
    put("dur", dur)
    put("pid", 1)
    put("tid", 2)
    if (sid != null) put("sid", sid)
    if (trid != null) put("trid", trid)
  }

  private fun write(vararg events: JsonObject) {
    traceFile.writeText(Json.encodeToString(JsonArray(events.toList())))
  }

  private fun json(vararg events: JsonObject): String = Json.encodeToString(JsonArray(events.toList()))

  private fun names(): List<String> =
    SessionTraceFile.read(traceFile).map { it.getValue("name").jsonPrimitive.content }

  @Test
  fun `a second writer's events are added to the first writer's, not written over them`() {
    // The whole point. A run is recorded by the host and by the device, both into this one file, and
    // whichever finished last used to be the only one you could see.
    write(event("host-tool", ts = 1_000))

    SessionTraceFile.merge(traceFile, json(event("device-capture", ts = 2_000)))

    assertEquals(listOf("host-tool", "device-capture"), names())
  }

  @Test
  fun `a device trace and a host trace stay one trace`() {
    // Both halves share the trace id, which is what lets them be assembled at all — so the merged
    // file must not split them apart.
    val trid = "0123456789abcdef0123456789abcdef"
    write(event("host-tool", ts = 1_000, sid = "aaaaaaaaaaaaaaaa", trid = trid))

    SessionTraceFile.merge(
      traceFile,
      json(event("captureMergedScreenTrees", ts = 1_500, sid = "bbbbbbbbbbbbbbbb", trid = trid)),
    )

    val trids = SessionTraceFile.read(traceFile).map { it.getValue("trid").jsonPrimitive.content }
    assertEquals(listOf(trid, trid), trids)
  }

  @Test
  fun `a batch that arrives twice is recorded once`() {
    // A device retries an upload, or posts a trace the host already had. Without this, every retry
    // doubles the timeline and every span appears to have happened twice.
    val batch = json(event("tool", ts = 1_000), event("llm", ts = 2_000))

    SessionTraceFile.merge(traceFile, batch)
    SessionTraceFile.merge(traceFile, batch)

    assertEquals(listOf("tool", "llm"), names())
  }

  @Test
  fun `a batch that overlaps an earlier one keeps only what is new`() {
    SessionTraceFile.merge(traceFile, json(event("a", ts = 1_000), event("b", ts = 2_000)))

    SessionTraceFile.merge(traceFile, json(event("b", ts = 2_000), event("c", ts = 3_000)))

    assertEquals(listOf("a", "b", "c"), names())
  }

  @Test
  fun `two spans with the same name at different times are both kept`() {
    // Deduplication is by whole event, not by name: a tool called twice is two spans, and collapsing
    // them would hide half the run's cost.
    SessionTraceFile.merge(traceFile, json(event("tapOn", ts = 1_000)))
    SessionTraceFile.merge(traceFile, json(event("tapOn", ts = 5_000)))

    assertEquals(listOf("tapOn", "tapOn"), names())
  }

  @Test
  fun `merged events come out ordered by start time`() {
    // A viewer reads the file in order. Events arriving out of order is the normal case, because the
    // device's batch lands after the host's whether or not it happened after it.
    write(event("late-host", ts = 9_000))

    SessionTraceFile.merge(traceFile, json(event("early-device", ts = 100), event("mid-device", ts = 5_000)))

    assertEquals(listOf("early-device", "mid-device", "late-host"), names())
  }

  @Test
  fun `an event with no timestamp sorts last rather than first`() {
    // It cannot be placed, and placing it at the front would make the run look like it started there.
    write(event("real", ts = 1_000))

    SessionTraceFile.merge(traceFile, json(buildJsonObject { put("name", "no-ts") }))

    assertEquals(listOf("real", "no-ts"), names())
  }

  @Test
  fun `the first writer creates the file`() {
    SessionTraceFile.merge(traceFile, json(event("first", ts = 1)))

    assertTrue(traceFile.exists())
    assertEquals(listOf("first"), names())
  }

  @Test
  fun `a missing parent directory is created`() {
    val nested = File(tempFolder.root, "session-abc/${SessionTraceFile.FILE_NAME}")

    SessionTraceFile.merge(nested, json(event("first", ts = 1)))

    assertTrue(nested.exists())
  }

  @Test
  fun `an unparseable existing file is replaced rather than blocking the write`() {
    // A damaged file must not mean the session can never record again.
    traceFile.writeText("{ this is not a trace")

    SessionTraceFile.merge(traceFile, json(event("recovered", ts = 1)))

    assertEquals(listOf("recovered"), names())
  }

  @Test
  fun `an existing file holding something other than an array is treated as empty`() {
    traceFile.writeText("""{"traceEvents":[]}""")

    SessionTraceFile.merge(traceFile, json(event("recovered", ts = 1)))

    assertEquals(listOf("recovered"), names())
  }

  @Test
  fun `incoming JSON that is not an event array is preserved verbatim`() {
    // Every caller wrote the payload through before this existed. Failing to read it is not a reason
    // to throw it away.
    SessionTraceFile.merge(traceFile, """{"traceEvents":[]}""")

    assertEquals("""{"traceEvents":[]}""", traceFile.readText())
  }

  @Test
  fun `an empty incoming batch leaves what is already recorded`() {
    write(event("host-tool", ts = 1_000))

    SessionTraceFile.merge(traceFile, "[]")

    assertEquals(listOf("host-tool"), names())
  }

  @Test
  fun `every field of a merged event survives the round trip`() {
    // The file is read by Perfetto, the profiler and the OTel exporter, all of which need the ids.
    val original = event("tool", ts = 1_000, sid = "aaaaaaaaaaaaaaaa", trid = "b".repeat(32))

    SessionTraceFile.merge(traceFile, json(original))

    assertEquals(original, SessionTraceFile.read(traceFile).single())
  }

  @Test
  fun `reading an absent file gives no events rather than failing`() {
    assertEquals(emptyList(), SessionTraceFile.read(File(tempFolder.root, "nope.json")))
  }

  @Test
  fun `a merge writes a JSON array a viewer can parse`() {
    SessionTraceFile.merge(traceFile, json(event("a", ts = 1), event("b", ts = 2)))

    val parsed = Json.parseToJsonElement(traceFile.readText())
    assertTrue(parsed is JsonArray, "trace.json must stay a bare array of events")
    assertEquals(2, parsed.size)
  }

  @Test
  fun `three writers all land in one file`() {
    // Host rule, host runner and device upload can all target the same session.
    SessionTraceFile.merge(traceFile, json(event("host-rule", ts = 1_000)))
    SessionTraceFile.merge(traceFile, json(event("host-runner", ts = 2_000)))
    SessionTraceFile.merge(traceFile, json(event("device", ts = 3_000)))

    assertEquals(listOf("host-rule", "host-runner", "device"), names())
  }

  @Test
  fun `events differing only in duration are both kept`() {
    // Duration is part of the identity, so a re-measured span is not mistaken for the same one.
    SessionTraceFile.merge(traceFile, json(event("tool", ts = 1_000, dur = 10)))
    SessionTraceFile.merge(traceFile, json(event("tool", ts = 1_000, dur = 20)))

    assertEquals(2, SessionTraceFile.read(traceFile).size)
  }

  @Test
  fun `a large existing file merges without dropping anything`() {
    val existing = (1..500).map { event("span-$it", ts = it.toLong()) }
    traceFile.writeText(Json.encodeToString(buildJsonArray { existing.forEach { add(it) } }))

    SessionTraceFile.merge(traceFile, json(event("late", ts = 10_000)))

    assertEquals(501, SessionTraceFile.read(traceFile).size)
    assertEquals("late", names().last())
  }

  // ── Damaged input must not cost you the half that parsed ──

  @Test
  fun `an unreadable payload is parked beside a trace that parsed, not written over it`() {
    // The failure this whole file exists to prevent, arriving by a different door: a device uploads
    // something malformed and the host's half of the run is gone.
    write(event("host-tool", ts = 1_000))

    SessionTraceFile.merge(traceFile, "{ not a trace at all")

    assertEquals(listOf("host-tool"), names())
    assertEquals("{ not a trace at all", File(tempFolder.root, "trace.json.raw").readText())
  }

  @Test
  fun `one bad event does not take its batch down`() {
    // `parseEvents` used to return null if any single element was not an object, which turned one
    // malformed event into "this payload is unreadable" — and so into a clobbered file.
    write(event("host-tool", ts = 1_000))

    SessionTraceFile.merge(traceFile, """[{"name":"good","ph":"X","ts":2000,"dur":1}, "garbage"]""")

    assertEquals(listOf("host-tool", "good"), names())
    assertTrue(!File(tempFolder.root, "trace.json.raw").exists(), "a readable batch must not be parked")
  }

  // ── Concurrent writers ──

  @Test
  fun `writers finishing together all keep their events`() {
    // A device upload lands on a Ktor handler thread while the host exports its own half to the
    // same path. Two interleaved read-modify-writes drop whichever batch wrote first.
    val writers = 8
    val perWriter = 25
    val ready = java.util.concurrent.CyclicBarrier(writers)
    val threads = (0 until writers).map { w ->
      Thread {
        ready.await()
        repeat(perWriter) { i ->
          SessionTraceFile.merge(traceFile, json(event("w$w-$i", ts = (w * 1_000 + i).toLong())))
        }
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertEquals(writers * perWriter, SessionTraceFile.read(traceFile).size)
  }

  @Test
  fun `a merge leaves no temp files behind`() {
    // The publish goes through a temp file in the same directory; a leaked one would show up in the
    // session directory and in every artifact upload of it.
    SessionTraceFile.merge(traceFile, json(event("host-tool", ts = 1)))
    SessionTraceFile.merge(traceFile, json(event("device-op", ts = 2)))

    assertEquals(listOf(SessionTraceFile.FILE_NAME), tempFolder.root.list()!!.sorted())
  }

  // ── Device clock ──

  @Test
  fun `a device batch is marked so the profiler can tell it from host spans`() {
    // Without the mark the extractor reads a device timestamp as a host one: the skew — whole
    // seconds — stretches the session window, and the spans render in Tools instead of Device.
    SessionTraceFile.merge(traceFile, json(event("a11y-capture", ts = 1)), onDeviceClock = true)

    assertEquals(
      listOf("device"),
      SessionTraceFile.read(traceFile).map { it["clock"]?.jsonPrimitive?.content },
    )
  }

  @Test
  fun `a host batch carries no clock marker`() {
    SessionTraceFile.merge(traceFile, json(event("host-tool", ts = 1)))

    assertEquals(listOf(null), SessionTraceFile.read(traceFile).map { it["clock"] })
  }

  @Test
  fun `marking a device batch does not disturb the host events already recorded`() {
    write(event("host-tool", ts = 1_000))

    SessionTraceFile.merge(traceFile, json(event("a11y-capture", ts = 2_000)), onDeviceClock = true)

    val byName = SessionTraceFile.read(traceFile).associateBy { it["name"]!!.jsonPrimitive.content }
    assertEquals(null, byName.getValue("host-tool")["clock"])
    assertEquals("device", byName.getValue("a11y-capture")["clock"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a device batch that already says which clock it is on keeps what it says`() {
    // A future producer that stamps its own origin must not have it rewritten by the endpoint that
    // happened to receive it.
    val stamped = buildJsonObject {
      put("name", "already-stamped")
      put("ph", "X")
      put("ts", 1)
      put("dur", 1)
      put("clock", "host")
    }

    SessionTraceFile.merge(traceFile, json(stamped), onDeviceClock = true)

    assertEquals("host", SessionTraceFile.read(traceFile).single()["clock"]?.jsonPrimitive?.content)
  }

  @Test
  fun `the same device batch arriving twice is still recorded once`() {
    // The mark is added before the dedupe, so a retried upload does not become two events that
    // differ only by whether they carry it.
    val batch = json(event("a11y-capture", ts = 1))

    SessionTraceFile.merge(traceFile, batch, onDeviceClock = true)
    SessionTraceFile.merge(traceFile, batch, onDeviceClock = true)

    assertEquals(1, SessionTraceFile.read(traceFile).size)
  }
}
