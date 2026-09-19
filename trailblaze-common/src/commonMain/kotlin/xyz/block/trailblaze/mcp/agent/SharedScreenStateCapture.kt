package xyz.block.trailblaze.mcp.agent

import xyz.block.trailblaze.api.ScreenState

/**
 * Captures the device screen once per agent turn and shares that snapshot among tool dispatch and the
 * decorators for the following LLM request. The dispatcher reuses the request snapshot as its
 * pre-action state, then [captureFresh] replaces it with the post-action state. The
 * [ScreenshotAttachingLlmClient] and [LoggingLlmClient] both reuse that post-action state for the next
 * request.
 *
 * Usage: wire tool dispatch and both decorators to [asProvider]. After a device-mutating tool runs,
 * call [captureFresh] once; the next request then consumes that post-action state without another
 * device round-trip. Call [clear] when the agent run ends so screenshot bytes are not retained.
 *
 * Not thread-safe by design — Koog's strategy graph dispatches LLM requests serially within a single
 * `run()`, so there is never more than one `execute()` or tool dispatch in flight.
 */
internal class SharedScreenStateCapture(private val source: () -> ScreenState) {
  private var cached: ScreenState? = null

  /** Returns a provider that reuses its snapshot until [captureFresh] or [clear] is called. */
  fun asProvider(): () -> ScreenState = { cached ?: source().also { cached = it } }

  /** Capture the latest screen and make it the shared snapshot for subsequent consumers. */
  fun captureFresh(): ScreenState {
    cached = null
    return source().also { cached = it }
  }

  /**
   * Release the cached snapshot after the agent run so its screenshot bytes and view hierarchy are
   * not retained.
   */
  fun clear() {
    cached = null
  }
}
