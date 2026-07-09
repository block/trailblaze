package xyz.block.trailblaze.desktop

import java.io.File
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.trailrunner.TrailRunnerEndpoint
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console

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

  override fun startTrailblazeDesktopApp(headless: Boolean, daemonAlreadyRunning: Boolean) {
    installRunHandler()
    // Anchor every workspace-config consumer (tool catalog, LSP schema, scripted-tool source
    // lookups, target discovery) to the trails directory picked in Trail Runner settings — not the
    // CWD-based default `TrailblazeWorkspaceConfigBootstrap` wires at CLI startup. Installed here,
    // after that one-shot init, so this assignment is final; an explicit `TRAILBLAZE_CONFIG_DIR`
    // still wins inside [TrailblazeDesktopAppConfig.workspaceConfigDirOrNull].
    WorkspaceConfigDirHolder.resolver = { desktopAppConfig.workspaceConfigDirOrNull() }
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
      daemonAlreadyRunning = daemonAlreadyRunning,
      // Serve the Trail Runner web UI (/trailrunner/) on the daemon. The open-source build runs it
      // with the config's extension (DefaultTrailRunnerExtension unless a downstream build
      // overrides it): no integrations, no analytics, no LLM authoring assists — the UI degrades
      // gracefully wherever an extension member is absent.
      extraDaemonRoutes = {
        TrailRunnerEndpoint.register(
          routing = this,
          trailsRootProvider = {
            val appConfig = desktopAppConfig.trailblazeSettingsRepo.serverStateFlow.value.appConfig
            File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(appConfig))
          },
          logsRepo = desktopAppConfig.logsRepo,
          settingsRepo = desktopAppConfig.trailblazeSettingsRepo,
          deviceManager = deviceManager,
          llmModelListsProvider = { desktopAppConfig.getAllSupportedLlmModelLists() },
          extension = desktopAppConfig.trailRunnerExtension,
        )
      },
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
      // Match the args produced by [OpenSourceTrailblazeDesktopAppConfig.additionalInstrumentationArgs]
      // used on the trail-run path. Without selectedProviderId/defaultModel,
      // `trailblaze.llm.provider.type`, `base_url`, `chat_completions_path`, `headers`,
      // `auth_required`, and `default_model` never reach the on-device APK — and any
      // openai_compatible provider then fails on-device with "Unsupported provider"
      // because the on-device LLM client map is keyed by provider id.
      onDeviceInstrumentationArgsProvider = {
        // Safe cast: `OpenSourceTrailblazeDesktopApp`'s ctor pins this to OpenSourceTrailblazeDesktopAppConfig.
        val selected = (desktopAppConfig as OpenSourceTrailblazeDesktopAppConfig).selectedLlmInstrumentationArgs()
        JvmLLMProvidersUtil.getAdditionalInstrumentationArgs(
          selectedProviderId = selected.selectedProviderId,
          defaultModel = selected.defaultModel,
        )
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
      dynamicLlmClientProvider = { createDynamicClient(it) },
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
      saveAnnotatedScreenshotsProvider = {
        CliConfigHelper.readConfig()?.saveAnnotatedScreenshots ?: true
      },
    )
  }

}
