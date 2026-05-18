package xyz.block.trailblaze.playwright

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Route
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * Verifies that [PlaywrightBrowserManager.resetSession] gives the next session true
 * Playwright-`newContext`-level isolation — not just `clearCookies()`.
 *
 * The concrete contract: storage written into the page before `resetSession()`
 * (cookies, localStorage, sessionStorage) must be invisible after the reset, and
 * the underlying `BrowserContext` reference must have actually been swapped.
 */
class PlaywrightBrowserManagerResetSessionTest {

  private lateinit var manager: PlaywrightBrowserManager

  @After
  fun tearDown() {
    if (::manager.isInitialized) {
      manager.close()
    }
  }

  @Test
  fun resetSession_wipesLocalStorageAndCookies_andSwapsBrowserContext() = runBlocking {
    manager = PlaywrightBrowserManager(headless = true)

    // Real https origin so the browser permits storage APIs — `data:` URLs and `about:blank`
    // disable localStorage with a SecurityError. The route() interception is reapplied after
    // resetSession() because the BrowserContext is replaced.
    val originUrl = "https://test.trailblaze.invalid/"
    val htmlHandler: (Route) -> Unit = { route ->
      route.fulfill(
        Route.FulfillOptions()
          .setStatus(200)
          .setContentType("text/html")
          .setBody("<html><body>page</body></html>"),
      )
    }

    withContext(manager.playwrightDispatcher) {
      manager.currentPage.context().route("**/*", htmlHandler)
      manager.currentPage.navigate(originUrl)
      manager.currentPage.evaluate(
        """() => {
          localStorage.setItem('tb-auth-token', 'session-1-secret');
          sessionStorage.setItem('tb-session-key', 'session-1-key');
          document.cookie = 'tb-cookie=session-1-cookie';
        }""",
      )
      assertEquals(
        "session-1-secret",
        manager.currentPage.evaluate("() => localStorage.getItem('tb-auth-token')"),
      )
      assertEquals(
        "tb-cookie=session-1-cookie",
        manager.currentPage.evaluate("() => document.cookie"),
      )
    }

    val contextBeforeReset: BrowserContext = withContext(manager.playwrightDispatcher) {
      manager.currentPage.context()
    }

    withContext(manager.playwrightDispatcher) {
      manager.resetSession()
    }

    val contextAfterReset: BrowserContext = withContext(manager.playwrightDispatcher) {
      manager.currentPage.context()
    }
    assertNotSame(
      contextBeforeReset,
      contextAfterReset,
      "resetSession() must swap the BrowserContext; reusing the same context " +
        "would leave localStorage/IndexedDB intact even after clearCookies().",
    )

    withContext(manager.playwrightDispatcher) {
      manager.currentPage.context().route("**/*", htmlHandler)
      manager.currentPage.navigate(originUrl)
      assertNull(
        manager.currentPage.evaluate("() => localStorage.getItem('tb-auth-token')"),
        "localStorage must be empty after resetSession()",
      )
      assertNull(
        manager.currentPage.evaluate("() => sessionStorage.getItem('tb-session-key')"),
        "sessionStorage must be empty after resetSession()",
      )
      assertEquals(
        "",
        manager.currentPage.evaluate("() => document.cookie"),
        "Cookies must be empty after resetSession()",
      )
    }
  }

}
