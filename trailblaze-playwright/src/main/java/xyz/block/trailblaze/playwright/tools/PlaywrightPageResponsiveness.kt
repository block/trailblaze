package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError

/**
 * Makes a web assertion fail fast on a page that has stopped responding, instead of waiting it out.
 *
 * Playwright's `expect()` (`assertThat(...)`) makes one check with no time limit before its timed
 * retries start. When the page's main thread is blocked, that check waits for as long as the block
 * lasts: a `web_verifyTextVisible` with the default 5s timeout was observed waiting 1,000s while a
 * page sat frozen, then passing. `waitForFunction` does honor its timeout, so a trivial script run
 * through it answers first, and the assertion only starts on a page that can answer it.
 *
 * The probe gets a larger budget than the assertion's 5s. The untimed check means a page that stalls
 * for longer than 5s, with the text already on it, used to pass once the stall ended. A budget at the
 * assertion's own 5s would fail those runs, whereas [PROBE_TIMEOUT_MS] still bounds a real freeze.
 */
internal object PlaywrightPageResponsiveness {

  /** Well above Playwright's 5s assertion timeout, so a brief stall still passes; see the class doc. */
  const val PROBE_TIMEOUT_MS = 30_000.0

  /** Throws [PageUnresponsiveException] if [page] does not run a trivial script within [timeoutMs]. */
  fun requireResponsive(page: Page, timeoutMs: Double = PROBE_TIMEOUT_MS) {
    try {
      page.waitForFunction("() => true", null, Page.WaitForFunctionOptions().setTimeout(timeoutMs)).dispose()
    } catch (_: TimeoutError) {
      throw PageUnresponsiveException(timeoutMs)
    }
  }
}

internal class PageUnresponsiveException(timeoutMs: Double) : RuntimeException(
  "The page stopped responding: it ran no script for ${(timeoutMs / 1000).toInt()}s, so the check " +
    "could not run. Its main thread is blocked by a long-running script or a stalled renderer, or a " +
    "navigation has not finished.",
)
