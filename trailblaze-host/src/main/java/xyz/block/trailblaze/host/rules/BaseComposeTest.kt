package xyz.block.trailblaze.host.rules

import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

/**
 * Base test class for Compose Desktop testing.
 *
 * Parallel to [BasePlaywrightNativeTest] but uses Compose [ComposeUiTest] and
 * [ComposeTrailblazeAgent] instead of Playwright. No browser, Maestro
 * driver, or connected device is needed — tests run against Compose UI directly.
 */
@OptIn(ExperimentalTestApi::class)
class BaseComposeTest(
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient =
    TrailblazeHostDynamicLlmClientProvider(
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
    ),
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  customYamlToolNames: Set<ToolName> = setOf(),
  val trailblazeDeviceId: TrailblazeDeviceId,
  val viewportWidth: Int = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_WIDTH,
  val viewportHeight: Int = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_HEIGHT,
) {

  val trailblazeDeviceInfo: TrailblazeDeviceInfo
    get() =
      TrailblazeDeviceInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        trailblazeDriverType = TrailblazeDriverType.COMPOSE,
        widthPixels = viewportWidth,
        heightPixels = viewportHeight,
        classifiers = listOf(TrailblazeDeviceClassifier("desktop")),
      )

  val loggingRule: TrailblazeLoggingRule =
    HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
    )

  private val resolvedComposeToolSet =
    TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = ComposeToolSetIds.ALL,
    )

  private val allToolClasses = resolvedComposeToolSet.toolClasses + customToolClasses
  private val allYamlToolNames = resolvedComposeToolSet.yamlToolNames + customYamlToolNames

  private val toolRepo =
    TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "Compose Desktop Tool Set",
        toolClasses = allToolClasses,
        yamlToolNames = allYamlToolNames,
      ),
    )

  private val trailblazeYaml = TrailblazeYaml.Default

  /**
   * Runs a trail YAML against a Compose UI test instance.
   *
   * Call this from within a `runComposeUiTest { }` block, passing the ComposeUiTest.
   */
  suspend fun runTestWithCompose(
    target: ComposeTestTarget,
    yaml: String,
    trailFilePath: String? = null,
    useRecordedSteps: Boolean = true,
    sendSessionStartLog: Boolean = true,
  ): SessionId {
    val agent =
      ComposeTrailblazeAgent(
        target = target,
        trailblazeLogger = loggingRule.logger,
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )

    val screenStateProvider = agent.screenStateProvider

    val trailblazeRunner =
      TrailblazeRunner(
        screenStateProvider = screenStateProvider,
        agent = agent,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = trailblazeLlmModel,
        trailblazeToolRepo = toolRepo,
        systemPromptTemplate = COMPOSE_SYSTEM_PROMPT,
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
      )

    val elementComparator =
      TrailblazeElementComparator(
        screenStateProvider = screenStateProvider,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = trailblazeLlmModel,
        toolRepo = toolRepo,
      )

    val trailblazeRunnerUtil =
      TrailblazeRunnerUtil(
        trailblazeRunner = trailblazeRunner,
        runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
          val result =
            agent.runTrailblazeTools(
              trailblazeTools,
              null,
              screenState = screenStateProvider(),
              elementComparator = elementComparator,
              screenStateProvider = screenStateProvider,
            )
          when (val toolResult = result.result) {
            is TrailblazeToolResult.Success -> toolResult
            is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
          }
        },
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
      )

    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(yaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    if (sendSessionStartLog) {
      val session = loggingRule.session
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus =
              SessionStatus.Started(
                trailConfig = trailConfig,
                trailFilePath = trailFilePath,
                testClassName = "BaseComposeTest",
                testMethodName = "run",
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

    for (item in trailItems) {
      val itemResult =
        when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, useRecordedSteps)
          is TrailYamlItem.ToolTrailItem ->
            trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem ->
            item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
        }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }

    return loggingRule.session?.sessionId ?: SessionId("unknown")
  }

  companion object {
    internal val COMPOSE_SYSTEM_PROMPT =
      """
**You are testing a Compose Desktop application.**

You will be provided with the current screen state, including the Compose semantics tree
which describes the UI hierarchy, and a screenshot of the application window.

The semantics tree is your primary tool for identifying elements. Elements can be identified
by their testTag (resourceId) or text content. Use these identifiers in your tool calls.

When interpreting objectives, if an objective begins with the word "expect", "verify", "confirm", or
"assert" (case-insensitive), you should use the objective_status tool to report the result.

**NOTE:**
- Use takeSnapshot to refresh your view of the UI when needed.
- After clicks or actions that change the UI, use takeSnapshot to see the updated state.
      """
        .trimIndent()
  }
}
