package xyz.block.trailblaze.host.turbo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What turns turbo on.
 *
 * Worth pinning because the default matters more than the feature: an existing developer or CI
 * lane must not start installing a helper into the app under test — and restarting it — just
 * because they upgraded the CLI.
 */
class TurboGateTest {

  @Test
  fun `neither source set is off`() {
    assertFalse(TurboGate.fromValues(env = null, configEnabled = false))
  }

  @Test
  fun `config toggle alone opens the gate`() {
    assertTrue(TurboGate.fromValues(env = null, configEnabled = true))
  }

  @Test
  fun `env '1' or 'true' opens the gate regardless of config`() {
    assertTrue(TurboGate.fromValues(env = "1", configEnabled = false))
    assertTrue(TurboGate.fromValues(env = "true", configEnabled = false))
    assertTrue(TurboGate.fromValues(env = "TRUE", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value does not open the gate on its own`() {
    assertFalse(TurboGate.fromValues(env = "0", configEnabled = false))
    assertFalse(TurboGate.fromValues(env = "false", configEnabled = false))
    assertFalse(TurboGate.fromValues(env = "", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value still yields on when the config toggle is on`() {
    // Env is an override for turning turbo ON for a one-off run, not a way to force it off — same
    // precedence as every other Trailblaze env toggle.
    assertTrue(TurboGate.fromValues(env = "0", configEnabled = true))
  }

  // --- the per-run flag ---

  @Test
  fun `--turbo turns it on for a run with nothing else set`() {
    assertTrue(TurboGate.fromValues(env = null, configEnabled = false, runOverride = true))
  }

  @Test
  fun `--no-turbo wins over an enabling env var and config`() {
    // The one precedence that matters: a run must be able to opt OUT, or there is no way to get a
    // clean comparison run on a machine or CI lane that has turbo switched on globally.
    assertFalse(TurboGate.fromValues(env = "1", configEnabled = true, runOverride = false))
  }

  @Test
  fun `no per-run choice falls back to env and config`() {
    assertTrue(TurboGate.fromValues(env = "1", configEnabled = false, runOverride = null))
    assertTrue(TurboGate.fromValues(env = null, configEnabled = true, runOverride = null))
    assertFalse(TurboGate.fromValues(env = null, configEnabled = false, runOverride = null))
  }
}
