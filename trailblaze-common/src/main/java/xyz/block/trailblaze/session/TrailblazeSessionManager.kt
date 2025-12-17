package xyz.block.trailblaze.session

import xyz.block.trailblaze.logs.model.SessionId
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the status of a single Trailblaze test session for a specific device.
 * This is the single source of truth for whether a session is cancelled or has reached max calls limit.
 *
 * Each device should have its own instance of this manager.
 * The agent/runner checks this directly to control execution flow.
 */
class TrailblazeSessionManager {

  // Current active session ID (thread-safe)
  private val currentSessionId = AtomicReference<SessionId?>(null)

  // Cancellation status for the current session (thread-safe)
  private val isCancelled = AtomicReference(false)

  // Max calls limit reached status for the current session (thread-safe)
  private val maxCallsLimitInfo = AtomicReference<MaxCallsLimitInfo?>(null)

  /**
   * Data class to hold information about max calls limit being reached
   */
  data class MaxCallsLimitInfo(
    val maxCalls: Int,
    val objectivePrompt: String,
  )

  /**
   * Starts a new session. This clears any previous cancellation state and max calls limit state.
   */
  fun startSession(sessionId: SessionId) {
    currentSessionId.set(sessionId)
    isCancelled.set(false)
    maxCallsLimitInfo.set(null)
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
   * Marks that the current session has reached the max calls limit.
   */
  fun markMaxCallsLimitReached(
    maxCalls: Int,
    objectivePrompt: String,
  ) {
    val sessionId = currentSessionId.get()
    if (sessionId != null) {
      maxCallsLimitInfo.set(MaxCallsLimitInfo(maxCalls, objectivePrompt))
    }
  }

  /**
   * Checks if the current session has reached the max calls limit.
   */
  fun hasReachedMaxCallsLimit(): Boolean = maxCallsLimitInfo.get() != null

  /**
   * Gets the max calls limit info if it was reached, null otherwise.
   */
  fun getMaxCallsLimitInfo(): MaxCallsLimitInfo? = maxCallsLimitInfo.get()

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
  fun getCurrentSessionId(): SessionId? = currentSessionId.get()

  /**
   * Clears all session state. Mainly for testing.
   */
  fun clear() {
    currentSessionId.set(null)
    isCancelled.set(false)
    maxCallsLimitInfo.set(null)
  }
}
