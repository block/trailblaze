package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.host.playwright.PlaywrightNativeHostDriverDescriptor.Companion.PlaywrightCacheResolution
import xyz.block.trailblaze.host.playwright.PlaywrightNativeHostDriverDescriptor.Companion.resolvePlaywrightCacheReuse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

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

  @get:Rule
  val tempFolder = TemporaryFolder()

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
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    assertEquals(PlaywrightCacheResolution.NoCachedTest, resolution)
  }

  @Test
  fun `cached model and max-llm-calls match request means reuse cached test`() {
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }

  @Test
  fun `cached model differs means rebuild around the cached browser`() {
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = anthropicSonnet,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    // Critical: the live browser is preserved across the rebuild so the page
    // state (URL, cookies, in-flight forms) survives the LLM-config switch.
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `max-llm-calls differs means rebuild around the cached browser`() {
    // The lazy TrailblazeRunner inside BasePlaywrightNativeTest bakes maxSteps at
    // construction time, so a cap change (e.g. cap=10 first run, cap=1 second run) needs
    // a fresh test instance — otherwise the second run silently inherits the first run's
    // cap. Preserve the browser so the page state isn't lost.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 10,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 1,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `null max-llm-calls vs explicit value also triggers rebuild`() {
    // A previously-unset cap becoming explicit (or vice versa) is the most common
    // real-world transition — user starts a daemon without the flag, then runs with
    // `--max-llm-calls 5`. Treat that as a rebuild so the new cap is honored.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 5,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
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
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = mismatchedProvider,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
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
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = anthropicSonnet,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
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
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    assertTrue(resolution is PlaywrightCacheResolution.NoCachedTest)
  }

  @Test
  fun `cached logs dir differing from the request means rebuild around the cached browser`() {
    // The regression this guards: the MCP bridge and the recording screen's device-connect
    // both populate this cache, and a test cached by either one carries its OWN logging rule.
    // If that rule sits at a different logs directory than the run is configured for, reusing
    // the test files the whole session in the wrong place — so rebuild, keeping the browser.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = tempFolder.newFolder("git-root-logs"),
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = tempFolder.newFolder("configured-logs"),
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `cached logs dir equal to the request means reuse cached test`() {
    // Anti-vacuity companion to the case above: matching directories must NOT force a
    // rebuild, or every interactive MCP step would relaunch its test instance.
    val configured = tempFolder.newFolder("configured-logs")
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = configured,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = configured,
      requestedNoLogging = false,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }

  @Test
  fun `a different spelling of the same logs dir means reuse cached test`() {
    // Compared by canonical path, so a `..`-bearing spelling of the same directory — which
    // is what a hand-edited `logsDirectory` setting can produce — doesn't churn the cache.
    val configured = tempFolder.newFolder("parent", "configured-logs")
    val sibling = tempFolder.newFolder("parent", "sibling")
    val indirect = File(sibling, "../configured-logs")
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = null,
      cachedLogsDir = configured,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = null,
      requestedLogsDir = indirect,
      requestedNoLogging = false,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }

  @Test
  fun `a request with no logs dir accepts any cached one`() {
    // Null request means "no opinion" — the JUnit and no-app-config callers let the rule
    // resolve its own default, so a cached test must still be reusable for them.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = null,
      cachedLogsDir = tempFolder.newFolder("some-logs"),
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = null,
      requestedLogsDir = null,
      requestedNoLogging = false,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }

  @Test
  fun `a cached test with no logs dir cannot serve a request that has one`() {
    // Defensive: a live test's rule always resolves SOME directory, so the runner never
    // passes null here today. Pin the safe answer anyway — an unknown cached directory can't
    // be assumed to be the configured one — so the function stays total.
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = null,
      cachedLogsDir = null,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = null,
      requestedLogsDir = tempFolder.newFolder("configured-logs"),
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `a no-logging request cannot reuse a test cached with logging on`() {
    // Same class of regression as the logs-dir case: the MCP bridge and the recording screen's
    // device-connect populate this cache with logging ON, so reusing such a test for a
    // `--no-logging` run would write the session files the run asked to suppress.
    val configured = tempFolder.newFolder("configured-logs")
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = configured,
      cachedNoLogging = false,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = configured,
      requestedNoLogging = true,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `a logging request cannot reuse a test cached with no-logging`() {
    // The other direction: a test cached by a `--no-logging` run carries a read-only LogsRepo,
    // so reusing it for a normal run would silently drop that run's session files.
    val configured = tempFolder.newFolder("configured-logs")
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = configured,
      cachedNoLogging = true,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = configured,
      requestedNoLogging = false,
    )
    val rebuild = assertIs<PlaywrightCacheResolution.RebuildWithCachedBrowser>(resolution)
    assertSame(fakeBrowserManager, rebuild.browser)
  }

  @Test
  fun `a matching no-logging stance still reuses the cached test`() {
    // Anti-vacuity companion to both cases above: agreeing on no-logging must NOT churn the cache.
    val configured = tempFolder.newFolder("configured-logs")
    val resolution = resolvePlaywrightCacheReuse(
      cachedModel = openaiGpt4,
      cachedBrowserManager = fakeBrowserManager,
      cachedMaxLlmCalls = 25,
      cachedLogsDir = configured,
      cachedNoLogging = true,
      requestedModel = openaiGpt4,
      requestedMaxLlmCalls = 25,
      requestedLogsDir = configured,
      requestedNoLogging = true,
    )
    assertEquals(PlaywrightCacheResolution.ReuseCachedTest, resolution)
  }
}
