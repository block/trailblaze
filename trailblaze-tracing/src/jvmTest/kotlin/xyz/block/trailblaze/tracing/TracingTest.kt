package xyz.block.trailblaze.tracing

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder

class TracingTest {

  @Before
  fun reset() {
    TrailblazeTracer.clear()
    // Also the level, because it defaults from the environment: a developer with
    // TRAILBLAZE_TRACE_LEVEL set would otherwise fail every test in this file for a reason nothing
    // here mentions.
    TrailblazeTracer.level = TraceLevel.NORMAL
  }

  private fun recordedEvents(): List<JsonObject> =
    TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(TrailblazeTracer.exportJson()).map { it.jsonObject }

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

  private fun eventNamed(name: String): JsonObject = recordedEvents().single { it.str("name") == name }

  @Test
  fun test() = runBlocking {
    traceRecorder.trace("abc") {
      delay(1)
    }
    TrailblazeTracer.traceSuspend("def") {
      delay(1)
    }

    val json = TrailblazeTracer.exportJson()

    val events = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(json)
    assertEquals(events.size, 2)
  }

  @Test
  fun `a nested trace records the enclosing span as its parent`() {
    traceRecorder.trace("outer") {
      traceRecorder.trace("inner") {
        traceRecorder.trace("innermost") { }
      }
    }

    val outer = eventNamed("outer")
    val inner = eventNamed("inner")
    val innermost = eventNamed("innermost")

    assertNull("an outermost span has no parent", outer.str("psid"))
    assertEquals(outer.str("sid"), inner.str("psid"))
    assertEquals(inner.str("sid"), innermost.str("psid"))
  }

  @Test
  fun `sibling traces share a parent rather than nesting into each other`() {
    traceRecorder.trace("parent") {
      traceRecorder.trace("first") { }
      traceRecorder.trace("second") { }
    }

    val parent = eventNamed("parent")
    assertEquals(parent.str("sid"), eventNamed("first").str("psid"))
    assertEquals(parent.str("sid"), eventNamed("second").str("psid"))
  }

  @Test
  fun `a span opened after its parent closed is a root, not the parent's child`() {
    traceRecorder.trace("earlier") { }
    traceRecorder.trace("later") { }

    assertNull(eventNamed("earlier").str("psid"))
    assertNull("closing a span must restore the previous innermost span", eventNamed("later").str("psid"))
  }

  @Test
  fun `parentage survives a thrown exception in the block`() {
    traceRecorder.trace("outer") {
      runCatching {
        traceRecorder.trace("throwing") { error("boom") }
      }
      traceRecorder.trace("after") { }
    }

    val outer = eventNamed("outer")
    // The failed span must still close, or "after" would be recorded as its child.
    assertEquals(outer.str("sid"), eventNamed("throwing").str("psid"))
    assertEquals(outer.str("sid"), eventNamed("after").str("psid"))
    assertEquals("boom", eventNamed("throwing")["args"]?.jsonObject?.str("error"))
  }

  @Test
  fun `suspending parentage survives a thread hop`() = runBlocking {
    TrailblazeTracer.traceSuspend("suspending-parent") {
      // Force a resumption on a different thread: a thread-local alone would lose the parent here.
      withContext(Dispatchers.IO) {
        TrailblazeTracer.traceSuspend("suspending-child") { delay(1) }
      }
    }

    val parent = eventNamed("suspending-parent")
    val child = eventNamed("suspending-child")
    assertNotNull(parent.str("sid"))
    assertEquals(parent.str("sid"), child.str("psid"))
    assertTrue(
      "the child really did run on another thread",
      parent["tid"]?.jsonPrimitive?.content != child["tid"]?.jsonPrimitive?.content,
    )
  }

  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  @Test
  fun `a coroutine that lands on the thread while a span is suspended does not adopt it`() = runBlocking {
    // The leak this pins: a suspending span used to set the thread-local and leave it set for the
    // whole suspension, so anything else dispatched to that thread recorded it as a parent — and
    // the extractor trusts a declared parent by id, collapsing the child into a long-closed span.
    val oneThread = kotlinx.coroutines.newSingleThreadContext("tracing-test")
    try {
      withContext(oneThread) {
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suspended = launch {
          TrailblazeTracer.traceSuspend("suspended") {
            inside.complete(Unit)
            delay(200)
          }
        }
        inside.await() // "suspended" is now parked inside its own span, on this very thread
        traceRecorder.trace("bystander") { }
        suspended.join()
      }
    } finally {
      oneThread.close()
    }

    assertNull("a parked span must not parent unrelated work on its thread", eventNamed("bystander").str("psid"))
  }

  @Test
  fun `a non-suspending trace inside a suspending one still finds its parent`() = runBlocking {
    TrailblazeTracer.traceSuspend("suspending") {
      delay(1) // resume before opening the child, so the local has to be reinstalled
      traceRecorder.trace("sync-child") { }
    }

    assertEquals(eventNamed("suspending").str("sid"), eventNamed("sync-child").str("psid"))
  }

