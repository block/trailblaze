package xyz.block.trailblaze.playwright.console

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.ConsoleMessage
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.util.Console
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Per-[BrowserContext] browser-console capture using Playwright's typesafe
 * [BrowserContext.onConsoleMessage] listener — the web counterpart to Android
 * logcat ([xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture]) and the
 * iOS Simulator system log ([xyz.block.trailblaze.capture.logcat.IosLogCapture]).
 *
 * Every `console.log` / `warn` / `error` / `info` / `debug` the page emits is
 * appended one-per-line to `<session-dir>/device.log` — the single canonical
 * device-log filename the report's "Device Logs" panel reads
 * ([DeviceLogSource]). Lines are formatted so the panel's `WebDeviceLogParser`
 * can recover a (rough) timestamp + a [LogLevel]:
 * ```
 * 2026-06-22 14:23:45.678 [error] Failed to load resource: net::ERR_FAILED
 * ```
 *
 * Listeners attach at the [BrowserContext] level — they fire for every page in
 * the context (main frame, iframes, popups), survive in-page navigation, and
 * don't require re-attachment when a new page is created. No JS injection.
 *
 * Only the local, non-blocking [ConsoleMessage] accessors `type()` / `text()`
 * are read on the listener thread — unlike `Response.body()` / `allHeaders()`
 * (see [xyz.block.trailblaze.playwright.network.WebNetworkCapture]), these never
 * re-enter Playwright via CDP, so there's no listener-thread deadlock risk.
 *
 * Failure is bounded: a per-message write failure increments a drop counter and
 * is logged once on [stop] rather than tearing down the trail.
 *
 * Lookup is keyed by [BrowserContext] identity via a [WeakHashMap] so a closed
 * context's capture is eligible for GC. The [Companion] methods are the public
 * entry points.
 */
