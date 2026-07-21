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

  private fun jsonOf(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
}
