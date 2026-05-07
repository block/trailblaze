package xyz.block.trailblaze.scripting

import xyz.block.trailblaze.scripting.subprocess.LaunchedSubprocessRuntime
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Per-session lifecycle handle for both forms of session-scoped scripting machinery:
 *
 *  - **Inline scripted tools** (declared under a pack manifest's `target.tools:`) — each
 *    runs its handler inside an in-process [QuickJsToolHost][xyz.block.trailblaze.quickjs.tools.QuickJsToolHost].
 *    Owned by the per-tool [LazyYamlScriptedToolRegistration] entries.
 *  - **External MCP servers** (declared under `mcp_servers:`) — each runs as its own
 *    subprocess. Owned by [LaunchedSubprocessRuntime].
 *
 * Returned by `TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny` so the session
 * cleanup lambda has one shutdown surface to call regardless of which forms a target uses.
 *
 * Replaces the bare `LaunchedSubprocessRuntime?` return type from before #2749 — same
 * `shutdownAll()` method shape, so caller cleanup loops (`runtimes.forEach { it.shutdownAll() }`)
 * carry over unchanged. Only the declared element type changes.
 */
class LaunchedScriptingRuntime internal constructor(
  /** Subprocess MCP runtime, null when the target only declares inline scripted tools. */
  val subprocessRuntime: LaunchedSubprocessRuntime?,
  /** Inline scripted-tool registrations whose hosts need disposal at session end. */
  val inlineRegistrations: List<LazyYamlScriptedToolRegistration>,
  /**
   * The session's [TrailblazeToolRepo] that the inline registrations were added to via
   * `addDynamicTools`. Held here so [shutdownAll] can deregister them by name during
   * teardown — without this, the repo would keep stale registrations whose backing
   * QuickJS engines have been freed, and a repo reuse (e.g. a daemon that runs
   * back-to-back sessions against the same repo instance) would surface
   * `NameAlreadyRegistered` collisions on the next session's `addDynamicTools` call.
   */
  private val toolRepo: TrailblazeToolRepo,
) {

  /**
   * Shut down every spawned subprocess, deregister every dynamic tool from the repo,
   * and free every inline scripted-tool's QuickJS engine. Best-effort — failures in any
   * one shutdown don't short-circuit the rest, mirroring the policy
   * [LaunchedSubprocessRuntime.shutdownAll] already enforces on its own internals.
   */
  suspend fun shutdownAll() {
    // Inline registrations first — their QuickJS engines may hold references the
    // subprocess teardown would invalidate, and the order matches the construction order
    // in TrailblazeHostYamlRunner (inline registered first via toolRepo.addDynamicTools).
    // Deregister-then-dispose mirrors `LaunchedSubprocessRuntime.shutdownAll`'s order so
    // a tool can't be dispatched to mid-teardown after its host is freed.
    for (registration in inlineRegistrations) {
      try {
        toolRepo.removeDynamicTool(registration.name)
      } catch (e: Throwable) {
        Console.log(
          "[LaunchedScriptingRuntime] SHUTDOWN_FAIL kind=deregister " +
            "tool=${registration.name.toolName} reason=${e::class.simpleName}: ${e.message}",
        )
      }
      try {
        registration.dispose()
      } catch (e: Throwable) {
        Console.log(
          "[LaunchedScriptingRuntime] SHUTDOWN_FAIL kind=dispose " +
            "tool=${registration.name.toolName} reason=${e::class.simpleName}: ${e.message}",
        )
      }
    }
    subprocessRuntime?.shutdownAll()
  }
}
