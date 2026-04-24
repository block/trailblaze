package xyz.block.trailblaze.scripting.callback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract for the `/scripting/callback` HTTP endpoint used by subprocess MCP servers to
 * call back into Trailblaze from inside a tool handler. JSON-first per the envelope-migration
 * devlog ([`2026-04-22-scripting-sdk-envelope-migration.md`](../../../../../../../../../docs/devlog/2026-04-22-scripting-sdk-envelope-migration.md))
 * — a proto contract is deliberately deferred until the action surface and language list grow
 * beyond what justifies the codegen pipeline.
 *
 * Versioned via [JsScriptingCallbackRequest.version]; breaking changes bump the version and the endpoint
 * dispatches by version (the whole `v1` shape is frozen post-ship).
 *
 * **Staging note.** The endpoint ships before any subprocess sends a [JsScriptingCallbackRequest] —
 * the TS SDK callback surface lands in a follow-up. This contract is here as the
 * foundation both sides build against.
 */
@Serializable
data class JsScriptingCallbackRequest(
  val version: Int = CURRENT_VERSION,
  /**
   * Session id the callback claims to belong to. The endpoint cross-checks this against the
   * live [JsScriptingInvocationRegistry] entry for [invocationId] — mismatch = `CallbackError`,
   * not silent dispatch against a different session. Required field in
   * v1 so the cross-check can't be silently skipped by a malformed client.
   */
  @SerialName("session_id") val sessionId: String,
  @SerialName("invocation_id") val invocationId: String,
  val action: JsScriptingCallbackAction,
) {
  companion object {
    const val CURRENT_VERSION: Int = 1
  }
}

/**
 * Discriminated union of actions a subprocess can request via a callback. Today only
 * [CallTool] exists — additional variants (tap, inputText, memorize, …) can be added when the
 * typed-commands surface gets filled out. Adding a variant is additive and does not bump
 * [JsScriptingCallbackRequest.version] as long as existing consumers remain valid.
 */
@Serializable
sealed interface JsScriptingCallbackAction {

  /**
   * Invoke a Trailblaze tool by name with JSON arguments. Mirrors `toolCallToTrailblazeTool`'s
   * shape on the Kotlin side so the callback endpoint can dispatch directly without re-parsing
   * per-tool argument schemas — tool schemas stay Kotlin-authoritative.
   */
  @Serializable
  @SerialName("call_tool")
  data class CallTool(
    @SerialName("tool_name") val toolName: String,
    @SerialName("arguments_json") val argumentsJson: String,
  ) : JsScriptingCallbackAction
}

/**
 * Response for [JsScriptingCallbackRequest]. Exactly one [JsScriptingCallbackResult] variant present.
 */
@Serializable
data class JsScriptingCallbackResponse(val result: JsScriptingCallbackResult)

@Serializable
sealed interface JsScriptingCallbackResult {

  /**
   * Result of a [JsScriptingCallbackAction.CallTool]. [success] reflects whether the tool returned
   * successfully (not whether the HTTP request succeeded); on failure [errorMessage] carries
   * a human-readable description. [textContent] is the tool's primary string output when it
   * produced one (empty string otherwise) so callers don't have to special-case null.
   */
  @Serializable
  @SerialName("call_tool_result")
  data class CallToolResult(
    val success: Boolean,
    @SerialName("text_content") val textContent: String = "",
    @SerialName("error_message") val errorMessage: String = "",
  ) : JsScriptingCallbackResult

  /**
   * Protocol-level error — the invocation could not be dispatched (unknown / stale
   * `invocation_id`, malformed request, etc.). Distinct from a [CallToolResult] with
   * `success=false`, which reports a tool that ran but failed.
   */
  @Serializable
  @SerialName("error")
  data class Error(val message: String) : JsScriptingCallbackResult
}
