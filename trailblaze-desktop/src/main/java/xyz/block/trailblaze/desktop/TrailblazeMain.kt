@file:JvmName("Trailblaze")
@file:OptIn(ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil.getAvailableTrailblazeLlmProviders
import xyz.block.trailblaze.model.TargetTestApp
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.ui.DeviceManager
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.yaml.DesktopYamlRunner
import java.io.File

val logsDir = File("../logs")

val logsRepo = LogsRepo(logsDir)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  val trailblazeSavedSettingsRepo = TrailblazeSettingsRepo(
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(
      availableFeatures = TrailblazeServerState.SavedTrailblazeAppConfig.AvailableFeatures(
        hostMode = false,
      ),
    ),
    allToolClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsByKoogToolDescriptor,
  )
  val server = TrailblazeMcpServer(
    logsRepo,
    isOnDeviceMode = {
      !trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.availableFeatures.hostMode
    },
  )

  val modelLists = setOf(
    OpenAITrailblazeLlmModelList,
    OllamaTrailblazeLlmModelList,
  )

  val availableTrailblazeLlmProviders: Set<TrailblazeLlmProvider> = getAvailableTrailblazeLlmProviders(modelLists)

  val availableModelLists = modelLists.filter { availableTrailblazeLlmProviders.contains(it.provider) }.toSet()

  val deviceManager = DeviceManager(targetDeviceFilter = { it })

  val targetTestApp = TargetTestApp.DEFAULT_ANDROID_ON_DEVICE
  val yamlRunner = DesktopYamlRunner(targetTestApp, onRunHostYaml = { runOnHostParams ->
    CoroutineScope(Dispatchers.IO).launch {
      TrailblazeHostYamlRunner.runHostYaml(
        runOnHostParams = runOnHostParams,
        allAndroidTargetTestApps = listOf(targetTestApp),
        hostAppTarget = null,
      )
    }
  })

  MainTrailblazeApp(
    trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
    logsRepo = logsRepo,
    trailblazeMcpServerProvider = { server },
    targetTestApp = targetTestApp,
    customEnvVarNames = emptyList(),
  ).runTrailblazeApp(
    customTabs = listOf(),
    availableModelLists = availableModelLists,
    deviceManager = deviceManager,
    yamlRunner = yamlRunner,
  )
}
