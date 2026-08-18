package xyz.block.trailblaze.mcp.agent

import xyz.block.trailblaze.api.ScreenState

/**
 * Captures the device screen **at most once per LLM request** and shares that one snapshot among the
 * decorators that need it within the same request — the [ScreenshotAttachingLlmClient] (which attaches
 * the annotated screenshot) and the [LoggingLlmClient] (which records the screen + image-token
 * breakdown). Without this, each decorator calls its own `screenStateProvider` and the screen is
 * captured twice per tool-calling turn — and on the on-device RPC driver each capture is a full
 * device round-trip (Bitmap pull + encode), so a multi-turn objective pays for double the captures.
 *
 * Usage: wire both decorators to [asProvider]; the OUTERMOST decorator calls [clear] at the END of
 * every `execute()` (in a `finally`). Whoever touches the provider first in a request captures (via
 * [source]); later touches in the same request reuse the snapshot; [clear] then releases it so the
 * next request re-captures. Clearing at request end (rather than start) does double duty: it keeps
 * each request's snapshot fresh AND doesn't retain the captured [ScreenState] (screenshot bytes +
 * view hierarchy) in the idle gap between requests or after the final request.
 *
 * Not thread-safe by design — Koog's strategy graph dispatches LLM requests serially within a single
 * `run()`, so there is never more than one `execute()` in flight, and [clear] (called by the
 * outermost decorator after it returns) never races the reads it follows.
 */
internal class SharedScreenStateCapture(private val source: () -> ScreenState) {
  private var cached: ScreenState? = null

  /**
   * A `screenStateProvider` that captures once per request (on first access after the previous
   * request's [clear]) and returns that same snapshot to any later caller in the same request.
   */
  fun asProvider(): () -> ScreenState = { cached ?: source().also { cached = it } }

  /**
   * Release the cached snapshot. The outermost decorator calls this at the END of every `execute()`
   * (in a `finally`), so the captured [ScreenState] isn't retained between requests or after the last
   * one, and the next request necessarily re-captures (the screen changes as tools run between
   * requests, so a stale reuse would be wrong anyway).
   */
  fun clear() {
    cached = null
  }
}
