package xyz.block.trailblaze.host.animations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the pure env-over-config resolver behind [DisableAnimationsGate.enabled]. The
 * gate must be OFF by default (neither source opts in) so merging the disabler changes no run's
 * behavior; either source alone opens it.
 */
class DisableAnimationsGateTest {

  @Test
  fun `neither source set is off`() {
    assertFalse(DisableAnimationsGate.fromValues(env = null, configEnabled = false))
  }

  @Test
  fun `config toggle alone opens the gate`() {
    assertTrue(DisableAnimationsGate.fromValues(env = null, configEnabled = true))
  }

  @Test
  fun `env '1' or 'true' opens the gate regardless of config`() {
    assertTrue(DisableAnimationsGate.fromValues(env = "1", configEnabled = false))
    assertTrue(DisableAnimationsGate.fromValues(env = "true", configEnabled = false))
    assertTrue(DisableAnimationsGate.fromValues(env = "TRUE", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value does not open the gate on its own`() {
    assertFalse(DisableAnimationsGate.fromValues(env = "0", configEnabled = false))
    assertFalse(DisableAnimationsGate.fromValues(env = "yes", configEnabled = false))
    assertFalse(DisableAnimationsGate.fromValues(env = "", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value still yields on when the config toggle is on`() {
    assertTrue(DisableAnimationsGate.fromValues(env = "0", configEnabled = true))
  }
}
