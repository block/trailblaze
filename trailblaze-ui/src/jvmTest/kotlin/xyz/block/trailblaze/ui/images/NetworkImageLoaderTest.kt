package xyz.block.trailblaze.ui.images

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins what the served-report loader hands Coil for each shape a screenshot reference arrives in.
 *
 * This loader used to branch on the platform, and the non-JVM arm built URLs relative to the page
 * the report was being viewed from. There is no non-JVM consumer any more, so every reference now
 * resolves against the static server — these say what that means for each shape, so the collapse
 * is something a reader can check rather than take on faith.
 */
class NetworkImageLoaderTest {

  private val loader = NetworkImageLoader(serverBaseUrl = "http://localhost:8443")

  @Test
  fun `a step with no screenshot has nothing to load`() {
    assertNull(loader.getImageModel(sessionId = "session-1", screenshotFile = null))
  }

  @Test
  fun `a relative name is served out of the session directory`() {
    assertEquals(
      "http://localhost:8443/static/session-1/shot.png",
      loader.getImageModel(sessionId = "session-1", screenshotFile = "shot.png"),
    )
  }

  @Test
  fun `a name that already carries the session id is not given a second one`() {
    // Reports disagree about whether the session id is part of the reference. Prefixing one that
    // already has it asks the server for `session-1/session-1/shot.png`, which it will not serve.
    assertEquals(
      "http://localhost:8443/static/session-1/shot.png",
      loader.getImageModel(sessionId = "session-1", screenshotFile = "session-1/shot.png"),
    )
  }

  @Test
  fun `a reference that is already a URL is left alone`() {
    val url = "https://artifacts.example.com/build123/shot.png"

    // A report published to an artifact host names its screenshots absolutely. Putting the static
    // base in front would turn it into a path with the scheme still in the middle.
    assertEquals(url, loader.getImageModel(sessionId = "session-1", screenshotFile = url))
  }

  @Test
  fun `a blank reference still asks for the session directory`() {
    // Not the answer you'd design, but it's the one callers get today, and blankness is screened
    // upstream rather than here — the inspector checks `isNullOrBlank` before asking for a model.
    // Pinned so that a future null-for-blank change is a deliberate edit to this test rather than
    // a silent one.
    assertEquals(
      "http://localhost:8443/static/session-1/",
      loader.getImageModel(sessionId = "session-1", screenshotFile = ""),
    )
  }

  @Test
  fun `the default constructor follows the port override applied at startup`() {
    // Production builds every loader with the no-arg constructor, so the companion default is the
    // base URL that actually ships. It is rewritten at app startup when the server picks a
    // different port; reading it once at class-init instead would leave every image pointed at
    // the port the app didn't take.
    val original = NetworkImageLoader.currentServerBaseUrl
    try {
      NetworkImageLoader.currentServerBaseUrl = "http://localhost:9999"

      assertEquals(
        "http://localhost:9999/static/session-1/shot.png",
        NetworkImageLoader().getImageModel(sessionId = "session-1", screenshotFile = "shot.png"),
      )
    } finally {
      NetworkImageLoader.currentServerBaseUrl = original
    }
  }
}
