package xyz.block.trailblaze.host

import com.microsoft.playwright.Page
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner.PlaywrightCacheResolution
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner.resolvePlaywrightCacheReuse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail

/**
 * Covers [resolvePlaywrightCacheReuse], which decides whether a cached
 * `BasePlaywrightNativeTest` is safe to reuse for an incoming run-yaml request.
 *
 * The motivating bug: prior to wiring this decision in, the eagerly-cached test
 * carried whichever LLM model was active at first browser launch and stuck around
 * for the daemon's lifetime — so `trailblaze config llm <other-provider>` after
 * the cache was warm silently kept running every web tool against the wrong client
 * (with the symptom "Unsupported provider id=&lt;configured&gt;" inside the
 * Playwright thread). The decision logic encoded here ensures the cached browser
 * is preserved (no relaunch + no page-state loss) while the test itself is
 * rebuilt against the now-current model.
 */
class PlaywrightCacheReuseTest {

  private val openaiGpt4 =
    TrailblazeLlmModel.fallback(TrailblazeLlmProvider.OPENAI, "gpt-4.1")
  private val anthropicSonnet =
    TrailblazeLlmModel.fallback(TrailblazeLlmProvider.ANTHROPIC, "claude-sonnet-4")

  private val fakeBrowserManager = NoOpPageManager()

  @Test
  fun `no cached test means fresh build`() {
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = null,
      cachedBrowserManager = null,
      requestedModel = openaiGpt4,
    )
    assertEquals(PlaywrightCacheResolution.NoCachedTest, resolution)
  }

  @Test
  fun `cached model matches requested model means reuse cached test`() {
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      requestedModel = openaiGpt4,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }

  @Test
  fun `cached model differs means rebuild around the cached browser`() {
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      requestedModel = anthropicSonnet,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    // Critical: the live browser is preserved across the rebuild so the page
    // state (URL, cookies, in-flight forms) survives the LLM-config switch.
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `provider differs but modelId matches still triggers rebuild`() {
    // Same modelId string under a different provider id is a different model —
    // data-class equality covers this, but pin it down so a future refactor that
    // narrows the comparison to modelId alone fails the test loudly.
    val mismatchedProvider = TrailblazeLlmModel.fallback(
      provider = TrailblazeLlmProvider(id = "custom", display = "Custom"),
      modelId = openaiGpt4.modelId,
    )
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      requestedModel = mismatchedProvider,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `cached model without a browser falls back to fresh build`() {
    // Defensive case: in practice the runner only calls this with both fields
    // populated together (cachedTest provides both), but the function is total
    // and shouldn't NPE or silently crash if a future caller invariants drift.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = null,
      requestedModel = anthropicSonnet,
    )
    assertEquals(PlaywrightCacheResolution.NoCachedTest, resolution)
  }

  @Test
  fun `match check is silent on null inputs - no NPE`() {
    // Same belt-and-suspenders: null cachedModel + non-null browser is malformed
    // input but must not crash.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = null,
      cachedBrowserManager = fakeBrowserManager,
      requestedModel = openaiGpt4,
    )
    assertTrue(resolution is PlaywrightCacheResolution.NoCachedTest)
  }

  /** Stub — these tests don't exercise any [PlaywrightPageManager] behaviour. */
  private class NoOpPageManager : PlaywrightPageManager {
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
}
