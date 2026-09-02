package xyz.block.trailblaze.logs.server.endpoints

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.block.trailblaze.util.Console

/**
 * Manages async trail run lifecycle.
 *
 * Wraps the existing synchronous `onRunRequest` callback with state tracking so
 * that the CLI can submit a run and poll for progress instead of holding a
 * blocking HTTP connection open for the entire trail execution.
 */
class CliRunManager(
  private val onRunRequest: suspend (CliRunRequest, onProgress: (String) -> Unit) -> CliRunResponse,
) : java.io.Closeable {
  private val runs = ConcurrentHashMap<String, MutableRunState>()
  // SupervisorJob, not Job: runs are independent, and this scope is shared by every one of them.
  // Under a plain Job a single child that fails cancels the scope, and every later submitRun
  // returns a runId whose body never executes — the run sits in PENDING and the CLI waits out its
  // whole no-progress window for a trail with nothing wrong with it. The catch below stops the
  // failures it can see; this stops the ones it can't, including anything thrown before the try
  // is entered.
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private class MutableRunState(
    var state: RunState = RunState.PENDING,
    @Volatile var sessionId: String? = null,
    @Volatile var progressMessage: String? = null,
    var result: CliRunResponse? = null,
    var job: Job? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long = 0L,
    /** Human-readable name for "who is using this daemon" surfaces (status/shutdown logs). */
    val runLabel: String? = null,
  )

  /** Submit a run request. Returns the runId immediately. */
  fun submitRun(request: CliRunRequest): String {
    val runId = UUID.randomUUID().toString()
    val runState = MutableRunState(
      runLabel = request.trailFilePath ?: request.testName,
    )
    runs[runId] = runState

    runState.job = scope.launch {
      synchronized(runState) { runState.state = RunState.RUNNING }
      try {
        val response = onRunRequest(request) { message ->
          runState.progressMessage = message
        }
        synchronized(runState) {
          runState.sessionId = response.sessionId
          runState.result = response
          runState.state = if (response.success) RunState.COMPLETED else RunState.FAILED
          runState.completedAt = System.currentTimeMillis()
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        synchronized(runState) {
          runState.state = RunState.CANCELLED
          runState.result = CliRunResponse(success = false, error = "Cancelled")
          runState.completedAt = System.currentTimeMillis()
        }
      } catch (t: Throwable) {
        // Throwable, not Exception: the CLI has no other way to learn a run died. It polls
        // /cli/run-status and gives up only after a full no-progress window, so an Error that
        // escapes this catch (a NoClassDefFoundError from a half-built classpath, a
        // StackOverflowError, an initializer failure) leaves the run stuck in RUNNING and the
        // user staring at a silent prompt for ten minutes, ending in a watchdog message that
        // names the timeout rather than what broke. Escaping also cancels `scope`, which every
        // later run shares, so one Error wedges the daemon for runs that had nothing wrong.
        //
        // Terminal state FIRST, diagnostics after. Rendering a stack trace allocates, and this
        // catch handles OutOfMemoryError: if the render throws under memory pressure before the
        // state flips, the run stays RUNNING and the CLI waits out the whole no-progress window —
        // the exact failure this catch exists to prevent, reintroduced by the logging meant to
        // explain it. Everything below reads fields already on the throwable, so it allocates
        // nothing worth failing on.
        synchronized(runState) {
          runState.state = RunState.FAILED
          runState.result = CliRunResponse(
            success = false,
            // StackOverflowError and friends carry a null message. javaClass.name rather than
            // qualifiedName: the latter is null for a local or anonymous throwable class, which
            // would land back on a useless "Unknown error" for a type the JVM can name fine.
            error = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.name,
          )
          runState.completedAt = System.currentTimeMillis()
        }
        // The response carries the message only, and for the classpath failures this catch exists
        // for the frame that names the class-load site IS the diagnostic. Log the trace here so
        // the daemon log has it even though the CLI's one-line error can't. Best-effort: the run
        // is already terminal, so losing the trace costs a diagnostic, not the CLI's exit.
        runCatching {
          Console.log("[CliRunManager] run $runId failed: ${t::class.simpleName}: ${t.message}")
          Console.log(t.stackTraceToString())
        }
      }
    }

    // Clean up old entries
    cleanupExpired()

    return runId
  }

  /** Get the current status snapshot for a run. */
  fun getStatus(runId: String): CliRunStatusResponse? {
    val run = runs[runId] ?: return null
    synchronized(run) {
      return CliRunStatusResponse(
        runId = runId,
        state = run.state,
        sessionId = run.sessionId,
        progressMessage = run.progressMessage,
        result = run.result,
      )
    }
  }

  /**
   * Number of runs currently PENDING or RUNNING. Exposed on `/cli/status` (and logged by the
   * shutdown endpoint) so external tooling — e.g. the dev launcher's stale-JAR daemon restart —
   * can tell a busy daemon from an idle one before deciding to stop it.
   */
  fun activeRunCount(): Int = runs.values.count {
    synchronized(it) { it.state == RunState.PENDING || it.state == RunState.RUNNING }
  }

  /**
   * One human-readable line per in-flight run — trail name, state, age, session, latest
   * progress — so the surfaces that refuse to (or are about to) stop a busy daemon can say
   * exactly WHO is using it, not just how many.
   */
  fun activeRunSummaries(): List<String> {
    val now = System.currentTimeMillis()
    return runs.values.mapNotNull { run ->
      synchronized(run) {
        if (run.state != RunState.PENDING && run.state != RunState.RUNNING) return@mapNotNull null
        buildString {
          append(run.runLabel ?: "unnamed run")
          append(" — ${run.state.name.lowercase()} for ${(now - run.createdAt) / 1000}s")
          run.sessionId?.let { append(", session $it") }
          run.progressMessage?.let { append(" — $it") }
        }
      }
    }
  }

  /** Cancel an in-flight run. Returns true if the run was found and cancellation was requested. */
  fun cancelRun(runId: String): Boolean {
    val run = runs[runId] ?: return false
    synchronized(run) {
      if (run.state != RunState.PENDING && run.state != RunState.RUNNING) return false
      run.job?.cancel()
      run.state = RunState.CANCELLED
      run.result = CliRunResponse(success = false, error = "Cancelled")
      run.completedAt = System.currentTimeMillis()
    }
    return true
  }

  /** Remove entries that completed more than [ttlMs] ago. */
  private fun cleanupExpired(ttlMs: Long = COMPLETED_TTL_MS) {
    val now = System.currentTimeMillis()
    runs.entries.removeIf { (_, run) ->
      val terminal = run.state == RunState.COMPLETED ||
        run.state == RunState.FAILED ||
        run.state == RunState.CANCELLED
      terminal && run.completedAt > 0L && (now - run.completedAt > ttlMs)
    }
  }

  override fun close() {
    scope.coroutineContext[Job]?.cancel()
  }


  companion object {
    /** Keep completed run entries for 5 minutes before cleanup. */
    private const val COMPLETED_TTL_MS = 5 * 60 * 1000L
  }
}
