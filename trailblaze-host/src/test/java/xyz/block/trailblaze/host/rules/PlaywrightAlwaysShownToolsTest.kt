package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import kotlin.reflect.KClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.NoOpPageManager
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.http.NoOpLlmClient
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins that a web target's `always_shown_tools` reach the Playwright-native agent, so the decision
 * engine keeps them visible when it hides tools. `BasePlaywrightElectronTest` has the same wiring
 * but can't be built here: its constructor launches a real Electron app.
 */
class PlaywrightAlwaysShownToolsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val llmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = NoOpLlmClient()
  }

  private fun target(alwaysShown: Map<TrailblazeDriverType, Set<String>>) =
    object : TrailblazeHostAppTarget(id = "web-app", displayName = "Web App") {
      override fun getAlwaysShownToolNamesForDriver(driverType: TrailblazeDriverType): Set<String> =
        alwaysShown[driverType].orEmpty()

      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()
    }

  private fun agentAlwaysShownTools(appTarget: TrailblazeHostAppTarget?): Set<String> =
    BasePlaywrightNativeTest(
      dynamicLlmClient = llmClient,
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "always-shown-web", trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB),
      // Adopting a stub manager keeps the constructor from launching a real browser.
      existingBrowserManager = NoOpPageManager(),
      logsDir = tempFolder.root,
      noLogging = true,
      appTarget = appTarget,
    ).agentRunner.alwaysShownTools

  @Test
  fun `the agent gets the target's always-shown tools for Playwright native`() {
    val appTarget = target(
      mapOf(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE to setOf("web_app_search"),
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY to setOf("android_app_swipe"),
      ),
    )

    assertThat(agentAlwaysShownTools(appTarget)).containsOnly("web_app_search")
  }

  @Test
  fun `the agent gets no always-shown tools without a target`() {
    assertThat(agentAlwaysShownTools(appTarget = null)).isEmpty()
  }
}
