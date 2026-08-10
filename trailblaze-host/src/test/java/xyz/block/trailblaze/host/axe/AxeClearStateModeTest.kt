package xyz.block.trailblaze.host.axe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-gate tests for the `TRAILBLAZE_IOS_CLEAR_STATE_MODE` routing
 * ([AxeDeviceManager.useContainerClearState]) — the env read stays outside the gate so this
 * needs no simulator and no environment mutation.
 */
class AxeClearStateModeTest {

  @Test
  fun `container opts in, case-insensitively and trimmed`() {
    assertTrue(AxeDeviceManager.useContainerClearState("container"))
    assertTrue(AxeDeviceManager.useContainerClearState("CONTAINER"))
    assertTrue(AxeDeviceManager.useContainerClearState(" Container "))
  }

  @Test
  fun `unset or any other value keeps the default reinstall path`() {
    assertFalse(AxeDeviceManager.useContainerClearState(null))
    assertFalse(AxeDeviceManager.useContainerClearState(""))
    assertFalse(AxeDeviceManager.useContainerClearState("1"))
    assertFalse(AxeDeviceManager.useContainerClearState("true"))
    assertFalse(AxeDeviceManager.useContainerClearState("reinstall"))
  }
}
