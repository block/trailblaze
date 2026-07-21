package xyz.block.trailblaze.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the env+config resolution that gates the stream-screenshot path — it controls whether
 * a side-effectful pipeline (screenrecord + ffmpeg) is started, so accepted values, the
 * config-enables-STREAM path, and the AB-over-everything precedence must not drift.
 */
class StreamScreenshotModeTest {

  @Test
  fun `unset or unrecognized env with config off stays off`() {
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.fromValues(null, null, false))
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.fromValues("", null, false))
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.fromValues("0", null, false))
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.fromValues("false", null, false))
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.fromValues("yes", null, false))
  }

  @Test
  fun `env 1 and true enable STREAM, case-insensitively`() {
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.fromValues("1", null, false))
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.fromValues("true", null, false))
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.fromValues("TRUE", null, false))
  }

  @Test
  fun `config toggle enables STREAM even when env is unset`() {
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.fromValues(null, null, true))
    // An explicit falsey env value does NOT override the config toggle — env only ever enables.
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.fromValues("0", null, true))
  }

  @Test
  fun `env AB enables AB_COMPARE`() {
    assertEquals(StreamScreenshotMode.AB_COMPARE, StreamScreenshotMode.fromValues(null, "True", false))
  }

  @Test
  fun `AB takes precedence over both STREAM sources`() {
    assertEquals(StreamScreenshotMode.AB_COMPARE, StreamScreenshotMode.fromValues("1", "1", false))
    // AB wins even when only the config toggle (not the env STREAM flag) is on.
    assertEquals(StreamScreenshotMode.AB_COMPARE, StreamScreenshotMode.fromValues(null, "1", true))
  }
}
