package xyz.block.trailblaze.logs.model

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.events.SessionEvents

/**
 * One NTP-style reading of a device's wall clock against the host's: the host clock just before
 * the query was sent ([hostBeforeMs]), the device's answer ([deviceMs]), and the host clock just
 * after the answer arrived ([hostAfterMs]).
 *
 * The device read happened somewhere inside the round trip, so the best estimate of the host time
 * it corresponds to is the midpoint, and the error is bounded by half the round trip. A reader keeps
 * the reading with the SHORTEST round trip ([tightest]) because that bound is the tightest one.
 */
@Serializable
data class DeviceClockSample(val hostBeforeMs: Long, val hostAfterMs: Long, val deviceMs: Long) {
  val roundTripMs: Long
    get() = hostAfterMs - hostBeforeMs

  /** Integer midpoint, truncated; the TS mirror (`device-clock-sync.ts`) truncates the same way. */
  val hostMidMs: Long
    get() = hostBeforeMs + roundTripMs / 2

  /** `device − host`: positive when the device clock runs ahead of the host's. */
  val deviceAheadMs: Long
    get() = deviceMs - hostMidMs

  companion object {
    /** The reading with the shortest round trip; the first one wins a tie. Null when empty. */
    fun tightest(samples: List<DeviceClockSample>): DeviceClockSample? =
      samples.minByOrNull { it.roundTripMs }
  }
}

/** A clock read that failed, kept so a session with no samples says why instead of reading 0. */
@Serializable
data class DeviceClockReadFailure(val atHostMs: Long, val error: String)

/**
 * Every clock reading a session took against one device, in the order they were taken. Each
 * [samples] entry is already the tightest of a burst of reads (see `DeviceClock.measure`).
 *
 * [deviceName] is the device's name in a multi-device session (`seller`, `buyer`): the suffix its
 * capture streams carry (`<stream>.<deviceName>.ndjson`). Null in a single-device session.
 */
@Serializable
data class DeviceClockTrack(
  val deviceId: String,
  val samples: List<DeviceClockSample> = emptyList(),
  val failures: List<DeviceClockReadFailure> = emptyList(),
  val deviceName: String? = null,
) {
  private val byDeviceTime: List<DeviceClockSample> by lazy { samples.sortedBy { it.deviceMs } }

  /**
   * How far the device clock ran ahead of the host's at device time [deviceMs]: the nearest
   * sample's offset outside the sampled range, linear interpolation between the two samples that
   * bracket it inside. Keyed on DEVICE time because that is the only time a device-stamped record
   * carries. Null when the track has no samples.
   *
   * Drift moves the offset by milliseconds a minute; a change past [CLOCK_STEP_MS] between two
   * samples means the device clock was set in between. A step happens at one instant, so
   * interpolating across it would shift every record between the samples; the nearer sample (in
   * device time, the earlier on a tie) is on the right side of the step instead.
   */
  fun deviceAheadMsAt(deviceMs: Long): Long? {
    val sorted = byDeviceTime
    if (sorted.isEmpty()) return null
    if (deviceMs <= sorted.first().deviceMs) return sorted.first().deviceAheadMs
    if (deviceMs >= sorted.last().deviceMs) return sorted.last().deviceAheadMs
    // First sample at or after deviceMs. Binary search: every record converts, so a linear scan
    // per record is quadratic on a long run.
    var after = 0
    var end = sorted.size - 1
    while (after < end) {
      val mid = (after + end) ushr 1
      if (sorted[mid].deviceMs >= deviceMs) end = mid else after = mid + 1
    }
    val hi = sorted[after]
    val lo = sorted[after - 1]
    if (hi.deviceMs == lo.deviceMs) return hi.deviceAheadMs
    if (abs(hi.deviceAheadMs - lo.deviceAheadMs) > CLOCK_STEP_MS) {
      return if (deviceMs - lo.deviceMs <= hi.deviceMs - deviceMs) lo.deviceAheadMs else hi.deviceAheadMs
    }
    val fraction = (deviceMs - lo.deviceMs).toDouble() / (hi.deviceMs - lo.deviceMs).toDouble()
    return (lo.deviceAheadMs + fraction * (hi.deviceAheadMs - lo.deviceAheadMs)).roundToLong()
  }

  /** [deviceMs] on the host timeline, or null when the track has no samples. */
  fun toHostMs(deviceMs: Long): Long? = deviceAheadMsAt(deviceMs)?.let { deviceMs - it }

  companion object {
    /** An offset change between adjacent samples larger than this is a clock step, not drift. */
    const val CLOCK_STEP_MS: Long = 1_000L
  }
}

