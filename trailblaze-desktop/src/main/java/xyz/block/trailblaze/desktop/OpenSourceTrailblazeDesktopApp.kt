package xyz.block.trailblaze.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.host.yaml.RunOnHostParams
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

  override suspend fun runYaml(desktopRunYamlParams: DesktopAppRunYamlParams) {
    DesktopYamlRunner(
      trailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      onRunHostYaml = { runOnHostParams: RunOnHostParams ->
        CoroutineScope(Dispatchers.IO).launch {
          TrailblazeHostYamlRunner.runHostYaml(
            runOnHostParams = runOnHostParams,
            deviceManager = desktopAppConfig.deviceManager,
            dynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
              trailblazeLlmModel = runOnHostParams.runYamlRequest.trailblazeLlmModel,
              trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
            ),
          )
        }
      },
    ).runYaml(
      desktopRunYamlParams = desktopRunYamlParams,
    )
  }

  override fun startTrailblazeDesktopApp() {
    MainTrailblazeApp(
      trailblazeSavedSettingsRepo = desktopAppConfig.trailblazeSettingsRepo,
      logsRepo = desktopAppConfig.logsRepo,
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
      yamlRunner = { desktopRunYamlParams: DesktopAppRunYamlParams ->
        CoroutineScope(Dispatchers.IO).launch {
          runYaml(desktopRunYamlParams)
        }
      },
    )
  }
}
