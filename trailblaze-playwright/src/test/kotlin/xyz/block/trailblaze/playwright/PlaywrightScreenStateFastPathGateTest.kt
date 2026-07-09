package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration tests for the fast-path + cardinality-gate hybrid in [PlaywrightScreenState]
 * (`fastMatchWithCardinalityGate`, `FAST_ROLE_NAME_MATCH_JS`) — the latency shortcut added on
 * top of the `AriaSnapshotMode.AI` ref-based resolution to fix a regression where
 * `annotationElements` (the annotated-screenshot path) got measurably slower on ordinary
 * link/button-heavy pages, since every element started paying a per-ref round-trip cost
 * instead of the single round-trip the pre-existing role/name DOM walk needed for tags it
 * already covered correctly.
 *
 * The critical property under test: restoring that fast DOM walk must NOT reintroduce the
 * silently-wrong-bounding-box risk it originally had. `getFallbackRole`'s `SUMMARY -> button`
 * rule is a real, reproducible case where the fast walk's role computation disagrees with
 * Playwright's own `ariaSnapshot()` — an open `<summary>` is NOT exposed as `role="button"` by
 * Playwright, but the fast walk's heuristic still counts it as one. The gate must catch this
 * disagreement (via cardinality cross-check against `aiRefsByRoleName`) and defer to the
 * ref-based path instead of trusting a coincidentally-ordered wrong match.
 */
class PlaywrightScreenStateFastPathGateTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 800))
    page = context.newPage()
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  @Test
  fun `fast path resolves link-heavy pages without a per-element round trip`() {
    // Regression pin for the annotationElements slowdown: before the fast-path hybrid,
    // resolving N links cost N per-ref round-trips (roughly 1ms/element observed). The
    // fast path should resolve ALL of these via cardinality-corroborated matches, so this
    // should stay fast regardless of link count. We don't assert a hard timing bound here
    // (flaky on shared CI hardware) — instead we assert on the FUNCTIONAL contract: every
    // link gets a correct, distinct annotation.
    val html = buildString {
      append("<!DOCTYPE html><html><body style='margin:0;padding:0;'>")
      for (i in 1..30) append("<a href=\"#item$i\">Link $i</a><br/>")
      append("</body></html>")
    }
    page.setContent(html)

    val state = PlaywrightScreenState(page, 1280, 800)
    val annotations = state.annotationElements
    assertNotNull(annotations)
    assertEquals(30, annotations.size, "Every link should get exactly one annotation")
    // Each link should land at a distinct y position (stacked via <br/>).
    val distinctTops = annotations.map { it.bounds.top }.distinct()
    assertEquals(30, distinctTops.size, "Each link's annotation should be at its own position")
  }

  @Test
  fun `cardinality gate rejects a phantom match from the fast heuristic's summary-as-button rule`() {
    // getFallbackRole() maps <summary> to role="button" unconditionally, but Playwright's
    // own ariaSnapshot() does NOT expose an open <summary> as a separate "button" node (it's
    // absorbed into the <details> element's own accessible name/role). A page with an open
    // <summary>Submit</summary> BEFORE a real <button>Submit</button> makes the fast walk's
    // naive nth=0 lookup for "button Submit" land on the summary (wrong element, wrong
    // position) unless the cardinality gate catches the count disagreement (fast walk sees
    // 2 "button Submit" candidates; Playwright's ariaSnapshot() and the compact list only
    // ever produce 1) and defers to the ref-based path for the real button instead.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0;padding:0;">
        <details open style="position:absolute;left:50px;top:500px;">
          <summary>Submit</summary>
          <p>Some detail content.</p>
        </details>
        <button id="real" style="position:absolute;left:50px;top:50px;width:120px;height:40px;">Submit</button>
      </body>
      </html>
      """.trimIndent(),
    )

    val state = PlaywrightScreenState(page, 1280, 800)
    val submitEntry = state.elementIdMapping.entries.find { it.value.descriptor.contains("Submit") }
    assertNotNull(submitEntry, "Should find exactly one \"button Submit\" entry in the compact list")

    val annotations = state.annotationElements
    assertNotNull(annotations)
    val submitAnnotation = annotations.find { it.refLabel == submitEntry.key }
    assertNotNull(submitAnnotation, "The real Submit button should be annotated")

    // The real button is at top=50; the phantom summary match (if trusted) would be at
    // top=500. Landing near top=50 proves the gate rejected the phantom fast-path match.
    assertEquals(50, submitAnnotation.bounds.top, "Submit annotation must be the real button, not the summary phantom match")
  }
}
