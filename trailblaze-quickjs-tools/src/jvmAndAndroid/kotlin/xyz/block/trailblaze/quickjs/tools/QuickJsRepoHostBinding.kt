package xyz.block.trailblaze.quickjs.tools

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console

/**
 * Default [HostBinding] installed on every [QuickJsToolHost] launched by
 * [QuickJsToolBundleLauncher]. Surfaces a structured "not yet wired" envelope when a
 * bundled handler calls `trailblaze.call(...)` so the awaiting JS handler sees a
 * well-formed [TrailblazeToolResult] instead of a cryptic JSON parse error.
 *
 * ### Why no live cross-tool dispatch yet
 *
 * Two distinct hops are needed to wire `trailblaze.call(...)` end to end:
 *
 *  1. **QuickJS → QuickJS** (same engine, same bundle or another bundle in the same host).
 *     Naively re-entering [QuickJsToolHost.callTool] from this binding deadlocks: the
 *     outer call holds the host's `evalMutex` while the inner waits to acquire the same
 *     non-reentrant mutex. A non-locking re-entry path would also clobber
 *     `__trailblazeLastResult` (the global the outer uses to read its result back).
 *
 *  2. **QuickJS → host-side tool** (Maestro / Playwright / subprocess MCP). Needs a
 *     [TrailblazeToolExecutionContext] (screen state, agent, etc.) — not available from
 *     the `(name, argsJson)` binding signature without coroutine-context plumbing the
 *     `quickjs-kt` async-function continuation doesn't reliably preserve.
 *
 * Returning a structured error here keeps tool authors who experiment with
 * `trailblaze.call(...)` from getting an opaque transport error or a deadlock; they see a
 * well-formed `TrailblazeToolResult.isError = true` envelope with a clear message. Real
 * cross-tool composition is a follow-up — see the rollout in the module README.
 */
// `toolRepo` is held but not yet read — the cross-tool dispatch path (see module README)
// will use it. `sessionId` shows up in the failure log so device-farm log greps can
// correlate composition attempts with their session.
internal class QuickJsRepoHostBinding(
  @Suppress("UNUSED_PARAMETER") private val toolRepo: TrailblazeToolRepo,
  private val sessionId: SessionId,
) : HostBinding {

  override suspend fun callFromBundle(name: String, argsJson: String): String {
    Console.log(
      "[QuickJsRepoHostBinding] CALL_NOT_WIRED tool=$name session=${sessionId.value}",
    )
    return errorEnvelope(
      "trailblaze.call('$name'): cross-tool composition is not yet wired in this runtime.",
    )
  }

  /**
   * Error envelope shaped to match the SDK's `TrailblazeToolResult` so the awaiting
   * handler sees a well-formed response on the JS side. Built from a typed
   * [QuickJsToolResultEnvelope] data class so the field names live in [QuickJsToolEnvelopes]
   * and a typo here is a Kotlin compile error rather than a silently malformed envelope.
   * Encoding via `kotlinx.serialization` keeps quote-laden error messages safe — the
   * serializer escapes them into valid JSON.
   */
  private fun errorEnvelope(message: String): String {
    val envelope = QuickJsToolResultEnvelope(
      content = listOf(QuickJsContentPart(type = "text", text = message)),
      isError = true,
    )
    return QuickJsToolEnvelopeJson.encodeToString(
      QuickJsToolResultEnvelope.serializer(),
      envelope,
    )
  }
}
