package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The screen header names the focused element the way the element list does, ref included.
 * A model told to focus a field checks this line; without the field's name it can't tell
 * which one has focus, and keeps clicking it.
 */
class PlaywrightFocusedElementTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 800)).newPage()
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  private fun focusedLine(): String =
    PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation.lines().single { it.startsWith("Focused:") }

  private fun refFor(state: PlaywrightScreenState, descriptor: String): String =
    state.elementIdMapping.entries.single { it.value.descriptor == descriptor }.key

  @Test
  fun `a field named by its label is named with its ref`() {
    page.setContent(
      """
      <label for="name">Name (required)</label><input id="name" type="text">
      <label for="price">Price</label><input id="price" type="text">
      """.trimIndent(),
    )
    page.locator("#price").focus()

    val state = PlaywrightScreenState(page, 1280, 800)

    assertEquals("Focused: [${refFor(state, "textbox \"Price\"")}] textbox \"Price\"", focusedLine())
  }

  @Test
  fun `of two fields with the same name, the focused one is named`() {
    page.setContent("""<input aria-label="Search"><input aria-label="Search" id="second">""")
    page.locator("#second").focus()

    val state = PlaywrightScreenState(page, 1280, 800)
    val second = state.elementIdMapping.entries.filter { it.value.descriptor == "textbox \"Search\"" }[1].key

    assertEquals("Focused: [$second] textbox \"Search\"", focusedLine())
  }

  @Test
  fun `a field that lost focus after the snapshot is not named as focused`() {
    page.setContent(
      """
      <label for="name">Name (required)</label><input id="name" type="text">
      <label for="price">Price</label><input id="price" type="text">
      """.trimIndent(),
    )
    page.locator("#price").focus()
    val state = PlaywrightScreenState(page, 1280, 800)
    state.annotationElements // takes the snapshot, which marks Price focused

    page.locator("#name").focus()

    val focused = state.viewHierarchyTextRepresentation.lines().single { it.startsWith("Focused:") }
    assertFalse("Price" in focused, focused)
  }

  @Test
  fun `the focused field is named after a cross-site navigation`() {
    // Playwright prefixes the main frame's refs after it changes site (`f4e1`).
    page.route("**/*") { route ->
      val body = if (route.request().url().startsWith("https://b.test")) "<label>Price <input id='price'></label>" else "<p>Start</p>"
      route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody(body))
    }
    page.navigate("https://a.test/")
    page.navigate("https://b.test/")
    page.locator("#price").focus()

    val state = PlaywrightScreenState(page, 1280, 800)

    assertEquals("Focused: [${refFor(state, "textbox \"Price\"")}] textbox \"Price\"", focusedLine())
  }
}
