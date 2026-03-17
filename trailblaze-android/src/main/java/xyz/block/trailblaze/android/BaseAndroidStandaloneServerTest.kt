package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.agent.AgentResult
import xyz.block.trailblaze.agent.DirectMcpAgent
import xyz.block.trailblaze.android.agent.KoogLlmSamplingSource
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Base standalone runner test for On-Device Android Trailblaze Tests
 */
abstract class BaseAndroidStandaloneServerTest {

  /**
   * We are using a typical [TrailblazeAndroidLoggingRule] but are using it on-demand in this case
   *
   * We don't know the deviceId when the test starts, so we need to store it somewhere for the device id provider
   *
   * This must be set before a test is run
   */
  protected lateinit var trailblazeDeviceId: TrailblazeDeviceId

  @get:Rule
  val trailblazeLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { getDeviceClassifiers() }
  )


  abstract fun handleRunRequest(runYamlRequest: RunYamlRequest)

  protected var runTestCoroutineScope: CoroutineScope? = null

  protected fun cancelAnyActiveRuns() {
    runTestCoroutineScope?.cancel()
    runTestCoroutineScope = null
  }

  fun startInTestCoroutineScope(work: suspend () -> Unit) {
    cancelAnyActiveRuns()
    val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    runTestCoroutineScope = coroutineScope
    // Use runBlocking within the scope so the calling suspend function blocks until
    // work completes, while still being cancellable via runTestCoroutineScope.cancel().
    runBlocking(coroutineScope.coroutineContext) {
      work()
    }
  }

  abstract fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient

  abstract fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier>

  /**
   * Returns custom Trailblaze tools for the specified target app.
   *
   * Subclasses can override this to provide app-specific tools that will be
   * registered with the DirectMcpAgent's tool repository.
   *
   * @param targetAppName The name of the target app (e.g., "myApp", "anotherApp")
   * @return Custom tools for the app, or null if no custom tools are needed
   */
  open fun getCustomToolsForTargetApp(targetAppName: String?): CustomTrailblazeTools? = null

  val adbReversePort =
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT

  /**
   * Creates the [MaestroTrailblazeAgent] to use in [handleRunDirectAgentRequest].
   *
   * Subclasses can override this to provide a different agent implementation (e.g.,
   * [xyz.block.trailblaze.android.accessibility.AccessibilityTrailblazeAgent] for accessibility
   * mode).
   */
  protected open fun createAgentForDirectRun(runYamlRequest: RunYamlRequest): MaestroTrailblazeAgent =
    AndroidMaestroTrailblazeAgent(
      trailblazeLogger = trailblazeLoggingRule.logger,
      trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = {
        trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
      },
    )

  /**
   * Creates the screen state provider to use in [handleRunDirectAgentRequest].
   *
   * Subclasses can override this to provide accessibility-based screen state (e.g., return
   * `{ accessibilityAgent.getScreenState() }` for accessibility mode).
   */
  protected open fun createScreenStateProviderForDirectRun(
    runYamlRequest: RunYamlRequest,
    agent: MaestroTrailblazeAgent,
  ): () -> ScreenState = {
    AndroidOnDeviceUiAutomatorScreenState(
      includeScreenshot = runYamlRequest.directAgentConfig.includeScreenshots,
      filterViewHierarchy = true,
      setOfMarkEnabled = runYamlRequest.config.setOfMarkEnabled,
    )
  }

  /**
   * Handles a [RunYamlRequest] using [DirectMcpAgent] (the Koog-based agent).
   *
   * Executes any [TrailYamlItem.ToolTrailItem] setup steps first (e.g., launchApp), then runs
   * each [TrailYamlItem.PromptsTrailItem] objective using the DirectMcpAgent.
   */
  fun handleRunDirectAgentRequest(runYamlRequest: RunYamlRequest) {
    this.trailblazeDeviceId = runYamlRequest.trailblazeDeviceId

    val llmClient = getDynamicLlmClient(runYamlRequest.trailblazeLlmModel).createLlmClient()

    val trailblazeAgent = createAgentForDirectRun(runYamlRequest)
    val screenStateProvider = createScreenStateProviderForDirectRun(runYamlRequest, trailblazeAgent)

    // Resolve the target app name: prefer the explicit request field, fall back to the trail
    // config's `app` field. This ensures custom tools (e.g., myApp_launchSignedIn) are
    // registered even when the host doesn't set targetAppName in the RunYamlRequest.
    val trailblazeYaml = createTrailblazeYaml()
    val trailConfig = try { trailblazeYaml.extractTrailConfig(runYamlRequest.yaml) } catch (_: Exception) { null }
    val resolvedTargetApp = runYamlRequest.targetAppName ?: trailConfig?.app
    val customToolsForTargetApp = getCustomToolsForTargetApp(resolvedTargetApp?.lowercase())
    val trailblazeToolRepo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.getLlmToolSet(
        runYamlRequest.config.setOfMarkEnabled,
      ),
    )
    // Add app-specific custom tools if available.
    // Both initialToolRepoToolClasses AND registeredAppSpecificLlmTools must be added —
    // some app targets (e.g., Square) put tools only in registeredAppSpecificLlmTools
    // with an empty initialToolRepoToolClasses when using dynamic tool set catalogs.
    customToolsForTargetApp?.let { customTools ->
      trailblazeToolRepo.registeredTrailblazeToolClasses.addAll(customTools.initialToolRepoToolClasses)
      trailblazeToolRepo.registeredTrailblazeToolClasses.addAll(customTools.registeredAppSpecificLlmTools)
    }

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = screenStateProvider,
      llmClient = llmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      toolRepo = trailblazeToolRepo,
    )

    val samplingSource = KoogLlmSamplingSource(
      llmClient = llmClient,
      llmModel = runYamlRequest.trailblazeLlmModel,
    )

    // On-device agent always uses DIRECT strategy - MCP sampling is only possible
    // when the agent runs on the host machine with an MCP client connection
    val directMcpAgent = DirectMcpAgent(
      samplingSource = samplingSource,
      trailblazeAgent = trailblazeAgent,
      screenStateProvider = screenStateProvider,
      elementComparator = elementComparator,
      maxIterations = runYamlRequest.directAgentConfig.maxIterationsPerObjective,
      includeScreenshots = runYamlRequest.directAgentConfig.includeScreenshots,
      llmCallStrategy = LlmCallStrategy.DIRECT,
      trailblazeToolRepo = trailblazeToolRepo,
      trailblazeLogger = trailblazeLoggingRule.logger,
      sessionProvider = {
        trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
      },
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
    )

    val trailItems = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolsForTargetApp?.allForSerializationTools() ?: emptySet(),
    ).decodeTrail(runYamlRequest.yaml)
    Console.log("[DirectAgent] Target app: $resolvedTargetApp, custom tools: ${customToolsForTargetApp != null}")
    val toolItems = trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>()
    val objectives = trailItems.filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps.map { step -> step.prompt } }

    startInTestCoroutineScope {
      // Execute setup tool steps (e.g., launchApp) before running prompt objectives
      for (toolItem in toolItems) {
        val toolResult = trailblazeAgent.runTrailblazeTools(
          tools = toolItem.tools.map { it.trailblazeTool },
          screenState = screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = screenStateProvider,
        )
        if (toolResult.result is TrailblazeToolResult.Error) {
          throw TrailblazeException(
            "Tool setup step failed: ${(toolResult.result as TrailblazeToolResult.Error).errorMessage}"
          )
        }
      }

      for (objective in objectives) {
        val result = directMcpAgent.run(objective)
        when (result) {
          is AgentResult.Success -> {
            Console.log("[DirectMcpAgent] Objective succeeded: ${result.summary}")
          }
          is AgentResult.Failed -> {
            throw TrailblazeException("[DirectMcpAgent] Objective failed: ${result.reason}")
          }
          is AgentResult.Error -> {
            throw TrailblazeException("[DirectMcpAgent] Objective error: ${result.message}")
          }
        }
      }
    }
  }

  /**
   * Creates a callback for running YAML-based tests via TrailblazeRunner.
   *
   * This callback handles session lifecycle management around [handleRunRequest].
   * Use this when constructing an [OnDeviceRpcServer] in subclasses.
   */
  fun createRunTrailblazeYamlCallback(): suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession =
    { runYamlRequest: RunYamlRequest, session: TrailblazeSession ->
      // Set the session on the logging rule so it's available to all components
      // that use sessionProvider (AndroidTrailblazeRule and its subcomponents)
      trailblazeLoggingRule.setSession(session)
      try {
        handleRunRequest(runYamlRequest)
      } finally {
        // Clear the session after execution to prevent stale sessions
        trailblazeLoggingRule.setSession(null)
      }
      session // Return the session unchanged
    }

  /**
   * Creates a callback for running tests via two-tier agent (OuterLoopAgent + InnerLoopScreenAnalyzer).
   *
   * This callback handles session lifecycle management around [handleRunDirectAgentRequest].
   * Use this when constructing an [OnDeviceRpcServer] in subclasses.
   */
  fun createRunTwoTierAgentCallback(): suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession =
    { runYamlRequest: RunYamlRequest, session: TrailblazeSession ->
      // Set the session for two-tier agent execution
      trailblazeLoggingRule.setSession(session)
      try {
        handleRunDirectAgentRequest(runYamlRequest)
      } finally {
        trailblazeLoggingRule.setSession(null)
      }
      session
    }
}
