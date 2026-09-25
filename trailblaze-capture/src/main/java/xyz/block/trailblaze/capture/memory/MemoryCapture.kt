package xyz.block.trailblaze.capture.memory

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.ToolCallAwareCaptureStream
import xyz.block.trailblaze.capture.ToolCallPhase
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.util.Console

/**
 * Tracks the app under test's memory for the life of a session and records it as the `memory`
 * session-event stream (`<sessionDir>/events/memory.ndjson`).
 *
 * Two clocks drive it:
 *  - **Every tool call.** The host agent loop tells the capture when a tool is about to run and when
 *    it finished ([onToolCall]); both moments are always written, so the `delta` on the `after_tool`
 *    row is what that one action cost. Every navigation the trail performs is a tool call, so this
 *    is also "sample on every navigation".
 *  - **A cron.** Every [sampleIntervalMs] a reading is taken and written only when
 *    [MemoryChangeDetector] says something moved — an allocation spike between tools, or the
 *    process dying. The first and last readings are always written so the report has both bookends.
 *
 * A reading costs real time (measured: ~50 ms over adb, ~116 ms through the on-device runner,
 * ~207 ms on the iOS Simulator, each about 65 ms more with a forced GC), and a
 * trail makes hundreds of tool calls, so by default NO reading runs on the tool's own thread:
 * [onToolCall] hands the request to the one background worker that also runs the cron and returns
 * at once. The event keeps the boundary's timestamp and reason; the reading itself lands a
 * fraction of a second later, which is why a default-mode `before_tool` row is "around the start
 * of the tool", not strictly before it. Boundaries that arrive while a reading is in flight wait
 * their turn in a short queue, so a run of instant tools still gets both halves of each pair; only
 * once [MAX_PENDING_REQUESTS] have banked up — a device that has stopped answering — is the oldest
 * dropped, and the stop summary says how many.
 *
 * With [synchronousToolSamples] on (the diagnostics mode) the tool thread takes the reading itself
 * and waits for it, so the pair is an exact before/after at the cost of two readings per tool
 * call. The cron stands down for the length of each tool call in that mode: a tick landing between
 * a tool's two readings would force a GC in the middle of the action and put its own row between
 * the pair, which is exactly what the mode exists to avoid. Nothing is lost, because every tool
 * boundary is already sampled. [forceGc] pairs with it: every reading first asks the app to collect
 * garbage when the probe can (see [MemoryProbe.read]), so the heap figure is live objects only.
 * Each event records whether that happened (`gcForced`) and how long the reading took (`readMs`).
 *
 * In the default mode the cron keeps running through a tool call, so a tool long enough to span a
 * tick can have a `memory_changed` row between its two boundary rows — worth having, since it says
 * the allocation happened during that action, but it does mean the `after_tool` delta is against
 * that row rather than against `before_tool`. Only one reading is ever in flight either way.
 *
 * The app's pid is re-resolved on every pass: at session start the trail usually has not launched
 * the app yet, and a force-stop + relaunch mid-trail changes the pid. Both show up as events
 * (`process_started`, `process_restarted`, `process_died`) rather than silently ending sampling.
 *
 * Each event is deliberately small — heap used, the heap limit, whether a GC ran, and the
 * movement since the last event (see [MemorySnapshot.toEventPayload]); the probes read the full
 * dump into [MemorySnapshot], so a richer event is a mapping change, not a new reader.
 *
 * Writes the same event-stream contract every other producer uses, so the HTML report, the Trail
 * Runner's Artifacts panel and CI's session zip pick it up with no viewer change; the bundled
 * `memory.formatter.ts` turns each row into a one-line summary.
 *
 * [probe] and [clock] are injectable so the sampling/emit policy is unit-tested without a device.
 */
