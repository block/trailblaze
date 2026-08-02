package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.host.animations.DisableAnimationsGate
import xyz.block.trailblaze.host.animations.EffectiveDisableAnimationsConfig

/**
 * Behavior of the `disable-animations` entry in [CONFIG_KEYS] (the experimental opt-in for
 * session-scoped OS-animation disabling). Mirrors [CliConfigHelperIosBaguetteVideoKeyTest]:
 * tri-state `Boolean? = null` field, explicit choices persist even when they match the default,
 * and reading the config seeds the JVM-wide [EffectiveDisableAnimationsConfig] holder the session
 * wiring resolves the gate from.
 */
class CliConfigHelperDisableAnimationsKeyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restore() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
    EffectiveDisableAnimationsConfig.clearForTests()
  }

  private fun isolateAppDataDir() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
  }

  private fun settingsFile(): File =
    File(File(System.getProperty("trailblaze.appdata.dir")), "trailblaze-settings.json")

  @Test
  fun `set with true or false persists the explicit choice`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("disable-animations")
    assertEquals(true, key.set(CliConfigHelper.defaultConfig(), "true")?.disableAnimationsEnabled)
    assertEquals(false, key.set(CliConfigHelper.defaultConfig(), "false")?.disableAnimationsEnabled)
  }

  @Test
  fun `set with 'unset' clears the preference back to inherit-the-default`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("disable-animations")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "true")
    assertNull(key.set(withValue!!, "unset")?.disableAnimationsEnabled)
  }

  @Test
  fun `set with an unrecognized value returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("disable-animations")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "yes"))
  }

  @Test
  fun `get on a default config reads as '(not set)'`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("disable-animations")
    assertEquals("(not set)", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `no recorded preference stays absent from the file`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it }
    assertFalse(settingsFile().readText().contains("disableAnimationsEnabled"))
    assertNull(CliConfigHelper.readConfig()?.disableAnimationsEnabled)
  }

  @Test
  fun `an explicit choice is written to disk even when it matches the default`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(disableAnimationsEnabled = false) }
    assertTrue(settingsFile().readText().contains("disableAnimationsEnabled"))
    assertEquals(false, CliConfigHelper.readConfig()?.disableAnimationsEnabled)
  }

  @Test
  fun `reading a persisted opt-in seeds the effective holder and the gate resolves on`() {
    // Skip if the env var is set in this environment — it would independently open the gate.
    if (System.getenv(DisableAnimationsGate.ENV_VAR) != null) return
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(disableAnimationsEnabled = true) }
    EffectiveDisableAnimationsConfig.clearForTests()
    CliConfigHelper.readConfig() // side effect: seeds the holder from disk
    assertTrue(EffectiveDisableAnimationsConfig.enabled)
    assertTrue(DisableAnimationsGate.enabled())
  }

  @Test
  fun `reading with no preference leaves the holder off and the gate closed`() {
    if (System.getenv(DisableAnimationsGate.ENV_VAR) != null) return
    isolateAppDataDir()
    EffectiveDisableAnimationsConfig.enabled = true // prove readConfig resets it
    CliConfigHelper.updateConfig { it }
    CliConfigHelper.readConfig()
    assertFalse(EffectiveDisableAnimationsConfig.enabled)
    assertFalse(DisableAnimationsGate.enabled())
  }
}
