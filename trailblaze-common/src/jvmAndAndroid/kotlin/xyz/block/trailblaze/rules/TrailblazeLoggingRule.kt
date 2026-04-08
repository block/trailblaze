package xyz.block.trailblaze.rules

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.runner.Description
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.client.SessionMetadata
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.tracing.TrailblazeTraceExporter
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * Base JUnit4 Logging Rule for Trailblaze Tests
 *
 * Provides stateless logger with explicit session management:
 * - Use `logger` with `session` for all logging operations
 * - Session lifecycle is managed automatically by the rule
 * - Access current session via the `session` property
 */
abstract class TrailblazeLoggingRule(
  private val logsBaseUrl: String = "https://localhost:${TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT}",
  private val writeLogToDisk: ((currentTestName: SessionId, log: TrailblazeLog) -> Unit) = { _, _ -> },
  private val writeScreenshotToDisk: ((screenshot: TrailblazeScreenStateLog) -> Unit) = { _ -> },
  private val writeTraceToDisk: ((sessionId: SessionId, json: String) -> Unit) = { _, _ -> },
  /** This can be used for additional log monitoring or verifications if needed, but is not needed for normal usage */
  private val additionalLogEmitter: LogEmitter? = null,
  /**
   * When true, all log emission is suppressed — neither HTTP nor disk writes occur.
   * Use with [--no-logging] so test runs don't pollute the session list.
   */
  private val noLogging: Boolean = false,
) : SimpleTestRule() {

  abstract val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo

  /**
   * Current session for this test.
   * Updated automatically during test lifecycle (ruleCreation, afterTestExecution).
   * Can also be set externally via [setSession] for non-JUnit usage.
   */
  var session: TrailblazeSession? = null
    private set

  /**
   * Optional provider that captures the current screen state at the moment of failure.
   * Set this after the test runner is initialized so that failure screenshots can be
   * logged automatically when a test fails.
   */
  var failureScreenStateProvider: (() -> ScreenState)? = null

  /**
   * Allows external session injection for non-JUnit usage (e.g., desktop app runner).
   * This enables using the logging infrastructure without going through JUnit rules.
   */
  fun setSession(newSession: TrailblazeSession?) {
    session = newSession
  }

  /**
   * LogEmitter that sends logs to server or disk.
   * Shared by both SessionManager and Logger.
   */
  private val logEmitter: LogEmitter by lazy {
    LogEmitter { log: TrailblazeLog ->
      if (noLogging) return@LogEmitter

      // Notify additional log emitter (for test inspection, etc.)
      additionalLogEmitter?.emit(log)

      // Get session ID from current session
      val sessionId = session?.sessionId ?: SessionId("unknown")
      runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          try {
            val httpResult = trailblazeLogServerClient.postAgentLog(log)
            if (httpResult.status.value != 200) {
              Console.log("Error while posting agent log: ${httpResult.status.value} ${httpResult.bodyAsText()}")
            }
          } catch (e: Exception) {
            Console.log("Failed to post agent log to server: ${e.message}")
            writeLogToDisk(sessionId, log)
          }
        } else {
          writeLogToDisk(sessionId, log)
        }
      }
    }
  }

  /**
   * Session Manager for lifecycle operations (start, end, create sessions).
   * Public to allow external components to manage sessions explicitly.
   */
  val sessionManager: TrailblazeSessionManager by lazy {
    TrailblazeSessionManager(logEmitter)
  }

  /**
   * Stateless logger for emitting log events.
   * Always use with an explicit [session] instance.
   */
  val logger: TrailblazeLogger by lazy {
    TrailblazeLogger(
      logEmitter = logEmitter,
      screenStateLogger = screenStateLogger,
    )
  }

  val trailblazeLogServerClient by lazy {
    TrailblazeLogServerClient(
      httpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
        timeoutInSeconds = 2,
      ),
      baseUrl = logsBaseUrl,
    )
  }

  private val screenStateLogger by lazy {
    ServerScreenStateLogger(
      isServerAvailable = isServerAvailable,
      trailblazeLogServerClient = trailblazeLogServerClient,
      writeScreenshotToDisk = writeScreenshotToDisk,
    )
  }

  private val isServerAvailable by lazy {
    if (noLogging) return@lazy false
    val startTime = Clock.System.now()
    val isRunning = runBlocking { trailblazeLogServerClient.isServerRunning() }
    Console.log("isServerAvailable [$isRunning] took ${Clock.System.now() - startTime}ms")
    if (!isRunning) {
      Console.log(
        "Log Server is not available at ${trailblazeLogServerClient.baseUrl}. Run with ./gradlew :trailblaze-server:run",
      )
    }
    isRunning
  }

  var description: Description? = null
    private set

  override fun ruleCreation(description: Description) {
    this.description = description

    val testName = "${description.testClass.canonicalName}_${description.methodName}"

    // Start new session with metadata so title resolution uses class/method
    // instead of falling back to the session ID (which contains a timestamp prefix)
    session = sessionManager.startSession(
      sessionName = testName,
      metadata = SessionMetadata(
        testClassName = description.testClass.canonicalName,
        testMethodName = description.methodName,
      ),
    )
  }

  override fun beforeTestExecution(description: Description) {
    TrailblazeTracer.clear()
    super.beforeTestExecution(description)
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    // Capture failure screenshot before ending the session
    if (result.isFailure) {
      captureFailureScreenshot()
    }

    // End session if it exists
    session?.let { currentSession ->
      sessionManager.endSession(
        session = currentSession,
        isSuccess = result.isSuccess,
        exception = result.exceptionOrNull(),
      )
    }

    exportTraces()
  }

  private fun captureFailureScreenshot() {
    val currentSession = session ?: return
    val provider = failureScreenStateProvider ?: return
    try {
      val screenState = provider()
      logger.logSnapshot(currentSession, screenState, displayName = "failure_screenshot")
      Console.log("📸 Failure screenshot captured for session ${currentSession.sessionId.value}")
    } catch (e: Exception) {
      Console.log("Failed to capture failure screenshot: ${e.message}")
    }
  }

  private fun exportTraces() {
    val sessionId = session?.sessionId ?: SessionId("unknown")
    runBlocking(Dispatchers.IO) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = trailblazeLogServerClient,
        isServerAvailable = isServerAvailable,
        writeToDisk = { traceJson -> writeTraceToDisk(sessionId, traceJson) },
      )
    }
  }
}

private class ServerScreenStateLogger(
  val isServerAvailable: Boolean,
  val trailblazeLogServerClient: TrailblazeLogServerClient,
  val writeScreenshotToDisk: ((screenshot: TrailblazeScreenStateLog) -> Unit) = { _ -> },
) : ScreenStateLogger {
  override fun logScreenState(screenState: TrailblazeScreenStateLog): String {
    // Send Log
    return runBlocking(Dispatchers.IO) {
      if (isServerAvailable) {
        try {
          val logResult = trailblazeLogServerClient.postScreenshot(
            screenshotFilename = screenState.fileName,
            sessionId = screenState.sessionId,
            screenshotBytes = screenState.screenState.screenshotBytes ?: ByteArray(0),
          )
          if (logResult.status.value != 200) {
            Console.log("Error while posting screenshot: ${logResult.status.value}")
          }
        } catch (e: Exception) {
          Console.log("Failed to post screenshot to server: ${e.message}")
          writeScreenshotToDisk(screenState)
        }
      } else {
        writeScreenshotToDisk(screenState)
      }
      screenState.fileName
    }
  }
}
