package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Method
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.util.Console

/**
 * One running subprocess MCP session: the spawned `bun` [Process], the
 * `io.modelcontextprotocol.kotlin.sdk.client.Client` wired to it, and the stdio transport
 * connecting the two.
 *
 * Authored classes aren't meant to construct this directly — call [connect] to spawn and
 * finish the MCP `initialize` handshake in one step. The live [client] is exposed so later
 * commits can drive `tools/list` and `tools/call` against it.
 *
 * Shutdown is intentionally minimal for this landing: close the client (which tears down the
 * transport and sends SIGPIPE via stdin close), wait up to 5 s for the subprocess to exit,
 * then escalate to [Process.destroy] → [Process.destroyForcibly]. The graceful-shutdown
 * polish (MCP `shutdown` notification, structured log capture of tail stderr) lives in the
 * lifecycle commit.
 */
class McpSubprocessSession internal constructor(
  val spawnedProcess: SpawnedProcess,
  val transport: StdioClientTransport,
  val client: Client,
  val stderrCapture: StderrCapture,
  /**
   * Dedicated daemon thread draining the subprocess's stderr into [stderrCapture], owned by
   * this session rather than by [transport]. See [connect] for why the transport must NOT own
   * stderr on the crash path.
   */
  private val stderrPump: Thread,
) {

  /** True while the subprocess is still alive. Flips to false on exit / shutdown. */
  val isAlive: Boolean get() = spawnedProcess.process.isAlive

  /**
   * Blocks up to [millis] for the stderr pump to finish draining. Once the subprocess has
   * exited, its stderr pipe reaches EOF and the pump terminates almost immediately. The crash
   * path calls this after confirming the process has been reaped, so the captured tail is
   * guaranteed complete before it is snapshotted into the error envelope. Best-effort: a
   * timeout returns quietly and an interrupt is preserved (see [joinPreservingInterrupt]) so a
   * wedged pump can never block error reporting.
   */
  internal fun awaitStderrDrained(millis: Long) {
    joinPreservingInterrupt(stderrPump, millis)
  }

  /**
   * Closes the MCP client (which closes the stdio transport + the process's stdin), then
   * waits briefly for the subprocess to exit on its own. Escalates to SIGTERM / SIGKILL if
   * it doesn't honor the EOF signal inside [exitWait]. Flushes + closes the stderr capture
   * last so the on-disk log ends up complete regardless of which escalation step terminated
   * the process.
   *
   * Blocking `Process.waitFor` calls run under [Dispatchers.IO] so the caller's coroutine
   * dispatcher (often `Default`) isn't pinned while we wait up to ~9 s on a stuck child.
   * After `destroyForcibly` we still wait `afterSigkillSeconds` so the function doesn't
   * return until the subprocess is actually gone — callers can safely re-spawn immediately.
   */
  suspend fun shutdown(exitWait: Duration = Duration.DEFAULT) = withContext(Dispatchers.IO) {
    runCatching { client.close() }
    destroyWithEscalation(spawnedProcess.process, exitWait)
    // The subprocess is gone now, so its stderr pipe is at EOF and the pump is finishing its
    // last reads — join it before closing the capture so the on-disk log ends up complete.
    joinPreservingInterrupt(stderrPump, STDERR_PUMP_JOIN_MS)
    stderrCapture.close()
  }

  /** Shutdown timing knobs — exposed so tests can hurry the escalation. */
  data class Duration(
    val afterCloseSeconds: Long,
    val afterSigtermSeconds: Long,
    /** Bounded wait after [Process.destroyForcibly] so [shutdown] only returns once the OS
     *  has actually reaped the subprocess. Without this, callers racing a re-spawn can land
     *  on a zombie still holding file descriptors. */
    val afterSigkillSeconds: Long,
  ) {
    companion object {
      /** Scope-devlog defaults: 5 s for graceful exit, 2 s after SIGTERM, 2 s after SIGKILL. */
      val DEFAULT = Duration(
        afterCloseSeconds = 5,
        afterSigtermSeconds = 2,
        afterSigkillSeconds = 2,
      )
    }
  }

  companion object {

    /** What Trailblaze advertises to MCP servers as the connecting client. */
    val DEFAULT_CLIENT_INFO: Implementation = Implementation(
      name = "trailblaze",
      version = "0.1.0",
    )

    /**
     * Hard bound on the subprocess MCP `initialize` handshake in [connect]. The handshake is
     * awaited from a `bun` subprocess that could hang before answering; without a bound the
     * connect parks indefinitely. That indefinite park is the root of the daemon-wide MCP wedge
     * (build 3366): a `device` connect that triggered the subprocess cold-build never returned,
     * and — because the build runs while holding session-scoped state the shared MCP dispatch
     * path needs — every later request, including a fresh session's `initialize`, timed out at
     * 300s while `/ping` stayed healthy. Bounding the handshake makes a wedged subprocess fail
     * that one session-startup call fast instead. 60s is generous for a cold `bun` start yet far
     * below the 300s client timeout. Overridable via
     * `TRAILBLAZE_MCP_SUBPROCESS_HANDSHAKE_TIMEOUT_MS`; a malformed / non-positive value falls
     * back to the default. Read once at class load, matching the daemon's other env knobs.
     */
    val DEFAULT_HANDSHAKE_TIMEOUT_MS: Long =
      System.getenv("TRAILBLAZE_MCP_SUBPROCESS_HANDSHAKE_TIMEOUT_MS")
        ?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    /**
     * Default stderr severity classifier. Lines mentioning "error" surface as WARNING so they
     * show up in session logs; everything else is DEBUG. Authors wanting richer classification
     * pass their own classifier to [connect].
     */
    val DEFAULT_STDERR_CLASSIFIER: (String) -> StdioClientTransport.StderrSeverity = { line ->
      if (line.contains("error", ignoreCase = true)) {
        StdioClientTransport.StderrSeverity.WARNING
      } else {
        StdioClientTransport.StderrSeverity.DEBUG
      }
    }

    /**
     * Wires a [StdioClientTransport] to [spawnedProcess]'s stdio, constructs a [Client] with
     * [clientInfo], and performs the MCP `initialize` handshake before returning.
     *
     * Does **not** populate `_meta.trailblaze` on the initialize request. The scope devlog
     * (§ MCP handshake flow) documents why: neither the Kotlin client SDK nor the TypeScript
     * server SDK exposes the `_meta` channel on `initialize` ergonomically, so the
     * `TRAILBLAZE_*` env vars from [McpSubprocessSpawner.envVars] are the authoritative
     * handshake snapshot. A structured handshake payload via `ClientCapabilities.extensions`
     * is the more natural future path; additive follow-up.
     */
    suspend fun connect(
      spawnedProcess: SpawnedProcess,
      clientInfo: Implementation = DEFAULT_CLIENT_INFO,
      stderrCapture: StderrCapture = StderrCapture(),
      stderrClassifier: (String) -> StdioClientTransport.StderrSeverity = DEFAULT_STDERR_CLASSIFIER,
      handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): McpSubprocessSession {
      // A non-positive bound would make the watchdog's `delay` return immediately and force-kill
      // every subprocess before it could answer — fail loudly on the programming error instead.
      // The default and env-parse paths already guarantee a positive value; this guards direct
      // callers / tests.
      require(handshakeTimeoutMillis > 0) {
        "handshakeTimeoutMillis must be positive, was $handshakeTimeoutMillis"
      }
      val process = spawnedProcess.process
      // Pump stderr on a session-owned daemon thread instead of handing the error stream to
      // the MCP transport. The transport tears its entire coroutine scope down the instant the
      // child's stdout hits EOF — which is exactly the crash signal — and that cancellation
      // races (and on a busy host, beats) its own stderr reader, so the child's final
      // diagnostics never reach [stderrCapture]. The result is a [TrailblazeToolResult.Error.FatalError]
      // that reports "(no stderr captured)" precisely when the stderr tail matters most.
      // Reproduced as a flaky failure of SubprocessCrashEnvelopeTest under CPU contention (and
      // as the deterministic red on the constrained CI runner). Owning the reader here keeps
      // stderr capture alive independently of the transport, so the tail is complete whether
      // the subprocess exits cleanly or dies mid-dispatch.
      val stderrPump = startStderrPump(
        process = process,
        scriptName = spawnedProcess.scriptFile.name,
        stderrCapture = stderrCapture,
        classifier = stderrClassifier,
      )
      val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
        error = null,
      )
      val client = Client(clientInfo, ClientOptions())
      // Route scripted-tool `ctx.logger.*` calls — emitted by the subprocess as MCP
      // `notifications/message` — into the host's `Console` so authors see their log lines
      // in the daemon stdout / session log without rolling their own emitter. The handler
      // runs on the client's IO dispatcher; we keep it allocation-light and never throw —
      // logging must not be able to take down a tool dispatch.
      client.setNotificationHandler<LoggingMessageNotification>(
        Method.Defined.NotificationsMessage,
      ) { notification ->
        routeLoggingMessage(notification.params, spawnedProcess.scriptFile.name)
        CompletableDeferred(Unit)
      }
      // Tears the just-spawned subprocess down on any handshake failure. Runs under
      // NonCancellable so it completes even when the caller's coroutine is being cancelled —
      // otherwise the suspend teardown would abort on the cancelled scope and leak the
      // subprocess. Shares the same escalation knobs as [shutdown] so both paths scale together.
      suspend fun teardownFailedHandshake() {
        withContext(NonCancellable) {
          runCatching { client.close() }
          withContext(Dispatchers.IO) { destroyWithEscalation(process, Duration.DEFAULT) }
          joinPreservingInterrupt(stderrPump, STDERR_PUMP_JOIN_MS)
          runCatching { stderrCapture.close() }
        }
      }

      // Bound the MCP `initialize` handshake with a watchdog rather than `withTimeout`.
      // `client.connect` completes the handshake by reading the subprocess's stdout, and that
      // read is a blocking, non-cancellable native read — a `bun` subprocess that hangs before
      // answering parks it, and `withTimeout` cannot unwind a thread blocked in a native read
      // (verified by test: a plain `withTimeout` returns only once the subprocess exits on its
      // own). See [DEFAULT_HANDSHAKE_TIMEOUT_MS] for why that park wedges the whole daemon. So
      // arm a watchdog that force-destroys the subprocess after the timeout; that closes its
      // stdout, the parked read unwinds, and `client.connect` throws — no matter which thread /
      // dispatcher the handshake is running on.
      //
      // `decided` is the single arbiter of the outcome, claimed via compare-and-set by exactly one
      // of {watchdog fires, handshake succeeds}. It closes the boundary race where the handshake
      // returns in the same instant the watchdog's `delay` elapses: cancelling the watchdog alone
      // can't stop the coroutine once `delay` has returned (nothing suspends after it), so without
      // the CAS the watchdog could still force-destroy a subprocess we'd already handed back as a
      // live session. Whoever wins the CAS acts; the loser stands down.
      val decided = AtomicBoolean(false)
      // Own the scope (not just the launched job) so every exit path can cancel it — the scope's
      // root Job would otherwise dangle. Detached from the caller's coroutine on purpose: the
      // watchdog must be able to fire while the caller thread is parked in the non-cancellable
      // native read (the whole point), which a child of that coroutine could not.
      val watchdogScope = CoroutineScope(Dispatchers.IO)
      watchdogScope.launch {
        delay(handshakeTimeoutMillis)
        if (decided.compareAndSet(false, true)) {
          // A wedged subprocess writes nothing to stderr, so without this line the force-kill is
          // silent and a session-startup failure is indistinguishable from any other. Name the
          // culprit script + the bound it blew so on-call can attribute it.
          Console.log(
            "[McpSubprocessSession] handshake watchdog fired for " +
              "'${spawnedProcess.scriptFile.name}' after ${handshakeTimeoutMillis}ms — " +
              "force-destroying the subprocess",
          )
          runCatching { process.destroyForcibly() }
        }
      }
      try {
        client.connect(transport)
      } catch (t: Throwable) {
        watchdogScope.cancel()
        // A genuine parent cancellation must surface as cancellation, never be re-attributed as a
        // handshake timeout — which it could be if it raced the watchdog and lost the CAS below.
        // Teardown still runs (under NonCancellable) so the subprocess isn't leaked.
        if (t is CancellationException) {
          teardownFailedHandshake()
          throw t
        }
        // If we can still claim the outcome, this was an organic handshake failure (server crashed
        // during init, bad protocol version) — propagate it unchanged. If the CAS fails, the
        // watchdog already claimed it and is force-destroying the subprocess: surface an attributable
        // timeout (with the stream-closed exception as cause) rather than the opaque read error, so
        // the launcher names the culprit script.
        val timedOut = !decided.compareAndSet(false, true)
        teardownFailedHandshake()
        if (timedOut) {
          throw McpSubprocessHandshakeTimeoutException(spawnedProcess.scriptFile.name, handshakeTimeoutMillis, t)
        }
        throw t
      }
      // Handshake returned. Race the watchdog for the outcome: if we win, the watchdog stands down
      // (its CAS will fail) and we hand back the live session. If we lose, the watchdog is already
      // force-destroying the subprocess — don't return a session whose process is being torn down
      // under it; fail as a timeout, consistent with the catch above.
      if (decided.compareAndSet(false, true)) {
        watchdogScope.cancel()
        return McpSubprocessSession(spawnedProcess, transport, client, stderrCapture, stderrPump)
      }
      watchdogScope.cancel()
      teardownFailedHandshake()
      throw McpSubprocessHandshakeTimeoutException(spawnedProcess.scriptFile.name, handshakeTimeoutMillis)
    }

    /**
     * Bound on how long the session-owned stderr pump is joined during teardown / handshake
     * failure. Once the subprocess is gone the pump terminates almost immediately on the
     * stderr EOF; this is only a backstop against a wedged read so cleanup can't hang.
     */
    internal const val STDERR_PUMP_JOIN_MS: Long = 2_000L

    /**
     * Starts a daemon thread that reads [process]'s stderr line by line into [stderrCapture]
     * until EOF, surfacing error-severity lines (per [classifier]) to the host [Console] so a
     * failing subprocess is visible in the daemon output. Capture into [stderrCapture] is
     * unconditional; quieter lines live only in the per-session capture log. Owned by the
     * session (see [connect]) rather than the MCP transport so a stdout-EOF crash can't cancel
     * it before the child's final diagnostics are captured.
     *
     * A line the [classifier] grades [StdioClientTransport.StderrSeverity.FATAL] fails the
     * session fast, mirroring the transport's old FATAL handling (which called its error
     * handler and stopped processing): the subprocess is force-terminated, so its stdio reaches
     * EOF and the in-flight / next dispatch surfaces the failure instead of continuing to talk
     * to a server its own author declared dead. The default classifier never returns FATAL, so
     * this only fires for a caller-supplied classifier that opts into it.
     */
    private fun startStderrPump(
      process: Process,
      scriptName: String,
      stderrCapture: StderrCapture,
      classifier: (String) -> StdioClientTransport.StderrSeverity,
    ): Thread = Thread {
      runCatching {
        process.errorStream.bufferedReader().use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            stderrCapture.accept(line)
            routeStderrLine(line, scriptName, classifier(line)) {
              // Mirror the transport's old fail-fast on a fatal-classified line: tear the
              // subprocess down so the session can't keep dispatching to a doomed server.
              runCatching { process.destroyForcibly() }
            }
          }
        }
      }
    }.apply {
      isDaemon = true
      name = "trailblaze-mcp-stderr-$scriptName"
      start()
    }
  }
}

