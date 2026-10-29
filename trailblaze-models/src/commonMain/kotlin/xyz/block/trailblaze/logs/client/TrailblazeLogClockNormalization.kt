package xyz.block.trailblaze.logs.client

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.model.HasTraceId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.logs.model.TraceId

/**
 * A session's device→host clock offsets in milliseconds, keyed by the device each log came from.
 * Per device, not per session, because a multi-device session binds devices with independent
 * clocks — one blended offset would mis-normalize the device it doesn't match. Logs this can't key
 * to a device (and a device whose own logs were never anchored) fall back to
 * [sessionWideOffsetMs].
 *
 * A log's device is its [TrailblazeLog.TrailblazeToolLog.deviceName] when it carries one. Most
 * device-emitted logs don't: a tool the host RPC-dispatches to an Android device is logged BY the
 * device, which holds no bindings to stamp (see that field's kdoc, #3818), and driver logs have no
 * such field at all. A non-tool log that shares a [TraceId] with tool logs takes their device — a
 * driver screenshot can upload after the RPC that took it returned, even after the next
 * `switchDevice`, so receipt time can name the wrong device for it. Everything else is attributed
 * by HANDOVER (see [DeviceHandovers]): execution in a session is sequential and the host stamps the
 * active binding on every tool log it writes itself, so the device that sent a log is the one the
 * latest host-stamped log named when the host received it. A log received before any handover
 * belongs to the device the session started on, which is the unnamed bucket. Without this, unnamed
 * devices share one bucket, and the one whose clock runs ahead drags the others' logs early — far
 * enough to sort a device's first tool before the handover that started it, into the previous
 * device's step.
 */
class TrailblazeDeviceClockOffsets internal constructor(
  private val byDeviceName: Map<String?, Long>,
  private val handovers: DeviceHandovers,
  private val devicesByTraceId: Map<String, String?>,
  /**
   * The offset for a log this session can't key to a device: the minimum over EVERY anchored
   * sample. Also what a reader with no [TrailblazeLog] in hand uses — a device log stream
   * (logcat, the iOS system log) is stamped by the same device clock the logs are, so
   * `deviceMs = hostMs - sessionWideOffsetMs` puts a host-clock timeline position onto it.
   */
  val sessionWideOffsetMs: Long,
  /**
   * How many ingestion anchors these offsets were derived from — a one-anchor offset is a guess.
   * Deliberately NOT part of [describe]: a live session gains anchors continuously, and a reader
   * that dedupes its breadcrumb on the description would log again on every new anchor even when
   * the offsets it is reporting never moved.
   */
  val anchorCount: Int,
) {
  fun offsetMsFor(log: TrailblazeLog): Long = offsetMsForRecord(
    isToolLog = log is TrailblazeLog.TrailblazeToolLog,
    deviceName = (log as? TrailblazeLog.TrailblazeToolLog)?.deviceName,
    traceId = (log as? HasTraceId)?.traceId?.traceId,
    hostReceivedAtMs = log.hostReceivedAt?.toEpochMilliseconds(),
  )

  /**
   * The same lookup from a record's raw fields, for a reader holding a log record rather than a
   * decoded [TrailblazeLog] — the report snapshot's byte-preserved JSON view. A record nothing
   * attributes takes the unnamed bucket (the device the session started on); a device this session
   * never anchored falls back to [sessionWideOffsetMs].
   */
  fun offsetMsForRecord(isToolLog: Boolean, deviceName: String?, traceId: String?, hostReceivedAtMs: Long?): Long {
    val key = when {
      !deviceName.isNullOrEmpty() -> deviceName
      !isToolLog && traceId != null && traceId in devicesByTraceId -> devicesByTraceId.getValue(traceId)
      else -> handovers.deviceOf(null, hostReceivedAtMs)
    }
    return byDeviceName[key] ?: sessionWideOffsetMs
  }

  /** The offsets alone, stable across re-derivations that reach the same answer. */
  fun describe(): String = byDeviceName.entries.joinToString { (name, offsetMs) ->
    "${name ?: "(unnamed device)"}=${offsetMs}ms"
  }
}

