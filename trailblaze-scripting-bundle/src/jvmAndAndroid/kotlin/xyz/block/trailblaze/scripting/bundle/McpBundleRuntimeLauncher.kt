package xyz.block.trailblaze.scripting.bundle

import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * The handle `launchAll` returns. Holds the live bundle sessions + the tool names they
 * registered so [shutdownAll] can tear everything down cleanly at session end.
 */
class LaunchedBundleRuntime internal constructor(
  val sessions: List<McpBundleSession>,
  private val repo: TrailblazeToolRepo,
  private val registeredNames: List<xyz.block.trailblaze.toolcalls.ToolName>,
) {

  /**
   * Remove every tool this launch registered from [repo], then close every bundle session.
   * Best-effort — a failure in one shutdown doesn't block the rest.
   */
  suspend fun shutdownAll() {
    for (name in registeredNames) {
      runCatching { repo.removeDynamicTool(name) }
    }
    for (session in sessions) {
      runCatching { session.shutdown() }
    }
  }
}

/**
 * Starts every declared MCP bundle at session start, registers their advertised tools
 * into [TrailblazeToolRepo], and returns a handle the caller closes at session end.
 *
 * `AndroidTrailblazeRule` calls this for you when `mcpServers` is non-empty. You'd call
 * it yourself only when writing something that skips the rule (test framework, custom
 * runner, etc.).
 *
 * Fail-fast: if any bundle fails during startup, every bundle that already started is
 * shut down and the exception propagates. `command:` entries aren't bundleable and are
 * logged + skipped.
 */
object McpBundleRuntimeLauncher {

  /**
   * @param bundleSourceResolver turns a `script:` path into loadable bytes. Default
   *   reads from the local filesystem (fine for JVM). On-device, pass a resolver that
   *   returns an `AndroidAssetBundleJsSource`.
   *
   * The on-device callback channel is wired unconditionally: every registered tool gets
   * a [BundleToolRegistration.JsScriptingCallbackContext] pointing at [toolRepo], so
   * `ctx.client.callTool(…)` inside a bundled handler dispatches through the in-process
   * `__trailblazeCallback` binding (see
   * [xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher]). No opt-in flag
   * because the callback channel has no runtime cost when unused — registrations are
   * per-invocation and only fire when a tool actually calls back.
   */
  suspend fun launchAll(
    mcpServers: List<McpServerConfig>,
    deviceInfo: TrailblazeDeviceInfo,
    @Suppress("UNUSED_PARAMETER") sessionId: SessionId,
    toolRepo: TrailblazeToolRepo,
    bundleSourceResolver: (McpServerConfig) -> BundleJsSource = { it.toBundleJsSourceFromFile() },
  ): LaunchedBundleRuntime {
    val bundleable = mcpServers.filter { it.isBundleable }
    if (bundleable.isEmpty()) {
      return LaunchedBundleRuntime(
        sessions = emptyList(),
        repo = toolRepo,
        registeredNames = emptyList(),
      )
    }
    val skipped = mcpServers.size - bundleable.size
    if (skipped > 0) {
      // `command:` entries are host-only by definition. On-device can't run arbitrary
      // executables. Log so authors notice — but don't fail: a target may legitimately
      // declare a `command:` entry for its host-agent sessions alongside bundleable
      // `script:` entries for on-device.
      Console.log(
        "MCP bundle runtime: skipping $skipped non-bundleable mcp_servers entries " +
          "(command: entries are host-only).",
      )
    }

    val started = mutableListOf<McpBundleSession>()
    val pendingRegistrations = mutableListOf<BundleToolRegistration>()
    // One shared JsScriptingCallbackContext per launch: every registered tool reaches the same live
    // TrailblazeToolRepo for callback dispatch. Construct once rather than per-registration
    // so a future per-bundle override (e.g. scoped toolRepos) has a single hook to revisit.
    val callbackContext = BundleToolRegistration.JsScriptingCallbackContext(toolRepo = toolRepo)

    try {
      for (entry in bundleable) {
        val source = bundleSourceResolver(entry)
        val session = McpBundleSession.connect(source)
        started += session

        val registered = session.fetchAndFilterTools(driver = deviceInfo.trailblazeDriverType)
        registered.forEach { reg ->
          pendingRegistrations += BundleToolRegistration(
            registered = reg,
            sessionProvider = { session },
            callbackContext = callbackContext,
          )
        }
      }
      // Atomic batch: addDynamicTools validates collisions across the whole batch before
      // inserting anything. A collision (same name advertised by two bundles) surfaces
      // as a single startup failure instead of a partial registration.
      toolRepo.addDynamicTools(pendingRegistrations)
      return LaunchedBundleRuntime(
        sessions = started.toList(),
        repo = toolRepo,
        registeredNames = pendingRegistrations.map { it.name },
      )
    } catch (t: Throwable) {
      for (session in started) {
        runCatching { session.shutdown() }
      }
      throw t
    }
  }
}
