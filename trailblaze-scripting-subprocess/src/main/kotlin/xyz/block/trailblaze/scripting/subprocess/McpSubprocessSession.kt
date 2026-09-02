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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicReference
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
     * How long [connect]'s process-exit lever waits, after the subprocess exits, before claiming the
     * handshake outcome.
     *
     * A subprocess can write its whole `initialize` response and exit in the same breath: the bytes
     * sit in the pipe buffer and the handshake completes off them a moment later, so exit alone does
     * NOT prove the handshake is doomed. Claiming instantly would fail a connect that was about to
     * succeed. This pause lets the buffered read land — after it, a still-pending handshake has
     * nothing left to read and no process to answer it, which is the orphaned-await park that only a
     * lever can unwedge.
     *
     * Deliberately small relative to [DEFAULT_HANDSHAKE_TIMEOUT_MS]: the whole point of the lever is
     * to fail in milliseconds where the bound would have taken a minute, so this must stay orders of
     * magnitude below it.
     *
     * This is a ceiling, not the value actually waited — see [deadSubprocessGraceMs], which scales it
     * down for callers that pass a bound this coarse grace would outlast.
     */
    internal const val DEAD_SUBPROCESS_GRACE_MS: Long = 250L

    /**
     * The grace [connect] actually waits, for a handshake bounded at [handshakeTimeoutMillis].
     *
     * A fixed [DEAD_SUBPROCESS_GRACE_MS] silently disables the exit lever for short bounds: the
     * timeout racer fires at the bound while the lever is still sitting in its grace, so a subprocess
     * that exited instantly gets reported as having blown the bound — the exact misleading error this
     * lever exists to remove. Both the env override and the `handshakeTimeoutMillis` parameter accept
     * any positive value, and this module's own tests pass bounds of 250ms, so that is a reachable
     * configuration and not a theoretical one.
     *
     * Halving keeps a margin on both sides: the lever gets to claim first for a process that has
     * already exited, and there is still a real pause for the buffered-stdout case rather than a
     * claim-instantly degenerate. Floored at 1ms because `delay(0)` would reintroduce exactly the
     * steal this grace prevents.
     *
     * That margin is strict for every bound of 2ms or more. At a bound of exactly 1ms the floor makes
     * grace and bound equal, so the two racers tie and scheduling picks the winner — accepted rather
     * than special-cased, because a 1ms handshake bound cannot be met by any real subprocess, so both
     * outcomes are honest reports of the same failure.
     */
    internal fun deadSubprocessGraceMs(handshakeTimeoutMillis: Long): Long =
      minOf(DEAD_SUBPROCESS_GRACE_MS, maxOf(1L, handshakeTimeoutMillis / 2))

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
      // arm a watchdog with two levers, each covering a park the other can't:
      //
      //  - **Force-destroy the subprocess.** Closes its stdout, so a handshake parked in that
      //    blocking native read unwinds and `client.connect` throws — no matter which thread /
      //    dispatcher the handshake is running on.
      //  - **Cancel the handshake coroutine.** Destroy is a no-op when the subprocess is ALREADY
      //    dead — and an instant-exit subprocess can close the transport before the SDK registers
      //    the initialize response handler, orphaning a suspending await that no stream event will
      //    ever complete (`Protocol.doClose` snapshots the handler map before the request lands;
      //    the late-registered handler is wiped un-notified and `request`'s `result.await()` parks
      //    forever). That park is exactly what wedged this module's `check` run at 99% until the
      //    CI step was cancelled. The await is a plain cancellable suspension, so cancelling
      //    the handshake coroutine unwedges it.
      //
      // Both of those levers are armed by the SAME `delay(handshakeTimeoutMillis)`, so a subprocess
      // that dies immediately still had to burn the full bound before either could fire — and then
      // got reported as a *timeout*, which is the wrong story about a process that was gone in
      // milliseconds. So arm a third lever on the one signal that distinguishes the two:
      //
      //  - **Watch for process exit.** A dead subprocess can never answer `initialize`, so once it
      //    has exited there is nothing left to wait for: claim the outcome and unwedge the parked
      //    await immediately instead of at the bound. `onExit()` is callback-driven, so this parks
      //    no thread (a blocking `waitFor` would pin an IO thread for the whole session on the
      //    success path), and it is already complete for a subprocess that was dead on arrival.
      //    The [deadSubprocessGraceMs] pause before claiming is what keeps this from stealing a
      //    handshake that is only microseconds from completing off buffered stdout — scaled to the
      //    bound so a short bound can't make the timeout racer win this one by default.
      //
      // `decided` is the single arbiter of the outcome, claimed via compare-and-set by exactly one
      // of {watchdog fires, subprocess exits, handshake settles}. It closes the boundary race where
      // the handshake returns in the same instant the watchdog's `delay` elapses: cancelling the
      // watchdog alone can't stop the coroutine once `delay` has returned (nothing suspends after
      // it), so without the CAS the watchdog could still force-destroy a subprocess we'd already
      // handed back as a live session. Whoever wins the CAS acts; the losers stand down — and the
      // winner's identity is what the catch below turns into the right exception.
      val decided = AtomicReference<HandshakeOutcome?>(null)
      // Own the scope (not just the launched job) so every exit path can cancel it — the scope's
      // root Job would otherwise dangle. Detached from the caller's coroutine on purpose: the
      // watchdog must be able to fire while the handshake is parked in the non-cancellable
      // native read (the whole point), which a child of that coroutine could not.
      val watchdogScope = CoroutineScope(Dispatchers.IO)
      try {
        coroutineScope {
          val handshake = async { client.connect(transport) }
          watchdogScope.launch {
            delay(handshakeTimeoutMillis)
            if (decided.compareAndSet(null, HandshakeOutcome.TIMED_OUT)) {
              // A wedged subprocess writes nothing to stderr, so without this line the force-kill
              // is silent and a session-startup failure is indistinguishable from any other. Name
              // the culprit script + the bound it blew so on-call can attribute it.
              Console.log(
                "[McpSubprocessSession] handshake watchdog fired for " +
                  "'${spawnedProcess.scriptFile.name}' after ${handshakeTimeoutMillis}ms — " +
                  "force-destroying the subprocess",
              )
              runCatching { process.destroyForcibly() }
              handshake.cancel()
            }
          }
          watchdogScope.launch {
            process.onExit().await()
            delay(deadSubprocessGraceMs(handshakeTimeoutMillis))
            if (decided.compareAndSet(null, HandshakeOutcome.PROCESS_EXITED)) {
              Console.log(
                "[McpSubprocessSession] subprocess '${spawnedProcess.scriptFile.name}' exited " +
                  "during its initialize handshake (exit code ${process.exitValue()}) — " +
                  "failing the connect instead of waiting out the ${handshakeTimeoutMillis}ms bound",
              )
              handshake.cancel()
            }
          }
          handshake.await()
        }
      } catch (t: Throwable) {
        watchdogScope.cancel()
        // A genuine caller cancellation must surface as cancellation, never be re-attributed as a
        // handshake timeout. Distinguish it from the watchdog's own `handshake.cancel()` (also a
        // CancellationException) by the caller's job state: only a cancelled caller makes this
        // context inactive. Teardown still runs (under NonCancellable) so the subprocess isn't
        // leaked.
        if (t is CancellationException && !currentCoroutineContext().isActive) {
          teardownFailedHandshake()
          throw t
        }
        // If we can still claim the outcome, this was an organic handshake failure (server crashed
        // during init, bad protocol version) — propagate it unchanged. If the CAS fails, a lever
        // already claimed it and tore the subprocess down: surface the attributable error for
        // WHICHEVER lever won (with the underlying unwind — stream-closed read error or the lever's
        // handshake cancellation — as cause) rather than an opaque error, so the launcher names the
        // culprit script and the message tells the true story of how the handshake died.
        val claimedByUs = decided.compareAndSet(null, HandshakeOutcome.HANDSHAKE_SETTLED)
        teardownFailedHandshake()
        if (!claimedByUs) {
          throw when (decided.get()) {
            HandshakeOutcome.PROCESS_EXITED -> McpSubprocessExitedDuringHandshakeException(
              spawnedProcess.scriptFile.name,
              runCatching { process.exitValue() }.getOrNull(),
              t,
            )
            else -> McpSubprocessHandshakeTimeoutException(
              spawnedProcess.scriptFile.name,
              handshakeTimeoutMillis,
              t,
            )
          }
        }
        throw t
      }
      // Handshake returned. Race the levers for the outcome: if we win, they stand down (their CAS
      // will fail) and we hand back the live session. If we lose, a lever is already tearing the
      // subprocess down — don't return a session whose process is going away under it; fail with
      // that lever's error, consistent with the catch above.
      if (decided.compareAndSet(null, HandshakeOutcome.HANDSHAKE_SETTLED)) {
        watchdogScope.cancel()
        return McpSubprocessSession(spawnedProcess, transport, client, stderrCapture, stderrPump)
      }
      watchdogScope.cancel()
      teardownFailedHandshake()
      throw when (decided.get()) {
        HandshakeOutcome.PROCESS_EXITED -> McpSubprocessExitedDuringHandshakeException(
          spawnedProcess.scriptFile.name,
          runCatching { process.exitValue() }.getOrNull(),
        )
        else -> McpSubprocessHandshakeTimeoutException(spawnedProcess.scriptFile.name, handshakeTimeoutMillis)
      }
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
 * Which of [McpSubprocessSession.connect]'s racers claimed the handshake outcome. Exactly one wins
 * the compare-and-set; the winner's identity is what selects the exception the caller sees, so a
 * subprocess that died in milliseconds is never reported as having blown a 60-second bound.
 */
