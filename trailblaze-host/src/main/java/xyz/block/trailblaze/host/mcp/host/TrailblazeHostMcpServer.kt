package xyz.block.trailblaze.host.mcp.host

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.JvmOpenAiApiKeyUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.host.mcp.host.newtools.HostDeviceToolSet
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

class TrailblazeHostMcpServer(
  val logsRepo: LogsRepo,
  val isOnDeviceMode: () -> Boolean,
) {
  val loggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = {
      // Placeholder until hostRunner is initialized
      TrailblazeDeviceInfo(
        trailblazeDriverType = xyz.block.trailblaze.devices.TrailblazeDriverType.ANDROID_HOST,
        widthPixels = 0,
        heightPixels = 0,
      )
    },
  ).apply {
    CoroutineScope(Dispatchers.IO).launch {
      subscribeToLoggingEventsAndSendToServer()
    }
  }

  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      setOfMarkEnabled = true,
      trailblazeLogger = loggingRule.trailblazeLogger,
    )
  }

  private val trailblazeDeviceInfoProvider: (() -> TrailblazeDeviceInfo) by lazy {
    {
      TrailblazeDeviceInfo(
        trailblazeDriverType = hostRunner.connectedTrailblazeDriverType,
        widthPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.widthPixels,
        heightPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.heightPixels,
      )
    }
  }

  val hostMaestroAgent: HostMaestroTrailblazeAgent by lazy {
    loggingRule.trailblazeLogger.sendStartLog(
      trailConfig = null,
      className = "TrailblazeHostMcpServer",
      methodName = "TrailblazeHostMcpServer",
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
    )

    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
      trailblazeLogger = loggingRule.trailblazeLogger,
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
      trailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
      trailblazeToolRepo = hostToolRepo,
      trailblazeLogger = loggingRule.trailblazeLogger,
      sessionManager = loggingRule.sessionManager,
    )
  }

  val trailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = logsRepo,
    isOnDeviceMode = { isOnDeviceMode() },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
  ) { context: TrailblazeMcpSseSessionContext, server ->
    // Provide additional tools to the MCP server
    ToolRegistry {
      tools(
        HostDeviceToolSet(
          sessionContext = context, // Session context will be set later
          logsRepo = logsRepo,
          hostOpenAiRunnerProvider = { hostOpenAiRunner },
          toolRepo = hostToolRepo,
          trailblazeLogger = loggingRule.trailblazeLogger,
        ).asTools(TrailblazeJsonInstance),
      )
    }
  }
}
