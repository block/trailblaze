package xyz.block.trailblaze.logs.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
 * The tool catalog split: descriptors are written once per session as a
 * [TrailblazeLog.TrailblazeToolCatalogLog] instead of being repeated in full on every
 * [TrailblazeLog.TrailblazeLlmRequestLog].
 */
class TrailblazeLoggerToolCatalogTest {

  private fun screenState() = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(nodeId = 1, className = "FrameLayout")
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private fun koogTool(name: String) = ToolDescriptor(
    name = name,
    description = "description for $name",
    requiredParameters = listOf(
      ToolParameterDescriptor(name = "ref", description = "element ref", type = ToolParameterType.String),
    ),
  )

  private val tapAndType = listOf(koogTool("tap"), koogTool("inputText"))
  private val tapTypeAndSwipe = tapAndType + koogTool("swipe")

  /** Drives one real LLM request through the logger and returns everything it emitted. */
  private class Harness(sessionId: String = "catalog_test") {
    val emitted = mutableListOf<TrailblazeLog>()
    val logger = TrailblazeLogger(
      logEmitter = LogEmitter(emitted::add),
      screenStateLogger = ScreenStateLogger { it.fileName },
    )
    val session = TrailblazeSession(sessionId = SessionId(sessionId), startTime = Clock.System.now())

    val catalogs get() = emitted.filterIsInstance<TrailblazeLog.TrailblazeToolCatalogLog>()
    val requests get() = emitted.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
  }

  private fun Harness.request(tools: List<ToolDescriptor>) {
    logger.logLlmRequest(
      session = session,
      koogLlmRequestMessages = emptyList(),
      stepStatus = PromptStepStatus(
        promptStep = DirectionStep(step = "do the thing"),
        screenStateProvider = { screenState() },
      ).also { it.prepareNextStep() }, // populates currentScreenState, as the real runner does
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
      toolDescriptors = tools,
      requestContext = TrailblazeLog.LlmRequestContext(
        agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
        llmCallStrategy = LlmCallStrategy.DIRECT,
        agentTier = AgentTier.OUTER,
      ),
    )
  }

  @Test
  fun `an unchanged toolset is written as ONE catalog no matter how many requests use it`() {
    // The whole point of the split: five requests used to carry five full copies of the
    // descriptors. A `.zip` cannot dedupe those either, because it compresses each file alone.
    val h = Harness()

    repeat(5) { h.request(tapAndType) }

    assertEquals("five requests must be logged", 5, h.requests.size)
    assertEquals("an unchanged toolset is written exactly once", 1, h.catalogs.size)
    assertTrue(
      "no request may still carry the descriptors inline",
      h.requests.all { it.toolOptions.isEmpty() },
    )
  }

  @Test
  fun `every request points at the catalog that holds its descriptors`() {
    val h = Harness()

    repeat(3) { h.request(tapAndType) }

    val catalog = h.catalogs.single()
    assertEquals(
      "every request must name the catalog",
      listOf(catalog.toolCatalogId, catalog.toolCatalogId, catalog.toolCatalogId),
      h.requests.map { it.toolCatalogId },
    )
    assertEquals(
      "the catalog must hold the descriptors that were offered",
      listOf("inputText", "tap"),
      catalog.toolOptions.map { it.name },
    )
  }

  @Test
  fun `a toolset change mid-session writes a second catalog and re-points later requests`() {
    // The case that rules out a single snapshot at session start: a target swap or a different
    // tool bundle changes what the LLM is offered, and the log has to show it.
    val h = Harness()

    h.request(tapAndType)
    h.request(tapTypeAndSwipe)

    assertEquals("a changed toolset must write its own catalog", 2, h.catalogs.size)
    assertNotEquals(
      "the two catalogs must be distinguishable",
      h.catalogs[0].toolCatalogId,
      h.catalogs[1].toolCatalogId,
    )
    assertEquals(h.catalogs[0].toolCatalogId, h.requests[0].toolCatalogId)
    assertEquals(h.catalogs[1].toolCatalogId, h.requests[1].toolCatalogId)
    assertEquals(
      "the second catalog must hold the NEW toolset",
      listOf("inputText", "swipe", "tap"),
      h.catalogs[1].toolOptions.map { it.name },
    )
  }

  @Test
  fun `returning to an earlier toolset re-uses its catalog instead of writing a third`() {
    // Why readers must diff `toolCatalogId` across requests rather than counting catalog logs:
    // A→B→A is three distinct toolset states but only two catalogs on disk.
    val h = Harness()

    h.request(tapAndType)
    h.request(tapTypeAndSwipe)
    h.request(tapAndType)

    assertEquals("a repeat toolset must NOT write a duplicate catalog", 2, h.catalogs.size)
    assertEquals(
      "the third request must resolve back to the first catalog",
      h.requests[0].toolCatalogId,
      h.requests[2].toolCatalogId,
    )
    assertNotEquals(h.requests[1].toolCatalogId, h.requests[2].toolCatalogId)
  }

  @Test
  fun `a catalog is emitted before the request that needs it`() {
    // Log files are numbered in emission order, so a reader streaming the session in order must
    // never meet a `toolCatalogId` it cannot yet resolve.
    val h = Harness()

    h.request(tapAndType)

    val catalogIndex = h.emitted.indexOfFirst { it is TrailblazeLog.TrailblazeToolCatalogLog }
    val requestIndex = h.emitted.indexOfFirst { it is TrailblazeLog.TrailblazeLlmRequestLog }
    assertTrue("catalog must precede its first consumer", catalogIndex in 0 until requestIndex)
  }

  @Test
  fun `two sessions each get their own catalog even for an identical toolset`() {
    // One logger serves many sessions. Deduping on the catalog id alone would let the second
    // session reference a catalog that only exists in the first session's log directory.
    val first = Harness(sessionId = "session_one")
    val second = Harness(sessionId = "session_two")

    first.request(tapAndType)
    second.request(tapAndType)

    assertEquals(1, first.catalogs.size)
    assertEquals("the second session must write its own copy", 1, second.catalogs.size)
    assertEquals(
      "the same toolset must produce the same id in both",
      first.catalogs.single().toolCatalogId,
      second.catalogs.single().toolCatalogId,
    )
  }

  @Test
  fun `requests carry the tool names so a reader can list them without the catalog`() {
    val h = Harness()

    h.request(tapAndType)

    assertEquals(listOf("inputText", "tap"), h.requests.single().toolNames)
  }
}
