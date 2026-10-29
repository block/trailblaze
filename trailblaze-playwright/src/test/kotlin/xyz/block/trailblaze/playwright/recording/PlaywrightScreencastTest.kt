package xyz.block.trailblaze.playwright.recording

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [PlaywrightScreencast]'s pure CDP helpers.
 *
 * The streaming loop that consumes these needs a live browser and is exercised manually
 * against the `/devices` viewer; this file locks the wire shapes it produces and parses,
 * because a regression here silently breaks the fast web mirror (a bad ack shape stalls
 * Chrome after one frame; a bad parse drops every frame).
 */
class PlaywrightScreencastTest {

  @Test
  fun `startScreencastParams requests every jpeg frame capped at the viewport`() {
    val params = PlaywrightScreencast.startScreencastParams(maxWidth = 1280, maxHeight = 720)
    assertEquals("jpeg", params.get("format").asString)
    assertEquals(PlaywrightScreencast.DEFAULT_QUALITY, params.get("quality").asInt)
    assertEquals(1280, params.get("maxWidth").asInt)
    assertEquals(720, params.get("maxHeight").asInt)
    assertEquals(1, params.get("everyNthFrame").asInt)
  }

  @Test
  fun `startScreencastParams omits non-positive bounds so Chrome picks its own framing`() {
    val params = PlaywrightScreencast.startScreencastParams(maxWidth = 0, maxHeight = 0)
    assertEquals(false, params.has("maxWidth"))
    assertEquals(false, params.has("maxHeight"))
  }

  @Test
  fun `startScreencastParams clamps quality into the CDP-valid range`() {
    assertEquals(100, PlaywrightScreencast.startScreencastParams(1, 1, quality = 250).get("quality").asInt)
    assertEquals(0, PlaywrightScreencast.startScreencastParams(1, 1, quality = -5).get("quality").asInt)
  }

  @Test
  fun `ackParams echoes the session id Chrome expects`() {
    assertEquals(42, PlaywrightScreencast.ackParams(42).get("sessionId").asInt)
  }

  @Test
  fun `parseScreencastFrame reads data and sessionId from a real event body`() {
    val event = JsonParser.parseString(
      """{"data":"/9j/BASE64JPEG","sessionId":7,"metadata":{"deviceWidth":800}}""",
    ).asJsonObject
    val frame = PlaywrightScreencast.parseScreencastFrame(event)
    assertEquals("/9j/BASE64JPEG", frame?.dataBase64)
    assertEquals(7, frame?.sessionId)
  }

  @Test
  fun `parseScreencastFrame returns null when data is missing`() {
    assertNull(PlaywrightScreencast.parseScreencastFrame(jsonOf("""{"sessionId":1}""")))
  }

  @Test
  fun `parseScreencastFrame returns null when data is empty`() {
    assertNull(PlaywrightScreencast.parseScreencastFrame(jsonOf("""{"data":"","sessionId":1}""")))
  }

  @Test
  fun `parseScreencastFrame returns null when sessionId is missing`() {
    assertNull(PlaywrightScreencast.parseScreencastFrame(jsonOf("""{"data":"/9j/x"}""")))
  }

  @Test
  fun `parseScreencastFrame returns null when sessionId is not a number`() {
    assertNull(PlaywrightScreencast.parseScreencastFrame(jsonOf("""{"data":"/9j/x","sessionId":"nope"}""")))
  }

  @Test
  fun `parseScreencastFrame reads the swap time Chrome stamps in metadata as epoch ms`() {
    val frame = PlaywrightScreencast.parseScreencastFrame(
      jsonOf("""{"data":"x","sessionId":1,"metadata":{"timestamp":1759400000.1234}}"""),
    )
    assertEquals(1_759_400_000_123L, frame?.swappedAtMs)
  }

  @Test
  fun `FrameClock places a frame at its swap, not at when it was read`() {
    val clock = PlaywrightScreencast.FrameClock()
    // A frame read promptly teaches the clock this browser's delivery delay: 2 ms.
    assertEquals(9_002L, clock.capturedAtAt(swappedAtMs = 9_000L, receivedAtMs = 9_002L))
    // A frame read 300 ms late, while the Playwright thread was busy, still lands at its swap.
    assertEquals(10_002L, clock.capturedAtAt(swappedAtMs = 10_000L, receivedAtMs = 10_302L))
  }

  @Test
  fun `FrameClock moves a remote browser's swap times onto this host's clock`() {
    for (browserClockSkewMs in listOf(-5_000L, 5_000L, 120_000L)) {
      val clock = PlaywrightScreencast.FrameClock()
      // Host instants a frame was shown, and how long each waited to be read.
      val shownAt = listOf(100_000L, 101_000L, 102_000L)
      val delays = listOf(40L, 2L, 300L)
      val stamps = shownAt.zip(delays).map { (shown, delay) ->
        clock.capturedAtAt(swappedAtMs = shown + browserClockSkewMs, receivedAtMs = shown + delay)
      }
      // Once a prompt frame has been seen, every frame is within that frame's delay of when it showed.
      assertEquals(listOf(101_002L, 102_002L), stamps.drop(1), "skew $browserClockSkewMs")
      // Before then the clock can't tell delay from skew; the first frame is stamped on receipt.
      assertEquals(100_040L, stamps.first(), "skew $browserClockSkewMs")
    }
  }

  @Test
  fun `FrameClock stamps a frame with no swap time on receipt`() {
    val frame = PlaywrightScreencast.ScreencastFrame("x", 1, swappedAtMs = null)
    assertEquals(5_000L, PlaywrightScreencast.FrameClock().capturedAtMs(frame, receivedAtMs = 5_000L))
  }

  private fun PlaywrightScreencast.FrameClock.capturedAtAt(swappedAtMs: Long, receivedAtMs: Long): Long =
    capturedAtMs(PlaywrightScreencast.ScreencastFrame("x", 1, swappedAtMs), receivedAtMs)

  private fun jsonOf(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
}