class WebConsoleCapture private constructor(
  private val sessionId: String,
  private val sessionDir: File,
) {
  private val deviceLogFile: File = File(sessionDir, DEVICE_LOG_FILENAME)
  private val active: AtomicBoolean = AtomicBoolean(false)
  private val writeLock = Any()
  private var writer: BufferedWriter? = null
  private val droppedWrites: AtomicInteger = AtomicInteger(0)

  // Console messages are handed off to this queue on the Playwright dispatcher thread
  // (an O(1), lock-free, no-I/O enqueue) and drained to disk by [drainer], a dedicated
  // background thread. This is the load-bearing design choice: a chatty web app can emit
  // a high volume of console messages, and Playwright dispatches every page event on a
  // single thread — so doing a synchronized disk write+flush per message *on that thread*
  // (the original implementation) would serialize page operations behind disk I/O and can
  // stall the page, surfacing downstream as a dropped browser/MCP connection. Same hazard
  // WebNetworkCapture's kdoc calls out for `Response.body()`. Keep the listener trivial.
  private val queue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
  // Tracked explicitly because ConcurrentLinkedQueue.size is O(n) — calling it per message on
  // the dispatcher thread (to enforce the cap) would be O(n²). incremented on enqueue, decremented
  // as the drainer polls.
  private val queuedCount: AtomicInteger = AtomicInteger(0)
  private val droppedEnqueues: AtomicInteger = AtomicInteger(0)
  private var drainer: Thread? = null

  // Stored as a field so off* can detach the same instance.
  private val onConsoleListener: Consumer<ConsoleMessage> = Consumer { handleMessage(it) }

  /** True iff this capture was constructed for the given session. */
  internal fun matches(otherSessionId: String, otherSessionDir: File): Boolean =
    sessionId == otherSessionId && sessionDir == otherSessionDir

  private fun attach(ctx: BrowserContext) {
    if (!active.compareAndSet(false, true)) return
    try {
      if (!sessionDir.exists() && !sessionDir.mkdirs()) {
        throw IOException("Could not create session directory: ${sessionDir.absolutePath}")
      }
      // Touch the file so a read immediately after start (no console output yet)
      // distinguishes "started, idle" from "never started".
      if (!deviceLogFile.exists()) deviceLogFile.createNewFile()
      synchronized(writeLock) {
        writer = BufferedWriter(FileWriter(deviceLogFile, /* append = */ true))
      }
    } catch (e: Exception) {
      active.set(false)
      throw e
    }
    // Start the background drainer BEFORE attaching the listener so no queued message waits
    // for a writer. Daemon so it never blocks JVM exit if a stop() is somehow missed.
    drainer = Thread({ runDrainLoop() }, "web-console-capture").apply {
      isDaemon = true
      start()
    }
    ctx.onConsoleMessage(onConsoleListener)
  }

  private fun detach(ctx: BrowserContext) {
    if (!active.compareAndSet(true, false)) return
    ctx.offConsoleMessage(onConsoleListener)
    // Wake the drainer out of its idle sleep and let it flush whatever it has queued, then
    // join briefly. The loop's exit condition (`active == false && queue empty`) drives it to
    // drain the tail before returning.
    drainer?.let { t ->
      t.interrupt()
      runCatching { t.join(DRAIN_JOIN_TIMEOUT_MS) }
    }
    drainer = null
    // Safety net: flush any straggler the drainer didn't reach (e.g. join timed out), then close.
    synchronized(writeLock) {
      val w = writer
      if (w != null) {
        drainInto(w)
        runCatching { w.flush() }
        runCatching { w.close() }
      }
      writer = null
    }
    val dropped = droppedWrites.get() + droppedEnqueues.get()
    if (dropped > 0) {
      Console.log("WebConsoleCapture stopped for session=$sessionId — dropped lines=$dropped")
    }
  }

  fun isActive(): Boolean = active.get()

  /**
   * Runs on the Playwright dispatcher thread — must stay trivial (no disk I/O, no lock that
   * a disk write holds). Formats the line and hands it to [queue]; the background [drainer]
   * does the writing. A bounded queue caps worst-case memory if the producer ever outruns the
   * drainer (pathological flood); excess is dropped and counted, never blocks the dispatcher.
   */
  private fun handleMessage(message: ConsoleMessage) {
    if (!active.get()) return
    val line = runCatching { formatLine(message) }.getOrNull() ?: return
    if (queuedCount.get() >= MAX_QUEUED_LINES) {
      droppedEnqueues.incrementAndGet()
      return
    }
    queue.add(line)
    queuedCount.incrementAndGet()
  }

  /**
   * `2026-06-22 14:23:45.678 [type] text` — the timestamp prefix matches the
   * iOS compact format so the panel's relative-time math lines up, and the
   * `[type]` tag carries the console level for `WebDeviceLogParser`. Embedded
   * newlines are flattened to spaces so each console message stays one line.
   */
  private fun formatLine(message: ConsoleMessage): String {
    val ts = TS_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
    val type = runCatching { message.type() }.getOrNull()?.ifBlank { "log" } ?: "log"
    val text = runCatching { message.text() }.getOrNull()
      ?.replace('\n', ' ')
      ?.replace('\r', ' ')
      ?: ""
    return "$ts [$type] $text"
  }

  /**
   * Background drain loop (the only thread that touches the writer). Drains the queue in
   * batches and flushes after each batch, so a JVM crash leaves a complete prefix and the
   * report's ~1s device-log poll sees fresh lines. Sleeps when idle; [detach] interrupts it
   * to wake it for the final tail flush. Exits once capture is stopped AND the queue is empty.
   */
  private fun runDrainLoop() {
    while (active.get() || queue.isNotEmpty()) {
      val flushed = synchronized(writeLock) {
        val w = writer ?: return@synchronized 0
        val n = drainInto(w)
        if (n > 0) runCatching { w.flush() }
        n
      }
      if (flushed == 0) {
        try {
          Thread.sleep(DRAIN_INTERVAL_MS)
        } catch (_: InterruptedException) {
          // Woken by detach() for the final drain — loop re-checks the exit condition.
        }
      }
    }
  }

  /** Drains all currently-queued lines into [w]. Caller holds [writeLock]. Returns the count. */
  private fun drainInto(w: BufferedWriter): Int {
    var n = 0
    while (true) {
      val line = queue.poll() ?: break
      queuedCount.decrementAndGet()
      runCatching {
        w.write(line)
        w.newLine()
      }.onFailure { droppedWrites.incrementAndGet() }
      n++
    }
    return n
  }

  companion object {
    /** The single canonical device-log filename, shared with Android logcat / iOS log capture. */
    const val DEVICE_LOG_FILENAME: String = CaptureFilenames.DEVICE_LOG

    /** How long the drainer idles between polls when the queue is empty. */
    private const val DRAIN_INTERVAL_MS: Long = 250L

    /** Max time [detach] waits for the drainer to flush the queued tail before its own safety drain. */
    private const val DRAIN_JOIN_TIMEOUT_MS: Long = 2_000L

    /**
     * Soft cap on un-drained queued lines. A web app that floods console faster than disk can
     * drain would otherwise grow this unbounded; beyond the cap we drop + count rather than risk
     * memory pressure. Generous enough that normal (even chatty) sessions never hit it.
     */
    private const val MAX_QUEUED_LINES: Int = 100_000

    // Fixed-width prefix matching the iOS compact format (yyyy-MM-dd HH:mm:ss.SSS) so the
    // panel's timestamp detection ([4]='-',[7]='-',[10]=' ',[13]=':') recognizes it. Pinned to
    // Locale.ROOT: the default JVM locale can render non-ASCII digits (e.g. ar/fa locales), which
    // WebDeviceLogParser — reading ASCII digits at fixed offsets — would then fail to parse.
    private val TS_FORMAT: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    // BrowserContext is a stable identity reference held by the browser manager for the
    // lifetime of the session — WeakHashMap lets the entry GC when the context closes.
    private val instances: WeakHashMap<BrowserContext, WebConsoleCapture> = WeakHashMap()

    /**
     * Idempotently starts console capture for [ctx]. Returns the live capture,
     * attaching the listener on first call (or after [stop]). If a previous
     * capture exists for this context but was constructed for a different
     * session, the old listener is detached and a fresh capture is created. A
     * repeat start with the same session is a no-op.
     */
    @Synchronized
    fun start(ctx: BrowserContext, sessionId: String, sessionDir: File): WebConsoleCapture {
      val existing = instances[ctx]
      if (existing != null && existing.matches(sessionId, sessionDir)) {
        if (!existing.isActive()) existing.attach(ctx)
        return existing
      }
      existing?.detach(ctx)
      val capture = WebConsoleCapture(sessionId, sessionDir)
      instances[ctx] = capture
      capture.attach(ctx)
      return capture
    }

    /** Detaches the listener. Returns true if a capture was active and got stopped. */
    @Synchronized
    fun stop(ctx: BrowserContext): Boolean {
      val capture = instances[ctx] ?: return false
      if (!capture.isActive()) return false
      capture.detach(ctx)
      return true
    }
  }
}
