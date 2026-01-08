package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Immutable snapshot of session state.
 * Contains all metadata needed to log events for a specific test session.
 *
 * This class is thread-safe by virtue of being immutable. All modifications
 * return a new instance with the updated state.
 *
 * ## Usage
 * ```kotlin
 * val session = TrailblazeSession(
 *     sessionId = SessionId("test_session"),
 *     startTime = Clock.System.now()
 * )
 *
 * // Mark fallback used - returns new instance
 * val updatedSession = session.withFallbackUsed()
 *
 * // Calculate duration
 * val durationMs = session.calculateDuration()
 * ```
 */
@Serializable
data class TrailblazeSession(
  val sessionId: SessionId,
  val startTime: Instant,
  val usedFallback: Boolean = false,
  val metadata: SessionMetadata = SessionMetadata(),
) {
  /**
   * Creates a new session with fallback marked as used.
   * Returns a new instance (this class is immutable).
   */
  fun withFallbackUsed(): TrailblazeSession = copy(usedFallback = true)

  /**
   * Calculates duration from session start to now in milliseconds.
   */
  fun calculateDuration(): Long {
    return Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
  }
}
