package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behavior of the `require-steps` entry in [CONFIG_KEYS]. Mirrors the shape of the
 * other CliConfigHelper-driven tests: writes the settings file to a TemporaryFolder
 * via `trailblaze.appdata.dir`, exercises the registered key's `get` / `set` lambdas,
 * and asserts on the resulting persisted `SavedTrailblazeAppConfig.requireSteps`.
 */
class CliConfigHelperRequireStepsKeyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
  }

  private fun isolateAppDataDir() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
  }

  @Test
  fun `default value is false`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("require-steps")
    assertEquals("false", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `set true persists the value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("require-steps")
    val updated = key.set(CliConfigHelper.defaultConfig(), "true")
    assertTrue(updated!!.requireSteps)
  }

  @Test
  fun `set false persists the value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("require-steps")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "true")
    val cleared = key.set(withValue!!, "false")
    assertFalse(cleared!!.requireSteps)
  }

  @Test
  fun `set with non-boolean value returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("require-steps")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "maybe"))
  }

  @Test
  fun `round trip through CliConfigHelper write read`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val reread = CliConfigHelper.readConfig()
    assertEquals(true, reread?.requireSteps)
  }
}
