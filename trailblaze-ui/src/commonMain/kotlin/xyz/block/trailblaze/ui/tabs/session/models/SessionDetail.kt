package xyz.block.trailblaze.ui.tabs.session.models

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus

data class SessionDetail(
  val session: SessionInfo,
  /**
   * The session's logs on ONE timeline: device-stamped entries re-stamped onto the host clock by
   * `normalizedToHostClock`, so every view below can compare timestamps without knowing which
   * runtime emitted which log.
   */
  val logs: List<TrailblazeLog>,
  val overallStatus: SessionStatus? = null,
  val deviceName: String? = null,
  val deviceType: String? = null,
  val totalDurationMs: Long? = null,
  /**
   * How far the device's clock ran behind the host's, in ms (negative when it ran ahead) — the
   * shift already applied to [logs]. Carried because the device's OWN log streams (logcat, the iOS
   * system log) are still stamped in the device domain, so a view syncing them to this timeline
   * has to shift back by it. Null when the session gave nothing to derive it from.
   */
  val deviceClockOffsetMs: Long? = null,
)