package xyz.block.trailblaze

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.BaseAndroidStandaloneServerTest
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.OnDeviceOpenAICompatibleLlmClientFactory
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

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

  override fun handleRunRequest(
    runYamlRequest: RunYamlRequest,
    agentMemory: AgentMemory,
  ): TrailblazeToolResult.Success? {
    this.trailblazeDeviceId = runYamlRequest.trailblazeDeviceId
    // Propagate the runtime driver type so session logs reflect the actual driver
    runYamlRequest.driverType?.let { trailblazeLoggingRule.driverTypeOverride = it }
    val androidTrailblazeRule = AndroidTrailblazeRule(
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      llmClient = getDynamicLlmClient(runYamlRequest.trailblazeLlmModel).createLlmClient(),
      config = runYamlRequest.config,
      trailblazeDeviceId = this.trailblazeDeviceId,
      trailblazeLoggingRule = trailblazeLoggingRule,
      agentMemoryOverride = agentMemory,
      maxLlmCalls = runYamlRequest.maxLlmCalls,
      // Honor the agent the host selected for THIS request (e.g. --agent KOOG_STRATEGY_GRAPH),
      // rather than the process-wide `trailblaze.agent` instrumentation arg.
      agentImplementationOverride = runYamlRequest.agentImplementation,
    )
    var lastToolSuccess: TrailblazeToolResult.Success? = null
    startInTestCoroutineScope {
      lastToolSuccess = androidTrailblazeRule.runSuspend(
        testYaml = runYamlRequest.yaml,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        trailFilePath = runYamlRequest.trailFilePath,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog
      )
    }
    return lastToolSuccess
  }

  override fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient {
    // Reuse the cached HTTP client to prevent "unknown client" errors
    val ollamaBaseUrl =
      InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.BASE_URL_ARG)
    // Koog 1.0.0: LLM clients no longer take a raw Ktor `HttpClient`. Wrap our cached
    // on-device client in a `KtorKoogHttpClient.Factory` so its config flows through.
    val httpClientFactory = KtorKoogHttpClient.Factory(baseClient = cachedHttpClient)
    val llmClients = mutableMapOf<LLMProvider, LLMClient>(
      TrailblazeLlmProvider.NONE.toKoogLlmProvider() to NoOpLlmClient(),
      LLMProvider.Ollama to OllamaClient(
        baseUrl = ollamaBaseUrl ?: "http://localhost:11434",
        httpClientFactory = httpClientFactory,
      ),
    )
    InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.resolve(TrailblazeLlmProvider.OPENAI))?.let { openAiApiKey ->
      llmClients[LLMProvider.OpenAI] = OpenAILLMClient(
        apiKey = openAiApiKey,
        httpClientFactory = httpClientFactory,
      )
    }
    // Custom openai_compatible providers from the workspace `trailblaze.yaml` arrive via
    // instrumentation args and are rebuilt on-device by [OnDeviceOpenAICompatibleLlmClientFactory].
    // The host only emits PROVIDER_TYPE_ARG=openai_compatible for the *currently selected*
    // provider, so reaching a non-null result here means the user explicitly configured this
    // provider as openai_compatible — register/replace under the active provider id even when
    // it collides with a built-in (e.g. redefining `providers.openai` with a custom base_url).
    OnDeviceOpenAICompatibleLlmClientFactory
      .createOrNull(trailblazeLlmModel, cachedHttpClient)
      ?.let { customClient ->
        llmClients[trailblazeLlmModel.trailblazeLlmProvider.toKoogLlmProvider()] = customClient
      }
    return DefaultDynamicLlmClient(
      trailblazeLlmModel = trailblazeLlmModel,
      llmClients = llmClients,
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
    onDeviceRpcServer.startServer(port = onDeviceRpcPort, wait = true)
  }
}
