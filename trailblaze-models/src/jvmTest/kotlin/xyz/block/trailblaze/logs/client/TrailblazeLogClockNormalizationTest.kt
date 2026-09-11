package xyz.block.trailblaze.logs.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.logs.model.getSessionInfo

/**
 * Behavior of the shared device→host clock normalization every reader derives its offsets from.
 *
 * The scenario throughout is the one that motivated the field: a device whose clock trails the
 * host's, so its raw stamps land BEFORE host-stamped logs that actually happened first.
 */
class TrailblazeLogClockNormalizationTest {

  private val session = SessionId("clock-normalization-test")

  @Test
  fun `a session with no anchored device log has no offsets and normalizes nothing`() {
    val logs = listOf(
      hostLog(atMs = 1_000),
      toolLog(startMs = 1_100, durationMs = 50, clock = TrailblazeClockDomain.HOST),
      // Device-stamped but never anchored at host ingestion (pulled off the device after the
      // fact): there is nothing to measure the skew against.
      toolLog(startMs = 900, durationMs = 50, clock = TrailblazeClockDomain.DEVICE),
    )

    assertNull(logs.deviceClockOffsets())
    assertEquals(900, logs.last().normalizedMs(logs.deviceClockOffsets()))
    assertSame(logs, logs.normalizedToHostClock())
  }

  @Test
  fun `the offset is the minimum sample, so a slow upload cannot shift spans late`() {
    val logs = listOf(
      // Same device, same true skew of 1000ms. The second upload sat in a batch for 5s, which
      // inflates only its own sample.
      anchoredDeviceToolLog(hostStartMs = 2_000, durationMs = 100, skewMs = 1_000),
      anchoredDeviceToolLog(hostStartMs = 3_000, durationMs = 100, skewMs = 1_000, latencyMs = 5_000),
    )

    val offsets = assertNotNull(logs.deviceClockOffsets())
    assertEquals(1_000, offsets.sessionWideOffsetMs)
    assertEquals(2_000, logs[0].normalizedMs(offsets))
    assertEquals(3_000, logs[1].normalizedMs(offsets))
  }

  @Test
  fun `each device is offset by its own skew, and an unkeyed log takes the session-wide minimum`() {
    val logs = listOf(
      anchoredDeviceToolLog(hostStartMs = 2_000, durationMs = 100, skewMs = 1_000, deviceName = "seller"),
      anchoredDeviceToolLog(hostStartMs = 2_500, durationMs = 100, skewMs = 4_000, deviceName = "buyer"),
      // A device-stamped log with no device to key on, because it isn't a tool log.
      statusLog(atMs = 2_600, clock = TrailblazeClockDomain.DEVICE),
    )

    val offsets = assertNotNull(logs.deviceClockOffsets())
    assertEquals(1_000, offsets.offsetMsFor(logs[0]))
    assertEquals(4_000, offsets.offsetMsFor(logs[1]))
    // Not "the first device" and not a blend — the smallest measured skew, which is what every
    // reader used session-wide before per-device keying existed.
    assertEquals(1_000, offsets.sessionWideOffsetMs)
    assertEquals(1_000, offsets.offsetMsFor(logs[2]))
    assertEquals(3_600, logs[2].normalizedMs(offsets))
  }

  @Test
  fun `a host-stamped log is left alone even when the session has an offset`() {
    val logs = listOf(
      anchoredDeviceToolLog(hostStartMs = 2_000, durationMs = 100, skewMs = 1_000),
      hostLog(atMs = 2_500),
      // Written before the clock field existed: unmarked means host, same as it always read.
      hostLog(atMs = 2_600, clock = null),
    )

    val offsets = assertNotNull(logs.deviceClockOffsets())
    assertEquals(2_500, logs[1].normalizedMs(offsets))
    assertEquals(2_600, logs[2].normalizedMs(offsets))
  }

