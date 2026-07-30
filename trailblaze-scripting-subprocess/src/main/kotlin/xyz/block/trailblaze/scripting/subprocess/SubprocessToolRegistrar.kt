package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.util.Console

/**
 * One subprocess-advertised tool that passed the Trailblaze registration filter and is ready
 * to enter the session's tool registry.
 *
 * Carries everything a later dispatch layer (adapter + Koog wiring) needs: the advertised
 * name (also the registered name per conventions § 4), parsed [TrailblazeToolMeta], the MCP
 * [inputSchema] for the LLM-facing arg signature, and the human-readable description.
 *
 * Deliberately thin — the adapter that implements `ExecutableTrailblazeTool` lives one
 * commit later; this record is what the registration pipeline produces, independent of how
 * it's surfaced to Koog / the LLM.
 */
data class RegisteredSubprocessTool(
  val advertisedName: ToolName,
  val description: String?,
  val inputSchema: ToolSchema,
  val meta: TrailblazeToolMeta,
)

/**
 * Pure filter-and-project step: turn a `tools/list` response into the set that applies to
 * the current session (driver + agent mode) and should be registered.
 *
 * Separated from the session-level `fetchAndFilterTools` call so tests can exercise the
 * filter logic with synthesized [Tool] lists — no real subprocess required.
 */
object SubprocessToolRegistrar {

  /**
   * Parse each tool's `_meta`, apply the capability filter, and return the registerable
   * subset. Order matches the input. Tools rejected by the filter are dropped silently —
   * the session log records what was skipped and why is a polish item for the lifecycle
   * commit (structured logging will ride there).
   */
  fun filterAdvertisedTools(
    tools: List<Tool>,
    driver: TrailblazeDriverType,
    preferHostAgent: Boolean,
  ): List<RegisteredSubprocessTool> = tools.mapNotNull { tool ->
    val meta = TrailblazeToolMeta.fromTool(tool)
    if (!meta.shouldRegister(driver, preferHostAgent)) {
      null
    } else {
      RegisteredSubprocessTool(
        advertisedName = ToolName(tool.name),
        description = tool.description,
        inputSchema = tool.inputSchema,
        meta = meta,
      )
    }
  }

  /**
   * Pre-spawn twin of [filterAdvertisedTools]: the same capability filter, applied to a target's
   * declared inline scripted tools BEFORE a wrapper is synthesized and a subprocess forked for
   * them.
   *
   * Both gates read the same `_meta` (a synthesized wrapper advertises exactly [advertisedMeta]),
   * so this never keeps a tool [filterAdvertisedTools] would drop, nor drops one it would keep —
   * it only decides the question earlier, when the answer is already known. Applying it only after
   * the handshake meant a session paid a fork, a bun cold start, and a `script:` path resolution
   * for a tool it was always going to discard: a web-only `runtime: subprocess` tool failed an
   * android session outright, either at path resolution (a bundled trailmap's `.ts` ships inside
   * the JAR, not the consuming workspace) or at handshake (the author module reaches
   * `@trailblaze/scripting` through its trailmap's `tools/tsconfig.json` `paths` climb into a
   * built SDK checkout, which a consumer workspace doesn't have).
   *
   * Skips are logged rather than silent — an author whose tool never registers needs to see the
   * gate that rejected it, not just an "unknown tool" at dispatch.
   */
  fun applicableInlineTools(
    tools: List<InlineScriptToolConfig>,
    driver: TrailblazeDriverType,
    preferHostAgent: Boolean,
    logPrefix: String = "[subprocess-tools]",
  ): List<InlineScriptToolConfig> {
    val (applicable, skipped) = tools.partition {
      val meta = advertisedMeta(it)?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()
      meta.shouldRegister(driver, preferHostAgent)
    }
    if (skipped.isNotEmpty()) {
      Console.log(
        "$logPrefix Not spawning ${skipped.size} subprocess scripted tool(s) that don't apply to a " +
          "${driver.platform.name} / ${driver.yamlKey} session: ${skipped.joinToString(", ") { it.name }}",
      )
    }
    return applicable
  }

  /**
   * The `_meta` an inline scripted tool advertises once it reaches `tools/list` — its authored
   * `_meta` with the [InlineScriptToolConfig.requiresHost] shortcut folded in. `null` when the tool
   * advertises no metadata at all.
   *
   * One source of truth for two consumers: [InlineScriptToolServerSynthesizer] emits it into the
   * generated wrapper, and [applicableInlineTools] gates on it before that wrapper exists. Keeping
   * them on the same function is what makes the pre-spawn gate provably agree with the post-spawn
   * one. Only `requiresHost` folds in here — the other typed shortcuts are already merged into
   * `_meta` at descriptor-load time (`TrailmapScriptedToolFile.toInlineScriptToolConfigs`).
   */
  fun advertisedMeta(config: InlineScriptToolConfig): JsonObject? {
    if (!config.requiresHost) return config.meta
    val merged = config.meta?.toMutableMap() ?: mutableMapOf()
    merged["trailblaze/requiresHost"] = JsonPrimitive(true)
    return JsonObject(merged)
  }
}

/**
 * Convenience: run `tools/list` on this session's connected [io.modelcontextprotocol.kotlin.sdk.client.Client]
 * and delegate to [SubprocessToolRegistrar.filterAdvertisedTools].
 *
 * The caller provides the session-context bits (driver + host-agent mode) so this method
 * stays agnostic of where those come from — the repo integration layer can pass in what it
 * has without coupling to a higher-level session object.
 */
suspend fun McpSubprocessSession.fetchAndFilterTools(
  driver: TrailblazeDriverType,
  preferHostAgent: Boolean,
): List<RegisteredSubprocessTool> {
  val result = client.listTools(ListToolsRequest())
  return SubprocessToolRegistrar.filterAdvertisedTools(result.tools, driver, preferHostAgent)
}
