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
   * Provider that captures the current screen state at the moment of failure.
   *
   * Must be assigned after the test runner is initialized (the provider lambda typically
   * references dependents of this rule, which is why it isn't a constructor parameter).
   * All three run methods — JUnit, on-device RPC, host CLI — wire this from their
   * wrapping rule's `init` block. If a caller forgets to set it, [captureFailureScreenshot]
   * logs a warning instead of silently no-op'ing.
   */
  lateinit var failureScreenStateProvider: () -> ScreenState

  /**
   * Allows external session injection for non-JUnit usage (e.g., desktop app runner).
   * This enables using the logging infrastructure without going through JUnit rules.
   */
  fun setSession(newSession: TrailblazeSession?) {
    session = newSession
  }

  /**
   * Transient observers notified of every emitted [TrailblazeLog] for the duration of a
   * [withLogObserver] call. Backed by a copy-on-write list so a register/remove on one thread is
   * safe against the emit on another (the device dispatch can emit from a different coroutine
   * thread than the one that installed the observer).
   */
  private val transientLogObservers =
    java.util.concurrent.CopyOnWriteArrayList<(TrailblazeLog) -> Unit>()

  /**
   * Runs [block], invoking [observer] for every [TrailblazeLog] emitted while it executes, then
   * removes the observer (even if [block] throws).
   *
   * The on-device RPC dispatch uses this to count how many `TrailblazeToolLog`s it emitted for a
   * tool sent over RPC, so the host can skip its own duplicate `logToolExecution` and the tool
   * renders once in the session report (#3818). Observers fire synchronously on the emitting
   * thread before any disk/server write, so keep them cheap and non-blocking.
   */
  fun <T> withLogObserver(observer: (TrailblazeLog) -> Unit, block: () -> T): T {
    transientLogObservers.add(observer)
    return try {
      block()
    } finally {
      transientLogObservers.remove(observer)
    }
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

      // Notify any transient observers (e.g. the on-device dispatch counting its own tool logs
      // for the host double-log fix). Cheap, synchronous, on the emitting thread.
      transientLogObservers.forEach { it(log) }

      // Get session ID from current session
      val sessionId = session?.sessionId ?: SessionId("unknown")
      runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          try {
            val httpResult = trailblazeLogServerClient.postAgentLog(log)
            if (httpResult.status.value != 200) {
              // A non-200 means the server rejected the log — fall back to disk just like the
              // exception path below, so the log still lands somewhere durable. Without this, a
              // reachable-but-erroring log server silently drops the log. That matters for the
              // on-device tool-log count (#3818): the device counts a `TrailblazeToolLog` at
              // emit time and the host skips its own catch-all emit on the strength of that
              // count, so a dropped persist here would leave the tool absent from the report.
              // The disk fallback keeps "counted" ⇒ "persisted (server or disk)" true.
              Console.log("Error while posting agent log: ${httpResult.status.value} ${httpResult.bodyAsText()}")
              writeLogToDisk(sessionId, log)
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
  }

  override fun beforeTestExecution(description: Description) {
    TrailblazeTracer.clear()

    val testName = "${description.testClass.canonicalName}_${description.methodName}"

    // Create a fresh session for each test execution attempt. This ensures retries
    // (via RetryRule) get their own session directory with separate logs.
    session = sessionManager.startSession(
      sessionName = testName,
      metadata = SessionMetadata(
        testClassName = description.testClass.canonicalName,
        testMethodName = description.methodName,
      ),
    )

    super.beforeTestExecution(description)
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    // Per-step AgentDriverLog screenshots are all pre-action frames, so capture a
    // terminal screenshot here or the outcome of the last step is never in the report.
    session?.let { currentSession ->
      if (result.isFailure) {
        captureFailureScreenshot(currentSession)
      } else {
        captureFinalScreenshot(currentSession)
      }
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
    session = null
  }

  /**
   * Captures a screenshot at the moment of failure and logs it as a snapshot.
   * Uses the rule's configured [failureScreenStateProvider]; logs a warning and
   * returns if the provider was never assigned.
   *
   * Single entry point for all run methods (JUnit, on-device RPC, host CLI) so
   * failure-screenshot behavior stays consistent.
   */
  fun captureFailureScreenshot(session: TrailblazeSession) {
    if (!this::failureScreenStateProvider.isInitialized) {
      Console.log(
        "⚠️  Skipping failure screenshot for session ${session.sessionId.value}: " +
          "no failureScreenStateProvider wired on TrailblazeLoggingRule"
      )
      return
    }
    captureFailureScreenshot(session, failureScreenStateProvider)
  }

  /**
   * Overload that takes an explicit [screenStateProvider]. Used by host-side runners
   * where the provider varies per driver (Playwright, Electron, Maestro, etc.) and
   * isn't pre-wired onto the rule.
   */
  fun captureFailureScreenshot(session: TrailblazeSession, screenStateProvider: () -> ScreenState) {
    try {
      val screenState = screenStateProvider()
      logger.logSnapshot(session, screenState, displayName = "failure_screenshot")
      Console.log("📸 Failure screenshot captured for session ${session.sessionId.value}")
    } catch (e: Exception) {
      Console.log(
        "Failed to capture failure screenshot:\n" +
          "  type=${e::class.simpleName}\n" +
          "  message=${e.message}\n" +
          e.stackTraceToString(),
      )
    }
  }

  /**
   * Captures a terminal screenshot on a passing run and logs it as a snapshot, so the
   * storyboard/timeline includes the state after the final action (per-step screenshots
   * are all pre-action). Mirrors [captureFailureScreenshot]; no-ops with a warning if the
   * provider was never assigned.
   */
  fun captureFinalScreenshot(session: TrailblazeSession) {
    if (!this::failureScreenStateProvider.isInitialized) {
      Console.log(
        "⚠️  Skipping final screenshot for session ${session.sessionId.value}: " +
          "no failureScreenStateProvider wired on TrailblazeLoggingRule"
      )
      return
    }
    captureFinalScreenshot(session, failureScreenStateProvider)
  }

  /**
   * Overload that takes an explicit [screenStateProvider]. Used by host-side runners
   * where the provider varies per driver (Playwright, Electron, Maestro, etc.) and
   * isn't pre-wired onto the rule. Mirrors the failure overload.
   */
  fun captureFinalScreenshot(session: TrailblazeSession, screenStateProvider: () -> ScreenState) {
    try {
      val screenState = screenStateProvider()
      logger.logSnapshot(session, screenState, displayName = "final_screenshot")
      Console.log("📸 Final screenshot captured for session ${session.sessionId.value}")
    } catch (e: Exception) {
      Console.log(
        "Failed to capture final screenshot:\n" +
          "  type=${e::class.simpleName}\n" +
          "  message=${e.message}\n" +
          e.stackTraceToString(),
      )
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
