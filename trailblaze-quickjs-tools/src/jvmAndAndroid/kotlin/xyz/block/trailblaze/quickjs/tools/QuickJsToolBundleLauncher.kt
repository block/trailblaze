package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * YAML-descriptor-derived advertisement metadata for a scripted tool, supplied to
 * [QuickJsToolBundleLauncher.launchAll] via `advertisementOverrides`.
 *
 * Typed scripted tools bundle through a synthesized wrapper that registers a handler-only entry on
 * `globalThis.__trailblazeTools` (no `spec`) — the bundle is the lean dispatch surface; the tool's
 * description / inputSchema / `_meta` live in its YAML descriptor. Without an override the launcher
 * would advertise such a tool to the LLM with an empty descriptor and skip `_meta` gating. The
 * on-device caller ([xyz.block.trailblaze.android.AndroidTrailblazeRule]) builds one of these from
 * the discovered YAML descriptor so advertisement + gating match the daemon/host path (which
 * already sources both from YAML via `InProcessScriptedToolLauncher`).
 *
 * @param descriptor the LLM-facing descriptor (`buildScriptedToolDescriptor(config)`).
 * @param meta the `_meta` registration gate (`QuickJsToolMeta` from the descriptor's `_meta`).
 */
data class QuickJsToolAdvertisement(
  val descriptor: TrailblazeToolDescriptor,
  val meta: QuickJsToolMeta,
) {
  companion object {
    /**
     * Build the advertisement (LLM-facing descriptor + on-device `_meta` registration gate) for a
     * catalog scripted tool's [config]. The ONE place a YAML scripted-tool config becomes "what the
     * LLM sees + how it's gated on-device", so the on-device launch path
     * (`AndroidTrailblazeRule.launchToolsetScriptedToolBundles`) can't drift from the descriptor the
     * host path derives via the same `buildScriptedToolDescriptor`. `config.meta` already carries the
     * folded top-level `supportedPlatforms` / `requiresHost` shortcuts as `trailblaze/`-prefixed keys,
     * which is exactly what [QuickJsToolMeta.fromSpec] reads.
     */
    fun fromInlineScriptToolConfig(config: InlineScriptToolConfig): QuickJsToolAdvertisement =
      QuickJsToolAdvertisement(
        descriptor = LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config),
        meta = QuickJsToolMeta.fromSpec(buildJsonObject { config.meta?.let { put("_meta", it) } }),
      )
  }
}

/**
 * The handle [QuickJsToolBundleLauncher.launchAll] returns. Holds the live tool hosts +
 * the tool names they registered so [shutdownAll] can tear everything down cleanly at
 * session end. Mirror of `LaunchedBundleRuntime` in the legacy MCP-shaped runtime, minus
 * the MCP framing.
 *
 * The runtime carries the launch's [sessionId] so failure logs can be grep-correlated
 * with the corresponding `[QuickJsToolBundleLauncher] REGISTERED session=…` line — both
 * surfaces are silent today on the device farm without explicit session tagging.
 */
class LaunchedQuickJsToolRuntime internal constructor(
  val hosts: List<QuickJsToolHost>,
  private val repo: TrailblazeToolRepo,
  private val registeredNames: List<ToolName>,
  private val sessionId: SessionId,
  /**
   * Bundle filenames keyed by the same index as [hosts]. Stored alongside the host list
   * (rather than mutating `QuickJsToolHost`, which is owned by an earlier module) so the
   * `SHUTDOWN_FAILED kind=host` log can name *which* bundle's host leaked.
   * `QuickJsToolHost.toString()` is `QuickJsToolHost@<hash>` — useless for correlation on
   * a device-farm log. Internal — only the shutdown log line consumes this.
   */
  private val bundleFilenames: List<String>,
) {
  /**
   * Remove every tool this launch registered from [repo], then shut down every host.
   * Best-effort — a failure in one shutdown doesn't block the rest. Per-step failures
   * are logged with the launch's [sessionId] (and, for host shutdowns, the bundle's
   * filename) so a leaked QuickJS native allocation or a stranded dynamic-tool
   * registration is observable from logs alone. Without these logs, a leaked engine
   * is invisible unless a developer enables JVM-level instrumentation that device-farm
   * runs don't ship by default.
   */
  suspend fun shutdownAll() {
    for (name in registeredNames) {
      runCatching { repo.removeDynamicTool(name) }
        .onFailure { e ->
          Console.log(
            "[LaunchedQuickJsToolRuntime] SHUTDOWN_FAILED kind=tool name=${name.toolName} " +
              "session=${sessionId.value} reason=${e.message}",
          )
        }
    }
    hosts.forEachIndexed { index, host ->
      runCatching { host.shutdown() }
        .onFailure { e ->
          val bundle = bundleFilenames.getOrNull(index) ?: "(unknown-bundle)"
          Console.log(
            "[LaunchedQuickJsToolRuntime] SHUTDOWN_FAILED kind=host bundle=$bundle " +
              "session=${sessionId.value} reason=${e.message}",
          )
        }
    }
  }
}