  @Test
  fun `a suspending span leaves the thread-local exactly as it found it`() = runBlocking {
    traceRecorder.trace("sync-outer") {
      runBlocking { TrailblazeTracer.traceSuspend("suspending-inner") { delay(1) } }
      traceRecorder.trace("sync-after") { }
    }

    // If the suspending span had clobbered the local, "sync-after" would hang off it instead.
    assertEquals(eventNamed("sync-outer").str("sid"), eventNamed("sync-after").str("psid"))
    assertEquals(eventNamed("sync-outer").str("sid"), eventNamed("suspending-inner").str("psid"))
  }

  @Test
  fun `an inline trace that suspends still parents the children it opens after resuming`() = runBlocking {
    // `trace { }` is inline, so its lambda suspends along with the enclosing function. The thread
    // context element has to track the span that is actually innermost, not the one it was built
    // for, or "after-resume" hangs off the outer span instead.
    TrailblazeTracer.traceSuspend("suspending-outer") {
      traceRecorder.trace("inline-inner") {
        delay(1)
        traceRecorder.trace("after-resume") { }
      }
    }

    assertEquals(eventNamed("inline-inner").str("sid"), eventNamed("after-resume").str("psid"))
    assertEquals(eventNamed("suspending-outer").str("sid"), eventNamed("inline-inner").str("psid"))
  }

  @Test
  fun `a coroutine launched inside an inline span inherits that span, not the one above it`() = runBlocking {
    // The child's frame is snapshotted where it is launched, so it has to read the span that is
    // innermost right now. No suspension between opening "inline" and launching, deliberately:
    // anything that only learns the innermost span at a suspension boundary hands out "outer".
    TrailblazeTracer.traceSuspend("outer") {
      traceRecorder.trace("inline") {
        kotlinx.coroutines.coroutineScope {
          launch(Dispatchers.Default) { traceRecorder.trace("launched") { } }
        }
      }
    }

    assertEquals(eventNamed("inline").str("sid"), eventNamed("launched").str("psid"))
  }

  @Test
  fun `sibling coroutines under one span do not steal each other's children`() = runBlocking {
    // Both children inherit the parent's thread context element. One shared tracker would let them
    // overwrite each other's innermost span across suspensions.
    TrailblazeTracer.traceSuspend("shared-parent") {
      kotlinx.coroutines.coroutineScope {
        listOf("a", "b").forEach { tag ->
          launch(Dispatchers.Default) {
            traceRecorder.trace("branch-$tag") {
              delay(5)
              traceRecorder.trace("leaf-$tag") { }
            }
          }
        }
      }
    }

    assertEquals(eventNamed("branch-a").str("sid"), eventNamed("leaf-a").str("psid"))
    assertEquals(eventNamed("branch-b").str("sid"), eventNamed("leaf-b").str("psid"))
  }

  @Test
  fun `concurrent threads do not adopt each other's spans`() {
    val done = java.util.concurrent.CountDownLatch(2)
    val bothInside = java.util.concurrent.CyclicBarrier(2)
    listOf("a", "b").map { tag ->
      Thread {
        traceRecorder.trace("root-$tag") {
          bothInside.await() // both threads hold an open span simultaneously
          traceRecorder.trace("child-$tag") { }
        }
        done.countDown()
      }.apply { start() }
    }.forEach { it.join() }
    done.await()

    // Each child belongs to its OWN thread's root, despite the intervals fully overlapping.
    assertEquals(eventNamed("root-a").str("sid"), eventNamed("child-a").str("psid"))
    assertEquals(eventNamed("root-b").str("sid"), eventNamed("child-b").str("psid"))
  }

  @Test
  fun `a direct producer can mint an id and name the enclosing suspending span as its parent`() = runBlocking {
    // The shape the ktor and Playwright emitters use: build the event yourself, but still be
    // addressable. Without an id such a span can only be placed by containment inference, and the
    // HTTP emitters are exactly the ones inference refuses (their tid is observation-time).
    TrailblazeTracer.traceSuspend("enclosing-tool") {
      val parent = kotlin.coroutines.coroutineContext[TraceSpanContextElement.Key]?.spanId
      traceRecorder.add(
        CompleteEvent(
          name = "POST https://example/v1",
          cat = "http",
          ts = kotlinx.datetime.Clock.System.now(),
          dur = kotlin.time.Duration.ZERO,
          pid = 1,
          tid = 99,
          args = mapOf("async" to "true"),
          sid = traceRecorder.newSpanId(),
          psid = parent,
          kind = SpanKind.CLIENT,
        ).toJsonObject(),
      )
    }

    val http = eventNamed("POST https://example/v1")
    assertEquals(eventNamed("enclosing-tool").str("sid"), http.str("psid"))
    assertTrue("a direct producer's span must be addressable", http.str("sid")!!.matches(Regex("[0-9a-f]{16}")))
    assertEquals("CLIENT", http.str("kind"))
  }

