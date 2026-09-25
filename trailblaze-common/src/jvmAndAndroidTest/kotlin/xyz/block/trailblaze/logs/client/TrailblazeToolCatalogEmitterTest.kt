package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The dedup registry each request-log producer keeps.
 *
 * What matters here is what a reader of a session directory can rely on: a catalog exists before
 * anything references it, it is written once, and a process that never stops does not grow without
 * bound because of it.
 */
class TrailblazeToolCatalogEmitterTest {

  private fun descriptor(name: String) = TrailblazeToolDescriptor(
    name = name,
    description = "does $name",
    requiredParameters = listOf(
      TrailblazeToolParameterDescriptor(name = "ref", description = "element ref", type = "STRING"),
    ),
  )

  private val tapAndSwipe = listOf(descriptor("swipe"), descriptor("tap"))
  private val tapOnly = listOf(descriptor("tap"))

  private fun TrailblazeToolCatalogEmitter.emit(
    session: String,
    tools: List<TrailblazeToolDescriptor>,
    sink: MutableList<TrailblazeLog.TrailblazeToolCatalogLog>,
  ) = emitIfNew(SessionId(session), tools, Clock.System.now()) { sink.add(it) }

  @Test
  fun `an unchanged toolset is written once and every caller gets the same id`() {
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    val ids = (1..5).map { emitter.emit("s", tapAndSwipe, written) }

    assertEquals("the catalog must be written exactly once", 1, written.size)
    assertEquals("every caller must get the same id", 1, ids.toSet().size)
    assertEquals(written.single().toolCatalogId, ids.first())
  }

  @Test
  fun `a request offered no tools writes no catalog and gets no id`() {
    // A text-only sampling call passes no descriptors. An empty catalog would add a log per
    // session that resolves to exactly what the absent id already means.
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    val id = emitter.emit("s", emptyList(), written)

    assertTrue("no catalog should have been written", written.isEmpty())
    assertEquals("an empty toolset has no catalog to point at", null, id)
  }

  @Test
  fun `a changed toolset writes its own catalog and returning to the first re-uses it`() {
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    val first = emitter.emit("s", tapAndSwipe, written)
    val second = emitter.emit("s", tapOnly, written)
    val third = emitter.emit("s", tapAndSwipe, written)

    assertEquals("A→B→A must write two catalogs, not three", 2, written.size)
    assertNotEquals(first, second)
    assertEquals("the return to A must resolve back to A's catalog", first, third)
  }

  @Test
  fun `each session writes its own catalog even for an identical toolset`() {
    // A catalog id only means something next to the session whose log directory holds it. Deduping
    // on the id alone would let the second session reference a catalog it never wrote.
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    val first = emitter.emit("session_one", tapAndSwipe, written)
    val second = emitter.emit("session_two", tapAndSwipe, written)

    assertEquals(2, written.size)
    assertEquals("the same toolset must hash the same in both", first, second)
    assertEquals(
      listOf("session_one", "session_two"),
      written.map { it.session.value },
    )
  }

  @Test
  fun `a concurrent caller cannot proceed until the catalog has actually been written`() {
    // The reason claim and write share a lock. If the claim were released first, the loser would
    // be told "already written" while the winner was still inside `emit`, and could persist its
    // request log ahead of the catalog it points at.
    val emitter = TrailblazeToolCatalogEmitter()
    val writeStarted = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    val catalogFullyWritten = AtomicInteger(0)
    val observedUnwritten = AtomicInteger(0)

    val winner = thread {
      emitter.emitIfNew(SessionId("s"), tapAndSwipe, Clock.System.now()) {
        writeStarted.countDown()
        // Hold the write open so a second caller has every chance to slip past it.
        releaseWriter.await(5, TimeUnit.SECONDS)
        catalogFullyWritten.incrementAndGet()
      }
    }

    assertTrue("writer never started", writeStarted.await(5, TimeUnit.SECONDS))
    val loser = thread {
      emitter.emitIfNew(SessionId("s"), tapAndSwipe, Clock.System.now()) { }
      // Whatever this caller does next (write its request log) must happen after the catalog.
      if (catalogFullyWritten.get() == 0) observedUnwritten.incrementAndGet()
    }
    // Give the loser a real chance to return early if the lock were not held across the write.
    Thread.sleep(200)
    releaseWriter.countDown()
    winner.join(5_000)
    loser.join(5_000)

    assertEquals("the catalog must have been written exactly once", 1, catalogFullyWritten.get())
    assertEquals(
      "a caller was told the catalog existed before it had been written",
      0,
      observedUnwritten.get(),
    )
  }

  @Test
  fun `concurrent callers across threads still write exactly one catalog`() {
    val emitter = TrailblazeToolCatalogEmitter()
    val written = java.util.Collections.synchronizedList(mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>())
    val start = CountDownLatch(1)

    val threads = (1..16).map {
      thread {
        start.await(5, TimeUnit.SECONDS)
        emitter.emitIfNew(SessionId("s"), tapAndSwipe, Clock.System.now()) { written.add(it) }
      }
    }
    start.countDown()
    threads.forEach { it.join(5_000) }

    assertEquals("16 racing callers must still produce one catalog", 1, written.size)
  }

