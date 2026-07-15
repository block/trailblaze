package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget

class CliConfigHelperDefaultsTest {

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

  @Test
  fun `defaultConfig derives sibling logs and trails paths and enables web driver`() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)

    val config = CliConfigHelper.defaultConfig()

    assertEquals(appDataDir.canonicalPath, config.appDataDirectory)
    assertEquals(File(appDataDir.parentFile, "logs").canonicalPath, config.logsDirectory)
    assertEquals(File(appDataDir.parentFile, "trails").canonicalPath, config.trailsDirectory)
    assertEquals(
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      config.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.WEB],
    )
  }

  @Test
  fun `target selection stays null unless the user picks one (tri-state)`() {
    // A fabricated selectedTargetAppId (e.g. "default") persisted without user intent would
    // outrank / mask the workspace `defaults.target` rung on every later resolution.
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)

    assertNull(CliConfigHelper.defaultConfig().selectedTargetAppId)

    // First-run write path (getOrCreateConfig) must not persist a selection either.
    CliConfigHelper.getOrCreateConfig()
    assertNull(CliConfigHelper.readConfigRaw()?.selectedTargetAppId)

    // Read-side hydration must not materialize one, and a config mutation that
    // doesn't touch the target must not write one.
    CliConfigHelper.updateConfig { it }
    assertNull(CliConfigHelper.readConfig()?.selectedTargetAppId)
    assertNull(CliConfigHelper.readConfigRaw()?.selectedTargetAppId)
  }

  @Test
  fun `updateConfig persists hydrated runtime paths for partial CLI settings`() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
    val settingsFile = File(appDataDir, "trailblaze-settings.json")
    settingsFile.writeText(
      """
      {
        "selectedTrailblazeDriverTypes": {
          "ANDROID": "ANDROID_ONDEVICE_INSTRUMENTATION",
          "IOS": "IOS_HOST"
        },
        "selectedTargetAppId": "default"
      }
      """.trimIndent(),
    )

    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = DefaultTrailblazeHostAppTarget.id) }

    val updated = CliConfigHelper.readConfigRaw()
    assertEquals(appDataDir.canonicalPath, updated?.appDataDirectory)
    assertEquals(File(appDataDir.parentFile, "logs").canonicalPath, updated?.logsDirectory)
    assertEquals(File(appDataDir.parentFile, "trails").canonicalPath, updated?.trailsDirectory)
    assertEquals(
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      updated?.selectedTrailblazeDriverTypes?.get(TrailblazeDevicePlatform.WEB),
    )
    assertTrue(
      settingsFile.readText().contains("PLAYWRIGHT_NATIVE"),
      "rewritten CLI settings should preserve the default WEB driver",
    )
  }
}