/**
 * When each binding of a multi-device session became the active one, on the host clock: every
 * host-clock tool log that names its device — `switchDevice` included, logged under its
 * destination — in time order. Compared against [TrailblazeLog.hostReceivedAt], which is
 * host-clock too, so no device skew enters the attribution. Reading receipt time rather than file
 * order keeps it independent of how a reader enumerated the logs. Empty for a single-device
 * session, which leaves every lookup exactly as it was before handovers were read.
 *
 * Each log counts at its COMPLETION (`timestamp + durationMs`): the host stamps the binding active
 * when a tool finished, so a host-side wrapper that started on one device and switched to another
 * names the second, and dating that at its start would hand the first device's logs inside the
 * wrapper to the second.
 */
internal class DeviceHandovers(logs: List<TrailblazeLog>) {
  private val atMs: LongArray
  private val names: List<String>

  init {
    val sorted = logs
      .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.clock != TrailblazeClockDomain.DEVICE && !it.deviceName.isNullOrEmpty() }
      // A handover that failed never moved the session; its log names a device that never became
      // active. Spelled out because the tool itself lives in trailblaze-common.
      .filterNot { it.toolName == SWITCH_DEVICE_TOOL_NAME && !it.successful }
      .map { it.timestamp.toEpochMilliseconds() + it.durationMs to it.deviceName!! }
      .sortedBy { it.first }
    atMs = LongArray(sorted.size) { sorted[it].first }
    names = sorted.map { it.second }
  }

  /** [deviceName] when the log names itself, else the binding active when the host received it. */
  fun deviceOf(deviceName: String?, hostReceivedAtMs: Long?): String? {
    if (!deviceName.isNullOrEmpty()) return deviceName
    if (hostReceivedAtMs == null) return null
    // The latest handover at or before receipt; none before it leaves the log unattributed.
    var lo = 0
    var hi = atMs.size - 1
    var found = -1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (atMs[mid] <= hostReceivedAtMs) {
        found = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    return if (found >= 0) names[found] else null
  }

  private companion object {
    const val SWITCH_DEVICE_TOOL_NAME = "switchDevice"
  }
}

/**
 * This session's device→host clock offsets, derived from tool logs that both carry the
 * device-clock marker and were anchored at host ingestion: each anchored log gives
 * `hostReceivedAt - (timestamp + durationMs)` — the host receives a tool's log just after the
 * tool finishes, so every sample is the true skew PLUS that upload's latency. The MINIMUM across
 * a device's samples is used because latency only ever adds: the least-delayed upload is the
 * closest measurement of pure skew, and a batched upload — whose late `hostReceivedAt` inflates
 * its samples by the whole batching delay — contributes nothing to a minimum. (A central estimate
 * like the median keeps the TYPICAL latency in the offset, shifting every device span late — far
 * enough to push a window's last tool past its own ObjectiveComplete, the original misassignment
 * mirrored.) The residual error is the fastest upload's latency, which errs toward the
 * unnormalized behavior rather than toward a new failure mode. A sample counts toward the device
 * that sent it — its own name, else the handover active at receipt (see
 * [TrailblazeDeviceClockOffsets]).
 *
 * Null when the session has no anchored device-clock tool log to derive from (all-host sessions,
 * pre-field logs, and disk-fallback device logs pulled off the device without ever reaching host
 * ingestion) — callers then keep reading raw timestamps.
 *
 * Silent by design, because this runs on read paths that re-derive on every log file a running
 * session writes; a caller that wants the breadcrumb (a misplaced span is far easier to debug with
 * the offset in hand) logs [TrailblazeDeviceClockOffsets.describe] itself, once.
 *
 * Every reader that puts a session on one timeline derives its offsets HERE rather than
 * re-implementing this: the recording generator's window assignment, the session viewers, and
 * `SessionInfo`'s start/duration all have to agree, and two derivations that drift disagree about
 * which step a tool belongs to. The report's TypeScript (`run-report-extract.ts`) runs in a
 * browser and cannot call this, so it mirrors this derivation — keep the two in step
 * (`clock-normalization-parity-fixtures.json` pins both).
 */
