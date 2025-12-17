package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.logs.model.SessionId

interface TrailblazeSessionListener {
  val trailblazeSessionId: SessionId
  fun onSessionStarted()
  fun onUpdate(message: String)
  fun onSessionEnded()
}