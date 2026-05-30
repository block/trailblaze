package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses the `trailblaze report` command-line surface to confirm `--max-size` is wired
 * up, accepts the documented value shapes, applies to all three export formats
 * (`--gif`/`--video`/`--webp`), and correctly rejects mis-uses at the `call()`-level
 * USAGE check. Behavioral checks of the actual scale-down loop live in
 * [MaxArtifactSizeTest] — this test only proves the picocli annotation glue and
 * pre-`generateSessionReport` validation are in place.
 */
class ReportCommandMaxSizeWiringTest {

  @Test
  fun `--max-size is optional and defaults to null`() {
    val cmd = parseReport()
    assertNull(cmd.maxSize)
  }

  @Test
  fun `--max-size captures the raw string for downstream parsing`() {
    val cmd = parseReport("--id", "abc", "--gif", "--max-size", "10MB")
    assertEquals("10MB", cmd.maxSize)
  }

  @Test
  fun `--max-size accepts the plain-bytes form`() {
    val cmd = parseReport("--id", "abc", "--gif", "--max-size", "1024000")
    assertEquals("1024000", cmd.maxSize)
  }

  @Test
  fun `--max-size works alongside --webp (the validate-migration use-case)`() {
    val cmd = parseReport("--id", "abc", "--webp", "--max-size", "10MB")
    assertEquals("10MB", cmd.maxSize)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.webpOutput)
  }

  @Test
  fun `--max-size works alongside --video`() {
    // The MP4 rescale path is structurally different from GIF/WebP (it takes a source
    // file rather than a frame directory), so wiring it through the same `--max-size`
    // entry point deserves its own pin.
    val cmd = parseReport("--id", "abc", "--video", "--max-size", "10MB")
    assertEquals("10MB", cmd.maxSize)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.videoOutput)
  }

  @Test
  fun `--max-size works when all three export formats are requested`() {
    // The flag is applied per artifact, so a multi-format invocation should cap each
    // one independently. Pin the parse so future code that splits the flag per format
    // (or rejects multi-format) gets caught by this test instead of in production.
    val cmd = parseReport(
      "--id", "abc",
      "--gif",
      "--video",
      "--webp",
      "--max-size", "10MB",
    )
    assertEquals("10MB", cmd.maxSize)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.gifOutput)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.videoOutput)
    assertEquals(ReportCommand.USE_DEFAULT_PATH, cmd.webpOutput)
  }

  @Test
  fun `--max-size shows up in the help text`() {
    val help = CommandLine(ReportCommand()).usageMessage
    assertTrue(
      help.contains("--max-size"),
      "Expected --max-size to appear in `trailblaze report --help` but help was:\n$help",
    )
  }

  @Test
  fun `--max-size without --gif --video or --webp exits USAGE`() {
    // `--max-size` only matters when an artifact is being exported. With none of the
    // three formats requested, the flag has no effect — reject upfront rather than
    // silently dropping the value.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--max-size", "10MB")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `--max-size with an unparseable value exits USAGE`() {
    // Bad values need to surface as USAGE errors with the parser's message, not as a
    // runtime stack trace once generateSessionReport starts the export.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--gif", "--max-size", "garbage")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `--max-size with an unknown unit suffix exits USAGE`() {
    // The size regex permits any alphabetic suffix; only K/M/G (with optional B/iB) are
    // valid. `10TB` should reject cleanly rather than fall through to the file-size
    // multiplier picking up an unexpected default.
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--webp", "--max-size", "10TB")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `--max-size with an overflowing value exits USAGE`() {
    // The picocli layer catches IllegalArgumentException from `parseSize` and surfaces
    // it as a USAGE error. Pin that path explicitly so an overflow value doesn't slip
    // through to `generateSessionReport` with a saturated Long (which would silently
    // produce an "infinite" cap and effectively turn --max-size into a no-op).
    val cmd = ReportCommand()
    CommandLine(cmd).parseArgs("--id", "abc", "--webp", "--max-size", "99999999999G")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  /**
   * Builds a fresh `ReportCommand` and feeds the given args through picocli without
   * invoking `call()` — this lets us inspect the parsed field values without standing
   * up the daemon/app dependencies.
   */
  private fun parseReport(vararg args: String): ReportCommand {
    val cmd = ReportCommand()
    val parseResult = CommandLine(cmd).parseArgs(*args)
    // Sanity: picocli must have consumed every arg without dropping into help/version.
    assertTrue(parseResult.errors().isEmpty(), "picocli parse errors: ${parseResult.errors()}")
    return cmd
  }
}
