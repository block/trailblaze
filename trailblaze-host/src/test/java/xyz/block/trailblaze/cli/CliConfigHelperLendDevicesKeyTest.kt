package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavior of the `lend-devices` entry in [CONFIG_KEYS]: off by default, strict true/false. */
class CliConfigHelperLendDevicesKeyTest {

  private val key = CONFIG_KEYS.getValue("lend-devices")

  @Test
  fun `lending is off by default`() {
    assertEquals("false", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `true and false round-trip`() {
    val on = key.set(CliConfigHelper.defaultConfig(), "true")!!
    assertTrue(on.lendDevices)
    assertEquals("true", key.get(on))
    assertFalse(key.set(on, "false")!!.lendDevices)
  }

  @Test
  fun `anything else is rejected`() {
    assertNull(key.set(CliConfigHelper.defaultConfig(), "yes"))
  }
}
