package xyz.block.trailblaze.rules

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
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.tracing.TrailblazeTracer

/**
 * Base JUnit4 Logging Rule for Trailblaze Tests
 *
 * Provides stateless logger with explicit session management:
 * - Use `logger` with `session` for all logging operations
 * - Session lifecycle is managed automatically by the rule
 * - Access current session via the `session` property
 */
abstract class TrailblazeLoggingRule(
  private val logsBaseUrl: String = "https://localhost:8443",
  private val writeLogToDisk: ((currentTestName: SessionId, log: TrailblazeLog) -> Unit) = { _, _ -> },
  private val writeScreenshotToDisk: ((screenshot: TrailblazeScreenStateLog) -> Unit) = { _ -> },
  private val writeTraceToDisk: ((sessionId: SessionId, json: String) -> Unit) = { _, _ -> },
  /** This can be used for additional log monitoring or verifications if needed, but is not needed for normal usage */
  private val additionalLogEmitter: LogEmitter? = null,
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
      // Notify additional log emitter (for test inspection, etc.)
      additionalLogEmitter?.emit(log)

      // Get session ID from current session
      val sessionId = session?.sessionId ?: SessionId("unknown")
      runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          val logResult = trailblazeLogServerClient.postAgentLog(log)
          if (logResult.status.value != 200) {
            println("Error while posting agent log: ${logResult.status.value}")
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
        timeoutInSeconds = 5,
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
    val startTime = Clock.System.now()
    val isRunning = runBlocking { trailblazeLogServerClient.isServerRunning() }
    println("isServerAvailable [$isRunning] took ${Clock.System.now() - startTime}ms")
    if (!isRunning) {
      println(
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
    
    // Start new session
    session = sessionManager.startSession(testName)
  }

  override fun beforeTestExecution(description: Description) {
    TrailblazeTracer.clear()
    super.beforeTestExecution(description)
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
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

  private fun exportTraces() {
    val traceEventsJson = TrailblazeTracer.exportJson()
    val sessionId = session?.sessionId ?: SessionId("unknown")
    writeTraceToDisk(sessionId, traceEventsJson)
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
        val logResult = trailblazeLogServerClient.postScreenshot(
          screenshotFilename = screenState.fileName,
          sessionId = screenState.sessionId,
          screenshotBytes = screenState.screenState.screenshotBytes ?: ByteArray(0),
        )
        if (logResult.status.value != 200) {
          println("Error while posting agent log: ${logResult.status.value}")
        }
      } else {
        writeScreenshotToDisk(screenState)
      }
      screenState.fileName
    }
  }
}
