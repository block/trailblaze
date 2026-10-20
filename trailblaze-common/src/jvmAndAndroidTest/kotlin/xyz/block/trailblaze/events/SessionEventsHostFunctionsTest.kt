package xyz.block.trailblaze.events

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

class SessionEventsHostFunctionsTest {

  private val sessionDir: File = Files.createTempDirectory("session-events").toFile()
  private val session = TrailblazeSession(sessionId = SessionId("s-${System.nanoTime()}"), startTime = Clock.System.now())

  @AfterTest
  fun cleanUp() {
    ActiveSessionEventsDir.clear()
    SessionEventsWaitPump.clear()
    sessionDir.deleteRecursively()
  }

  private fun context(dir: File? = sessionDir) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider { session },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    sessionDirProvider = dir?.let { d -> { _: SessionId -> d } },
  )

  private fun journey(name: String, type: String) = buildJsonObject {
    put("journeyName", name)
    put("eventType", buildJsonObject { put("type", type) })
  }

  private fun append(stream: String, data: JsonObject, dir: File = sessionDir, timeMs: Long = System.currentTimeMillis()) =
    FileEventSink(dir).use { it.append(stream, timeMs, data) }

  private fun call(name: String, args: String, ctx: TrailblazeToolExecutionContext = context()): JsonObject =
    when (val outcome = runBlocking { TrailblazeHostFunctionDispatcher.call(name, args, ctx) }) {
      is TrailblazeHostFunctionDispatcher.Outcome.Success -> outcome.value.jsonObject
      is TrailblazeHostFunctionDispatcher.Outcome.Failure -> error(outcome.message)
    }

  private fun query(args: String, ctx: TrailblazeToolExecutionContext = context()) =
    call(SessionEventsHostFunctions.QUERY, args, ctx)

  private fun names(result: JsonObject) =
    result["events"]!!.jsonArray.map { it.jsonObject["data"]!!.jsonObject["journeyName"]!!.jsonPrimitive.content }

  private val stream = "com.example.journeys"

  @Test
  fun `any tool can query mid-run, and a missing stream says it was never captured`() {
    append(stream, journey("LOGIN", "start"))
    append(stream, journey("LOGIN", "end"))
    append(stream, journey("CHECKOUT", "end"))

    val ends = query("""{"stream":"$stream","match":{"eventType":{"type":"end"}}}""")
    assertTrue(ends["captured"]!!.jsonPrimitive.boolean)
    assertEquals(listOf("LOGIN", "CHECKOUT"), names(ends))

    val missing = query("""{"stream":"com.example.analytics"}""")
    assertFalse(missing["captured"]!!.jsonPrimitive.boolean)
    assertEquals(0, missing["events"]!!.jsonArray.size)
  }

  @Test
  fun `a waiting query converts device stamps with a clock sample that lands during the wait`() {
    // The stream exists before the device clock is measured, so the first scan has nothing to
    // convert with; the record that matches arrives after the sample, an hour ahead raw.
    append(stream, journey("LOGIN", "start"))
    val aheadMs = 3_600_000L
    val result = runBlocking {
      val waiter = async(Dispatchers.Default) {
        query("""{"stream":"$stream","match":{"journeyName":"CHECKOUT"},"waitMs":10000}""")
      }
      delay(300)
      val now = System.currentTimeMillis()
      DeviceClockSyncFile.appendSample(sessionDir, "emulator-5554", DeviceClockSample(now, now, now + aheadMs))
      FileEventSink(sessionDir).use {
        it.append(stream, now + aheadMs, journey("CHECKOUT", "end"), TrailblazeClockDomain.DEVICE)
      }
      waiter.await()
    }
    val timeMs = result["events"]!!.jsonArray.single().jsonObject["timeMs"]!!.jsonPrimitive.long
    assertTrue(timeMs <= System.currentTimeMillis(), "on the host clock, not an hour ahead: $timeMs")
  }

  @Test
  fun `since a mark reads only what arrived after it, including streams created later`() {
    append(stream, journey("LOGIN", "end"))
    call(SessionEventsHostFunctions.MARK, """{"name":"launched"}""")
    append(stream, journey("CHECKOUT", "end"))
    append("network", buildJsonObject { put("journeyName", "AUTH") })

    assertEquals(listOf("CHECKOUT"), names(query("""{"stream":"$stream","since":"launched"}""")))
    assertEquals(listOf("AUTH"), names(query("""{"stream":"network","since":"launched"}""")))
  }

  @Test
  fun `a wait returns as soon as the record lands, and a cursor reads only what came next`() = runBlocking {
    append(stream, journey("LOGIN", "start"))
    val writer = launch {
      delay(300)
      append(stream, journey("LOGIN", "end"))
    }
    val result = query("""{"stream":"$stream","match":{"journeyName":"LOGIN","eventType":{"type":"end"}},"waitMs":10000}""")
    writer.join()

    assertEquals(listOf("LOGIN"), names(result))
    assertTrue(result["scan"]!!.jsonObject["waitedMs"]!!.jsonPrimitive.long < 5000)

    append(stream, journey("CHECKOUT", "start"))
    val next = query("""{"stream":"$stream","after":${result["cursor"]!!.jsonPrimitive.long}}""")
    assertEquals(listOf("CHECKOUT"), names(next))
  }

  @Test
  fun `newest returns the latest match first, and count returns no payloads`() {
    listOf("A", "B", "C").forEach { append(stream, journey(it, "end")) }

    assertEquals(listOf("C"), names(query("""{"stream":"$stream","newest":true,"limit":1}""")))
    assertEquals(listOf("C", "B"), names(query("""{"stream":"$stream","newest":true,"limit":2}""")))

    val counted = query("""{"stream":"$stream","count":true,"match":{"eventType":{"type":"end"}}}""")
    assertEquals(3, counted["count"]!!.jsonPrimitive.int)
    assertFalse(counted.containsKey("events"))
  }

  @Test
  fun `a wait for more matches than the limit returns the limit, and its cursor skips nothing`() {
    listOf("A", "B", "C").forEach { append(stream, journey(it, "end")) }

    val first = query("""{"stream":"$stream","limit":2,"minCount":5,"waitMs":300}""")
    assertEquals(listOf("A", "B"), names(first))
    assertTrue(first["scan"]!!.jsonObject["waitedMs"]!!.jsonPrimitive.long < 300, "returned at the limit without waiting")

    val rest = query("""{"stream":"$stream","after":${first["cursor"]!!.jsonPrimitive.long}}""")
    assertEquals(listOf("C"), names(rest))
  }

  @Test
  fun `on a device with no host session dir, the registered capture folder is read`() {
    val deviceDir = Files.createTempDirectory("ondevice-capture").toFile()
    try {
      append(stream, journey("LOGIN", "end"), dir = deviceDir)
      ActiveSessionEventsDir.set(deviceDir)
      assertEquals(listOf("LOGIN"), names(query("""{"stream":"$stream"}""", context(dir = null))))
    } finally {
      deviceDir.deleteRecursively()
    }
  }

  @Test
  fun `an unknown mark is an error, not an empty result`() {
    append(stream, journey("LOGIN", "end"))
    val outcome = runBlocking {
      TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.QUERY, """{"stream":"$stream","since":"nope"}""", context())
    }
    assertTrue(outcome is TrailblazeHostFunctionDispatcher.Outcome.Failure)
    assertTrue((outcome as TrailblazeHostFunctionDispatcher.Outcome.Failure).message.contains("no mark named 'nope'"))
  }

  @Test
  fun `newest means last to arrive, even when its timestamp is older`() {
    append(stream, journey("LATE_CLOCK", "end"), timeMs = 300)
    append(stream, journey("ARRIVED_LAST", "end"), timeMs = 100)

    assertEquals(listOf("ARRIVED_LAST", "LATE_CLOCK"), names(query("""{"stream":"$stream","newest":true}""")))
  }

  @Test
  fun `an unknown mark fails even before its stream exists`() {
    val outcome = runBlocking {
      TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.QUERY, """{"stream":"com.example.later","since":"nope"}""", context())
    }
    assertTrue(outcome is TrailblazeHostFunctionDispatcher.Outcome.Failure)
  }

  @Test
  fun `an idle wait over a line still being written reads the file once`() {
    append(stream, journey("LOGIN", "start"))
    val file = File(File(sessionDir, SessionEvents.DIR_NAME), SessionEvents.fileName(stream))
    file.appendText("{\"timeMs\":1,")
    val length = file.length()

    val result = query("""{"stream":"$stream","match":{"journeyName":"CHECKOUT"},"waitMs":600}""")
    assertEquals(0, result["events"]!!.jsonArray.size)
    val bytesRead = result["scan"]!!.jsonObject["bytesRead"]!!.jsonPrimitive.long
    assertTrue(bytesRead <= length, "read $bytesRead bytes of a $length-byte file")
  }

  @Test
  fun `a newest-first wait that sees a burst returns its latest matches`() = runBlocking {
    append(stream, journey("BEFORE", "start"))
    val writer = launch {
      delay(300)
      // One uninterrupted write on the test's thread, so a single poll sees the whole burst.
      FileEventSink(sessionDir).use { sink ->
        listOf("A", "B", "C", "D", "E").forEach { sink.append(stream, System.currentTimeMillis(), journey(it, "end")) }
      }
    }
    val result = query("""{"stream":"$stream","match":{"eventType":{"type":"end"}},"newest":true,"limit":2,"waitMs":10000}""")
    writer.join()

    assertEquals(listOf("E", "D"), names(result))
  }

  @Test
  fun `a count waits for its own threshold, even past the record limit`() {
    FileEventSink(sessionDir).use { sink ->
      repeat(SessionEventsHostFunctions.MAX_LIMIT) { sink.append(stream, System.currentTimeMillis(), journey("LOGIN", "end")) }
    }
    val counted = query("""{"stream":"$stream","count":true,"minCount":${SessionEventsHostFunctions.MAX_LIMIT + 1},"waitMs":300}""")

    assertEquals(SessionEventsHostFunctions.MAX_LIMIT, counted["count"]!!.jsonPrimitive.int)
    assertTrue(counted["scan"]!!.jsonObject["waitedMs"]!!.jsonPrimitive.long >= 300, "waited for the threshold")
  }

  @Test
  fun `the size budget counts encoded bytes, so non-ASCII records can't overrun it`() {
    // 300,000 characters, 900 KB as UTF-8: five of them pass the budget only if counted by bytes.
    val padding = "日".repeat(300_000)
    FileEventSink(sessionDir).use { sink ->
      (1..5).forEach { sink.append(stream, System.currentTimeMillis(), buildJsonObject { put("n", it); put("pad", padding) }) }
    }

    val result = query("""{"stream":"$stream"}""")

    assertTrue(result["truncated"]?.jsonPrimitive?.boolean == true, "five 900 KB records fit a 4 MB budget")
    val sample = "a\u00e9\u20ac\ud83d\ude00"
    assertEquals(sample.toByteArray(Charsets.UTF_8).size.toLong(), SessionEventsHostFunctions.utf8Bytes(sample))
  }

  @Test
  fun `a result stops at its size budget, and its cursor picks up the rest`() {
    val padding = "x".repeat(900_000)
    val all = listOf("A", "B", "C", "D", "E", "F")
    FileEventSink(sessionDir).use { sink ->
      all.forEach { sink.append(stream, System.currentTimeMillis(), buildJsonObject { put("journeyName", it); put("pad", padding) }) }
    }

    val first = query("""{"stream":"$stream"}""")
    assertTrue(first["truncated"]!!.jsonPrimitive.boolean)
    val firstNames = names(first)
    assertTrue(firstNames.size < all.size, "returned ${firstNames.size} of ${all.size}")

    val rest = query("""{"stream":"$stream","after":${first["cursor"]!!.jsonPrimitive.long}}""")
    assertEquals(all, firstNames + names(rest))

    val newest = query("""{"stream":"$stream","newest":true}""")
    assertTrue(newest["truncated"]!!.jsonPrimitive.boolean)
    assertEquals(all.reversed().take(names(newest).size), names(newest))
  }

  @Test
  fun `a loss not tied to one stream makes every result incomplete, with its reason`() {
    append(stream, journey("LOGIN", "end"))
    assertTrue(query("""{"stream":"$stream"}""")["complete"]!!.jsonPrimitive.boolean)

    File(sessionDir, SessionCaptureHealth.MARKER_FILE).writeText("plugin event capture: queue full\n")
    val counted = query("""{"stream":"$stream","count":true,"match":{"journeyName":"CHECKOUT"}}""")

    assertEquals(0, counted["count"]!!.jsonPrimitive.int)
    assertFalse(counted["complete"]!!.jsonPrimitive.boolean)
    assertEquals("plugin event capture: queue full", counted["incompleteReason"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a loss in one stream makes only that stream's results incomplete`() {
    append(stream, journey("LOGIN", "end"))
    append("memory", buildJsonObject { put("pss", 1) })
    // An app restart mid-trail leaves a memory reading empty; that gap says nothing about journeys.
    SessionCaptureHealth.markIncomplete(sessionDir, "memory-capture: a reading came back with nothing", "memory")

    assertTrue(query("""{"stream":"$stream"}""")["complete"]!!.jsonPrimitive.boolean)
    val memory = query("""{"stream":"memory"}""")
    assertFalse(memory["complete"]!!.jsonPrimitive.boolean)
    assertEquals("memory-capture: a reading came back with nothing", memory["incompleteReason"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a pump that blocks past its slice doesn't stretch the wait`() = runBlocking {
    // Like a pump queued behind other work on the browser's thread.
    SessionEventsWaitPump.register("slow") { Thread.sleep(1_000) }
    val started = System.currentTimeMillis()

    SessionEventsWaitPump.waitFor("slow", 1_000)

    val took = System.currentTimeMillis() - started
    assertTrue(took < 2_000, "a 1 s wait took $took ms")
  }

  @Test
  fun `a pump queued behind long work on the driver's thread can't hold a wait past its deadline`() = runBlocking {
    SessionEventsWaitPump.register("busy") { Thread.sleep(30_000) }
    val started = System.currentTimeMillis()

    SessionEventsWaitPump.waitFor("busy", 1_000)

    val took = System.currentTimeMillis() - started
    assertTrue(took < 3_000, "a 1 s wait took $took ms")
  }

  @Test
  fun `cancelling a wait doesn't hang on a pump stuck behind the driver's thread`() = runBlocking {
    SessionEventsWaitPump.register("stuck") { Thread.sleep(10_000) }
    val wait = launch(kotlinx.coroutines.Dispatchers.Default) { SessionEventsWaitPump.waitFor("stuck", 10_000) }
    delay(100)
    val started = System.currentTimeMillis()

    wait.cancelAndJoin()

    val took = System.currentTimeMillis() - started
    assertTrue(took < 2_000, "cancelling took $took ms")
  }

  @Test
  fun `a record too large to read makes the result incomplete, since it might have matched`() {
    append(stream, buildJsonObject { put("journeyName", "LOGIN"); put("blob", "x".repeat(EventStreamScanner.DEFAULT_MAX_LINE_BYTES)) })
    append(stream, journey("CHECKOUT", "end"))

    val counted = query("""{"stream":"$stream","count":true,"match":{"journeyName":"LOGIN"}}""")

    assertEquals(0, counted["count"]!!.jsonPrimitive.int)
    assertFalse(counted["complete"]!!.jsonPrimitive.boolean)
    assertTrue(counted["incompleteReason"]!!.jsonPrimitive.content.contains("1 record(s)"), counted.toString())
  }

  @Test
  fun `a loss still counts when its marker can't be written`() {
    val unwritable = File(Files.createTempFile("not-a-dir", "").toFile().apply { deleteOnExit() }, "session")
    SessionCaptureHealth.markIncomplete(unwritable, "web network capture: queue overflow")

    assertEquals("web network capture: queue overflow", SessionCaptureHealth.failure(unwritable))
    SessionCaptureHealth.clear(unwritable)
    assertEquals(null, SessionCaptureHealth.failure(unwritable))
  }

  @Test
  fun `a misspelled option is an error, not a different query`() {
    val outcome = runBlocking {
      TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.QUERY, """{"stream":"$stream","wait":5000}""", context())
    }
    assertTrue(outcome is TrailblazeHostFunctionDispatcher.Outcome.Failure)
    assertTrue(outcome.message.contains("'wait'"), outcome.message)
  }

  @Test
  fun `a wait runs the session's pump, so a driver that delivers only while called still lands its record`() {
    append(stream, journey("LOGIN", "start"))
    var pumped = 0
    // Like Playwright for Java: the record reaches the capture only while the pump is running.
    val pump: (Long) -> Unit = { ms ->
      if (++pumped == 2) append(stream, journey("LOGIN", "end"))
      Thread.sleep(ms)
    }
    SessionEventsWaitPump.register(session.sessionId.value, pump)

    val result = query("""{"stream":"$stream","match":{"eventType":{"type":"end"}},"waitMs":10000}""")

    assertEquals(listOf("LOGIN"), names(result))
    assertTrue(pumped >= 2)
    SessionEventsWaitPump.unregister(session.sessionId.value, pump)
    val calls = pumped
    query("""{"stream":"$stream","match":{"journeyName":"NEVER"},"waitMs":300}""")
    assertEquals(calls, pumped, "an unregistered pump is not run")
  }
}
