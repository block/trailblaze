package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.KoogRunnableAgent
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import kotlin.test.Test

/**
 * Drives [KoogTestAgentRunner.recover] through the real strategy graph against a scripted model, to pin
 * what a self-heal is allowed to do. A heal on a `step:` whose recorded assertion failed used to get
 * the full tool surface: healing "the checkout sheet is open" tapped through the checkout flow and
 * submitted the order, and another heal reported COMPLETED from a screenshot with nothing
 * asserted.
 */
class KoogTestAgentRunnerRecoverTest {

  private val assertTool = AssertVisibleTrailblazeTool::class.toolName().toolName
  private val tapTool = TapTrailblazeTool::class.toolName().toolName

  private val assertStep = DirectionStep(step = "The checkout sheet is open with the preset quantity chips")

  @Test
  fun `a heal of a failed assertion cannot act and cannot pass without asserting`() {
    val model = ScriptedModel(firstStatus = "COMPLETED")

    val status = recover(
      assertStep,
      recorded = listOf(
        TapOnByElementSelector(reason = "checkout"),
        AssertVisibleBySelectorTrailblazeTool(reason = "sheet open"),
        AssertVisibleBySelectorTrailblazeTool(reason = "preset chips"),
      ),
      failedAt = 1,
      model = model,
    )

    assertThat(model.advertisedOnFirstRequest).contains(assertTool)
    assertThat(model.advertisedOnFirstRequest).doesNotContain(tapTool)
    assertThat(status).isInstanceOf(AgentTaskStatus.Failure.ObjectiveFailed::class)
  }

  /** iOS keeps the full surface on verify steps until its scoping is validated, but not the gate. */
  @Test
  fun `a heal of a failed assertion on an unscoped driver still cannot pass without asserting`() {
    val model = ScriptedModel(firstStatus = "COMPLETED")

    val status = recover(
      DirectionStep(step = "The Terms of Service page loaded in the device browser"),
      recorded = listOf(AssertVisibleBySelectorTrailblazeTool(reason = "ToS heading")),
      model = model,
      driverType = TrailblazeDriverType.IOS_HOST,
    )

    assertThat(status).isInstanceOf(AgentTaskStatus.Failure.ObjectiveFailed::class)
  }

  @Test
  fun `a heal of a failed action keeps the full surface`() {
    val model = ScriptedModel(firstStatus = "COMPLETED")

    val status = recover(
      DirectionStep(step = "Tap Checkout on the cart tile"),
      recorded = listOf(TapOnByElementSelector(reason = "checkout")),
      model = model,
    )

    assertThat(model.advertisedOnFirstRequest).contains(tapTool)
    assertThat(status).isInstanceOf(AgentTaskStatus.Success.ObjectiveComplete::class)
  }

  /** A verify-only heal could pass on the guard and never run the action recorded after it. */
  @Test
  fun `a heal of a failed assertion with an action still to run keeps the full surface`() {
    val model = ScriptedModel(firstStatus = "COMPLETED")

    val status = recover(
      DirectionStep(step = "Open the deep link and land on Transactions"),
      recorded = listOf(
        AssertVisibleBySelectorTrailblazeTool(reason = "not on Transactions yet"),
        TapOnByElementSelector(reason = "open the link"),
        AssertVisibleBySelectorTrailblazeTool(reason = "on Transactions"),
      ),
      model = model,
    )

    assertThat(model.advertisedOnFirstRequest).contains(tapTool)
    assertThat(status).isInstanceOf(AgentTaskStatus.Success.ObjectiveComplete::class)
  }

  /** The verify surface has no memory assertions, so a verify-only heal could never re-run this check. */
  @Test
  fun `a heal of a failed memory assertion keeps the full surface`() {
    val model = ScriptedModel(firstStatus = "COMPLETED")

    val status = recover(
      DirectionStep(step = "The cart total went up by the item price"),
      recorded = listOf(AssertEqualsTrailblazeTool(actual = "{{after}}", expected = "{{expected}}")),
      model = model,
    )

    assertThat(model.advertisedOnFirstRequest).contains(tapTool)
    assertThat(status).isInstanceOf(AgentTaskStatus.Success.ObjectiveComplete::class)
  }

