package xyz.block.trailblaze.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.devices.WebBrowserManager
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmTokenProvider
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import kotlinx.coroutines.flow.StateFlow
import xyz.block.trailblaze.logs.server.McpServerDebugState
import xyz.block.trailblaze.ui.TrailblazeBuiltInTabs
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.composables.DeviceClassifierIconProvider
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import java.io.File

/**
 * A common interface containing what is needed for desktop app configuration.
 */
abstract class TrailblazeDesktopAppConfig(
  /** The LLM model that should be used as default if none is selected. */
  private val defaultLlmModel: TrailblazeLlmModel,
  /** The default model list. */
  private val defaultProviderModelList: TrailblazeLlmModelList,
) {

  /**
   * Environment variables that should be surfaced in UI/settings for this configuration.
   */
  abstract val customEnvVarNames: List<String>

  /**
   * Gets the status of LLM tokens for a specific provider.
   * 
   * This method checks token availability without triggering any authentication flows.
   * Override in subclasses to provide provider-specific token checking (e.g., custom OAuth flows).
   * 
   * @param provider The LLM provider to check token status for.
   * @return The token status for the given provider.
   */
  open fun getLlmTokenStatus(provider: TrailblazeLlmProvider): LlmTokenStatus {
    // Default implementation checks environment variables for standard open source providers
    val envVarName = getEnvironmentVariableForProvider(provider)
    val envVarValue = LlmProviderEnvVarUtil.getEnvironmentVariableValueForProvider(provider)

    return when {
      envVarName == null -> LlmTokenStatus.Available(provider) // Provider doesn't need a token (e.g., Ollama)
      envVarValue?.isNotBlank() == true -> LlmTokenStatus.Available(provider)
      else -> LlmTokenStatus.NotAvailable(provider)
    }
  }

  /**
   * Gets the environment variable name for a provider's API key.
   * Returns null if the provider doesn't require an API key.
   * 
   * Override in subclasses to add support for additional providers.
   */
  open fun getEnvironmentVariableForProvider(provider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
  }

  /**
   * Gets all supported LLM model lists for this configuration.
   * Unlike [getCurrentlyAvailableLlmModelLists], this returns ALL supported providers
   * regardless of whether they have tokens configured.
   * 
   * Used by the `auth` command to show status of all providers.
   */
  abstract fun getAllSupportedLlmModelLists(): Set<TrailblazeLlmModelList>

  /**
   * Gets the status of all supported LLM providers.
   * 
   * @return A map of provider to token status for ALL supported providers (not just available ones).
   */
  fun getAllLlmTokenStatuses(): Map<TrailblazeLlmProvider, LlmTokenStatus> {
    return getAllSupportedLlmModelLists()
      .map { it.provider }
      .distinct()
      .associateWith { getLlmTokenStatus(it) }
  }

  /**
   * LLM token provider used by the recording tab to authenticate LLM calls.
   * Override in subclasses to provide custom auth (e.g., corporate SSO/OAuth).
   * Defaults to the open source provider which reads from environment variables.
   */
  open val llmTokenProvider: TrailblazeDynamicLlmTokenProvider
    get() = TrailblazeHostDynamicLlmTokenProvider

  /**
   * Additional global settings content to render in the Settings tab.
   */
  open val globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit = {}

  /**
   * Additional content to render on the Home tab.
   * Override in subclasses to inject custom sections (e.g., authentication cards).
   */
  open val homeAdditionalContent: @Composable ColumnScope.() -> Unit = {}

  /**
   * Additional instrumentation args to pass to YAML runs for this configuration.
   */
  open suspend fun additionalInstrumentationArgs(): Map<String, String> = emptyMap()

  abstract fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList>

  /** That's where logs are stored and managed. */
  abstract val logsRepo: LogsRepo

  abstract val trailblazeSettingsRepo: TrailblazeSettingsRepo

  abstract val defaultAppDataDir: File
  abstract val recordedTrailsRepo: RecordedTrailsRepo

  abstract val appIconProvider: AppIconProvider

  /**
   * Provider for device classifier icons. Internal builds can override
   * to add custom icons for customized use cases.
   */
  open val deviceClassifierIconProvider: DeviceClassifierIconProvider =
    DefaultDeviceClassifierIconProvider

  abstract val defaultAppTarget: TrailblazeHostAppTarget

  abstract val availableAppTargets: Set<TrailblazeHostAppTarget>

  abstract fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String>

  /**
   * Gets version information for an installed app on the specified device.
   * Override in subclasses if custom version retrieval is needed.
   */
  open fun getAppVersionInfo(trailblazeDeviceId: TrailblazeDeviceId, appId: String): AppVersionInfo? {
    return MobileDeviceUtils.getAppVersionInfo(trailblazeDeviceId, appId)
  }

  fun getCurrentLlmModel(): TrailblazeLlmModel {
    val serverState = trailblazeSettingsRepo.serverStateFlow.value
    val savedProviderId = serverState.appConfig.llmProvider
    val savedModelId: String = serverState.appConfig.llmModel
    val currentProviderModelList =
      getCurrentlyAvailableLlmModelLists().firstOrNull { it.provider.id == savedProviderId }
        ?: defaultProviderModelList

    val selectedTrailblazeLlmModel: TrailblazeLlmModel =
      currentProviderModelList.entries.firstOrNull { it.modelId == savedModelId }
        ?: defaultLlmModel
    return selectedTrailblazeLlmModel
  }

  /**
   * Resolves a [TrailblazeLlmModel] from explicit provider/model ID strings.
   * Used by the CLI `--llm-provider` / `--llm-model` flags.
   *
   * Searches all available model lists for a matching entry. If [providerId] is given,
   * only models from that provider are considered. If [modelId] is given, it must match
   * exactly. Returns null if no match is found.
   */
  fun resolveLlmModel(providerId: String?, modelId: String?): TrailblazeLlmModel? {
    val allModelLists = getAllSupportedLlmModelLists()
    val providerLists = if (providerId != null) {
      allModelLists.filter { it.provider.id.equals(providerId, ignoreCase = true) }
    } else {
      allModelLists
    }
    if (modelId != null) {
      for (list in providerLists) {
        val match = list.entries.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
        if (match != null) return match
      }
      return null
    }
    // Provider given but no model — return first model from that provider
    return providerLists.firstOrNull()?.entries?.firstOrNull()
  }

  /**
   * Returns the list of tabs for this desktop app configuration.
   * Override in subclasses to customize which tabs are shown.
   *
   * @param deviceManager runtime device manager
   * @param yamlRunner runner for YAML executions
   * @param additionalInstrumentationArgs provider for instrumentation args (defaults to config implementation)
   */
  open fun getTabs(
    deviceManager: TrailblazeDeviceManager,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgsProvider: suspend () -> Map<String, String> = { additionalInstrumentationArgs() },
    mcpServerDebugStateFlow: StateFlow<McpServerDebugState>? = null,
    recommendTrailblazeAsAgent: Boolean = false,
  ): List<TrailblazeAppTab> {
    return getStandardTabs(
      deviceManager = deviceManager,
      yamlRunner = yamlRunner,
      additionalInstrumentationArgsProvider = additionalInstrumentationArgsProvider,
      globalSettingsContent = globalSettingsContent,
      customEnvVarNames = customEnvVarNames,
      webBrowserManager = deviceManager.webBrowserManager,
      mcpServerDebugStateFlow = mcpServerDebugStateFlow,
      recommendTrailblazeAsAgent = recommendTrailblazeAsAgent,
      onTestLlmConnection = { model -> testLlmConnection(model) },
    )
  }

  /**
   * Tests the LLM connection by sending a simple prompt and reporting the result along with
   * full HTTP diagnostics (request URL, headers, response status, headers, body) to help
   * debug provider connectivity issues. Uses [llmTokenProvider] for auth, so subclasses that
   * override it (e.g. for OAuth) get correct credentials automatically.
   */
  protected suspend fun testLlmConnection(model: TrailblazeLlmModel): Result<String> {
    val report = StringBuilder()
    report.appendLine("Provider: ${model.trailblazeLlmProvider.display}")
    report.appendLine("Model: ${model.modelId}")

    val diagnosticClient = TrailblazeHttpClientFactory.createDiagnosticHttpClient(timeoutInSeconds = 30)
    val client =
      TrailblazeHostDynamicLlmClientProvider(
        trailblazeLlmModel = model,
        trailblazeDynamicLlmTokenProvider = llmTokenProvider,
        baseClient = diagnosticClient.httpClient,
      )

    return try {
      val startTime = System.currentTimeMillis()
      val responses =
        client.createLlmClient().execute(
          prompt =
            ai.koog.prompt.dsl.Prompt(
              messages =
                listOf(
                  ai.koog.prompt.message.Message.User(
                    content = "Respond with OK",
                    metaInfo =
                      ai.koog.prompt.message.RequestMetaInfo.create(kotlin.time.Clock.System),
                  ),
                ),
              id = "llm-connection-test",
            ),
          model = model.toKoogLlmModel(),
          tools = emptyList(),
        )
      val elapsedMs = System.currentTimeMillis() - startTime
      report.appendLine("Response time: ${elapsedMs}ms")
      report.appendLine("Response: ${responses.firstOrNull()?.content ?: "(empty)"}")
      report.appendLine()
      report.appendLine("--- Request ---")
      report.append(diagnosticClient.interceptor.requestLog)
      report.appendLine()
      report.appendLine("--- Response ---")
      report.append(diagnosticClient.interceptor.responseLog)
      Result.success(report.toString())
    } catch (e: Exception) {
      report.appendLine("Error: ${e.message}")
      var cause = e.cause
      while (cause != null) {
        report.appendLine("Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
        cause = cause.cause
      }
      if (diagnosticClient.interceptor.requestLog.isNotEmpty()) {
        report.appendLine()
        report.appendLine("--- Request ---")
        report.append(diagnosticClient.interceptor.requestLog)
      }
      if (diagnosticClient.interceptor.responseLog.isNotEmpty()) {
        report.appendLine()
        report.appendLine("--- Response ---")
        report.append(diagnosticClient.interceptor.responseLog)
      }
      Result.failure(RuntimeException(report.toString(), e))
    } finally {
      diagnosticClient.httpClient.close()
    }
  }

  /**
   * Creates the standard set of tabs (Sessions, Trails, Devices, YAML, Settings).
   * Subclasses can call this and add/remove tabs as needed.
   *
   * @param isProviderLocked Whether the LLM provider dropdown starts locked.
   *   When true, the provider dropdown is disabled and a lock icon is shown to unlock it.
   *   Defaults to false (unlocked) for open source builds.
   */
  protected fun getStandardTabs(
    deviceManager: TrailblazeDeviceManager,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgsProvider: suspend () -> Map<String, String>,
    globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit,
    customEnvVarNames: List<String>,
    openGoose: (() -> Unit)? = null,
    isProviderLocked: Boolean = false,
    webBrowserManager: WebBrowserManager? = null,
    mcpServerDebugStateFlow: StateFlow<McpServerDebugState>? = null,
    recommendTrailblazeAsAgent: Boolean = false,
    onTestLlmConnection: (suspend (TrailblazeLlmModel) -> Result<String>)? = null,
  ): List<TrailblazeAppTab> {
    return buildList {
      add(
        TrailblazeBuiltInTabs.homeTab(
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          deviceManager = deviceManager,
          additionalHomeContent = homeAdditionalContent,
        )
      )
      add(
        TrailblazeBuiltInTabs.sessionsTab(
          logsRepo = logsRepo,
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          deviceManager = deviceManager,
          recordedTrailsRepo = recordedTrailsRepo,
        )
      )
      if (mcpServerDebugStateFlow != null) {
        add(
          TrailblazeBuiltInTabs.mcpTab(
            mcpServerDebugStateFlow = mcpServerDebugStateFlow,
            trailblazeSettingsRepo = trailblazeSettingsRepo,
            recommendTrailblazeAsAgent = recommendTrailblazeAsAgent,
          )
        )
      }
      add(
        TrailblazeBuiltInTabs.trailsTab(
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          deviceManager = deviceManager,
          currentTrailblazeLlmModelProvider = { getCurrentLlmModel() },
          yamlRunner = yamlRunner,
          additionalInstrumentationArgs = additionalInstrumentationArgsProvider,
        )
      )
      add(
        TrailblazeBuiltInTabs.devicesTab(
          deviceManager = deviceManager,
          trailblazeSettingsRepo = trailblazeSettingsRepo,
        )
      )
      add(
        TrailblazeBuiltInTabs.recordTab(
          deviceManager = deviceManager,
          currentTrailblazeLlmModelProvider = { getCurrentLlmModel() },
          llmTokenProvider = llmTokenProvider,
        )
      )
      add(
        TrailblazeBuiltInTabs.yamlTab(
          deviceManager = deviceManager,
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          currentTrailblazeLlmModelProvider = { getCurrentLlmModel() },
          yamlRunner = yamlRunner,
          additionalInstrumentationArgs = additionalInstrumentationArgsProvider,
        )
      )
      add(
        TrailblazeBuiltInTabs.settingsTab(
          trailblazeSettingsRepo = trailblazeSettingsRepo,
          logsRepo = logsRepo,
          globalSettingsContent = globalSettingsContent,
          availableModelLists = getCurrentlyAvailableLlmModelLists(),
          customEnvVarNames = customEnvVarNames,
          openGoose = openGoose ?: { TrailblazeDesktopUtil.openGoose(port = trailblazeSettingsRepo.portManager.httpPort) },
          isProviderLocked = isProviderLocked,
          playwrightInstallState = webBrowserManager?.playwrightInstaller?.installState,
          onInstallPlaywright = webBrowserManager?.let { { it.playwrightInstaller.installBrowsers() } },
          onTestLlmConnection = onTestLlmConnection,
        )
      )
    }
  }
}
