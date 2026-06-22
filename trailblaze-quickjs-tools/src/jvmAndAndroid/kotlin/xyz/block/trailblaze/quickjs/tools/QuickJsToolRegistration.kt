package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * One QuickJS-bundle-advertised tool's entry in the session's `TrailblazeToolRepo`.
 *
 * Mirror of the legacy `BundleToolRegistration` minus the MCP framing — tools are resolved
 * by name and dispatched via [QuickJsToolHost.callTool]. The repo can't tell whether a
 * dynamic registration came from the QuickJS or MCP runtime; both surface as Koog tools
 * the LLM picks from the same list.
 */
class QuickJsToolRegistration(
  /**
   * The host this tool lives in. The registration holds the host reference rather than
   * looking it up at execute time so the `@trailblaze/scripting` SDK's "register on bundle
   * evaluation, dispatch by name" contract can be implemented as a direct call.
   */
  internal val host: QuickJsToolHost,
  /** What the bundle advertised when [QuickJsToolHost.listTools] was called. */
  internal val spec: RegisteredToolSpec,
  /**
   * Session-scoped binding for this tool's host. Forwarded to [QuickJsToolSerializer] so the
   * constructed [QuickJsTrailblazeTool] sets [SessionScopedHostBinding.activeContext] around
   * its QuickJS evaluation — the only mechanism that survives the `asyncFunction` callback's
   * thread hop and lets a nested `trailblaze.call(...)` resolve the session context. Null on
   * paths that don't support cross-tool composition.
   */
  internal val binding: SessionScopedHostBinding? = null,
  /**
   * Advertise-time descriptor to expose to the LLM instead of one derived from [spec].
   *
   * Typed scripted tools (`export const x = trailblaze.tool<I>(...)`) bundle through a
   * synthesized wrapper that registers a handler-only entry on `globalThis.__trailblazeTools`
   * (no `spec`), because the bundle is the lean dispatch surface — the description / inputSchema /
   * `_meta` live in the tool's YAML descriptor, which the daemon/host path already reads. The
   * on-device launcher supplies that YAML-derived descriptor here so the LLM sees the real
   * description + parameters rather than the empty descriptor [spec] would yield. Null on paths
   * that legitimately advertise from the bundle's own `spec` (e.g. a hand-written `pure.js`
   * fixture that populates `spec`).
   */
  internal val descriptorOverride: TrailblazeToolDescriptor? = null,
  /**
   * 1:1 with the scripted tool's declared `surfaceToLlm`. When `false`,
   * [TrailblazeToolRepo.advertisedDynamic] drops this registration from the LLM's tool menu while
   * keeping it dispatchable by name and resolvable for recorded replays. Sourced from the tool's
   * `_meta` ([QuickJsToolMeta.surfaceToLlm]) by [QuickJsToolBundleLauncher]. Default `true`.
   */
  override val surfaceToLlm: Boolean = true,
  /**
   * 1:1 with the scripted tool's declared `isRecordable`. When `false`, [decodeToolCall] threads it
   * onto the decoded tool's `toolMetadata` so the recording gate keeps the invocation out of the
   * `.trail.yaml`. Sourced from the tool's `_meta` ([QuickJsToolMeta.isRecordable]) by
   * [QuickJsToolBundleLauncher]. Default `true`.
   */
  internal val isRecordable: Boolean = true,
) : DynamicTrailblazeToolRegistration {

  override val name: ToolName = ToolName(spec.name)

  override val trailblazeDescriptor: TrailblazeToolDescriptor =
    descriptorOverride ?: spec.toTrailblazeToolDescriptor()

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: bundle authors can advertise schema types the LLM-tool descriptor
    // doesn't model today (`array`, `object`, etc.) — those fall back to String rather than
    // crashing session startup. Same posture the legacy MCP-bundle registration takes.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = QuickJsToolSerializer(name, host, binding, isRecordable)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: QuickJsTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        val result = args.execute(context)
        "Executed QuickJS tool: ${name.toolName} — result: $result"
      },
    )
  }

  override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
    // `isRecordable` is threaded onto the decoded QuickJsTrailblazeTool itself (via the serializer)
    // rather than wrapping it: the decoded instance must STAY a QuickJsTrailblazeTool so
    // SessionScopedHostBinding's same-host re-entry guard (`resolved is QuickJsTrailblazeTool`)
    // still fires — a wrapper would let a non-recordable same-bundle compose bypass the guard and
    // deadlock the host's non-reentrant evalMutex. The tool's `toolMetadata` carries the opt-out so
    // the recording gate (`getIsRecordableFromAnnotation`) skips it.
    val serializer = QuickJsToolSerializer(name, host, binding, isRecordable)
    return Json.decodeFromString(serializer, argumentsJson)
  }
}