  @Test
  fun `the registry stays bounded as sessions accumulate`() {
    // Nothing gives this emitter a session-ended signal — MCP sampling has none — and a daemon
    // keeps one for its lifetime, so the bound is what stops unbounded growth.
    val emitter = TrailblazeToolCatalogEmitter(maxRetainedSessions = 4)
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    repeat(50) { emitter.emit("session_$it", tapAndSwipe, written) }

    assertEquals("each new session writes its own catalog", 50, written.size)
    assertTrue(
      "registry grew past its bound: ${emitter.retainedSessionCount()}",
      emitter.retainedSessionCount() <= 4,
    )
  }

  @Test
  fun `an evicted session re-writes its catalog rather than losing it`() {
    // Eviction has to be safe, not merely rare: a session that outlives its registry entry must
    // still end up with a resolvable catalog, even at the cost of a duplicate log.
    val emitter = TrailblazeToolCatalogEmitter(maxRetainedSessions = 2)
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    val firstId = emitter.emit("long_running", tapAndSwipe, written)
    repeat(5) { emitter.emit("short_$it", tapOnly, written) }
    val afterEviction = emitter.emit("long_running", tapAndSwipe, written)

    assertEquals("the id must not change across eviction", firstId, afterEviction)
    val forLongRunning = written.filter { it.session.value == "long_running" }
    assertEquals("the evicted session must re-write its catalog", 2, forLongRunning.size)
    assertEquals(
      "the duplicate must carry the same descriptors, so resolving by id is unaffected",
      forLongRunning[0].toolOptions,
      forLongRunning[1].toolOptions,
    )
  }

  @Test
  fun `an active session is not evicted by a burst of short ones`() {
    // Access-ordered rather than insertion-ordered: the session still making requests is the one
    // worth keeping, and re-writing its catalog on every request would defeat the whole split.
    val emitter = TrailblazeToolCatalogEmitter(maxRetainedSessions = 3)
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()

    emitter.emit("active", tapAndSwipe, written)
    repeat(10) {
      emitter.emit("short_$it", tapOnly, written)
      emitter.emit("active", tapAndSwipe, written) // keeps touching the active session
    }

    assertEquals(
      "the active session's catalog must have been written once",
      1,
      written.count { it.session.value == "active" },
    )
  }

  @Test
  fun `a catalog whose write failed is written again on the next request`() {
    // The claim means "this catalog is on disk", so a failed write must not make one. The MCP
    // sampling producer reaches this directly: when a request throws, its error path logs the
    // same request over again, and a stuck claim would leave that retry pointing at a catalog
    // nobody ever wrote — the session resolves no tools for every request after it.
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()
    var failNextWrite = true

    runCatching {
      emitter.emitIfNew(SessionId("session"), tapAndSwipe, Clock.System.now()) {
        if (failNextWrite) throw IllegalStateException("disk full")
        written.add(it)
      }
    }
    failNextWrite = false
    emitter.emit("session", tapAndSwipe, written)

    assertEquals("the retry must write the catalog it claimed", 1, written.size)
    assertEquals(tapAndSwipe, written.single().toolOptions)
  }

  @Test
  fun `a failed write is reported rather than swallowed`() {
    // Rolling the claim back must not turn a write failure into a silent success — the caller
    // decides what a lost log means, and the producers already have error paths for it.
    val emitter = TrailblazeToolCatalogEmitter()

    val thrown = runCatching {
      emitter.emitIfNew(SessionId("session"), tapAndSwipe, Clock.System.now()) {
        throw IllegalStateException("disk full")
      }
    }.exceptionOrNull()

    assertEquals("disk full", thrown?.message)
  }

  @Test
  fun `a catalog reported as missed during its own write is written again on the next request`() {
    // The on-device sink never throws: a failed upload falls back to the device's disk, so the
    // catalog lands where the requests after it do not. The sink reports that while the write is
    // still in progress, which is the one moment the claim is being made.
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()
    var missNextWrite = true

    repeat(2) {
      emitter.emitIfNew(SessionId("session"), tapAndSwipe, Clock.System.now()) {
        written.add(it)
        if (missNextWrite) {
          missNextWrite = false
          emitter.forget(it.session, it.toolCatalogId)
        }
      }
    }
    emitter.emit("session", tapAndSwipe, written)

    assertEquals("the missed catalog is written once more, then deduped again", 2, written.size)
  }

  @Test
  fun `forgetting a catalog in one session leaves another session's claim alone`() {
    val emitter = TrailblazeToolCatalogEmitter()
    val written = mutableListOf<TrailblazeLog.TrailblazeToolCatalogLog>()
    val id = emitter.emit("kept", tapAndSwipe, written)!!
    emitter.emit("missed", tapAndSwipe, written)

    emitter.forget(SessionId("missed"), id)
    emitter.emit("kept", tapAndSwipe, written)

    assertEquals("the untouched session must still dedupe", 2, written.size)
  }
}
