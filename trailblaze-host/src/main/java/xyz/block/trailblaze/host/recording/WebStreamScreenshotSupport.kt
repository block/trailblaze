package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.StreamScreenshotMode
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.util.Console

/**
 * Web wiring for [DeviceStreamScreenshotSource]: wraps a Playwright screen-state provider so
 * agent-loop screenshots are served from the live CDP screencast instead of a per-turn
 * `page.screenshot()`. Lives host-side (not on `PlaywrightTrailblazeAgent`) because the
 * stream-screenshot core does; the agent just accepts the wrapped provider.
 *
 * Mode comes from [StreamScreenshotMode.resolveWeb] (the `TRAILBLAZE_WEB_STREAM_SCREENSHOT[_AB]`
 * env vars or the shared `stream-screenshots` config toggle), read once at construction. In OFF
 * mode [screenStateProvider] is the delegate untouched.
 *
 * Web trees ([ScreenState.viewHierarchy], the ARIA snapshot) are captured host-side, so the
 * tree clock is the host clock (offset 0). The delegate's members are lazy — the wrapper
 * forces the tree capture and stamps host time when it's final, then pairs that stamp with a
 * screencast frame. `PlaywrightScreenState.screenshotBytes` is also lazy, which makes STREAM
 * mode's economics automatic: a matched frame means the delegate's `page.screenshot()` is
 * simply never invoked, and on fallback the lazy screenshot serves the LLM turn with no
 * re-capture.
 *
 * Lifecycle: the screencast subscription starts lazily on the first stream/AB capture;
 * [close] tears it down. One instance per (test rule, browser) session.
 */
class WebStreamScreenshotSupport(
  private val deviceId: TrailblazeDeviceId,
  private val pageManager: PlaywrightPageManager,
  private val delegateProvider: () -> ScreenState,
) : AutoCloseable {

  private val mode = StreamScreenshotMode.resolveWeb()

  /** Lazily started on the first capture; null until then, or when stream mode is off/failed. */
  @Volatile private var streamScreenshotSource: DeviceStreamScreenshotSource? = null

  /** Sticky: once the screencast fails to start we stop retrying for the session. */
  @Volatile private var streamSourceUnavailable = false

  /** The provider to hand the runner/agent: identical to the delegate when stream mode is OFF. */
  val screenStateProvider: () -> ScreenState =
    if (mode == StreamScreenshotMode.OFF) delegateProvider else ({ captureScreenState() })

  private fun captureScreenState(): ScreenState {
    val source = ensureStreamSource() ?: return delegateProvider()

    val base = delegateProvider()
    // The delegate is lazy — force the ARIA tree so the host stamp below marks when the tree
    // content was actually read from the page, not when some later consumer first touches it.
    base.viewHierarchy
    val treeCapturedAtHostMs = System.currentTimeMillis()
    val result = runBlocking {
      source.awaitFrameMatching(treeCapturedAtHostMs, STREAM_FRAME_TIMEOUT_MS)
    }
    return when (mode) {
      StreamScreenshotMode.AB_COMPARE -> {
        // page.screenshot() stays authoritative; log enough per capture to judge the stream
        // path's viability (match rate, skew, sizes) from a normal run.
        when (result) {
          is StreamFrameMonitor.Result.Matched -> Console.log(
            "[stream-screenshot] AB matched: skewMs=${result.frameVsTreeSkewMs} " +
              "streamBytes=${result.jpegBytes.size} " +
              "pageScreenshotBytes=${base.screenshotBytes?.size} treeTs=$treeCapturedAtHostMs",
          )
          is StreamFrameMonitor.Result.Unavailable -> Console.log(
            "[stream-screenshot] AB unmatched: ${result.reason} treeTs=$treeCapturedAtHostMs",
          )
        }
        base
      }
      StreamScreenshotMode.STREAM -> when (result) {
        is StreamFrameMonitor.Result.Matched -> {
          Console.log(
            "[stream-screenshot] matched: skewMs=${result.frameVsTreeSkewMs} " +
              "bytes=${result.jpegBytes.size} treeTs=$treeCapturedAtHostMs",
          )
          StreamScreenshotScreenState(delegate = base, streamJpegBytes = result.jpegBytes)
        }
        is StreamFrameMonitor.Result.Unavailable -> {
          Console.log(
            "[stream-screenshot] unmatched (${result.reason}) — falling back to page.screenshot()",
          )
          base // lazy delegate screenshot serves the turn; no re-capture needed
        }
      }
      StreamScreenshotMode.OFF -> base // unreachable — provider short-circuits OFF
    }
  }

  private fun ensureStreamSource(): DeviceStreamScreenshotSource? {
    if (streamSourceUnavailable) return null
    streamScreenshotSource?.let { return it }
    val created = DeviceStreamScreenshotSource.forWeb(
      deviceId = deviceId,
      stream = PlaywrightDeviceScreenStream(pageManager),
    )
    return try {
      if (created.start()) {
        streamScreenshotSource = created
        created
      } else {
        runCatching { created.close() }
        streamSourceUnavailable = true
        null
      }
    } catch (e: Exception) {
      Console.log("[stream-screenshot] failed to start web screencast source: ${e.message}")
      runCatching { created.close() }
      streamSourceUnavailable = true
      null
    }
  }

  override fun close() {
    streamScreenshotSource?.let {
      streamScreenshotSource = null
      runCatching { it.close() }
    }
  }

  private companion object {
    const val STREAM_FRAME_TIMEOUT_MS = 2_500L
  }
}
