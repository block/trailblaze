package xyz.block.trailblaze.host

import com.microsoft.playwright.Page
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail

/**
 * A [PlaywrightPageManager] that does nothing, for tests that need one to exist but never drive a
 * browser through it — cache-reuse resolution, and adopting an "existing" browser so constructing
 * a `BasePlaywrightNativeTest` doesn't launch Chromium.
 *
 * Every screen-state accessor throws rather than returning a fixture: a test that reaches one has
 * strayed past what this stub can honestly stand in for, and should say so loudly.
 */
internal class NoOpPageManager : PlaywrightPageManager {
  override val currentPage: Page get() = error("not used in this test")
  override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
  override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()
  override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
  override fun getScreenState(): ScreenState = error("not used in this test")
  override fun captureScreenStateForLogging(): ScreenState = error("not used in this test")
  override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
  override fun resetSession() = Unit
  override fun close() = Unit
}
