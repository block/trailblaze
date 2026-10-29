package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A settings file written by an older version still carries fields that have since been removed
 * (`showWaypointsTab`). Loading it must keep the user's other settings. A decode failure is not
 * loud: [TrailblazeSettingsRepo.load] falls back to the defaults and overwrites the file, so every
 * saved setting would be silently reset on upgrade.
 */
class TrailblazeSettingsRepoRemovedFieldTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `a settings file with a removed field keeps the user's other settings`() {
    val saved = SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = emptyMap(),
      showDevicesTab = true,
      trailsDirectory = "/my/trails",
    )
    val encoded = TrailblazeJson.defaultWithoutToolsInstance.encodeToJsonElement(
      SavedTrailblazeAppConfig.serializer(),
      saved,
    ).jsonObject
    val legacy = JsonObject(encoded + ("showWaypointsTab" to JsonPrimitive(false)))
    val settingsFile = File(tempFolder.newFolder("settings"), "trailblaze-settings.json")
    settingsFile.writeText(legacy.toString())

    val repo = TrailblazeSettingsRepo(
      settingsFile = settingsFile,
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
    )

    val loaded = repo.serverStateFlow.value.appConfig
    assertEquals(true, loaded.showDevicesTab)
    assertEquals("/my/trails", loaded.trailsDirectory)
  }
}
