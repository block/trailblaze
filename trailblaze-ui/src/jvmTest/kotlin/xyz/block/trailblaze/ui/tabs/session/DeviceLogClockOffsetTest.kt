package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The device-log panel scrubs on the host timeline while its lines carry the device's own stamps,
 * so this offset is what makes auto-scroll land on the line a step actually produced. Getting the
 * preference order wrong is silent: the panel still scrolls, just to the wrong line.
 */
class DeviceLogClockOffsetTest {

  @Test
  fun `the session's measured skew wins over the first-line estimate, and inverts direction`() {
    // A device 3s behind the host: the session's device->host offset is +3000, so a host timestamp
    // maps to a device stamp 3s EARLIER. The first line landed 8s in, which would have estimated
    // 8000 — a first line is emitted whenever the device gets around to it, not at session start.
    assertEquals(
      DeviceLogClockOffset(-3_000, DeviceLogClockBasis.INGESTION_ANCHORED),
      deviceLogClockOffset(sessionDeviceClockOffsetMs = 3_000, firstDeviceLineMs = 108_000, sessionStartMs = 100_000),
    )
  }

  @Test
  fun `a measured skew of zero still beats the estimate`() {
    // 0 is a measurement, not a missing value: a session whose device clock matches the host's
    // must not fall through to an estimate that would invent an offset out of emit latency.
    assertEquals(
      DeviceLogClockOffset(0, DeviceLogClockBasis.INGESTION_ANCHORED),
      deviceLogClockOffset(sessionDeviceClockOffsetMs = 0, firstDeviceLineMs = 108_000, sessionStartMs = 100_000),
    )
  }

  @Test
  fun `an unanchored session estimates from its first parseable line`() {
    assertEquals(
      DeviceLogClockOffset(8_000, DeviceLogClockBasis.FIRST_LINE_ESTIMATE),
      deviceLogClockOffset(sessionDeviceClockOffsetMs = null, firstDeviceLineMs = 108_000, sessionStartMs = 100_000),
    )
  }

  @Test
  fun `a stream with nothing to measure against reports itself unsynced`() {
    // No parseable line at all, and the session that hasn't logged a start yet — both leave the
    // panel showing lines it cannot place, which is what UNSYNCED tells the caller to say.
    assertEquals(
      DeviceLogClockOffset(0, DeviceLogClockBasis.UNSYNCED),
      deviceLogClockOffset(sessionDeviceClockOffsetMs = null, firstDeviceLineMs = null, sessionStartMs = 100_000),
    )
    assertEquals(
      DeviceLogClockOffset(0, DeviceLogClockBasis.UNSYNCED),
      deviceLogClockOffset(sessionDeviceClockOffsetMs = null, firstDeviceLineMs = 108_000, sessionStartMs = 0),
    )
  }
}
