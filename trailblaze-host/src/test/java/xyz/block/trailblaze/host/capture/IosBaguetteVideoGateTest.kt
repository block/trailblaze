package xyz.block.trailblaze.host.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the pure env-over-config resolver behind [IosBaguetteVideoGate.enabled]. The gate
 * must be OFF by default (neither source opts in) so merging the baguette recorder changes no run's
 * behavior; either source alone opens it.
 */
class IosBaguetteVideoGateTest {

  @Test
  fun `neither source set is off`() {
    assertFalse(IosBaguetteVideoGate.fromValues(env = null, configEnabled = false))
  }

  @Test
  fun `config toggle alone opens the gate`() {
    assertTrue(IosBaguetteVideoGate.fromValues(env = null, configEnabled = true))
  }

  @Test
  fun `env '1' or 'true' opens the gate regardless of config`() {
    assertTrue(IosBaguetteVideoGate.fromValues(env = "1", configEnabled = false))
    assertTrue(IosBaguetteVideoGate.fromValues(env = "true", configEnabled = false))
    assertTrue(IosBaguetteVideoGate.fromValues(env = "TRUE", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value does not open the gate on its own`() {
    assertFalse(IosBaguetteVideoGate.fromValues(env = "0", configEnabled = false))
    assertFalse(IosBaguetteVideoGate.fromValues(env = "yes", configEnabled = false))
    assertFalse(IosBaguetteVideoGate.fromValues(env = "", configEnabled = false))
  }

  @Test
  fun `a non-truthy env value still yields on when the config toggle is on`() {
    assertTrue(IosBaguetteVideoGate.fromValues(env = "0", configEnabled = true))
  }
}
