package xyz.block.trailblaze.playwright.network

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.network.NetworkEvent
import xyz.block.trailblaze.network.Phase
import xyz.block.trailblaze.network.REDACTED_VALUE
import xyz.block.trailblaze.network.Source
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.Writer
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the failure-prone, easy-to-isolate parts of
 * [WebNetworkCapture] — the redaction helpers, content-type heuristic, URL
 * path extraction, and the three [WebNetworkCapture.persistBody] branches
 * (inline-text / blob / truncated).
 *
 * These don't need a real browser: they call the `internal` helpers directly
 * via the private constructor reflectively (the public `start()` factory
 * requires a `BrowserContext`).
 */
class WebNetworkCaptureUnitTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private fun newCapture(sessionDir: File = tmp.newFolder()): WebNetworkCapture {
    @Suppress("UNCHECKED_CAST")
    val ctor = WebNetworkCapture::class.java.declaredConstructors
      .single { it.parameterCount == 3 } as Constructor<WebNetworkCapture>
    ctor.isAccessible = true
    return ctor.newInstance("session-test", sessionDir, null)
  }

  // The drainer's failure modes live behind `private` and only reachable through a real
  // BrowserContext, so the tests below drive them directly.

  private fun setPrivate(cap: WebNetworkCapture, field: String, value: Any?) {
    WebNetworkCapture::class.java.getDeclaredField(field).apply { isAccessible = true }.set(cap, value)
  }

  private fun invokePrivate(cap: WebNetworkCapture, method: String): Any? =
    WebNetworkCapture::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(cap)

  private fun invokePrivateWithArg(cap: WebNetworkCapture, method: String, arg: Any): Any? =
    WebNetworkCapture::class.java.declaredMethods
      .single { it.name == method && it.parameterCount == 1 }
      .apply { isAccessible = true }
      .invoke(cap, arg)

  @Suppress("UNCHECKED_CAST")
  private fun queueOf(cap: WebNetworkCapture): java.util.Queue<Any> =
    WebNetworkCapture::class.java.getDeclaredField("queue")
      .apply { isAccessible = true }
      .get(cap) as java.util.Queue<Any>

  private fun newPendingWrite(id: String): Any {
    val ctor = Class.forName("xyz.block.trailblaze.playwright.network.WebNetworkCapture\$PendingWrite")
      // Not `.first()`: default parameter values give the class a synthetic constructor with
      // extra mask arguments alongside the real 4-arg one.
      .declaredConstructors.single { it.parameterCount == 4 }
      .apply { isAccessible = true }
    val event = NetworkEvent(
      id = id,
      sessionId = "session-test",
      phase = Phase.REQUEST_START,
      timestampMs = 0L,
      method = "GET",
      url = "https://example.com/$id",
      urlPath = "/$id",
      source = Source.PLAYWRIGHT_WEB,
    )
    return ctor.newInstance(event, null, null, 0L)
  }

  // -------- redactRequestHeaders --------

  @Test
  fun `redactRequestHeaders scrubs Authorization value regardless of case`() {
    val cap = newCapture()
    val title = cap.redactRequestHeaders(mapOf("Authorization" to "Bearer abc", "Accept" to "*/*"))
    // Key kept (so consumers see the header was sent), value scrubbed.
    assertEquals(REDACTED_VALUE, title["Authorization"])
    assertEquals("*/*", title["Accept"])

    val lower = cap.redactRequestHeaders(mapOf("authorization" to "Bearer abc"))
    assertEquals(REDACTED_VALUE, lower["authorization"])

    val upper = cap.redactRequestHeaders(mapOf("AUTHORIZATION" to "Bearer abc"))
    assertEquals(REDACTED_VALUE, upper["AUTHORIZATION"])

    // The actual token value should never appear in the returned map.
    listOf(title, lower, upper).forEach { result ->
      assertFalse(
        result.values.any { it.contains("Bearer abc") },
        "secret leaked: $result",
      )
    }
  }

  @Test
  fun `redactRequestHeaders preserves unrelated headers untouched`() {
    val cap = newCapture()
    val cleaned = cap.redactRequestHeaders(
      mapOf("X-Custom" to "v", "content-type" to "application/json"),
    )
    assertEquals(mapOf("X-Custom" to "v", "content-type" to "application/json"), cleaned)
  }

  // -------- redactResponseHeaders --------

  @Test
  fun `redactResponseHeaders scrubs Set-Cookie value regardless of case`() {
    val cap = newCapture()
    val title = cap.redactResponseHeaders(mapOf("Set-Cookie" to "sid=1", "X-Other" to "ok"))
    assertEquals(REDACTED_VALUE, title["Set-Cookie"])
    assertEquals("ok", title["X-Other"])

    val lower = cap.redactResponseHeaders(mapOf("set-cookie" to "sid=1"))
    assertEquals(REDACTED_VALUE, lower["set-cookie"])

    listOf(title, lower).forEach { result ->
      assertFalse(
        result.values.any { it.contains("sid=1") },
        "cookie value leaked: $result",
      )
    }
  }

  // -------- isLikelyText --------

  @Test
  fun `isLikelyText recognizes common textual content types`() {
    val cap = newCapture()
    listOf(
      "text/html",
      "text/plain; charset=utf-8",
      "application/json",
      "application/vnd.api+json",
      "application/xml",
      "text/xml",
      "application/javascript",
      "application/x-www-form-urlencoded",
    ).forEach { contentType ->
      assertTrue(cap.isLikelyText(contentType), "expected text: $contentType")
    }
  }

  @Test
  fun `isLikelyText returns false for binary or unknown types`() {
    val cap = newCapture()
    listOf(
      null,
      "image/png",
      "image/jpeg",
      "video/mp4",
      "application/octet-stream",
      "application/protobuf",
    ).forEach { contentType ->
      assertFalse(cap.isLikelyText(contentType), "expected binary: $contentType")
    }
  }

  // -------- pathOf --------

  @Test
  fun `pathOf extracts URL path and tolerates malformed input`() {
    val cap = newCapture()
    assertEquals("/v1/users", cap.pathOf("https://api.example.com/v1/users?id=1"))
    assertEquals("/", cap.pathOf("https://example.com/"))
    assertEquals("", cap.pathOf("https://example.com"))
    // URI parsing throws on malformed input; the helper returns "" rather
    // than blowing up the listener.
    assertEquals("", cap.pathOf("not even close to a url with spaces"))
  }

  // -------- persistBody three branches --------

  @Test
  fun `persistBody inlines a small text payload`() {
    val sessionDir = tmp.newFolder()
    val cap = newCapture(sessionDir)
    val payload = "{\"signal\":\"save\"}".toByteArray(Charsets.UTF_8)
    val ref = cap.persistBody(eventId = "abc", bytes = payload, contentType = "application/json", prefix = "req")

    assertEquals(payload.size.toLong(), ref.sizeBytes)
    assertEquals("{\"signal\":\"save\"}", ref.inlineText)
    assertNull(ref.blobPath)
    assertFalse(ref.truncated)
    // No bodies/ dir should be created when the body inlines.
    assertFalse(File(sessionDir, "bodies").exists())
  }

  @Test
  fun `persistBody writes a blob for binary payloads`() {
    val sessionDir = tmp.newFolder()
    val cap = newCapture(sessionDir)
    // 8 KB binary — bigger than INLINE_BODY_LIMIT_BYTES and non-text content
    // type. Either condition alone would force the blob path.
    val payload = ByteArray(8 * 1024) { (it % 256).toByte() }
    val ref = cap.persistBody(eventId = "evt-1", bytes = payload, contentType = "image/png", prefix = "res")

    assertEquals(payload.size.toLong(), ref.sizeBytes)
    assertNull(ref.inlineText)
    assertEquals("bodies/res_evt-1.bin", ref.blobPath)
    assertFalse(ref.truncated)
    val written = File(sessionDir, "bodies/res_evt-1.bin").readBytes()
    assertEquals(payload.size, written.size)
  }

  @Test
  fun `persistBody truncates payloads beyond MAX_BLOB_BYTES and flags it`() {
    val sessionDir = tmp.newFolder()
    val cap = newCapture(sessionDir)
    val originalSize = WebNetworkCapture.MAX_BLOB_BYTES + 200_000
    val payload = ByteArray(originalSize) { (it % 256).toByte() }
    val ref = cap.persistBody(eventId = "big", bytes = payload, contentType = "application/octet-stream", prefix = "res")

    // sizeBytes reports the original payload so the renderer can show real magnitude.
    assertEquals(originalSize.toLong(), ref.sizeBytes)
    assertTrue(ref.truncated)
    assertEquals("bodies/res_big.bin", ref.blobPath)
    val written = File(sessionDir, "bodies/res_big.bin").readBytes()
    // The on-disk blob is capped at MAX_BLOB_BYTES regardless of payload size.
    assertEquals(WebNetworkCapture.MAX_BLOB_BYTES, written.size)
  }

  @Test
  fun `a body clamped before queueing still reports its true size and truncation`() {
    // The listener thread trims oversized bodies to MAX_BLOB_BYTES before they enter the
    // queue, so persistBody sees bytes that are already exactly at the cap. Without the
    // pre-clamp size riding along, that looks indistinguishable from a body that happened
    // to be exactly MAX_BLOB_BYTES: no truncation badge, and sizeBytes understating a
    // multi-MB upload by however much was trimmed.
    val sessionDir = tmp.newFolder()
    val cap = newCapture(sessionDir)
    val trueSize = WebNetworkCapture.MAX_BLOB_BYTES + 3_000_000L
    val clamped = ByteArray(WebNetworkCapture.MAX_BLOB_BYTES) { (it % 256).toByte() }

    val ref = cap.persistBody(
      eventId = "clamped",
      bytes = clamped,
      contentType = "application/octet-stream",
      prefix = "req",
      originalSizeBytes = trueSize,
    )

    assertEquals(trueSize, ref.sizeBytes)
    assertTrue(ref.truncated, "A clamped body must still be badged as truncated.")
    assertEquals(
      WebNetworkCapture.MAX_BLOB_BYTES,
      File(sessionDir, "bodies/req_clamped.bin").readBytes().size,
    )
  }

  /**
   * The drain thread's exit condition is `stopped AND queue empty` — but an entry can be
   * queued by a listener already in flight when stop() closed the writer, and that entry
   * can never be written. Without a writer-gone exit the loop makes no progress and never
   * satisfies its condition either: a daemon thread waking every 250ms for the life of the
   * process, holding the queued bodies. Driven directly here because the window is a race
   * between Playwright's dispatch thread and stop() that a test can't schedule.
   */
  @Test
  fun `the drain loop exits instead of spinning on a queue it can never write`() {
    val cap = newCapture()
    // Post-stop state: not active, writer closed, one entry still queued.
    val queue = queueOf(cap)
    queue.add(newPendingWrite("stranded"))

    val thread = Thread { invokePrivate(cap, "runDrainLoop") }.apply { isDaemon = true; start() }
    // Generous ceiling: this is hang containment, not a speed assertion. The loop either
    // returns straight away or never does.
    thread.join(10_000)

    assertFalse(thread.isAlive, "the drain loop must return when the writer is gone")
    assertTrue(queue.isEmpty(), "the unwritable entry must be discarded, not left queued")
  }

  /**
   * A BufferedWriter accepts lines into memory and only touches the disk on flush, so a full
   * or read-only filesystem raises at flush time — after the entries have already left the
   * queue. Swallowing that loses the batch while the stop summary reports zero writes
   * dropped, which is the one signal telling an operator the NDJSON is incomplete.
   */
  @Test
  fun `a failing flush is charged to the dropped-write count`() {
    val cap = newCapture()
    val failing = BufferedWriter(
      object : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush(): Unit = throw IOException("no space left on device")
        override fun close() = Unit
      },
    )
    setPrivate(cap, "ndjsonWriter", failing)
    queueOf(cap).add(newPendingWrite("stranded"))

    invokePrivate(cap, "runDrainLoop")

    val summary = invokePrivate(cap, "summarizeDrops") as String?
    assertNotNull(summary, "A lost batch must be summarized, not reported as a clean stop.")
    assertTrue(summary.contains("writes=1"), "expected one dropped write, got: $summary")
  }

  /**
   * `enqueue`'s admission check is not atomic with its publication, and stop() can complete
   * entirely in that window — closing the writer and retiring the drainer, so nothing is left
   * to notice the entry. The queue is swapped for one that flips `active` inside `add`, which
   * puts stop() exactly where the real race puts it rather than hoping a thread lands there.
   */
  @Test
  fun `an event published while stop is winning the race is reclaimed, not stranded`() {
    val cap = newCapture()
    val active = WebNetworkCapture::class.java.getDeclaredField("active")
      .apply { isAccessible = true }
      .get(cap) as AtomicBoolean
    active.set(true)
    val racingQueue = object : ConcurrentLinkedQueue<Any>() {
      override fun add(element: Any): Boolean {
        active.set(false)
        return super.add(element)
      }
    }
    setPrivate(cap, "queue", racingQueue)

    invokePrivateWithArg(cap, "enqueue", newPendingWrite("late"))

    assertTrue(racingQueue.isEmpty(), "A post-stop entry must not be left queued forever.")
    val summary = invokePrivate(cap, "summarizeDrops") as String?
    assertNotNull(summary, "Reclaiming an event is a drop and must be summarized.")
    assertTrue(summary.contains("queueOverflow=1"), "expected one dropped enqueue, got: $summary")
  }

  @Test
  fun `persistBody pure helpers cover the three exit modes`() {
    val cap = newCapture()
    val outcomes = listOf(
      Triple("application/json", "hi".toByteArray(), "inline"),
      Triple("image/png", ByteArray(8 * 1024), "blob"),
      Triple(
        "application/octet-stream",
        ByteArray(WebNetworkCapture.MAX_BLOB_BYTES + 1),
        "truncated",
      ),
    )
    val seen = outcomes.map { (ct, bytes, _) ->
      val ref = cap.persistBody("id", bytes, ct, "req")
      when {
        ref.inlineText != null -> "inline"
        ref.truncated -> "truncated"
        ref.blobPath != null -> "blob"
        else -> "unknown"
      }
    }
    // Pin the three exit modes — guards against a future refactor collapsing
    // the inline path into the blob path or vice versa.
    assertEquals(setOf("inline", "blob", "truncated"), seen.toSet())
  }

}
