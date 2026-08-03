package xyz.block.trailblaze.scripting

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl
import xyz.block.trailblaze.scripting.fetch.OkHttpFetchExtension
import xyz.block.trailblaze.scripting.subprocess.InlineScriptToolServerSynthesizer
import xyz.block.trailblaze.scripting.subprocess.McpSubprocessRuntimeLauncher
import xyz.block.trailblaze.scripting.subprocess.SubprocessToolRegistrar
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Launches + registers a host session's scripted tools into its [TrailblazeToolRepo], so they are
 * dispatchable by name (LLM-driven `tap`-style steps, recorded-replay re-execution, AND Kotlin
 * composition via `invokeFrameworkTool` / `ctx.tools.<name>`).
 *
 * Extracted from `TrailblazeHostYamlRunner` so the **two** in-process hosts of the agent
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
 *  - **target-declared** (`target.tools:`) IN-PROCESS tools are routed by whether esbuild is present
 *    and, when it is, whether the in-process SDK source is on disk (see [planInlineToolRoute]):
 *      - esbuild + SDK source (a real source checkout): EVERY tool is live-bundled fresh from its
 *        `.ts` via [DaemonScriptedToolBundler] — a staged classpath `<name>.bundle.js` is never
 *        consulted, and a bundle failure propagates, so a developer's edit is never shadowed and a
 *        broken edit surfaces its esbuild error.
 *      - esbuild without the SDK source (a binary user running their own tools, `@trailblaze/scripting`
 *        resolving from their `node_modules`): each tool is live-bundled, falling back per-tool to a
 *        precompiled classpath bundle when the live-bundle fails and one exists.
 *      - no esbuild (an installed uber-JAR daemon): each tool is served from its precompiled classpath
 *        bundle. In both of the latter two routes a tool with no bundle is loudly skipped ([#2749]) and
 *        the skip stays local to that tool, so its siblings still register.
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
   *   spawned subprocess scripted tools, and the TypeScript launch step a mobile target composes is
   *   in-process, so `false` covers it. (A target's `target.tools:` list is not itself
   *   platform-scoped — `getInlineScriptTools()` returns every entry — but the session gate below
   *   now drops the ones that don't apply before any fork happens.)
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

    // Host daemon opts in to a real `fetch` for in-process scripted tools (replaces shelling curl
    // via `ctx.tools.exec`). Unrestricted by default — same reach as the `curl` it replaces; a
    // deployment that wants to constrain it passes a FetchHostAllowlist. One shared instance per
    // launch (OkHttp pools connections). On-device leaves this null; the engine module never sees
    // OkHttp.
    val fetchExtension = OkHttpFetchExtension()

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
      // Filter out tools an earlier pass already registered (idempotency) up front, so neither the
      // precompiled-bundle lookup nor the esbuild resolution runs for a tool already present.
      val notPreRegistered = quickJsInlineTools.filter { ToolName(it.name) !in preRegistered }
      // Resolve the (tool -> bundle) work and create the registrations under ONE rollback guard:
      // the bundle resolution ([resolveInlineToolBundlesToRegister]) runs esbuild / classpath
      // extraction (disk I/O), so keeping it inside the guard means a full/read-only tmpdir or a
      // bundler failure disposes the QuickJS engines already created and degrades to a clean
      // launch-abort, instead of escaping unguarded. A collision at [TrailblazeToolRepo.addDynamicTools]
      // (the commit) rolls back the same way.
      registerWithRollback<Pair<InlineScriptToolConfig, File>, LazyYamlScriptedToolRegistration>(
        produce = { resolveInlineToolBundlesToRegister(notPreRegistered, onProgressMessage) },
        create = { (config, bundleFile) ->
          LazyYamlScriptedToolRegistration.create(
            toolConfig = config,
            bundlePath = bundleFile,
            toolRepo = toolRepo,
            sessionId = sessionId,
            engineExtension = fetchExtension,
          )
        },
        commit = { toolRepo.addDynamicTools(it) },
        dispose = { it.dispose() },
        onRollback = { accumulated, e ->
          Console.log(
            "$logPrefix Rolling back ${accumulated.size} inline " +
              "scripted-tool registration(s) due to startup failure: ${e.message}",
          )
        },
      )
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
      engineExtension = fetchExtension,
    )
    val inlineRegistrations = targetInlineRegistrations + toolsetRegistrations

    // 2. MCP subprocesses: synthesized wrappers for inline scripted tools whose effective runtime is
    // SUBPROCESS (explicit `runtime: subprocess`). If subprocess launch throws after the QuickJS-path
    // inline registrations succeeded, the inline regs are stranded in the toolRepo with no cleanup
    // handle — catch + dispose them before rethrowing.
    //
    // Gated on the session's driver/platform FIRST: a tool this session would discard at
    // `tools/list` must not cost a fork and a `script:` resolution to discover that. See
    // [SubprocessToolRegistrar.applicableInlineTools].
    val spawnableInlineTools = if (includeSubprocess) {
      SubprocessToolRegistrar.applicableInlineTools(
        tools = nodeApiInlineTools,
        driver = deviceInfo.trailblazeDriverType,
        preferHostAgent = config.preferHostAgent,
        logPrefix = logPrefix,
      )
    } else {
      emptyList()
    }
    val mcpServers = if (spawnableInlineTools.isNotEmpty()) {
      InlineScriptToolServerSynthesizer.synthesize(
        tools = spawnableInlineTools,
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
   * Resolution order:
   *  1. A path that names a real file — absolute, or relative and resolving against CWD — wins
   *     outright. Note this is deliberately "is a file", NOT "is absolute": an absolute path to a
   *     file that does not exist keeps going, because being absolute says nothing about whether the
   *     source is there. `TrailblazeProjectConfigLoader` absolutizes `script:` against the
   *     descriptor's directory for any trailmap discovered on the **filesystem**, so a trailmap
   *     whose descriptors are on disk but whose `.ts` sources ship in the JAR arrives here as an
   *     absolute path to nothing. Returning it unexamined skipped every fallback below and handed
   *     the bundler a missing file — surfacing as `Scripted-tool source not found: <workspace>/…`
   *     even though the source was on the classpath the whole time.
   *  2. **CWD walk-up** (source / gradle mode): the bundled `targets/<id>.yaml` rewrites each
   *     `script:` to be relative to the repo root (so the daemon, launched from the repo root via
   *     `./trailblaze`, resolves it against its CWD). The JUnit host test rule runs with CWD = the
   *     Gradle module dir, so a bare `File(script)` would miss — walk up from CWD to the first
   *     ancestor where the repo-root-relative path resolves. That ancestor IS the repo root.
   *  3. **Classpath-resource fallback** (JAR / installed-CLI mode): the daemon runs from the
   *     installed uber JAR in a workspace repo root that does NOT contain the framework module's
   *     `.ts` sources on disk (a bundled trailmap's `.ts` sources live in that framework module's
   *     resources, not in the workspace), so the repo-root-relative `script:` the walk-up in (2)
   *     resolves against never resolves to a real file there. But the JAR bundles those `.ts`
   *     sources as classpath resources under `trails/config/trailmaps/<id>/tools/…/<name>.ts`.
   *     Recover the source from the classpath and extract it — together with the trailmap's whole
   *     `tools/` tree (recursively, subdirectories preserved), so esbuild/bun relative-import
   *     resolution still finds imported modules — into a temp dir, returning the extracted path (at
   *     its nested path when the script lives in a subdirectory). See [resolveFromClasspath].
   *  4. Fall back to the direct `File(script)` (the not-found case) so the bundler throws its clear
   *     "Scripted-tool source not found" error with the resolved absolute path.
   */
  internal fun resolveScriptFile(
    script: String,
    loadClasspathResource: (String) -> String? = { path ->
      ClasspathResourceDiscovery.loadResource(path)
    },
    listClasspathToolScripts: (String) -> Set<String> = { toolsRoot ->
      ClasspathResourceDiscovery.discoverFilenamesRecursive(toolsRoot, ".ts")
    },
    classpathExtractRoot: File = File(
      System.getProperty("java.io.tmpdir"),
      CLASSPATH_EXTRACT_SUBDIR,
    ),
  ): File {
    val direct = File(script)
    if (direct.isFile) return direct
    // An absolute path that isn't a file can still be recovered from the classpath below (the
    // anchor segment survives absolutization), but it can't be joined onto CWD ancestors — that
    // would just re-derive the same path.
    if (!direct.isAbsolute) {
      var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
      while (dir != null) {
        val candidate = File(dir, script)
        if (candidate.isFile) return candidate
        dir = dir.parentFile
      }
    }
    resolveFromClasspath(script, loadClasspathResource, listClasspathToolScripts, classpathExtractRoot)
      ?.let { return it }
    return direct
  }

  /**
   * Classpath-resource fallback for [resolveScriptFile], used in JAR / installed-CLI mode where the
   * `.ts` source isn't on the workspace filesystem but IS bundled in the uber JAR under classpath
   * path `trails/config/trailmaps/<id>/tools/…/<name>.ts`. The `script:` field the bundler receives
   * may carry a longer repo-relative prefix (a framework module's resource path ending in that
   * segment) AND may point at a script nested in a subdirectory (`tools/internal/…`,
   * `tools/android/…`, `tools/host/…`); we anchor on the `<id>/tools` root to derive the canonical
   * classpath path regardless of the prefix or nesting depth.
   *
   * The whole `tools/` tree is extracted (recursively, preserving subdirectory structure) rather
   * than just the one script, so a tool that imports another module — a sibling (`./shared.ts`), a
   * nested helper (`./subdir/…`), or one up a level — resolves under esbuild/bun, which walk the
   * entry's directory for relative imports and need those modules present on disk. Extraction is
   * keyed by trailmap id under [extractRoot], so repeated calls for tools in the same trailmap reuse
   * the dir. Returns null when the script path has no recognizable `<id>/tools` anchor or the
   * requested resource isn't on the classpath (the caller then falls back to its clear not-found
   * error).
   */
  private fun resolveFromClasspath(
    script: String,
    loadClasspathResource: (String) -> String?,
    listClasspathToolScripts: (String) -> Set<String>,
    extractRoot: File,
  ): File? {
    val normalized = script.replace(File.separatorChar, '/')
    val anchorIdx = normalized.indexOf("$TRAILMAPS_CLASSPATH_ANCHOR/")
    if (anchorIdx < 0) return null
    // classpathPath = trails/config/trailmaps/<id>/tools/<subdirs…>/<name>.ts
    val classpathPath = normalized.substring(anchorIdx)
    // Segments after the anchor: <id>/tools/<subdirs…>/<name>.ts. Require <id>/tools/<file> at
    // minimum; the script may sit any number of subdirectories deep under `tools`.
    val segments = classpathPath.removePrefix("$TRAILMAPS_CLASSPATH_ANCHOR/").split('/')
    if (segments.size < 3 || segments[1] != SCRIPTED_TOOLS_DIR) return null
    val trailmapId = segments[0]
    val toolsRoot = "$TRAILMAPS_CLASSPATH_ANCHOR/$trailmapId/$SCRIPTED_TOOLS_DIR"
    // The requested script's path relative to the tools root — preserves any subdirectories.
    val requestedRelPath = classpathPath.removePrefix("$toolsRoot/")
    // The requested script must itself be present on the classpath — otherwise this isn't a
    // classpath-bundled trailmap tool, so return null and let the caller's not-found error fire.
    if (loadClasspathResource(classpathPath) == null) return null

    // trailmap id keys the extraction dir so tools from different trailmaps don't collide, and
    // repeated calls within one trailmap reuse it.
    val extractDir = File(extractRoot, "$trailmapId/$SCRIPTED_TOOLS_DIR")
    // Extract every `.ts` under the tools root, PRESERVING subdirectory structure, so nested
    // scripts (tools/<subdir>/x.ts) resolve AND their relative imports (siblings, ./subdir/…, ../)
    // find their targets on disk for esbuild/bun.
    for (relPath in listClasspathToolScripts(toolsRoot) + requestedRelPath) {
      val content = loadClasspathResource("$toolsRoot/$relPath") ?: continue
      val out = File(extractDir, relPath)
      out.parentFile?.mkdirs() // nested names (e.g. host/foo.ts) need their parent dir created first
      // Overwrite unconditionally: content is immutable for a given JAR, so a re-extract is idempotent.
      out.writeText(content)
    }
    return File(extractDir, requestedRelPath).takeIf { it.isFile }
  }

  /** A target-declared inline tool paired with the `.bundle.js` to register for it — from a fresh
   * live-bundle or an extracted precompiled classpath bundle. */
  internal data class ResolvedInlineTool(
    val config: InlineScriptToolConfig,
    val bundleFile: File,
  )

  /**
   * A target's inline QuickJS tools split by whether a `.bundle.js` could be [resolved] for each
   * (from a fresh live-bundle or a precompiled classpath bundle) versus those [unresolved] — which,
   * with no bundle available, are loudly skipped ([#2749]) rather than registered.
   */
  internal data class InlineToolBundlePlan(
    val resolved: List<ResolvedInlineTool>,
    val unresolved: List<InlineScriptToolConfig>,
  )

  /**
   * The route for a target's inline QuickJS tools, chosen by [planInlineToolRoute] on whether esbuild
   * is present (can we live-bundle at all) and, when it is, whether the in-process SDK source is on
   * disk (a real Trailblaze checkout vs a binary user with their own esbuild).
   */
  internal sealed interface InlineToolRoute {
    /**
     * esbuild is present, so live-bundle every [tools] entry fresh from its `.ts` — a source
     * checkout's edit is always used, never shadowed by a staged classpath bundle.
     *
     * [allowPrecompiledFallback] splits the two live cases. With the in-process SDK source on disk (a
     * real Trailblaze checkout) it is FALSE: a bundle failure propagates so a broken live edit
     * surfaces its esbuild error. Without the SDK source (a binary user with a global esbuild running
     * their own `target.tools:`) it is TRUE: `@trailblaze/scripting` resolves from the user's
     * `node_modules` when present (live-bundle succeeds), and a tool whose live-bundle fails — SDK
     * unresolvable, no source and no `node_modules` — falls back to its precompiled classpath bundle
     * instead of tanking the session.
     */
    data class LiveBundle(
      val tools: List<InlineScriptToolConfig>,
      val allowPrecompiledFallback: Boolean,
    ) : InlineToolRoute

    /**
     * esbuild is absent (an installed uber-JAR daemon): serve each tool from its precompiled
     * classpath bundle; tools with none are loudly skipped.
     */
    data class PrecompiledOnly(val plan: InlineToolBundlePlan) : InlineToolRoute
  }

  /**
   * Choose the inline-tool route from whether esbuild is present and, if so, whether the in-process
   * SDK source is on disk. esbuild + SDK source → [InlineToolRoute.LiveBundle] with no precompiled
   * fallback (a real checkout: live edits win, failures surface). esbuild without the SDK source →
   * [InlineToolRoute.LiveBundle] WITH precompiled fallback (a binary user: live-bundle via their
   * `node_modules`, else fall back to the shipped bundle). esbuild absent →
   * [InlineToolRoute.PrecompiledOnly]. Pure over the injected [resolvePrecompiled]; the precompiled
   * plan is only built (disk I/O) on the esbuild-absent route, which is why the caller invokes this
   * inside the launch rollback guard.
   */
  internal fun planInlineToolRoute(
    tools: List<InlineScriptToolConfig>,
    esbuildPresent: Boolean,
    sdkSourcePresent: Boolean,
    resolvePrecompiled: (InlineScriptToolConfig) -> File? = { resolvePrecompiledBundle(it.script) },
  ): InlineToolRoute =
    if (esbuildPresent) {
      InlineToolRoute.LiveBundle(tools, allowPrecompiledFallback = !sdkSourcePresent)
    } else {
      InlineToolRoute.PrecompiledOnly(planInlineToolBundles(tools, resolvePrecompiled))
    }

  /**
   * Split [tools] into those a `.bundle.js` could be [resolved][InlineToolBundlePlan.resolved] for and
   * those [unresolved][InlineToolBundlePlan.unresolved] (the loud-skip set), via [resolveBundle] —
   * order-preserving. [resolveBundle] is the per-route resolver: on the esbuild-absent route the
   * classpath lookup; on the live-with-fallback route a synchronous lookup composing a precomputed
   * live-bundle result with the classpath fallback. Pure over the injected resolver so the split is
   * unit-testable without esbuild or a QuickJS engine.
   */
  internal fun planInlineToolBundles(
    tools: List<InlineScriptToolConfig>,
    resolveBundle: (InlineScriptToolConfig) -> File?,
  ): InlineToolBundlePlan {
    val resolved = mutableListOf<ResolvedInlineTool>()
    val unresolved = mutableListOf<InlineScriptToolConfig>()
    for (tool in tools) {
      val bundle = resolveBundle(tool)
      if (bundle != null) resolved += ResolvedInlineTool(tool, bundle) else unresolved += tool
    }
    return InlineToolBundlePlan(resolved, unresolved)
  }

  /**
   * Resolve the (tool -> `.bundle.js`) pairs to register for a target's inline QuickJS [tools],
   * following the [planInlineToolRoute] gate. Runs esbuild / classpath extraction (disk I/O), so the
   * caller invokes it inside the launch rollback guard ([registerWithRollback]).
   */
  private suspend fun resolveInlineToolBundlesToRegister(
    tools: List<InlineScriptToolConfig>,
    onProgressMessage: (String) -> Unit,
  ): List<Pair<InlineScriptToolConfig, File>> {
    val esbuildBinary = LazyYamlScriptedToolRegistration.resolveEsbuildBinary()
    // The in-process SDK source the bundler aliases `@trailblaze/scripting` to. Resolved
    // independently of the esbuild binary's location; when absent the bundler omits the alias and
    // `@trailblaze/scripting` resolves from `node_modules` instead. Its presence also distinguishes a
    // real Trailblaze checkout from a binary user running their own tools (see [planInlineToolRoute]).
    val inProcessSdkEntry = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry()
    return when (
      val route =
        planInlineToolRoute(
          tools,
          esbuildPresent = esbuildBinary != null,
          sdkSourcePresent = inProcessSdkEntry != null,
        )
    ) {
      is InlineToolRoute.LiveBundle -> {
        // Route selected because esbuild resolved, so it is non-null here by construction.
        val binary = checkNotNull(esbuildBinary) { "LiveBundle route requires a resolved esbuild binary" }
        // Supplying the SDK entry (may be null) keeps the slim on-device profile when present and lets
        // the bundler fall back to `node_modules` resolution when absent.
        val bundler = DaemonScriptedToolBundler(binary, inProcessSdkEntryOverride = inProcessSdkEntry)
        // Static-analysis pre-pass (#3190): a tool whose import closure reaches `node:*` builtins or
        // Node-only npm deps would fail the real bundle pass and tank session start for every sibling
        // tool. The analyzer skips such tools cleanly and registers the on-device-viable siblings.
        val analyzer = ScriptedToolImportAnalyzer(binary)
        // Resolve each tool's source ONCE, here, and hand the same file to both the analyzer and
        // the bundler. A bundled trailmap's `.ts` lives in the JAR, not at its workspace-relative
        // path, so the raw path would leave the analyzer reading nothing while the bundler reads
        // the real extracted source — the two silently disagreeing about the same tool.
        val sources = route.tools.associateWith { resolveScriptFile(it.script) }
        val partition = partitionByImportClosure(route.tools, analyzer, resolveSource = { sources.getValue(it) })
        if (!route.allowPrecompiledFallback) {
          // Real SDK-source checkout: live-bundle every tool; a bundle failure PROPAGATES (rolls the
          // registration back) so a broken live edit surfaces its esbuild error instead of being
          // silently masked by a staged precompiled bundle.
          partition.toBundle.map { tool ->
            tool to bundler.bundleOne(sources.getValue(tool), tool.name)
          }
        } else {
          // Binary user (esbuild but no SDK source): live-bundle each tool — `@trailblaze/scripting`
          // resolves from the user's node_modules. On a live-bundle failure, fall back to a precompiled
          // classpath bundle when one exists; otherwise skip JUST that tool — never abort its siblings
          // or the session (per-tool degradation, matching the no-esbuild route) — while surfacing the
          // ORIGINAL esbuild error in the skip report so a real author bug (syntax / unresolved import)
          // is diagnosable at startup rather than only later as an "unknown tool".
          val liveBundleErrors = linkedMapOf<InlineScriptToolConfig, Throwable>()
          val liveBundled =
            partition.toBundle.associateWith { tool ->
              try {
                bundler.bundleOne(sources.getValue(tool), tool.name)
              } catch (cancellation: CancellationException) {
                throw cancellation
              } catch (bundleFailure: Throwable) {
                liveBundleErrors[tool] = bundleFailure
                null
              }
            }
          val plan =
            planInlineToolBundles(partition.toBundle) { tool ->
              liveBundled[tool] ?: resolvePrecompiledBundle(tool.script)
            }
          reportUnregisteredInlineTools(plan.unresolved, onProgressMessage, liveBundleErrors)
          // A tool whose live-bundle failed but whose precompiled bundle covered it still REGISTERED;
          // surface its original error as a breadcrumb (not an error — the tool works) so no live-bundle
          // failure is ever fully hidden. From a source checkout this flags an edit that didn't take
          // effect; on an installed JAR it's the expected SDK-absent fallback.
          val unresolvedSet = plan.unresolved.toSet()
          liveBundleErrors.filterKeys { it !in unresolvedSet }.forEach { (tool, failure) ->
            Console.log(
              "[scripted-tools] '${tool.name}' fell back to its precompiled bundle after its live-bundle " +
                "failed: ${failure.message ?: failure.toString()}",
            )
          }
          plan.resolved.map { it.config to it.bundleFile }
        }
      }
      is InlineToolRoute.PrecompiledOnly -> {
        reportUnregisteredInlineTools(route.plan.unresolved, onProgressMessage)
        route.plan.resolved.map { it.config to it.bundleFile }
      }
    }
  }

  /**
   * Loudly report inline scripted tools that will NOT be registered this session — no live-bundle was
   * possible (no esbuild, or esbuild but `@trailblaze/scripting` unresolvable) and no precompiled
   * `.bundle.js` is on the classpath. A silent skip here is what made a fresh daemon ship with
   * launch-critical TS scripted tools unregistered, surfacing hours later as a cryptic "Unknown
   * framework tool" at dispatch time — so this uses `Console.error` (survives CLI quiet mode) and
   * mirrors a short line to [onProgressMessage]. No-op when nothing was skipped.
   */
  private fun reportUnregisteredInlineTools(
    skipped: List<InlineScriptToolConfig>,
    onProgressMessage: (String) -> Unit,
    liveBundleErrors: Map<InlineScriptToolConfig, Throwable> = emptyMap(),
  ) {
    if (skipped.isEmpty()) return
    val names = skipped.joinToString(", ") { it.name }
    Console.error(
      "[#2749] No bundle available for ${skipped.size} inline scripted tool(s) ($names): live-bundling " +
        "was unavailable (no esbuild, or esbuild without `@trailblaze/scripting` resolvable — no SDK " +
        "source and no node_modules) and no precompiled `.bundle.js` is on the classpath. They WILL NOT " +
        "be registered this session, so any trail that composes them (e.g. an app launch step) will " +
        "fail. Recover by using a Trailblaze build whose uber JAR ships these tools' precompiled bundles " +
        "(the build precompiles a target's `target.tools:` at package time), or by running from a source " +
        "checkout with esbuild on PATH and the `@trailblaze/scripting` SDK source present " +
        "(`source bin/activate-hermit` then `bun install` in the TypeScript SDK dir), then restart the " +
        "daemon. Maintainers: ensure the build stages this trailmap's `<name>.bundle.js` into the JAR's " +
        "classpath resources.",
    )
    // For a tool whose live-bundle actually FAILED (esbuild present), surface the original esbuild
    // error so a real author bug (syntax / unresolved import) is diagnosable, not hidden behind the
    // generic message above. Tools skipped for having no toolchain at all carry no such error.
    for (tool in skipped) {
      val failure = liveBundleErrors[tool] ?: continue
      Console.error("[#2749] '${tool.name}' live-bundle failed: ${failure.message ?: failure.toString()}")
    }
    onProgressMessage(
      "Skipping ${skipped.size} inline scripted tool(s) ($names): no live-bundle possible and no " +
        "precompiled bundle on the classpath. Use a build that ships their precompiled bundles, or run " +
        "from a source checkout with esbuild on PATH and the SDK source present.",
    )
  }

  /**
   * Create a registration for each produced item and hand the full set to [commit], disposing every
   * registration already created (best-effort) if [produce], any [create], or [commit] throws — so a
   * failed bundle extraction, a bundler error, or a name collision at commit can't strand a QuickJS
   * engine or escape the guard. [produce] runs inside the guard so its disk / esbuild I/O is covered.
   * Returns the registrations once [commit] has adopted them; [onRollback] is invoked before disposal
   * for logging.
   */
  internal suspend fun <I, R> registerWithRollback(
    produce: suspend () -> List<I>,
    create: suspend (I) -> R,
    commit: suspend (List<R>) -> Unit,
    dispose: suspend (R) -> Unit,
    onRollback: (List<R>, Throwable) -> Unit = { _, _ -> },
  ): List<R> {
    val accumulated = mutableListOf<R>()
    return try {
      for (item in produce()) accumulated += create(item)
      commit(accumulated)
      accumulated
    } catch (e: Throwable) {
      onRollback(accumulated, e)
      for (reg in accumulated) runCatching { dispose(reg) }
      throw e
    }
  }

  /**
   * Resolve a target-declared tool's build-time precompiled `<name>.bundle.js` from the classpath —
   * the installed-JAR counterpart to [resolveScriptFile]'s `.ts` fallback, for the `.bundle.js`
   * sibling of that `.ts`. The build stages each precompiled bundle at
   * `trails/config/trailmaps/<id>/tools/…/<name>.bundle.js` (via
   * [ScriptedToolNameDiscoverer.bundleResourcePathForScript], the same rule the on-device asset
   * launcher uses), so we derive that path from the tool's `script:` and load its raw bytes.
   *
   * Extraction is content-addressed and atomic: the extract path is keyed by the bundle's SHA-256, so
   * a complete file already present is byte-identical and is reused as-is; a fresh extract writes to a
   * unique temp file and [Files.move]s it into place ([StandardCopyOption.ATOMIC_MOVE], degrading to a
   * plain replace where the filesystem can't do an atomic move). Together these mean a concurrent
   * session — including one reading a bundle lazily at dispatch — never observes a half-written file.
   *
   * Returns the extracted file for [LazyYamlScriptedToolRegistration.create]; a blank `script:` (a
   * `target.tools:` config error, distinct from a missing toolchain — logged as such so it isn't
   * misread as the [#2749] "esbuild missing" case) or a bundle not on the classpath returns null so
   * the caller does not crash.
   */
  internal fun resolvePrecompiledBundle(
    script: String,
    loadClasspathResourceBytes: (String) -> ByteArray? = { path ->
      (Thread.currentThread().contextClassLoader?.getResource(path)
        ?: HostScriptedToolLauncher::class.java.classLoader?.getResource(path))
        ?.readBytes()
    },
    extractRoot: File = File(
      System.getProperty("java.io.tmpdir"),
      PRECOMPILED_BUNDLE_EXTRACT_SUBDIR,
    ),
  ): File? {
    if (script.isBlank()) {
      Console.error(
        "[#2749] An inline scripted tool declares a blank `script:` — a `target.tools:` config error, " +
          "NOT a missing toolchain, so no `.bundle.js` can be resolved for it. Fix the tool's " +
          "`script:` in the target's trailmap.",
      )
      return null
    }
    val bundleResourcePath = ScriptedToolNameDiscoverer.bundleResourcePathForScript(script)
    val bytes = loadClasspathResourceBytes(bundleResourcePath) ?: return null
    // bundleResourcePath = trails/config/trailmaps/<id>/tools/…/<name>.bundle.js — keep the trailmap
    // subpath under a SHA-256-of-content dir so distinct bundles never collide and an identical one is
    // shared. A file already present under this hash is complete (writes are atomic-moved in), so reuse
    // it rather than rewriting — which is what makes concurrent extraction safe.
    val relative = bundleResourcePath.removePrefix("$TRAILMAPS_CLASSPATH_ANCHOR/")
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val out = File(extractRoot, "$hash/$relative")
    if (out.isFile) return out
    out.parentFile?.mkdirs()
    val tmp = File.createTempFile("${out.name}.", ".tmp", out.parentFile)
    try {
      tmp.writeBytes(bytes)
      try {
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      tmp.delete()
    }
    return out
  }

  /**
   * Classpath directory prefix under which trailmaps (and their `tools/<name>.ts` sources) are
   * bundled as resources. A tool's `script:` field is anchored on this segment to derive the
   * canonical classpath path regardless of any repo-relative prefix the baked target carries.
   */
  private const val TRAILMAPS_CLASSPATH_ANCHOR = "trails/config/trailmaps"

  /** Trailmap-relative subdirectory that owns scripted-tool `.ts` sources. */
  private const val SCRIPTED_TOOLS_DIR = "tools"

  /** Temp-dir subdirectory under `java.io.tmpdir` for classpath-extracted `.ts` sources. */
  private const val CLASSPATH_EXTRACT_SUBDIR = "trailblaze-classpath-scripted-tools"

  /** Temp-dir subdirectory under `java.io.tmpdir` for classpath-extracted precompiled `.bundle.js`. */
  private const val PRECOMPILED_BUNDLE_EXTRACT_SUBDIR = "trailblaze-classpath-scripted-tool-bundles"
}
