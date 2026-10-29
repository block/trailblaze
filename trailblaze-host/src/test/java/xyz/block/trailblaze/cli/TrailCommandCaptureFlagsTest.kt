package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * What `trailblaze run`'s capture flags mean after parsing.
 *
 * The default-on streams (logcat, iOS logs, memory) are `negatable = true` booleans that start
 * out `true`. Picocli reads that initializer as "the option name IS the negated form", so without
 * an explicit `fallbackValue` the pair was inverted: `--capture-memory` turned capture OFF and
 * `--no-capture-memory` did nothing at all. Both spellings are asserted here for each flag,
 * because the broken behavior was invisible — the run still passed, it just captured the opposite
 * of what was asked for.
 */
class TrailCommandCaptureFlagsTest {

  private fun parse(vararg args: String): TrailCommand =
    TrailCommand().also { CommandLine(it).parseArgs(*args) }

  @Test
  fun `the default-on capture streams are on with no flags`() {
    val command = parse("trail.yaml")
    assertTrue(command.captureLogcat)
    assertTrue(command.captureIosLogs)
    assertTrue(command.captureMemory)
  }

  @Test
  fun `the no- spelling turns a default-on stream off`() {
    assertFalse(parse("trail.yaml", "--no-capture-logcat").captureLogcat)
    assertFalse(parse("trail.yaml", "--no-capture-ios-logs").captureIosLogs)
    assertFalse(parse("trail.yaml", "--no-capture-memory").captureMemory)
  }

  @Test
  fun `the positive spelling leaves a default-on stream on rather than toggling it off`() {
    assertTrue(parse("trail.yaml", "--capture-logcat").captureLogcat)
    assertTrue(parse("trail.yaml", "--capture-ios-logs").captureIosLogs)
    assertTrue(parse("trail.yaml", "--capture-memory").captureMemory)
  }

  @Test
  fun `turning one stream off leaves the others alone`() {
    val command = parse("trail.yaml", "--no-capture-memory")
    assertFalse(command.captureMemory)
    assertTrue(command.captureLogcat)
    assertTrue(command.captureIosLogs)
  }

  @Test
  fun `a trail run records video unless told not to`() {
    assertEquals(true, sentCaptureVideo(parse("trail.yaml")))
    assertEquals(true, sentCaptureVideo(parse("trail.yaml", "--capture-video")))
    assertEquals(false, sentCaptureVideo(parse("trail.yaml", "--no-capture-video")))
    assertEquals(true, sentCaptureVideo(parse("trail.yaml", "--no-capture-video", "--capture-all")))
  }

  /** The `captureVideo` the daemon receives for a delegated run of [command]. */
  private fun sentCaptureVideo(command: TrailCommand): Boolean? =
    command.delegatedRunRequest(
      file = File("trail.yaml"),
      yamlContent = "- prompts:\n  - step: do the thing\n",
      testName = "any",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { null },
    ).captureVideo
}
