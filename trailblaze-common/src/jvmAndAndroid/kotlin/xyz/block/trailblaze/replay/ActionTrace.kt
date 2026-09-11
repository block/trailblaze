package xyz.block.trailblaze.replay

import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where one replayed action's time goes ON THE DEVICE, boundary by boundary, from the instant the
 * RPC frame arrives to the instant the reply is written back.
 *
 * A replayed action's cost is normally read as the host's tool span, and the parts of it that the
 * device can name — settle, capture, execute — do not add up to that span. The remainder is real,
 * is inside the request, and cannot be attributed by subtraction. This records every boundary
 * between the two ends of the request so the remainder is a measured table instead of a guess.
 *
 * Every mark is an offset in milliseconds from [begin]. Absolute offsets, not deltas, so a reader
 * can subtract any two boundaries without the trace having to anticipate which pair matters.
 *
 * OFF by default, and unlike the replay gates it never follows turbo — it is a measuring tool,
 * so it is only ever on because somebody turned it on:
 *
 *     adb shell setprop debug.trailblaze.replay.trace 1
 *
 * When off, [mark] and [logPost] are a volatile-boolean read and a return. Nothing allocates.
 *
 * ### Scope
 * One in-flight `run_yaml` at a time is the shape the on-device server already has (the handler
 * cancels any previous job before starting a new one), so this is a process-wide singleton rather
 * than a per-request object threaded through six modules — marks are made from six of them, on
 * driver threads as well as the dispatch coroutine, so nothing request-local would reach them all.
 *
 * Two things can therefore land in a trace that does not own them, and both are reported rather
 * than hidden, because a measuring tool that quietly mixes two requests is worse than one that
 * says it did:
 * - Another RPC overlapping a `run_yaml` — a `get_screen_state`, say. `otherRpcs` counts them.
 * - A `run_yaml` starting before the previous one finished. The handler only *launches* the old
 *   job's cancellation, and its teardown is `NonCancellable`, so the old request's last boundaries
 *   can arrive inside the new request's window. `displacedOpenTrace=1` says this line began on top
 *   of a trace that never emitted, so its early boundaries may not be its own.
 */
object ActionTrace {

  /**
   * Every boundary a request can be marked at, declared roughly in the order a request walks them.
   *
   * A closed vocabulary rather than free strings, for two reasons. A mistyped name does not fail —
   * it emits a phantom boundary nobody set out to record. And [mark] is first-write-wins, so two
   * call sites that reach for the same name silently swallow the second one's boundary. Reading
   * this list is also the fastest way to see the shape of a request.
   *
   * These are *edges*, not spans: every interval is the gap between two of them, and an edge like
   * [CAPTURE_DONE] closes one interval and opens the next. That is why there is no start/finish
   * pairing here — a reader subtracts whichever two edges they care about, and the trace does not
   * have to anticipate which pair that is. [PRE_SETTLE_START]/[PRE_SETTLE_DONE] are the one true
   * pair, because that wait is bracketed by nothing else.
   *
   * [wireName] is what lands in the emitted line, so the vocabulary can be refactored without
   * breaking a reader (or a script) that greps for a boundary by name.
   */
  enum class Boundary(val wireName: String) {
    /** The RPC frame has been decoded and dispatched to a handler. */
    DISPATCH_START("dispatchStart"),
    HANDLE_ENTERED("handleEntered"),
    YAML_PARSED("yamlParsed"),
    SESSION_READY("sessionReady"),
    JOB_STARTED("jobStarted"),

    /** The pre-action settle wait, the one boundary pair that brackets a wait of its own. */
    PRE_SETTLE_START("preSettleStart"),
    PRE_SETTLE_DONE("preSettleDone"),

    /**
     * The on-device standalone server's own dispatch, which is the other host a request can arrive
     * through. Absent from a run driven over the RPC route, present instead of nothing when the
     * standalone server is the one dispatching.
     */
    DISPATCH_ENTERED("dispatchEntered"),
    TARGET_RESOLVED("targetResolved"),
    SERVICE_READY("serviceReady"),
    TOOL_REPO_READY("toolRepoReady"),
    RULE_BUILT("ruleBuilt"),

    TRAIL_DECODED("trailDecoded"),
    BUNDLES_LAUNCHED("bundlesLaunched"),
    OBJECTIVE_STARTED("objectiveStarted"),