class MemoryCapture(
  private val probe: MemoryProbe,
  private val clock: () -> Long = System::currentTimeMillis,
  private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
  private val forceGc: Boolean = false,
  private val synchronousToolSamples: Boolean = false,
  /** Injectable only so a test can reach the ceiling without writing five thousand events. */
  private val maxEvents: Int = MAX_EVENTS,
) : CaptureStream, ToolCallAwareCaptureStream {
  override val type = CaptureType.MEMORY

  /** A reading somebody asked for: a tool boundary (with its time, reason and trace) or the cron. */
  private class Request(val reason: String?, val tool: String?, val traceId: String?, val timeMs: Long)

  private val lock = ReentrantLock()
  private val wakeUp = lock.newCondition()

  /**
   * Held across the device read, so only one reading is ever in flight. The default mode has one
   * reader thread and never contends; the diagnostics mode adds the tool thread, and the probes are
   * not built for two callers — they keep per-app state, remember a pid between readings, and on
   * Android a reading holds a device-side monitor that a second one would just queue behind.
   */
  private val readLock = ReentrantLock()

  /**
   * Diagnostics mode only: set for the length of a tool call so the cron does not read across it.
   * Written by the tool thread, read by the worker. The agent loop reports the `after` phase from a
   * `finally`, so a tool that throws still clears this.
   */
  @Volatile private var toolInFlight = false
  private var sink: FileEventSink? = null
  private var deviceId: String? = null
  private var appId: String? = null
  private var lastEmitted: MemorySnapshot? = null
  private var emittedCount = 0
  private var sampledCount = 0
  private var coalescedCount = 0

  /** Readings that came back with nothing, and how many in a row — see [noteUnreadable]. */
  private var unreadableCount = 0
  private var consecutiveUnreadable = 0

  /** Readings that threw, so a probe failing on every tick logs a handful of lines, not hundreds. */
  private var failureCount = 0

  /** Set once the ceiling has been announced, so the announcement is not also counted as an event. */
  private var ceilingLogged = false

  /**
   * Boundary requests waiting for the reader, oldest first.
   *
   * A queue rather than a single slot because a tool that finishes faster than a reading takes is
   * ordinary, and with one slot its `before`, its `after` and the NEXT tool's `before` all collapse
   * into whichever arrived last: that tool gets no rows at all, and the surviving `after_tool` row
   * still carries a tool name and a delta measured against something much older. Bounded because a
   * device that has stopped answering must not bank an unbounded backlog of reads.
   */
  private val pending = ArrayDeque<Request>()

  /** How many boundary requests are waiting. Internal so a test can assert the queue's bound. */
  internal fun pendingRequestCount(): Int = lock.withLock { pending.size }
  @Volatile private var stopped = false
  private var worker: Thread? = null

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    if (appId == null) {
      // The events are about one app's heap; with no app id there is nothing worth writing.
      // `info`, not `log`: a normal CLI run is quiet, and this is the answer to "why does my run
      // have no memory data" — a line nobody can see does not make anything diagnosable.
      Console.info("Not sampling memory on $deviceId: the target declares no app id for this platform")
      return
    }
    lock.withLock {
      this.deviceId = deviceId
      this.appId = appId
      lastEmitted = null
      emittedCount = 0
      sampledCount = 0
      coalescedCount = 0
      unreadableCount = 0
      consecutiveUnreadable = 0
      failureCount = 0
      ceilingLogged = false
      pending.clear()
      toolInFlight = false
      stopped = false
      sink = FileEventSink(sessionDir, logLabel = "memory-capture")
    }
    val mode = buildString {
      append(if (synchronousToolSamples) "synchronous readings around every tool call" else "readings around every tool call in the background")
      append(if (forceGc) ", forcing a GC before each reading" else ", no forced GC")
    }
    // `info` so it survives a quiet CLI run: this line and the stop summary are how a reader finds
    // out memory capture ran at all.
    Console.info(
      "Sampling memory for $appId every ${sampleIntervalMs}ms, $mode → " +
        "${SessionEvents.DIR_NAME}/${SessionEvents.fileName(STREAM_NAME)}",
    )
    worker = thread(name = "trailblaze-memory-capture-$deviceId", isDaemon = true) { workerLoop() }
  }

  /**
   * The one thread that reads the device in the default mode. It serves a waiting boundary
   * request as soon as there is one, and otherwise takes a cron pass every [sampleIntervalMs].
   */
  private fun workerLoop() {
    var nextCronAt = clock() + sampleIntervalMs
    // The first pass is immediate so the session has its opening bookend.
    runCatching { sampleOnce() }.onFailure { noteSampleFailure("sample", it) }
    while (!stopped) {
      val request = try {
        lock.withLock {
          while (!stopped && pending.isEmpty()) {
            val wait = nextCronAt - clock()
            if (wait <= 0) break
            if (!wakeUp.await(wait, TimeUnit.MILLISECONDS)) break
          }
          pending.removeFirstOrNull()
        }
      } catch (_: InterruptedException) {
        return // stop() is taking the final bookend itself.
      }
      if (stopped) return
      runCatching {
        if (request != null) {
          sampleOnce(
            forcedReason = request.reason,
            tool = request.tool,
            traceId = request.traceId,
            atTimeMs = request.timeMs,
          )
        } else {
          // A diagnostics tool owns the readings until it finishes; this tick is dropped, not queued.
          if (!toolInFlight) sampleOnce()
        }
        // The interval is time since the last READING, whichever clock asked for it. Advancing it
        // only on the cron branch means that once a tick is overdue, every boundary reading is
        // followed straight away by a cron one — twice the device cost, for a second answer taken
        // milliseconds after the first.
        nextCronAt = clock() + sampleIntervalMs
      }.onFailure { noteSampleFailure("sample", it) }
    }
  }

  /**
   * A reading that threw. Logged for the first few and then every [FAILURE_LOG_EVERY]th, because a
   * probe that is failing fails on every cron tick and every tool boundary — at one line each, a
   * long trail buries its own output in the same message. The stop summary carries the total.
   */
  private fun noteSampleFailure(what: String, cause: Throwable) {
    if (stopped) return
    val failures = lock.withLock { ++failureCount }
    if (failures <= FAILURE_LOG_FIRST || failures % FAILURE_LOG_EVERY == 0) {
      Console.log("[memory-capture] $what failed (#$failures): ${cause.message}")
    }
  }

  /**
   * A reading that came back with nothing. Every distinct cause — a wedged transport, a timed-out
   * shell, a dump that would not parse, a package that is not there, a non-macOS host — arrives
   * here as the same null, and each one alone is unremarkable: the app simply may not be running
   * yet. A RUN of them is the signal, and is what "my session has no memory data" looks like from
   * the inside, so it is said once and the recovery is said once too.
   */
  private fun noteUnreadable() {
    val run = lock.withLock {
      unreadableCount++
      ++consecutiveUnreadable
    }
    if (run == UNREADABLE_RUN_TO_REPORT) {
      Console.log(
        "[memory-capture] $run readings in a row came back with nothing for ${appId ?: "the app"} on " +
          "${deviceId ?: "the device"} — the app may not be running, or the device is not answering",
      )
    }
  }

  override fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String?) {
    if (stopped) return
    val reason = when (phase) {
      ToolCallPhase.BEFORE -> MemoryChangeDetector.REASON_BEFORE_TOOL
      ToolCallPhase.AFTER -> MemoryChangeDetector.REASON_AFTER_TOOL
    }
    if (synchronousToolSamples) {
      // Set before the reading and, on `before`, left set for the whole tool call, so the cron
      // neither reads alongside this one nor lands a row between the pair.
      toolInFlight = true
      runCatching { sampleOnce(forcedReason = reason, tool = toolName, traceId = traceId) }
        .onFailure { noteSampleFailure("$reason sample", it) }
      toolInFlight = phase == ToolCallPhase.BEFORE
      return
    }
    lock.withLock {
      if (sink == null) return
      // Only once the queue is full — a device that has stopped answering — is anything dropped,
      // and it is the oldest, so what survives is the part of the run closest to now.
      if (pending.size >= MAX_PENDING_REQUESTS) {
        pending.removeFirst()
        coalescedCount++
      }
      pending.addLast(Request(reason, toolName, traceId, clock()))
      wakeUp.signal()
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    stopped = true
    lock.withLock { wakeUp.signalAll() }
    // An interrupt does not unblock a socket read, so the worker can outlive the join. Whether it
    // did decides what the rest of this does: everything below would otherwise queue behind a
    // reading that is already wedged, adding another full device timeout to every session stop.
    val workerWedged = worker?.let { w ->
      w.interrupt()
      runCatching { w.join(STOP_JOIN_TIMEOUT_MS) }
      w.isAlive
    } ?: false
    worker = null
    // Boundary readings still waiting are folded into the final bookend — it is the same moment.
    // The newest one's tool and trace ride along, so the last tool of a run keeps the row that
    // joins to its log instead of the session ending on an unattributed `final`.
    val foldedIn = lock.withLock {
      if (sink == null) return null
      val newest = pending.lastOrNull()
      pending.clear()
      newest
    }
    // Outside the lock: the final reading takes as long as any other, and holding the lock across
    // it would make a concurrent tool boundary wait for the device after all.
    if (workerWedged) {
      Console.log("[memory-capture] a reading is still in flight against $deviceId; skipping the final sample")
    } else {
      runCatching {
        sampleOnce(
          forcedReason = MemoryChangeDetector.REASON_FINAL,
          tool = foldedIn?.tool,
          traceId = foldedIn?.traceId,
        )
      }.onFailure { Console.log("[memory-capture] final sample failed: ${it.message}") }
    }
    lock.withLock {
      sink?.close()
      sink = null
    }
    // Closing the probe tears down its clients, so it must not run while a reading is using them —
    // a straggler worker would otherwise recreate what the close just cleared. Bounded, because
    // waiting out a wedged reading here is the stall this whole stream is written to avoid.
    if (readLock.tryLock(CLOSE_READ_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      try {
        runCatching { probe.close() }
      } finally {
        readLock.unlock()
      }
    } else {
      Console.log("[memory-capture] a reading is still holding the device; leaving the probe to be closed by the process")
    }
    lock.withLock {
      val coalesced = if (coalescedCount > 0) ", $coalescedCount boundary request(s) dropped" else ""
      val unreadable = if (unreadableCount > 0) ", $unreadableCount reading(s) came back with nothing" else ""
      val failed = if (failureCount > 0) ", $failureCount reading(s) failed" else ""
      // `info`, not `log`: with the CLI quiet by default this is the only place a run says whether
      // memory capture produced anything, and "0 events from 0 samples" is the answer to a real
      // question.
      Console.info(
        "[memory-capture] $emittedCount memory event(s) from $sampledCount sample(s)" +
          "$coalesced$unreadable$failed → " +
          "${SessionEvents.DIR_NAME}/${SessionEvents.fileName(STREAM_NAME)}",
      )
    }
    // The output is an events stream, discovered through `events/` like every other producer's —
    // not a capture artifact with its own bookends in capture_metadata.json.
    return null
  }

  /**
   * One sampling pass: read the device, decide, and append an event when warranted. Returns the
   * reason an event was written, or null when the reading was skipped as unchanged.
   *
   * [forcedReason] bypasses the change policy (tool boundaries, the final bookend); [atTimeMs]
   * stamps the event with the moment that was asked about rather than when the reading landed —
   * but never before the row already written, so the stream stays in timestamp order.
   * Internal so tests can drive the policy deterministically instead of racing the worker.
   */
  internal fun sampleOnce(
    forcedReason: String? = null,
    tool: String? = null,
    traceId: String? = null,
    atTimeMs: Long? = null,
  ): String? {
    val device: String
    val app: String?
    lock.withLock {
      if (sink == null) return null
      device = deviceId ?: return null
      app = appId
    }
    // The device read happens outside the lock so a tool boundary can be queued while it runs, but
    // under [readLock] so it is the only reading in flight. `readMs` is the read, not the wait for
    // the lock — it is reported as the cost of a reading.
    val (reading, readMs) = readLock.withLock {
      val readStartedNs = System.nanoTime()
      probe.read(device, app, forceGc) to (System.nanoTime() - readStartedNs) / 1_000_000
    }
    if (reading == null) {
      noteUnreadable()
      return null
    }
    lock.withLock {
      if (consecutiveUnreadable >= UNREADABLE_RUN_TO_REPORT) {
        Console.log("[memory-capture] readings are answering again for ${appId ?: "the app"} on $device")
      }
      consecutiveUnreadable = 0
    }
    lock.withLock {
      val activeSink = sink ?: return null
      sampledCount++
      // A boundary reading carries the moment it was ASKED about, which can predate a cron row that
      // landed while the boundary waited its turn at the reader. The report sorts rows by
      // timestamp, so an unclamped stamp would file this row BEFORE the very reading its `delta` is
      // measured against and point the change backwards. Holding the stamp at or after the last
      // emitted row keeps the file's order and the report's order the same one; the cost is that
      // such a row reads as the instant the queue cleared rather than the instant it was asked for.
      val askedAtMs = atTimeMs ?: clock()
      val snapshot = reading.copy(timeMs = maxOf(askedAtMs, lastEmitted?.timeMs ?: askedAtMs))
      // A boundary reading that discovers the process gone (or back, or replaced) reports THAT: it
      // is the only reading that can, because the next one compares against this pid-less snapshot
      // and finds nothing changed. The bookends keep their own names — `first` has nothing to
      // compare against, and `final` is a contract the report reads as the session's last row.
      val transition = MemoryChangeDetector.processTransition(lastEmitted, snapshot)
        ?.takeIf { forcedReason == MemoryChangeDetector.REASON_BEFORE_TOOL || forcedReason == MemoryChangeDetector.REASON_AFTER_TOOL }
      val reason = transition
        ?: forcedReason
        ?: MemoryChangeDetector.changeReason(lastEmitted, snapshot)
        ?: return null
      if (forcedReason != MemoryChangeDetector.REASON_FINAL && emittedCount >= maxEvents) {
        // Announced once, and NOT by bumping the counter: that would make the stop summary claim
        // one more event than the file holds.
        if (!ceilingLogged) {
          ceilingLogged = true
          Console.log("[memory-capture] reached $maxEvents events; only the final sample will be written")
        }
        return null
      }
      activeSink.append(
        STREAM_NAME,
        snapshot.timeMs,
        snapshot.toEventPayload(reason, lastEmitted, tool, readMs, traceId),
      )
      lastEmitted = snapshot
      emittedCount++
      return reason
    }
  }

  companion object {
    /** Stream name → `events/memory.ndjson`; also what `memory.formatter.ts` dispatches on. */
    const val STREAM_NAME = "memory"

    /** The cron between tool calls. Tool boundaries are sampled regardless of this. */
    const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 5_000

    /** Hard ceiling per session so a pathological allocator cannot bloat the session directory. */
    const val MAX_EVENTS: Int = 5_000

    /**
     * How many boundary requests may wait for the reader before the oldest is dropped. Sized for
     * "a few instant tools while one reading is in flight", not for a device that has stopped
     * answering — past this, banking more work would only make the backlog outlive the run.
     */
    const val MAX_PENDING_REQUESTS: Int = 8

    /** A run of empty readings this long is reported once, and its recovery reported once. */
    const val UNREADABLE_RUN_TO_REPORT: Int = 5

    private const val FAILURE_LOG_FIRST: Int = 3
    private const val FAILURE_LOG_EVERY: Int = 50

    private const val STOP_JOIN_TIMEOUT_MS: Long = 5_000

    /** How long teardown waits for an in-flight reading before leaving the probe to the process. */
    private const val CLOSE_READ_LOCK_TIMEOUT_MS: Long = 1_000
  }
}
