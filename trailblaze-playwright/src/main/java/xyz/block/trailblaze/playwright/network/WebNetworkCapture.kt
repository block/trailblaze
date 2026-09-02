package xyz.block.trailblaze.playwright.network

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.network.BodyRef
import xyz.block.trailblaze.network.InflightRequestTracker
import xyz.block.trailblaze.network.NetworkEvent
import xyz.block.trailblaze.network.Phase
import xyz.block.trailblaze.network.REDACTED_VALUE
import xyz.block.trailblaze.network.Source
import xyz.block.trailblaze.util.Console
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Per-[BrowserContext] network capture using Playwright's typesafe listener API.
 *
 * Listeners attach at the [BrowserContext] level — they fire for every page in
 * the context (main frame, iframes, popups), survive in-page navigation, and
 * don't require re-attachment when a new page is created. No JS injection.
 *
 * Each captured exchange emits two events (REQUEST_START + RESPONSE_END) sharing
 * an [NetworkEvent.id]; failures emit one (FAILED) instead of RESPONSE_END.
 * Events are appended one-per-line to `<session-dir>/network.ndjson`. The
 * listener thread does NO disk I/O: it reads only local, non-blocking accessors
 * off the Playwright objects and hands a [PendingWrite] to [queue]; a dedicated
 * background [drainer] thread does JSON serialization, body persistence, and
 * the NDJSON write+flush (same design as
 * [xyz.block.trailblaze.playwright.console.WebConsoleCapture], and the same
 * reason: Playwright dispatches every page event on a single thread, so a
 * synchronized write+flush per event on that thread serializes page operations
 * behind disk I/O). Bodies larger than [INLINE_BODY_LIMIT_BYTES]
 * (or non-text by content-type) are written to `<session-dir>/bodies/` and
 * referenced via [BodyRef.blobPath]; payloads beyond [MAX_BLOB_BYTES] are
 * truncated and flagged with [BodyRef.truncated].
 *
 * Redaction is intentionally minimal: `Authorization` request headers and
 * `Set-Cookie` response headers are scrubbed, with the field names listed in
 * [NetworkEvent.redactedFields] so the renderer can show what was removed.
 *
 * Lookup is keyed by [BrowserContext] identity via a [WeakHashMap] so a closed
 * context's capture is eligible for GC. The [Companion] methods are the public
 * entry points — tools should not construct instances directly.
 *
 * Failure modes are bounded so a transient error in one handler can't lose an
 * unrelated exchange: each handler wraps only the failure-prone steps (body
 * fetch, blob write, NDJSON write) in narrow `runCatching` blocks and emits
 * the metadata-only event when those individual steps fail. Per-kind drop
 * counts are surfaced in a single summary line on [stop].
 */
