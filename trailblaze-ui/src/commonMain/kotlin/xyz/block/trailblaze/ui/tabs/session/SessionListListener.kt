package xyz.block.trailblaze.ui.tabs.session

interface SessionListListener {
  fun onSessionAdded(sessionId: String)
  fun onSessionRemoved(sessionId: String)
}