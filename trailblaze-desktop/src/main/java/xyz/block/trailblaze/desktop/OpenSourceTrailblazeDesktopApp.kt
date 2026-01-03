package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * The Open Source Trailblaze Desktop App
 */
class OpenSourceTrailblazeDesktopApp : TrailblazeDesktopApp(
  desktopAppConfig = OpenSourceTrailblazeDesktopAppConfig()
) {

  fun createDynamicClient(trailblazeLlmModel: TrailblazeLlmModel): TrailblazeHostDynamicLlmClientProvider {
    return TrailblazeHostDynamicLlmClientProvider(
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider
    )
  }

  override fun startTrailblazeDesktopApp() {
    MainTrailblazeApp(
      trailblazeSavedSettingsRepo = desktopAppConfig.trailblazeSettingsRepo,
      logsRepo = desktopAppConfig.logsRepo,
      yamlRunner = { desktopRunYamlParams: DesktopAppRunYamlParams ->
        desktopYamlRunner.runYaml(desktopRunYamlParams)
      },
      recordedTrailsRepo = desktopAppConfig.recordedTrailsRepo,
      trailblazeMcpServerProvider = { trailblazeMcpServer },
      customEnvVarNames = emptyList(),
    ).runTrailblazeApp(
      customTabs = { listOf() },
      availableModelLists = desktopAppConfig.getCurrentlyAvailableLlmModelLists(),
      deviceManager = deviceManager,
      additionalInstrumentationArgs = { emptyMap() },
      globalSettingsContent = { },
      currentTrailblazeLlmModelProvider = { desktopAppConfig.getCurrentLlmModel() },
    )
  }

  override val deviceManager: TrailblazeDeviceManager by lazy {
    TrailblazeDeviceManager(
      settingsRepo = desktopAppConfig.trailblazeSettingsRepo,
      currentTrailblazeLlmModelProvider = { desktopAppConfig.getCurrentLlmModel() },
      availableAppTargets = desktopAppConfig.availableAppTargets,
      appIconProvider = desktopAppConfig.appIconProvider,
      defaultHostAppTarget = desktopAppConfig.defaultAppTarget,
      runYamlLambda = { desktopYamlRunner.runYaml(it) },
      installedAppIdsProviderBlocking = { desktopAppConfig.getInstalledAppIds(it) },
      logsRepo = desktopAppConfig.logsRepo,
      onDeviceInstrumentationArgsProvider = {
        JvmLLMProvidersUtil.getAdditionalInstrumentationArgs()
      }
    )
  }

  override val desktopYamlRunner: DesktopYamlRunner by lazy {
    DesktopYamlRunner(
      trailblazeDeviceManager = deviceManager,
      trailblazeHostAppTargetProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      dynamicLlmClientProvider = { createDynamicClient(it) }
    )
  }

  val mcpBridge: TrailblazeMcpBridge by lazy {
    TrailblazeMcpBridgeImpl(
      trailblazeDeviceManager = deviceManager,
    )
  }

  override val trailblazeMcpServer by lazy {
    TrailblazeMcpServer(
      logsRepo = desktopAppConfig.logsRepo,
      mcpBridge = mcpBridge,
      trailsDirProvider = { desktopAppConfig.trailblazeSettingsRepo.getCurrentTrailsDir() },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    )
  }

}
