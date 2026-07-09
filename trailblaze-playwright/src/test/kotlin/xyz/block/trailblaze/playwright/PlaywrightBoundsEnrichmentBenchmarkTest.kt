package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.AriaSnapshotMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Wall-clock + completeness benchmark for element-bounds resolution strategies on
 * DOM-heavy pages, following up on the batching fix for
 * https://github.com/block/trailblaze/issues/199.
 *
 * Compares three strategies for resolving bounding boxes across every node in a page's
 * ARIA-snapshot-derived tree:
 *  - OLD: one role/name Locator resolution per node (the pre-fix behavior; this is what
 *    caused the original 10+ minute hang).
 *  - CURRENT: [PlaywrightScreenState]'s production path — a single batched `page.evaluate`
 *    matching by computed-or-fallback role/name, with the OLD per-node path as a residual
 *    fallback for unmatched nodes.
 *  - PROPOSED: one `AriaSnapshotMode.AI` snapshot (mints a stable `[ref=eN]` per node) plus
 *    one `aria-ref=eN` Locator resolution per node. The `aria-ref` engine resolves via an
 *    O(1) map lookup server-side (no DOM rescan), unlike OLD/CURRENT's role/name matching,
 *    and is correct by construction (no string-matching ambiguity) — but still one
 *    round-trip per node, since Playwright's `_ariaRef` identity is confined to an isolated
 *    script world and isn't readable from a plain `page.evaluate()` batch call (verified in
 *    [AriaRefPrototypeTest]).
 *
 * All three strategies share the same wall-clock budget used in production
 * ([BUDGET_MS], matching `PlaywrightScreenState.ENRICHMENT_BUDGET_MS`) so the comparison
 * reflects what a real run would actually resolve, not an unbounded best case.
 *
 * Gated behind `-Dtrailblaze.playwright.runBenchmarks=true` — this module's tests aren't
 * wired into `:check` (see this module's build.gradle.kts), but the extra gate keeps a
 * plain `./gradlew :trailblaze-playwright:test` fast by default.
 */
class PlaywrightBoundsEnrichmentBenchmarkTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    assumeTrue(
      "Skipped by default - pass -Dtrailblaze.playwright.runBenchmarks=true to run",
      System.getProperty("trailblaze.playwright.runBenchmarks") == "true",
    )
    playwright = Playwright.create()
    browser = playwright.chromium().launch(
      BrowserType.LaunchOptions().setHeadless(true),
    )
    val context = browser.newContext(
      Browser.NewContextOptions().setViewportSize(1280, 800),
    )
    page = context.newPage()
  }

  @After
  fun tearDown() {
    if (::browser.isInitialized) browser.close()
    if (::playwright.isInitialized) playwright.close()
  }

  private fun allButtonHtml(count: Int) = buildString {
    append("<!DOCTYPE html><html><body>")
    for (i in 1..count) append("<button>Button $i</button>")
    append("</body></html>")
  }

  private fun headingsButtonsParagraphsHtml(units: Int) = buildString {
    append("<!DOCTYPE html><html><body>")
    for (i in 1..units) {
      append("<h2>Heading $i</h2><button>Button $i</button><p>Paragraph text number $i</p>")
    }
    append("</body></html>")
  }

  private fun headingsLinksButtonsTextHtml(units: Int) = buildString {
    append("<!DOCTYPE html><html><body>")
    for (i in 1..units) {
      append(
        "<h2>Heading $i</h2><a href=\"#item$i\">Link $i</a>" +
          "<button>Button $i</button> Adjacent text $i ",
      )
    }
    append("</body></html>")
  }

  private fun boundsDescriptorFor(role: String, name: String?): String = when {
    name != null && role == "text" -> "text: $name"
    name != null -> "$role \"$name\""
    else -> role
  }

  private fun countNodes(node: ViewHierarchyTreeNode): Int =
    1 + node.children.sumOf { countNodes(it) }

  private fun countBounded(node: ViewHierarchyTreeNode): Int =
    (if (node.bounds != null) 1 else 0) + node.children.sumOf { countBounded(it) }

  /** OLD strategy: one role/name Locator resolution per node, no batching, budget-capped. */
  private fun benchmarkOldPerNodeLocator(tree: ViewHierarchyTreeNode): Pair<Long, Int> {
    val descriptorOccurrences = mutableMapOf<String, Int>()
    val deadlineMs = System.currentTimeMillis() + BUDGET_MS
    var resolved = 0
    val start = System.currentTimeMillis()
    fun visit(node: ViewHierarchyTreeNode) {
      val role = node.className ?: "generic"
      val descriptor = boundsDescriptorFor(role, node.text)
      val nth = descriptorOccurrences.getOrDefault(descriptor, 0)
      descriptorOccurrences[descriptor] = nth + 1
      if (System.currentTimeMillis() <= deadlineMs) {
        try {
          val locator = PlaywrightAriaSnapshot.resolveElementRef(
            page,
            PlaywrightAriaSnapshot.ElementRef(descriptor, nth),
          )
          if (locator.count() > 0) {
            val box = locator.boundingBox(Locator.BoundingBoxOptions().setTimeout(500.0))
            if (box != null && box.width > 0 && box.height > 0) resolved++
          }
        } catch (_: Exception) {
        }
      }
      node.children.forEach { visit(it) }
    }
    visit(tree)
    return (System.currentTimeMillis() - start) to resolved
  }

  /** PROPOSED strategy: one AI-mode snapshot + one aria-ref Locator resolution per node. */
  private fun benchmarkProposedAriaRef(): Triple<Long, Int, Int> {
    val start = System.currentTimeMillis()
    val yaml = page.locator(":root").ariaSnapshot(
      Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI),
    )
    val refs = Regex("""\[ref=(e\d+)]""").findAll(yaml).map { it.groupValues[1] }.toList()
    val deadlineMs = System.currentTimeMillis() + BUDGET_MS
    var resolved = 0
    for (ref in refs) {
      if (System.currentTimeMillis() > deadlineMs) break
      try {
        val locator = page.locator("aria-ref=$ref")
        val box = locator.boundingBox(Locator.BoundingBoxOptions().setTimeout(500.0))
        if (box != null && box.width > 0 && box.height > 0) resolved++
      } catch (_: Exception) {
      }
    }
    val elapsed = System.currentTimeMillis() - start
    return Triple(elapsed, resolved, refs.size)
  }

  private fun runComparison(label: String, html: String) {
    page.setContent(html)
    val yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
    val tree = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(yaml)
    val total = countNodes(tree)

    // CURRENT: production PlaywrightScreenState path (batched JS + residual fallback).
    val currentStart = System.currentTimeMillis()
    val currentState = PlaywrightScreenState(page, 1280, 800)
    val enrichedTree = currentState.viewHierarchy
    val currentElapsed = System.currentTimeMillis() - currentStart
    val currentResolved = countBounded(enrichedTree)

    // OLD: per-node role/name locator resolution, no batching.
    val (oldElapsed, oldResolved) = benchmarkOldPerNodeLocator(tree)

    // PROPOSED: AI-mode ref + per-ref locator resolution.
    val (proposedElapsed, proposedResolved, proposedTotal) = benchmarkProposedAriaRef()

    println(
      """
      |=== $label (total nodes ~= $total) ===
      |  OLD (per-node role/name):   ${oldElapsed}ms, $oldResolved/$total bounded (${pct(oldResolved, total)})
      |  CURRENT (batched role/name): ${currentElapsed}ms, $currentResolved/$total bounded (${pct(currentResolved, total)})
      |  PROPOSED (AI ref, per-node): ${proposedElapsed}ms, $proposedResolved/$proposedTotal bounded (${pct(proposedResolved, proposedTotal)})
      """.trimMargin(),
    )
  }

  private fun pct(n: Int, total: Int): String =
    if (total == 0) "n/a" else "${(n * 100.0 / total).let { "%.1f".format(it) }}%"

  @Test
  fun `benchmark all-button page`() {
    runComparison("all-button (8000)", allButtonHtml(8000))
  }

  @Test
  fun `benchmark headings buttons paragraphs page`() {
    runComparison("headings+buttons+p (~4980)", headingsButtonsParagraphsHtml(1660))
  }

  @Test
  fun `benchmark headings links buttons adjacent-text page`() {
    runComparison("headings+links+buttons+text (~8300)", headingsLinksButtonsTextHtml(2075))
  }

  companion object {
    /** Matches PlaywrightScreenState.ENRICHMENT_BUDGET_MS so the comparison is apples-to-apples. */
    private const val BUDGET_MS = 5_000L
  }
}
