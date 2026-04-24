package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.yaml.TrailConfig
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
   * The session ID will be sanitized (non-alphanumeric characters replaced
   * with underscores, converted to lowercase) via [SessionId.sanitized] — the
   * same sanitization applied to host-generated IDs, so an ID generated on
   * the host and forwarded as an override survives the round-trip unchanged.
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
      sessionId = SessionId.sanitized(sessionId.value),
      startTime = Clock.System.now(),
      metadata = metadata,
    )
  }

  /**
   * Emits a session start log with device info and classifiers.
   * Call this immediately after creating a session to establish the session context
   * before any other logs are emitted.
   *
   * ## Example
   * ```kotlin
   * val session = sessionManager.startSession("MyTest")
   * sessionManager.emitSessionStartLog(
   *     session = session,
   *     trailConfig = trailConfig,
   *     trailFilePath = "/path/to/test.trail.yaml",
   *     hasRecordedSteps = true,
   *     testMethodName = "testLogin",
   *     testClassName = "LoginTest",
   *     trailblazeDeviceInfo = deviceInfoProvider(),
   *     trailblazeDeviceId = deviceId,
   *     rawYaml = yamlContent,
   * )
   * ```
   *
   * @param session The session to emit the start log for
   * @param trailConfig Optional trail configuration extracted from YAML
   * @param trailFilePath Optional path to the trail file
   * @param hasRecordedSteps Whether the trail has recorded steps
   * @param testMethodName The test method name for logging
   * @param testClassName The test class name for logging
   * @param trailblazeDeviceInfo Device info including classifiers
   * @param trailblazeDeviceId Optional device ID
   * @param rawYaml Optional raw YAML content
   */
  fun emitSessionStartLog(
    session: TrailblazeSession,
    trailConfig: TrailConfig?,
    trailFilePath: String?,
    hasRecordedSteps: Boolean,
    testMethodName: String,
    testClassName: String,
    trailblazeDeviceInfo: TrailblazeDeviceInfo,
    trailblazeDeviceId: TrailblazeDeviceId? = null,
    rawYaml: String? = null,
  ) {
    // Fall back to session metadata when explicit params are blank (e.g. when the
    // caller doesn't have test context but TrailblazeLoggingRule stored it on the session).
    val resolvedTestMethod = testMethodName.ifBlank { session.metadata.testMethodName.orEmpty() }
    val resolvedTestClass = testClassName.ifBlank { session.metadata.testClassName.orEmpty() }

    val sessionStartedStatus = SessionStatus.Started(
      trailConfig = trailConfig,
      trailFilePath = trailFilePath ?: session.metadata.trailFilePath,
      hasRecordedSteps = hasRecordedSteps,
      testMethodName = resolvedTestMethod,
      testClassName = resolvedTestClass,
      trailblazeDeviceInfo = trailblazeDeviceInfo,
      trailblazeDeviceId = trailblazeDeviceId,
      rawYaml = rawYaml,
    )

    val startLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = sessionStartedStatus,
      session = session.sessionId,
      timestamp = Clock.System.now(),
    )
    logEmitter.emit(startLog)
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
     * Format: `YYYY_MM_DD_HH_MM_SS_<seed>_<random>`, sanitized via [SessionId.sanitized].
     *
     * The full test name (including TestRail suite/section/case suffixes) is
     * preserved so downstream tooling can reliably map session IDs back to test
     * identifiers. Both host-generated and externally-provided IDs flow through
     * [SessionId.sanitized] so a host-generated ID passed to the on-device handler
     * (which re-sanitizes any override ID) survives the round-trip unchanged —
     * otherwise host and device would write to two different session directories.
     *
     * Example: `2026_01_07_14_30_45_mytest_1234`
     *
     * @param seed The name/identifier to include in the session ID
     * @return A new session ID
     */
    fun generateSessionId(seed: String): SessionId {
      val randomNumber = random.nextInt(0, 9999)
      return SessionId.sanitized("${dateTimeFormat.format(Date())}_${seed}_${randomNumber}")
    }
  }
}
