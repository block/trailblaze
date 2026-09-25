package xyz.block.trailblaze.capture.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.capture.ToolCallPhase
import xyz.block.trailblaze.events.SessionEvent
import xyz.block.trailblaze.events.SessionEvents

class MemoryCaptureTest {

  /** A scripted probe: the app's state is mutable between passes; every read is recorded. */
  private class FakeProbe : MemoryProbe {
    var pid: Int? = null
    /** Dalvik heap size in the dump; heap used = this − [MeminfoFixtures.DALVIK_HEAP_FREE_KB]. */
    var heapSizeKb: Long = 2_923
    var answering = true
    /** How long each read takes — the real thing costs a few hundred milliseconds. */
    var readDelayMs: Long = 0
    val gcRequests = mutableListOf<Boolean>()
    val readThreads = mutableListOf<String>()
    var closed = false
    private var readersNow = 0
    /** The most readers this probe ever saw at once. A real probe cannot serve two. */
    var maxConcurrentReads = 0
      private set

    override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? {
      synchronized(this) {
        gcRequests += forceGc
        readThreads += Thread.currentThread().name
        readersNow++
        maxConcurrentReads = maxOf(maxConcurrentReads, readersNow)
      }
      try {
        if (readDelayMs > 0) Thread.sleep(readDelayMs)
        if (!answering) return null
        val app = if (pid != null && appId != null) DumpsysMeminfoParser.parseAppSample(MeminfoFixtures.dumpsysWith(36_940, heapSizeKb = heapSizeKb)) else null
        return MemorySnapshot(
          timeMs = 0,
          appId = appId,
          pid = if (app != null) pid else null,
          app = app,
          device = DumpsysMeminfoParser.parseDeviceSample(MeminfoFixtures.PROC_MEMINFO),
          limits = if (app != null) AppMemoryLimits(heapGrowthLimitKb = 196_608, heapMaxKb = 524_288, largeHeap = false) else null,
          gcForced = if (app != null) forceGc else null,
          source = "fake",
        )
      } finally {
        synchronized(this) { readersNow-- }
      }
    }

    override fun close() {
      closed = true
    }
  }

