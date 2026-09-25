package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A settings file written before a driver was retired still names it. Loading must replace it
 * with the platform default rather than keep it: the selected-driver map doubles as the set of
 * drivers whose devices the app lists, so a retired selection would hide every device of that
 * platform.
 */
class TrailblazeSettingsRepoRetiredDriverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * Both tests need a driver that IS retired. If the set ever empties there is nothing to migrate,
   * so they skip — `first()` would fail them instead, reporting a crash where the honest answer is
   * "not applicable". The replacement rung itself stays covered by the tests that drive it with an
   * explicit driver.
   */
  private fun aRetiredDriver(): TrailblazeDriverType {
    val retired = TrailblazeDriverType.RETIRED_DRIVERS.firstOrNull()
    assumeTrue("no driver is retired, so there is no migration to exercise", retired != null)
    return retired!!
  }

  @Test
  fun `a persisted retired driver loads as the platform default`() {
    val retired = aRetiredDriver()
    val settingsFile = File(tempFolder.newFolder("settings"), "trailblaze-settings.json")
    settingsFile.writeText(
      TrailblazeJson.defaultWithoutToolsInstance.encodeToString(
        SavedTrailblazeAppConfig.serializer(),
        SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = mapOf(
            retired.platform to retired,
            TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
          ),
        ),
      ),
    )

    val repo = TrailblazeSettingsRepo(
      settingsFile = settingsFile,
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID, TrailblazeDriverType.IOS_HOST),
    )

    val selected = repo.serverStateFlow.value.appConfig.selectedTrailblazeDriverTypes
    assertEquals(TrailblazeDriverType.defaultForPlatform(retired.platform), selected[retired.platform])
    assertEquals(TrailblazeDriverType.IOS_HOST, selected[TrailblazeDevicePlatform.IOS])
    assertFalse(
      repo.getEnabledDriverTypes().any { it in TrailblazeDriverType.RETIRED_DRIVERS },
      "a retired driver survived load, so its platform's devices would be filtered out of the listing",
    )
  }

  /**
   * The settings file has a SECOND reader: with no daemon up, the CLI decodes it directly
   * ([CliConfigHelper.readConfigRaw]) and never goes through the repo above. Without the same
   * replacement, `trailblaze config show` reported the retired driver as the current one while no
   * driver row was marked selected.
   */
  @Test
  fun `the CLI's direct settings read replaces a retired driver too`() {
    val retired = aRetiredDriver()
    val appDataDir = tempFolder.newFolder("cli-appdata")
    val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
    try {
      CliConfigHelper.getSettingsFile().writeText(
        TrailblazeJson.defaultWithoutToolsInstance.encodeToString(
          SavedTrailblazeAppConfig.serializer(),
          SavedTrailblazeAppConfig(
            selectedTrailblazeDriverTypes = mapOf(
              retired.platform to retired,
              TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
            ),
          ),
        ),
      )

      val selected = CliConfigHelper.readConfigRaw()!!.selectedTrailblazeDriverTypes

      assertEquals(TrailblazeDriverType.defaultForPlatform(retired.platform), selected[retired.platform])
      // A runnable selection on another platform is untouched — this rung only refuses.
      assertEquals(TrailblazeDriverType.IOS_HOST, selected[TrailblazeDevicePlatform.IOS])
    } finally {
      if (priorAppDataDir == null) {
        System.clearProperty("trailblaze.appdata.dir")
      } else {
        System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
      }
    }
  }
}
