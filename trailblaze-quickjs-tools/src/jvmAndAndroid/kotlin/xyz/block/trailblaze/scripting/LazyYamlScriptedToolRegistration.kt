package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.QuickJsEngineExtension
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.quickjs.tools.QuickJsToolMeta
import xyz.block.trailblaze.quickjs.tools.QuickJsToolSerializer
import xyz.block.trailblaze.quickjs.tools.QuickJsTrailblazeTool
import xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding
import xyz.block.trailblaze.toolcalls.DeclaredSensitiveArgs
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Session-scoped registration for an inline scripted tool declared under a trailmap manifest's
 * `target.tools:` block. Replaces the legacy MCP-subprocess detour ([InlineScriptToolServerSynthesizer])
 * for inline scripted tools — `mcp_servers:` entries (true external MCP servers) keep going
 * through the subprocess path. See #2749 for the full motivation.
 *
 * **Why one [QuickJsToolHost] per registration**: the host's `evalMutex` is non-reentrant. If
 * one scripted tool's body calls `client.callTool("otherScriptedTool", …)` and both shared a
 * single host, the nested call would deadlock on the same mutex. Per-registration hosts mean
 * the nested call hits a *different* host's mutex, so re-entry is safe by construction.
 *
 * **Naming note**: the "Lazy" prefix is from the planning doc and implies on-first-dispatch
 * host construction. In practice the host is constructed eagerly via [create] at session
 * start — the cost is bounded (one QuickJS engine per scripted tool), the synchronization
 * is simpler, and `[QuickJsToolSerializer]` requires the host upfront. The class name is
 * preserved for plan-tracking continuity.
 */
