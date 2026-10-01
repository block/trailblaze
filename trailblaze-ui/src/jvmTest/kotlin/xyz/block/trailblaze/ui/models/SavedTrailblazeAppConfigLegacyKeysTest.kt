package xyz.block.trailblaze.ui.models

import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings files outlive the fields they were written with. A key this build no longer declares
 * must be skipped on load, or one removed preference would reset every other saved setting.
 */
class SavedTrailblazeAppConfigLegacyKeysTest {

  @Test
  fun `settings saved with an agent choice still load`() {
    // `agentImplementation` was written by builds that let users pick an agent.
    val saved = """
      {
        "selectedTrailblazeDriverTypes": {},
        "agentImplementation": "TRAILBLAZE_RUNNER",
        "maxLlmCalls": 7
      }
    """.trimIndent()

    val config = TrailblazeJson.defaultWithoutToolsInstance.decodeFromString(
      SavedTrailblazeAppConfig.serializer(),
      saved,
    )

    assertEquals(7, config.maxLlmCalls)
  }
}