/**
 * Routes one already-captured stderr line by its [severity]: error-severity lines
 * ([StdioClientTransport.StderrSeverity.WARNING] / [StdioClientTransport.StderrSeverity.FATAL])
 * are surfaced to the host [Console] so a failing subprocess is visible in the daemon output;
 * quieter levels stay in the per-session capture log only. A FATAL line additionally invokes
 * [onFatal] — the pump wires that to force-terminating the subprocess, mirroring the transport's
 * old fail-fast. Extracted so the severity → action mapping (including the otherwise
 * caller-only FATAL path) is unit-testable without spawning a subprocess.
 */
internal fun routeStderrLine(
  line: String,
  scriptName: String,
  severity: StdioClientTransport.StderrSeverity,
  onFatal: () -> Unit,
) {
  when (severity) {
    StdioClientTransport.StderrSeverity.FATAL -> {
      Console.error("[$scriptName] $line")
      onFatal()
    }
    StdioClientTransport.StderrSeverity.WARNING -> Console.error("[$scriptName] $line")
    StdioClientTransport.StderrSeverity.INFO,
    StdioClientTransport.StderrSeverity.DEBUG,
    StdioClientTransport.StderrSeverity.IGNORE,
    -> Unit
  }
}

/**
 * Joins [thread] for up to [millis], best-effort. A timeout returns quietly; an interrupt is
 * swallowed but the thread's interrupt flag is restored so cancellation still propagates at the
 * caller's next suspension point. Used for the session-owned stderr pump on every teardown path
 * (drain-before-snapshot, shutdown, handshake-failure cleanup) so a wedged pump can never hang
 * or silently eat an interrupt.
 */