class LazyYamlScriptedToolRegistration private constructor(
  private val toolConfig: InlineScriptToolConfig,
  private val host: QuickJsToolHost,
  private val binding: SessionScopedHostBinding,
) : DynamicTrailblazeToolRegistration {

  override val name: ToolName = ToolName(toolConfig.name)

  override val trailblazeDescriptor: TrailblazeToolDescriptor = buildDescriptor(toolConfig)

  // Read the config's provenance flag rather than inspecting the schema's shape: an
  // analyzer-generated no-arg schema and one synthesized from an author's absent `inputSchema:`
  // are byte-identical (`{type: object, properties: {}}`), and only the former is the tool's
  // complete contract. See [InlineScriptToolConfig.inputSchemaExhaustive].
  override val declaresExhaustiveParameters: Boolean = toolConfig.inputSchemaExhaustive

  // Honor BOTH the typed [InlineScriptToolConfig] field AND the namespaced `_meta` key, combined by
  // opt-out AND (a `false` from either side wins). The typed field is the source of truth for the
  // analyzer / top-level-shortcut authoring paths, but a descriptor can also carry a raw
  // `_meta.trailblaze/surfaceToLlm:false` (or `isRecordable:false`) without the shortcut — and the
  // on-device QuickJS launcher reads that `_meta` (via [QuickJsToolMeta]). Consulting it here keeps
  // the host in-process path consistent with on-device for that authoring shape.
  private val configMeta: QuickJsToolMeta =
    QuickJsToolMeta.fromSpec(buildJsonObject { toolConfig.meta?.let { put("_meta", it) } })
  private val effectiveSurfaceToLlm: Boolean = toolConfig.surfaceToLlm && configMeta.surfaceToLlm
  private val effectiveIsRecordable: Boolean = toolConfig.isRecordable && configMeta.isRecordable

  // Sourced from `_meta` alone (no typed `InlineScriptToolConfig` twin): every authoring surface —
  // the TS typed spec via the analyzer, the `.tool.yaml` top-level shortcut, a hand-authored
  // `_meta:` block — already folds into the namespaced key, so one read covers all three and there
  // is no second source of truth to reconcile.
  private val effectiveSensitiveArgs: DeclaredSensitiveArgs = configMeta.sensitiveArgs

  /**
   * 1:1 with the scripted tool's declared `surfaceToLlm` (`@TrailblazeToolClass.surfaceToLlm`
   * equivalent). When `false`, [TrailblazeToolRepo.advertisedDynamic] drops this registration
   * from the LLM's tool menu while keeping it dispatchable by name (composed by a parent tool)
   * and resolvable for recorded replays.
   */
  override val surfaceToLlm: Boolean get() = effectiveSurfaceToLlm

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: scripted-tool authors can declare `inputSchema` types the
    // LLM-tool descriptor doesn't model directly (`array`, `object`, etc.) — those fall
    // back to String rather than crashing session startup. Mirrors the leniency applied
    // by [BundleToolRegistration.buildKoogTool] for the on-device QuickJS path.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    // Pass the binding to the serializer so the decoded QuickJsTrailblazeTool carries it into
    // execute(). This fixes the LLM dispatch path (koogTool.decodeArgs → QuickJsTrailblazeTool →
    // agent.runTrailblazeTools) where the tool was previously executed without a
    // ContextSettingScriptedTool wrapper and thus never set binding.activeContext — causing the
    // QuickJS asyncFunction callback (Dispatchers.Default) to find null context and return
    // "no execution context installed" for every nested client.callTool() call.
    val serializer =
      QuickJsToolSerializer(name, host, binding, effectiveIsRecordable, effectiveSensitiveArgs)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: QuickJsTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        // args already carries binding (via QuickJsToolSerializer); args.execute(context) will
        // set binding.activeContext internally. The explicit set/clear below is a belt-and-
        // suspenders guard for any future caller that reaches this lambda directly (e.g. koog
        // itself executing the tool rather than going through agent.runTrailblazeTools).
        binding.activeContext = context
        val result = try {
          args.execute(context)
        } finally {
          binding.activeContext = null
        }
        "Executed scripted tool: ${name.toolName} — result: $result"
      },
    )
  }

  override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
    // Thread `isRecordable` onto the decoded QuickJsTrailblazeTool via the serializer so the
    // inner tool carries the recording opt-out on its own `toolMetadata` (the wrapper delegates
    // to it). Keeping the override on the inner tool — rather than on the wrapper — means both
    // dispatch paths agree and matches the on-device path's type-preserving approach.
    val serializer = QuickJsToolSerializer(
      name,
      host,
      isRecordable = effectiveIsRecordable,
      sensitiveArgs = effectiveSensitiveArgs,
    )
    val inner = Json.decodeFromString(serializer, argumentsJson) as QuickJsTrailblazeTool
    // Wrap the deserialized `QuickJsTrailblazeTool` so its outer `execute(...)` sets the
    // binding's active context before delegating to the host dispatch — same rationale
    // as the `executeTool` lambda above. This is the path pre-action dispatch takes
    // (`HostAccessibilityRpcClient.executePreAction(...)` calls `tool.execute(context)`
    // directly, bypassing the koog `executeTool` lambda).
    return ContextSettingScriptedTool(inner = inner, binding = binding)
  }

  /**
   * Wrapper that sets the binding's [SessionScopedHostBinding.activeContext] for the
   * duration of the inner [QuickJsTrailblazeTool]'s execute. Implements
   * [HostLocalExecutableTrailblazeTool] so callers' host-local routing decisions
   * (`HostAccessibilityRpcClient`, `BaseTrailblazeAgent`) recognize the wrapper as
   * host-only the same way they recognize the inner tool — without this, wrapping would
   * hide the host-local marker and the tool would be RPC'd to the device.
   *
   * Also implements [RawArgumentTrailblazeTool][xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool]
   * by delegating to [inner] so the wrapper survives a YAML round-trip WITH its arguments. The
   * daemon's host-driver dispatch (e.g. iOS-host) YAML-encodes the resolved tool and re-runs it via
   * `runYaml`; without exposing `rawToolArguments`, the wrapper hits the serializer's generic
   * `::class.serializer()` fallback (it isn't `@Serializable`), which drops every argument — the tool
   * then re-decodes + executes with empty args (e.g. `openUrl` runs but sees no `url`). Surfacing the
   * inner tool's args makes the serializer take the `RawArgumentTrailblazeTool` branch, which encodes
   * `name: {args}` so the round-trip preserves them.
   *
   * Delegates [toolMetadata] to [inner] so the recording opt-out the decoded [QuickJsTrailblazeTool]
   * carries (threaded via [QuickJsToolSerializer]) flows through the wrapper — `getIsRecordableFromAnnotation`
   * reads the WRAPPER's metadata, since the wrapper is the tool object both LLM dispatch and by-name
   * `invokeFrameworkTool` dispatch log (both resolve through [decodeToolCall]).
   *
   * Implements [SensitiveArgsTrailblazeTool] delegating to [inner] for the same reason: the log-encode
   * boundary resolves the marker off the tool object it is handed, which on this path is the WRAPPER.
   * Without it, a scripted tool's declared credential arg would be masked on the on-device path and
   * leak on the host path — the wrapper surfaces [rawToolArguments] verbatim.
   */
  private class ContextSettingScriptedTool(
    private val inner: QuickJsTrailblazeTool,
    private val binding: SessionScopedHostBinding,
  ) : xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool,
    xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool,
    xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool {
    override val advertisedToolName: String get() = inner.advertisedToolName
    override val rawToolArguments: JsonObject get() = inner.rawToolArguments
    override val sensitiveArgNames: Set<String> get() = inner.sensitiveArgNames
    override val toolMetadata: xyz.block.trailblaze.toolcalls.TrailblazeToolMetadata?
      get() = inner.toolMetadata

    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): xyz.block.trailblaze.toolcalls.TrailblazeToolResult {
      binding.activeContext = toolExecutionContext
      return try {
        inner.execute(toolExecutionContext)
      } finally {
        binding.activeContext = null
      }
    }
  }

  /**
   * Free the underlying QuickJS engine. Idempotent (delegates to [QuickJsToolHost.shutdown],
   * which is itself idempotent). Call from session-end cleanup; otherwise the engine + its
   * retained JS allocations leak for the daemon's lifetime.
   */
  suspend fun dispose() {
    host.shutdown()
  }

  companion object {
    /**
     * Locate an `esbuild` binary for daemon-time scripted-tool bundling. Returns `null`
     * if no binary is found — callers should log a clear warning and skip inline-tool
     * registration rather than failing the whole session, since the daemon is otherwise
     * still functional for non-scripted trails.
     *
     * Resolution order:
     *  1. **`PATH` lookup** — covers Homebrew installs (`brew install esbuild`), `npm i -g`,
     *     and any other globally-installed esbuild. This is the expected path on developer
     *     machines that have run `brew install esbuild`.
     *  2. **Build-tree walk-up** — walks from CWD upward looking for a Trailblaze SDK
     *     `node_modules/.bin/esbuild` at any of the layouts the repo has used:
     *       - `sdks/typescript/node_modules/.bin/esbuild` — the layout where the SDK
     *         lives directly under the repo root (the OSS layout, and Trailblaze's
     *         original layout in this repo).
     *       - `opensource/sdks/typescript/node_modules/.bin/esbuild` — the layout where
     *         the SDK lives under an `opensource/` subdir (the layout some
     *         monorepo-style consumers use).
     *     Mirrors the build-time resolution in `BundleAuthorToolsTask` so a fresh checkout
     *     that ran `bun install` once gets esbuild for free. Walks from the current
     *     working directory up to filesystem root.
     *
     * **CI silent-skip risk.** When neither path resolves, the caller skips inline-tool
     * registration and emits the `[#2749] esbuild binary not found...` breadcrumb (now
     * via `Console.info` so it survives CLI quiet mode — historically `Console.log` got
     * eaten by `--verbose=false` and the only visible failure surfaced was a downstream
     * "Unsupported tool type for RPC execution" at trail-time, with no hint about the
     * upstream bundler skip). The `opensource/sdks/typescript/...` candidate above is
     * what closes the CI gap on monorepo-style consumers: agents not running
     * `brew install esbuild` would otherwise hit only the walk-up, and a walk that only
     * recognized the flat layout would silently miss the `opensource/`-nested esbuild
     * even though `bun install` had populated it.
     *
     * Note: monorepo developers running the daemon from a parent directory of the SDK
     * (rather than from inside it) should put esbuild on `PATH` or invoke the daemon
     * from inside the SDK-containing tree — the walk-up only finds binaries in
     * ancestor directories, not sibling subtrees.
     */
    fun resolveEsbuildBinary(): File? {
      resolveEsbuildOnPath(System.getenv("PATH"))?.let { return it }
      return resolveEsbuildViaWalkup(File(System.getProperty("user.dir") ?: ".").absoluteFile)
    }

    /**
     * PATH-lookup half of [resolveEsbuildBinary], pulled out so a unit test can pin the
     * lookup against an injected `PATH` string. Splitting on the platform-aware path
     * separator and skipping blanks matches the shell's behavior.
     */
    internal fun resolveEsbuildOnPath(pathEnv: String?): File? {
      if (pathEnv == null) return null
      for (dir in pathEnv.split(File.pathSeparator)) {
        if (dir.isBlank()) continue
        val candidate = File(dir, "esbuild")
        if (candidate.exists() && candidate.canExecute()) return candidate
      }
      return null
    }

    /**
     * Walk-up half of [resolveEsbuildBinary], pulled out so a unit test can pin the
     * walk-up against an injected starting directory without depending on the host's
     * actual CWD or repo layout.
     *
     * The candidate-list order is **intentional and load-bearing**: the flat layout
     * (`sdks/typescript/...`) comes first because it's shorter and matches the repo's
     * documented tree shape; the `opensource/`-nested layout
     * (`opensource/sdks/typescript/...`) is the fallback that closes the monorepo
     * walk-up gap. A future repo reorg that introduces a third layout adds it to
     * [SDK_PACKAGE_SUBPATHS] — shared with the in-process-entry walk-up, so it cannot be
     * taught to one walk-up and not the other — and pins it with the matching test in
     * `LazyYamlScriptedToolRegistrationEsbuildResolverTest`; a regression in the walk-
     * up silently no-ops the entire QuickJS-inline-tool-registration phase on CI
     * agents that don't carry `esbuild` on PATH, which then surfaces hours later as a
     * cryptic "Unsupported tool type for RPC execution" at trail-dispatch time.
     */
    internal fun resolveEsbuildViaWalkup(startDir: File): File? {
      val relativeCandidates = SDK_PACKAGE_SUBPATHS.map { "$it/node_modules/.bin/esbuild" }
      var current: File? = startDir
      while (current != null) {
        for (rel in relativeCandidates) {
          val candidate = File(current, rel)
          if (candidate.exists() && candidate.canExecute()) return candidate
        }
        current = current.parentFile
      }
      return null
    }

    /** Path of the slim in-process entry relative to an SDK package dir (`sdks/typescript`). */
    internal const val IN_PROCESS_SDK_ENTRY_RELPATH: String = "src/in-process.ts"

    /**
     * SDK package dirs probed per ancestor by both walk-ups ([resolveEsbuildViaWalkup] and
     * [resolveInProcessSdkEntryViaWalkup]). Order is intentional and load-bearing: the flat layout
     * (`sdks/typescript`, the OSS tree shape) first, the `opensource/`-nested monorepo layout as
     * fallback. One list rather than a copy per walk-up in this file.
     *
     * SISTER-IMPL-TAG: sdk-package-subpaths. `ScriptedToolDefinitionAnalyzer.resolveSdkDir` in
     * `:trailblaze-host` keeps its own copy (it can't see this internal, and that module isn't on
     * the on-device classpath). A third layout has to be added in both places.
     */
    private val SDK_PACKAGE_SUBPATHS = listOf("sdks/typescript", "opensource/sdks/typescript")

    /**
     * One-liner the deps gate hands esbuild. `console.log` so the import is a value USE: under
     * `--loader=ts` an unused import is elided BEFORE resolution, and the probe would then pass
     * against any tree at all. The shell-side dependency installer documents the same trap.
     */
    private const val SDK_DEPS_PROBE_SOURCE: String = "import { z } from \"zod\"; console.log(z);"

    /**
     * Hang containment for the deps probe, not a performance budget — a real resolve is ~50ms, so
     * this only ever fires on a wedged binary. A deadline hit reports the tree as unusable, which is
     * what routes to the bundler's fallback. Injectable at [sdkDepsUsableForBundling] so a test can
     * drive the deadline branch in a second instead of two minutes.
     *
     * Matches the shell-side dependency installer's own probe deadline and
     * the bundler's own esbuild bound: the same binary shouldn't be called hung by one and healthy by
     * another, and a loaded CI agent is where a tighter bound would produce a false verdict.
     */
    internal const val SDK_DEPS_PROBE_TIMEOUT_SECONDS: Long = 120

    /** How long to wait for a force-killed probe to actually die before unlinking its temp files. */
    private const val PROBE_REAP_TIMEOUT_SECONDS: Long = 10

    /**
     * Environment keys withheld from the probe's esbuild child. `make-test-apk` reads the signing
     * passwords out of the environment and resolves the SDK entry in the same process, so without
     * this the probe would hand an esbuild binary — possibly one walked up from an arbitrary
     * ancestor tree — the keystore passwords, visible to it and to `ps -E`. A child that never sees
     * a secret cannot leak one.
     *
     * SISTER-IMPL-TAG: esbuild-withheld-secrets — the same set is stripped by
     * `DaemonScriptedToolBundler.SECRETS_WITHHELD_FROM_ESBUILD` for the real bundle. Duplicated by
     * name rather than shared because that class lives in `:trailblaze-host`, which depends on this
     * module and not the other way round; adding a key there means adding it here.
     */
    private val SECRETS_WITHHELD_FROM_PROBE = setOf(
      "TRAILBLAZE_INPROCESS_KEYSTORE_PASSWORD",
      "TRAILBLAZE_INPROCESS_KEY_PASSWORD",
    )

    /**
     * Drop [SECRETS_WITHHELD_FROM_PROBE] from [builder]'s inherited environment, leaving the rest
     * (esbuild needs `PATH`, and clearing wholesale would break the probe it is meant to protect).
     *
     * Its own function so a test can seed a builder with the keys and watch them go: the JVM's own
     * environment can't be mutated from inside it, so asserting on a real child's env would only
     * ever prove the parent didn't have them either.
     */
    internal fun withholdProbeSecrets(builder: ProcessBuilder): ProcessBuilder =
      builder.apply { environment().keys.removeAll(SECRETS_WITHHELD_FROM_PROBE) }

    /**
     * Locates the slim `@trailblaze/scripting` in-process SDK entry
     * (`sdks/typescript/src/in-process.ts`) that [DaemonScriptedToolBundler] aliases the package to.
     *
     * Resolved **independently of where esbuild lives** — deliberately NOT by walking up from the
     * esbuild binary. [resolveEsbuildBinary] prefers an esbuild on `PATH` (Homebrew, `npm -g`, …),
     * which sits in an unrelated tree; deriving the SDK source from that binary's location silently
     * fails and the bundler falls back to inlining the full ~1.2 MB SDK into every on-device bundle.
     * Resolving the entry from the daemon CWD instead keeps the slim profile working regardless of
     * which esbuild is used.
     *
     * Honors `TRAILBLAZE_SDK_DIR` (the same explicit override the scripted-tool analyzer uses for
     * installed-CLI scenarios where the SDK source isn't a CWD ancestor); otherwise walks up from
     * the daemon CWD for the SDK source tree — mirroring [resolveEsbuildViaWalkup]'s flat and
     * nested-monorepo layouts.
     *
     * **A tree only wins if it can actually bundle** ([sdkDepsUsableForBundling]). `in-process.ts`
     * does `export { z } from "zod"`, so aliasing the package to a tree whose `node_modules` were
     * never installed fails the bundle with `Could not resolve "zod"` and kills the trail before its
     * first tool call. Null is strictly better than that: `HostScriptedToolLauncher.planInlineToolRoute`
     * reads the absent SDK source as "not a source checkout" and re-enables the per-tool precompiled
     * classpath fallback, so a tool that still can't be live-bundled is served from its staged bundle
     * instead of taking session start down. The aliasless bundle [DaemonScriptedToolBundler] tries
     * first is NOT itself a rescue — a tool that imports `@trailblaze/scripting` from a dep-less
     * checkout usually fails that route too; the per-tool fallback is what saves the session. This
     * gate is the durable fix for a false shrink-regression alert, where a checkout with no
     * installed SDK deps failed the ProGuard release
     * smoke's replay — and it is nasty to diagnose unaided, because the per-tool bundle cache under
     * `~/.trailblaze/` outlives a CI job's checkout wipe, so a warm agent never invokes esbuild and
     * passes while a cold one fails.
     *
     * Null when no usable SDK source is reachable (an installed CLI that doesn't ship one, or a
     * checkout whose deps were never installed). Neither `~/.trailblaze/` cache dir can sneak in
     * here: the analyzer's bundled-shim dir carries no `src/in-process.ts` and the extracted SDK
     * runtime is `dist/index.js`-shaped, and neither sits under a `sdks/typescript` path segment or
     * has `node_modules` — the same exclusion `ScriptedToolDefinitionAnalyzer.resolveSdkDir` makes
     * explicitly for its own cache dir.
     *
     * [esbuildOverride] is for a caller that has already CHOSEN the bundler — `make-test-apk
     * --esbuild` names one for a host with none on `PATH`. The gate must probe with that binary,
     * because the verdict is only meaningful about the esbuild that will actually bundle: probing
     * with none at all reports the structural verdict "usable" and hands the dep-less tree straight
     * to the bundler the caller supplied.
     */
    fun resolveInProcessSdkEntry(esbuildOverride: File? = null): File? =
      resolveInProcessSdkEntry(
        esbuildOverride = esbuildOverride,
        sdkDirEnv = System.getenv("TRAILBLAZE_SDK_DIR"),
        startDir = File(System.getProperty("user.dir") ?: ".").absoluteFile,
      )

    /**
     * [resolveInProcessSdkEntry] with the process environment and CWD injected, so a test can pin
     * that [esbuildOverride] reaches the deps gate without depending on where the JVM was started
     * or on `TRAILBLAZE_SDK_DIR` being unset in the runner.
     */
    internal fun resolveInProcessSdkEntry(
      esbuildOverride: File?,
      sdkDirEnv: String?,
      startDir: File,
    ): File? =
      resolveInProcessSdkEntry(
        sdkDirEnv = sdkDirEnv,
        startDir = startDir,
        depsUsable = { sdkPackageDir ->
          if (esbuildOverride == null) {
            sdkDepsUsableForBundling(sdkPackageDir)
          } else {
            sdkDepsUsableForBundling(sdkPackageDir, esbuildBinary = { esbuildOverride })
          }
        },
      )

    /**
     * Composition half of [resolveInProcessSdkEntry], split out (like [resolveEsbuildOnPath]) so a
     * unit test can pin the `TRAILBLAZE_SDK_DIR` branch against injected values without touching the
     * process environment: a blank/absent [sdkDirEnv] is ignored, and an explicit dir whose
     * `src/in-process.ts` doesn't exist falls THROUGH to the [startDir] walk-up rather than
     * short-circuiting to null. [sdkDirEnv] is resolved to an absolute path so a relative override
     * isn't silently interpreted against the JVM cwd.
     *
     * The override is gated on installed deps too, and an override that names a real SDK tree with
     * no usable deps resolves to **null rather than falling through** to the walk-up. Naming a tree
     * explicitly means "alias `@trailblaze/scripting` to THIS SDK"; quietly bundling against a
     * different copy found above the CWD would mix the caller's tools with another checkout's SDK
     * version — the same hazard `resolveSdkDir` refuses to walk past its nearest tree for. Falling
     * through is reserved for an override that isn't an SDK package at all (no entry file), which is
     * a stale path rather than a version statement.
     *
     * [depsUsable] is injected so a test can drive both gate outcomes without an installed
     * `node_modules` tree; production supplies the real functional probe.
     */
    internal fun resolveInProcessSdkEntry(
      sdkDirEnv: String?,
      startDir: File,
      depsUsable: (File) -> Boolean = { sdkDepsUsableForBundling(it) },
    ): File? {
      sdkDirEnv?.takeIf { it.isNotBlank() }?.let { sdkDir ->
        val sdkPackageDir = File(sdkDir).absoluteFile
        if (File(sdkPackageDir, IN_PROCESS_SDK_ENTRY_RELPATH).isFile) {
          if (!depsUsable(sdkPackageDir)) {
            reportUnusableSdkTree(sdkPackageDir, viaOverride = true)
            return null
          }
          return File(sdkPackageDir, IN_PROCESS_SDK_ENTRY_RELPATH)
        }
      }
      return resolveInProcessSdkEntryViaWalkup(startDir, depsUsable)
    }

    /**
     * Walk-up half of [resolveInProcessSdkEntry], split out so a unit test can pin it against an
     * injected starting directory.
     *
     * The **nearest** SDK package dir decides: a tree that carries the entry but can't bundle it
     * returns null rather than continuing the walk. An outer checkout is a DIFFERENT copy of the SDK
     * (a worktree's parent, a sibling clone), and bundling this tree's tools against that copy's
     * version is worse than the bundler's own version-matched fallback — the reasoning
     * `ScriptedToolDefinitionAnalyzer.resolveSdkDir` spells out for the same shape.
     */
    internal fun resolveInProcessSdkEntryViaWalkup(
      startDir: File,
      depsUsable: (File) -> Boolean = { sdkDepsUsableForBundling(it) },
    ): File? {
      var current: File? = startDir
      while (current != null) {
        for (subPath in SDK_PACKAGE_SUBPATHS) {
          val sdkPackageDir = File(current, subPath)
          val candidate = File(sdkPackageDir, IN_PROCESS_SDK_ENTRY_RELPATH)
          if (!candidate.isFile) continue
          if (depsUsable(sdkPackageDir)) return candidate
          reportUnusableSdkTree(sdkPackageDir, viaOverride = false)
          return null
        }
        current = current.parentFile
      }
      return null
    }

    /**
     * Explain why an SDK source tree was passed over. Without this the only visible symptom is a
     * heavier bundle — or, on the precompiled-fallback route, a tool served from a staged bundle
     * instead of the author's live edit — and both are silent.
     *
     * Reported on EVERY degraded resolution rather than once per tree. The verdict is recomputed per
     * resolution anyway (see [sdkDepsUsableForBundling] on why it isn't memoized), and each caller
     * resolves once per operation — one session launch, one `usages` run, one `make-test-apk` — so
     * there is no repetition to suppress. A once-per-process dedup would instead mean only the
     * FIRST session of a long-lived daemon ever explained itself while every later one degraded in
     * silence, which is the shape that makes this class of failure hard to diagnose in the first
     * place.
     *
     * States the OBSERVED fact (this tree cannot resolve `zod`) and lists the causes rather than
     * asserting one. "`bun install` never ran" is only the most common: a partial install that bun
     * then reports "no changes" over, and an esbuild that failed or hung, land here identically, and
     * a message that named the wrong cause would send the reader to the wrong fix. What esbuild
     * actually said — the one thing that tells them apart — is logged by [probeSdkDeps] itself, which
     * is the layer that has it; the injected [depsUsable] seam here carries only the verdict.
     */
    private fun reportUnusableSdkTree(sdkPackageDir: File, viaOverride: Boolean) {
      val named = if (viaOverride) " even though TRAILBLAZE_SDK_DIR names it" else ""
      Console.info(
        "[LazyYamlScriptedToolRegistration] SDK source tree at $sdkPackageDir can't resolve its own " +
          "`zod`, so it won't be used to bundle scripted tools$named. Its dependencies are missing " +
          "or unusable. Fix: `bun install` there. If that leaves it unusable, delete its " +
          "`node_modules` and install again.",
      )
    }

    /**
     * Whether [sdkPackageDir] can serve as the `@trailblaze/scripting` alias target — i.e. whether
     * esbuild can resolve the `zod` re-export its `src/in-process.ts` performs.
     *
     * **Functional, not structural.** Every file-existence check that could stand in for this has a
     * tree that defeats it: `node_modules/` exists after an interrupted install, and
     * `zod/package.json` outlives a partial one — measured, with zod's files deleted but its
     * `package.json` kept, `bun install` reports "no changes" and exits 0 over a tree that still
     * cannot bundle. Asking the real bundler costs ~50ms and cannot be fooled by a tree that merely
     * LOOKS installed.
     *
     * The `node_modules` check ahead of it is a fast path, not a second gate. It looks for the
     * DIRECTORY rather than `node_modules/zod` on purpose: a present-but-partial tree is exactly what
     * the functional probe exists to catch, so narrowing the fast path would let a structural check
     * answer the question the probe is here to answer. It costs one `isDirectory` and gives a cold
     * checkout — the case this whole gate exists for — a deterministic answer without a subprocess.
     * What it cannot see is a fully hoisted install (npm/pnpm workspaces) that leaves the SDK package
     * dir with no `node_modules` of its own while `zod` resolves from an ancestor: this reports that
     * tree unusable and the caller degrades to the version-matched fallback. Conservative and wrong
     * in one direction only — and not a layout `bun install` in this repo produces.
     *
     * **Deliberately not memoized.** Each caller resolves once per operation, so the ~50ms lands
     * once per session launch rather than once per tool. A memo would be worse than the cost it
     * saves: a developer who runs `bun install` mid-session would stay pinned to the fallback for
     * the daemon's lifetime, and there is no invalidation signal to key it off.
     *
     * [esbuildBinary] defaults to the binary the BUNDLER will use ([resolveEsbuildBinary], PATH
     * first), because the question is whether that binary can resolve `zod` from this tree — probing
     * with a different esbuild than the one that will bundle can disagree. It falls back to the
     * tree's own bundler for the override case, where the SDK isn't a CWD ancestor and so the
     * walk-up inside [resolveEsbuildBinary] can't see it. With no esbuild anywhere the structural
     * answer stands: nothing can be live-bundled at all in that state, so the alias target is moot —
     * `planInlineToolRoute` has already routed every tool to its precompiled bundle.
     *
     * [esbuildBinary] is a SUPPLIER, not a value, so the `node_modules` fast path returns before
     * resolution happens at all — as a default it would run a full `PATH` scan and a walk to the
     * filesystem root on every cold checkout, which is the case the fast path exists to make cheap.
     *
     * SISTER-IMPL-TAG: sdk-deps-bundle-probe — the shell-side dependency installer's readiness
     * check asks the same question in bash, and the probe SOURCE
     * (including the value-use trap) must stay identical. Three differences are intentional, because
     * the two answer it for different purposes — bash decides whether to INSTALL, this decides
     * whether the alias BINDS:
     *  - No esbuild: bash reports not-ready (it wants an install to produce one); this reports usable
     *    (with no esbuild nothing live-bundles, so the alias is moot).
     *  - Fast path: bash requires the tree's own `node_modules/.bin/esbuild`; this requires only a
     *    `node_modules` dir, and accepts a PATH esbuild.
     *  - Probe flags: this mirrors `DaemonScriptedToolBundler.runEsbuild`'s resolution-relevant flags
     *    so the verdict predicts the real bundle; bash's coarser set is fine for a readiness check.
     *
     * The deadlines match on purpose ([SDK_DEPS_PROBE_TIMEOUT_SECONDS] and the bash side's own):
     * the same wedged binary should not be called hung by one
     * and healthy by the other.
     */
    internal fun sdkDepsUsableForBundling(
      sdkPackageDir: File,
      esbuildBinary: () -> File? = {
        resolveEsbuildBinary()
          ?: File(sdkPackageDir, "node_modules/.bin/esbuild").takeIf { it.canExecute() }
      },
      timeoutSeconds: Long = SDK_DEPS_PROBE_TIMEOUT_SECONDS,
    ): Boolean {
      if (!File(sdkPackageDir, "node_modules").isDirectory) return false
      val binary = esbuildBinary() ?: return true
      return probeSdkDeps(sdkPackageDir, binary, timeoutSeconds)
    }

    /**
     * Run [SDK_DEPS_PROBE_SOURCE] through [esbuildBinary] with [sdkPackageDir] as the working
     * directory (which is what esbuild resolves a stdin entry's imports against) and report whether
     * it bundled.
     *
     * Output goes to throwaway temp files rather than pipes: a bundled `zod` is over 500 KB, and a
     * pipe nobody drains fills and deadlocks the probe. On a "no" the captured output is logged here,
     * because this is the only layer that has it — [reportUnusableSdkTree] sits behind an injected
     * boolean seam and can only list candidate causes.
     *
     * **A probe that cannot RUN is not a verdict about the tree.** A read-only `java.io.tmpdir` says
     * nothing about whether `zod` resolves, so that path answers "usable" and leaves the tree's fate
     * to the bundler — the same call [sdkDepsUsableForBundling] makes when no esbuild exists at all.
     * A binary that fails to EXEC is different: that esbuild is the one that would bundle, so its
     * failure is a real "no".
     */
    private fun probeSdkDeps(
      sdkPackageDir: File,
      esbuildBinary: File,
      timeoutSeconds: Long,
    ): Boolean {
      var scratch: File? = null
      val bundleOut: File
      val log: File
      var proc: Process? = null
      try {
        // The first file is tracked before the second is attempted: a createTempFile that throws
        // after one already succeeded would otherwise leak it on every resolution, and a full temp
        // dir is exactly the condition under which that happens.
        bundleOut = File.createTempFile("trailblaze-sdk-deps-probe", ".js").also { scratch = it }
        log = File.createTempFile("trailblaze-sdk-deps-probe", ".log")
      } catch (e: Exception) {
        scratch?.delete()
        Console.info(
          "[LazyYamlScriptedToolRegistration] could not create a temp file for the SDK deps probe " +
            "(${e.message ?: e.javaClass.simpleName}) — this says nothing about $sdkPackageDir, so " +
            "the tree is left in place and the bundler decides.",
        )
        return true
      }
      try {
        val builder = ProcessBuilder(
          buildList {
            add(esbuildBinary.absolutePath)
            add("--bundle")
            add("--loader=ts")
            // The resolution-relevant half of DaemonScriptedToolBundler.runEsbuild's flag set. These
            // are what decide WHICH file a bare `zod` import resolves to (esbuild's default
            // platform=browser picks different package fields), so a probe without them can pass
            // over a tree the real bundle still fails on.
            add("--platform=neutral")
            add("--main-fields=module,main")
            add("--outfile=${bundleOut.absolutePath}")
          },
        )
          .directory(sdkPackageDir)
          .redirectErrorStream(true)
          .redirectOutput(log)
        // SISTER-IMPL-TAG: esbuild-withheld-secrets.
        proc = withholdProbeSecrets(builder).start()
        // The exit code is the verdict, so a failed write is not one: a process that already died
        // breaks the pipe here, and reporting THAT as "unusable" would hide whatever esbuild
        // actually said. Close either way — esbuild reads stdin to EOF before it resolves anything.
        runCatching {
          proc.outputStream.use { it.write(SDK_DEPS_PROBE_SOURCE.toByteArray(Charsets.UTF_8)) }
        }
        if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
          Console.info(
            "[LazyYamlScriptedToolRegistration] the SDK deps probe in $sdkPackageDir hung past its " +
              "${timeoutSeconds}s deadline and was killed. A real resolve is ~50ms, so this fires " +
              "on a wedged esbuild rather than a slow one.",
          )
          return false
        }
        val exit = proc.exitValue()
        if (exit == 0) return true
        // A child killed by a signal (exit >= 128: the OOM killer, a CI step tearing down its
        // process group) never got to answer the question, and its log is empty — so calling that
        // "this tree cannot resolve zod" would blame the tree for the machine, exactly what the
        // temp-file branch above refuses to do. esbuild's own verdict is exit 1.
        if (exit >= 128) {
          Console.info(
            "[LazyYamlScriptedToolRegistration] the SDK deps probe was killed by a signal " +
              "(exit $exit) before it answered — that says nothing about $sdkPackageDir, so the " +
              "tree is left in place and the bundler decides.",
          )
          return true
        }
        Console.info(
          "[LazyYamlScriptedToolRegistration] the SDK deps probe exited $exit." +
            readProbeLog(log)?.let { " esbuild said:\n$it" }.orEmpty(),
        )
        return false
      } catch (e: InterruptedException) {
        // Rethrown, not swallowed. The one suspend caller runs this inside `runInterruptible`, which
        // turns the interrupt back into the coroutine cancellation it came from — swallowing it would
        // let a cancelled launch carry on, and returning a verdict would state one we never got.
        // Restore the flag the throw cleared so anything between here and there sees it too.
        Thread.currentThread().interrupt()
        throw e
      } catch (e: Exception) {
        Console.info(
          "[LazyYamlScriptedToolRegistration] the SDK deps probe could not run " +
            "${esbuildBinary.absolutePath} (${e.message ?: e.javaClass.simpleName}) — treating " +
            "$sdkPackageDir as unusable, since that is the esbuild that would bundle it.",
        )
        return false
      } finally {
        // Kill the child on EVERY exit — deadline, interrupt, throw. Without this a wedged esbuild
        // outlives the resolver, and the deletes below are what keep a per-session probe from
        // accumulating half-megabyte bundles in the temp dir.
        proc?.takeIf { it.isAlive }?.let {
          it.destroyForcibly()
          // Reap before unlinking, like DaemonScriptedToolBundler.runEsbuild does: a child still
          // finishing its write recreates the outfile after the delete, orphaning the file we were
          // trying not to leave behind.
          runCatching { it.waitFor(PROBE_REAP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
        }
        bundleOut.delete()
        log.delete()
      }
    }

    /** First [PROBE_LOG_DETAIL_LIMIT] chars of the probe's combined output, or null when it said
     *  nothing (or the file went away). Bounded because esbuild can emit many errors and this
     *  lands in a user-facing line. */
    private fun readProbeLog(log: File): String? = runCatching {
      log.readText().trim().takeIf { it.isNotEmpty() }?.take(PROBE_LOG_DETAIL_LIMIT)
    }.getOrNull()

    private const val PROBE_LOG_DETAIL_LIMIT: Int = 1_000

    /**
     * Build a registration whose [QuickJsToolHost] is already connected to [bundlePath].
     *
     * Suspending because [QuickJsToolHost.connect] is — the JS evaluation that registers
     * `globalThis.__trailblazeTools[toolName]` runs synchronously inside QuickJS but the
     * JVM-side wiring around it (binding install, mutex setup) is suspend. Pre-connecting
     * means [decodeToolCall] is non-suspend and the resulting tool's `execute()` doesn't
     * have to re-check connection state on every dispatch.
     */
    suspend fun create(
      toolConfig: InlineScriptToolConfig,
      bundlePath: File,
      toolRepo: TrailblazeToolRepo,
      sessionId: SessionId,
      engineExtension: QuickJsEngineExtension? = null,
    ): LazyYamlScriptedToolRegistration {
      // Construct the binding eagerly and hold a reference so the registration can set
      // the binding's `activeContext` on each dispatch. Without holding the reference,
      // the only way to access the binding would be through the host's closure, which
      // doesn't expose it.
      val binding = SessionScopedHostBinding(toolRepo, sessionId)
      val host = QuickJsToolHost.connect(
        bundleJs = bundlePath.readText(),
        bundleFilename = "${toolConfig.name}.bundle.js",
        hostBinding = binding,
        // Optional engine extension (e.g. the OkHttp-backed `fetch`), so in-process scripted tools
        // can reach a bound `fetch` instead of shelling curl. This registration path is
        // host-launcher-only in production (`HostScriptedToolLauncher`); the on-device launchers
        // install the same extension via `QuickJsToolBundleLauncher.launchAll`, so `fetch` is
        // available in both runtimes.
        engineExtension = engineExtension,
      )
      return LazyYamlScriptedToolRegistration(toolConfig, host, binding)
    }

    /**
     * Public, launch-free descriptor builder for advertise-time surfaces that need a scripted
     * tool's [TrailblazeToolDescriptor] WITHOUT connecting a QuickJS engine (e.g. a synchronous
     * `getAvailableTools()` that advertises before any dispatch). Delegates to [buildDescriptor]
     * so the advertised shape matches what a launched registration would carry.
     */
    fun buildScriptedToolDescriptor(config: InlineScriptToolConfig): TrailblazeToolDescriptor =
      buildDescriptor(config)

    /**
     * Builds a [TrailblazeToolDescriptor] from the YAML-declared `inputSchema:` block.
     * Mirrors [xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor] but operates
     * directly on the [JsonObject] shape that [InlineScriptToolConfig.inputSchema] uses
     * (vs. the MCP SDK's `ToolSchema` wrapper). Identical extraction logic — `properties`
     * map → `TrailblazeToolParameterDescriptor` per entry, partitioned by the top-level
     * `required` list, threading each property's JSON-Schema `enum` into
     * [TrailblazeToolParameterDescriptor.validValues].
     */
    private fun buildDescriptor(config: InlineScriptToolConfig): TrailblazeToolDescriptor {
      val schema = config.inputSchema
      val requiredNames = (schema["required"]?.jsonArray.orEmpty())
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .toSet()
      val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
      val all = properties.mapNotNull { (propName, rawSchema) ->
        val propSchema = rawSchema as? JsonObject ?: return@mapNotNull null
        TrailblazeToolParameterDescriptor(
          name = propName,
          type = (propSchema["type"] as? JsonPrimitive)?.contentOrNull ?: "string",
          description = (propSchema["description"] as? JsonPrimitive)?.contentOrNull,
          validValues = jsonSchemaEnumValues(propSchema),
        )
      }
      return TrailblazeToolDescriptor(
        name = config.name,
        description = config.description,
        requiredParameters = all.filter { it.name in requiredNames },
        optionalParameters = all.filter { it.name !in requiredNames },
      ).apply {
        // Retain the full schema (nested `properties`/array `items`) alongside the flat parameter
        // split so [coerceArgsToDescriptorTypes] can re-align a scalar buried inside a nested
        // object / array-of-objects (e.g. `overrides[].value`) — not just the top-level args — on
        // both replay dispatch and the trail-recording validator. Non-empty schemas only; a
        // no-arg tool keeps the flat (null-schema) path. Set on the body property (not the
        // constructor) to keep the descriptor's public JVM ABI stable.
        inputSchema = schema.takeIf { it.isNotEmpty() }
      }
    }

    /**
     * Extracts a property schema's JSON-Schema `enum` array as a list of allowed string values, or
     * null when the property declares no usable string `enum`. A TS string-literal union
     * (`"UP" | "DOWN" | …`) lowers to `{ "type": "string", "enum": ["UP", "DOWN", …] }`, so this
     * is what carries the allowed-value fidelity a Kotlin `@Serializable` enum param gets for free
     * (koog surfaces those via `ToolParameterType.Enum`). Without it the LLM sees only `type:
     * string` and never the legal values.
     *
     * **Only STRING enums are promoted** — see [xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor]'s
     * `enumValues` for the rationale (koog enums are string-only, so a non-string enum would emit a
     * lying `{"type":"string",...}` schema). A non-string-typed enum keeps its primitive type and
     * drops the constraint. An empty `enum` folds to null. Mirrors that matching extraction.
     */
    private fun jsonSchemaEnumValues(propSchema: JsonObject): List<String>? {
      val type = (propSchema["type"] as? JsonPrimitive)?.contentOrNull
      if (type != null && !type.equals("string", ignoreCase = true)) return null
      return (propSchema["enum"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        ?.takeIf { it.isNotEmpty() }
    }
  }
}
