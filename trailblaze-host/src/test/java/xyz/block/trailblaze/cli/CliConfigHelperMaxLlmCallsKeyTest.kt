package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Behavior of the `max-llm-calls` entry in [CONFIG_KEYS]. Mirrors the shape of the
 * other CliConfigHelper-driven tests: writes the settings file to a TemporaryFolder
 * via `trailblaze.appdata.dir`, exercises the registered key's `get` / `set` lambdas,
 * and asserts on the resulting persisted `SavedTrailblazeAppConfig.maxLlmCalls`.
 */
class CliConfigHelperMaxLlmCallsKeyTest {

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
  fun `set with a positive integer persists the value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    val updated = key.set(CliConfigHelper.defaultConfig(), "25")
    assertEquals(25, updated?.maxLlmCalls)
  }

  @Test
  fun `set with zero returns null - signals invalid value to the caller`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "0"))
  }

  @Test
  fun `set with a negative integer returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "-3"))
  }

  @Test
  fun `set with a non-numeric value returns null`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "abc"))
  }

  @Test
  fun `set with 'unset' clears the persisted value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "42")
    val cleared = key.set(withValue!!, "unset")
    assertNull(cleared?.maxLlmCalls)
  }

  @Test
  fun `set with 'none' returns null - the only accepted clear-value is 'unset'`() {
    // Differs intentionally from the `llm` config key, which treats `none` as a real
    // provider sentinel. `max-llm-calls` has no analogous semantic, so we surface a single
    // clear-value (`unset`) and reject everything else as invalid.
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "none"))
  }

  @Test
  fun `get on a default config reads as '(not set)'`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    assertEquals("(not set)", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `get reflects the persisted value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("max-llm-calls")
    val updated = key.set(CliConfigHelper.defaultConfig(), "17")
    assertEquals("17", key.get(updated!!))
  }

  @Test
  fun `persisted value survives a write-then-read round trip via CliConfigHelper`() {
    isolateAppDataDir()
    // Goes through the actual disk-write path rather than just the key lambdas, to confirm
    // the new field serializes/deserializes through SavedTrailblazeAppConfig.
    CliConfigHelper.updateConfig { it.copy(maxLlmCalls = 12) }
    val reread = CliConfigHelper.readConfig()
    assertEquals(12, reread?.maxLlmCalls)
    // Settings file actually exists where we expect.
    val appDataDir = File(System.getProperty("trailblaze.appdata.dir"))
    val settingsFile = File(appDataDir, "trailblaze-settings.json")
    assertEquals(true, settingsFile.exists())
  }
}
