package xyz.block.trailblaze.logs.server.endpoints

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages async trail run lifecycle.
 *
 * Wraps the existing synchronous `onRunRequest` callback with state tracking so
 * that the CLI can submit a run and poll for progress instead of holding a
 * blocking HTTP connection open for the entire trail execution.
 */
class CliRunManager(
  private val onRunRequest: suspend (CliRunRequest) -> CliRunResponse,
) : java.io.Closeable {
  private val runs = ConcurrentHashMap<String, MutableRunState>()
  private val scope = CoroutineScope(Dispatchers.IO + Job())

  private class MutableRunState(
    var state: RunState = RunState.PENDING,
    @Volatile var sessionId: String? = null,
    @Volatile var progressMessage: String? = null,
    var result: CliRunResponse? = null,
    var job: Job? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long = 0L,
  )

  /** Submit a run request. Returns the runId immediately. */
  fun submitRun(request: CliRunRequest): String {
    val runId = UUID.randomUUID().toString()
    val runState = MutableRunState()
    runs[runId] = runState

    runState.job = scope.launch {
      synchronized(runState) { runState.state = RunState.RUNNING }
      try {
        val response = onRunRequest(request)
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
      } catch (e: Exception) {
        synchronized(runState) {
          runState.state = RunState.FAILED
          runState.result = CliRunResponse(success = false, error = e.message ?: "Unknown error")
          runState.completedAt = System.currentTimeMillis()
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

  /**
   * Update the progress message for a run. Called from the trail execution
   * callback to provide real-time progress updates to polling clients.
   */
  fun updateProgress(runId: String, message: String) {
    runs[runId]?.progressMessage = message
  }

  /**
   * Update the session ID for a run once it becomes known.
   */
  fun updateSessionId(runId: String, sessionId: String) {
    runs[runId]?.sessionId = sessionId
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