    /** Inside the driver: entered, settle released, screen captured, action executed. */
    DRIVER_ENTERED("driverEntered"),
    SETTLE_RELEASED("settleReleased"),
    CAPTURE_DONE("captureDone"),
    ACTION_EXECUTED("actionExecuted"),

    DRIVER_LOG_QUEUED("driverLogQueued"),
    TOOL_LOG_BUILT("toolLogBuilt"),
    TOOL_LOG_PRINTED("toolLogPrinted"),
    TOOL_LOG_UPLOADED("toolLogUploaded"),
    SHOT_ENCODED("shotEncoded"),
    SHOT_UPLOADED("shotUploaded"),
    DRIVER_LOG_UPLOADED("driverLogUploaded"),

    RECORDED_TOOLS_DONE("recordedToolsDone"),
    OBJECTIVE_COMPLETED("objectiveCompleted"),
    ITEMS_DONE("itemsDone"),
    BUNDLES_SHUTDOWN("bundlesShutdown"),
    DRIVER_LOGS_FLUSHED("driverLogsFlushed"),

    /** The standalone server's dispatch again, on the way back out. */
    RUN_RETURNED("runReturned"),

    AGENT_DONE("agentDone"),
    PROGRESS_COMPLETED("progressCompleted"),
    SESSION_END_HANDLED("sessionEndHandled"),
    OUTCOME_RESOLVED("outcomeResolved"),

    /** Back out at the RPC boundary: the reply is built and written. */
    HANDLER_RETURNED("handlerReturned"),
    REPLY_ENCODED("replyEncoded"),
    REPLY_WRITTEN("replyWritten"),
  }

  /** `1`/`true` turns the per-action boundary trace on. */
  const val TRACE_SYSPROP: String = "debug.trailblaze.replay.trace"

  private val getSysprop: java.lang.reflect.Method? by lazy {
    try {
      Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    } catch (t: Throwable) {
      null
    }
  }

  private fun sysprop(name: String): String = try {
    getSysprop?.invoke(null, name, "") as? String ?: ""
  } catch (t: Throwable) {
    ""
  }

  /** Pure parse of the sysprop value — extracted so the accepted values are unit-testable. */
  internal fun parseEnabled(raw: String): Boolean = raw.lowercase() in setOf("1", "true")

  /**
   * Read once per [begin] rather than per [mark]: a trace whose gate flips mid-request would emit
   * a line with holes in it, which reads as "that boundary took no time" rather than "that
   * boundary was not recorded".
   */
  @Volatile
  private var armed: Boolean = false

  @Volatile
  private var startNs: Long = 0L

  /** Insertion-ordered so the emitted line reads in the order the request actually walked. */
  private val marks = LinkedHashMap<String, Long>()

  private val postCount = AtomicInteger(0)
  private val postMs = AtomicLong(0)
  private val postBytes = AtomicLong(0)
  private val otherRpcs = AtomicInteger(0)

  /**
   * Whether this trace started on top of one that never emitted, which means an earlier request's
   * closing boundaries can appear in this line. Reported, not prevented — see the Scope note.
   */
  private val displacedOpenTrace = AtomicInteger(0)

  /** kind -> [count, totalMs, totalBytes], so the biggest uploader is named rather than inferred. */
  private val postsByKind = ConcurrentHashMap<String, LongArray>()

  /** True when a trace is being recorded right now. Cheap enough to guard a call site with. */
  val enabled: Boolean get() = armed

  /**
   * Reads the gate WITHOUT starting a trace, for a caller that has to do real work just to decide
   * whether this request is the one worth bracketing. The RPC frame loop has to decode the request
   * envelope to name the op, and `run_yaml` carries a whole trail's YAML and config — decoding
   * every frame twice would be a per-action cost paid on every run, trace or no trace.
   *
   * [begin] re-reads the gate itself, so this is only ever an early-out and never the value a
   * trace is armed from.
   */
  fun gateEnabled(): Boolean = parseEnabled(sysprop(TRACE_SYSPROP))

