package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.microsoft.playwright.Page
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail

/**
 * A browser-launched multi-device replay keeps a `web_fillSecret` value out of every later capture,
 * as a single-browser replay does: the runner dispatches a browser's recorded tools scripted.
 */
class BrowserLaunchedReplaySecretsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val launchManager = RecordingPageManager(inert(Page::class.java))

  private val noLlm = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    // Built for the run but never called: every step replays its recording.
    override fun createLlmClient(): LLMClient = OpenAILLMClient(apiKey = "not-a-real-key")
  }

  private val yaml = """
    config:
      devices:
        pair:
          devices:
            browser:
              classifier: web
              driver: PLAYWRIGHT_NATIVE
            other:
              classifier: web
              driver: PLAYWRIGHT_NATIVE
    trail:
      - step: Enter the code and continue
        recording:
          pair:
            - web_fillSecret:
                ref: css=input
                value: otp-83716
            - web_click:
                ref: css=button
  """.trimIndent()

  @Test
  fun `a secret filled in the launch browser is kept out of the next action's capture`() {
    val launchId = TrailblazeDeviceId("browser-launch", TrailblazeDevicePlatform.WEB)
    val otherId = TrailblazeDeviceId("browser-other", TrailblazeDevicePlatform.WEB)
    runBlocking {
      TrailblazeHostYamlRunner.runHostAgentWithOnDeviceRpc(
        dynamicLlmClient = noLlm,
        launchDevice = TrailblazeHostYamlRunner.DeviceConnection.WebBrowser(launchId, launchManager),
        runYamlRequest = RunYamlRequest(
          testName = "browser-launched-secret",
          yaml = yaml,
          trailFilePath = null,
          targetAppName = null,
          useRecordedSteps = true,
          trailblazeDeviceId = launchId,
          trailblazeLlmModel = TrailblazeLlmModel(
            trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
            modelId = "test-model",
            inputCostPerOneMillionTokens = 0.0,
            outputCostPerOneMillionTokens = 0.0,
            contextLength = 1000,
            maxOutputTokens = 1000,
            capabilityIds = emptyList(),
          ),
          config = TrailblazeConfig(),
          referrer = TrailblazeReferrer(id = "test", display = "Test"),
          driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        ),
        onProgressMessage = {},
        multiDeviceSession = TrailblazeHostYamlRunner.MultiDeviceSessionRpc(
          configurationName = "pair",
          startDeviceName = "browser",
          companions = mapOf(
            "other" to TrailblazeHostYamlRunner.DeviceConnection.WebBrowser(otherId, NoOpPageManager()),
          ),
          deviceDescriptions = mapOf("browser" to null, "other" to null),
        ),
        logsDir = tempFolder.newFolder("logs"),
      )
    }

    assertThat(launchManager.loggingCaptures).isEqualTo(0)
    assertThat(launchManager.replayCaptureSecrets).isNotEmpty()
    assertThat(launchManager.replayCaptureSecrets.last()).isEqualTo(setOf("otp-83716"))
  }

  /** Records which capture each tool asked for; the page accepts every call and the captures return nothing. */
  private class RecordingPageManager(override val currentPage: Page) : PlaywrightPageManager {
    var loggingCaptures = 0
    val replayCaptureSecrets = mutableListOf<Set<String>>()
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()

    override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R = action()

    override fun captureScreenStateForLogging(): ScreenState {
      loggingCaptures++
      error(UNSCRIPTED)
    }

    override fun captureTreeForReplay(withScreenshot: Boolean, secrets: Set<String>): ScreenState? {
      replayCaptureSecrets += secrets
      return null
    }

    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = error(UNSCRIPTED)
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  private companion object {
    const val UNSCRIPTED = "an unscripted capture reads the page as it is, secrets included"
  }

  /** A stand-in for a Playwright interface: every call succeeds, and one returning an interface returns another. */
  @Suppress("UNCHECKED_CAST")
  private fun <T> inert(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, _ ->
    val returns = method.returnType
    when {
      method.name == "hashCode" -> System.identityHashCode(proxy)
      method.name == "equals" -> false
      returns == String::class.java -> ""
      returns == java.lang.Boolean.TYPE -> false
      returns == Integer.TYPE -> 0
      returns == java.lang.Double.TYPE -> 0.0
      returns.isInterface -> inert(returns)
      else -> null
    }
  } as T
}
