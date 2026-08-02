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
import xyz.block.trailblaze.host.StreamScreenshotMode
import xyz.block.trailblaze.host.recording.EffectiveStreamScreenshotConfig

/**
 * Behavior of the `stream-screenshots` entry in [CONFIG_KEYS] (one toggle for the shared
 * Android/iOS/web stream engine). Mirrors
 * [CliConfigHelperUnifiedRecordingsKeyTest]: tri-state `Boolean? = null` field, explicit choices
 * persist even when they match the default, and reading the config seeds the JVM-wide
 * [EffectiveStreamScreenshotConfig] holder every platform's runner/agent resolves its mode from.
 */
class CliConfigHelperStreamScreenshotsKeyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restore() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
    EffectiveStreamScreenshotConfig.clearForTests()
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
    val key = CONFIG_KEYS.getValue("stream-screenshots")
    assertEquals(true, key.set(CliConfigHelper.defaultConfig(), "true")?.streamScreenshotsEnabled)
    assertEquals(false, key.set(CliConfigHelper.defaultConfig(), "false")?.streamScreenshotsEnabled)
  }

  @Test
  fun `set with 'unset' clears the preference back to inherit-the-default`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("stream-screenshots")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "true")
    assertNull(key.set(withValue!!, "unset")?.streamScreenshotsEnabled)
  }

  @Test
  fun `set with an unrecognized value returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("stream-screenshots")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "yes"))
  }

  @Test
  fun `get on a default config reads as '(not set)'`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("stream-screenshots")
    assertEquals("(not set)", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `no recorded preference stays absent from the file`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it }
    assertFalse(settingsFile().readText().contains("streamScreenshotsEnabled"))
    assertNull(CliConfigHelper.readConfig()?.streamScreenshotsEnabled)
  }

  @Test
  fun `an explicit choice is written to disk even when it matches the default`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(streamScreenshotsEnabled = false) }
    assertTrue(settingsFile().readText().contains("streamScreenshotsEnabled"))
    assertEquals(false, CliConfigHelper.readConfig()?.streamScreenshotsEnabled)
  }

  @Test
  fun `reading a persisted opt-in seeds the effective holder and resolves to STREAM`() {
    // Skip if the env var is set in this environment — it would independently select a mode.
    if (System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT") != null) return
    if (System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT_AB") != null) return
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(streamScreenshotsEnabled = true) }
    EffectiveStreamScreenshotConfig.clearForTests()
    CliConfigHelper.readConfig() // side effect: seeds the holder from disk
    assertTrue(EffectiveStreamScreenshotConfig.enabled)
    assertEquals(StreamScreenshotMode.STREAM, StreamScreenshotMode.resolve())
  }

  @Test
  fun `reading with no preference leaves the holder off`() {
    if (System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT") != null) return
    if (System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT_AB") != null) return
    isolateAppDataDir()
    EffectiveStreamScreenshotConfig.enabled = true // prove readConfig resets it
    CliConfigHelper.updateConfig { it }
    CliConfigHelper.readConfig()
    assertFalse(EffectiveStreamScreenshotConfig.enabled)
    assertEquals(StreamScreenshotMode.OFF, StreamScreenshotMode.resolve())
  }
}
