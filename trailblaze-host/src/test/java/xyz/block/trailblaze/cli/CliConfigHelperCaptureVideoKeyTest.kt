package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behavior of the `capture-video` entry in [CONFIG_KEYS] — the persistent opt-in for session video.
 *
 * Unlike the experimental tri-state keys, this one is a plain boolean matching its sibling capture
 * toggles (`captureLogcat` / `captureIosLogs`); it just defaults OFF. It is the only way interactive
 * `trailblaze session start` and MCP sessions can turn video on, because neither exposes a positive
 * per-run video flag.
 */
class CliConfigHelperCaptureVideoKeyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restore() {
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

  private fun settingsFile(): File =
    File(File(System.getProperty("trailblaze.appdata.dir")), "trailblaze-settings.json")

  @Test
  fun `video is off in a default config`() {
    isolateAppDataDir()
    assertEquals(false, CliConfigHelper.defaultConfig().captureVideo)
    assertEquals("false", CONFIG_KEYS.getValue("capture-video").get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `set with true or false persists the choice`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("capture-video")
    assertEquals(true, key.set(CliConfigHelper.defaultConfig(), "true")?.captureVideo)
    assertEquals(false, key.set(CliConfigHelper.defaultConfig(), "false")?.captureVideo)
  }

  @Test
  fun `set with an unrecognized value returns null`() {
    isolateAppDataDir()
    assertNull(CONFIG_KEYS.getValue("capture-video").set(CliConfigHelper.defaultConfig(), "yes"))
  }

  @Test
  fun `an opt-in survives a write-then-read round trip`() {
    // The daemon reads this file to resolve capture for MCP / interactive sessions, so the opt-in
    // has to actually reach disk — an in-memory-only toggle would silently do nothing there.
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(captureVideo = true) }
    assertTrue(settingsFile().readText().contains("captureVideo"))
    assertEquals(true, CliConfigHelper.readConfig()?.captureVideo)
  }
}