class WebNetworkCapture private constructor(
  private val sessionId: String,
  private val sessionDir: File,
  private val tracker: InflightRequestTracker?,
) {
  private val ndjsonFile: File = File(sessionDir, NDJSON_FILENAME)
  private val bodiesDir: File = File(sessionDir, BODIES_DIRNAME)
  private val active: AtomicBoolean = AtomicBoolean(false)
  private val ndjsonLock = Any()
  private val requestStarts: ConcurrentHashMap<Request, RequestState> = ConcurrentHashMap()

  // Long-lived writer held open for the capture lifetime. Initialized in attach()
  // and closed in detach(). Guarded by ndjsonLock for writes/lifecycle.
  private var ndjsonWriter: BufferedWriter? = null

  // Listener references are stored as fields so off* can detach the same instances.
  private val onRequestListener: Consumer<Request> = Consumer { handleRequest(it) }
  private val onResponseListener: Consumer<Response> = Consumer { handleResponse(it) }
  private val onRequestFailedListener: Consumer<Request> = Consumer { handleFailed(it) }

  // Per-kind drop counters surfaced on detach() so a session with N silent
  // failures shows up as one summary line rather than disappearing into stdout.
  private val droppedRequestBodies = AtomicInteger(0)
  private val droppedResponseBodies = AtomicInteger(0)
  private val droppedWrites = AtomicInteger(0)
  private val droppedEnqueues = AtomicInteger(0)

  // Events are handed off to this queue on the Playwright dispatcher thread (an O(1),
  // lock-free, no-I/O enqueue) and drained to disk by [drainer]. See the class kdoc for
  // why the listener thread must never touch disk. Single consumer thread + FIFO queue
  // preserves NDJSON line order.
  private val queue: ConcurrentLinkedQueue<PendingWrite> = ConcurrentLinkedQueue()
  // Tracked explicitly because ConcurrentLinkedQueue.size is O(n) — calling it per event
  // on the dispatcher thread (to enforce the cap) would be O(n²).
  private val queuedCount: AtomicInteger = AtomicInteger(0)
  private var drainer: Thread? = null

  private data class RequestState(val id: String, val startMs: Long)

  /**
   * A captured event awaiting its drainer-side work. [requestBodyBytes] is read on the
   * listener thread (a local, in-memory accessor) and already clamped to
   * [MAX_BLOB_BYTES] — a queue of up to [MAX_QUEUED_EVENTS] entries must not be able to
   * pin more than that per entry, and persistence would discard the excess anyway.
   * [requestBodyOriginalSize] carries the pre-clamp length so the renderer still reports
   * the true magnitude. Persisting ([persistBody] — potentially a blob file write) stays
   * on the drainer with the rest of the disk I/O.
   */
  private class PendingWrite(
    val event: NetworkEvent,
    val requestBodyBytes: ByteArray? = null,
    val requestBodyContentType: String? = null,
    val requestBodyOriginalSize: Long = requestBodyBytes?.size?.toLong() ?: 0L,
  )

  /** True iff this capture was constructed for the given session. */
  internal fun matches(otherSessionId: String, otherSessionDir: File): Boolean =
    sessionId == otherSessionId && sessionDir == otherSessionDir

  /**
   * Attaches the BrowserContext listeners and opens the NDJSON writer. Throws
   * [IOException] if the session directory or NDJSON file can't be created so
   * the caller surfaces a clear error instead of silently activating a capture
   * that drops every event.
   */
  private fun attach(ctx: BrowserContext) {
    if (!active.compareAndSet(false, true)) return
    try {
      if (!sessionDir.exists() && !sessionDir.mkdirs()) {
        throw IOException("Could not create session directory: ${sessionDir.absolutePath}")
      }
      // Touch the NDJSON file so a read immediately after start (no traffic yet)
      // distinguishes "started, idle" from "never started".
      if (!ndjsonFile.exists()) ndjsonFile.createNewFile()
      synchronized(ndjsonLock) {
        ndjsonWriter = BufferedWriter(FileWriter(ndjsonFile, /* append = */ true))
      }
    } catch (e: Exception) {
      active.set(false)
      throw e
    }
    // Start the background drainer BEFORE attaching listeners so no queued event waits
    // for a consumer. Daemon so it never blocks JVM exit if a stop() is somehow missed.
    drainer = Thread({ runDrainLoop() }, "web-network-capture").apply {
      isDaemon = true
      start()
    }
    ctx.onRequest(onRequestListener)
    ctx.onResponse(onResponseListener)
    ctx.onRequestFailed(onRequestFailedListener)
  }

  private fun detach(ctx: BrowserContext) {
    if (!active.compareAndSet(true, false)) return
    // off* takes the same Consumer instance to remove the registration.
    ctx.offRequest(onRequestListener)
    ctx.offResponse(onResponseListener)
    ctx.offRequestFailed(onRequestFailedListener)
    // Wake the drainer out of its idle sleep and let it flush the queued tail — its exit
    // condition (`active == false && queue empty`) drives the final drain — then join briefly.
    drainer?.let { t ->
      t.interrupt()
      runCatching { t.join(DRAIN_JOIN_TIMEOUT_MS) }
    }
    drainer = null
    // Safety net: drain any straggler the drainer didn't reach (e.g. join timed out), then close.
    synchronized(ndjsonLock) {
      val w = ndjsonWriter
      if (w != null) {
        flushCounting(w, drainInto(w), "flushTail")
        // close() re-attempts the flush, so after a failed one this throws too — already
        // counted above, but log it so a broken close never passes for a clean shutdown.
        runCatching { w.close() }.onFailure { logSwallowed("closeWriter", it) }
      }
      ndjsonWriter = null
    }
    // End any still-tracked in-flight ids so a session boundary doesn't pin
    // isIdle() false on the next session that reuses the same tracker.
    if (tracker != null) {
      requestStarts.values.forEach { tracker.onRequestEnd(it.id) }
    }
    requestStarts.clear()
    summarizeDrops()?.let { Console.log(it) }
  }

  fun isActive(): Boolean = active.get()

  fun ndjsonPath(): File = ndjsonFile

  private fun handleRequest(request: Request) {
    if (!active.get()) return
    val id = UUID.randomUUID().toString()
    val nowMs = System.currentTimeMillis()
    // `headers()` is locally cached on the Playwright object — `allHeaders()`
    // does an additional CDP roundtrip to pick up security-related headers
    // (cookies, set-cookie). Since we redact those anyway, the roundtrip is
    // pure overhead — and worse, doing it on the listener thread can deadlock
    // re-entrantly when the parent Playwright API call (e.g. waitForFunction)
    // is what's holding the dispatcher.
    val headers = redactRequestHeaders(safeHeaders { request.headers() })
    // Read the raw body bytes here — `postDataBuffer()` is non-blocking because
    // Playwright already holds those bytes in memory — but leave persistence
    // (potentially a blob file write) to the drainer. On failure, drop the body
    // but keep the event — the renderer can still pair REQUEST_START with
    // RESPONSE_END.
    val bodyBytes = runCatching { extractRequestBodyBytes(request) }
      .onFailure {
        droppedRequestBodies.incrementAndGet()
        logSwallowed("readRequestBody", it)
      }
      .getOrNull()
    val event = NetworkEvent(
      id = id,
      sessionId = sessionId,
      timestampMs = nowMs,
      phase = Phase.REQUEST_START,
      method = request.method(),
      url = request.url(),
      urlPath = pathOf(request.url()),
      requestHeaders = headers,
      source = Source.PLAYWRIGHT_WEB,
    )
    // Insert the entry only after we've committed to writing the event so a
    // throw above can't strand a RequestState that the response handler then
    // matches — which would emit an unpaired RESPONSE_END.
    requestStarts[request] = RequestState(id = id, startMs = nowMs)
    // Update the engine-agnostic in-flight tracker so future idling tools can
    // observe network activity. Done after we've committed to writing the
    // event so a transient build-event throw can't poison tracker state.
    tracker?.onRequestStart(id, request.url())
    enqueue(
      PendingWrite(
        event = event,
        requestBodyBytes = bodyBytes?.let { clampBodyForQueue(it) },
        requestBodyContentType = headers["content-type"],
        requestBodyOriginalSize = bodyBytes?.size?.toLong() ?: 0L,
      ),
    )
  }

  private fun handleResponse(response: Response) {
    if (!active.get()) return
    val request = response.request()
    val state = requestStarts.remove(request) ?: return
    tracker?.onRequestEnd(state.id)
    val nowMs = System.currentTimeMillis()
    // See request-side comment — `headers()` is locally cached, `allHeaders()`
    // can re-enter Playwright via CDP and deadlock under a parent waitFor.
    val headers = redactResponseHeaders(safeHeaders { response.headers() })
    val bodyRef = runCatching { buildResponseBodyRef(state.id, response, headers) }
      .onFailure {
        droppedResponseBodies.incrementAndGet()
        logSwallowed("buildResponseBody", it)
      }
      .getOrNull()
    val event = NetworkEvent(
      id = state.id,
      sessionId = sessionId,
      timestampMs = nowMs,
      phase = Phase.RESPONSE_END,
      method = request.method(),
      url = request.url(),
      urlPath = pathOf(request.url()),
      statusCode = response.status(),
      durationMs = nowMs - state.startMs,
      responseHeaders = headers,
      responseBodyRef = bodyRef,
      source = Source.PLAYWRIGHT_WEB,
    )
    enqueue(PendingWrite(event))
  }

  private fun handleFailed(request: Request) {
    if (!active.get()) return
    val state = requestStarts.remove(request) ?: return
    tracker?.onRequestEnd(state.id)
    val nowMs = System.currentTimeMillis()
    val event = NetworkEvent(
      id = state.id,
      sessionId = sessionId,
      timestampMs = nowMs,
      phase = Phase.FAILED,
      method = request.method(),
      url = request.url(),
      urlPath = pathOf(request.url()),
      durationMs = nowMs - state.startMs,
      source = Source.PLAYWRIGHT_WEB,
    )
    enqueue(PendingWrite(event))
  }

  /**
   * Runs on the Playwright dispatcher thread — an O(1) handoff, no disk I/O. The bounded
   * queue caps worst-case memory if the producer ever outruns the drainer; excess is
   * dropped and counted, never blocks the dispatcher.
   */
  private fun enqueue(pending: PendingWrite) {
    // Nothing may enter the queue once capture is stopped: [detach] closes the writer, so a
    // late arrival could never be written, and leaving it queued strands the drainer on a
    // non-empty queue it can't drain. Playwright can call a listener that passed the
    // `active` check at the top of the handler while detach runs, so the check belongs here
    // too — the pre-queue writer re-checked `active` for the same reason.
    if (!active.get()) {
      droppedEnqueues.incrementAndGet()
      return
    }
    // Claim the slot BEFORE publishing. Incrementing after `queue.add` lets the drainer
    // poll the entry and decrement in between, driving the counter negative and leaving
    // the cap unenforceable.
    if (queuedCount.incrementAndGet() > MAX_QUEUED_EVENTS) {
      queuedCount.decrementAndGet()
      droppedEnqueues.incrementAndGet()
      return
    }
    queue.add(pending)
    // Re-read AFTER publishing, because the check above is not atomic with the add: detach
    // can flip `active`, drain an empty queue, close the writer and retire the drainer in
    // that window, leaving this entry queued forever — pinning its body with no drop
    // diagnostic. Taking the admission through `ndjsonLock` instead would put the
    // dispatcher thread behind disk I/O, which is the whole reason this queue exists.
    if (!active.get()) {
      // The drainer may have taken it first, in which case it WAS written — only account
      // for an entry we actually reclaim.
      if (queue.remove(pending)) {
        queuedCount.decrementAndGet()
        droppedEnqueues.incrementAndGet()
      }
    }
  }

  /**
   * Background drain loop (the only thread that touches the writer outside [detach]'s
   * safety net). Drains the queue in batches and flushes after each batch, so a JVM crash
   * mid-session leaves a complete prefix — partial trailing lines are skipped + logged by
   * the read tool. Sleeps when idle; [detach] interrupts it for the final tail flush.
   * Exits once capture is stopped AND the queue is empty — or as soon as the writer is
   * gone, since nothing queued can be written after that and looping would spin forever.
   */
  private fun runDrainLoop() {
    while (active.get() || queue.isNotEmpty()) {
      val drained = synchronized(ndjsonLock) {
        val w = ndjsonWriter ?: return@synchronized null
        val batch = drainInto(w)
        flushCounting(w, batch, "flushEvents")
        batch.drained
      } ?: break
      if (drained == 0) {
        try {
          Thread.sleep(DRAIN_INTERVAL_MS)
        } catch (_: InterruptedException) {
          // Woken by detach() for the final drain — loop re-checks the exit condition.
        }
      }
    }
    // Anything still queued once the writer is gone can never be written. Account for it so
    // the stop summary reports the loss instead of implying a clean session. A no-op on the
    // ordinary exit, where the queue is already empty.
    while (queue.poll() != null) {
      queuedCount.decrementAndGet()
      droppedWrites.incrementAndGet()
    }
  }

  /**
   * [drained] is every entry removed from the queue, [buffered] only the ones whose line
   * made it into the writer's buffer — and therefore what a failing flush loses.
   */
  private class DrainBatch(val drained: Int, val buffered: Int)

  /** Drains all currently-queued events into [w]. Caller holds [ndjsonLock]. */
  private fun drainInto(w: BufferedWriter): DrainBatch {
    var drained = 0
    var buffered = 0
    while (true) {
      val pending = queue.poll() ?: break
      queuedCount.decrementAndGet()
      drained++
      runCatching {
        w.write(toLine(pending))
        w.newLine()
      }.onSuccess {
        buffered++
      }.onFailure {
        droppedWrites.incrementAndGet()
        logSwallowed("writeEvent", it)
      }
    }
    return DrainBatch(drained, buffered)
  }

  /**
   * Flushes a drained batch, charging a failure to [droppedWrites].
   *
   * A [BufferedWriter] takes writes into memory and only touches the disk on flush, so a
   * full or read-only filesystem raises here, not in [drainInto] — swallowing it loses the
   * whole batch while the stop summary still reports zero writes dropped.
   */
  private fun flushCounting(w: BufferedWriter, batch: DrainBatch, where: String) {
    if (batch.buffered == 0) return
    runCatching { w.flush() }.onFailure {
      droppedWrites.addAndGet(batch.buffered)
      logSwallowed(where, it)
    }
  }

  /**
   * Drainer-side completion of a pending event: persist the request body (the disk-I/O
   * step deliberately deferred off the listener thread) and serialize. A body-persist
   * failure drops the body but keeps the event.
   */
  private fun toLine(pending: PendingWrite): String {
    val bodyRef = pending.requestBodyBytes?.let { bytes ->
      runCatching {
        persistBody(
          eventId = pending.event.id,
          bytes = bytes,
          contentType = pending.requestBodyContentType,
          prefix = "req",
          originalSizeBytes = pending.requestBodyOriginalSize,
        )
      }
        .onFailure {
          droppedRequestBodies.incrementAndGet()
          logSwallowed("persistRequestBody", it)
        }
        .getOrNull()
    }
    val event = bodyRef?.let { pending.event.copy(requestBodyRef = it) } ?: pending.event
    return JSON.encodeToString(NetworkEvent.serializer(), event)
  }

  /**
   * Raw request-body bytes via the non-blocking local accessors ([Request.postDataBuffer]
   * / [Request.postData] — Playwright holds these in memory before sending, no CDP
   * roundtrip). Null for empty/absent bodies.
   */
  internal fun extractRequestBodyBytes(request: Request): ByteArray? {
    val bytes: ByteArray = runCatching { request.postDataBuffer() }.getOrNull()
      ?: runCatching { request.postData()?.toByteArray(Charsets.UTF_8) }.getOrNull()
      ?: return null
    return bytes.takeIf { it.isNotEmpty() }
  }

  /**
   * Clamps a captured body to what persistence would keep. Applied before the bytes enter
   * the queue: otherwise a burst of multi-MB uploads pins its full size in heap until the
   * drainer catches up, only for [persistBody] to throw the excess away.
   */
  private fun clampBodyForQueue(bytes: ByteArray): ByteArray =
    if (bytes.size > MAX_BLOB_BYTES) bytes.copyOf(MAX_BLOB_BYTES) else bytes

  @Suppress("UNUSED_PARAMETER") // params kept for future opt-in body capture path; see kdoc.
  internal fun buildResponseBodyRef(
    eventId: String,
    response: Response,
    headers: Map<String, String>,
  ): BodyRef? {
    // `response.body()` blocks the Playwright listener thread until the full
    // body is buffered into memory. Since Playwright dispatches every request /
    // response / page-event callback on a single thread, blocking here
    // serializes everything behind us — including the listener calls for
    // already-completed responses on the same page. Observed in the wild as
    // 44 REQUEST_START / 0 RESPONSE_END after navigating to a site whose
    // *first* response blocked body() on a slow chunked payload (no
    // Content-Length to pre-skip), with all subsequent response listeners
    // queued behind it.
    //
    // Pre-fetch filters by content-type or Content-Length aren't enough —
    // any single text response without Content-Length still blocks. So we
    // never call body() on the hot path. RESPONSE_END events still emit with
    // status + headers + duration (all non-blocking on the Response object),
    // and **request** bodies still capture in full via `postDataBuffer()`,
    // which is non-blocking because Playwright already has those bytes in
    // memory before the request was sent. That covers the common analytics-
    // signal assertion use case, since the signal payload typically lives in
    // the POST body of the outgoing request, not the response.
    //
    // If a future use case genuinely needs response bodies, the right path
    // is an opt-in flag (`captureResponseBodies`) plus an off-thread fetch
    // dispatched via `Page.evaluate(...)` — a CDP-level approach that
    // doesn't pin the listener thread. Out of scope for this PR.
    return null
  }

  /**
   * @param originalSizeBytes the body's length BEFORE any upstream clamping, so the
   *   truncation badge and reported magnitude stay accurate when the caller already
   *   trimmed the bytes (see `clampBodyForQueue`). Defaults to [bytes]'s own length.
   */
  internal fun persistBody(
    eventId: String,
    bytes: ByteArray,
    contentType: String?,
    prefix: String,
    originalSizeBytes: Long = bytes.size.toLong(),
  ): BodyRef {
    val originalSize = maxOf(originalSizeBytes, bytes.size.toLong())
    val truncated = originalSize > MAX_BLOB_BYTES
    // Persist at most MAX_BLOB_BYTES so a single multi-MB asset can't bloat the
    // session bundle. sizeBytes still reports the original Content-Length so the
    // renderer can show the real magnitude alongside the truncation badge.
    val effective = if (bytes.size > MAX_BLOB_BYTES) bytes.copyOf(MAX_BLOB_BYTES) else bytes

    if (effective.size <= INLINE_BODY_LIMIT_BYTES && isLikelyText(contentType)) {
      return BodyRef(
        sizeBytes = originalSize,
        contentType = contentType,
        inlineText = String(effective, Charsets.UTF_8),
        truncated = truncated,
      )
    }
    if (!bodiesDir.exists() && !bodiesDir.mkdirs()) {
      throw IOException("Could not create bodies directory: ${bodiesDir.absolutePath}")
    }
    val blobName = "${prefix}_${eventId}.bin"
    val blobFile = File(bodiesDir, blobName)
    blobFile.writeBytes(effective)
    return BodyRef(
      sizeBytes = originalSize,
      contentType = contentType,
      blobPath = "$BODIES_DIRNAME/$blobName",
      truncated = truncated,
    )
  }

  internal fun isLikelyText(contentType: String?): Boolean {
    val lower = contentType?.lowercase() ?: return false
    return lower.startsWith("text/") ||
      lower.contains("json") ||
      lower.contains("xml") ||
      lower.contains("javascript") ||
      lower.contains("x-www-form-urlencoded")
  }

  /**
   * Show key, scrub value: sensitive headers keep their key in the captured
   * map but have their value replaced with [REDACTED_VALUE]. Consumers can
   * still see "this header was sent" without the secret leaking into session
   * artifacts. Cookie / Cookie-related headers don't appear here at all
   * because Playwright's `headers()` API filters them — see the kdoc on
   * [NetworkEvent.requestHeaders] for the full story.
   */
  internal fun redactRequestHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (key, value) ->
      if (key.lowercase() == "authorization") REDACTED_VALUE else value
    }

  /** Same scrub-value pattern as [redactRequestHeaders], for response-side sensitive headers. */
  internal fun redactResponseHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (key, value) ->
      if (key.lowercase() == "set-cookie") REDACTED_VALUE else value
    }

  private fun safeHeaders(block: () -> Map<String, String>): Map<String, String> =
    runCatching(block).getOrDefault(emptyMap())

  internal fun pathOf(url: String): String = runCatching {
    URI(url).rawPath ?: ""
  }.getOrDefault("")

  private fun summarizeDrops(): String? {
    val req = droppedRequestBodies.get()
    val res = droppedResponseBodies.get()
    val writes = droppedWrites.get()
    val enqueues = droppedEnqueues.get()
    if (req == 0 && res == 0 && writes == 0 && enqueues == 0) return null
    return "WebNetworkCapture stopped for session=$sessionId — " +
      "dropped: requestBody=$req responseBody=$res writes=$writes queueOverflow=$enqueues"
  }

  private fun logSwallowed(where: String, t: Throwable) {
    Console.log("WebNetworkCapture.$where swallowed: ${t::class.simpleName}: ${t.message}")
  }

  companion object {
    const val NDJSON_FILENAME: String = "network.ndjson"
    const val BODIES_DIRNAME: String = "bodies"

    /** Bodies up to and including this size inline as text in the NDJSON line. */
    const val INLINE_BODY_LIMIT_BYTES: Int = 4 * 1024

    /**
     * Hard cap on persisted body bytes per event. Anything larger gets truncated
     * to this size on disk with [BodyRef.truncated] = true. Caps the worst-case
     * session-bundle blowup from a single large asset (image, video chunk, etc.)
     * — note `Response.body()` still loads the full payload into memory before
     * we truncate; we accept that since Playwright doesn't expose a streaming read.
     */
    const val MAX_BLOB_BYTES: Int = 1 * 1024 * 1024

    /** How long the drainer idles between polls when the queue is empty. */
    private const val DRAIN_INTERVAL_MS: Long = 250L

    /** Max time [detach] waits for the drainer to flush the queued tail before its own safety drain. */
    private const val DRAIN_JOIN_TIMEOUT_MS: Long = 2_000L

    /**
     * Soft cap on un-drained queued events. A page flooding requests faster than disk can
     * drain would otherwise grow this unbounded (each entry can carry up to [MAX_BLOB_BYTES]
     * of body); beyond the cap we drop + count rather than risk memory pressure.
     */
    private const val MAX_QUEUED_EVENTS: Int = 10_000

    private val JSON: Json = Json {
      encodeDefaults = false
      explicitNulls = false
    }

    // BrowserContext is a stable identity reference held by the browser manager
    // for the lifetime of the session — WeakHashMap lets the entry GC when the
    // context is closed without us tracking a separate close hook.
    private val instances: WeakHashMap<BrowserContext, WebNetworkCapture> = WeakHashMap()

    /**
     * Idempotently starts capture for [ctx]. Returns the live capture, attaching
     * listeners on first call (or after [stop]). If a previous capture exists
     * for this context but was constructed for a different session (e.g. MCP
     * host-local usage where one BrowserContext outlives multiple sessions),
     * the old listeners are detached and a fresh capture is created so events
     * route to the correct session directory. A repeat start with the same
     * session is a no-op.
     */
    @Synchronized
    fun start(
      ctx: BrowserContext,
      sessionId: String,
      sessionDir: File,
      tracker: InflightRequestTracker? = null,
    ): WebNetworkCapture {
      val existing = instances[ctx]
      if (existing != null && existing.matches(sessionId, sessionDir)) {
        if (!existing.isActive()) existing.attach(ctx)
        return existing
      }
      // Either no existing capture, or session rolled over — replace.
      existing?.detach(ctx)
      val capture = WebNetworkCapture(sessionId, sessionDir, tracker)
      instances[ctx] = capture
      capture.attach(ctx)
      return capture
    }

    /** Detaches listeners. Returns true if a capture was active and got stopped. */
    @Synchronized
    fun stop(ctx: BrowserContext): Boolean {
      val capture = instances[ctx] ?: return false
      if (!capture.isActive()) return false
      capture.detach(ctx)
      return true
    }

    /** Returns the active capture for [ctx], or null if none has been started. */
    @Synchronized
    fun get(ctx: BrowserContext): WebNetworkCapture? = instances[ctx]
  }
}
