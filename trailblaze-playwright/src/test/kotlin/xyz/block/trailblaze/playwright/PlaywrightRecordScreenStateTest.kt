package xyz.block.trailblaze.playwright

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail

/**
 * [PlaywrightRecordScreenState] keeps what a session record shows — the screenshot and the node
 * tree with bounds — and drops the element list only the LLM reads.
 */
class PlaywrightRecordScreenStateTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newPage()
    page.setContent("<html><body><button>Save</button></body></html>")
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  @Test
  fun `keeps the screenshot and the node tree's bounds, drops the LLM element list`() {
    val full = PlaywrightScreenState(page, 1280, 800)
    val record = PlaywrightRecordScreenState(PlaywrightScreenState(page, 1280, 800))

    // The control: the full capture has the element list the record leaves out.
    assertThat(full.viewHierarchyTextRepresentation).isNotNull()
    assertThat(record.viewHierarchyTextRepresentation).isNull()
    assertThat(full.pageContextSummary).isNotNull()
    assertThat(record.pageContextSummary).isNull()
    assertThat(record.screenshotBytes).isNotNull()
    val button = record.trailblazeNodeTree?.findFirst { node ->
      (node.driverDetail as? DriverNodeDetail.Web)?.ariaRole == "button"
    }
    assertThat(button?.bounds).isNotNull()
    assertThat(button!!.bounds!!.right - button.bounds!!.left).isGreaterThan(0)
  }
}
