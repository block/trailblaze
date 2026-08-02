package xyz.block.trailblaze.playwright.recording

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.block.trailblaze.capture.video.WebScreencastFeedRegistry
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.util.Console

/**
 * A single fanned-out CDP screencast for one Playwright page manager — the web analog of Android's
 * `H264Tee`. One [PlaywrightDeviceScreenStream.streamScreencastJpegFrames] pump feeds every
 * subscriber (today the session-video recorder via [WebScreencastFeedRegistry]; the `/devices`
 * viewer and stream-sourced screenshots are candidates to migrate onto it), so recording a
 * session's video costs one screencast rather than a second encoder.
 *
 * ### Lifecycle
 * The screencast is **lazy and reference-counted**: the pump starts when the first subscriber
 * attaches and stops when the last detaches. A manager can register this feed unconditionally at
 * construction (see [PlaywrightBrowserManager]) without paying for a screencast until something
 * actually records.
 *
 * ### Page-follow / resilience
 * [PlaywrightDeviceScreenStream.streamScreencastJpegFrames] binds to `currentPage` and fails its
 * flow when that page/context is torn down (a closed popup, a `resetSession` context swap). An
 * in-page navigation does *not* tear the page down, so it survives those transparently; for the
 * teardown cases the pump loop here re-invokes the stream after a short backoff, re-binding to the
 * new `currentPage`. That keeps one continuous recording across the whole session even though the
 * underlying CDP session is page-scoped.
 *
 * Frames are delivered to subscribers on this feed's own IO scope — off the Playwright pump thread
 * — so a slow subscriber (a disk write in the recorder) can't stall the screencast or contend with
 * taps/navigation on the single Playwright dispatcher thread.
 */
class PlaywrightScreencastFeed(
  private val pageManager: PlaywrightPageManager,
) : WebScreencastFeedRegistry.Feed {

  private val subscribers = CopyOnWriteArrayList<(ByteArray, Long) -> Unit>()
  private val lock = Any()
  private var scope: CoroutineScope? = null
  private var pumpJob: Job? = null

  override fun subscribe(onFrame: (jpeg: ByteArray, hostTimestampMs: Long) -> Unit): AutoCloseable {
    synchronized(lock) {
      subscribers.add(onFrame)
      if (pumpJob == null) startPump()
    }
    return AutoCloseable {
      synchronized(lock) {
        subscribers.remove(onFrame)
        if (subscribers.isEmpty()) stopPump()
      }
    }
  }

  private fun startPump() {
    val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = newScope
    pumpJob = newScope.launch {
      // Re-attach across page/context teardown (popup close, resetSession). An in-page navigate
      // never lands here — the stream keeps running. Stops when the scope is cancelled (last
      // subscriber left) via the isActive guard.
      while (isActive) {
        val stream = PlaywrightDeviceScreenStream(pageManager)
        try {
          stream.streamScreencastJpegFrames { jpeg ->
            val ts = System.currentTimeMillis()
            // trySend-style fan-out: a subscriber's failure never kills the pump or its peers.
            subscribers.forEach { runCatching { it(jpeg, ts) } }
          }
        } catch (e: CancellationException) {
          // Normal shutdown — the last subscriber detached and the scope was cancelled. Not an
          // error; rethrow so the coroutine unwinds instead of looping/logging noisily.
          throw e
        } catch (e: Exception) {
          // The page/context went away, or the CDP session died. Re-bind to the current page.
          Console.log("[PlaywrightScreencastFeed] screencast ended (${e.message}); re-attaching")
        }
        if (!isActive) break
        delay(REATTACH_BACKOFF_MS)
      }
    }
  }

  private fun stopPump() {
    scope?.cancel()
    scope = null
    pumpJob = null
  }

  private companion object {
    /** Backoff before re-binding the screencast to a new page after a teardown. */
    const val REATTACH_BACKOFF_MS = 200L
  }
}
