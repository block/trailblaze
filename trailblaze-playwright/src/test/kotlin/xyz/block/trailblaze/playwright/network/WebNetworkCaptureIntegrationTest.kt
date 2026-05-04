package xyz.block.trailblaze.playwright.network

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.network.InflightRequestTracker
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives [WebNetworkCapture] end-to-end against a [PlaywrightTestProxies]
 * fake `BrowserContext` so the listener wiring, session-reset behavior, and
 * tracker integration can be tested without launching a real browser.
 */
class WebNetworkCaptureIntegrationTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `session reset detaches old listeners and routes events to the new session dir`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dirA = tmp.newFolder("session-a")
    val dirB = tmp.newFolder("session-b")

    val captureA = WebNetworkCapture.start(ctx.proxy, "session-a", dirA)
    assertEquals(1, ctx.requestListeners.size)
    assertTrue(captureA.isActive())
    assertEquals(File(dirA, "network.ndjson"), captureA.ndjsonPath())

    val captureB = WebNetworkCapture.start(ctx.proxy, "session-b", dirB)
    // Old capture is detached; only the new one's listeners remain.
    assertEquals(1, ctx.requestListeners.size)
    assertEquals(1, ctx.responseListeners.size)
    assertEquals(1, ctx.requestFailedListeners.size)
    assertFalse(captureA.isActive())
    assertTrue(captureB.isActive())
    assertEquals(File(dirB, "network.ndjson"), captureB.ndjsonPath())

    WebNetworkCapture.stop(ctx.proxy)
  }

  @Test
  fun `start with same session is a no-op`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    val first = WebNetworkCapture.start(ctx.proxy, "s", dir)
    val second = WebNetworkCapture.start(ctx.proxy, "s", dir)
    assertEquals(1, ctx.requestListeners.size)
    assertSame(first, second)
    WebNetworkCapture.stop(ctx.proxy)
  }

  @Test
  fun `tracker reflects in-flight while request is open and idle after response`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    val tracker = InflightRequestTracker()

    WebNetworkCapture.start(ctx.proxy, "s", dir, tracker)
    assertTrue(tracker.isIdle())

    val req = PlaywrightTestProxies.fakeRequest(
      method = "GET",
      url = "https://api.example.com/me",
      headers = mapOf("accept" to "application/json"),
    )
    ctx.fireRequest(req)
    assertFalse(tracker.isIdle())
    assertEquals(1, tracker.inflightCount())

    val matched = tracker.inflightMatching(Regex(".*api\\.example\\.com.*"))
    assertEquals(1, matched.size)

    val res = PlaywrightTestProxies.fakeResponse(req, status = 200, body = ByteArray(0))
    ctx.fireResponse(res)
    assertTrue(tracker.isIdle())
    assertEquals(0, tracker.inflightCount())

    WebNetworkCapture.stop(ctx.proxy)
  }

  @Test
  fun `failed request also marks tracker idle`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    val tracker = InflightRequestTracker()

    WebNetworkCapture.start(ctx.proxy, "s", dir, tracker)
    val req = PlaywrightTestProxies.fakeRequest(url = "https://api.example.com/will-fail")
    ctx.fireRequest(req)
    assertFalse(tracker.isIdle())

    ctx.fireRequestFailed(req)
    assertTrue(tracker.isIdle())

    WebNetworkCapture.stop(ctx.proxy)
  }

  @Test
  fun `events written through full pipeline land as one NDJSON line per phase`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()

    WebNetworkCapture.start(ctx.proxy, "s", dir)
    val req = PlaywrightTestProxies.fakeRequest(
      method = "POST",
      url = "https://api.example.com/cdp/track",
      headers = mapOf("content-type" to "application/json"),
      postBody = "{\"signal\":\"save\"}".toByteArray(),
    )
    ctx.fireRequest(req)
    val res = PlaywrightTestProxies.fakeResponse(
      req,
      status = 204,
      headers = mapOf("set-cookie" to "sid=1"),
      body = ByteArray(0),
    )
    ctx.fireResponse(res)

    WebNetworkCapture.stop(ctx.proxy)

    val ndjson = File(dir, "network.ndjson").readText()
    val lines = ndjson.split('\n').filter { it.isNotBlank() }
    assertEquals(2, lines.size)
    assertTrue(lines[0].contains("\"REQUEST_START\""), "first line should be REQUEST_START: ${lines[0]}")
    assertTrue(lines[1].contains("\"RESPONSE_END\""), "second line should be RESPONSE_END: ${lines[1]}")
    // Set-Cookie key kept in the headers map so consumers see the header was
    // sent, value replaced with the placeholder so the secret can't leak.
    assertTrue(lines[1].contains("\"set-cookie\""), "set-cookie key should still appear: ${lines[1]}")
    assertTrue(lines[1].contains("***REDACTED***"), "set-cookie value should be the placeholder: ${lines[1]}")
    // The actual cookie value must not appear in the persisted line.
    assertFalse(lines[1].contains("sid=1"), "cookie value must not be persisted")
  }

  @Test
  fun `clean session ends with empty network ndjson and no body sidecar dir`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    WebNetworkCapture.start(ctx.proxy, "s", dir)
    WebNetworkCapture.stop(ctx.proxy)
    val ndjson = File(dir, "network.ndjson")
    // network.ndjson exists from the touch-on-attach behavior, but is empty.
    assertTrue(ndjson.exists())
    assertEquals(0L, ndjson.length())
    assertFalse(File(dir, "bodies").exists())
  }

  @Test
  fun `handler that fires after stop does not write past detach`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    WebNetworkCapture.start(ctx.proxy, "s", dir)
    val capturedListener = ctx.requestListeners.single()
    WebNetworkCapture.stop(ctx.proxy)

    // Simulate a listener that was already in flight when stop ran, by
    // invoking the captured Consumer reference directly. The active re-check
    // inside writeEvent should drop the write rather than reopen the closed
    // BufferedWriter.
    val req = PlaywrightTestProxies.fakeRequest(url = "https://example.com/late")
    capturedListener.accept(req)

    val ndjson = File(dir, "network.ndjson")
    assertEquals(0L, ndjson.length())
  }

  @Test
  fun `request body large enough to truncate writes to bodies dir`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    WebNetworkCapture.start(ctx.proxy, "s", dir)
    val big = ByteArray(WebNetworkCapture.MAX_BLOB_BYTES + 1024) { 'x'.code.toByte() }
    val req = PlaywrightTestProxies.fakeRequest(
      method = "POST",
      url = "https://example.com/upload",
      headers = mapOf("content-type" to "application/octet-stream"),
      postBody = big,
    )
    ctx.fireRequest(req)
    WebNetworkCapture.stop(ctx.proxy)

    val ndjson = File(dir, "network.ndjson").readText()
    assertTrue(ndjson.contains("\"truncated\":true"), "ndjson should mark truncated: $ndjson")
    val bodiesDir = File(dir, "bodies")
    assertTrue(bodiesDir.exists())
    val blobs = bodiesDir.listFiles()
    assertNotNull(blobs)
    assertEquals(1, blobs!!.size)
    assertEquals(WebNetworkCapture.MAX_BLOB_BYTES.toLong(), blobs[0].length())
    // sizeBytes in the NDJSON should preserve the original size.
    assertTrue(ndjson.contains("\"sizeBytes\":${big.size}"))
  }

  @Test
  fun `response bodies are never fetched on the listener thread`() {
    // Regression on the regression: pre-fetch filters by content-type and
    // Content-Length aren't enough — any single text response without
    // Content-Length still calls body() and blocks the Playwright thread.
    // Observed in production as 44 REQUEST_START / 0 RESPONSE_END after
    // navigating to a site whose first response's body() never returned in
    // the test window, and every subsequent response listener queued behind
    // it. The fix is the strongest one: never call
    // response.body() on the hot path. RESPONSE_END still emits with status
    // + headers + duration; responseBodyRef is always null.
    //
    // Across three different response shapes — binary, declared-huge,
    // chunked-without-Content-Length — we assert that NONE produce a
    // responseBodyRef AND no bodies/ dir is ever created. If any future
    // change starts calling body() again, this test will catch it without
    // needing a real browser.
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dir = tmp.newFolder()
    WebNetworkCapture.start(ctx.proxy, "s", dir)

    val cases = listOf(
      // Binary — would otherwise blob.
      Triple("https://example.com/logo.png", "image/png", null),
      // Declared-huge text — would otherwise emit a truncated placeholder.
      Triple(
        "https://example.com/vendor.js",
        "application/javascript",
        WebNetworkCapture.MAX_BLOB_BYTES.toLong() * 5,
      ),
      // Chunked text with no Content-Length — the case that bit us in prod.
      Triple("https://example.com/api/data", "application/json", null),
    )
    cases.forEach { (url, contentType, contentLength) ->
      val req = PlaywrightTestProxies.fakeRequest(url = url)
      ctx.fireRequest(req)
      val headers = buildMap<String, String> {
        put("content-type", contentType)
        contentLength?.let { put("content-length", it.toString()) }
      }
      val res = PlaywrightTestProxies.fakeResponse(
        req,
        status = 200,
        headers = headers,
        // If the contract regressed and body() were called, this byte array
        // would be persisted somewhere — we'd see it in the assertions below.
        body = ByteArray(8 * 1024) { 0xFF.toByte() },
      )
      ctx.fireResponse(res)
    }

    WebNetworkCapture.stop(ctx.proxy)

    val ndjson = File(dir, "network.ndjson").readText()
    val responseLines = ndjson.lines().filter { it.contains("\"RESPONSE_END\"") }
    assertEquals(cases.size, responseLines.size, "every response should emit RESPONSE_END")
    responseLines.forEachIndexed { idx, line ->
      assertTrue(line.contains("\"statusCode\":200"), "[$idx] status persisted: $line")
      assertFalse(line.contains("responseBodyRef"), "[$idx] no responseBodyRef: $line")
    }
    // No bodies/ dir ever — body() was never called, nothing to persist.
    assertFalse(
      File(dir, "bodies").exists(),
      "bodies dir must not exist; the hot path no longer fetches response bytes",
    )
  }

  @Test
  fun `tracker is reset on session rollover so stale ids do not pin idle false`() {
    val ctx = PlaywrightTestProxies.FakeBrowserContext()
    val dirA = tmp.newFolder("a")
    val dirB = tmp.newFolder("b")
    val tracker = InflightRequestTracker()

    WebNetworkCapture.start(ctx.proxy, "a", dirA, tracker)
    val req = PlaywrightTestProxies.fakeRequest(url = "https://example.com/never-finishes")
    ctx.fireRequest(req)
    assertFalse(tracker.isIdle())
    assertTrue(tracker.inflightCount() > 0)

    // Rolling to a new session detaches the previous capture, which ends any
    // still-in-flight ids on the tracker so the next session starts clean.
    WebNetworkCapture.start(ctx.proxy, "b", dirB, tracker)
    assertTrue(tracker.isIdle())

    WebNetworkCapture.stop(ctx.proxy)
  }
}