/**
 * `session_device_clock.json`: the device-vs-host clock readings a host-driven session measured, one
 * [DeviceClockTrack] per device it read.
 *
 * Readers use it to put a `clock: "device"` record (a trace span, a session event, a
 * `NetworkEvent`) on the host timeline. It is a measurement, so it wins over the offset the logs
 * imply (`deviceClockOffsets()`), which is only an upper bound inflated by upload latency.
 *
 * On-device runs never write it: everything there is stamped by the device clock already, so there
 * is nothing to convert.
 */
@Serializable
data class DeviceClockSync(
  val devices: List<DeviceClockTrack> = emptyList(),
  /**
   * The device a multi-device session started on. A record that names no device (a trace span)
   * came from it. Null in a single-device session, whose only track needs no tie-break.
   */
  val startDeviceId: String? = null,
) {

  /**
   * The track for [deviceId]. With no id, the session's start device when it named one, else its
   * only sampled device — and null when more than one device was sampled, because guessing would
   * put one device's records on another's clock. A record that cannot be attributed keeps its raw
   * stamp, which is what it did before this file existed.
   */
  fun track(deviceId: String? = null): DeviceClockTrack? {
    val sampled = devices.filter { it.samples.isNotEmpty() }
    if (deviceId != null) sampled.firstOrNull { it.deviceId == deviceId }?.let { return it }
    if (startDeviceId != null) return sampled.firstOrNull { it.deviceId == startDeviceId }
    return sampled.singleOrNull()
  }

  /**
   * The track for the capture stream [streamName]. A multi-device session suffixes each device's
   * streams with its name (`com.example.plugin.logging.buyer`), so a stream ending in a named
   * device's `.<deviceName>` belongs to that device and only to it: when that device has no
   * samples the answer is null, never another device's clock. A stream with no device suffix is
   * attributed as [track] with no id.
   *
   * Compared as file names ([SessionEvents.sanitizeName]), the way streams are written, so a device
   * named `buyer display` owns `<stream>.buyer_display`. A stream name read back from a file is
   * already sanitized; a raw one matches the same way.
   */
  fun trackForStream(streamName: String): DeviceClockTrack? {
    val file = SessionEvents.sanitizeName(streamName)
    val owner =
      devices
        .mapNotNull { track -> track.deviceName?.let { SessionEvents.sanitizeName(it) to track } }
        .filter { (name, _) -> file.endsWith(".$name") }
        .maxByOrNull { (name, _) -> name.length }
        ?.second
        ?: return track()
    return owner.takeIf { it.samples.isNotEmpty() }
  }

  /** [deviceMs] on the host timeline, or null when no track applies. */
  fun toHostMs(deviceMs: Long, deviceId: String? = null): Long? = track(deviceId)?.toHostMs(deviceMs)

  /** [deviceMs] from capture stream [streamName] on the host timeline; see [trackForStream]. */
  fun toHostMsForStream(deviceMs: Long, streamName: String): Long? =
    trackForStream(streamName)?.toHostMs(deviceMs)

  companion object {
    /**
     * Must not start with a hex digit: session-log discovery reads every `*.json` that does as a
     * `TrailblazeLog`, in this version's readers and in every one already shipped.
     */
    const val FILE_NAME: String = "session_device_clock.json"

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
    }

    fun encode(sync: DeviceClockSync): String = JSON.encodeToString(serializer(), sync)

    /** Null for unreadable text, so a damaged file reads as "not measured" rather than failing. */
    fun decodeOrNull(text: String): DeviceClockSync? =
      runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
  }
}
