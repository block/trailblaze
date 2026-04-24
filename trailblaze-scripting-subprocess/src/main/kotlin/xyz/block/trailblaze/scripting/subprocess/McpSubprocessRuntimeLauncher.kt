package xyz.block.trailblaze.scripting.subprocess

import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Handle returned by [McpSubprocessRuntimeLauncher.launchAll]. Owns every subprocess spawned at
 * session start so teardown can shut them all down (and deregister their tools from the
 * session's [TrailblazeToolRepo]) regardless of which entry they came from.
 *
 * Intentionally thin — no per-spawn lifecycle knobs surface here; the launcher is a
 * fire-and-forget wiring layer, and [shutdownAll] is the only operation it needs to expose to
 * the session runner. The individual [McpSubprocessSession]s remain accessible via [sessions]
 * for tests / diagnostics.
 */
class LaunchedSubprocessRuntime internal constructor(
  val sessions: List<McpSubprocessSession>,
  private val repo: TrailblazeToolRepo,
  private val registeredNames: List<xyz.block.trailblaze.toolcalls.ToolName>,
) {

  /**
   * Shut down every spawned subprocess and remove every tool they registered from the repo.
   *
   * Runs sequentially — subprocess shutdowns are individually bounded to ~9s by
   * [McpSubprocessSession.shutdown]'s escalation ladder, and per the scope devlog we abort the
   * session on any spawn failure during startup, so we're never juggling more than a handful
   * of sessions here. Parallel shutdown is a polish item if it ever becomes noticeable.
   *
   * Best-effort: failures in one shutdown don't short-circuit the rest — subprocesses and
   * tool registrations are peer resources, and a stuck MCP session shouldn't keep another
   * running or leak a dynamic-tool registration into the next session.
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
 * Session-startup wiring for Decision 038 subprocess MCP servers.
 *
 * At session start, the host runner calls [launchAll] with the resolved session context
 * ([TrailblazeDeviceInfo] + [TrailblazeConfig] + [SessionId]) and the target's
 * [McpServerConfig] entries. For each `script:` entry we:
 *
 *  1. Build an [McpSpawnContext] from the device + session snapshot.
 *  2. Open a [StderrCapture] pointing at `<sessionLogDir>/subprocess_stderr.log` so author
 *     diagnostics survive the subprocess's graceful shutdown.
 *  3. [McpSubprocessSpawner.spawn] → [McpSubprocessSession.connect] to finish the MCP
 *     `initialize` handshake.
 *  4. Run `tools/list`, filter by driver + host-agent mode, and build
 *     [SubprocessToolRegistration]s whose `sessionProvider` closes over the live session.
 *  5. Hand every registration to [TrailblazeToolRepo.addDynamicTools] as one atomic batch
 *     across all spawned subprocesses — collisions between subprocesses register as a single
 *     startup failure rather than half a session's worth of tools slipping through.
 *
 * Fail-fast: if any spawn / handshake / registration step raises, every already-spawned
 * subprocess in this call is shut down and the exception rethrows. The session-startup path
 * aborts with a clear error rather than trying to continue with a partial tool set (per the
 * scope devlog's § Subprocess lifecycle).
 *
 * `command:` entries are silently skipped today — the schema reserves them but the runtime
 * doesn't implement them in this landing. Authors get a warning through the transitional
 * loader guard (in `:trailblaze-common`) if they try to declare `command:` before support
 * lands.
 */
object McpSubprocessRuntimeLauncher {

  /**
   * Filename template for per-subprocess stderr under the session log directory. Each
   * subprocess gets its own file so concurrent writes can't interleave at character level —
   * `FileWriter(append = true)` doesn't guarantee atomic line writes across distinct writers,
   * which would produce garbled output when multiple subprocesses emit stderr concurrently.
   *
   * Filename format: `subprocess_stderr_<scriptBaseName>.log` (e.g. `subprocess_stderr_login.log`
   * for `./tools/login.ts`). Script basename makes attribution obvious in session logs. If two
   * entries declare the same basename, they get an `_<index>` suffix for disambiguation —
   * which should be vanishingly rare in practice since authors don't typically declare the
   * same script twice.
   */
  private fun stderrLogFileFor(sessionLogDir: File, scriptFile: File, indexIfCollision: Int): File {
    val base = "subprocess_stderr_${scriptFile.nameWithoutExtension}"
    val suffix = if (indexIfCollision == 0) "" else "_$indexIfCollision"
    return File(sessionLogDir, "$base$suffix.log")
  }

  /**
   * Spawn every `script:` entry in [mcpServers], handshake, filter, register, and return a
   * [LaunchedSubprocessRuntime] for the caller to shut down at session end.
   *
   * [sessionLogDir] is where per-session stderr gets captured — typically
   * `LogsRepo.getSessionDir(sessionId)`. Passed in (rather than the launcher taking a
   * `LogsRepo`) to keep this module free of a `:trailblaze-report` dependency; the caller
   * already has the repo and can derive the path.
   *
   * [toolRepo] receives all advertised tools via a single [TrailblazeToolRepo.addDynamicTools]
   * call at the end — the repo validates name collisions across the whole batch atomically
   * (see the repo's KDoc) so a collision between two subprocesses fails the session startup
   * instead of registering the first and rejecting the second.
   */
  suspend fun launchAll(
    mcpServers: List<McpServerConfig>,
    deviceInfo: TrailblazeDeviceInfo,
    config: TrailblazeConfig,
    sessionId: SessionId,
    sessionLogDir: File,
    toolRepo: TrailblazeToolRepo,
    /**
     * Trailblaze daemon's HTTP server base URL (e.g. `http://localhost:52525`). Threaded into
     * the subprocess via `TRAILBLAZE_BASE_URL` env var AND the per-call `_meta.trailblaze.baseUrl`
     * envelope so the TS SDK can hit `/scripting/callback`. Nullable because tests that don't
     * exercise the callback path can skip it — envelope injection degrades gracefully.
     */
    baseUrl: String? = null,
  ): LaunchedSubprocessRuntime {
    val scriptEntries = mcpServers.filter { it.script != null }
    if (scriptEntries.isEmpty()) {
      return LaunchedSubprocessRuntime(sessions = emptyList(), repo = toolRepo, registeredNames = emptyList())
    }
    val skipped = mcpServers.size - scriptEntries.size
    if (skipped > 0) {
      // `command:` entries are schema-reserved but not runtime-implemented. Log rather than
      // fail: authors may legitimately declare them anticipating the follow-up landing, and
      // the transitional loader warning already alerts them on YAML load.
      Console.log(
        "MCP runtime: skipping $skipped non-`script:` mcp_servers entries " +
          "(command: support not yet implemented)",
      )
    }

    val spawnContext = McpSpawnContext(
      platform = deviceInfo.platform,
      driver = deviceInfo.trailblazeDriverType,
      widthPixels = deviceInfo.widthPixels,
      heightPixels = deviceInfo.heightPixels,
      sessionId = sessionId,
      baseUrl = baseUrl,
    )

    // Only plumb the callback context when a baseUrl is actually available — the callback
    // endpoint can't be reached without it, and injecting a `_meta.trailblaze.baseUrl: null`
    // would be more surprising to SDK consumers than just omitting the envelope.
    val callbackContext = baseUrl?.let {
      SubprocessToolRegistration.JsScriptingCallbackContext(baseUrl = it, toolRepo = toolRepo)
    }

    val started = mutableListOf<McpSubprocessSession>()
    val pendingRegistrations = mutableListOf<SubprocessToolRegistration>()
    val usedBaseNames = mutableMapOf<String, Int>()

    try {
      for (entry in scriptEntries) {
        // Spawn FIRST so a `ProcessBuilder.start()` failure can't leave an unclosed
        // StderrCapture dangling — there'd be no session to own the writer's cleanup.
        val spawned = McpSubprocessSpawner.spawn(config = entry, context = spawnContext)
        val base = spawned.scriptFile.nameWithoutExtension
        val collisionIndex = usedBaseNames.getOrDefault(base, 0)
        usedBaseNames[base] = collisionIndex + 1
        val stderrCapture = StderrCapture(
          logFile = stderrLogFileFor(sessionLogDir, spawned.scriptFile, collisionIndex),
        )
        // From here on, [McpSubprocessSession.connect]'s own try/catch closes the capture
        // if the handshake fails — it was built for exactly this leak window in the
        // runtime PR's round of review fixes.
        val session = McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = stderrCapture,
        )
        started += session

        val registered = session.fetchAndFilterTools(
          driver = deviceInfo.trailblazeDriverType,
          preferHostAgent = config.preferHostAgent,
        )
        registered.forEach { reg ->
          pendingRegistrations += SubprocessToolRegistration(
            registered = reg,
            sessionProvider = { session },
            callbackContext = callbackContext,
          )
        }
      }
      // Atomic batch: TrailblazeToolRepo.addDynamicTools validates collisions across the whole
      // batch before inserting anything, so duplicate names (intra-subprocess or across
      // subprocesses) surface as a single startup failure rather than a partial registration.
      toolRepo.addDynamicTools(pendingRegistrations)
      return LaunchedSubprocessRuntime(
        sessions = started.toList(),
        repo = toolRepo,
        registeredNames = pendingRegistrations.map { it.name },
      )
    } catch (t: Throwable) {
      // Session startup is aborting: shut down whatever did spawn so we don't leak subprocesses
      // or stderr-capture file handles on a half-successful launch.
      for (session in started) {
        runCatching { session.shutdown() }
      }
      throw t
    }
  }
}
