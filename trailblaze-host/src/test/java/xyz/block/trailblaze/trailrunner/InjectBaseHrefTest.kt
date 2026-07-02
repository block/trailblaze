package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for [injectBaseHref] — the pure base-tag injection that keeps the Trail Runner SPA's
 * relative asset paths resolving under the route prefix when the UI is reached without a trailing
 * slash (e.g. behind a cloud-workstation preview proxy). Exercises the robustness cases: attribute/casing
 * variants of `<head>`, idempotency against existing `<base>` forms, and the no-`<head>` fallback.
 */
class InjectBaseHrefTest {

  @Test
  fun `injects base immediately after a plain head tag`() {
    val out = injectBaseHref("<html><head>\n<link href=\"./app.css\"></head>", "/trailrunner")
    assertEquals("<html><head>\n<base href=\"/trailrunner/\" />\n<link href=\"./app.css\"></head>", out)
  }

  @Test
  fun `injects after a head tag that carries attributes`() {
    val out = injectBaseHref("<head lang=\"en\" data-x>\n<link href=\"./a.css\">", "/trailrunner")
    assertTrue(out.contains("<head lang=\"en\" data-x>\n<base href=\"/trailrunner/\" />"), out)
    // The base must land before the first relative asset.
    assertTrue(out.indexOf("<base ") < out.indexOf("./a.css"), out)
  }

  @Test
  fun `matches an uppercase HEAD tag`() {
    val out = injectBaseHref("<HEAD>\n<link href=\"./a.css\">", "/trailrunner")
    assertTrue(out.contains("<base href=\"/trailrunner/\" />"), "expected injection for <HEAD>: $out")
  }

  @Test
  fun `is idempotent when a base tag already exists`() {
    val withSpace = "<head>\n<base href=\"/existing/\" />\n"
    assertEquals(withSpace, injectBaseHref(withSpace, "/trailrunner"))
  }

  @Test
  fun `is idempotent for self-closing and uppercase base variants`() {
    val selfClosing = "<head><base/></head>"
    assertEquals(selfClosing, injectBaseHref(selfClosing, "/trailrunner"))
    val upper = "<head><BASE href=\"/x/\"></head>"
    assertEquals(upper, injectBaseHref(upper, "/trailrunner"))
  }

  @Test
  fun `returns the document unchanged when there is no head tag`() {
    // Degraded but non-fatal: serve as-is (the helper logs a warning) rather than corrupting output.
    val noHead = "<html><body>hi</body></html>"
    assertEquals(noHead, injectBaseHref(noHead, "/trailrunner"))
  }

  @Test
  fun `derives the trailing slash from the supplied base path`() {
    val out = injectBaseHref("<head></head>", "/trailrunner")
    assertTrue(out.contains("href=\"/trailrunner/\""), out)
  }
}
