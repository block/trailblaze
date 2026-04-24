package xyz.block.trailblaze

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.BaseAndroidStandaloneServerTest
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.accessibility.OnDeviceAccessibilityServiceSetup
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.runner.rpc.OnDeviceRpcServer
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.http.NoOpLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel

/**
 * This would be the single test that runs the MCP server.  It blocks the instrumentation test
 * so we can send prompts/etc.
 *
 * OPEN SOURCE VERSION
 */
class AndroidStandaloneServerTest : BaseAndroidStandaloneServerTest() {

  // Cache the HTTP client to prevent "unknown client" errors on subsequent calls
  private val cachedHttpClient by lazy {
    TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 120,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )
  }

  override fun handleRunRequest(runYamlRequest: RunYamlRequest) {
    this.trailblazeDeviceId = runYamlRequest.trailblazeDeviceId
    // Propagate the runtime driver type so session logs reflect the actual driver
    runYamlRequest.driverType?.let { trailblazeLoggingRule.driverTypeOverride = it }
    val androidTrailblazeRule = AndroidTrailblazeRule(
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      llmClient = getDynamicLlmClient(runYamlRequest.trailblazeLlmModel).createLlmClient(),
      config = runYamlRequest.config,
      trailblazeDeviceId = this.trailblazeDeviceId,
      trailblazeLoggingRule = trailblazeLoggingRule
    )
    startInTestCoroutineScope {
      androidTrailblazeRule.runSuspend(
        testYaml = runYamlRequest.yaml,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        trailFilePath = runYamlRequest.trailFilePath,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog
      )
    }
  }

  override fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient {
    // Reuse the cached HTTP client to prevent "unknown client" errors
    val ollamaBaseUrl =
      InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.BASE_URL_ARG)
    return DefaultDynamicLlmClient(
      trailblazeLlmModel = trailblazeLlmModel,
      llmClients = mutableMapOf<LLMProvider, LLMClient>(
        TrailblazeLlmProvider.NONE.toKoogLlmProvider() to NoOpLlmClient(),
        LLMProvider.Ollama to OllamaClient(
          baseUrl = ollamaBaseUrl ?: "http://localhost:11434",
          baseClient = cachedHttpClient,
        ),
      ).apply {
        InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.resolve(TrailblazeLlmProvider.OPENAI))?.let { openAiApiKey ->
          put(
            LLMProvider.OpenAI,
            OpenAILLMClient(
              baseClient = cachedHttpClient,
              apiKey = openAiApiKey,
            ),
          )
        }
      },
    )
  }

  override fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier> {
    return TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers()
  }

  @Test
  fun startServer() {
    // Configure UiAutomation to not suppress accessibility services before starting the
    // RPC server. The host enables the accessibility service via ADB after instrumentation
    // connects, so UiAutomation must already be configured by that point.
    OnDeviceAccessibilityServiceSetup.ensureUiAutomationDoesNotSuppressAccessibility()

    val onDeviceRpcServer = OnDeviceRpcServer(
      loggingRule = trailblazeLoggingRule,
      runTrailblazeYaml = createRunTrailblazeYamlCallback(),
      trailblazeDeviceInfoProvider = { deviceId ->
        // Set the lateinit property early so the logging rule's provider can access it
        this.trailblazeDeviceId = deviceId
        trailblazeLoggingRule.trailblazeDeviceInfoProvider()
      },
      deviceClassifiers = getDeviceClassifiers(),
    )
    onDeviceRpcServer.startServer(port = adbReversePort, wait = true)
  }
}
