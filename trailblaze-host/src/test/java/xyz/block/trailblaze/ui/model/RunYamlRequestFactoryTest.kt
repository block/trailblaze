package xyz.block.trailblaze.ui.model

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Pins [RunYamlRequestFactory]'s target-attribution contract: the request's
 * `targetAppName` comes from the injected effective-target resolution (persisted
 * selection → workspace `defaults.target`), NOT the raw persisted
 * [SavedTrailblazeAppConfig.selectedTargetAppId]. A run resolved through the
 * workspace default would otherwise record `targetAppName = null` and lose the
 * target attribution in session logs / reports.
 */
class RunYamlRequestFactoryTest {

  private fun factory(
    persistedTargetId: String?,
    effectiveTargetAppId: () -> String?,
  ) = RunYamlRequestFactory(
    appConfig = SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = emptyMap(),
      selectedTargetAppId = persistedTargetId,
    ),
    llmModel = TrailblazeLlmModels.GPT_4O_MINI,
    effectiveTargetAppId = effectiveTargetAppId,
  )

  private val device = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    instanceId = "emulator-5554",
    description = "test emulator",
  )

  @Test
  fun `create records the effective target id not the raw persisted selection`() {
    // Persisted selection is null (workspace-default scenario) but the effective
    // resolution yields a target — the request must carry the effective id.
    val request = factory(persistedTargetId = null, effectiveTargetAppId = { "alpha" })
      .create(
        device = device,
        yaml = "- pressBack",
        testName = "test",
        referrer = TrailblazeReferrer.YAML_TAB,
      )

    assertEquals("alpha", request.targetAppName)
  }

  @Test
  fun `create prefers the effective target over a non-null raw persisted selection`() {
    // The airtight regression guard: a non-null persisted id that DIFFERS from the effective
    // resolution. A regression that read `appConfig.selectedTargetAppId` directly would record
    // "raw" here; the contract requires the effective "alpha".
    val request = factory(persistedTargetId = "raw", effectiveTargetAppId = { "alpha" })
      .create(
        device = device,
        yaml = "- pressBack",
        testName = "test",
        referrer = TrailblazeReferrer.YAML_TAB,
      )

    assertEquals("alpha", request.targetAppName)
  }

  @Test
  fun `create records null when no effective target resolves`() {
    val request = factory(persistedTargetId = null, effectiveTargetAppId = { null })
      .create(
        device = device,
        yaml = "- pressBack",
        testName = "test",
        referrer = TrailblazeReferrer.YAML_TAB,
      )

    assertNull(request.targetAppName)
  }
}