private fun joinPreservingInterrupt(thread: Thread, millis: Long) {
  try {
    thread.join(millis)
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
  }
}

/**
 * Routes an inbound MCP `notifications/message` (sent by scripted tools via `ctx.logger.*`)
 * into the host's [Console]. `error` / `critical` / `alert` / `emergency` go to
 * [Console.error]; everything else to [Console.log], which honors the host's stdout/stderr
 * redirect and quiet-mode settings.
 *
 * The `data` field on the wire is either a plain string (for `ctx.logger.info("foo")`) or a
 * JSON object containing `message` and optional `fields` (for `ctx.logger.info("foo", { ...
 * })`). We unwrap both shapes into a flat `[<logger>] <message> <fields-json>` line so the
 * Console abstraction sees a single string just like every other log emitter on the host.
 */
internal fun routeLoggingMessage(
  params: LoggingMessageNotificationParams,
  fallbackLoggerName: String,
) {
  val loggerLabel = params.logger ?: fallbackLoggerName
  val line = "[$loggerLabel] " + renderLoggingData(params.data)
  when (params.level) {
    LoggingLevel.Error,
    LoggingLevel.Critical,
    LoggingLevel.Alert,
    LoggingLevel.Emergency,
    LoggingLevel.Warning -> Console.error(line)
    LoggingLevel.Notice,
    LoggingLevel.Info,
    LoggingLevel.Debug -> Console.log(line)
  }
}

