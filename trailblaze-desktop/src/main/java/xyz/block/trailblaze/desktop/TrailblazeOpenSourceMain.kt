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

/**
 * Gets the application data directory.
 * Uses ~/.trailblaze for consistency across platforms, or the configured directory.
 */
private fun getAppDataDirectory(): File {
  // First, we need to load settings from the default location to see if a custom location is set
  val defaultAppDataDir = File(System.getProperty("user.home"), ".trailblaze")
  val defaultSettingsFile = File(defaultAppDataDir, "trailblaze-settings.json")

  // Try to read the configured app data directory from settings
  if (defaultSettingsFile.exists()) {
    try {
      val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
      val config = json.decodeFromString<TrailblazeServerState.SavedTrailblazeAppConfig>(
        defaultSettingsFile.readText(),
      )
      if (config.appDataDirectory != null) {
        return File(config.appDataDirectory).apply { mkdirs() }
      }
    } catch (e: Exception) {
      println("Could not load custom app data directory from settings: ${e.message}")
    }
  }

  return defaultAppDataDir.apply { mkdirs() }
}

private val trailblazeSettingsRepo = TrailblazeSettingsRepo(
  settingsFile = File(getAppDataDirectory(), "trailblaze-settings.json"),
  initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(),
)

// Get logs directory from settings, or use default
private fun getLogsDirectory(): File {
  val configuredPath = trailblazeSettingsRepo.serverStateFlow.value.appConfig.logsDirectory
  return if (configuredPath != null) {
    File(configuredPath).apply { mkdirs() }
  } else {
    File(getAppDataDirectory(), "logs").apply { mkdirs() }
  }
}

// Get trails directory from settings, or use default
private fun getTrailsDirectory(): File {
  val configuredPath = trailblazeSettingsRepo.serverStateFlow.value.appConfig.trailsDirectory
  return if (configuredPath != null) {
    File(configuredPath).apply { mkdirs() }
  } else {
    File(getAppDataDirectory(), "trails").apply { mkdirs() }
  }
}

val logsDir = getLogsDirectory()
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
  val server = TrailblazeMcpServer(
    logsRepo = logsRepo,
    targetTestAppProvider = { targetTestApp },
  )

  val deviceManager = TrailblazeDeviceManager(
    supportedDrivers = TrailblazeDriverType.entries.toSet(),
    appTargets = setOf(targetTestApp),
    appIconProvider = AppIconProvider.DefaultAppIconProvider,
    settingsRepo = trailblazeSettingsRepo,
    trailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
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

  val trailsDir = getTrailsDirectory()
  val recordedTrailsRepo = xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm(trailsDirectory = trailsDir)

  MainTrailblazeApp(
    trailblazeSavedSettingsRepo = trailblazeSettingsRepo,
    logsRepo = logsRepo,
    recordedTrailsRepo = recordedTrailsRepo,
    trailblazeMcpServerProvider = { server },
    customEnvVarNames = emptyList(),
  ).runTrailblazeApp(
    customTabs = listOf(),
    availableModelLists = AVAILABLE_MODEL_LISTS,
    deviceManager = deviceManager,
    yamlRunner = { desktopRunYamlParams ->
      CoroutineScope(Dispatchers.IO).launch {
        DesktopYamlRunner(
          trailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
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
