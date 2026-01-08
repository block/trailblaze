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
import xyz.block.trailblaze.logs.client.TrailblazeSession

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
    return DefaultDynamicLlmClient(
      trailblazeLlmModel = trailblazeLlmModel,
      llmClients = mutableMapOf<LLMProvider, LLMClient>(
        LLMProvider.Ollama to OllamaClient(baseClient = cachedHttpClient),
      ).apply {
        InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")?.let { openAiApiKey ->
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
    val onDeviceRpcServer = OnDeviceRpcServer(
      sessionManager = trailblazeLoggingRule.sessionManager,
      runTrailblazeYaml = { runYamlRequest: RunYamlRequest, session: TrailblazeSession ->
        // Set the session on the logging rule so it's available to all components
        // that use sessionProvider (AndroidTrailblazeRule and its subcomponents)
        trailblazeLoggingRule.setSession(session)
        try {
          handleRunRequest(runYamlRequest)
        } finally {
          // Clear the session after execution to prevent stale sessions
          trailblazeLoggingRule.setSession(null)
        }
        session // Return the session unchanged
      },
    )
    onDeviceRpcServer.startServer(port = adbReversePort, wait = true)
  }
}
