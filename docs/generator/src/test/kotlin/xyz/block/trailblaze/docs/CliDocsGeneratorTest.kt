package xyz.block.trailblaze.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec

class CliDocsGeneratorTest {

  @Test
  fun `bare URL with placeholder is wrapped in backticks`() {
    val input = "Point your browser at http://localhost:<daemon-port>/waypoints/graph instead."
    val expected = "Point your browser at `http://localhost:<daemon-port>/waypoints/graph` instead."
    assertEquals(expected, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `already-backticked URL is left unchanged`() {
    val input = "Point your browser at `http://localhost:<daemon-port>/waypoints/graph` instead."
    assertEquals(input, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `plain URL without placeholder is left unchanged`() {
    val input = "See https://example.com/docs for details."
    assertEquals(input, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `URL with multiple placeholders is wrapped`() {
    val input = "Connect to http://<host>:<port>/api for the API."
    val expected = "Connect to `http://<host>:<port>/api` for the API."
    assertEquals(expected, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `non-URL angle brackets are left unchanged`() {
    val input = "Use <command-name> to run the tool."
    assertEquals(input, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `text with no URLs is left unchanged`() {
    val input = "This is a plain description with no URLs."
    assertEquals(input, CliDocsGenerator.escapeMdxUnsafeUrls(input))
  }

  @Test
  fun `a negatable option is documented under both spellings`() {
    assertEquals(listOf("--turbo", "--no-turbo"), spelledNamesOf(negatable = true, "--turbo"))
  }

  @Test
  fun `an ordinary option is documented under its declared name only`() {
    assertEquals(listOf("--device"), spelledNamesOf(negatable = false, "--device"))
  }

  @Test
  fun `a short name has no negative form and stays single`() {
    // picocli negates `--long` but leaves `-s` alone, so a negatable option declared with both
    // must not grow a spelling the parser would reject.
    assertEquals(listOf("-t", "--turbo", "--no-turbo"), spelledNamesOf(negatable = true, "-t", "--turbo"))
  }

  private fun spelledNamesOf(negatable: Boolean, vararg names: String): List<String> {
    val option = OptionSpec.builder(names)
      .type(Boolean::class.javaObjectType)
      .negatable(negatable)
      .build()
    // The option must belong to a command: the negative spelling comes from that command's
    // transformer, not from the option.
    CommandSpec.create().addOption(option)
    return CliDocsGenerator.spelledNames(option)
  }
}
