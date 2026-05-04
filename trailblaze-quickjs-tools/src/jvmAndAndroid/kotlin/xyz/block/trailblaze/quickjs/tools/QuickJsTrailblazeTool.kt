package xyz.block.trailblaze.quickjs.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Executable form of a tool advertised by a QuickJS bundle. Implements
 * [HostLocalExecutableTrailblazeTool] so [BaseTrailblazeAgent.runTrailblazeTools][xyz.block.trailblaze.BaseTrailblazeAgent]
 * short-circuits to [execute] and skips the driver-specific path that would otherwise route
 * the tool through Maestro / Playwright.
 *
 * No MCP framing here — [execute] simply asks [host] to dispatch by name with the typed
 * args + a small ctx envelope built from [QuickJsToolCtxEnvelope]. The data classes in
 * [QuickJsToolEnvelopes] are the single source of truth for envelope field names; a
 * future change that grows the `ctx` shape edits the data class instead of every
 * `put("…", …)` call site.
 */
class QuickJsTrailblazeTool(
  /** The host this tool's bundle was loaded into. Stable for the session. */
  internal val host: QuickJsToolHost,
  /** The tool name the bundle registered. Same name the LLM sees. */
  internal val advertisedName: ToolName,
  /** LLM-supplied args, decoded from the tool-call's `arguments` JSON. */
  internal val args: JsonObject,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = advertisedName.toolName

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val ctx = buildCtxEnvelope(toolExecutionContext)
    return try {
      val resultJson = host.callTool(advertisedName.toolName, args, ctx)
      resultJson.toTrailblazeToolResult(toolName = advertisedName.toolName)
    } catch (e: CancellationException) {
      // Coroutine cancellation must propagate — swallowing breaks structured concurrency
      // for session teardown, agent abort, etc. Same pattern the legacy BundleTrailblazeTool
      // uses; catch order matters because CancellationException extends Exception.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    }
  }

  private fun buildCtxEnvelope(toolExecutionContext: TrailblazeToolExecutionContext): JsonObject {
    val sessionId = toolExecutionContext.sessionProvider.invoke().sessionId
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    val ctx = QuickJsToolCtxEnvelope(
      sessionId = sessionId.value,
      device = QuickJsDeviceContext(
        platform = deviceInfo.trailblazeDriverType.platform.name,
        driver = deviceInfo.trailblazeDriverType.yamlKey,
      ),
    )
    // The host expects the ctx as a JsonObject (it embeds it inline as a JS literal),
    // so encode-to-element rather than encode-to-string — saves a parse-back trip.
    return QuickJsToolEnvelopeJson.encodeToJsonElement(
      QuickJsToolCtxEnvelope.serializer(),
      ctx,
    ).jsonObject
  }
}

/**
 * Maps the SDK's tool-result envelope to a [TrailblazeToolResult]. Decodes through the
 * typed [QuickJsToolResultEnvelope] — the field names live in [QuickJsToolEnvelopes],
 * not as ad-hoc string keys here.
 *
 * **Malformed-envelope handling:** an envelope that fails strict deserialization (e.g.,
 * a bare `{}` or `{ result: 42 }` with no `content` array, or one whose content list
 * holds non-object entries) is reported as [TrailblazeToolResult.Error.ExceptionThrown]
 * rather than a silent `Success(null)`. Author bugs in a bundle handler that returned
 * the wrong shape would otherwise hide as no-op trail passes — the LLM sees nothing
 * wrong, trail logs show success, and the broken handler keeps shipping. Treating
 * "shape doesn't match" as a structural error makes the bug loud at the boundary.
 * [QuickJsToolEnvelopeJson]'s `ignoreUnknownKeys` keeps forward-compatible extra fields
 * harmless — only required-field mismatches surface as errors.
 */
internal fun JsonObject.toTrailblazeToolResult(toolName: String? = null): TrailblazeToolResult {
  // We need the parser to be lenient on extra fields but strict on shape — `content`
  // must exist for a Success. Decode into the typed envelope; if `content` was missing
  // entirely we'll see an empty list (the data class default), which we treat as a
  // structural error per the kdoc above.
  val envelope = try {
    QuickJsToolEnvelopeJson.decodeFromJsonElement(
      QuickJsToolResultEnvelope.serializer(),
      this,
    )
  } catch (e: SerializationException) {
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "QuickJS tool ${toolLabel(toolName)}returned a malformed envelope: ${e.message}",
    )
  }
  if (envelope.isError) {
    val rendered = envelope.renderContent()
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = rendered.ifBlank { "QuickJS tool returned isError without text content" },
    )
  }
  // A missing `content` decodes to an empty list (data-class default). Distinguish "the
  // bundle returned an empty content array on purpose" (Success with null message —
  // legitimate for void-shaped tools) from "the bundle didn't return a content key at
  // all" (structural error). The presence check looks at the raw JsonObject because
  // the data-class default has already collapsed both into the same in-memory shape.
  if ("content" !in this) {
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "QuickJS tool ${toolLabel(toolName)}returned an envelope without `content` — " +
        "the handler must return `{ content: [...] }` (and optionally `isError: true`).",
    )
  }
  val rendered = envelope.renderContent()
  return TrailblazeToolResult.Success(message = rendered.ifBlank { null })
}

private fun toolLabel(toolName: String?): String = toolName?.let { "'$it' " }.orEmpty()

/**
 * Concatenates every text-typed content part with newlines. Non-text parts surface as a
 * `<type content>` placeholder so the LLM sees that *something* was returned. Mirrors
 * the previous JsonObject-based renderer; the only change is that the parts are now
 * typed [QuickJsContentPart]s rather than ad-hoc JsonObject lookups.
 */
private fun QuickJsToolResultEnvelope.renderContent(): String =
  content.joinToString(separator = "\n") { part ->
    if (part.type == "text") part.text.orEmpty() else "<${part.type} content>"
  }
