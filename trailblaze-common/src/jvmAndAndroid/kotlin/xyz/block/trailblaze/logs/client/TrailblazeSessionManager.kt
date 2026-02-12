package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Manages the lifecycle of Trailblaze sessions.
 * Handles session creation, state transitions, and end logging.
 *
 * ## Responsibilities
 * - Generate session IDs with timestamps
 * - Create session instances
 * - Emit session end logs
 * - Determine appropriate end status based on exception types
 *
 * ## Usage
 * ```kotlin
 * val sessionManager = TrailblazeSessionManager(logEmitter)
 *
 * // Start a new session
 * val session = sessionManager.startSession("MyTest")
 *
 * // ... test execution ...
 *
 * // End the session
 * sessionManager.endSession(session, isSuccess = true)
 * ```
 */
class TrailblazeSessionManager(
  private val logEmitter: LogEmitter,
) {

  /**
   * Starts a new session with the given name.
   * Generates a session ID with timestamp (YYYY_MM_DD_HH_MM_SS) and random suffix.
   *
   * ## Example
   * ```kotlin
   * val session = sessionManager.startSession(
   *     sessionName = "LoginTest",
   *     metadata = SessionMetadata(
   *         testClassName = "com.example.LoginTest",
   *         testMethodName = "testSuccessfulLogin"
   *     )
   * )
   * // session.sessionId.value = "2026_01_07_14_30_45_LoginTest_1234"
   * ```
   *
   * @param sessionName The name to use for the session (combined with timestamp)
   * @param metadata Optional metadata to attach to the session
   * @return A new immutable session instance
   */
  fun startSession(
    sessionName: String,
    metadata: SessionMetadata = SessionMetadata(),
  ): TrailblazeSession {
    val sessionId = generateSessionId(sessionName)
    return TrailblazeSession(
      sessionId = sessionId,
      startTime = Clock.System.now(),
      metadata = metadata,
    )
  }

  /**
   * Creates a session with an explicit session ID.
   * Useful when client provides the ID (e.g., MCP server resuming a session).
   *
   * The session ID will be sanitized (truncated to 100 chars, non-alphanumeric
   * characters replaced with underscores, converted to lowercase).
   *
   * ## Example
   * ```kotlin
   * val session = sessionManager.createSessionWithId(
   *     sessionId = SessionId("my-custom-session-id"),
   *     metadata = SessionMetadata(...)
   * )
   * ```
   *
   * @param sessionId The exact session ID to use (will be sanitized)
   * @param metadata Optional metadata to attach to the session
   * @return A new immutable session instance
   */
  fun createSessionWithId(
    sessionId: SessionId,
    metadata: SessionMetadata = SessionMetadata(),
  ): TrailblazeSession {
    return TrailblazeSession(
      sessionId = truncateSessionId(sessionId.value),
      startTime = Clock.System.now(),
      metadata = metadata,
    )
  }

  /**
   * Ends a session by emitting an end log.
   * Automatically determines the correct end status based on parameters:
   *
   * - **MaxCallsLimitReachedException**: [SessionStatus.Ended.MaxCallsLimitReached]
   * - **With fallback + success**: [SessionStatus.Ended.SucceededWithFallback]
   * - **With fallback + failure**: [SessionStatus.Ended.FailedWithFallback]
   * - **Normal success**: [SessionStatus.Ended.Succeeded]
   * - **Normal failure**: [SessionStatus.Ended.Failed]
   *
   * ## Example
   * ```kotlin
   * try {
   *     // ... test execution ...
   *     sessionManager.endSession(session, isSuccess = true)
   * } catch (e: Exception) {
   *     sessionManager.endSession(session, isSuccess = false, exception = e)
   * }
   * ```
   *
   * @param session The session to end
   * @param isSuccess Whether the session completed successfully
   * @param exception Optional exception if the session failed
   */
  fun endSession(
    session: TrailblazeSession,
    isSuccess: Boolean,
    exception: Throwable? = null,
  ) {
    val endedStatus = determineEndStatus(session, isSuccess, exception)
    val endLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = endedStatus,
      session = session.sessionId,
      timestamp = Clock.System.now(),
    )
    logEmitter.emit(endLog)
  }

  /**
   * Ends a session with an explicit status.
   * Use this when you need fine-grained control over the end status.
   *
   * ## Example
   * ```kotlin
   * sessionManager.endSession(
   *     session,
   *     SessionStatus.Ended.Cancelled(
   *         durationMs = session.calculateDuration(),
   *         cancellationMessage = "User cancelled test"
   *     )
   * )
   * ```
   *
   * @param session The session to end
   * @param endedStatus The specific end status to emit
   */
  fun endSession(
    session: TrailblazeSession,
    endedStatus: SessionStatus.Ended,
  ) {
    val endLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = endedStatus,
      session = session.sessionId,
      timestamp = Clock.System.now(),
    )
    logEmitter.emit(endLog)
  }

  /**
   * Determines the appropriate end status based on session state and exception.
   */
  private fun determineEndStatus(
    session: TrailblazeSession,
    isSuccess: Boolean,
    exception: Throwable?,
  ): SessionStatus.Ended {
    // Check for MaxCallsLimitReachedException
    if (exception is xyz.block.trailblaze.exception.MaxCallsLimitReachedException) {
      return SessionStatus.Ended.MaxCallsLimitReached(
        durationMs = session.calculateDuration(),
        maxCalls = exception.maxCalls,
        objectivePrompt = exception.objectivePrompt,
      )
    }

    val durationMs = session.calculateDuration()

    return when {
      isSuccess && session.usedFallback -> SessionStatus.Ended.SucceededWithFallback(durationMs)
      !isSuccess && session.usedFallback -> SessionStatus.Ended.FailedWithFallback(
        durationMs = durationMs,
        exceptionMessage = formatException(exception),
      )
      isSuccess -> SessionStatus.Ended.Succeeded(durationMs)
      else -> SessionStatus.Ended.Failed(
        durationMs = durationMs,
        exceptionMessage = formatException(exception),
      )
    }
  }

  /**
   * Formats an exception as a string for logging.
   */
  private fun formatException(exception: Throwable?): String {
    return buildString {
      appendLine(exception?.message)
      appendLine(exception?.stackTraceToString())
    }
  }

  companion object {
    private const val MAX_SESSION_ID_LENGTH = 100

    @Suppress("SimpleDateFormat")
    private val dateTimeFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)

    /**
     * Random number generator for unique session ID suffixes.
     * Seeded with 0 for deterministic behavior in tests.
     */
    private val random = Random(0)

    /**
     * Generates a session ID with timestamp and random suffix.
     *
     * Format: `YYYY_MM_DD_HH_MM_SS_<seed>_<random>`
     *
     * Example: `2026_01_07_14_30_45_MyTest_1234`
     *
     * @param seed The name/identifier to include in the session ID
     * @return A new session ID
     */
    fun generateSessionId(seed: String): SessionId {
      val randomNumber = random.nextInt(0, 9999)
      return SessionId("${dateTimeFormat.format(Date())}_${seed}_${randomNumber}")
    }

    /**
     * Truncates and sanitizes session ID to ensure it's filesystem-safe.
     *
     * - Truncates to 100 characters max
     * - Replaces non-alphanumeric characters with underscores
     * - Converts to lowercase
     *
     * @param sessionId The session ID to sanitize
     * @return The sanitized session ID
     */
    fun truncateSessionId(sessionId: String): SessionId = SessionId(
      sessionId.take(minOf(sessionId.length, MAX_SESSION_ID_LENGTH))
        .replace(Regex("[^a-zA-Z0-9]"), "_")
        .lowercase()
    )
  }
}
