package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.model.SessionId

/**
 * This is an abstraction on top of the logging system that will notify listeners of various states.
 */
interface TrailblazeSessionListener {
  val trailblazeSessionId: SessionId
  fun onSessionStarted()
  fun onUpdate(message: String)
  fun onSessionEnded()
}

/**
 * Listener for changes to the session list (when sessions are added or removed).
 */
interface SessionListListener {
  fun onSessionAdded(sessionId: SessionId)
  fun onSessionRemoved(sessionId: SessionId)
}
