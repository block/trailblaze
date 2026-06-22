package xyz.block.trailblaze.scripting

import java.io.File
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl
import xyz.block.trailblaze.scripting.subprocess.InlineScriptToolServerSynthesizer
import xyz.block.trailblaze.scripting.subprocess.McpSubprocessRuntimeLauncher
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Launches + registers a host session's scripted tools into its [TrailblazeToolRepo], so they are
 * dispatchable by name (LLM-driven `tap`-style steps, recorded-replay re-execution, AND Kotlin
 * composition via `invokeFrameworkTool` / `ctx.tools.<name>`).
 *
 * Extracted verbatim from `TrailblazeHostYamlRunner` so the **two** in-process hosts of the agent
 * loop share ONE launch path instead of drifting:
 *  - the daemon / CLI runner (`TrailblazeHostYamlRunner`)
 *  - the JUnit host test rule (`BaseHostTrailblazeTest`) — which previously tracked scripted-tool
 *    NAMES (for advertisement/exclusion) but never LAUNCHED them, so a recorded iOS/Android-host
 *    trail that re-executed a composite tool dispatching a scripted step via `invokeFrameworkTool`
 *    hit "Unknown framework tool" on replay. This is the gap that blocked migrating a target's
 *    launch sub-tools to TypeScript on the host path (the on-device path already registers them via
 *    `AndroidTrailblazeRule`).
 *
 * Two delivery routes, mirroring the daemon:
 *  - **target-declared** (`target.tools:`) IN-PROCESS tools are esbuild-bundled live at session
 *    start ([DaemonScriptedToolBundler]) — no committed `.bundle.js` needed.
 *  - **catalog/toolset-delivered** scripted tools load their committed `.bundle.js` from the
 *    classpath via the shared [InProcessScriptedToolLauncher].
 *  - target-declared tools that opt into [ScriptedToolRuntime.SUBPROCESS] route through the Node/Bun
 *    subprocess MCP synthesizer ([InlineScriptToolServerSynthesizer] + [McpSubprocessRuntimeLauncher]).
 *
 * Cleanup is the caller's responsibility: wrap [LaunchedScriptingRuntime.shutdownAll] in the
 * teardown path inside `withContext(NonCancellable)` so subprocess + QuickJS-engine handles are
 * freed even when the surrounding coroutine is cancelled (trail timeout, user abort).
 *
 * Returns `null` when the session has no launchable scripted tools (nothing to clean up).
 */
object HostScriptedToolLauncher {

