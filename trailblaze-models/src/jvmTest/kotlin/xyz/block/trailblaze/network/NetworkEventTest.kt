package xyz.block.trailblaze.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the on-disk NDJSON contract for [NetworkEvent]. Web (Playwright) and on-device
 * mobile capture writers both emit this shape, so the renderer and any downstream
 * readers depend on it.
 *
 * Round-trip + key shape assertions — no behavioral coverage of capture itself.
 */
class NetworkEventTest {

  private val json = Json {
    encodeDefaults = false
    explicitNulls = false
  }

  @Test
  fun `request start round-trips`() {
    val event = NetworkEvent(
      id = "abc-123",
      sessionId = "session-1",
      timestampMs = 1_700_000_000_000L,
      phase = Phase.REQUEST_START,
      method = "POST",
      url = "https://api.example.com/cdp/track?u=1",
      urlPath = "/cdp/track",
      requestHeaders = mapOf("content-type" to "application/json"),
      requestBodyRef = BodyRef(
        sizeBytes = 42L,
        contentType = "application/json",
        inlineText = "{\"signal\":\"save\"}",
      ),
      source = Source.PLAYWRIGHT_WEB,
    )
    val line = json.encodeToString(NetworkEvent.serializer(), event)
    val decoded = json.decodeFromString(NetworkEvent.serializer(), line)
    assertEquals(event, decoded)
  }

  @Test
  fun `response end carries status and duration`() {
    val event = NetworkEvent(
      id = "abc-123",
      sessionId = "session-1",
      timestampMs = 1_700_000_000_500L,
      phase = Phase.RESPONSE_END,
      method = "POST",
      url = "https://api.example.com/cdp/track",
      urlPath = "/cdp/track",
      statusCode = 204,
      durationMs = 500L,
      responseHeaders = mapOf("content-type" to "application/json"),
      responseBodyRef = BodyRef(
        sizeBytes = 8192L,
        contentType = "application/octet-stream",
        blobPath = "bodies/res_abc-123.bin",
      ),
      source = Source.PLAYWRIGHT_WEB,
    )
    val decoded = json.decodeFromString(
      NetworkEvent.serializer(),
      json.encodeToString(NetworkEvent.serializer(), event),
    )
    assertEquals(event, decoded)
    assertEquals(204, decoded.statusCode)
    assertEquals(500L, decoded.durationMs)
    assertNull(decoded.responseBodyRef!!.inlineText)
  }

  @Test
  fun `redacted header value is the agreed placeholder`() {
    // Sensitive header values are replaced in-place with REDACTED_VALUE so
    // the consumer sees the header was sent without exposing the secret.
    // This pins the placeholder string — anything reading the NDJSON later
    // (renderers, HAR converters, downstream tools) can match on it.
    val event = NetworkEvent(
      id = "abc-123",
      sessionId = "session-1",
      timestampMs = 1_700_000_000_000L,
      phase = Phase.REQUEST_START,
      method = "GET",
      url = "https://api.example.com/me",
      urlPath = "/me",
      requestHeaders = mapOf(
        "accept" to "application/json",
        "authorization" to REDACTED_VALUE,
      ),
      source = Source.PLAYWRIGHT_WEB,
    )
    val decoded = json.decodeFromString(
      NetworkEvent.serializer(),
      json.encodeToString(NetworkEvent.serializer(), event),
    )
    assertEquals("***REDACTED***", REDACTED_VALUE)
    assertEquals(REDACTED_VALUE, decoded.requestHeaders!!["authorization"])
    assertEquals("application/json", decoded.requestHeaders!!["accept"])
  }

  @Test
  fun `truncated flag round-trips on body ref`() {
    // Pin the contract for the renderer's "truncated" badge — sizeBytes keeps
    // the original payload size while the on-disk blob is capped.
    val ref = BodyRef(
      sizeBytes = 5_000_000L,
      contentType = "application/octet-stream",
      blobPath = "bodies/res_abc-123.bin",
      truncated = true,
    )
    val decoded = json.decodeFromString(
      BodyRef.serializer(),
      json.encodeToString(BodyRef.serializer(), ref),
    )
    assertEquals(ref, decoded)
    assertTrue(decoded.truncated)
    assertEquals(5_000_000L, decoded.sizeBytes)
  }

  @Test
  fun `mobile sources are part of the contract`() {
    // Locks in the enum values so the renderer and downstream consumers can rely on them.
    assertEquals(3, Source.entries.size)
    assertEquals(Source.ANDROID, Source.valueOf("ANDROID"))
    assertEquals(Source.IOS, Source.valueOf("IOS"))
  }
}
