package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeToolSet
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.util.toPascalCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import kotlin.reflect.KClass

/**
 * Base test class for Playwright-native web testing.
 *
 * Parallel to [BaseHostTrailblazeTest] but uses [PlaywrightBrowserManager] and
 * [PlaywrightTrailblazeAgent] instead of Maestro-based components. No Maestro
 * driver, host runner, or connected device is needed.
 */
class BasePlaywrightNativeTest(
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
    trailblazeLlmModel = trailblazeLlmModel,
    trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
  ),
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  allSerializationToolClasses: Set<KClass<out TrailblazeTool>> = customToolClasses,
  appTarget: TrailblazeHostAppTarget? = null,
  val trailblazeDeviceId: TrailblazeDeviceId,
  val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig(),
  val analyticsUrlPatterns: List<String> = emptyList(),
) {

  init {
    TrailblazeJsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
      allToolClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName +
          (PlaywrightNativeToolSet.LlmToolSet.toolClasses + allSerializationToolClasses)
            .associateBy { it.toolName() },
    )
  }

  val browserManager: PlaywrightPageManager = PlaywrightBrowserManager(
    headless = config.browserHeadless,
    idlingConfig = idlingConfig,
    analyticsUrlPatterns = analyticsUrlPatterns,
  )

  val trailblazeDeviceInfo: TrailblazeDeviceInfo
    get() = TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      widthPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_WIDTH,
      heightPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_HEIGHT,
      classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
    )

  val loggingRule: TrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
  )

  private val playwrightAgent by lazy {
    PlaywrightTrailblazeAgent(
      browserManager = browserManager,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  private val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "Playwright Native Tool Set",
      PlaywrightNativeToolSet.LlmToolSet.toolClasses + customToolClasses,
    ),
  )

  private val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
      screenStateProvider = browserManager::getScreenState,
      agent = playwrightAgent,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = PLAYWRIGHT_NATIVE_SYSTEM_PROMPT,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  private val elementComparator by lazy {
    TrailblazeElementComparator(
      screenStateProvider = browserManager::getScreenState,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )
  }

  private val trailblazeYaml = createTrailblazeYaml(
    customTrailblazeToolClasses = PlaywrightNativeToolSet.LlmToolSet.toolClasses + allSerializationToolClasses,
  )

  private val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        val result = playwrightAgent.runTrailblazeTools(
          tools = trailblazeTools,
          traceId = null,
          screenState = browserManager.getScreenState(),
          elementComparator = elementComparator,
          screenStateProvider = browserManager::getScreenState,
        )
        when (val toolResult = result.result) {
          is TrailblazeToolResult.Success -> toolResult
          is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
        }
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  private suspend fun runTrail(
    trailItems: List<TrailYamlItem>,
    useRecordedSteps: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ) {
    // In recording playback, skip DOM stability in post-action settle — element readiness
    // waits handle the critical timing, and DOM stability always times out on real SPAs.
    playwrightAgent.skipPostActionDomStability = useRecordedSteps

    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, useRecordedSteps, onStepProgress)
        is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
  }

  suspend fun runTrailblazeYamlSuspend(
    yaml: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailFilePath: String?,
    useRecordedSteps: Boolean = true,
    sendSessionStartLog: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): SessionId = withContext(browserManager.playwrightDispatcher) {
    // Run the entire agent loop on the Playwright thread to maintain thread affinity.
    // After callLlm() suspends and resumes, the coroutine resumes here (not on a
    // random Dispatchers.IO thread), so all Playwright API calls stay on the correct thread.

    // Set working directory to the trail file's own directory so that relative paths
    // in tools (e.g., navigate) resolve from the trail file's location.
    playwrightAgent.workingDirectory = trailFilePath?.let { java.io.File(it).absoluteFile.parentFile }

    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(yaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    if (sendSessionStartLog) {
      val session = loggingRule.session
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = trailFilePath,
              testClassName = trailConfig?.title?.let { toPascalCaseIdentifier(it) }
                ?: trailFilePath?.let { toPascalCaseIdentifier(java.io.File(it).parentFile.name) }
                ?: "BasePlaywrightNativeTest",
              testMethodName = trailFilePath?.let { toSnakeCaseIdentifier(java.io.File(it).parentFile.name) }
                ?: "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }
    }
    runTrail(trailItems, useRecordedSteps, onStepProgress)
    loggingRule.session?.sessionId ?: SessionId("unknown")
  }

  fun close() {
    browserManager.close()
  }

  companion object {
    internal val PLAYWRIGHT_NATIVE_SYSTEM_PROMPT = """
**You are managing a {{device_description}} using Playwright.**

You will be provided with the current screen state, including:
- A list of interactive page elements with element IDs
- A screenshot of the browser viewport

## Page Elements

The page elements list shows meaningful elements on the page, each with a unique ID.
Format: `[eN] role "name"` — for example:
```
[e1] link "Home"
[e2] heading "Welcome"
[e3] textbox "Email"
[e4] button "Submit"
```

When calling tools, use the element ID (e.g., "e5") as the `ref` parameter to target
an element. You can also use ARIA descriptors (e.g., 'button "Submit"') if the element
is not in the list or you need more precision.

## Reasoning

Every tool accepts an optional `reasoning` parameter. ALWAYS include it to explain:
- Why you chose this specific action
- What you expect to happen as a result
This reasoning is logged for debugging and test reports.

When interpreting objectives, if an objective begins with the word "expect", "verify", "confirm", or
"assert" (case-insensitive), you should use the objective_status tool to report the result.

**NOTE:**
- Use playwright_snapshot to refresh your view of the page when needed.
- After navigation or clicks that change the page, use playwright_snapshot to see the updated state.
    """.trimIndent()
  }
}
