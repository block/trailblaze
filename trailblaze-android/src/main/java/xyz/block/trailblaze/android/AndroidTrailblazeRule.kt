package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.LLMClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AndroidAssetsUtil
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicToolSet
import xyz.block.trailblaze.utils.Ext.asMaestroCommands
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * On-Device Android Trailblaze Rule Implementation.
 *
 * Provides stateless logger with explicit session management:
 * - Access logger via `logger` property
 * - Access current session via `session` property
 * - Use `logger.log(session, log)` for all logging operations
 */
open class AndroidTrailblazeRule(
  val llmClient: LLMClient,
  val trailblazeLlmModel: TrailblazeLlmModel,
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  private val trailblazeDeviceId: TrailblazeDeviceId = DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID,
  val trailblazeLoggingRule: TrailblazeAndroidLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers() },
  ),
  customToolClasses: CustomTrailblazeTools? = null,
) : SimpleTestRuleChain(trailblazeLoggingRule),
  TrailblazeRule {

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent(
    trailblazeLogger = trailblazeLoggingRule.logger,
    trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
    sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
  )

  private val trailblazeToolRepo = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.getSetOfMarkToolSet(
      config.setOfMarkEnabled,
    ) + DynamicToolSet(
      toolClasses = customToolClasses?.initialToolRepoToolClasses ?: emptySet(),
    ),
  )

  private val screenStateProvider: () -> ScreenState = {
    AndroidOnDeviceUiAutomatorScreenState(
      includeScreenshot = true,
      filterViewHierarchy = true,
      setOfMarkEnabled = config.setOfMarkEnabled,
    )
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = llmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = trailblazeToolRepo,
  )

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
  }

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses?.allForSerializationTools() ?: setOf(),
  )

  private val trailblazeRunner: TestAgentRunner by lazy {
    TrailblazeRunner(
      trailblazeToolRepo = trailblazeToolRepo,
      trailblazeLlmModel = trailblazeLlmModel,
      llmClient = llmClient,
      screenStateProvider = screenStateProvider,
      agent = trailblazeAgent,
      trailblazeLogger = trailblazeLoggingRule.logger,
      sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = ::runTrailblazeTool,
    )
  }

  suspend fun runSuspend(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
    sendSessionStartLog: Boolean,
  ) {
    val trailItems = trailblazeYaml.decodeTrail(testYaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    if (sendSessionStartLog) {
      val currentSession = trailblazeLoggingRule.session
        ?: error("Session not available when sendSessionStartLog=true. Ensure this rule is used as a @Rule in a JUnit test.")

      trailblazeLoggingRule.logger.log(
        currentSession,
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = trailConfig,
            trailFilePath = trailFilePath,
            testClassName = trailblazeLoggingRule.description?.className ?: "AndroidTrailblazeRule",
            testMethodName = trailblazeLoggingRule.description?.methodName ?: "run",
            trailblazeDeviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
            rawYaml = testYaml,
            hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
            trailblazeDeviceId = trailblazeDeviceId,
          ),
          session = currentSession.sessionId,
          timestamp = Clock.System.now(),
        ),
      )
    }
    trailblazeAgent.clearMemory()
    trailItems.forEach { item ->
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> runMaestroCommands(item.maestro.maestroCommands.asMaestroCommands())
        is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPrompt(item.promptSteps, useRecordedSteps)
        is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> handleConfig(item.config)
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
  }

  override fun run(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
  ) = runBlocking {
    runSuspend(
      testYaml = testYaml,
      trailFilePath = trailFilePath,
      useRecordedSteps = useRecordedSteps,
      sendSessionStartLog = true,
    )
  }

  private fun runTrailblazeTool(trailblazeTools: List<TrailblazeTool>): TrailblazeToolResult {
    val runTrailblazeToolsResult = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTools,
      screenState = screenStateProvider(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    )
    return when (val toolResult = runTrailblazeToolsResult.result) {
      is TrailblazeToolResult.Success -> toolResult
      is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
    }
  }

  @Deprecated("Prefer the suspend version.")
  private fun runMaestroCommandsBlocking(maestroCommands: List<Command>): TrailblazeToolResult =
    runBlocking { runMaestroCommands(maestroCommands) }

  private suspend fun runMaestroCommands(maestroCommands: List<Command>): TrailblazeToolResult {
    return when (
      val maestroResult =
        trailblazeAgent.runMaestroCommands(
          maestroCommands = maestroCommands,
          traceId = null,
        )
    ) {
      is TrailblazeToolResult.Success -> maestroResult
      is TrailblazeToolResult.Error -> throw TrailblazeException(maestroResult.errorMessage)
    }
  }

  private fun handleConfig(config: TrailConfig): TrailblazeToolResult {
    config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
    return TrailblazeToolResult.Success
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(DirectionStep(objective))
    return if (runnerResult is AgentTaskStatus.Success) {
      // Success!
      true
    } else {
      throw TrailblazeException(runnerResult.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun tool(vararg trailblazeTool: TrailblazeTool): TrailblazeToolResult {
    val result = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTool.toList(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    ).result
    return if (result is TrailblazeToolResult.Success) {
      result
    } else {
      throw TrailblazeException(result.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override suspend fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult {
    val runCommandsResult = trailblazeAgent.runMaestroCommands(
      maestroCommand.toList(),
      null,
    )
    return if (runCommandsResult is TrailblazeToolResult.Success) {
      runCommandsResult
    } else {
      throw TrailblazeException(runCommandsResult.toString())
    }
  }

  fun runFromAsset(
    yamlAssetPath: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      AndroidAssetsUtil::assetExists,
    ),
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
    targetAppId: String? = null,
  ) {
    val computedAssetPath: String = TrailRecordings.findBestTrailResourcePath(
      path = yamlAssetPath,
      deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers,
      doesResourceExist = AndroidAssetsUtil::assetExists,
    ) ?: throw TrailblazeException("Asset not found: $yamlAssetPath")
    println("Running from asset: $computedAssetPath")
    // Make sure the app is stopped before the test so the LLM doesn't get confused and think it's already running.
    if (forceStopApp && targetAppId != null) {
      AdbCommandUtil.forceStopApp(targetAppId)
    }
    val yamlContent = AndroidAssetsUtil.readAssetAsString(computedAssetPath)
    run(
      testYaml = yamlContent,
      useRecordedSteps = useRecordedSteps,
      trailFilePath = yamlAssetPath,
    )
  }

  companion object {
    /**
     * Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)
     *
     * NOTE: It would be better to pass these values as instrumentation args if possible
     */
    @Deprecated("Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)")
    val DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID = TrailblazeDeviceId(
      instanceId = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
  }
}
