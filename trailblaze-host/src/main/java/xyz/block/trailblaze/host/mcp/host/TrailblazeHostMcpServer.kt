package xyz.block.trailblaze.host.mcp.host

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.JvmOpenAiApiKeyUtil
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.host.mcp.host.newtools.HostDeviceToolSet
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

class TrailblazeHostMcpServer(
  val logsRepo: LogsRepo,
  val isOnDeviceMode: () -> Boolean,
) {

  val hostMaestroAgent: HostMaestroTrailblazeAgent by lazy {
    val hostRunner = MaestroHostRunnerImpl(
      setOfMarkEnabled = true,
    )
    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
    )
  }

  val hostToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DeviceControlTrailblazeToolSet,
  )

  // Create OpenAI agent runner for this specific run
  val hostOpenAiRunner: TrailblazeRunner by lazy {
    // Create the runner
    TrailblazeRunner(
      screenStateProvider = hostMaestroAgent.maestroHostRunner.screenStateProvider,
      agent = hostMaestroAgent,
      llmClient = OpenAILLMClient(JvmOpenAiApiKeyUtil.getApiKeyFromEnv()),
      llmModel = OpenAIModels.Chat.GPT4_1,
      trailblazeToolRepo = hostToolRepo,
    )
  }

  val trailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = logsRepo,
    isOnDeviceMode = { isOnDeviceMode() },
  ) { context: TrailblazeMcpSseSessionContext, server ->
    // Provide additional tools to the MCP server
    ToolRegistry {
      tools(
        HostDeviceToolSet(
          sessionContext = context, // Session context will be set later
          logsRepo = logsRepo,
          hostOpenAiRunnerProvider = { hostOpenAiRunner },
          toolRepo = hostToolRepo,
        ).asTools(TrailblazeJsonInstance),
      )
    }
  }
}
