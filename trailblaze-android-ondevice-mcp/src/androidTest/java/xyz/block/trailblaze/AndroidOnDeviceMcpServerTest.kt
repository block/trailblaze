package xyz.block.trailblaze

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.mcp.OnDeviceRpcServerUtils

/**
 * This would be the single test that runs the MCP server on port 52526.  It blocks the instrumentation test
 * so we can send prompts/etc.
 */
class AndroidOnDeviceMcpServerTest {

  @get:Rule
  val trailblazeLoggingRule = TrailblazeAndroidLoggingRule(
    sendStartAndEndLogs = false,
  )

  @Test
  fun mcpServer() {
    OnDeviceRpcServerUtils(
      runTrailblazeYaml = { runYamlRequest ->
        handleRunRequest(runYamlRequest)
      },
    ).startServer(52526, true)
  }

  private fun handleRunRequest(runYamlRequest: RunYamlRequest) {
    val openAiApiKey: String? = InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")
    val baseClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 120,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )

    val defaultDynamicLlmClient = DefaultDynamicLlmClient(
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
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

    AndroidTrailblazeRule(
      trailblazeLlmModel = defaultDynamicLlmClient.trailblazeLlmModel,
      llmClient = defaultDynamicLlmClient.createLlmClient(),
      additionalRules = listOf(),
    ).run(
      testYaml = runYamlRequest.yaml,
      useRecordedSteps = runYamlRequest.useRecordedSteps,
    )
  }
}
