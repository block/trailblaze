package xyz.block.trailblaze.host.networkcapture

import com.microsoft.playwright.BrowserContext
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Network capture for the web browsers a multi-device session binds as devices.
 *
 * A single-device web run captures through its own test rule, which owns the browser for the run.
 * A web device bound beside an Android one has no such owner: the run only borrows the browser (a
 * daemon slot can outlive the session), so nothing would stop its capture when the session ends,
 * and the next session's capture would silently go on writing into this one's directory. This
 * object pairs each start with a stop at the session-end barrier, `finalizeHostSessionResources`.
 *
 * Each browser writes `events/network.<deviceLabel>.ndjson` (see [WebNetworkCapture.ndjsonFileFor]),
 * so its traffic is attributed to the device it came from.
 */
object WebCompanionNetworkCapture {

  private class Armed(
    val pageManager: PlaywrightPageManager,
    val context: BrowserContext,
    val capture: WebNetworkCapture,
  )

  private val lock = Any()
  private val armedBySession = HashMap<String, MutableList<Armed>>()

  /**
   * Sessions [stop] has ended. Attaching hops to the Playwright thread, so a session can end while
   * its capture is still attaching; the late attach finds its session here and detaches itself
   * instead of writing into an ended session. Bounded: only a start racing its own session's end
   * needs the entry.
   */
  private val endedSessions = LinkedHashSet<String>()

  /** Starts capture on [pageManager]'s browser for [sessionId]. Idempotent per browser. */
  fun start(
    sessionId: String,
    sessionDir: File,
    pageManager: PlaywrightPageManager,
    deviceLabel: String,
  ) {
    // Bridged: `currentPage` can lazily create the page, and listener registration sends wire
    // messages — both are Playwright API calls, which must run on its thread.
    val armed = pageManager.onPlaywrightThread {
      val context = pageManager.currentPage.context()
      val capture = WebNetworkCapture.start(
        ctx = context,
        sessionId = sessionId,
        sessionDir = sessionDir,
        deviceLabel = deviceLabel,
      )
      Armed(pageManager, context, capture)
    }
    val sessionEnded = synchronized(lock) {
      if (sessionId in endedSessions) {
        true
      } else {
        val armedForSession = armedBySession.getOrPut(sessionId) { mutableListOf() }
        if (armedForSession.none { it.capture === armed.capture }) armedForSession += armed
        false
      }
    }
    if (sessionEnded) {
      Console.log(
        "[WebCompanionNetworkCapture] session $sessionId ended while '$deviceLabel' was " +
          "attaching; detaching its capture."
      )
      stopArmed(armed)
    }
  }

  /**
   * Stops every browser [start] armed for [sessionId], flushing their files. Idempotent. Every
   * browser is attempted before the first failure is rethrown, so one closed browser cannot leave
   * another's capture writing. A browser another session has since taken over keeps capturing for
   * that session.
   */
  fun stop(sessionId: String) {
    val armed = synchronized(lock) {
      endedSessions += sessionId
      while (endedSessions.size > MAX_ENDED_SESSIONS) endedSessions.remove(endedSessions.first())
      armedBySession.remove(sessionId).orEmpty()
    }
    val failures = armed.mapNotNull { runCatching { stopArmed(it) }.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
      failures.drop(1).forEach(first::addSuppressed)
      throw first
    }
  }

  private fun stopArmed(armed: Armed) {
    armed.pageManager.onPlaywrightThread { WebNetworkCapture.stop(armed.context, armed.capture) }
  }

  private const val MAX_ENDED_SESSIONS = 256
}
