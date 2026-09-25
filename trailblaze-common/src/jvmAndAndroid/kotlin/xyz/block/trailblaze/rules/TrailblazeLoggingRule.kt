package xyz.block.trailblaze.rules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.runner.Description
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.http.shouldBypassProxyForLogServer
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.client.SessionMetadata
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.client.withClockMetadata
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
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

  /** Android runners override this to use the persistent protobuf upload socket. */
  protected open val useBinaryLogTransport: Boolean = false

  /**
   * Whether this rule's timestamps — trace spans AND agent logs — are stamped on a device's clock
   * rather than the host's. On-device runners override it; a host runner records against the same
   * clock the report is drawn on.
   *
   * Declared here because the emitter is the only place that knows: the host uploads its own
   * traces and logs through the same endpoints a device does, so a receiver that assumed "an
   * upload is a device upload" would take the host's spans off the timeline. And it must be
   * stamped at EMISSION, not at server ingestion, because [logEmitter] falls back to
   * [writeLogToDisk] when the server is unreachable or rejects an upload — those device logs
   * never pass through the host, yet readers still need their clock domain.
   *
   * Either way the answer is recorded on the log: false stamps [TrailblazeClockDomain.HOST], not
   * absence, so "host" is something a reader knows rather than something it assumes.
   */
  protected open val useDeviceClock: Boolean = false

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
   * Ends [startedSession], reporting the state carried by this rule's live [session] reference
   * whenever that reference is the same session.
   *
   * **Prefer this over calling [sessionManager].endSession directly.** [TrailblazeSession] is
   * immutable, so state accrued mid-run produces a *new* instance that only the holder of the live
   * reference sees. The one that matters in practice is [TrailblazeSession.withSelfHealUsed]:
   * `TrailblazeRunnerUtil` marks self-heal by publishing a copy through [setSession], so a caller
   * that ends the session with the instance it started with reports `usedSelfHeal = false` and a
   * run that self-heal actually rescued lands as plain [SessionStatus.Ended.Succeeded] instead of
   * [SessionStatus.Ended.SucceededWithSelfHeal] — indistinguishable, in the session list and the
   * report, from a clean pass. (The JUnit path in [afterTestExecution] never had the bug because it
   * ends from the live field; every host-runner path did.)
   *
   * Falls back to [startedSession] when the live reference is absent, or belongs to a *different*
   * session — a reused rule must never end a session other than the one the caller started.
   */
  fun endSession(
    startedSession: TrailblazeSession,
    isSuccess: Boolean,
    exception: Throwable? = null,
  ) {
    val ending = liveStateFor(startedSession)
    exportTracesBeforeEnd(ending.sessionId)
    sessionManager.endSession(
      session = ending,
      isSuccess = isSuccess,
      exception = exception,
    )
  }

  /**
   * Ends [startedSession] with an explicit status, exporting the trace first.
   *
   * The same wrapper as [endSession] above, for the terminal paths that name their own status
   * rather than deriving it from a success flag — a run cancelled by a timeout is the one on
   * device. Without this overload those paths could only reach [sessionManager] directly, which is
   * how a cancelled run's report came to have no tracer spans at all.
   */
  fun endSession(
    startedSession: TrailblazeSession,
    endedStatus: SessionStatus.Ended,
  ) {
    val ending = liveStateFor(startedSession)
    exportTracesBeforeEnd(ending.sessionId)
    sessionManager.endSession(
      session = ending,
      endedStatus = endedStatus,
    )
  }

  /** The session this rule has already exported a trace for. See [exportTracesBeforeEnd]. */
  @Volatile
  private var tracesExportedFor: SessionId? = null

  /**
   * Writes `trace.json` while the session is still open, so it is on disk before the terminal
   * status log this precedes.
   *
   * The Ended log is what every follower reads as "this run is complete" — the daemon closes its
   * live stream on it and the live report rebuilds its payload on it. Exporting afterwards means a
   * follower reads the session's trace while the file is still missing, treats the absence as final
   * and never looks again, so a run followed to completion has no tracer spans in its report at all.
   *
   * **Exports at most once per session**, because the exporter *drains* the tracer: a second export
   * carries only what was recorded since the first. The host writes trace.json by merging, so
   * appending a second batch is safe there, but the two Android writers replace the file — so a run
   * that ends through both of this rule's entry points ([endSession] and [afterTestExecution], which
   * [InProcessStandaloneServerTest] does) would end up with a file holding only its teardown spans.
   * The cost of exporting once is the handful of spans recorded after the session ends; the cost of
   * exporting twice is the whole run's trace, on exactly the on-device path that can least afford it.
   *
   * Runners with their own export outside this rule are unaffected — the host runner's `finally`
   * still merges in whatever its teardown recorded.
   *
   * Failure is contained: the terminal status has to be emitted even when the trace cannot be
   * written, or a run whose export throws never reports an outcome at all and hangs in the session
   * list as still running.
   *
   * A suppressed run reads nothing and touches nothing. The recorder is process-wide and a daemon
   * runs trails concurrently, so draining here to keep a suppressed run's spans off the next
   * session would take a *concurrent* run's buffered spans with them — the loss is total and
   * silent, and it lands on the run that did ask for a trace. Nothing needs the drain either way:
   * every start already clears the recorder when it is the only run recording, in
   * [beforeTestExecution] on the JUnit path and in `HostRunTraceRecording.begin` on the host's.
   */
  private fun exportTracesBeforeEnd(sessionId: SessionId) {
    if (tracesExportedFor == sessionId) return
    tracesExportedFor = sessionId
    if (noLogging) return
    runCatching { exportTraces(sessionId) }
      .onFailure { Console.log("Trace export before session end failed: ${it.message}") }
  }

  /**
   * The most current known instance of [startedSession] — the live [session] reference when it
   * refers to the same session, otherwise [startedSession] itself. See [endSession].
   */
  private fun liveStateFor(startedSession: TrailblazeSession): TrailblazeSession =
    session?.takeIf { it.sessionId == startedSession.sessionId } ?: startedSession

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
    LogEmitter { emittedLog: TrailblazeLog ->
      if (noLogging) return@LogEmitter

      // Stamp the clock domain first, so every downstream consumer — observers, server upload,
      // and the disk fallback that never reaches the server — sees the same marked log.
      //
      // Host logs are stamped too, positively, rather than left to absence: a reader can't tell an
      // unstamped host log from one written before the field existed, and the report profiler
      // still falls back to guessing device-vs-host from the log CLASS for those — a guess that is
      // wrong for every host driver, because a host driver's AgentDriverLog carries the same
      // serial name an on-device Maestro driver log does. A positive HOST retires that guess.
      val log = if (emittedLog.clock == null) {
        emittedLog.withClockMetadata(
          clock = if (useDeviceClock) TrailblazeClockDomain.DEVICE else TrailblazeClockDomain.HOST,
        )
      } else {
        emittedLog
      }

      // Notify additional log emitter (for test inspection, etc.)
      additionalLogEmitter?.emit(log)

      // Notify any transient observers (e.g. the on-device dispatch counting its own tool logs
      // for the host double-log fix). Cheap, synchronous, on the emitting thread.
      transientLogObservers.forEach { it(log) }

      // Get session ID from current session
      val sessionId = session?.sessionId ?: SessionId("unknown")
      // False only when the server was reachable and this log missed it: the log then sits on the
      // device's disk while the logs around it reach the host.
      val reachedServerIfUp = runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          try {
            val sent = trailblazeLogServerClient.sendAgentLog(log)
            if (!sent) {
              // A rejected upload falls back to disk just like the
              // exception path below, so the log still lands somewhere durable. Without this, a
              // reachable-but-erroring log server silently drops the log. That matters for the
              // on-device tool-log count (#3818): the device counts a `TrailblazeToolLog` at
              // emit time and the host skips its own catch-all emit on the strength of that
              // count, so a dropped persist here would leave the tool absent from the report.
              // The disk fallback keeps "counted" ⇒ "persisted (server or disk)" true.
              Console.log("Error while uploading agent log; falling back to disk")
              writeLogToDisk(sessionId, log)
            }
            sent
          } catch (e: Exception) {
            Console.log("Failed to post agent log to server: ${e.message}")
            writeLogToDisk(sessionId, log)
            false
          }
        } else {
          writeLogToDisk(sessionId, log)
          true
        }
      }
      // A catalog is written once and referenced by every request after it, so one that missed the
      // host must be written again rather than left for the rest of the session to point at.
      // Outside runBlocking on purpose: the logger emitting this catalog holds a lock this takes.
      if (!reachedServerIfUp && log is TrailblazeLog.TrailblazeToolCatalogLog) {
        logger.toolCatalogNotDelivered(log)
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
      // A device-local log server is the host at the other end of an `adb reverse` (or the
      // emulator's host loopback) — a control channel, never a destination the device's HTTP proxy
      // is for, and a network-capture proxy must not be able to take it down. A remote
      // `trailblaze.logsEndpoint` keeps the proxy: reaching it may be what the proxy is for.
      httpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
        timeoutInSeconds = 2,
        bypassSystemProxy = shouldBypassProxyForLogServer(logsBaseUrl),
      ),
      baseUrl = logsBaseUrl,
      useBinaryTransport = useBinaryLogTransport,
    )
  }

  private val screenStateLogger by lazy {
    ServerScreenStateLogger(
      isServerAvailable = { isServerAvailable },
      trailblazeLogServerClient = trailblazeLogServerClient,
      writeScreenshotToDisk = writeScreenshotToDisk,
    )
  }

  /** How long a failed log-server ping is trusted before the next log asks again. Tests shorten it. */
  protected open val logServerRetryAfterMs: Long = LogServerAvailability.DEFAULT_INITIAL_RETRY_AFTER_MS

  private val logServerAvailability by lazy {
    LogServerAvailability(
      probe = { runBlocking { trailblazeLogServerClient.isServerRunning() } },
      initialRetryAfterMs = logServerRetryAfterMs,
      onProbe = { available, tookMs ->
        Console.log("isServerAvailable [$available] took ${tookMs}ms")
        if (!available) {
          Console.log(
            "Log Server is not available at ${trailblazeLogServerClient.baseUrl}. Run with ./gradlew :trailblaze-server:run",
          )
        }
      },
    )
  }

  /**
   * Re-evaluated on every log rather than fixed by the first ping: see [LogServerAvailability] for
   * what one failed ping used to cost.
   */
  private val isServerAvailable: Boolean
    get() = !noLogging && logServerAvailability.isAvailable()

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

    // Write trace.json BEFORE the terminal status, through the same guarded helper the host paths
    // use — so this path is equally protected from an export that throws, and a run that ends
    // through both entry points exports once rather than draining twice. See exportTracesBeforeEnd.
    exportTracesBeforeEnd(session?.sessionId ?: SessionId("unknown"))

    // End session if it exists
    session?.let { currentSession ->
      sessionManager.endSession(
        session = currentSession,
        isSuccess = result.isSuccess,
        exception = result.exceptionOrNull(),
      )
    }

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

  private fun exportTraces(explicitSessionId: SessionId? = null) {
    val sessionId = explicitSessionId ?: session?.sessionId ?: SessionId("unknown")
    runBlocking(Dispatchers.IO) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = trailblazeLogServerClient,
        isServerAvailable = isServerAvailable,
        writeToDisk = { traceJson -> writeTraceToDisk(sessionId, traceJson) },
        onDeviceClock = useDeviceClock,
      )
    }
  }
}

private class ServerScreenStateLogger(
  val isServerAvailable: () -> Boolean,
  val trailblazeLogServerClient: TrailblazeLogServerClient,
  val writeScreenshotToDisk: ((screenshot: TrailblazeScreenStateLog) -> Unit) = { _ -> },
) : ScreenStateLogger {
  override fun logScreenState(screenState: TrailblazeScreenStateLog): String {
    // Send Log
    return runBlocking(Dispatchers.IO) {
      if (isServerAvailable()) {
        try {
          val sent = trailblazeLogServerClient.sendScreenshot(
            screenshotFilename = screenState.fileName,
            sessionId = screenState.sessionId,
            screenshotBytes = screenState.screenState.screenshotBytes ?: ByteArray(0),
          )
          if (!sent) {
            Console.log("Error while uploading screenshot; falling back to disk")
            writeScreenshotToDisk(screenState)
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