  private fun recover(
    step: DirectionStep,
    recorded: List<TrailblazeTool>,
    failedAt: Int = 0,
    model: ScriptedModel,
    driverType: TrailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
  ): AgentTaskStatus {
    val llmModel = TrailblazeLlmModels.GPT_4O_MINI
    val screen = TextScreenState("[a12] Button \"Checkout\"")
    val runner = KoogTestAgentRunner(
      agent = SucceedingAgent(),
      toolRepo = TrailblazeToolRepo.withDynamicToolSets(
        catalog = catalog(),
        driverType = driverType,
      ),
      screenStateProvider = { screen },
      elementComparator = NO_COMPARATOR,
      llmClient = model.client(llmModel.toKoogLlmModel().provider),
      trailblazeLlmModel = llmModel,
      logger = TrailblazeLogger.createNoOp(),
      sessionProvider = { SESSION },
      maxLlmCalls = 5,
      systemPromptTemplate = "",
    )
    val recording = recorded.map { TrailblazeToolYamlWrapper(name = "recorded", trailblazeTool = it) }
    return runner.recover(
      step.copy(recording = ToolRecording(recording)),
      PromptRecordingResult.Failure(
        successfulTools = recording.take(failedAt),
        failedTool = recording[failedAt],
        failureResult = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "not found"),
      ),
    )
  }

  private fun catalog() = listOf(
    ToolSetCatalogEntry(
      id = "core",
      description = "always-on core",
      toolClasses = setOf(ObjectiveStatusTrailblazeTool::class),
      alwaysEnabled = true,
    ),
    ToolSetCatalogEntry(
      id = "verification",
      description = "assertion tools",
      toolClasses = setOf(AssertVisibleTrailblazeTool::class),
      compatibleDriverTypes = setOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    ),
    ToolSetCatalogEntry(
      id = "tapping",
      description = "a state-changing tool",
      toolClasses = setOf(TapTrailblazeTool::class),
      alwaysEnabled = true,
    ),
  )

  /** Answers the first request with [firstStatus] and every later one with FAILED. */
  private class ScriptedModel(private val firstStatus: String) {
    val advertisedOnFirstRequest = mutableListOf<String>()
    private var requests = 0

    fun client(provider: LLMProvider): LLMClient = object : LLMClient() {
      override fun llmProvider(): LLMProvider = provider
      override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        requests++
        if (requests == 1) advertisedOnFirstRequest += tools.map { it.name }
        val status = if (requests == 1) firstStatus else "FAILED"
        return Message.Assistant(
          parts = listOf(
            MessagePart.Tool.Call(
              id = "call-$requests",
              tool = KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME,
              args = """{"status":"$status","explanation":"from the screenshot"}""",
            ),
          ),
          metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
      }
      override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
        listOf(execute(prompt, model, tools))
      override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        throw NotImplementedError()
      override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
      override fun close() = Unit
    }
  }

  private class SucceedingAgent : KoogRunnableAgent {
    override val trailblazeLogger: TrailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId = "fake", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider { SESSION }
    override val memory = AgentMemory()
    override fun buildKoogToolExecutionContext(
      traceId: TraceId?,
      screenStateProvider: () -> ScreenState,
    ): TrailblazeToolExecutionContext = error("no dynamic tools in this test")

    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult =
      TrailblazeAgent.RunTrailblazeToolsResult(tools, tools, TrailblazeToolResult.Success())
  }

  private class TextScreenState(override val viewHierarchyTextRepresentation: String) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  private companion object {
    val SESSION = TrailblazeSession(sessionId = SessionId("recover-test"), startTime = Clock.System.now())

    val NO_COMPARATOR = object : ElementComparator {
      override fun getElementValue(prompt: String): String? = null
      override fun evaluateBoolean(statement: String) = BooleanAssertionTrailblazeTool(reason = statement, result = true)
      override fun evaluateString(query: String) = StringEvaluationTrailblazeTool(reason = query, result = "")
      override fun extractNumberFromString(input: String): Double? = null
    }
  }
}
