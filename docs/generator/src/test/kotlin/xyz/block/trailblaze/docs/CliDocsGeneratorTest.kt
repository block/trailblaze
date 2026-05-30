package xyz.block.trailblaze.docs

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