  @Test
  fun `minted span ids never collide with the ids the tracer assigns itself`() {
    val minted = List(200) { traceRecorder.newSpanId() }
    repeat(200) { i -> traceRecorder.trace("span-$i") { } }

    val traced = recordedEvents().mapNotNull { it.str("sid") }
    assertEquals(200, traced.size)
    assertEquals("minted ids must be distinct", 200, minted.toSet().size)
    assertTrue("minted and traced ids must not overlap", (minted.toSet() intersect traced.toSet()).isEmpty())
  }

  @Test
  fun `events added directly carry no span identity, but still join the trace`() {
    traceRecorder.add(
      CompleteEvent(
        name = "direct",
        cat = "http",
        ts = kotlinx.datetime.Clock.System.now(),
        dur = kotlin.time.Duration.ZERO,
        pid = 1,
        tid = 1,
      ).toJsonObject(),
    )

    val event = eventNamed("direct")
    assertTrue("absent span identity must not be serialized as null", "sid" !in event.keys)
    assertTrue("psid" !in event.keys)
    // Without a trace id the event could not be merged with the spans it happened inside.
    assertEquals(traceRecorder.traceId(), event.str("trid"))
  }

  @Test
  fun `every span in a recording belongs to one trace`() {
    traceRecorder.trace("outer") { traceRecorder.trace("inner") { } }

    val traceIds = recordedEvents().map { it.str("trid") }.toSet()
    assertEquals(setOf(traceRecorder.traceId()), traceIds)
  }

  @Test
  fun `ids are hex of the OpenTelemetry widths, so they survive a merge with another process`() {
    traceRecorder.trace("outer") { traceRecorder.trace("inner") { } }

    val inner = eventNamed("inner")
    assertTrue("trace id was ${inner.str("trid")}", inner.str("trid")!!.matches(Regex("[0-9a-f]{32}")))
    assertTrue("span id was ${inner.str("sid")}", inner.str("sid")!!.matches(Regex("[0-9a-f]{16}")))
    assertEquals(inner.str("psid"), eventNamed("outer").str("sid"))
  }

  @Test
  fun `span ids never repeat, so a psid resolves to exactly one span`() {
    repeat(500) { i -> traceRecorder.trace("span-$i") { } }

    val ids = recordedEvents().mapNotNull { it.str("sid") }
    assertEquals(500, ids.size)
    assertEquals("every span id must be distinct", ids.size, ids.toSet().size)
  }

  @Test
  fun `span kind is recorded when it is not the in-process default`() {
    traceRecorder.trace("outgoing", kind = SpanKind.CLIENT) { }
    traceRecorder.trace("in-process") { }

    assertEquals("CLIENT", eventNamed("outgoing").str("kind"))
    // INTERNAL is the overwhelming majority of spans; writing it on each one is noise in a file
    // we hand to Perfetto.
    assertTrue("INTERNAL must stay out of the recorded JSON", "kind" !in eventNamed("in-process").keys)
  }

  @Test
  fun `clear starts a new trace so a stale parent cannot dangle`() {
    traceRecorder.trace("first-recording") { }
    val firstTrace = eventNamed("first-recording").str("trid")
    val firstSpan = eventNamed("first-recording").str("sid")
    TrailblazeTracer.clear()
    traceRecorder.trace("second-recording") { }

    val second = eventNamed("second-recording")
    assertTrue("a new recording must be a new trace", firstTrace != second.str("trid"))
    assertTrue("a new recording must not reuse span ids", firstSpan != second.str("sid"))
  }

  // -- trace levels --

  @Test
  fun `at OFF nothing is recorded, and the block still runs and returns`() {
    TrailblazeTracer.level = TraceLevel.OFF
    var ran = false

    val result = TrailblazeTracer.trace("tool") {
      ran = true
      "value"
    }

    assertTrue("the block must still run", ran)
    assertEquals("value", result)
    assertEquals(0, recordedEvents().size)
  }

  @Test
  fun `detail spans are off at NORMAL and on at VERBOSE`() {
    TrailblazeTracer.level = TraceLevel.NORMAL
    TrailblazeTracer.trace("tool") { TrailblazeTracer.traceDetail("driver-op") { } }
    assertEquals(listOf("tool"), recordedEvents().map { it.str("name") })

    TrailblazeTracer.clear()
    TrailblazeTracer.level = TraceLevel.VERBOSE
    TrailblazeTracer.trace("tool") { TrailblazeTracer.traceDetail("driver-op") { } }
    assertEquals(setOf("tool", "driver-op"), recordedEvents().mapNotNull { it.str("name") }.toSet())
  }

