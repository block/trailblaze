package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.logs.model.SessionId

interface SessionListListener {
  fun onSessionAdded(sessionId: SessionId)
  fun onSessionRemoved(sessionId: SessionId)
}