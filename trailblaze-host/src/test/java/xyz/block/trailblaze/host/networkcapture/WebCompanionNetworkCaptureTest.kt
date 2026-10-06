package xyz.block.trailblaze.host.networkcapture

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.host.capture.finalizeHostSessionResources
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A web device in a multi-device session borrows its browser, so its capture is stopped by the
 * session-end barrier rather than by a test rule that owns the browser.
 */
class WebCompanionNetworkCaptureTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `the session-end barrier stops the capture a web device started`() {
    val browser = FakeBrowser()
    val sessionDir = tmp.newFolder()

    WebCompanionNetworkCapture.start("session-a", sessionDir, browser.pageManager, "dashboard")
    assertTrue(File(sessionDir, "events/network.dashboard.ndjson").exists())
    assertEquals(1, browser.requestListeners)

    finalizeHostSessionResources(listOf(SessionId("session-a"))) {}

    assertEquals(0, browser.requestListeners, "the browser must stop writing into the ended session")
  }

  @Test
  fun `ending one session leaves another session's browser capturing`() {
    val ended = FakeBrowser()
    val running = FakeBrowser()
    WebCompanionNetworkCapture.start("session-ended", tmp.newFolder(), ended.pageManager, "dashboard")
    WebCompanionNetworkCapture.start("session-running", tmp.newFolder(), running.pageManager, "dashboard")

    finalizeHostSessionResources(listOf(SessionId("session-ended"))) {}

    assertEquals(0, ended.requestListeners)
    assertEquals(1, running.requestListeners)
    WebCompanionNetworkCapture.stop("session-running")
  }

  @Test
  fun `ending a session leaves the session that took its browser over capturing`() {
    val shared = FakeBrowser()
    WebCompanionNetworkCapture.start("session-older", tmp.newFolder(), shared.pageManager, "dashboard")
    WebCompanionNetworkCapture.start("session-newer", tmp.newFolder(), shared.pageManager, "dashboard")
    assertEquals(1, shared.requestListeners)

    finalizeHostSessionResources(listOf(SessionId("session-older"))) {}

    assertEquals(1, shared.requestListeners, "the newer session's stream must keep recording")
    WebCompanionNetworkCapture.stop("session-newer")
    assertEquals(0, shared.requestListeners)
  }

  @Test
  fun `a capture that finishes attaching after its session ended detaches itself`() {
    val browser = FakeBrowser()
    // Attaching hops to the Playwright thread, so the session can end before the attach lands.
    finalizeHostSessionResources(listOf(SessionId("session-late"))) {}

    WebCompanionNetworkCapture.start("session-late", tmp.newFolder(), browser.pageManager, "dashboard")

    assertEquals(0, browser.requestListeners, "an ended session must not go on capturing")
  }

  @Test
  fun `a browser that cannot be stopped does not fail the session`() {
    val crashed = FakeBrowser(failOnStop = true)
    WebCompanionNetworkCapture.start("session-crashed", tmp.newFolder(), crashed.pageManager, "dashboard")

    // Throws if the barrier counts the failure, which would turn a passed run into a failed one.
    finalizeHostSessionResources(listOf(SessionId("session-crashed"))) {}

    assertEquals(1, crashed.stopAttempts, "the barrier must still try to stop the browser")
  }

  /** A browser whose context counts the request listeners capture registers on it. */
  private class FakeBrowser(private val failOnStop: Boolean = false) {
    var requestListeners = 0
      private set
    var stopAttempts = 0
      private set

    private val context: BrowserContext =
      Proxy.newProxyInstance(BrowserContext::class.java.classLoader, arrayOf(BrowserContext::class.java)) { proxy, method, args ->
        when (method.name) {
          // Capture keys its registry on the context's identity.
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          "onRequest" -> { requestListeners++; null }
          "offRequest" -> {
            stopAttempts++
            if (failOnStop) error("Target page, context or browser has been closed")
            requestListeners--
            null
          }
          else -> null
        }
      } as BrowserContext

    private val page: Page =
      Proxy.newProxyInstance(Page::class.java.classLoader, arrayOf(Page::class.java)) { _, method, _ ->
        if (method.name == "context") context else null
      } as Page

    val pageManager: PlaywrightPageManager = object : PlaywrightPageManager {
      override val currentPage: Page get() = page
      override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
      override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()
      override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
      override fun getScreenState(): ScreenState = error("not used in this test")
      override fun captureScreenStateForLogging(): ScreenState = error("not used in this test")
      override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
      override fun resetSession() = Unit
      override fun close() = Unit
    }
  }
}
