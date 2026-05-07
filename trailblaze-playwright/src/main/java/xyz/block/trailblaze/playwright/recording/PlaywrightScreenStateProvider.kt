package xyz.block.trailblaze.playwright.recording

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeNodeMapper
import xyz.block.trailblaze.recording.ScreenStateProvider

/**
 * [ScreenStateProvider] for Playwright-backed devices. Captures the page's viewport
 * dimensions, screenshots, and ARIA-derived view hierarchy and packages them into the same
 * [GetScreenStateResponse] shape the Android RPC and iOS Maestro paths produce — so a single
 * recording client (or, eventually, a WASM browser surface) can speak one screen-state
 * contract regardless of platform.
 *
 * **trailblazeNodeTree is populated via [PlaywrightTrailblazeNodeMapper.mapWithBounds].**
 * The ARIA snapshot is structural-only, but `mapWithBounds` runs a single `page.evaluate()`
 * to capture `getBoundingClientRect()` for every visible DOM element and matches the
 * results back to the ARIA tree by role+name. The resulting tree carries
 * [DriverNodeDetail.Web][xyz.block.trailblaze.api.DriverNodeDetail.Web] details with
 * bounds, ARIA role/name, ARIA descriptor, CSS selector, and `data-testid` — exactly the
 * shape `TrailblazeNodeSelectorGenerator.resolveFromTap` needs to produce durable selectors
 * for recorded clicks.
 *
 * The DOM-walk hit-test (`PlaywrightDeviceScreenStream.resolveClickTargetAt`) is still the
 * primary `ref` source; the tree-backed selector path lights up the recording UI's selector
 * picker (Tune button) and gives recorded `web_click` calls a `nodeSelector` that survives
 * page reflows in the way a raw CSS `ref` does not.
 *
 * **pageContextSummary holds the URL.** The Web equivalent of an Android package + activity
 * label — gives a downstream LLM (or a human looking at a recording) a quick "what page was
 * this on" signal without having to parse the hierarchy.
 *
 * Page interaction goes through [PlaywrightPageManager.playwrightDispatcher] — Playwright's
 * page object is single-threaded and any cross-thread call throws.
 */
class PlaywrightScreenStateProvider(
  private val pageManager: PlaywrightPageManager,
) : ScreenStateProvider {

  override suspend fun getScreenState(includeScreenshot: Boolean): GetScreenStateResponse? {
    return withContext(pageManager.playwrightDispatcher) {
      try {
        val page = pageManager.currentPage
        val viewport = page.viewportSize()
        val ariaYaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
        val viewHierarchy = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(ariaYaml)
        val screenshotBase64: String? = if (includeScreenshot) {
          page.screenshot(
            Page.ScreenshotOptions()
              .setFullPage(false)
              .setAnimations(ScreenshotAnimations.DISABLED)
              .setTimeout(500.0),
          ).encodeBase64()
        } else {
          null
        }
        GetScreenStateResponse(
          viewHierarchy = viewHierarchy,
          screenshotBase64 = screenshotBase64,
          deviceWidth = viewport.width,
          deviceHeight = viewport.height,
          // ARIA snapshot is structural-only, but mapWithBounds enriches it with a single
          // page.evaluate() that pulls getBoundingClientRect() for every visible DOM element.
          // That gives us a tree the selector generator + selector-picker UI can consume —
          // same shape as Android/iOS, with `DriverNodeDetail.Web` carrying bounds and
          // ARIA/CSS identifiers.
          trailblazeNodeTree = PlaywrightTrailblazeNodeMapper.mapWithBounds(
            yaml = ariaYaml,
            page = page,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
          ),
          pageContextSummary = page.url() ?: "",
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        // Page is mid-navigation or screenshot timed out — same recovery posture as the
        // legacy `frames()` loop: skip the tick.
        null
      }
    }
  }
}