  @Test
  fun `normalizedToHostClock restamps device logs onto one timeline and marks them host`() {
    val device = anchoredDeviceToolLog(hostStartMs = 2_000, durationMs = 100, skewMs = 1_000)
    val host = hostLog(atMs = 1_500)
    val normalized = listOf(device, host).normalizedToHostClock()

    val restamped = normalized[0]
    assertEquals(2_000, restamped.timestamp.toEpochMilliseconds())
    assertEquals(TrailblazeClockDomain.HOST, restamped.clock)
    // The anchor stays as the provenance of the shift.
    assertEquals(device.hostReceivedAt, restamped.hostReceivedAt)
    // Input order is preserved: the device log was first in the list and still is, even though it
    // now sorts second.
    assertSame(host, normalized[1])
    assertEquals(listOf(1_500L, 2_000L), normalized.sortedBy { it.timestamp }.map { it.timestamp.toEpochMilliseconds() })
  }

  @Test
  fun `normalizing an already-normalized session shifts nothing a second time`() {
    val logs = listOf(
      anchoredDeviceToolLog(hostStartMs = 2_000, durationMs = 100, skewMs = 1_000),
      hostLog(atMs = 1_500),
    )

    val once = logs.normalizedToHostClock()
    assertEquals(listOf(2_000L, 1_500L), once.map { it.timestamp.toEpochMilliseconds() })
    assertEquals(once, once.normalizedToHostClock())
  }

  @Test
  fun `the shift is whole milliseconds, so sub-millisecond precision survives it`() {
    val precise = toolLog(
      startMs = 0,
      durationMs = 100,
      clock = TrailblazeClockDomain.DEVICE,
      hostReceivedAtMs = 1_100,
    ).copy(timestamp = Instant.fromEpochSeconds(0, 750_000))

    val offsets = assertNotNull(listOf(precise).deviceClockOffsets())
    assertEquals(1_000, offsets.sessionWideOffsetMs)
    val shifted = precise.normalizedTimestamp(offsets)
    assertEquals(1_000, shifted.toEpochMilliseconds())
    assertEquals(750_000, shifted.nanosecondsOfSecond % 1_000_000)
  }

  @Test
  fun `getSessionInfo measures start and duration on the host timeline`() {
    val logs = listOf(
      statusLog(atMs = 10_000),
      // Ran at host 11_000 for 500ms on a device 3s behind, so its raw stamp (8_000) predates the
      // session's own start log.
      anchoredDeviceToolLog(hostStartMs = 11_000, durationMs = 500, skewMs = 3_000),
      hostLog(atMs = 12_000),
    )

    val info = assertNotNull(logs.getSessionInfo())
    assertEquals(10_000, info.timestamp.toEpochMilliseconds())
    assertEquals(2_000, info.durationMs)
  }

  private fun toolLog(
    startMs: Long,
    durationMs: Long,
    deviceName: String? = null,
    clock: TrailblazeClockDomain? = null,
    hostReceivedAtMs: Long? = null,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
    toolName = "tapOn",
    successful = true,
    traceId = null,
    durationMs = durationMs,
    session = session,
    timestamp = Instant.fromEpochMilliseconds(startMs),
    deviceName = deviceName,
    clock = clock,
    hostReceivedAt = hostReceivedAtMs?.let { Instant.fromEpochMilliseconds(it) },
  )

  /**
   * A tool log as host ingestion anchors it: stamped by a device whose clock is [skewMs] behind
   * the host's, received [latencyMs] after the tool finished.
   */
  private fun anchoredDeviceToolLog(
    hostStartMs: Long,
    durationMs: Long,
    skewMs: Long,
    latencyMs: Long = 0,
    deviceName: String? = null,
  ) = toolLog(
    startMs = hostStartMs - skewMs,
    durationMs = durationMs,
    deviceName = deviceName,
    clock = TrailblazeClockDomain.DEVICE,
    hostReceivedAtMs = hostStartMs + durationMs + latencyMs,
  )

  /** A non-tool log — the shape that has no device to key an offset on. */
  private fun statusLog(atMs: Long, clock: TrailblazeClockDomain? = TrailblazeClockDomain.HOST) =
    TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Unknown,
      session = session,
      timestamp = Instant.fromEpochMilliseconds(atMs),
      clock = clock,
    )

  private fun hostLog(atMs: Long, clock: TrailblazeClockDomain? = TrailblazeClockDomain.HOST) =
    statusLog(atMs = atMs, clock = clock)
}