  @Test
  fun `turning detail off does not orphan the spans that remain`() {
    // The risk in gating a whole layer: a span nested INSIDE a suppressed one must reparent to the
    // nearest span that is still recorded, not lose its parent. A detail span that is not recorded
    // must not open a frame either.
    TrailblazeTracer.level = TraceLevel.NORMAL

    TrailblazeTracer.trace("tool") {
      TrailblazeTracer.traceDetail("driver-op") {
        TrailblazeTracer.trace("http") { }
      }
    }

    assertEquals(eventNamed("tool").str("sid"), eventNamed("http").str("psid"))
  }

  @Test
  fun `at VERBOSE a detail span becomes the parent of what it contains`() {
    TrailblazeTracer.level = TraceLevel.VERBOSE

    TrailblazeTracer.trace("tool") {
      TrailblazeTracer.traceDetail("driver-op") {
        TrailblazeTracer.trace("http") { }
      }
    }

    val detail = eventNamed("driver-op")
    assertEquals(eventNamed("tool").str("sid"), detail.str("psid"))
    assertEquals(detail.str("sid"), eventNamed("http").str("psid"))
  }

  @Test
  fun `a suspending detail span follows the same level gate`() = runBlocking {
    TrailblazeTracer.level = TraceLevel.NORMAL
    TrailblazeTracer.traceSuspend("tool") {
      TrailblazeTracer.traceDetailSuspend("capture") { delay(1) }
    }
    assertEquals(listOf("tool"), recordedEvents().map { it.str("name") })

    TrailblazeTracer.clear()
    TrailblazeTracer.level = TraceLevel.VERBOSE
    TrailblazeTracer.traceSuspend("tool") {
      TrailblazeTracer.traceDetailSuspend("capture") { delay(1) }
    }
    assertEquals(eventNamed("tool").str("sid"), eventNamed("capture").str("psid"))
  }

  @Test
  fun `a level configured by name is recognized, and a typo is not silently the default`() {
    assertEquals(TraceLevel.OFF, TraceLevel.parse("off"))
    assertEquals(TraceLevel.NORMAL, TraceLevel.parse(" Normal "))
    assertEquals(TraceLevel.VERBOSE, TraceLevel.parse("VERBOSE"))
    assertEquals(TraceLevel.VERBOSE, TraceLevel.parse("detailed"))
    // Null, not NORMAL: the caller warns about a typo, which is the difference between "I asked for
    // verbose and got nothing" costing a minute or an afternoon.
    assertNull(TraceLevel.parse("verbse"))
    assertNull(TraceLevel.parse(null))
    assertNull(TraceLevel.parse(""))
  }

  // -- The OFF gate, at the recorder --

