package xyz.block.trailblaze.logs.client

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain

/**
 * A session's device→host clock offsets in milliseconds, keyed by
 * [TrailblazeLog.TrailblazeToolLog.deviceName]. Per device, not per session, because a
 * multi-device session binds devices with independent clocks — one blended offset would
 * mis-normalize the device it doesn't match, regressing spans that were previously fine. Logs
 * without a usable device key (non-tool logs, and a named device whose own logs were never
 * anchored) fall back to [sessionWideOffsetMs].
 *
 * Keying is only as good as [TrailblazeLog.TrailblazeToolLog.deviceName], and one path leaves it
 * null on purpose: a tool the host RPC-dispatches to an Android device is logged BY the device,
 * which holds no bindings to stamp (see that field's kdoc, #3818). Two such devices in one session
 * therefore share the null bucket and get its minimum — the session-wide estimate, which is what
 * every reader used before this existed. Carrying the name on the RPC request is what would split
 * them; until then this degrades to the old behavior rather than to a wrong per-device one.
 */
class TrailblazeDeviceClockOffsets internal constructor(
  private val byDeviceName: Map<String?, Long>,
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
  fun offsetMsFor(log: TrailblazeLog): Long = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> offsetMsForDeviceName(log.deviceName)
    else -> sessionWideOffsetMs
  }

  /**
   * The same lookup keyed by name alone, for a reader holding a raw log record rather than a
   * decoded [TrailblazeLog] — the report snapshot's byte-preserved JSON view. A name this session
   * never anchored falls back to [sessionWideOffsetMs], as it does for a decoded log.
   */
  fun offsetMsForDeviceName(deviceName: String?): Long = byDeviceName[deviceName] ?: sessionWideOffsetMs

  /** The offsets alone, stable across re-derivations that reach the same answer. */
  fun describe(): String = byDeviceName.entries.joinToString { (name, offsetMs) ->
    "${name ?: "(unnamed device)"}=${offsetMs}ms"
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
 * unnormalized behavior rather than toward a new failure mode.
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
 * which step a tool belongs to. The report's TypeScript profiler (`perf-extract.ts`) runs in a
 * browser and cannot call this, so it mirrors this derivation — keep the two in step.
 */
fun List<TrailblazeLog>.deviceClockOffsets(): TrailblazeDeviceClockOffsets? {
  val samplesByDevice: Map<String?, List<Long>> =
    filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.clock == TrailblazeClockDomain.DEVICE }
      .mapNotNull { log ->
        log.hostReceivedAt?.let { receivedAt ->
          log.deviceName to
            (receivedAt.toEpochMilliseconds() - (log.timestamp.toEpochMilliseconds() + log.durationMs))
        }
      }
      .groupBy({ it.first }, { it.second })
  if (samplesByDevice.isEmpty()) return null
  return TrailblazeDeviceClockOffsets(
    byDeviceName = samplesByDevice.mapValues { (_, samples) -> samples.min() },
    sessionWideOffsetMs = samplesByDevice.values.flatten().min(),
    anchorCount = samplesByDevice.values.sumOf { it.size },
  )
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
