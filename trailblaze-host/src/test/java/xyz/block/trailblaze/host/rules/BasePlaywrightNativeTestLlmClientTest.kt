package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.NoOpPageManager
import xyz.block.trailblaze.http.DynamicLlmClient

/**
 * Pins [BasePlaywrightNativeTest.hasCallerLlmClient], which decides whether a cached browser test
 * is rebuilt before a run that brings the app's LLM client. The rule falls back to a default
 * client when it is given none, so the flag has to record whether the caller passed one, not
 * whether the rule ended up with one - the second is always true.
 */
class BasePlaywrightNativeTestLlmClientTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "llm-client-test-web",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
  )

  private val callerClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = error("no LLM in this test")
  }

  private fun rule(client: DynamicLlmClient?) = BasePlaywrightNativeTest(
    dynamicLlmClient = client,
    trailblazeDeviceId = deviceId,
    // Adopting a stub manager keeps the constructor from launching a real browser.
    existingBrowserManager = NoOpPageManager(),
    noLogging = true,
  )

  @Test
  fun `a rule given the app's client says so and uses it`() {
    val rule = rule(callerClient)
    assertThat(rule.hasCallerLlmClient).isTrue()
    assertThat(rule.dynamicLlmClient).isSameInstanceAs(callerClient)
  }

  @Test
  fun `a rule given no client says so, though it still has the default one`() {
    val rule = rule(null)
    assertThat(rule.hasCallerLlmClient).isFalse()
    assertThat(rule.dynamicLlmClient is TrailblazeHostDynamicLlmClientProvider).isTrue()
  }
}
