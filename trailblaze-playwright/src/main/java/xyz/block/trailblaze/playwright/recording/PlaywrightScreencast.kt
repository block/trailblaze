package xyz.block.trailblaze.playwright.recording

import com.google.gson.JsonObject

/**
 * Pure helpers for driving a Chromium CDP screencast (`Page.startScreencast`) — the
 * high-frame-rate source for the web device mirror in the `/devices` viewer.
 *
 * These are split out from [PlaywrightDeviceScreenStream] so the CDP wire shapes (start
 * params, per-frame ack params, and `Page.screencastFrame` parsing) can be unit-tested
 * without standing up a live browser. The streaming loop that actually talks to the page —
 * [PlaywrightDeviceScreenStream.streamScreencastJpegFrames] — is the only impure part and
 * relies entirely on these helpers for the shapes it sends and receives.
 */
internal object PlaywrightScreencast {

  /**
   * A decoded `Page.screencastFrame` event: the still-base64 JPEG payload plus the
   * `sessionId` Chrome expects echoed back in the ack before it emits the next frame.
   */
  data class ScreencastFrame(val dataBase64: String, val sessionId: Int)

  /**
   * Params for `Page.startScreencast`.
   *
   * `jpeg` at a moderate [quality] keeps per-frame bytes small enough to stream at encoder
   * rate over a local WebSocket; `everyNthFrame = 1` asks Chrome for every composited frame.
   * [maxWidth]/[maxHeight] cap the frame at the device viewport so Chrome doesn't upscale on
   * hi-dpi displays — passed only when positive so a not-yet-sized page just gets Chrome's
   * default framing.
   */
  fun startScreencastParams(
    maxWidth: Int,
    maxHeight: Int,
    quality: Int = DEFAULT_QUALITY,
  ): JsonObject = JsonObject().apply {
    addProperty("format", "jpeg")
    addProperty("quality", quality.coerceIn(0, 100))
    if (maxWidth > 0) addProperty("maxWidth", maxWidth)
    if (maxHeight > 0) addProperty("maxHeight", maxHeight)
    addProperty("everyNthFrame", 1)
  }

  /** Params for `Page.screencastFrameAck` — Chrome won't emit the next frame until acked. */
  fun ackParams(sessionId: Int): JsonObject = JsonObject().apply {
    addProperty("sessionId", sessionId)
  }

  /**
   * Extracts the JPEG payload + ack id from a `Page.screencastFrame` event body. Returns
   * `null` if either field is missing or malformed (e.g. a non-numeric `sessionId`, an empty
   * `data`) so a stray or partial event can never crash the stream — the caller just skips it.
   */
  fun parseScreencastFrame(event: JsonObject): ScreencastFrame? {
    val dataElement = event.get("data")?.takeIf { it.isJsonPrimitive } ?: return null
    val sessionElement = event.get("sessionId")?.takeIf { it.isJsonPrimitive } ?: return null
    val data = dataElement.asString
    if (data.isEmpty()) return null
    val sessionId = try {
      sessionElement.asInt
    } catch (_: NumberFormatException) {
      return null
    }
    return ScreencastFrame(dataBase64 = data, sessionId = sessionId)
  }

  /** Balances frame smoothness against per-frame size for a local-daemon WebSocket. */
  const val DEFAULT_QUALITY = 60
}
