package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * `trailblaze config device <platform>` is the one deliberate way to set, or `unset` to clear, the
 * machine-wide default device platform that `trailblaze run` falls back to. `device connect` and `session start` pin
 * only their own terminal, so this key is the only writer left and must keep working.
 */
class CliConfigHelperDeviceKeyTest {

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

  private val key get() = CONFIG_KEYS.getValue("device")

  @Test
  fun `unset by default`() {
    isolateAppDataDir()
    assertEquals("not set", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `set saves the platform as the machine-wide default`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { key.set(it, "ios")!! }
    val reread = CliConfigHelper.readConfig()!!
    assertEquals("IOS", reread.cliDevicePlatform)
    assertEquals("ios", key.get(reread))
  }

  @Test
  fun `unset clears a saved default`() {
    // A machine upgraded from a release where `device connect` also saved this default can hold
    // one nobody chose; `config device unset` must be able to drop it.
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(cliDevicePlatform = "WEB") }
    CliConfigHelper.updateConfig { key.set(it, "unset")!! }
    val reread = CliConfigHelper.readConfig()!!
    assertNull(reread.cliDevicePlatform)
    assertEquals("not set", key.get(reread))
  }

  @Test
  fun `set rejects an unknown platform`() {
    isolateAppDataDir()
    assertNull(key.set(CliConfigHelper.defaultConfig(), "toaster"))
  }
}
