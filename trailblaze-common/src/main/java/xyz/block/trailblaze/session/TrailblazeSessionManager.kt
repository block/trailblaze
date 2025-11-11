package xyz.block.trailblaze.session

import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the status of a single Trailblaze test session for a specific device.
 * This is the single source of truth for whether a session is cancelled.
 *
 * Each device should have its own instance of this manager.
 * The agent/runner checks this directly to control execution flow.
 */
class TrailblazeSessionManager {

  // Current active session ID (thread-safe)
  private val currentSessionId = AtomicReference<String?>(null)

  // Cancellation status for the current session (thread-safe)
  private val isCancelled = AtomicReference(false)

  /**
   * Starts a new session. This clears any previous cancellation state.
   */
  fun startSession(sessionId: String) {
    currentSessionId.set(sessionId)
    isCancelled.set(false)
  }

  /**
   * Marks the current session as cancelled.
   */
  fun cancelCurrentSession() {
    val sessionId = currentSessionId.get()
    if (sessionId != null) {
      isCancelled.set(true)
    }
  }

  /**
   * Checks if the current session is cancelled.
   */
  fun isCurrentSessionCancelled(): Boolean = isCancelled.get()

  /**
   * Ends the current session. This does NOT clear cancellation state
   * (so we can check if a completed session was cancelled).
   */
  fun endSession() {
    currentSessionId.set(null)
  }

  /**
   * Gets the current active session ID.
   */
  fun getCurrentSessionId(): String? = currentSessionId.get()

  /**
   * Clears all session state. Mainly for testing.
   */
  fun clear() {
    currentSessionId.set(null)
    isCancelled.set(false)
  }
}
