package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Hard, always-on regression test for https://github.com/block/trailblaze/issues/199 — a
 * DOM-heavy web page (thousands of nodes mixing headings, paragraphs, lists, links, and
 * buttons) used to hang `viewHierarchy` enrichment for 10+ minutes, because the pre-batching
 * per-node bounds resolution ran once per node with no shared wall-clock budget.
 *
 * Unlike `PlaywrightBoundsEnrichmentBenchmarkTest` (opt-in, prints timing/completeness
 * numbers for manual comparison across strategies), this test is NOT gated behind a system
 * property — it runs in every plain `./gradlew :trailblaze-playwright:test`, specifically so
 * a future change to the shared-deadline logic, the fast-path gate, or the ref-resolution
 * budget can't silently reintroduce an unbounded hang without breaking CI.
 *
 * The wall-clock bound here (30s) is deliberately generous relative to
 * `PlaywrightScreenState.ENRICHMENT_BUDGET_MS` (5s) — this is a hang guard, not a
 * performance benchmark, so it must tolerate slower CI hardware without becoming flaky.
 * 30s is still two orders of magnitude below the original 10+ minute bug.
 */
class PlaywrightScreenStateEnrichmentBudgetTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  @Test
  fun `viewHierarchy enrichment on a large mixed-content page completes well within the hang-guard bound`() {
    // Mirrors the shape that exposed the original bug: mixed tags (not dominated by one
    // well-covered tag like <button>), so the fast path alone can't resolve everything and
    // the ref-based / per-node fallback paths both see real work.
    val html = buildString {
      append("<!DOCTYPE html><html><body>")
      for (i in 1..1500) {
        append("<h2>Heading $i</h2>")
        append("<a href=\"#item$i\">Link $i</a>")
        append("<p>Paragraph text number $i with some content.</p>")
        append("<button>Button $i</button>")
      }
      append("</body></html>")
    }

    val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 800))
    val page = context.newPage()
    page.setContent(html)

    val start = System.currentTimeMillis()
    val tree = PlaywrightScreenState(page, 1280, 800).viewHierarchy
    val elapsed = System.currentTimeMillis() - start

    assertTrue(
      elapsed < 30_000,
      "viewHierarchy enrichment took ${elapsed}ms on a ~6000-node mixed-content page — " +
        "this is the hang guard for https://github.com/block/trailblaze/issues/199; " +
        "30s is already two orders of magnitude below the original 10+ minute bug",
    )

    // Loose completeness floor: catches a DIFFERENT regression (the mechanism completing
    // fast but silently resolving nothing) without pinning an exact percentage that would
    // make this test brittle against implementation changes.
    val allNodes = tree.aggregate()
    val boundedCount = allNodes.count { it.bounds != null }
    assertTrue(
      boundedCount > allNodes.size / 4,
      "Only $boundedCount/${allNodes.size} nodes got bounds — expected at least a quarter " +
        "resolved on a page this size; this may indicate the enrichment pipeline is " +
        "silently failing rather than genuinely hitting the wall-clock budget",
    )

    context.close()
  }
}
