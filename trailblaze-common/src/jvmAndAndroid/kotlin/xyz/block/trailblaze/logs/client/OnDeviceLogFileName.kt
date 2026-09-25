package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.logs.model.SessionId
import java.util.concurrent.atomic.AtomicLong

/**
 * Names the log files an on-device run writes into Downloads for the host to pull later.
 *
 * Session plus timestamp is not a unique name. The device writer deletes any existing file before
 * writing, so two logs sharing a millisecond means the second silently replaces the first — and
 * one LLM request emits exactly that: its [TrailblazeLog.TrailblazeToolCatalogLog] and the
 * request referencing it are both stamped with the request's start time. Without the counter the
 * request destroys the catalog it points at, and the pulled session resolves no tool descriptors
 * at all.
 *
 * The counter is zero-padded so a lexical sort of the pulled directory still matches emission
 * order, which a bare millisecond cannot do for logs written inside the same one.
 */
object OnDeviceLogFileName {

  private val sequence = AtomicLong(0)

  /**
   * `<session>_<epochMillis>_<counter>.json`, unique for the life of the process.
   *
   * Bounded, because the counter pushed the suffix from 19 bytes to 30 and session ids run long
   * enough for that to matter — see [BoundedLogFileName].
   */
  fun forLog(sessionId: SessionId, timestampEpochMillis: Long): String = BoundedLogFileName.of(
    sessionId = sessionId.value,
    suffix = buildString {
      append('_')
      append(timestampEpochMillis)
      append('_')
      append(sequence.getAndIncrement().toString().padStart(SEQUENCE_WIDTH, '0'))
      append(".json")
    },
  )

  /**
   * The counter runs for the life of the process, not per session, and a long-lived on-device
   * runner serves many sessions. Ten digits outlasts any process; a narrower pad would stop
   * sorting lexically the moment the count outgrew it.
   */
  private const val SEQUENCE_WIDTH = 10
}