internal enum class HandshakeOutcome {
  /** `client.connect` returned or threw on its own — an organic result, whatever it was. */
  HANDSHAKE_SETTLED,

  /** The watchdog's bound elapsed with the handshake still pending. */
  TIMED_OUT,

  /** The subprocess exited (and stayed exited past the grace pause) mid-handshake. */
  PROCESS_EXITED,
}

/**
 * Thrown when the subprocess exits during its MCP `initialize` handshake
 * ([McpSubprocessSession.connect]). A dead subprocess can never answer, so this fails the connect as
 * soon as the exit is observed rather than waiting out
 * [McpSubprocessSession.DEFAULT_HANDSHAKE_TIMEOUT_MS].
 *
 * Distinct from [McpSubprocessHandshakeTimeoutException] on purpose: "your script exited immediately"
 * and "your script never answered" are different bugs with different fixes, and reporting the former
 * as a timeout sends the reader looking for a hang that never happened.
 *
 * [exitCode] is the subprocess's status when known, and it is the first thing to look at because it
 * splits the two shapes this covers: non-zero means the script itself failed (a missing import, a
 * throw at module scope) and its stderr tail holds the reason, while zero means it ran to completion
 * without ever serving MCP — usually a script that forgot to start the server, where stderr is empty
 * and the source is what to read. The message stays neutral between them rather than asserting a
 * startup crash. Null only if the process could not be queried.
 */
class McpSubprocessExitedDuringHandshakeException(
  val scriptName: String,
  val exitCode: Int?,
  cause: Throwable? = null,
) : Exception(
  "MCP subprocess '$scriptName' exited during its initialize handshake" +
    (exitCode?.let { " (exit code $it)" } ?: "") +
    " — a non-zero code means the script failed (see its stderr); zero means it exited without" +
    " serving MCP",
  cause,
)

/**
 * Thrown when a subprocess MCP `initialize` handshake ([McpSubprocessSession.connect]) does not
 * complete within its timeout. Carries the script name + timeout so the session-startup failure
 * (see [McpSubprocessRuntimeLauncher.launchAll]'s fail-fast) is attributable to the offending
 * script. The subprocess has already been torn down by the time this is thrown.
 *
 * A subprocess that *exited* mid-handshake raises [McpSubprocessExitedDuringHandshakeException]
 * instead — this one means the process was still alive and simply never answered.
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