  /**
   * @param includeSubprocess when `false`, the Node/Bun **subprocess** MCP path is skipped entirely
   *   — only the in-process (QuickJS) target-declared + catalog tools are registered. The daemon
   *   passes `true` (its historical behavior). The JUnit host test rule passes `false`: it has never
   *   spawned subprocess scripted tools, and a target's `target.tools:` list is NOT platform-scoped
   *   (`getInlineScriptTools()` returns every entry), so a Maestro-host session would otherwise newly
   *   fork a web-only subprocess sign-in tool just to register a tool it never dispatches. The
   *   TypeScript launch step a mobile target composes is in-process, so `false` covers it without
   *   that risk.
   */
  suspend fun launch(
    targetTestApp: TrailblazeHostAppTarget?,
    config: TrailblazeConfig,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    logsRepo: LogsRepo,
    toolRepo: TrailblazeToolRepo,
    classLoader: ClassLoader,
    logPrefix: String,
    includeSubprocess: Boolean = true,
    onProgressMessage: (String) -> Unit,
  ): LaunchedScriptingRuntime? {
    val sessionDir = logsRepo.getSessionDir(sessionId)

    // Idempotent launch: skip target-declared tools already registered on this repo by an earlier
    // pass in the same session. The daemon can reach this launcher twice against one repo (e.g. an
    // iOS-host trail run whose session setup already registered the target's `target.tools:` tools),
    // and `addDynamicTools` throws on a duplicate name ("Dynamic tool 'someScriptedTool' is
    // already registered by another dynamic source"), crashing the whole launch over tools that are
    // already present and working. (#3912 made the shared catalog launcher idempotent the same way;
    // this covers the target-declared esbuild path it doesn't reach.) Empty on the JUnit host path
    // (fresh per-test repo), so that path is unaffected.
    val preRegistered: Set<ToolName> = toolRepo.getRegisteredDynamicTools().keys

    // 1. Inline scripted tools (target.tools: in trailmap manifests) — the #2749 path. Each tool
    // routes to one of two runtimes: subprocess (full Node API surface) or QuickJS in-process
    // (composes via client.callTool(...), no subprocess fork). A tool runs in-process unless its
    // descriptor explicitly sets `runtime: subprocess` — there is no extension heuristic.
    val targetToolConfigs = targetTestApp?.getInlineScriptTools().orEmpty()
    val (nodeApiInlineTools, quickJsInlineTools) = targetToolConfigs.partition { tool ->
      ScriptedToolRuntime.resolve(tool.runtime) == ScriptedToolRuntime.SUBPROCESS
    }
    val targetInlineRegistrations = if (quickJsInlineTools.isNotEmpty()) {
      val esbuildBinary = LazyYamlScriptedToolRegistration.resolveEsbuildBinary()
      if (esbuildBinary == null) {
        // `Console.error` (not `Console.log`/`info`) so this loud, actionable breadcrumb
        // survives CLI quiet mode AND is unmistakable in the daemon log. A silent skip here
        // is exactly what made a fresh daemon ship with launch-critical TS scripted tools
        // unregistered (e.g. a target's TypeScript launch step that the app's
        // `launchAppSignedIn`-style orchestrator composes by name) — the failure then surfaced
        // hours later as a cryptic "Unknown framework tool" at trail-dispatch time. Name the two recoveries
        // explicitly: the hermit-pinned toolchain (`source bin/activate-hermit`, which puts
        // bun on PATH so the SDK's esbuild walk-up resolves) and `bun install` (which
        // populates `sdks/typescript/node_modules/.bin/esbuild` in the first place).
        Console.error(
          "[#2749] esbuild binary not found on PATH or build-tree fallback locations — " +
            "${quickJsInlineTools.size} inline scripted tool(s) (${quickJsInlineTools.joinToString(", ") { it.name }}) " +
            "WILL NOT be registered this session, so any trail that composes them (e.g. an app " +
            "launch step) will fail. Recover by running `source bin/activate-hermit` (puts the " +
            "repo's pinned bun on PATH) and/or `bun install` in the Trailblaze TypeScript SDK " +
            "directory (`sdks/typescript`; nested one directory deeper in a combined monorepo " +
            "checkout), then restart the daemon (`./trailblaze app --stop` then re-run).",
        )
        onProgressMessage(
          "Skipping ${quickJsInlineTools.size} inline scripted tool(s): esbuild not found " +
            "on PATH or in the Trailblaze SDK's `node_modules/.bin/` (both the flat and " +
            "nested repo layouts are checked). Run `source bin/activate-hermit` and/or " +
            "`bun install` in the Trailblaze TypeScript SDK directory (`sdks/typescript`; " +
            "nested one directory deeper in a combined monorepo checkout), then restart the daemon.",
        )
        emptyList()
      } else {
        val bundler = DaemonScriptedToolBundler(esbuildBinary)
        // Static-analysis pre-pass (#3190): a tool whose import closure reaches `node:*` builtins or
        // Node-only npm deps would fail the real bundle pass and tank session start for every
        // sibling tool in the QuickJS partition. The analyzer lets us skip such tools cleanly and
        // register the on-device-viable siblings.
        val analyzer = ScriptedToolImportAnalyzer(esbuildBinary)
        val partition = partitionByImportClosure(quickJsInlineTools, analyzer)
        val toBundle = partition.toBundle.filter { ToolName(it.name) !in preRegistered }
        // Build registrations one by one so a partial failure can dispose the QuickJS engines we
        // already created before rethrowing — otherwise an aborted session start leaks engines.
        val accumulated = mutableListOf<LazyYamlScriptedToolRegistration>()
        try {
          for (tool in toBundle) {
            val bundlePath = bundler.bundleOne(resolveScriptFile(tool.script), tool.name)
            accumulated += LazyYamlScriptedToolRegistration.create(
              toolConfig = tool,
              bundlePath = bundlePath,
              toolRepo = toolRepo,
              sessionId = sessionId,
            )
          }
          // Last opportunity to throw before LaunchedScriptingRuntime owns the registrations; if it
          // raises (e.g. name collision against the existing repo), the cleanup below disposes the
          // already-created hosts.
          toolRepo.addDynamicTools(accumulated)
        } catch (e: Throwable) {
          Console.log(
            "$logPrefix Rolling back ${accumulated.size} inline " +
              "scripted-tool registration(s) due to startup failure: ${e.message}",
          )
          for (reg in accumulated) {
            runCatching { reg.dispose() }
          }
          throw e
        }
        accumulated
      }
    } else {
      emptyList()
    }

    // 1b. Toolset-delivered scripted tools — pre-compiled QuickJS bundles loaded from classpath via
    // the shared in-process launcher (also used by the MCP daemon). Target-declared scripted tools
    // (handled above) win on name collision, so they're passed as skipNames.
    val toolsetRegistrations = InProcessScriptedToolLauncher.launch(
      toolRepo = toolRepo,
      sessionId = sessionId,
      sessionDir = sessionDir,
      toolNames = toolRepo.allCatalogScriptedToolNames,
      skipNames = targetToolConfigs.map { ToolName(it.name) }.toSet(),
      classLoader = classLoader,
      logPrefix = logPrefix,
    )
    val inlineRegistrations = targetInlineRegistrations + toolsetRegistrations

    // 2. MCP subprocesses: synthesized wrappers for inline scripted tools whose effective runtime is
    // SUBPROCESS (explicit `runtime: subprocess`). If subprocess launch throws after the QuickJS-path
    // inline registrations succeeded, the inline regs are stranded in the toolRepo with no cleanup
    // handle — catch + dispose them before rethrowing.
    val mcpServers = if (includeSubprocess && nodeApiInlineTools.isNotEmpty()) {
      InlineScriptToolServerSynthesizer.synthesize(
        tools = nodeApiInlineTools,
        outputDir = File(sessionDir, "inline-script-tools"),
      )
    } else {
      emptyList()
    }
    val launchableCount = mcpServers.count { it.script != null }
    val subprocessRuntime = if (launchableCount > 0) {
      onProgressMessage("Launching $launchableCount subprocess MCP server(s)...")
      try {
        McpSubprocessRuntimeLauncher.launchAll(
          mcpServers = mcpServers,
          deviceInfo = deviceInfo,
          config = config,
          sessionId = sessionId,
          sessionLogDir = sessionDir,
          toolRepo = toolRepo,
          // Null when no HTTP server was registered for this process (unit-tested runner paths).
          baseUrl = JsScriptingCallbackBaseUrl.get(),
        )
      } catch (e: Throwable) {
        Console.log(
          "$logPrefix Rolling back ${inlineRegistrations.size} inline " +
            "scripted-tool registration(s) due to MCP server launch failure: ${e.message}",
        )
        for (reg in inlineRegistrations) {
          runCatching { toolRepo.removeDynamicTool(reg.name) }
          runCatching { reg.dispose() }
        }
        throw e
      }
    } else {
      null
    }

    // If neither path produced anything actionable, no cleanup needed.
    if (inlineRegistrations.isEmpty() && subprocessRuntime == null) return null

    return LaunchedScriptingRuntime(
      subprocessRuntime = subprocessRuntime,
      inlineRegistrations = inlineRegistrations,
      toolRepo = toolRepo,
    )
  }

  /**
   * Resolve a target-declared scripted tool's `.ts`/`.js` source to an existing file, independent
   * of the JVM working directory.
   *
   * The bundled `targets/<id>.yaml` rewrites each `script:` to be relative to the repo root (so the
   * daemon, launched from the repo root via `./trailblaze`, resolves it against its CWD). The JUnit
   * host test rule, however, runs with CWD = the Gradle module dir, so a bare `File(script)` would
   * resolve to `<module>/<repo-root-relative-path>` and miss. Walk up from CWD to the first ancestor
   * where the repo-root-relative path resolves — that ancestor IS the repo root. Falls back to the
   * direct `File(script)` (absolute paths, or the not-found case so the bundler throws its clear
   * "Scripted-tool source not found" error with the resolved absolute path).
   */
  private fun resolveScriptFile(script: String): File {
    val direct = File(script)
    if (direct.isAbsolute || direct.isFile) return direct
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, script)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    return direct
  }
}
