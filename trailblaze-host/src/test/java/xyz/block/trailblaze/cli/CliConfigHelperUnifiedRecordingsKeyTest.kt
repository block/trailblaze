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
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter

/**
 * Behavior of the `unified-recordings` entry in [CONFIG_KEYS]. Mirrors the shape of
 * [CliConfigHelperMaxLlmCallsKeyTest]: writes the settings file to a TemporaryFolder via
 * `trailblaze.appdata.dir`, exercises the registered key's `get` / `set` lambdas, and asserts on
 * the persisted `SavedTrailblazeAppConfig.unifiedRecordingsEnabled`.
 *
 * The field is deliberately tri-state (`Boolean? = null`): `null` means "no preference recorded"
 * and inherits the framework default from [UnifiedRecordingWriter.resolveGate]; an explicit
 * `true`/`false` is the user's choice and must be persisted EVEN WHEN it matches the current
 * default, so it keeps meaning what the user said if the default ever changes. The
 * serialization-contract tests below pin that.
 */
class CliConfigHelperUnifiedRecordingsKeyTest {

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

  private fun settingsFile(): File =
    File(File(System.getProperty("trailblaze.appdata.dir")), "trailblaze-settings.json")

  @Test
  fun `set with true or false persists the explicit choice`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("unified-recordings")
    assertEquals(true, key.set(CliConfigHelper.defaultConfig(), "true")?.unifiedRecordingsEnabled)
    assertEquals(false, key.set(CliConfigHelper.defaultConfig(), "false")?.unifiedRecordingsEnabled)
  }

  @Test
  fun `set with 'unset' clears the preference back to inherit-the-default`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("unified-recordings")
    val withValue = key.set(CliConfigHelper.defaultConfig(), "false")
    val cleared = key.set(withValue!!, "unset")
    assertNull(cleared?.unifiedRecordingsEnabled)
  }

  @Test
  fun `set with an unrecognized value returns null - signals invalid value to the caller`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("unified-recordings")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "yes"))
  }

  @Test
  fun `get on a default config reads as '(not set)'`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("unified-recordings")
    assertEquals("(not set)", key.get(CliConfigHelper.defaultConfig()))
  }

  @Test
  fun `get reflects the persisted value`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("unified-recordings")
    assertEquals("false", key.get(key.set(CliConfigHelper.defaultConfig(), "false")!!))
  }

  // ---------------------------------------------------------------------------
  // Serialization contract: explicit choices persist, no-preference stays absent
  // ---------------------------------------------------------------------------

  @Test
  fun `an explicit choice is written to disk even when it matches the current default`() {
    // The trap this guards against: with encodeDefaults=false and a NON-null field default, an
    // explicit choice equal to the default is silently dropped from the file — and then silently
    // flips if the default ever changes. The nullable field makes any explicit value non-default,
    // so it must appear in the persisted JSON.
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(unifiedRecordingsEnabled = true) }
    assertTrue(settingsFile().readText().contains("unifiedRecordingsEnabled"))
    assertEquals(true, CliConfigHelper.readConfig()?.unifiedRecordingsEnabled)
  }

  @Test
  fun `no recorded preference stays absent from the file and inherits the current default`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it } // write the default config untouched
    assertFalse(settingsFile().readText().contains("unifiedRecordingsEnabled"))
    assertNull(CliConfigHelper.readConfig()?.unifiedRecordingsEnabled)
  }

  @Test
  fun `a persisted opt-out drives the resolved gate off`() {
    // Env var outranks persisted config; skip in an environment that sets it.
    if (System.getenv(UnifiedRecordingWriter.ENV_UNIFIED_RECORDINGS) != null) return
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(unifiedRecordingsEnabled = false) }
    assertFalse(CliConfigHelper.resolveUnifiedRecordingsGate())
  }
}