fun List<TrailblazeLog>.deviceClockOffsets(): TrailblazeDeviceClockOffsets? {
  val handovers = DeviceHandovers(this)
  val samplesByDevice: Map<String?, List<Long>> =
    filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.clock == TrailblazeClockDomain.DEVICE }
      .mapNotNull { log ->
        log.hostReceivedAt?.let { receivedAt ->
          handovers.deviceOf(log.deviceName, receivedAt.toEpochMilliseconds()) to
            (receivedAt.toEpochMilliseconds() - (log.timestamp.toEpochMilliseconds() + log.durationMs))
        }
      }
      .groupBy({ it.first }, { it.second })
  if (samplesByDevice.isEmpty()) return null
  return TrailblazeDeviceClockOffsets(
    byDeviceName = samplesByDevice.mapValues { (_, samples) -> samples.min() },
    handovers = handovers,
    devicesByTraceId = devicesByTraceId(handovers),
    sessionWideOffsetMs = samplesByDevice.values.flatten().min(),
    anchorCount = samplesByDevice.values.sumOf { it.size },
  )
}

/**
 * The device each [TraceId] ran on, from the tool logs that carry it — for the driver logs a tool
 * emits under its trace, which can reach the host after the session moved on. A trace whose tool
 * logs resolve to more than one device (a wrapper that switched mid-way) is left out, so its logs
 * fall back to receipt time.
 */
private fun List<TrailblazeLog>.devicesByTraceId(handovers: DeviceHandovers): Map<String, String?> {
  val devices = mutableMapOf<String, String?>()
  val ambiguous = mutableSetOf<String>()
  for (log in filterIsInstance<TrailblazeLog.TrailblazeToolLog>()) {
    val traceId = log.traceId?.traceId ?: continue
    val device = handovers.deviceOf(log.deviceName, log.hostReceivedAt?.toEpochMilliseconds())
    if (traceId in devices && devices[traceId] != device) ambiguous += traceId else devices[traceId] = device
  }
  return devices - ambiguous
}

/**
 * This log's [TrailblazeLog.timestamp] on the host timeline: its own stamp, plus its device's
 * offset from [offsets] when the log declares itself device-stamped. An unmarked log (host-clock,
 * or written before the field existed) and a session with no derivable offset both come back
 * unchanged, which is what keeps every pre-existing session log reading exactly as it did before.
 */
fun TrailblazeLog.normalizedTimestamp(offsets: TrailblazeDeviceClockOffsets?): Instant =
  if (clock == TrailblazeClockDomain.DEVICE) {
    timestamp + (offsets?.offsetMsFor(this) ?: 0L).milliseconds
  } else {
    timestamp
  }

/** [normalizedTimestamp] as epoch millis, for readers that compare and sort on Longs. */
fun TrailblazeLog.normalizedMs(offsets: TrailblazeDeviceClockOffsets?): Long =
  normalizedTimestamp(offsets).toEpochMilliseconds()

/**
 * This session's logs with every device-stamped [TrailblazeLog.timestamp] re-stamped onto the host
 * timeline — the one-line way for a reader that compares timestamps at many call sites (a session
 * viewer) to honor the clock domain without threading [TrailblazeDeviceClockOffsets] through each
 * one. Input order is preserved; sort the result if you need chronological order, which is now
 * well-defined because every stamp is in one domain.
 *
 * A re-stamped log's [TrailblazeLog.clock] becomes [TrailblazeClockDomain.HOST], because that is
 * now true of its timestamp — which also makes this idempotent: a second pass finds no
 * device-clock log to derive an offset from and shifts nothing. [TrailblazeLog.hostReceivedAt] is
 * left in place as the provenance of the shift.
 *
 * Whole-millisecond shift, so sub-millisecond precision in the original stamp survives.
 */
fun List<TrailblazeLog>.normalizedToHostClock(
  offsets: TrailblazeDeviceClockOffsets? = deviceClockOffsets(),
): List<TrailblazeLog> {
  if (offsets == null) return this
  return map { log ->
    if (log.clock != TrailblazeClockDomain.DEVICE) {
      log
    } else {
      log.withClockMetadata(
        timestamp = log.normalizedTimestamp(offsets),
        clock = TrailblazeClockDomain.HOST,
      )
    }
  }
}
