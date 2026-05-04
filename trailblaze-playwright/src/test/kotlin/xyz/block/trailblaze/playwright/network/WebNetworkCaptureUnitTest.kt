package xyz.block.trailblaze.playwright.network

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.network.REDACTED_VALUE
import java.io.File
import java.lang.reflect.Constructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
