package xyz.block.trailblaze.android.accessibility

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Keeps one session's scripted-tool bundle launch alive across that session's dispatches.
 *
 * On the host-drives-the-loop RPC path every replayed action is its own `run_yaml` dispatch, and
 * `AndroidTrailblazeRule.runSuspend` treats each one as a whole run: it launches the catalog's
 * QuickJS bundles at the top and shuts them down in its `finally`. For a recorded replay that is a
 * per-action tax on tools the action never calls.
 *
 * The reuse is keyed on **(session, tool repo instance)**, not on the session alone, because a
 * launch registers its tools INTO a repo — a dispatch that built a different repo would find the
 * tools missing and fail "Unknown tool" at dispatch. When a claim misses, the caller launches
 * normally, which is exactly today's behaviour.
 *
 * On under turbo; see [ReplayCaptureOptions.REUSE_TOOL_BUNDLES_SYSPROP].
 */
object ScriptedToolBundleReuse {

  private class Retained(
    val sessionId: SessionId,
    val repo: TrailblazeToolRepo,
    val runtime: LaunchedQuickJsToolRuntime?,
  )

  @Volatile
  private var retained: Retained? = null

  /**
   * True when [sessionId] already has a launch registered into [repo], so the caller can skip both
   * the launch and its teardown. A miss leaves the cache untouched — the caller launches and then
   * calls [retain].
   */
  @Synchronized
  fun claim(sessionId: SessionId, repo: TrailblazeToolRepo): Boolean {
    val current = retained ?: return false
    return current.sessionId == sessionId && current.repo === repo
  }

  /**
   * Serializes claim-then-launch, because the two are one decision.
   *
   * A bare [claim] followed by a launch has a window between them, and the launch is the slow part:
   * two overlapping dispatches of one session would both miss the claim and both launch into the
   * same shared repo. The second launch then dies registering tool names the first already
   * registered ("is already registered by another dynamic source"), because
   * `TrailblazeToolRepo.addDynamicTools` refuses to shadow a name.
   */
  private val launchLock = Mutex()

  /**
   * True when [sessionId]'s bundles were already launched into [repo], false when [launch] ran.
   *
   * A launch returned by [launch] is retained here, so the caller must not shut it down itself. An
   * overlapping dispatch waits for an in-flight launch and then reuses it rather than starting a
   * second one — the wait is shorter than the launch it replaces.
   */
  suspend fun claimOrLaunch(
    sessionId: SessionId,
    repo: TrailblazeToolRepo,
    launch: suspend () -> LaunchedQuickJsToolRuntime?,
  ): Boolean = launchLock.withLock {
    if (claim(sessionId, repo)) return@withLock true
    retain(sessionId, repo, launch())
    false
  }

  /**
   * Takes ownership of [runtime] for the rest of [sessionId]'s dispatches, shutting down whatever
   * it replaces. A retained launch therefore never outlives its usefulness: the next dispatch that
   * could not reuse it is what closes it.
   *
   * Replacing means shutting down, not just forgetting, even within one session. A caller that
   * builds a fresh tool repo per dispatch — which is every caller that does not hand this rule a
   * repo of its own — misses [claim] every time and lands here every time; dropping the previous
   * runtime on the floor there would leak one QuickJS context per action.
   */
  @Synchronized
  fun retain(sessionId: SessionId, repo: TrailblazeToolRepo, runtime: LaunchedQuickJsToolRuntime?) {
    val previous = retained
    if (replacesPreviousLaunch(previous?.runtime, runtime)) {
      previous?.let { shutdown(it) }
    }
    retained = Retained(sessionId, repo, runtime)
  }

  /**
   * True when retaining [incoming] displaces a live [previous] launch, which therefore has to be
   * shut down rather than dropped. Pure so the leak this closes is testable without a QuickJS
   * engine: the same runtime being re-retained is a no-op, anything else is a replacement.
   */
  internal fun replacesPreviousLaunch(previous: Any?, incoming: Any?): Boolean =
    previous != null && previous !== incoming

  /**
   * Releases [sessionId]'s retained launch, if the retained launch is still that session's.
   *
   * Called by the dispatch that owns an end of the session — `AndroidTrailblazeRule.runSuspend`'s
   * `finally`, when this dispatch sent the session-start or session-end log. A retained launch
   * belongs to a session, so without that call the last session's QuickJS context would stay alive
   * until some unrelated later dispatch happened to displace it.
   *
   * Scoped to the caller's own session, not "whatever is retained". Starting a session while one
   * is running only *launches* the previous job's cancellation and then starts the replacement
   * immediately, and the cancelled job's teardown runs under `NonCancellable` — so the interrupted
   * session's `finally` can reach this after the replacement has already retained a launch of its
   * own. An unscoped release would shut down the newer session's live QuickJS runtime out from
   * under it, and every scripted tool it went on to call would fail.
   */
  @Synchronized
  fun release(sessionId: SessionId) {
    val current = retained ?: return
    if (current.sessionId != sessionId) return
    shutdown(current)
    retained = null
  }

  private fun shutdown(entry: Retained) {
    val runtime = entry.runtime ?: return
    runCatching { runBlocking { runtime.shutdownAll() } }
      .onFailure { e ->
        Console.log(
          "[reused-tool-bundles] shutdown failed for session=${entry.sessionId.value}: ${e.message}",
        )
      }
  }
}