/**
 * Stands up every declared QuickJS tool bundle at session start, registers their
 * advertised tools into [TrailblazeToolRepo], and returns a handle the caller closes at
 * session end.
 *
 * Direct counterpart to `McpBundleRuntimeLauncher.launchAll(...)` for the MCP-free runtime
 * track. The two launchers share a target-config shape ([McpServerConfig.script] points at
 * a `.js` bundle) but the dispatch is different: this launcher hands each tool to a
 * [QuickJsToolHost] and registers a [QuickJsToolRegistration] that calls
 * [QuickJsToolHost.callTool] directly — no MCP `tools/list` round-trip, no transport.
 *
 * `AndroidTrailblazeRule` calls this for you when `quickjsToolBundles` is non-empty.
 *
 * Fail-fast: if any bundle fails during startup, every host that already started is shut
 * down and the exception propagates. `command:` entries aren't bundleable (consistent with
 * the legacy launcher) and are logged + skipped.
 */
object QuickJsToolBundleLauncher {

  /**
   * @param bundles target-declared bundles. Same `script:` convention as
   *   [McpBundleRuntimeLauncher][xyz.block.trailblaze.scripting.bundle.McpBundleRuntimeLauncher]
   *   so a target can list either kind in its YAML and pick the runtime.
   * @param preferHostAgent whether the calling session has a host agent in scope. Threads
   *   into [QuickJsToolMeta.shouldRegister] so tools tagged
   *   `_meta["trailblaze/requiresHost"] = true` register only when a host agent is
   *   available. On-device sessions (the [AndroidTrailblazeRule.quickjsToolBundles] path)
   *   pass `false` so host-only tools drop at registration; host CLIs / desktop daemons
   *   that resolve bundles from the local filesystem pass `true` to surface those tools.
   *   Default `false` matches the safer on-device behavior — explicit opt-in for host
   *   sessions keeps a misconfigured caller from accidentally exposing host-only tools.
   * @param bundleSourceResolver turns a `script:` path into loadable bytes. Default reads
   *   from the local filesystem. On-device, pass a resolver that returns an
   *   [AndroidAssetBundleSource][AndroidAssetBundleSource] (or wraps the path however the
   *   caller wants).
   *
   * Each bundle gets one [SessionScopedHostBinding] installed into its host, so
   * `trailblaze.call(...)` from inside a handler dispatches through [toolRepo] with the
   * session's execution context (the framework layer — distinct from a plain local function
   * call, which never leaves the engine). Because the framework gives each tool its own
   * bundle/engine, tool-to-tool composition is normally cross-engine and chains to any depth.
   * Composing a tool from the *same* bundle is refused (it would deadlock the host's shared
   * evalMutex): for same-file logic reuse call a plain local function; for a genuinely
   * separate tool put it in its own bundle; a host/driver tool (e.g. `maestro`) also works
   * since it doesn't re-enter QuickJS.
   */
  suspend fun launchAll(
    bundles: List<McpServerConfig>,
    deviceInfo: TrailblazeDeviceInfo,
    sessionId: SessionId,
    toolRepo: TrailblazeToolRepo,
    preferHostAgent: Boolean = false,
    bundleSourceResolver: (McpServerConfig) -> BundleSource = ::defaultBundleSourceResolver,
    /**
     * Per-tool advertisement overrides keyed by tool name. When a bundle registers a tool whose
     * name is present here, the override's descriptor + `_meta` gate are used instead of the
     * bundle's own (handler-only typed-tool wrappers carry no `spec`, so without this the LLM
     * would see an empty descriptor and `_meta` filters wouldn't run). Empty (the default)
     * preserves the bundle-sourced behavior for callers whose bundles populate their own `spec`.
     */
    advertisementOverrides: Map<ToolName, QuickJsToolAdvertisement> = emptyMap(),
    /**
     * Optional engine extension installed into each launched bundle's QuickJS engine BEFORE the
     * bundle evaluates (e.g. an OkHttp-backed `fetch`; see [QuickJsEngineExtension]). Default
     * `null` preserves the current on-device behavior — the on-device launchers don't bind
     * `fetch`. A host (or an on-device caller that opts in) passes one to make it available.
     */
    engineExtension: QuickJsEngineExtension? = null,
  ): LaunchedQuickJsToolRuntime {
    val bundleable = bundles.filter { it.isBundleable }
    if (bundleable.isEmpty()) {
      return LaunchedQuickJsToolRuntime(
        hosts = emptyList(),
        repo = toolRepo,
        registeredNames = emptyList(),
        sessionId = sessionId,
        bundleFilenames = emptyList(),
      )
    }
    val skipped = bundles.size - bundleable.size
    if (skipped > 0) {
      // `command:` entries are host-only by definition. On-device can't run arbitrary
      // executables. Log so authors notice — but don't fail: a target may legitimately
      // declare a `command:` entry alongside bundleable `script:` entries.
      Console.log(
        "QuickJS bundle runtime: skipping $skipped non-bundleable mcp_servers entries " +
          "(command: entries are host-only).",
      )
    }

    val started = mutableListOf<QuickJsToolHost>()
    val startedFilenames = mutableListOf<String>()
    val pendingRegistrations = mutableListOf<QuickJsToolRegistration>()

    try {
      bundleable.forEachIndexed { index, entry ->
        val source = bundleSourceResolver(entry)
        // Per-bundle progress log so a tester watching device-farm logs can see which
        // bundle is loading when session startup stalls. Without this, a slow
        // `QuickJsToolHost.connect(...)` is silent until the post-success REGISTERED
        // line fires, masking which bundle is the culprit.
        Console.log(
          "[QuickJsToolBundleLauncher] LOADING session=${sessionId.value} " +
            "bundle=${index + 1}/${bundleable.size} filename=${source.filename}",
        )
        // One live binding per bundle: installed into the host so a handler's
        // `trailblaze.call(...)` dispatches through [toolRepo], and forwarded to each
        // [QuickJsToolRegistration] so the tool sets [SessionScopedHostBinding.activeContext]
        // around its evaluation (the only mechanism that survives the asyncFunction callback's
        // thread hop — see [QuickJsTrailblazeTool.binding]).
        val binding = SessionScopedHostBinding(toolRepo, sessionId)
        val host = QuickJsToolHost.connect(
          bundleJs = source.read(),
          bundleFilename = source.filename,
          hostBinding = binding,
          engineExtension = engineExtension,
        )
        // Give the binding its own host so it can refuse a same-bundle compose (which would
        // deadlock the shared evalMutex) instead of hanging the session.
        binding.ownHost = host
        started += host
        startedFilenames += source.filename

        val registered = host.listTools()
        for (spec in registered) {
          // Prefer the YAML-descriptor-derived advertisement (description / inputSchema / _meta)
          // when supplied — a handler-only typed-tool wrapper carries no `spec`, so the bundle's
          // own descriptor + meta would be empty. Falls back to the bundle's `spec` for tools with
          // no override (e.g. a hand-written `pure.js` that populates `spec`).
          val override = advertisementOverrides[ToolName(spec.name)]
          val meta = override?.meta ?: QuickJsToolMeta.fromSpec(spec.spec)
          if (!meta.shouldRegister(driver = deviceInfo.trailblazeDriverType, preferHostAgent = preferHostAgent)) {
            // requiresHost / driver / platform mismatch — drop at registration so the LLM
            // never sees a tool it can't actually run in this session. The legacy
            // MCP-shaped runtime hard-codes preferHostAgent=false because
            // `:trailblaze-scripting-bundle` is only consumed on-device today; this
            // launcher takes the parameter explicitly so a host-side caller (desktop CLI,
            // future host runner) can opt into host-only tool registration.
            continue
          }
          pendingRegistrations += QuickJsToolRegistration(
            host = host,
            spec = spec,
            binding = binding,
            descriptorOverride = override?.descriptor,
            surfaceToLlm = meta.surfaceToLlm,
            isRecordable = meta.isRecordable,
          )
        }
      }
      // Atomic batch: addDynamicTools validates collisions across the whole batch before
      // inserting anything. A collision (same name advertised twice) surfaces as a single
      // startup failure instead of a partial registration. Same shape as the legacy launcher.
      toolRepo.addDynamicTools(pendingRegistrations)
      // Per-launch registration log: tagged with sessionId so a tester grep-ing the device-farm
      // log by session can see exactly which QuickJS-runtime tools landed in the repo. The
      // legacy `BundleTrailblazeTool.execute` emits a similar `REGISTERED ...` line per tool
      // call — this gives the new path equivalent observability at session start.
      val registeredNames = pendingRegistrations.map { it.name.toolName }
      Console.log(
        "[QuickJsToolBundleLauncher] REGISTERED session=${sessionId.value} " +
          "tools=$registeredNames hosts=${started.size}",
      )
      return LaunchedQuickJsToolRuntime(
        hosts = started.toList(),
        repo = toolRepo,
        registeredNames = pendingRegistrations.map { it.name },
        sessionId = sessionId,
        bundleFilenames = startedFilenames.toList(),
      )
    } catch (t: Throwable) {
      for (host in started) {
        runCatching { host.shutdown() }
      }
      throw t
    }
  }

  /**
   * Default `script:` → [BundleSource] translator. Treats the path as a local filesystem
   * path; on-device launches override this with an [AndroidAssetBundleSource] resolver.
   */
  private fun defaultBundleSourceResolver(entry: McpServerConfig): BundleSource {
    val scriptPath = requireNotNull(entry.script) {
      "QuickJS tool bundle entry is missing `script:` — `command:` entries aren't " +
        "bundleable and must be filtered out before reaching the launcher."
    }
    return BundleSource.FromFile(scriptPath)
  }
}
