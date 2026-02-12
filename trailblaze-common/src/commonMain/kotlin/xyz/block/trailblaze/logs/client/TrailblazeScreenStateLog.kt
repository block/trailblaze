package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.SessionId

class TrailblazeScreenStateLog(
  val fileName: String,
  val sessionId: SessionId,
  val screenState: ScreenState,
)
