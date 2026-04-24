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
 *    feedback message.
 *  - `isError == true`  → [TrailblazeToolResult.Error.ExceptionThrown] carrying the first
 *    text block as the error message.
 *  - Missing `isError` (protocol default is `false`) → treated as success.
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
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message ?: "Subprocess tool returned isError=true with no text content.",
    )
  } else {
    TrailblazeToolResult.Success(message = message)
  }
}
