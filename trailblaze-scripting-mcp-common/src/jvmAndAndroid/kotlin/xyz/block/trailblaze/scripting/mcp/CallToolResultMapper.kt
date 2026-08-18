package xyz.block.trailblaze.scripting.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Maps an advertised MCP server's [CallToolResult] onto Trailblaze's sealed
 * [TrailblazeToolResult].
 *
 * MVP implements the conventions devlog (§ 3) baseline mapping only:
 *
 *  - `isError == false` → [TrailblazeToolResult.Success] with the first text block as the
 *    feedback message and any [CallToolResult.structuredContent] threaded through verbatim.
 *  - `isError == true`  → [TrailblazeToolResult.Error.ExceptionThrown] carrying the first
 *    text block as the error message.
 *  - Missing `isError` (protocol default is `false`) → treated as success.
 *
 * `structuredContent` is the MCP-spec field for a typed JSON return value (added in MCP
 * 0.7+). Trailblaze threads it through unchanged so a TS scripted tool whose handler
 * returns a non-string typed value can deliver it to a scripted caller — see
 * `JsScriptingCallbackResult.CallToolResult.structuredContent` for the wire field and
 * `client.tools.<name>(args)` (TS SDK) for the consumer side.
 *
 * `isError == true` responses thread [CallToolResult.structuredContent] through as
 * [TrailblazeToolResult.Error.ExceptionThrown.structuredPayload] — the mirror of the
 * success-side threading, and just as opaque: the framework never interprets the payload.
 * A TS tool opts in by throwing the SDK's `ToolError` with `data` (or returning an
 * `isError` envelope with `structuredContent` directly).
 *
 * `_meta.trailblaze.variant` rich-variant support (FatalError, MissingRequiredArgs, etc.
 * per conventions § 3 "future extension") is **not** implemented yet — authors who want
 * those variants should continue using the ExceptionThrown path for now. Lands additively
 * when a concrete author need surfaces.
 *
 * Shared across the subprocess runtime (`:trailblaze-scripting-subprocess`) and the
 * on-device bundle runtime (`:trailblaze-scripting-bundle`) so both surfaces hand authors
 * the same `TrailblazeToolResult` shape for the same MCP response.
 */
fun CallToolResult.toTrailblazeToolResult(): TrailblazeToolResult {
  val message = content.asSequence()
    .filterIsInstance<TextContent>()
    .firstOrNull()
    ?.text
  val hasError = isError == true
  return if (hasError) {
    // The scripted-caller channel ([JsScriptingCallbackResult.CallToolResult.errorMessage])
    // remains text-only: a TS tool calling BACK into Trailblaze doesn't receive the payload.
    // The trailhead flow doesn't need that direction — the tool's own failure crosses here,
    // and composite tools re-attach their payload TS-side before re-crossing.
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message ?: "Subprocess tool returned isError=true with no text content.",
      structuredPayload = structuredContent,
    )
  } else {
    TrailblazeToolResult.Success(
      message = message,
      structuredContent = structuredContent,
    )
  }
}
