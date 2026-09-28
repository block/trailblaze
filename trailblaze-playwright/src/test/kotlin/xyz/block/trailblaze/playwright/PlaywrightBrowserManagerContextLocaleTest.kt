package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Route
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A web lane's language is the browser context's: what a site reads from `navigator.language` and
 * receives as `Accept-Language`. These drive a real headless Chromium, because that is where the
 * two can disagree with what the manager believes it set.
 */
class PlaywrightBrowserManagerContextLocaleTest {

  private lateinit var manager: PlaywrightBrowserManager

  @After
  fun tearDown() {
    if (::manager.isInitialized) manager.close()
  }

  /** Loads a page and returns what the site saw: `navigator.language` and `Accept-Language`. */
  private suspend fun whatTheSiteSees(): Pair<Any?, String?> =
    withContext(manager.playwrightDispatcher) {
      var acceptLanguage: String? = null
      manager.currentPage.context().route("**/*") { route: Route ->
        acceptLanguage = route.request().allHeaders()["accept-language"]
        route.fulfill(
          Route.FulfillOptions().setStatus(200).setContentType("text/html").setBody("<p>page</p>"),
        )
      }
      manager.currentPage.navigate("https://test.trailblaze.invalid/")
      manager.currentPage.evaluate("() => navigator.language") to acceptLanguage
    }

  @Test
  fun `a context opened in a locale presents it to the site, and a reset keeps it`() = runBlocking {
    manager = PlaywrightBrowserManager(headless = true)

    manager.applyContextLocale("es-US")
    assertEquals("es-US" to "es-US", whatTheSiteSees())

    // A new session inside the same locale lane must not fall back to English.
    withContext(manager.playwrightDispatcher) { manager.resetSession() }
    assertEquals("es-US" to "es-US", whatTheSiteSees())
  }

  @Test
  fun `clearing the locale gives the next context the browser default again`() = runBlocking {
    manager = PlaywrightBrowserManager(headless = true)
    val (defaultLanguage, _) = whatTheSiteSees()

    manager.applyContextLocale("es")
    assertEquals("es" to "es", whatTheSiteSees())

    manager.applyContextLocale(null)
    val (language, acceptLanguage) = whatTheSiteSees()
    assertEquals(defaultLanguage, language)
    assertNotEquals("es", acceptLanguage)
  }
}
