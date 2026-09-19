package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * What `trailblaze run`'s browser-visibility flags mean after parsing.
 *
 * `--headless` is a `negatable = true` Boolean that starts out `true`. Picocli 4.7.7 reads that
 * initializer as "the option name IS the negated form", so without an explicit `fallbackValue`
 * the pair was inverted: `--headless` surfaced a visible browser and `--no-headless` did nothing.
 * Both spellings are asserted here, along with the deprecated `--show-browser` they combine with,
 * because nothing else surfaces the difference — a web run passes either way, it just shows or
 * hides a window against what was asked for.
 */
class TrailCommandHeadlessFlagTest {

  private fun parse(vararg args: String): TrailCommand =
    TrailCommand().also { CommandLine(it).parseArgs(*args) }

  @Test
  fun `no flags runs headless`() {
    assertTrue(parse("trail.yaml").resolvedBrowserHeadless())
  }

  @Test
  fun `the positive spelling keeps the run headless rather than toggling it visible`() {
    assertTrue(parse("trail.yaml", "--headless").resolvedBrowserHeadless())
    assertTrue(parse("trail.yaml", "--headless=true").resolvedBrowserHeadless())
  }

  @Test
  fun `the no- spelling surfaces a visible browser`() {
    assertFalse(parse("trail.yaml", "--no-headless").resolvedBrowserHeadless())
    assertFalse(parse("trail.yaml", "--headless=false").resolvedBrowserHeadless())
  }

  @Test
  fun `the deprecated show-browser flag still surfaces a visible browser`() {
    assertFalse(parse("trail.yaml", "--show-browser").resolvedBrowserHeadless())
  }

  @Test
  fun `show-browser wins over an explicit headless request`() {
    // The two flags disagree; a request for a window is the one that can't be satisfied
    // silently, so it wins. Pinned so the precedence can't drift unnoticed.
    assertFalse(parse("trail.yaml", "--show-browser", "--headless").resolvedBrowserHeadless())
  }

  @Test
  fun `the parsed field tracks the spelling that was passed`() {
    // The resolution above collapses both flags into one boolean; these assert the raw field,
    // which is what an inverted `negatable` pair gets wrong.
    assertTrue(parse("trail.yaml").headless)
    assertTrue(parse("trail.yaml", "--headless").headless)
    assertFalse(parse("trail.yaml", "--no-headless").headless)
    assertFalse(parse("trail.yaml").showBrowser)
    assertTrue(parse("trail.yaml", "--show-browser").showBrowser)
  }
}
