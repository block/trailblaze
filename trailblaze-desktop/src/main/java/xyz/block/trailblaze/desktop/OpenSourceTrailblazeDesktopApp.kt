package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeDesktopApp

/**
 * The Open Source Trailblaze Desktop App
 */
class OpenSourceTrailblazeDesktopApp : TrailblazeDesktopApp(
  desktopAppConfig = OpenSourceTrailblazeDesktopAppConfig()
) {

  override val trailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = desktopAppConfig.logsRepo,
    trailsDirProvider = { desktopAppConfig.trailblazeSettingsRepo.getCurrentTrailsDir() },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
  )

  fun createDynamicClient(trailblazeLlmModel: TrailblazeLlmModel): TrailblazeHostDynamicLlmClientProvider {
    return TrailblazeHostDynamicLlmClientProvider(
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider
    )
  }

  override val desktopYamlRunner: DesktopYamlRunner = DesktopYamlRunner(
    trailblazeDeviceManager = desktopAppConfig.deviceManager,
    trailblazeHostAppTargetProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    dynamicLlmClientProvider = { createDynamicClient(it) }
  )

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
      deviceManager = desktopAppConfig.deviceManager,
      additionalInstrumentationArgs = { emptyMap() },
      globalSettingsContent = { },
      currentTrailblazeLlmModelProvider = { desktopAppConfig.getCurrentLlmModel() },

      )
  }
}
