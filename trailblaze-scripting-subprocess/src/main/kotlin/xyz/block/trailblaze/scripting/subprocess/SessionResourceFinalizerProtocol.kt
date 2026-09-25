package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Wire contract for process-local scripted-tool session resources.
 *
 * The TypeScript SDK advertises a hidden tool bearing [TOOL_NAME] and
 * `trailblaze/sessionResourceFinalizer: true`. A tool can retain an opaque handle in a callback
 * registered with `ctx.session.registerCleanup`; the host invokes this endpoint before closing the
 * process so the handle never crosses into `AgentMemory`, an MCP tool result, or session logs.
 */
internal object SessionResourceFinalizerProtocol {
  const val TOOL_NAME: String = "trailblaze_releaseSessionResources"
  suspend fun finalize(
    session: McpSubprocessSession,
    sessionId: SessionId,
    callbackContext: SubprocessToolRegistration.JsScriptingCallbackContext?,
  ) {
    // The original tool invocation is already closed during teardown. Give the hidden finalizer
    // a fresh registry handle so a cleanup that calls `ctx.tools` resolves through a live host
    // context instead of being rejected as a stale invocation.
    val invocation = callbackContext?.openFinalizerInvocation(sessionId)
    try {
      val response = session.client.callTool(
        CallToolRequest(
          params = CallToolRequestParams(
            name = TOOL_NAME,
            arguments = buildJsonObject { put("sessionId", JsonPrimitive(sessionId.value)) },
            meta = invocation?.requestMeta,
          ),
        ),
      )
      // The SDK intentionally redacts the callback error because it can include an opaque
      // credential. Preserve only that the contract failed, never its content/error payload.
      check(response.isError != true) {
        "Scripted subprocess session-resource cleanup reported a failure."
      }
    } finally {
      invocation?.handle?.close()
    }
  }
}
