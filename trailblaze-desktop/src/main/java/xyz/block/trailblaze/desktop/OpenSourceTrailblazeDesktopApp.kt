package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.compose.driver.tools.ComposeToolSet
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console

/**
 * The Open Source Trailblaze Desktop App
 */
class OpenSourceTrailblazeDesktopApp : TrailblazeDesktopApp(
  desktopAppConfig = OpenSourceTrailblazeDesktopAppConfig()
) {

  init {
    TrailblazeJsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
      allToolClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName
        + ComposeToolSet.toolClassesByToolName
        + desktopAppConfig.availableAppTargets.flatMap { it.getAllCustomToolClassesForSerialization() }
        .associateBy { it.toolName() },
    )
  }

  fun createDynamicClient(trailblazeLlmModel: TrailblazeLlmModel): TrailblazeHostDynamicLlmClientProvider {
    return TrailblazeHostDynamicLlmClientProvider(
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider
    )
  }

  override fun startTrailblazeDesktopApp(headless: Boolean) {
    installRunHandler()
    MainTrailblazeApp(
      trailblazeSavedSettingsRepo = desktopAppConfig.trailblazeSettingsRepo,
      logsRepo = desktopAppConfig.logsRepo,
      trailblazeMcpServerProvider = { trailblazeMcpServer },
    ).runTrailblazeApp(
      allTabs = {
        desktopAppConfig.getTabs(
          deviceManager = deviceManager,
          yamlRunner = { desktopYamlRunner.runYaml(it) },
          mcpServerDebugStateFlow = trailblazeMcpServer.mcpServerDebugStateFlow,
        )
      },
      deviceManager = deviceManager,
      headless = headless,
    )
  }

  override val deviceManager: TrailblazeDeviceManager by lazy {
    TrailblazeDeviceManager(
      settingsRepo = desktopAppConfig.trailblazeSettingsRepo,
      currentTrailblazeLlmModelProvider = { desktopAppConfig.getCurrentLlmModel() },
      availableAppTargets = desktopAppConfig.availableAppTargets,
      appIconProvider = desktopAppConfig.appIconProvider,
      deviceClassifierIconProvider = desktopAppConfig.deviceClassifierIconProvider,
      defaultHostAppTarget = desktopAppConfig.defaultAppTarget,
      runYamlLambda = { desktopYamlRunner.runYaml(it) },
      installedAppIdsProviderBlocking = { desktopAppConfig.getInstalledAppIds(it) },
      appVersionInfoProviderBlocking = { deviceId, appId -> desktopAppConfig.getAppVersionInfo(deviceId, appId) },
      logsRepo = desktopAppConfig.logsRepo,
      onDeviceInstrumentationArgsProvider = {
        JvmLLMProvidersUtil.getAdditionalInstrumentationArgs()
      },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp
    )
  }

  override val desktopYamlRunner: DesktopYamlRunner by lazy {
    DesktopYamlRunner(
      trailblazeDeviceManager = deviceManager,
      trailblazeHostAppTargetProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      dynamicLlmClientProvider = { createDynamicClient(it) },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp
    )
  }

  val mcpBridge: TrailblazeMcpBridge by lazy {
    TrailblazeMcpBridgeImpl(
      trailblazeDeviceManager = deviceManager,
      logsRepo = desktopAppConfig.logsRepo,
    )
  }

  override val trailblazeMcpServer by lazy {
    TrailblazeMcpServer(
      logsRepo = desktopAppConfig.logsRepo,
      mcpBridge = mcpBridge,
      trailsDirProvider = { desktopAppConfig.trailblazeSettingsRepo.getCurrentTrailsDir() },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      // Wire up LLM client/model for Koog agent's local sampling fallback
      llmClientProvider = {
        try {
          val currentModel = desktopAppConfig.getCurrentLlmModel()
          createDynamicClient(currentModel).createLlmClient()
        } catch (e: Exception) {
          // LLM not configured - will fall back to MCP client sampling
          Console.log("[MCP Server] LLM client not available: ${e.message}")
          null
        }
      },
      llmModelProvider = { desktopAppConfig.getCurrentLlmModel() },
      llmModelListsProvider = { desktopAppConfig.getAllSupportedLlmModelLists() },
    )
  }

}
