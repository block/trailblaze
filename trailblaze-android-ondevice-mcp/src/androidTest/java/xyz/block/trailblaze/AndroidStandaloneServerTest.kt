package xyz.block.trailblaze

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.BaseAndroidStandaloneServerTest
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.runner.rpc.OnDeviceRpcServer
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.DynamicLlmClient
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

  override fun handleRunRequest(runYamlRequest: RunYamlRequest) {
    startInTestCoroutineScope {
      AndroidTrailblazeRule(
        trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
        llmClient = getDynamicLlmClient(runYamlRequest.trailblazeLlmModel).createLlmClient(),
        config = runYamlRequest.config,
        trailblazeDeviceId = runYamlRequest.trailblazeDeviceId,
        trailblazeLoggingRule = trailblazeLoggingRule,
      ).runSuspend(
        testYaml = runYamlRequest.yaml,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        trailFilePath = runYamlRequest.trailFilePath,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog
      )
    }
  }

  override fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient {
    val openAiApiKey: String? = InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")
    val baseClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 120,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )
    return DefaultDynamicLlmClient(
      trailblazeLlmModel = trailblazeLlmModel,
      llmClients = mutableMapOf<LLMProvider, LLMClient>(
        LLMProvider.Ollama to OllamaClient(baseClient = baseClient),
      ).apply {
        openAiApiKey?.let {
          put(
            LLMProvider.OpenAI,
            OpenAILLMClient(
              baseClient = baseClient,
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
    val onDeviceRpcServer = OnDeviceRpcServer(
      runTrailblazeYaml = { runYamlRequest: RunYamlRequest ->
        handleRunRequest(runYamlRequest)
      },
    )
    onDeviceRpcServer.startServer(port = adbReversePort, wait = true)
  }
}
