package xyz.block.trailblaze

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a scripted tool's result `_meta` marks its failure as fatal
 * (`_meta.trailblaze.variant == "FatalError"`, per the scripted-tools MCP conventions devlog).
 *
 * A fatal failure maps to [xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.FatalError]
 * so a recorded step skips self-heal. The TS SDK sets the marker when a nested `ctx.tools.*` call
 * returned a fatal error and the calling tool let it propagate.
 *
 * Lives beside [applyScriptedToolMemoryDelta] for the same reason: the subprocess/MCP runtime and
 * the on-device QuickJS runtime both read it and can't share a module that carries more.
 */
fun scriptedToolResultIsFatal(resultMeta: JsonObject?): Boolean {
  val trailblaze = resultMeta?.get(META_KEY_TRAILBLAZE) as? JsonObject ?: return false
  return (trailblaze[META_KEY_VARIANT] as? JsonPrimitive)?.takeIf { it.isString }?.content == VARIANT_FATAL_ERROR
}

/** `_meta.trailblaze.variant` — names the [TrailblazeToolResult.Error] subtype of a failed result. */
private const val META_KEY_VARIANT: String = "variant"

private const val VARIANT_FATAL_ERROR: String = "FatalError"
