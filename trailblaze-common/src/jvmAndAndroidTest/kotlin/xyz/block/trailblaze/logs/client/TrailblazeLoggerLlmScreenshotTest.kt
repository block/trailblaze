package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.datetime.Clock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.block.trailblaze.agent.AgentTier
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.yaml.DirectionStep

/**
 * An LLM request log keeps the raw screenshot. The model is sent the set-of-mark variant, but
 * everything that reads the log (reports, string crops, waypoint examples) wants the screen as
 * it looked, not covered in marks.
 */
class TrailblazeLoggerLlmScreenshotTest {

  // PNG signature + a distinguishing byte, so the two variants are real, different images.
  private val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)
  private val annotated = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 2)

  private fun screenState() = object : ScreenState {
    override val screenshotBytes: ByteArray = raw
    override val annotatedScreenshotBytes: ByteArray = annotated
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(nodeId = 1, className = "FrameLayout")
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private val emitted = mutableListOf<TrailblazeLog>()
  private val saved = mutableListOf<TrailblazeScreenStateLog>()
  private val logger = TrailblazeLogger(
    logEmitter = LogEmitter(emitted::add),
    screenStateLogger = ScreenStateLogger {
      saved += it
      it.fileName
    },
  )
  private val session = TrailblazeSession(sessionId = SessionId("llm_screenshot_test"), startTime = Clock.System.now())

  private fun request() {
    logger.logLlmRequest(
      session = session,
      koogLlmRequestMessages = emptyList(),
      stepStatus = PromptStepStatus(
        promptStep = DirectionStep(step = "do the thing"),
        screenStateProvider = { screenState() },
      ).also { it.prepareNextStep() },
      trailblazeLlmModel = TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
        modelId = "test-model",
        inputCostPerOneMillionTokens = 0.0,
        outputCostPerOneMillionTokens = 0.0,
        contextLength = 1_000,
        maxOutputTokens = 1_000,
        capabilityIds = emptyList(),
      ),
      response = Message.Assistant(
        part = MessagePart.Tool.Call(id = "call-1", tool = "tap", args = "{}"),
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
      ),
      startTime = Clock.System.now(),
      traceId = TraceId.Companion.generate(TraceId.Companion.TraceOrigin.LLM),
      toolDescriptors = emptyList(),
      requestContext = TrailblazeLog.LlmRequestContext(
        agentImplementation = AgentImplementation.KOOG_STRATEGY_GRAPH,
        llmCallStrategy = LlmCallStrategy.DIRECT,
        agentTier = AgentTier.OUTER,
      ),
    )
  }

  @Test
  fun `the screenshot saved for an LLM request is the raw one, not the annotated one`() {
    request()

    assertArrayEquals(raw, saved.single().screenState.screenshotBytes)
  }

  @Test
  fun `the request log says its screenshot is not annotated`() {
    // Readers treat a missing flag as annotated, so the log has to say so explicitly.
    request()

    val log = emitted.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().single()
    assertEquals(saved.single().fileName, log.screenshotFile)
    assertEquals(false, log.screenshotIsAnnotated)
  }
}
