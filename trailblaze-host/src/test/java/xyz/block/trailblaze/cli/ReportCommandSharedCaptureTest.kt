package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shared-capture wiring on `trailblaze report` — passing bare `--gif` or bare
 * `--webp` triggers a single frame capture and auto-emits both formats by default,
 * `--no-gif` / `--no-webp` suppresses the companion encode, and explicit paths stay
 * single-format. Behavior of the actual capture+encode pipeline is covered by
 * [ReportGifExporterTest] and [ReportWebpExporterTest]; this test only proves the
 * orchestrator's resolution + USAGE guards stay in place.
 */
class ReportCommandSharedCaptureTest {

  @Test
  fun `bare --gif auto-promotes webp to USE_DEFAULT_PATH`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = ReportCommand.USE_DEFAULT_PATH,
      webpSpec = null,
      suppressGif = false,
      suppressWebp = false,
    )
    assertEquals(ReportCommand.USE_DEFAULT_PATH, gif)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, webp)
  }

  @Test
  fun `bare --webp auto-promotes gif to USE_DEFAULT_PATH`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = null,
      webpSpec = ReportCommand.USE_DEFAULT_PATH,
      suppressGif = false,
      suppressWebp = false,
    )
    assertEquals(ReportCommand.USE_DEFAULT_PATH, gif)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, webp)
  }

  @Test
  fun `explicit --gif path does NOT auto-promote webp`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = "/tmp/foo.gif",
      webpSpec = null,
      suppressGif = false,
      suppressWebp = false,
    )
    assertEquals("/tmp/foo.gif", gif)
    assertNull(webp, "explicit --gif foo.gif must not auto-emit a .webp companion")
  }

  @Test
  fun `explicit --webp path does NOT auto-promote gif`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = null,
      webpSpec = "/tmp/foo.webp",
      suppressGif = false,
      suppressWebp = false,
    )
    assertNull(gif, "explicit --webp foo.webp must not auto-emit a .gif companion")
    assertEquals("/tmp/foo.webp", webp)
  }

  @Test
  fun `--no-gif suppresses auto-emit when bare --webp is requested`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = null,
      webpSpec = ReportCommand.USE_DEFAULT_PATH,
      suppressGif = true,
      suppressWebp = false,
    )
    assertNull(gif, "--no-gif must suppress the auto-emitted .gif companion")
    assertEquals(ReportCommand.USE_DEFAULT_PATH, webp)
  }

  @Test
  fun `--no-webp suppresses auto-emit when bare --gif is requested`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = ReportCommand.USE_DEFAULT_PATH,
      webpSpec = null,
      suppressGif = false,
      suppressWebp = true,
    )
    assertEquals(ReportCommand.USE_DEFAULT_PATH, gif)
    assertNull(webp, "--no-webp must suppress the auto-emitted .webp companion")
  }

  @Test
  fun `bare --gif and bare --webp both resolve to USE_DEFAULT_PATH`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = ReportCommand.USE_DEFAULT_PATH,
      webpSpec = ReportCommand.USE_DEFAULT_PATH,
      suppressGif = false,
      suppressWebp = false,
    )
    assertEquals(ReportCommand.USE_DEFAULT_PATH, gif)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, webp)
  }

  @Test
  fun `neither flag requested returns nulls`() {
    val (gif, webp) = resolveSharedCaptureSpecs(
      gifSpec = null,
      webpSpec = null,
      suppressGif = false,
      suppressWebp = false,
    )
    assertNull(gif)
    assertNull(webp)
  }

  @Test
  fun `--no-gif --gif is a USAGE error`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--gif", "--no-gif")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call())
  }

  @Test
  fun `--no-webp --webp is a USAGE error`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--webp", "--no-webp")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call())
  }

  @Test
  fun `--no-gif --no-webp together is a USAGE error`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--no-gif", "--no-webp")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call())
  }

  @Test
  fun `--no-gif alone without --webp is a USAGE error`() {
    // Asking us to suppress a companion we wouldn't have auto-emitted in the first
    // place is almost certainly a misunderstanding. Reject upfront with a message
    // pointing at the missing --webp rather than no-op'ing silently.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--no-gif")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call())
  }

  @Test
  fun `--no-webp alone without --gif is a USAGE error`() {
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--no-webp")
    assertEquals(CommandLine.ExitCode.USAGE, cmd.call())
  }

  @Test
  fun `--webp --no-gif parses cleanly (the validate-migration use-case)`() {
    val cmd = ReportCommand()
    val parseResult = CommandLine(cmd).parseArgs("--id", "abc", "--webp", "--no-gif")
    assertTrue(parseResult.errors().isEmpty(), "picocli parse errors: ${parseResult.errors()}")
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.webpOutput)
    assertTrue(cmd.noGif)
    assertFalse(cmd.noWebp)
    assertNull(cmd.gifOutput)
  }

  @Test
  fun `--no-gif and --no-webp show up in the help text`() {
    val help = CommandLine(ReportCommand()).usageMessage
    assertTrue(help.contains("--no-gif"), "Expected --no-gif in help text:\n$help")
    assertTrue(help.contains("--no-webp"), "Expected --no-webp in help text:\n$help")
  }
}
