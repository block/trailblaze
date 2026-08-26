package xyz.block.trailblaze.report

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The contract the packaged JAR makes: it carries a COMPLETE viewer shell. Every property asserted
 * here is one whose absence is silent in a browser — the page still renders, and only a link
 * clicked weeks later reveals the viewer was truncated, engine-less, or not the shell at all.
 */
class ViewerShellResourceTest {

  @Test
  fun `the JAR carries a complete viewer shell and copyTo writes it out`() {
    val dest = Files.createTempDirectory("viewer-shell-test").toFile().resolve("nested/index.html")
    assertTrue(ViewerShellResource.copyTo(dest), "viewer shell resource missing from the classpath")

    val html = dest.readText()
    assertTrue(html.startsWith("<!doctype html>"), "shell must begin with the doctype (quirks mode otherwise)")
    assertTrue(html.contains("data-tb-shell"), "not a viewer shell — the viewer bundle would boot an empty report")
    assertTrue(
      html.contains("id=\"tb-selector-engine\""),
      "selector engine missing — the Inspector would silently never show a suggestion",
    )
    assertTrue(html.trimEnd().endsWith("</html>"), "shell is truncated")
  }
}
