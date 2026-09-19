package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the desktop app's loader hands Coil for each shape a screenshot reference arrives in.
 *
 * This is the loader behind every screenshot the desktop app draws, and until now nothing pinned
 * it: a reference that resolved to the wrong path failed inside the image pipeline, one frame and
 * one layer away from the branch that got it wrong.
 */
class FileSystemImageLoaderTest {

  private val loader = FileSystemImageLoader(basePath = "/logs")

  @Test
  fun `a step with no screenshot has nothing to load`() {
    assertNull(loader.getImageModel(sessionId = "session-1", screenshotFile = null))
  }

  @Test
  fun `a relative name is resolved under the session directory`() {
    val model = loader.getImageModel(sessionId = "session-1", screenshotFile = "shot.png")

    // The session id is the part that has to come from the caller: screenshots from different
    // sessions share filenames, so dropping it draws one session's screenshot into another's row.
    assertEquals(File("/logs/session-1/shot.png").toURI().toString(), model)
  }

  @Test
  fun `an absolute path keeps its own path rather than being nested under the base`() {
    val model = loader.getImageModel(sessionId = "session-1", screenshotFile = "/tmp/shot.png")

    // A log written outside the logs directory names its screenshots absolutely. Appending the
    // base path would look for `/logs/session-1//tmp/shot.png`, which exists nowhere.
    assertEquals(File("/tmp/shot.png").toURI().toString(), model)
  }

  @Test
  fun `a reference that is already a URL is left alone`() {
    val url = "http://localhost:52525/static/session-1/shot.png"

    // A session viewed against a running server names its screenshots by URL. Treating one as a
    // relative name would turn it into a file path with the scheme still in it.
    assertEquals(url, loader.getImageModel(sessionId = "session-1", screenshotFile = url))
  }

  @Test
  fun `a resolved reference is a URI Coil can open, not a bare path`() {
    val model = loader.getImageModel(sessionId = "session-1", screenshotFile = "shot.png")

    // Coil picks its fetcher off the scheme. A bare `/logs/session-1/shot.png` has none, so it
    // falls through to no fetcher at all and the pane reports a load failure for a file that is
    // sitting right there.
    assertTrue(
      model.orEmpty().startsWith("file:"),
      "the model must carry a scheme Coil can dispatch on, but was: $model",
    )
  }
}
