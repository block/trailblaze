package xyz.block.trailblaze

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.mcp.DefaultDynamicLlmClient
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
    val defaultDynamicLlmClient = DefaultDynamicLlmClient(runYamlRequest.dynamicLlmConfig)

    AndroidTrailblazeRule(
      llmModel = defaultDynamicLlmClient.createLlmModel(),
      llmClient = defaultDynamicLlmClient.createLlmClient(),
      additionalRules = listOf(),
    ).run(
      testYaml = runYamlRequest.yaml,
      useRecordedSteps = runYamlRequest.useRecordedSteps,
    )
  }
}
