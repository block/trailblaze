package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import picocli.CommandLine

/**
 * `trailblaze viewer` writes the viewer bundled into this binary.
 *
 * Two things are asserted, and both fail silently in a browser rather than at the command line.
 * The PATH contract: a `--output` ending in .html is the file, anything else is a directory that
 * gets `index.html` — a static host serves the viewer as a page at a path, so getting this backwards
 * publishes a viewer at a URL nobody links to. The BYTES contract: what lands is a complete shell,
 * not a truncated or engine-less one, which is the whole reason the shell is baked into the JAR
 * instead of built at the consumption site.
 */
class ViewerCommandTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun run(vararg args: String): Int = CommandLine(ViewerCommand()).execute(*args)

  private fun assertIsCompleteViewerShell(html: String) {
    assertTrue(html.startsWith("<!doctype html>"), "shell must begin with the doctype (quirks mode otherwise)")
    assertTrue(html.contains("data-tb-shell"), "not a viewer shell — the page would boot an empty report")
    assertTrue(
      html.contains("id=\"tb-selector-engine\""),
      "selector engine missing — Inspect UI would silently never show a suggestion",
    )
    assertTrue(html.trimEnd().endsWith("</html>"), "shell is truncated")
  }

  @Test
  fun `an html output path is written as that exact file`() {
    val destination = tempFolder.newFolder("out").resolve("viewer.html")

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("--output", destination.absolutePath))

    assertTrue(destination.isFile, "expected the viewer at ${destination.absolutePath}")
    assertIsCompleteViewerShell(destination.readText())
  }

  @Test
  fun `a non-html output path is a directory and gets index dot html inside it`() {
    val directory = tempFolder.newFolder("site")

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("--output", directory.absolutePath))

    val written = directory.resolve("index.html")
    assertTrue(written.isFile, "expected index.html inside ${directory.absolutePath}")
    assertIsCompleteViewerShell(written.readText())
  }

  @Test
  fun `missing parent directories are created rather than failing the command`() {
    // `trailblaze viewer -o dist/viewer/index.html` on a clean checkout is the ordinary case, so
    // the command has to make the path rather than ask the caller to mkdir first.
    val destination = tempFolder.root.resolve("does/not/exist/yet/index.html")

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("-o", destination.absolutePath))

    assertIsCompleteViewerShell(destination.readText())
  }

  @Test
  fun `the html suffix is recognized whatever its case`() {
    // Written .HTML on a case-insensitive filesystem is still a file target; treating it as a
    // directory would put the viewer at <name>.HTML/index.html, a path nothing links to.
    val destination = tempFolder.newFolder("cased").resolve("Viewer.HTML")

    assertEquals(TrailblazeExitCode.SUCCESS.code, run("--output", destination.absolutePath))

    assertTrue(destination.isFile, "expected the viewer written as the named file, not a directory")
  }

  @Test
  fun `output defaults to index dot html in the working directory`() {
    // Asserted through the parsed model rather than by running the command, which would write into
    // whatever directory the test JVM happens to sit in.
    val command = ViewerCommand()
    CommandLine(command).parseArgs()

    assertEquals("index.html", command.output.path)
  }
}
