package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.LLMClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AndroidAssetsUtil
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.toTrailblazeToolRepo
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.bundle.AndroidAssetBundleJsSource
import xyz.block.trailblaze.scripting.bundle.BundleJsSource
import xyz.block.trailblaze.scripting.bundle.LaunchedBundleRuntime
import xyz.block.trailblaze.scripting.bundle.McpBundleRuntimeLauncher
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.util.Console

/**
 * On-Device Android Trailblaze Rule Implementation.
 *
 * Provides stateless logger with explicit session management:
 * - Access logger via `logger` property
 * - Access current session via `session` property
 * - Use `logger.log(session, log)` for all logging operations
 *
 * Supports multiple driver types via optional [agentOverride] and [screenStateProviderOverride]:
 * - When not provided, uses the default UiAutomator-based agent and screen state
 * - For accessibility mode, callers inject accessibility-specific implementations
 */
open class AndroidTrailblazeRule(
  val trailblazeLlmModel: TrailblazeLlmModel = AndroidLlmClientResolver.resolveModel(),
  val llmClient: LLMClient = AndroidLlmClientResolver.createClient(trailblazeLlmModel),
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  private val trailblazeDeviceId: TrailblazeDeviceId = DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID,
  val trailblazeLoggingRule: TrailblazeAndroidLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers() },
  ),
  customToolClasses: CustomTrailblazeTools? = null,
  agentOverride: MaestroTrailblazeAgent? = null,
  screenStateProviderOverride: (() -> ScreenState)? = null,
  /**
   * MCP bundle declarations for tools authored in TypeScript and compiled to a JS bundle
   * (PR A5 on-device path). Each entry should have `script:` set to a relative path that
   * resolves to a `.js` asset shipped in the APK — the launcher reads via
   * `android.content.res.AssetManager`. `command:` entries are silently skipped since they
   * aren't bundleable.
   *
   * Default empty so tests that don't exercise scripted MCP tools don't have to provide a
   * list. When non-empty, [runSuspend] launches the bundles at session start, registers
   * advertised tools into [trailblazeToolRepo], and tears them down after the trail ends.
   * Host-only tools (`_meta["trailblaze/requiresHost"]: true`) are dropped at
   * registration — on-device has no host agent, so advertising them would create a
   * silent-fail path the PR A5 scope devlog explicitly rules out.
   */
  private val mcpServers: List<McpServerConfig> = emptyList(),
  /**
   * Resolver that maps each [McpServerConfig] to a [BundleJsSource] the launcher can read.
   * Default: treats the `script:` path as an Android asset path, reading via the test
   * instrumentation's AssetManager. Override for tests that want to hand in an inline JS
   * fixture or read from a non-default asset root.
   */
  private val bundleSourceResolver: (McpServerConfig) -> BundleJsSource = { entry ->
    AndroidAssetBundleJsSource(
      assetPath = requireNotNull(entry.script) {
        "mcpServers entry is missing `script:` — `command:` entries are host-only and " +
          "cannot bundle on-device."
      },
    )
  },
) : SimpleTestRuleChain(trailblazeLoggingRule),
  TrailblazeRule {

  private val trailblazeAgent: MaestroTrailblazeAgent = agentOverride ?: AndroidMaestroTrailblazeAgent(
    trailblazeLogger = trailblazeLoggingRule.logger,
    trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
    sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
    nodeSelectorMode = config.nodeSelectorMode,
  )

  private val trailblazeToolRepo =
    customToolClasses?.toTrailblazeToolRepo() ?: TrailblazeToolRepo.withDynamicToolSets()

  private val screenStateProvider: () -> ScreenState = screenStateProviderOverride ?: {
    AndroidOnDeviceUiAutomatorScreenState(
      includeScreenshot = true,
      deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers,
    )
  }

  init {
    trailblazeLoggingRule.failureScreenStateProvider = screenStateProvider
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

  private val trailblazeYaml = createTrailblazeYaml(
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
      trailblazeLogger = trailblazeLoggingRule.logger,
      sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
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

    // PR A5: launch the target-declared MCP bundles at session start, so advertised tools
    // are registered into [trailblazeToolRepo] before the LLM selects a tool. Tear down in
    // the `finally` so subprocess teardown still runs even if a trail step throws — the
    // same invariant the host subprocess launcher uses in [TrailblazeHostYamlRunner]. Host
    // wraps in `withContext(NonCancellable)` there; here the trail's calling coroutine is
    // already scoped to the runner so a trail-level cancellation shouldn't strand the
    // bundle session, but `runCatching` on `shutdownAll` protects against that edge.
    val launchedBundleRuntime: LaunchedBundleRuntime? = if (mcpServers.isNotEmpty()) {
      McpBundleRuntimeLauncher.launchAll(
        mcpServers = mcpServers,
        deviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
        sessionId = (trailblazeLoggingRule.session
          ?: error("Session not available for MCP bundle launch")).sessionId,
        toolRepo = trailblazeToolRepo,
        bundleSourceResolver = bundleSourceResolver,
        // Callback channel is wired unconditionally by the launcher — there's no daemon
        // HTTP server on-device, so `_meta.trailblaze.runtime = "ondevice"` tells the TS
        // SDK to dispatch `client.callTool(…)` through the in-process QuickJS binding
        // instead of fetch. See [McpBundleRuntimeLauncher.launchAll]'s kdoc.
      )
    } else {
      null
    }

    try {
      trailItems.forEach { item ->
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPrompt(item.promptSteps, useRecordedSteps)
          is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem -> handleConfig(item.config)
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }
    } finally {
      launchedBundleRuntime?.let { runtime ->
        // Wrap in `withContext(NonCancellable)` so a cancelled trail (timeout, abort,
        // user cancel) still runs the bundle teardown through to completion rather than
        // cancelling at the first suspension point inside `McpBundleSession.shutdown()`.
        // Without this, a cancelled run would leak the QuickJS native allocation plus
        // the dynamic-tool registrations into the next session's tool repo. Mirrors the
        // host-side pattern in `TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny`'s
        // caller. Flagged during code review.
        withContext(NonCancellable) {
          runCatching { runtime.shutdownAll() }
        }
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
    return TrailblazeToolResult.Success()
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(DirectionStep(objective))
    return if (runnerResult is AgentTaskStatus.Success) {
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
    Console.log("Running from asset: $computedAssetPath")
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
      instanceId = TrailblazeDriverType.DEFAULT_ANDROID.name,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
  }
}
