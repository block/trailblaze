package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Page
import kotlinx.coroutines.CoroutineDispatcher
import xyz.block.trailblaze.api.ScreenState

/**
 * Abstraction over Playwright page lifecycle used by [PlaywrightTrailblazeAgent] and test
 * infrastructure classes.
 *
 * Two implementations exist:
 * - [PlaywrightBrowserManager]: launches a fresh Chromium browser.
 * - [PlaywrightElectronBrowserManager]: connects to an Electron app via CDP.
 */
interface PlaywrightPageManager : AutoCloseable {
  val currentPage: Page
  val playwrightDispatcher: CoroutineDispatcher
  val idlingConfig: PlaywrightNativeIdlingConfig
  fun requestDetails(details: Set<ViewHierarchyDetail>)
  fun getScreenState(): ScreenState
  fun captureScreenStateForLogging(): ScreenState
  fun waitForPageReady(
    domStabilityTimeoutMs: Double = PlaywrightBrowserManager.DEFAULT_DOM_STABILITY_TIMEOUT_MS,
  )
  fun resetSession()
}
