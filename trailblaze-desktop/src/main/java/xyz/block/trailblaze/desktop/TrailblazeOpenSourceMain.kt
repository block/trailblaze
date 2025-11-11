@file:JvmName("Trailblaze")
@file:OptIn(ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import maestro.device.Device
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.ios.IosHostUtils
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.llm.providers.AnthropicTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.GoogleTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil.getAvailableTrailblazeLlmProviders
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import java.io.File

val logsDir = File("../logs")

val logsRepo = LogsRepo(logsDir)

/** All supported LLM model lists for open source host mode. */
private val ALL_MODEL_LISTS = setOf(
  AnthropicTrailblazeLlmModelList,
  GoogleTrailblazeLlmModelList,
  OllamaTrailblazeLlmModelList,
  OpenAITrailblazeLlmModelList,
)

/** Filtered list of model providers that have available API tokens. */
private val AVAILABLE_MODEL_LISTS = ALL_MODEL_LISTS.filter {
  getAvailableTrailblazeLlmProviders(ALL_MODEL_LISTS).contains(it.provider)
}.toSet()

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  val targetTestApp: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
  val trailblazeSavedSettingsRepo = TrailblazeSettingsRepo(
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(
      availableFeatures = TrailblazeServerState.SavedTrailblazeAppConfig.AvailableFeatures(
        hostMode = false,
      ),
    ),
  )
  val server = TrailblazeMcpServer(
    logsRepo = logsRepo,
    targetTestAppProvider = { targetTestApp },
    isOnDeviceMode = {
      !trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.availableFeatures.hostMode
    },
  )

  val deviceManager = TrailblazeDeviceManager(
    supportedDrivers = TrailblazeDriverType.entries.toSet(),
    appTargets = setOf(targetTestApp),
    appIconProvider = AppIconProvider.DefaultAppIconProvider,
    settingsRepo = trailblazeSavedSettingsRepo,
    getInstalledAppIds = { connectedMaestroDevice: Device.Connected ->
      when (connectedMaestroDevice.platform) {
        Platform.ANDROID -> AndroidHostAdbUtils.listInstalledPackages(
          deviceId = connectedMaestroDevice.instanceId,
        )

        Platform.IOS -> IosHostUtils.getInstalledAppIds(
          deviceId = connectedMaestroDevice.instanceId,
        )

        Platform.WEB -> emptyList()
      }.toSet()
    },
  ).apply {
    // Start polling device status to detect running sessions on Android devices
    startPollingDeviceStatus()
  }

  MainTrailblazeApp(
    trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
    logsRepo = logsRepo,
    trailblazeMcpServerProvider = { server },
    customEnvVarNames = emptyList(),
  ).runTrailblazeApp(
    customTabs = listOf(),
    availableModelLists = AVAILABLE_MODEL_LISTS,
    deviceManager = deviceManager,
    yamlRunner = { desktopRunYamlParams ->
      CoroutineScope(Dispatchers.IO).launch {
        DesktopYamlRunner(
          onRunHostYaml = { runOnHostParams: RunOnHostParams ->
            CoroutineScope(Dispatchers.IO).launch {
              TrailblazeHostYamlRunner.runHostYaml(
                runOnHostParams = runOnHostParams,
                deviceManager = deviceManager,
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
    },
  )
}
