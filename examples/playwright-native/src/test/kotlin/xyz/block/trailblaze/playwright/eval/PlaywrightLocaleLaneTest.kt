package xyz.block.trailblaze.playwright.eval

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.http.DynamicLlmClient
import kotlin.test.assertEquals

/**
 * A Spanish web lane (`web-browser-es`) runs a trail's `web-browser-es` leg in a browser whose
 * language is Spanish. The page below prints `navigator.language`, so each leg's
 * `web_verifyTextVisible` proves both which leg ran and what language the site saw: the English
 * leg asserts text that never appears, so selecting it fails the run.
 */
class PlaywrightLocaleLaneTest {

  private val offlineLlmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = OpenAILLMClient(apiKey = "not-a-real-key")
  }

  /** The `web-browser-es` lane as CI launches it: its classifier, and its language as `es`. */
  private val playwrightTest =
    BasePlaywrightNativeTest(
      dynamicLlmClient = offlineLlmClient,
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "playwright-locale-lane",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
      deviceClassifierOverride = listOf("web", "browser", "es").map(::TrailblazeDeviceClassifier),
      laneLocale = "es",
    )

  @JvmField
  @RegisterExtension
  val loggingExtension = TestRuleExtension(playwrightTest.loggingRule)

  @TempDir
  lateinit var tempDir: File

  @AfterEach
  fun tearDown() {
    playwrightTest.close()
  }

  private fun languagePage(): String =
    File(tempDir, "language.html").apply {
      writeText(
        "<p id='lang'></p>" +
          "<script>document.getElementById('lang').textContent = 'lang:' + navigator.language</script>",
      )
    }.absolutePath

  private fun run(yaml: String) = runBlocking {
    playwrightTest.runTrailblazeYamlSuspend(
      yaml = yaml,
      trailblazeDeviceId = playwrightTest.trailblazeDeviceInfo.trailblazeDeviceId,
      trailFilePath = null,
      sendSessionStartLog = true,
    )
  }

  @Test
  fun `the Spanish leg runs in the locale its device entry declares, over the lane's`() {
    run(
      """
        config:
          id: locale-lane
          devices:
            web: {}
            web-browser-es: { locale: es-US }
        trail:
          - step: Open the page and check its language
            recording:
              web:
                - web_navigate: { url: "${languagePage()}" }
                - web_verifyTextVisible: { text: "the English leg ran" }
              web-browser-es:
                - web_navigate: { url: "${languagePage()}" }
                - web_verifyTextVisible: { text: "lang:es-US" }
      """.trimIndent(),
    )

    // What the report groups and labels the session by.
    assertEquals(listOf("web", "browser", "es"), playwrightTest.trailblazeDeviceInfo.classifiers.map { it.classifier })
    assertEquals("es-US", playwrightTest.trailblazeDeviceInfo.locale)
  }

  @Test
  fun `the lane's language alone is enough when the trail declares none`() {
    run(
      """
        config:
          id: locale-lane
        trail:
          - step: Open the page and check its language
            recording:
              web:
                - web_navigate: { url: "${languagePage()}" }
                - web_verifyTextVisible: { text: "the English leg ran" }
              web-browser-es:
                - web_navigate: { url: "${languagePage()}" }
                - web_verifyTextVisible: { text: "lang:es" }
      """.trimIndent(),
    )

    assertEquals("es", playwrightTest.trailblazeDeviceInfo.locale)
  }
}
