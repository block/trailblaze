package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.quickjs.tools.QuickJsToolSerializer
import xyz.block.trailblaze.quickjs.tools.QuickJsTrailblazeTool
import xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
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

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: scripted-tool authors can declare `inputSchema` types the
    // LLM-tool descriptor doesn't model directly (`array`, `object`, etc.) — those fall
    // back to String rather than crashing session startup. Mirrors the leniency applied
    // by [BundleToolRegistration.buildKoogTool] for the on-device QuickJS path.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = QuickJsToolSerializer(name, host)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: QuickJsTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        // Set the context directly on the binding for the duration of this dispatch so
        // any `client.callTool(...)` from inside the bundled handler can resolve through
        // the binding — see `SessionScopedHostBinding.activeContext` for why we bypass
        // the ThreadLocal here (QuickJS's async-binding scope doesn't inherit
        // `asContextElement` propagation, so the ThreadLocal is unreliable in that
        // callback).
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
    val serializer = QuickJsToolSerializer(name, host)
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
   */
  private class ContextSettingScriptedTool(
    private val inner: QuickJsTrailblazeTool,
    private val binding: SessionScopedHostBinding,
  ) : xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String get() = inner.advertisedToolName

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
     * walk-up gap. A future repo reorg that introduces a third layout should add it
     * here and pin it with the matching test in
     * `LazyYamlScriptedToolRegistrationEsbuildResolverTest`; a regression in the walk-
     * up silently no-ops the entire QuickJS-inline-tool-registration phase on CI
     * agents that don't carry `esbuild` on PATH, which then surfaces hours later as a
     * cryptic "Unsupported tool type for RPC execution" at trail-dispatch time.
     */
    internal fun resolveEsbuildViaWalkup(startDir: File): File? {
      val relativeCandidates = listOf(
        "sdks/typescript/node_modules/.bin/esbuild",
        "opensource/sdks/typescript/node_modules/.bin/esbuild",
      )
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
      )
      return LazyYamlScriptedToolRegistration(toolConfig, host, binding)
    }

    /**
     * Builds a [TrailblazeToolDescriptor] from the YAML-declared `inputSchema:` block.
     * Mirrors [xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor] but operates
     * directly on the [JsonObject] shape that [InlineScriptToolConfig.inputSchema] uses
     * (vs. the MCP SDK's `ToolSchema` wrapper). Identical extraction logic — `properties`
     * map → `TrailblazeToolParameterDescriptor` per entry, partitioned by the top-level
     * `required` list.
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
        )
      }
      return TrailblazeToolDescriptor(
        name = config.name,
        description = config.description,
        requiredParameters = all.filter { it.name in requiredNames },
        optionalParameters = all.filter { it.name !in requiredNames },
      )
    }
  }
}
