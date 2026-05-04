package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins [HeadlessOption.resolve]'s precedence: an explicit `--headless` flag wins, otherwise
 * the inverted `showWebBrowser` config is consulted, otherwise the default.
 *
 * Same fixture pattern as [CliConfigHelperDefaultsTest] — redirect `trailblaze.appdata.dir`
 * at a temp folder so [CliConfigHelper.readConfig] reads/writes there instead of the user's
 * home directory.
 */
class HeadlessOptionTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
  }

  private fun redirectConfigToTempFolder(): File {
    val appDataDir = tempFolder.newFolder("appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
    return appDataDir
  }

  @Test
  fun `explicit headless=true wins over config`() {
    val appDataDir = redirectConfigToTempFolder()
    // Persist showWebBrowser=true (would resolve headless=false absent the explicit flag).
    CliConfigHelper.writeConfig(
      CliConfigHelper.defaultConfig().copy(showWebBrowser = true),
    )
    File(appDataDir, "trailblaze-settings.json").apply { /* sanity */ }

    val option = HeadlessOption().also { it.headless = true }
    assertEquals(true, option.resolve())
  }

  @Test
  fun `explicit headless=false wins over config`() {
    redirectConfigToTempFolder()
    CliConfigHelper.writeConfig(
      CliConfigHelper.defaultConfig().copy(showWebBrowser = false),
    )

    val option = HeadlessOption().also { it.headless = false }
    assertEquals(false, option.resolve())
  }

  @Test
  fun `unset headless falls back to inverted showWebBrowser=false`() {
    redirectConfigToTempFolder()
    CliConfigHelper.writeConfig(
      CliConfigHelper.defaultConfig().copy(showWebBrowser = false),
    )

    val option = HeadlessOption()
    // showWebBrowser=false → headless=true
    assertEquals(true, option.resolve())
  }

  @Test
  fun `unset headless falls back to inverted showWebBrowser=true`() {
    redirectConfigToTempFolder()
    CliConfigHelper.writeConfig(
      CliConfigHelper.defaultConfig().copy(showWebBrowser = true),
    )

    val option = HeadlessOption()
    // showWebBrowser=true (visible browser) → headless=false
    assertEquals(false, option.resolve())
  }

  @Test
  fun `unset headless and missing config defaults to headless=false`() {
    // Empty appdata dir — no settings file. readConfig returns null, fallback default.
    redirectConfigToTempFolder()

    val option = HeadlessOption()
    // The kdoc says: missing config → showWebBrowser default true → headless=false.
    assertEquals(false, option.resolve())
  }

  @Test
  fun `unset headless and corrupt config defaults to headless=false`() {
    val appDataDir = redirectConfigToTempFolder()
    // Write malformed JSON — `readConfig` swallows the exception via runCatching.
    File(appDataDir, "trailblaze-settings.json").writeText("{not valid json")

    val option = HeadlessOption()
    // The runCatching in resolve() catches the parse exception, falls back to default.
    assertEquals(false, option.resolve())
  }
}
