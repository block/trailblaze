package xyz.block.trailblaze.ui

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Pins [TrailblazeSettingsRepo.bindLiveTargetProvider]: selected-target resolution
 * ([TrailblazeSettingsRepo.getCurrentSelectedTargetApp]) follows the bound provider, so a
 * target live-registered after startup (`TrailblazeDeviceManager.registerNewTarget`) resolves
 * once the manager re-points the repo at its live set — instead of returning null against the
 * startup-frozen constructor lambda.
 */
class TrailblazeSettingsRepoLiveTargetsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `selected-target resolution follows the bound live provider`() {
    val startupTarget = target("aaa")
    val liveTarget = target("ccc")
    val repo = TrailblazeSettingsRepo(
      settingsFile = File(tempFolder.newFolder("settings"), "trailblaze-settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { setOf(startupTarget) },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
    )
    repo.updateState { state ->
      state.copy(appConfig = state.appConfig.copy(selectedTargetAppId = "ccc"))
    }

    // Against the startup-frozen provider, the live-created id doesn't resolve.
    assertNull(repo.getCurrentSelectedTargetApp())

    repo.bindLiveTargetProvider { setOf(startupTarget, liveTarget) }

    assertSame(liveTarget, repo.getCurrentSelectedTargetApp())
  }
}
