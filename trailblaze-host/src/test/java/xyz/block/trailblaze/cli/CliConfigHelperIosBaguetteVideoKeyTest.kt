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
import xyz.block.trailblaze.host.capture.IosBaguetteVideoGate
import xyz.block.trailblaze.host.recording.EffectiveIosBaguetteVideoConfig

/**
 * Behavior of the `ios-baguette-video` entry in [CONFIG_KEYS] (the experimental opt-in for the
 * baguette-stream iOS video recorder). Mirrors [CliConfigHelperStreamScreenshotsKeyTest]: tri-state
 * `Boolean? = null` field, explicit choices persist even when they match the default, and reading
 * the config seeds the JVM-wide [EffectiveIosBaguetteVideoConfig] holder the capture wiring resolves
 * the gate from.
 */
class CliConfigHelperIosBaguetteVideoKeyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restore() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
    EffectiveIosBaguetteVideoConfig.clearForTests()
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
    val key = CONFIG_KEYS.getValue("ios-baguette-video")
    assertEquals(true, key.set(CliConfigHelper.defaultConfig(), "true")?.iosBaguetteVideoEnabled)
    assertEquals(false, key.set(CliConfigHelper.defaultConfig(), "false")?.iosBaguetteVideoEnabled)
  }

  @Test
  fun `set with 'unset' clears the preference back to inherit-the-default`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("ios-baguette-video")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "true")
    assertNull(key.set(withValue!!, "unset")?.iosBaguetteVideoEnabled)
  }

  @Test
  fun `set with an unrecognized value returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("ios-baguette-video")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "yes"))
  }

  @Test
  fun `get on a default config reads as '(not set)'`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("ios-baguette-video")
    assertEquals("(not set)", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `no recorded preference stays absent from the file`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it }
    assertFalse(settingsFile().readText().contains("iosBaguetteVideoEnabled"))
    assertNull(CliConfigHelper.readConfig()?.iosBaguetteVideoEnabled)
  }

  @Test
  fun `an explicit choice is written to disk even when it matches the default`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(iosBaguetteVideoEnabled = false) }
    assertTrue(settingsFile().readText().contains("iosBaguetteVideoEnabled"))
    assertEquals(false, CliConfigHelper.readConfig()?.iosBaguetteVideoEnabled)
  }

  @Test
  fun `reading a persisted opt-in seeds the effective holder and the gate resolves on`() {
    // Skip if the env var is set in this environment — it would independently open the gate.
    if (System.getenv(IosBaguetteVideoGate.ENV_VAR) != null) return
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(iosBaguetteVideoEnabled = true) }
    EffectiveIosBaguetteVideoConfig.clearForTests()
    CliConfigHelper.readConfig() // side effect: seeds the holder from disk
    assertTrue(EffectiveIosBaguetteVideoConfig.enabled)
    assertTrue(IosBaguetteVideoGate.enabled())
  }

  @Test
  fun `reading with no preference leaves the holder off and the gate closed`() {
    if (System.getenv(IosBaguetteVideoGate.ENV_VAR) != null) return
    isolateAppDataDir()
    EffectiveIosBaguetteVideoConfig.enabled = true // prove readConfig resets it
    CliConfigHelper.updateConfig { it }
    CliConfigHelper.readConfig()
    assertFalse(EffectiveIosBaguetteVideoConfig.enabled)
    assertFalse(IosBaguetteVideoGate.enabled())
  }
}