  private fun withSession(block: (File) -> Unit) {
    val sessionDir = Files.createTempDirectory("memory-capture-test").toFile()
    try {
      block(sessionDir)
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  private fun events(sessionDir: File): List<SessionEvent> {
    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/memory.ndjson")
    if (!file.exists()) return emptyList()
    return file.readLines().filter { it.isNotBlank() }.map { Json.decodeFromString(SessionEvent.serializer(), it) }
  }

  /** The diagnostics configuration: readings on the caller's thread, GC forced — deterministic to drive by hand. */
  private fun capture(probe: MemoryProbe, clock: () -> Long = { 1_700_000_000_000L }, forceGc: Boolean = true) = MemoryCapture(
    probe = probe,
    clock = clock,
    // The sampling thread must not race the hand-driven passes below.
    sampleIntervalMs = 60_000,
    forceGc = forceGc,
    synchronousToolSamples = true,
  )

  @Test
  fun `writes a first and a final event and nothing for unchanged readings`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    var now = 1_700_000_000_000L
    val capture = capture(probe, clock = { now })
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    // Let the thread's own first pass land, then drive the rest by hand.
    waitForEvents(sessionDir, 1)
    now += 5_000
    assertNull(capture.sampleOnce(), "an identical reading must not produce an event")
    assertNull(capture.sampleOnce())
    now += 5_000
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "final"), written.map { it.reason() })
    assertEquals(listOf(1_700_000_000_000L, 1_700_000_010_000L), written.map { it.timeMs })
    val first = written[0].data.jsonObject
    assertEquals("com.android.settings", first["appId"]?.jsonPrimitive?.content)
    assertEquals(17220, first["pid"]?.jsonPrimitive?.content?.toInt())
    assertEquals("fake", first["source"]?.jsonPrimitive?.content)
    assertEquals(true, first["gcForced"]?.jsonPrimitive?.content?.toBoolean())
    // Heap used = heap size − heap free, straight from the Dalvik Heap row (2923 − 730).
    assertEquals(2_193, first["heapUsedKb"]?.jsonPrimitive?.content?.toLong())
    assertEquals(196_608, first["heapLimitKb"]?.jsonPrimitive?.content?.toLong())
    assertFalse(first.containsKey("deltaKb"), "the first event has nothing to diff against")
    // What the device had left, so a kill with a healthy heap is attributable to the system.
    assertEquals(360_660, first["deviceAvailableKb"]?.jsonPrimitive?.content?.toLong())
    // The event is the few numbers asked for, not the dump: no PSS breakdown and no limits block.
    assertEquals(
      setOf("reason", "appId", "pid", "heapUsedKb", "heapLimitKb", "deviceAvailableKb", "gcForced", "source", "readMs"),
      first.keys,
    )
  }

  @Test
  fun `a heap jump is emitted with its delta against the previous event`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    probe.heapSizeKb += 5_000
    assertEquals("memory_changed", capture.sampleOnce())
    probe.heapSizeKb += 100
    assertNull(capture.sampleOnce(), "a 100 kB wobble is under the threshold")
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "memory_changed", "final"), written.map { it.reason() })
    assertEquals(2_193 + 5_000, written[1].data.jsonObject["heapUsedKb"]?.jsonPrimitive?.content?.toLong())
    assertEquals(5_000, written[1].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong())
    // The final bookend diffs against the LAST EMITTED event, not the skipped wobble sample.
    assertEquals(100, written[2].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong())
  }

  @Test
  fun `in diagnostics mode every tool call is bracketed by a before and an after sample even when nothing changed`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "launchApp")
    probe.heapSizeKb += 300 // under the change threshold: a periodic sample would stay silent
    capture.onToolCall(ToolCallPhase.AFTER, "launchApp")
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    capture.onToolCall(ToolCallPhase.AFTER, "tapOnElement")
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "before_tool", "after_tool", "before_tool", "after_tool", "final"), written.map { it.reason() })
    assertEquals(listOf(null, "launchApp", "launchApp", "tapOnElement", "tapOnElement", null), written.map { it.tool() })
    // The after-sample's delta is what that one tool cost.
    assertEquals(300, written[2].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong())
    assertEquals(0, written[4].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong())
  }

  @Test
  fun `a tool boundary that finds the app gone reports the death, not an ordinary boundary row`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "stopApp", traceId = "tool-77")
    probe.pid = null // the tool killed the app
    capture.onToolCall(ToolCallPhase.AFTER, "stopApp", traceId = "tool-77")
    // The boundary row is the ONLY chance to say the process went away: it is now the last emitted
    // reading, so a periodic pass compares two pid-less readings and has nothing to report.
    assertNull(capture.sampleOnce(), "the death is already recorded; there is no second transition")
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "before_tool", "process_died", "final"), written.map { it.reason() })
    // Still attributed: the row names the tool that did it and carries its trace.
    assertEquals("stopApp", written[2].tool())
    assertEquals("tool-77", written[2].traceId())
  }

  @Test
  fun `a boundary still waiting when the session stops keeps its tool on the final row`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 300 }
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 60_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    // The run ends while the last tool's `after` reading is still queued. Dropping it outright
    // would end the session on a row joined to nothing, losing the last tool's memory entirely.
    capture.onToolCall(ToolCallPhase.BEFORE, "assertVisible", traceId = "tool-91")
    waitUntil("the worker picked the first request up") { synchronized(probe) { probe.gcRequests.size } == 2 }
    capture.onToolCall(ToolCallPhase.AFTER, "assertVisible", traceId = "tool-91")
    capture.stop()

    val written = events(sessionDir)
    val last = written.last()
    assertEquals("final", last.reason())
    assertEquals("assertVisible", last.tool(), "the folded-in boundary's tool")
    assertEquals("tool-91", last.traceId())
  }

  @Test
  fun `by default a tool boundary never waits for the device - the reading lands later, stamped with the boundary's time`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 300 }
    var now = 1_700_000_000_000L
    val capture = MemoryCapture(probe = probe, clock = { now }, sampleIntervalMs = 60_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    now += 1_000
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    // Returning before the reading landed IS the contract, and it is observable: the event is not
    // there yet, and no reading ever ran on this thread (asserted below). A wall-clock bound on
    // the call would say the same thing less reliably — a GC pause or a loaded agent flips it.
    assertEquals(1, events(sessionDir).size, "the reading has not landed yet")

    waitForEvents(sessionDir, 2)
    val boundary = events(sessionDir)[1]
    assertEquals("before_tool", boundary.reason())
    assertEquals("tapOnElement", boundary.tool())
    assertEquals(1_700_000_001_000L, boundary.timeMs, "the event is about the boundary, not about when the device answered")
    val toolThread = Thread.currentThread().name
    assertTrue(probe.readThreads.none { it == toolThread }, "no reading ran on the tool's thread: ${probe.readThreads}")
    capture.stop()
  }

  @Test
  fun `a boundary asked about before the row that beat it to the file still lands after that row`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    var now = 1_700_000_000_000L
    val capture = capture(probe, clock = { now })
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    // The shape the reader produces under load: a boundary is asked about at +2s, a cron reading
    // that was already in flight lands at +4s and is written first, and only then does the
    // boundary get the device. Driven by hand rather than raced so the ordering is the assertion
    // and not the scheduler.
    now += 4_000
    probe.heapSizeKb += 5_000
    assertEquals("memory_changed", capture.sampleOnce())
    probe.heapSizeKb += 5_000
    assertEquals(
      MemoryChangeDetector.REASON_BEFORE_TOOL,
      capture.sampleOnce(
        forcedReason = MemoryChangeDetector.REASON_BEFORE_TOOL,
        tool = "tapOnElement",
        atTimeMs = 1_700_000_002_000L,
      ),
    )
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "memory_changed", "before_tool", "final"), written.map { it.reason() })
    // Each row's `delta` is against the row before it in the FILE, and the report orders rows by
    // timestamp — so a stamp that inverted the two would show this boundary's 5 MB growth as
    // happening before the reading it was measured against, pointing the change backwards.
    assertEquals(
      written.map { it.timeMs }.sorted(),
      written.map { it.timeMs },
      "the stream must be in timestamp order, was: ${written.map { "${it.reason()}@${it.timeMs}" }}",
    )
    assertEquals(1_700_000_004_000L, written[2].timeMs, "held at the row it was measured against")
    assertEquals(5_000, written[2].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong())
  }

  @Test
  fun `boundaries that arrive while the device is still answering wait their turn, keeping each tool's pair`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 300 }
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 60_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    // An instant tool: its after and the next tool's before land while the first request is still
    // being read. A tool finishing faster than a reading takes is ordinary, and it must not cost
    // that tool both of its rows — the `after_tool` delta is the only record of what it cost.
    capture.onToolCall(ToolCallPhase.BEFORE, "wait")
    waitUntil("the worker picked the request up") { synchronized(probe) { probe.gcRequests.size } == 2 }
    capture.onToolCall(ToolCallPhase.AFTER, "wait")
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    waitForEvents(sessionDir, 4)
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "before_tool", "after_tool", "before_tool", "final"), written.map { it.reason() })
    assertEquals(listOf(null, "wait", "wait", "tapOnElement", null), written.map { it.tool() }, "no tool loses a row")
  }

  @Test
  fun `a device that has stopped answering drops the oldest boundaries rather than banking them all`() = withSession { sessionDir ->
    // The queue is for a tool that outran a reading, not for a wedged device. Past its bound the
    // oldest requests go, so the work that survives is the part of the run closest to now.
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 60_000 }
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 600_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")

    repeat(MemoryCapture.MAX_PENDING_REQUESTS + 4) { capture.onToolCall(ToolCallPhase.BEFORE, "tool$it") }

    assertEquals(
      MemoryCapture.MAX_PENDING_REQUESTS,
      capture.pendingRequestCount(),
      "the backlog holds its bound and no more",
    )
    capture.stop()
  }

  @Test
  fun `past the event ceiling only the final bookend is written`() = withSession { sessionDir ->
    // A pathological allocator must not fill the session directory. The bookend is the exception:
    // a run that hit the ceiling still needs its closing reading, or the report's last figure is
    // whatever was current thousands of events earlier.
    var now = 1_700_000_000_000L
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = MemoryCapture(
      probe = probe,
      clock = { now },
      sampleIntervalMs = 600_000,
      synchronousToolSamples = true,
      maxEvents = 3,
    )
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    repeat(6) {
      now += 1_000
      probe.heapSizeKb += 10_000 // every reading is a change, so only the ceiling can stop the writing
      capture.onToolCall(ToolCallPhase.BEFORE, "tool$it")
    }
    capture.stop()

    val written = events(sessionDir)
    assertEquals(3, written.count { it.reason() != "final" }, "writing stops at the ceiling")
    assertEquals("final", written.last().reason(), "and the closing bookend is written anyway")
  }

  @Test
  fun `the app is asked to collect garbage before every reading unless GC is turned off`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    capture.stop()
    assertTrue(probe.gcRequests.isNotEmpty() && probe.gcRequests.all { it }, probe.gcRequests.toString())

    val before = events(sessionDir).size
    val quiet = FakeProbe().apply { pid = 17220 }
    val noGc = capture(quiet, forceGc = false)
    noGc.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, before + 1)
    noGc.stop()
    assertTrue(quiet.gcRequests.isNotEmpty() && quiet.gcRequests.none { it }, quiet.gcRequests.toString())
  }

  @Test
  fun `the app launching, restarting and dying mid-session each become events`() = withSession { sessionDir ->
    val probe = FakeProbe() // not running yet — the trail launches it
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    probe.pid = 100
    assertEquals("process_started", capture.sampleOnce())
    probe.pid = 101
    assertEquals("process_restarted", capture.sampleOnce())
    probe.pid = null
    assertEquals("process_died", capture.sampleOnce())
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "process_started", "process_restarted", "process_died", "final"), written.map { it.reason() })
    val first = written[0].data.jsonObject
    assertFalse(first.containsKey("pid"))
    assertFalse(first.containsKey("heapUsedKb"))
    assertFalse(first.containsKey("gcForced"), "nothing to collect while the app is not running")
    assertEquals("com.android.settings", first["appId"]?.jsonPrimitive?.content, "the row still says which app it waits for")
    assertEquals(101, written[2].data.jsonObject["pid"]?.jsonPrimitive?.content?.toInt())
    assertFalse(written[2].data.jsonObject.containsKey("deltaKb"), "no delta across a process boundary")
    assertFalse(written[3].data.jsonObject.containsKey("deltaKb"), "no delta across a process boundary")
  }

  @Test
  fun `with no app id nothing is sampled and nothing is written`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", appId = null)
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    assertNull(capture.sampleOnce())
    assertNull(capture.stop())
    // Asserting the RESOURCE, not a moment of silence: with no app id no worker is ever started,
    // so the probe is untouched and the stream file is never created. A sleep-then-check would
    // pass just as happily against a worker that had not got going yet.
    assertTrue(probe.gcRequests.isEmpty(), "the probe is never asked")
    assertFalse(File(sessionDir, "${SessionEvents.DIR_NAME}/memory.ndjson").exists())
  }

  @Test
  fun `a probe that stops answering produces no events and stop still returns cleanly`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { answering = false }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    // Wait on the probe having been ASKED rather than on a stopwatch: the point is that readings
    // happened and produced nothing, which a sleep-then-assert-empty cannot tell from a worker
    // that simply had not started.
    waitUntil("the sampler asked the device") { synchronized(probe) { probe.gcRequests.isNotEmpty() } }
    assertNull(capture.sampleOnce())
    assertNull(capture.stop())
    assertTrue(events(sessionDir).isEmpty())
  }

  @Test
  fun `stop returns no artifact, closes the probe, and ignores later tool calls`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)
    assertNull(capture.stop())
    assertTrue(probe.closed)
    val after = events(sessionDir).size
    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement")
    assertEquals(after, events(sessionDir).size, "a stopped capture must not write")
    assertTrue(File(sessionDir, "${SessionEvents.DIR_NAME}/memory.ndjson").isFile)
  }

  @Test
  fun `GC is off unless asked for, and every event records how long its reading took`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220 }
    // The production default: nothing says "force GC".
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 60_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)
    capture.onToolCall(ToolCallPhase.AFTER, "tapOnElement")
    waitForEvents(sessionDir, 2)
    capture.stop()
    assertTrue(probe.gcRequests.isNotEmpty() && probe.gcRequests.none { it }, probe.gcRequests.toString())

    val written = events(sessionDir)
    assertTrue(written.size >= 3)
    for (event in written) {
      val data = event.data.jsonObject
      assertEquals(false, data["gcForced"]?.jsonPrimitive?.content?.toBoolean(), "the reading says no GC ran: $data")
      val readMs = data["readMs"]?.jsonPrimitive?.content?.toLong()
      assertTrue(readMs != null && readMs >= 0, "each event carries the reading's duration: $data")
    }
  }

  @Test
  fun `in diagnostics mode the cron does not read across a tool call, so the pair stays adjacent`() = withSession { sessionDir ->
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 40 }
    val capture = MemoryCapture(
      probe = probe,
      // Short enough that several ticks come due inside the tool call below.
      sampleIntervalMs = 50,
      forceGc = true,
      synchronousToolSamples = true,
    )
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "launchApp")
    // The tool allocates and takes long enough to span several cron ticks. Each of those would
    // force a GC in the middle of the action and write its own `memory_changed` row between the
    // pair, leaving the `after_tool` delta measured against the tick instead of the tool's start.
    probe.heapSizeKb += 5_000
    Thread.sleep(300)
    capture.onToolCall(ToolCallPhase.AFTER, "launchApp")
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "before_tool", "after_tool", "final"), written.map { it.reason() })
    assertEquals(
      5_000,
      written[2].data.jsonObject["deltaKb"]?.jsonPrimitive?.content?.toLong(),
      "the after row diffs against this tool's own before row",
    )
    assertEquals(1, probe.maxConcurrentReads, "the tool thread and the cron must never read the device at once")
  }

  @Test
  fun `the cron keeps sampling once the tool call is over`() = withSession { sessionDir ->
    // The stand-down is for the length of a tool call, not for the rest of the session: an
    // allocation between tools still has to land.
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 50, forceGc = true, synchronousToolSamples = true)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "launchApp")
    capture.onToolCall(ToolCallPhase.AFTER, "launchApp")
    probe.heapSizeKb += 5_000
    waitForEvents(sessionDir, 4)
    capture.stop()

    assertEquals("memory_changed", events(sessionDir)[3].reason(), "the cron resumed after the tool call")
  }

  @Test
  fun `a tool boundary's event carries the trace of the dispatch it brackets`() = withSession { sessionDir ->
    // Without this a memory row can only be tied back to the tool that caused it by name and
    // timestamp, which stops working the moment a trail taps the same element twice.
    val probe = FakeProbe().apply { pid = 17220 }
    val capture = capture(probe)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    // `first` is the fallback for a reading with nothing to compare against, so a synchronous
    // `before_tool` that beats the worker claims it and the worker's own pass then reports no change.
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement", "tool-1c3f")
    capture.onToolCall(ToolCallPhase.AFTER, "tapOnElement", "tool-1c3f")
    capture.stop()

    val written = events(sessionDir)
    assertEquals(listOf("first", "before_tool", "after_tool", "final"), written.map { it.reason() })
    assertEquals(
      listOf(null, "tool-1c3f", "tool-1c3f", null),
      written.map { it.traceId() },
      "both sides of the pair carry the trace; the bookends belong to no dispatch",
    )
  }

  @Test
  fun `the trace survives the hop to the background reader that the default mode takes`() = withSession { sessionDir ->
    // The default mode answers a boundary from another thread, well after the fact, so the trace
    // has to travel with the queued request rather than being read off whatever is current.
    val probe = FakeProbe().apply { pid = 17220; readDelayMs = 200 }
    val capture = MemoryCapture(probe = probe, sampleIntervalMs = 60_000)
    capture.start(sessionDir, "emulator-5554", "com.android.settings")
    waitForEvents(sessionDir, 1)

    capture.onToolCall(ToolCallPhase.BEFORE, "tapOnElement", "tool-9ab2")
    waitForEvents(sessionDir, 2)
    capture.stop()

    val boundary = events(sessionDir)[1]
    assertEquals("before_tool", boundary.reason())
    assertEquals("tool-9ab2", boundary.traceId())
  }

  private fun SessionEvent.reason(): String = data.jsonObject["reason"]!!.jsonPrimitive.content

  private fun SessionEvent.tool(): String? = data.jsonObject["tool"]?.jsonPrimitive?.content

  private fun SessionEvent.traceId(): String? = data.jsonObject["traceId"]?.jsonPrimitive?.content

  private fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    assertTrue(condition(), "timed out waiting until $what")
  }

  /**
   * Hang containment, not a performance bound. These waits are for a background thread to do
   * something that normally takes milliseconds; the number only needs to be far enough above a
   * loaded CI agent's worst case that it never decides the outcome.
   */
  private val WAIT_DEADLINE_MS = 60_000L

  private fun waitForEvents(sessionDir: File, count: Int) {
    val deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS
    while (events(sessionDir).size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertTrue(events(sessionDir).size >= count, "expected $count event(s) from the sampler's first pass")
  }
}
