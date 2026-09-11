package xyz.block.trailblaze.ui.images

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These are tests about what an inspector's failure message must NOT carry.
 *
 * A screenshot reference is usually a filename, but the published-report loader also accepts a
 * `data:` URI with the whole image inlined, and every loader folds the reference into the model it
 * returns — so the image pipeline's own failure text quotes it back. Both of those strings end up
 * in stdout and in a text pane with no scroll container and no line limit.
 */
class ScreenshotDiagnosticsTest {

  @Test
  fun `a filename is reported as-is`() {
    assertEquals("screenshot-1234.png", ScreenshotDiagnostics.ref("screenshot-1234.png"))
  }

  @Test
  fun `a url is reported as-is`() {
    val url = "http://localhost:8443/static/abc/screenshot-1234.png"
    assertEquals(url, ScreenshotDiagnostics.ref(url))
  }

  @Test
  fun `an embedded image is summarized to a size that does not depend on the image`() {
    val small = "data:image/png;base64,${"A".repeat(1_000)}"
    val huge = "data:image/png;base64,${"A".repeat(2_000_000)}"

    // The point is not "shorter than the input" — an implementation that kept a 2,000-character
    // slice would satisfy that. The point is that growing the image does not grow the message, so
    // compare two inputs three orders of magnitude apart. The only difference either summary may
    // carry from the payload is the digits of its length.
    val smallSummary = ScreenshotDiagnostics.ref(small)
    val hugeSummary = ScreenshotDiagnostics.ref(huge)

    assertEquals(
      smallSummary.replace(Regex("\\d+-char"), "N-char"),
      hugeSummary.replace(Regex("\\d+-char"), "N-char"),
    )
    assertTrue(
      "A".repeat(200) !in hugeSummary,
      "summary must not carry the payload, but was: $hugeSummary",
    )
  }

  @Test
  fun `an embedded image still says it was embedded and how big`() {
    val dataUri = "data:image/png;base64,${"A".repeat(500)}"

    val summary = ScreenshotDiagnostics.ref(dataUri)

    // Without the media type a reader cannot tell a summarized data URI from a summarized
    // filename, and the length is what says "a 522-character blob" rather than "a missing file".
    assertTrue(
      summary.startsWith("data:image/png;base64,"),
      "summary should keep the data URI prefix, but was: $summary",
    )
    assertTrue("522" in summary, "summary should report the full length, but was: $summary")
  }

  @Test
  fun `a data uri with no payload is not marked as elided`() {
    // These are the reachable short ones, and the reason it matters: `NetworkImageLoader` does not
    // return null for a payload-free data URI, it falls through and hands the whole string to the
    // image pipeline as a path — so this is what the reader sees when that fails. Claiming an
    // elision here would send them looking for a payload that was never there.
    assertEquals("data:", ScreenshotDiagnostics.ref("data:"))
    assertEquals(
      "data:image/png;base64,",
      ScreenshotDiagnostics.ref("data:image/png;base64,"),
    )
  }

  @Test
  fun `a reference that is not a data uri is still bounded`() {
    // `FileSystemImageLoader` prepends a base path and a session id, and a session id is not short.
    val longPath = "/Users/someone/Library/trailblaze/logs/${"nested/".repeat(200)}shot.png"

    val summary = ScreenshotDiagnostics.ref(longPath)

    assertTrue(
      summary.length < longPath.length / 4,
      "a long path should be bounded, but the summary was ${summary.length} chars",
    )
    assertTrue(
      summary.startsWith("/Users/someone/Library/trailblaze/logs/"),
      "the bounded form should keep the start of the path, but was: $summary",
    )
  }

  @Test
  fun `a cause keeps the exception type so the reader knows where to look`() {
    assertEquals(
      "IllegalStateException: no decoder",
      ScreenshotDiagnostics.cause(IllegalStateException("no decoder")),
    )
  }

  @Test
  fun `a blank exception message does not become a sentence that stops at the colon`() {
    // An empty message is non-null, so a plain null-check renders "Failed to load screenshot: ".
    assertEquals("IllegalStateException", ScreenshotDiagnostics.cause(IllegalStateException("")))
    assertEquals("IllegalStateException", ScreenshotDiagnostics.cause(IllegalStateException("   ")))
    assertEquals("IllegalStateException", ScreenshotDiagnostics.cause(IllegalStateException()))
  }

  @Test
  fun `a cause that quotes the whole image back is bounded too`() {
    // What the image pipeline actually reports: the model the loader built, which is the reference
    // with a server URL in front of it. Bounding the reference alone does not bound this.
    val payload = "A".repeat(2_000_000)
    val cause = ScreenshotDiagnostics.cause(
      IllegalStateException("Unable to open http://localhost:8443/static/abc/data:image/png;base64,$payload"),
    )

    assertTrue(cause.length < 400, "a cause should be bounded, but was ${cause.length} chars")
    assertTrue(
      "A".repeat(500) !in cause,
      "a cause must not carry the payload, but was: $cause",
    )
  }

  @Test
  fun `the pane distinguishes a load failure from a loader that produced nothing`() {
    val onLoadFailure = ScreenshotDiagnostics.message(
      loadError = "IllegalStateException: no decoder",
      screenshotFile = "shot.png",
    )
    val onNothingToLoad = ScreenshotDiagnostics.message(loadError = null, screenshotFile = "shot.png")

    // Different things to go look at — the decoder versus the loader — so they must not read the
    // same. And neither is "this node has no screenshot": both name one.
    assertEquals("Failed to load screenshot: IllegalStateException: no decoder", onLoadFailure)
    assertEquals("Failed to load screenshot: nothing to load for shot.png", onNothingToLoad)
  }
}
