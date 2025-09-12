package xyz.block.trailblaze.ui.tabs.session.models

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus

data class SessionDetail(
  val session: SessionInfo,
  val logs: List<TrailblazeLog>,
  val overallStatus: SessionStatus? = null,
  val deviceName: String? = null,
  val deviceType: String? = null,
  val totalDurationMs: Long? = null,
)