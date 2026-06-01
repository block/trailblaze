package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the verb-plumbing contract for [emitNoDevicesEnvelope] and
 * [emitMultipleDevicesEnvelope] — the two envelopes [resolveDeviceWithAutodetect] emits
 * when device autodetect falls through.
 *
 * Pre-fix, every action command that entered [cliReusableWithDevice] (`snapshot`,
 * `ask`, `verify`, `step`, `tool`) rendered `✗ Command failed` for these branches
 * because the wrapper called [resolveDeviceWithAutodetect] without a verb and the
 * resolver's default was the generic `"Command"`. From an OOBE-review report:
 * `trailblaze snapshot` with multiple devices printed
 * `✗ Command failed / reason: multiple devices connected — pick one` — the verb
 * named no recognizable subcommand. With `verb` now plumbed through
 * [cliReusableWithDevice] → [resolveDeviceWithAutodetect] →
 * [emitNoDevicesEnvelope] / [emitMultipleDevicesEnvelope] →
 * [reportCliError], the rendered envelope opens with `✗ Snapshot failed` (or
 * `Tool` / `Ask` / `Verify` / `Step`), matching the subcommand the user invoked.
 *
 * The tests cover both envelopes for each of the five verbs threaded by an
 * action command, plus the default-verb fallback for any future caller that
 * doesn't override.
 */
class DeviceResolverVerbEnvelopeTest {

  // ---------------------------------------------------------------------------------------
  // emitNoDevicesEnvelope: verb appears in the header, never collapses to the default.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `no-devices envelope renders the caller's verb in the header`() {
    val verbs = listOf("Snapshot", "Ask", "Verify", "Step", "Tool")
    for (verb in verbs) {
      val stderr = captureStderrText { emitNoDevicesEnvelope(verb) }
      assertTrue(
        stderr.startsWith("✗ $verb failed"),
        "expected `✗ $verb failed` header for verb=$verb; got:\n$stderr",
      )
      assertTrue("reason: no devices connected" in stderr, stderr)
      assertTrue(
        "trailblaze device connect web" in stderr,
        "no-devices envelope must keep the web-target hint; got:\n$stderr",
      )
      assertFalse(
        stderr.startsWith("✗ Command failed"),
        "verb=$verb must not collapse to the generic `Command` placeholder; got:\n$stderr",
      )
    }
  }

  @Test
  fun `no-devices envelope falls back to Command when caller does not override`() {
    // The default exists so a future wrapper that forgets to plumb a verb still gets a
    // valid (if generic) envelope rather than a compile error or empty header. The
    // production callers (action commands) always pass their own verb — tested above.
    val stderr = captureStderrText { emitNoDevicesEnvelope("Command") }
    assertTrue(stderr.startsWith("✗ Command failed"), stderr)
  }

  // ---------------------------------------------------------------------------------------
  // emitMultipleDevicesEnvelope: verb in the header, all device specs rendered, both
  // recovery sections (interactive `device connect` and per-call `--device`) present.
  // ---------------------------------------------------------------------------------------

  @Test
  fun `multiple-devices envelope renders the caller's verb and both recovery sections`() {
    val specs = listOf("android/emulator-5554", "ios/SIM-UUID")
    val verbs = listOf("Snapshot", "Ask", "Verify", "Step", "Tool")
    for (verb in verbs) {
      val stderr = captureStderrText { emitMultipleDevicesEnvelope(verb, specs) }
      assertTrue(
        stderr.startsWith("✗ $verb failed"),
        "expected `✗ $verb failed` header for verb=$verb; got:\n$stderr",
      )
      assertTrue("reason: multiple devices connected — pick one" in stderr, stderr)
      assertTrue("    trailblaze device connect android/emulator-5554" in stderr, stderr)
      assertTrue("    trailblaze device connect ios/SIM-UUID" in stderr, stderr)
      assertTrue("    --device android/emulator-5554" in stderr, stderr)
      assertTrue("    --device ios/SIM-UUID" in stderr, stderr)
      assertFalse(
        stderr.startsWith("✗ Command failed"),
        "verb=$verb must not collapse to the generic `Command` placeholder; got:\n$stderr",
      )
    }
  }

  @Test
  fun `multiple-devices envelope renders each spec exactly once per recovery section`() {
    // Defensive: if either forEach loop accidentally iterated twice (e.g. a future
    // refactor that joins the two sections), specs would render double and a script
    // grep-ing the output would see ghost devices.
    val specs = listOf("android/emulator-5554")
    val stderr = captureStderrText { emitMultipleDevicesEnvelope("Snapshot", specs) }

    val connectOccurrences = "trailblaze device connect android/emulator-5554".toRegex().findAll(stderr).count()
    val flagOccurrences = "--device android/emulator-5554".toRegex().findAll(stderr).count()
    assertEquals(1, connectOccurrences, "interactive line should render exactly once; got:\n$stderr")
    assertEquals(1, flagOccurrences, "--device line should render exactly once; got:\n$stderr")
  }
}