private fun renderLoggingData(data: JsonElement): String =
  when (data) {
    is JsonPrimitive ->
      // Bare-string payload (`ctx.logger.info("foo")`) → unwrap from JSON quoting.
      data.contentOrNull ?: data.toString()
    is JsonObject -> {
      // Structured payload from the TS SDK: `{ message, fields? }`. Render as
      // "<message> <fields-json>" when fields present; otherwise just the message.
      val msg = data["message"]?.jsonPrimitive?.contentOrNull
      val fields = data["fields"]?.jsonObject
      when {
        msg != null && fields != null && fields.isNotEmpty() -> "$msg ${fields}"
        msg != null -> msg
        else -> data.toString()
      }
    }
    else -> data.toString()
  }

/**
 * Escalates [process] teardown under one [exitWait] knob: SIGTERM → wait → SIGKILL → wait.
 * Assumes the caller has already signalled the subprocess (e.g. by closing stdin via
 * `client.close()`) and is just waiting for it to exit before escalating. Shared between
 * the public `shutdown` path and the initialize-failure cleanup inside `connect` so both
 * honour the same [Duration] configuration.
 *
 * Must run under [Dispatchers.IO] — uses blocking [Process.waitFor].
 */
private fun destroyWithEscalation(process: Process, exitWait: McpSubprocessSession.Duration) {
  if (!process.waitFor(exitWait.afterCloseSeconds, TimeUnit.SECONDS)) {
    process.destroy()
    if (!process.waitFor(exitWait.afterSigtermSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      process.waitFor(exitWait.afterSigkillSeconds, TimeUnit.SECONDS)
    }
  }
}

/**
 * Thrown when a subprocess MCP `initialize` handshake ([McpSubprocessSession.connect]) does not
 * complete within its timeout. Carries the script name + timeout so the session-startup failure
 * (see [McpSubprocessRuntimeLauncher.launchAll]'s fail-fast) is attributable to the offending
 * script. The subprocess has already been torn down by the time this is thrown.
 *
 * [cause] preserves the stream-closed exception the watchdog's force-destroy produced (the parked
 * `client.connect` read unwinds with it) so the underlying failure isn't lost in the daemon log.
 */
class McpSubprocessHandshakeTimeoutException(
  val scriptName: String,
  val timeoutMillis: Long,
  cause: Throwable? = null,
) : Exception(
  "MCP subprocess '$scriptName' did not complete its initialize handshake within ${timeoutMillis}ms",
  cause,
)