  @Test
  fun `a producer that builds its own event records nothing when tracing is off`() {
    // The HTTP and Playwright paths. They never touch the TrailblazeTracer facade, so a gate that
    // lived only there left `off` recording every request an ordinary run makes.
    TrailblazeTracer.level = TraceLevel.OFF
    try {
      traceRecorder.add(
        CompleteEvent(
          name = "POST https://example/v1", cat = "http",
          ts = kotlinx.datetime.Clock.System.now(), dur = kotlin.time.Duration.ZERO,
          pid = 1, tid = 2,
        ).toJsonObject(),
      )
      assertEquals(0, recordedEvents().size)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `a caller using the recorder directly records nothing when tracing is off`() {
    // The driver wrappers and the LLM client, which call traceRecorder.trace rather than the facade.
    TrailblazeTracer.level = TraceLevel.OFF
    try {
      var ran = false
      traceRecorder.trace("driver-op") { ran = true }
      assertEquals(0, recordedEvents().size)
      assertTrue("the work must still happen — this gates the recording, not the run", ran)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `a suspending recorder call records nothing when tracing is off`() = runBlocking {
    TrailblazeTracer.level = TraceLevel.OFF
    try {
      var ran = false
      traceRecorder.traceSuspend("driver-op") { ran = true }
      assertEquals(0, recordedEvents().size)
      assertTrue("the work must still happen", ran)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `a suspending span installs no coroutine context when tracing is off`() = runBlocking {
    // What makes the OFF gate on traceSuspend more than an optimization: `withContext` is the
    // expensive part of a suspending span, and skipping it is observable as the absence of the
    // element a recorded span would have published.
    TrailblazeTracer.level = TraceLevel.OFF
    try {
      var observed: String? = "sentinel"
      traceRecorder.traceSuspend("driver-op") {
        observed = kotlin.coroutines.coroutineContext[TraceSpanContextElement.Key]?.spanId
      }
      assertNull(observed)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `a span opens no frame when tracing is off`() {
    // Same for the non-suspending gate: it must not touch the nesting state, or a later recorded
    // span would nest under something that was never recorded.
    TrailblazeTracer.level = TraceLevel.OFF
    try {
      val before = TraceSpanLocal.get()?.spanId
      var inside: String? = "sentinel"
      traceRecorder.trace("driver-op") { inside = TraceSpanLocal.get()?.spanId }
      assertEquals(before, inside)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `setting the level through the facade reaches the recorder`() {
    // They must not be able to disagree: the facade is what a daemon flips at runtime, the recorder
    // is what actually decides.
    TrailblazeTracer.level = TraceLevel.VERBOSE
    try {
      assertEquals(TraceLevel.VERBOSE, traceRecorder.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  // -- Reading the configured level --

  @Test
  fun `the system property wins over the environment`() {
    // Documented to users, and the reason the property exists: one run overriding the shell it
    // inherited.
    assertEquals(
      TraceLevel.VERBOSE,
      resolveTraceLevel(property = "verbose", env = "off", warn = { }),
    )
  }

  @Test
  fun `the environment is used when no property is set`() {
    assertEquals(TraceLevel.OFF, resolveTraceLevel(property = null, env = "off", warn = { }))
  }

  @Test
  fun `an unrecognized value warns instead of silently defaulting`() {
    // A typo that quietly means "default" is how a run ends up with no detail and nobody knowing why.
    val warnings = mutableListOf<String>()
    val level = resolveTraceLevel(property = "verbse", env = null, warn = { warnings += it })

    assertEquals(TraceLevel.NORMAL, level)
    assertEquals(1, warnings.size)
    assertTrue("the warning must name the value: ${warnings.first()}", warnings.first().contains("verbse"))
  }

  @Test
  fun `nothing configured is silent`() {
    val warnings = mutableListOf<String>()
    assertEquals(TraceLevel.NORMAL, resolveTraceLevel(property = null, env = null, warn = { warnings += it }))
    assertEquals(0, warnings.size)
  }

  @Test
  fun `a blank value is absent, not a typo`() {
    // An exported-but-empty variable is how a shell says nothing; warning about it would cry wolf on
    // every run in that shell.
    val warnings = mutableListOf<String>()
    assertEquals(TraceLevel.NORMAL, resolveTraceLevel(property = "  ", env = null, warn = { warnings += it }))
    assertEquals(0, warnings.size)
  }

  @Test
  fun `a blank property falls through to the environment`() {
    assertEquals(TraceLevel.VERBOSE, resolveTraceLevel(property = "", env = "verbose", warn = { }))
  }

  @Test
  fun `a requested level applies for one run and is handed back after`() {
    // The daemon case: the process starts at one level and serves a run that asked for another.
    // Both halves matter — the run has to actually get what it asked for, and the next run has to
    // not inherit it.
    TrailblazeTracer.level = TraceLevel.NORMAL
    try {
      val duringRun = TrailblazeTracer.withLevel(TraceLevel.VERBOSE) { TrailblazeTracer.level }
      assertEquals(TraceLevel.VERBOSE, duringRun)
      assertEquals(TraceLevel.NORMAL, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `no requested level leaves the process alone`() {
    // A caller that sends nothing is not a caller asking for the default: a daemon whose level was
    // turned up for an investigation keeps it when an older CLI, or an MCP client, submits a run.
    TrailblazeTracer.level = TraceLevel.VERBOSE
    try {
      val duringRun = TrailblazeTracer.withLevel(null) { TrailblazeTracer.level }
      assertEquals(TraceLevel.VERBOSE, duringRun)
      assertEquals(TraceLevel.VERBOSE, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `the level is handed back even when the run fails`() {
    // A failing run is the common case for the one you turned verbose on for. If the restore rode
    // on a normal return, that failure would leave every later run verbose.
    TrailblazeTracer.level = TraceLevel.NORMAL
    try {
      var threw = false
      try {
        TrailblazeTracer.withLevel(TraceLevel.VERBOSE) { error("run failed") }
      } catch (_: IllegalStateException) {
        threw = true
      }
      assertTrue(threw)
      assertEquals(TraceLevel.NORMAL, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `overlapping runs record at the most verbose level any of them asked for`() {
    // A daemon runs trails concurrently and there is one level for the process. A run that asked for
    // verbose and got normal has lost spans nobody can get back; a run that asked for off and got
    // normal has a slightly larger trace. So the resolution goes upward.
    TrailblazeTracer.level = TraceLevel.NORMAL
    try {
      TrailblazeTracer.withLevel(TraceLevel.VERBOSE) {
        val whileBothRunning = TrailblazeTracer.withLevel(TraceLevel.OFF) { TrailblazeTracer.level }
        assertEquals(TraceLevel.VERBOSE, whileBothRunning)
      }
      assertEquals(TraceLevel.NORMAL, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `the run that finishes first does not take the level away from one still going`() {
    // Save-and-restore per run gets this backwards: the first to finish reinstates the process
    // default while the other run is mid-trail, and that run silently stops recording.
    TrailblazeTracer.level = TraceLevel.NORMAL
    val stillGoingStarted = java.util.concurrent.CountDownLatch(1)
    val firstFinished = java.util.concurrent.CountDownLatch(1)
    val levelAfterTheOtherFinished = java.util.concurrent.atomic.AtomicReference<TraceLevel>()
    try {
      val stillGoing = Thread {
        TrailblazeTracer.withLevel(TraceLevel.VERBOSE) {
          stillGoingStarted.countDown()
          firstFinished.await()
          levelAfterTheOtherFinished.set(TrailblazeTracer.level)
        }
      }
      stillGoing.start()
      stillGoingStarted.await()

      TrailblazeTracer.withLevel(TraceLevel.OFF) { }
      firstFinished.countDown()
      stillGoing.join()

      assertEquals(TraceLevel.VERBOSE, levelAfterTheOtherFinished.get())
      assertEquals(TraceLevel.NORMAL, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `the daemon default comes back whatever order the runs finish in`() {
    // The other half of the same defect: whoever finishes last restoring "the level when I started"
    // leaves the daemon sitting at some run's level, so every later run inherits it.
    TrailblazeTracer.level = TraceLevel.NORMAL
    try {
      val outerFinished = java.util.concurrent.CountDownLatch(1)
      val innerStarted = java.util.concurrent.CountDownLatch(1)
      val inner = Thread {
        TrailblazeTracer.withLevel(TraceLevel.OFF) {
          innerStarted.countDown()
          outerFinished.await()
        }
      }
      TrailblazeTracer.withLevel(TraceLevel.VERBOSE) {
        inner.start()
        innerStarted.await()
      }
      outerFinished.countDown()
      inner.join()

      assertEquals(TraceLevel.NORMAL, TrailblazeTracer.level)
    } finally {
      TrailblazeTracer.level = TraceLevel.NORMAL
    }
  }

  @Test
  fun `the level a run asks for survives the round trip through the wire`() {
    // The daemon receives a string, not the enum, and parses it. Every level this CLI could stamp
    // has to come back as itself — a level that decoded to null would silently mean "not
    // requested", which is the bug this whole path exists to fix.
    TraceLevel.entries.forEach { level ->
      assertEquals(level, TraceLevel.parse(level.name.lowercase()))
    }
  }

  @Test
  fun `the jvm actual reads the system property`() {
    val previous = System.getProperty(TRACE_LEVEL_PROPERTY)
    try {
      System.setProperty(TRACE_LEVEL_PROPERTY, "verbose")
      assertEquals(TraceLevel.VERBOSE, configuredTraceLevel())
    } finally {
      if (previous == null) System.clearProperty(TRACE_LEVEL_PROPERTY) else System.setProperty(TRACE_LEVEL_PROPERTY, previous)
    }
  }

  // -- Draining a flush --

  @Test
  fun `draining hands over everything recorded and leaves the recorder empty`() {
    traceRecorder.trace("first") { }
    traceRecorder.trace("second") { }

    val drained = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(traceRecorder.drain())

    assertEquals(2, drained.size)
    assertEquals(0, recordedEvents().size)
  }

  @Test
  fun `a session that flushes twice files both halves under one trace`() {
    // What `toJson()` then `clear()` could not do. A run exports whenever the exporter is asked to —
    // and if the second flush starts a new trace, the two halves of one run become two unrelated
    // traces, with no way to reunite them and no way to resolve a span whose parent was recorded
    // before the flush.
    traceRecorder.trace("before-flush") { }
    val first = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(traceRecorder.drain())

    traceRecorder.trace("after-flush") { }
    val second = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(traceRecorder.drain())

    val firstTrid = first.single().jsonObject.str("trid")
    assertNotNull("a flushed span must still name its trace", firstTrid)
    assertEquals(firstTrid, second.single().jsonObject.str("trid"))
  }

  @Test
  fun `a span whose parent was flushed in an earlier batch still resolves against it`() {
    // The case a merged file has to survive: parent in one batch, child in the next. The child names
    // the parent by id, so the two batches must also agree on the TRACE — a `psid` that resolves only
    // within a trace the parent does not belong to is an orphan.
    var childSpanId: String? = null
    var parentEvent: JsonObject? = null
    traceRecorder.trace("outer") {
      traceRecorder.trace("inner-before-flush") { }
      parentEvent = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(traceRecorder.drain())
        .map { it.jsonObject }
        .single { it.str("name") == "inner-before-flush" }
      traceRecorder.trace("inner-after-flush") {
        childSpanId = TraceSpanLocal.get()?.spanId
      }
    }

    val child = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(traceRecorder.drain())
      .map { it.jsonObject }
      .single { it.str("name") == "inner-after-flush" }
    assertEquals(childSpanId, child.str("sid"))
    assertEquals("both batches must be the same trace", parentEvent!!.str("trid"), child.str("trid"))
    assertEquals(parentEvent!!.str("psid"), child.str("psid"))
  }

  @Test
  fun `clearing still starts a new trace`() {
    // Draining is for a flush mid-run; clearing is for a genuinely new recording. Collapsing the two
    // is what made a flush rename the trace in the first place.
    traceRecorder.trace("run-one") { }
    val one = recordedEvents().single().str("trid")

    TrailblazeTracer.clear()
    traceRecorder.trace("run-two") { }

    assertTrue("a cleared recorder must start a new trace", one != recordedEvents().single().str("trid"))
  }

  // -- Joining the trace another process started --

  private val dispatched = TraceContext(
    traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
    spanId = "00f067aa0ba902b7",
  )

  @Test
  fun `a joined recording records into the trace it was handed`() {
    // The whole point: the device's half of a run has to carry the host's trace id, or the two
    // halves of one run arrive in a viewer as two unrelated traces.
    TrailblazeTracer.joinTrace(dispatched)

    traceRecorder.trace("captureHierarchy") { }

    assertEquals(dispatched.traceId, eventNamed("captureHierarchy").str("trid"))
  }

  @Test
  fun `a span with nothing above it hangs under the span that dispatched the work`() {
    TrailblazeTracer.joinTrace(dispatched)

    traceRecorder.trace("captureHierarchy") { }

    assertEquals(dispatched.spanId, eventNamed("captureHierarchy").str("psid"))
  }

  @Test
  fun `a nested span still parents to its enclosing span, not to the dispatcher`() {
    // Joining supplies the root that was missing; it must not flatten this process's own tree.
    TrailblazeTracer.joinTrace(dispatched)

    traceRecorder.trace("captureHierarchy") {
      traceRecorder.trace("awaitStable") { }
    }

    val outer = eventNamed("captureHierarchy")
    val inner = eventNamed("awaitStable")
    assertEquals(outer.str("sid"), inner.str("psid"))
    assertTrue(
      "only the outermost span inherits the dispatcher",
      inner.str("psid") != dispatched.spanId,
    )
  }

  @Test
  fun `joining a different trace than the one joined empties the recording`() {
    // A prior JOIN is what makes a different id mean a different run. This recorder was recording
    // run one's dispatches; the id that just arrived names a run it was never part of, so keeping
    // the old spans would file one recording under both trace ids.
    TrailblazeTracer.joinTrace(dispatched)
    traceRecorder.trace("previous-run") { }

    val nextRun = TraceContext(
      traceId = "af7651916cd43dd8448eb211c80319c1",
      spanId = "b7ad6b7169203331",
    )
    TrailblazeTracer.joinTrace(nextRun)
    traceRecorder.trace("this-run") { }

    assertEquals(listOf("this-run"), recordedEvents().map { it.str("name") })
    assertEquals(setOf(nextRun.traceId), recordedEvents().mapNotNull { it.str("trid") }.toSet())
  }

  @Test
  fun `spans traced before the first join survive it`() {
    // A self-minted trace id never equals any host's, so the first dispatch to carry a traceparent
    // always presents a "different" id — but the spans recorded before it belong to the run being
    // dispatched, not a previous one, and nothing has uploaded them yet. They are kept, and the
    // recording then holds both ids: a split trace names both halves, lost spans name nothing.
    traceRecorder.trace("before-any-join") { }

    TrailblazeTracer.joinTrace(dispatched)
    traceRecorder.trace("after-the-join") { }

    assertEquals(listOf("before-any-join", "after-the-join"), recordedEvents().map { it.str("name") })
    assertEquals(dispatched.traceId, eventNamed("after-the-join").str("trid"))
    assertTrue(eventNamed("before-any-join").str("trid") != dispatched.traceId)
  }

  @Test
  fun `re-joining the same trace keeps what is recorded and re-points the parent`() {
    // A host dispatches each tool call separately, and the device joins on every one. Emptying on
    // a join it is already inside would throw away every earlier dispatch's spans, since the
    // device uploads its trace once at the end of the session, not once per dispatch.
    TrailblazeTracer.joinTrace(dispatched)
    traceRecorder.trace("first-dispatch") { }

    val secondSpan = "b7ad6b7169203331"
    TrailblazeTracer.joinTrace(dispatched.copy(spanId = secondSpan))
    traceRecorder.trace("second-dispatch") { }

    assertEquals(listOf("first-dispatch", "second-dispatch"), recordedEvents().map { it.str("name") })
    assertEquals(dispatched.spanId, eventNamed("first-dispatch").str("psid"))
    assertEquals(secondSpan, eventNamed("second-dispatch").str("psid"))
  }

  @Test
  fun `a dispatch naming no trace stops inheriting the last one's`() {
    // The server outlives the runs it serves. Once a run whose host was recording is over, the next
    // run's work must not land in its trace, under a parent span that only existed in that host.
    TrailblazeTracer.joinTrace(dispatched)
    traceRecorder.trace("traced-run") { }

    TrailblazeTracer.joinTrace(null)
    traceRecorder.trace("untraced-run") { }

    val untraced = eventNamed("untraced-run")
    assertNull(untraced.str("psid"))
    assertNotNull(untraced.str("trid"))
    assertTrue(untraced.str("trid") != dispatched.traceId)
    // The finished run's spans are still here: nothing has uploaded them yet.
    assertEquals(dispatched.traceId, eventNamed("traced-run").str("trid"))
  }

  @Test
  fun `a recorder that never joined keeps one trace id across dispatches`() {
    // A device with no host trace to join is told so on every dispatch. Treating that as a boundary
    // would mint a trace id per dispatch and shatter the device's own session into one trace each.
    TrailblazeTracer.joinTrace(null)
    traceRecorder.trace("first") { }
    TrailblazeTracer.joinTrace(null)
    traceRecorder.trace("second") { }

    assertEquals(1, recordedEvents().mapNotNull { it.str("trid") }.toSet().size)
  }

  @Test
  fun `a traced run after an untraced one still starts its recording fresh`() {
    // traced -> untraced -> traced on a long-lived server. The untraced run's null join clears the
    // dispatch parent but must not make the recording "never joined": the third run names a trace
    // this recording was never part of, and keeping the first two runs' events would stack three
    // runs into its upload.
    TrailblazeTracer.joinTrace(dispatched)
    traceRecorder.trace("first-traced-run") { }

    TrailblazeTracer.joinTrace(null)
    traceRecorder.trace("untraced-run") { }

    val thirdRun = TraceContext(
      traceId = "af7651916cd43dd8448eb211c80319c1",
      spanId = "b7ad6b7169203331",
    )
    TrailblazeTracer.joinTrace(thirdRun)
    traceRecorder.trace("second-traced-run") { }

    assertEquals(listOf("second-traced-run"), recordedEvents().map { it.str("name") })
    assertEquals(setOf(thirdRun.traceId), recordedEvents().mapNotNull { it.str("trid") }.toSet())
  }

  @Test
  fun `clearing re-arms the first-join grace`() {
    // After a clear the recording is fresh, so its own pre-traceparent spans deserve to survive its
    // first join — the same grace a fresh recorder gets. A join remembered across the clear would
    // erase them.
    TrailblazeTracer.joinTrace(dispatched)
    TrailblazeTracer.clear()

    traceRecorder.trace("pre-traceparent") { }
    TrailblazeTracer.joinTrace(dispatched.copy(traceId = "af7651916cd43dd8448eb211c80319c1"))
    traceRecorder.trace("post-traceparent") { }

    assertEquals(listOf("pre-traceparent", "post-traceparent"), recordedEvents().map { it.str("name") })
  }

  @Test
  fun `clearing forgets the dispatching span as well as the trace`() {
    // A cleared recorder is a new run. Inheriting the previous run's dispatcher would hang this
    // run's spans under a parent that belongs to a trace nothing here records into.
    TrailblazeTracer.joinTrace(dispatched)
    TrailblazeTracer.clear()

    traceRecorder.trace("later-run") { }

    val event = eventNamed("later-run")
    assertNull("a new run must not inherit the old dispatcher", event.str("psid"))
    assertTrue("a new run must mint its own trace", event.str("trid") != dispatched.traceId)
  }

  @Test
  fun `a traceparent survives the round trip`() {
    val parsed = TraceContext.parse(dispatched.toTraceParent())

    assertEquals(dispatched, parsed)
    assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", dispatched.toTraceParent())
  }

  @Test
  fun `a traceparent this cannot place is refused rather than half-read`() {
    // Null, not a partial context: the caller's fallback is to record a trace of its own, which is
    // still a usable profile of its own half. Half-reading it would hang spans off an id that
    // resolves to nothing.
    assertNull(TraceContext.parse(null))
    assertNull("no field at all", TraceContext.parse(""))
    assertNull("missing the flags", TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7"))
    assertNull("a version whose layout we have not seen", TraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
    assertNull("trace id too short", TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e473-00f067aa0ba902b7-01"))
    assertNull("span id too short", TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba9-01"))
    assertNull("not hex", TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01"))
    assertNull("uppercase is not the lowercase-hex the spec defines", TraceContext.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"))
    // All-zero is OpenTelemetry's invalid id, so it is refused rather than joined.
    assertNull(TraceContext.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
    assertNull(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"))
  }

  @Test
  fun `an id this recorder mints is a traceparent the other end can read`() {
    // The two halves of this feature are minted and parsed by different processes, so the shape has
    // to match without either side normalising it.
    traceRecorder.trace("host-tool") { }
    val recorded = recordedEvents().single()

    val context = TraceContext(
      traceId = recorded.str("trid")!!,
      spanId = recorded.str("sid")!!,
    )

    assertEquals(context, TraceContext.parse(context.toTraceParent()))
  }
}