  /**
   * Starts a trace for one request. Reads the gate, resets every accumulator, and stamps t=0 at
   * [arrivedNs] — the caller's own stamp, taken when the request showed up.
   *
   * The caller supplies t=0 rather than letting this stamp it, because by the time the frame loop
   * knows a request is worth bracketing it has already read the frame's bytes, dispatched a
   * coroutine and decoded the envelope to name the op — and a `run_yaml` envelope carries a whole
   * trail's YAML. Stamping here would put all of that outside the window and understate the
   * request total, which is the number this trace exists to attribute.
   */
  fun begin(arrivedNs: Long) = begin(parseEnabled(sysprop(TRACE_SYSPROP)), arrivedNs)

  /** [begin] with the gate supplied, so the recording behaviour is testable off a device. */
  internal fun begin(on: Boolean, arrivedNs: Long = System.nanoTime()) {
    val displaced = synchronized(marks) {
      // Still armed means the previous request never emitted, so its closing boundaries are yet to
      // arrive and will land in this trace. Recorded on the trace that inherits them.
      val wasArmed = armed
      marks.clear()
      startNs = arrivedNs
      armed = on
      wasArmed
    }
    postCount.set(0)
    postMs.set(0)
    postBytes.set(0)
    otherRpcs.set(0)
    displacedOpenTrace.set(if (displaced) 1 else 0)
    postsByKind.clear()
  }

  /**
   * Records [boundary] at its offset from [begin]. First write wins, so a retried leg can't shadow.
   */
  fun mark(boundary: Boundary) {
    if (!armed) return
    val offsetMs = (System.nanoTime() - startNs) / 1_000_000
    synchronized(marks) { marks.putIfAbsent(boundary.wireName, offsetMs) }
  }

  /**
   * Records [boundary] at its offset and carries a size with it — the reply's serialized bytes, a
   * screenshot's encoded bytes. Written as `name=<offset>/<value>` so one line carries both.
   */
  fun markWith(boundary: Boundary, value: Long) {
    if (!armed) return
    val offsetMs = (System.nanoTime() - startNs) / 1_000_000
    synchronized(marks) {
      marks.putIfAbsent(boundary.wireName, offsetMs)
      marks.putIfAbsent("${boundary.wireName}#", value)
    }
  }

  /**
   * One log upload to the host, with the wall time it blocked its caller for.
   *
   * Every log this device emits is a WebSocket upload that waits for the host's ack before the
   * emitting thread continues, so these are not background costs — they are inside whatever the
   * request was doing at the time.
   */
  fun logPost(kind: String, ms: Long, bytes: Long) {
    if (!armed) return
    postCount.incrementAndGet()
    postMs.addAndGet(ms)
    postBytes.addAndGet(bytes)
    postsByKind.compute(kind) { _, prior ->
      val slot = prior ?: LongArray(3)
      slot[0] += 1
      slot[1] += ms
      slot[2] += bytes
      slot
    }
  }

  /** An RPC other than the traced `run_yaml` overlapped this trace. */
  fun otherRpc() {
    if (!armed) return
    otherRpcs.incrementAndGet()
  }

  /**
   * Emits the trace as one line and disarms. Called after the reply frame is written, so the last
   * boundary in the line is the last thing the request did.
   */
  fun emit() {
    val lines = renderAndDisarm() ?: return
    lines.forEach { Console.log(it) }
  }

  /**
   * The exact lines [emit] would print, or null when nothing was recorded. Disarms either way, so
   * a trace is emitted at most once and a second reader can't double-count it.
   */
  internal fun renderAndDisarm(): List<String>? {
    if (!armed) return null
    armed = false
    val snapshot = synchronized(marks) { marks.toList() }
    if (snapshot.isEmpty()) return null
    val boundaries = snapshot.joinToString(" ") { (name, value) ->
      if (name.endsWith("#")) "${name.dropLast(1)}Bytes=$value" else "$name=$value"
    }
    val lines = mutableListOf(
      "[action-trace] $boundaries logPosts=${postCount.get()} logPostMs=${postMs.get()} " +
        "logPostBytes=${postBytes.get()} otherRpcs=${otherRpcs.get()} " +
        "displacedOpenTrace=${displacedOpenTrace.get()}",
    )
    val byKind = postsByKind.entries.sortedByDescending { it.value[1] }
    if (byKind.isNotEmpty()) {
      lines += "[action-trace-posts] " +
        byKind.joinToString(" ") { (kind, slot) -> "$kind=${slot[0]}/${slot[1]}ms/${slot[2]}b" }
    }
    return lines
  }
}
