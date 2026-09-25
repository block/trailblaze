package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Catalog ids and the two-generation read path.
 *
 * Readers must go through [TrailblazeToolCatalog.resolveToolOptions] rather than touching
 * `toolOptions`, because which field holds the descriptors depends on when the log was written.
 */
class TrailblazeToolCatalogTest {

  private fun descriptor(name: String, description: String = "does $name") = TrailblazeToolDescriptor(
    name = name,
    description = description,
    requiredParameters = listOf(
      TrailblazeToolParameterDescriptor(name = "ref", description = "element ref", type = "STRING"),
    ),
  )

  private val tap = descriptor("tap")
  private val swipe = descriptor("swipe")

  private fun requestLog(
    toolCatalogId: String? = null,
    toolNames: List<String> = emptyList(),
    toolOptions: List<TrailblazeToolDescriptor> = emptyList(),
  ) = TrailblazeLog.TrailblazeLlmRequestLog(
    agentTaskStatus = AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        taskId = TaskId.generate(),
        prompt = "prompt",
        callCount = 1,
        taskStartTime = Clock.System.now(),
        totalDurationMs = 1L,
      ),
    ),
    viewHierarchy = ViewHierarchyTreeNode(),
    instructions = "instructions",
    trailblazeLlmModel = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1L,
      maxOutputTokens = 1L,
      capabilityIds = emptyList(),
    ),
    llmMessages = emptyList(),
    llmResponse = emptyList<Message.Assistant>(),
    actions = emptyList(),
    toolOptions = toolOptions,
    toolCatalogId = toolCatalogId,
    toolNames = toolNames,
    screenshotFile = null,
    durationMs = 1L,
    session = SessionId("s"),
    timestamp = Clock.System.now(),
    traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
    deviceHeight = 1920,
    deviceWidth = 1080,
  )

  @Test
  fun `the catalog id is standard FNV-1a, so other languages can reproduce it`() {
    // Published test vectors, plus one non-ASCII input: that is where a hash over UTF-16 code
    // units instead of UTF-8 bytes stops agreeing with every other implementation.
    assertEquals("cbf29ce484222325", TrailblazeToolCatalog.fnv1a64Hex(ByteArray(0)))
    assertEquals("af63dc4c8601ec8c", TrailblazeToolCatalog.fnv1a64Hex("a".encodeToByteArray()))
    assertEquals("85944171f73967e8", TrailblazeToolCatalog.fnv1a64Hex("foobar".encodeToByteArray()))
    assertEquals("0ac21707b7181e01", TrailblazeToolCatalog.fnv1a64Hex("\u00e9".encodeToByteArray()))
  }

  @Test
  fun `a build from before the catalog split can still read a new request log`() {
    // Device and desktop skew in practice — a newer device library posting to an older host
    // daemon is the common way — and `toolOptions` was a REQUIRED field before the split. Session
    // JSON omits defaults, so leaving the field out would make every new request log undecodable
    // to an older build: the daemon 400s the upload, and an older report drops the log without
    // saying so. Costing 16 bytes to keep it present degrades that to "shows no tools".
    val encoded = TrailblazeCompactJsonInstance.encodeToString(
      TrailblazeLog.serializer(),
      requestLog(toolCatalogId = "abc", toolNames = listOf("tap")),
    )

    val asOldBuildReadsIt = TrailblazeCompactJsonInstance
      .decodeFromString(PreSplitRequestLog.serializer(), encoded)

    assertEquals(emptyList(), asOldBuildReadsIt.toolOptions)
  }

  @Test
  fun `the same tools produce the same id and different tools do not`() {
    assertEquals(
      TrailblazeToolCatalog.idFor(listOf(tap, swipe)),
      TrailblazeToolCatalog.idFor(listOf(tap, swipe)),
    )
    assertNotEquals(
      TrailblazeToolCatalog.idFor(listOf(tap, swipe)),
      TrailblazeToolCatalog.idFor(listOf(tap)),
    )
  }

  @Test
  fun `a changed description changes the id even when the names match`() {
    // What the LLM was shown is the descriptor text, not just the tool names — an edited
    // description is a real toolset change and must not silently reuse the old catalog.
    assertNotEquals(
      TrailblazeToolCatalog.idFor(listOf(descriptor("tap", "taps an element"))),
      TrailblazeToolCatalog.idFor(listOf(descriptor("tap", "taps an element, twice"))),
    )
  }

  @Test
  fun `the id is order-sensitive, which is why the writer sorts`() {
    // Documents the contract rather than wishing it away: callers sort by name so the id tracks
    // the tool SET and not the order the registry happened to enumerate.
    assertNotEquals(
      TrailblazeToolCatalog.idFor(listOf(tap, swipe)),
      TrailblazeToolCatalog.idFor(listOf(swipe, tap)),
    )
    assertEquals(
      TrailblazeToolCatalog.idFor(listOf(tap, swipe).sortedBy { it.name }),
      TrailblazeToolCatalog.idFor(listOf(swipe, tap).sortedBy { it.name }),
    )
  }

  @Test
  fun `a current log resolves its descriptors through the catalog`() {
    val catalogId = TrailblazeToolCatalog.idFor(listOf(swipe, tap))
    val log = requestLog(toolCatalogId = catalogId, toolNames = listOf("swipe", "tap"))

    val resolved = TrailblazeToolCatalog.resolveToolOptions(
      log = log,
      catalogs = mapOf(catalogId to listOf(swipe, tap)),
    )

    assertEquals(listOf(swipe, tap), resolved)
  }

  @Test
  fun `a legacy log resolves its inline descriptors with no catalog present`() {
    // Sessions recorded before the split carry descriptors on the request and no catalog id.
    val legacy = requestLog(toolOptions = listOf(tap, swipe))

    val resolved = TrailblazeToolCatalog.resolveToolOptions(legacy, catalogs = emptyMap())

    assertEquals(listOf(tap, swipe), resolved)
  }

  @Test
  fun `an unresolvable catalog id yields empty rather than falling back to the legacy field`() {
    // A caller holding only part of a session (one log file, a stream that joined late) gets
    // "unknown". Falling through to `toolOptions` would report "no tools were offered", which is
    // a different and wrong claim.
    val log = requestLog(toolCatalogId = "deadbeefdeadbeef", toolNames = listOf("tap"))

    assertTrue(TrailblazeToolCatalog.resolveToolOptions(log, catalogs = emptyMap()).isEmpty())
  }

  @Test
  fun `names and counts read correctly for both log generations`() {
    val current = requestLog(toolCatalogId = "abc123", toolNames = listOf("swipe", "tap"))
    val legacy = requestLog(toolOptions = listOf(tap, swipe))

    assertEquals(listOf("swipe", "tap"), TrailblazeToolCatalog.toolNames(current))
    assertEquals(listOf("tap", "swipe"), TrailblazeToolCatalog.toolNames(legacy))
    assertEquals(2, TrailblazeToolCatalog.toolCount(current))
    assertEquals(2, TrailblazeToolCatalog.toolCount(legacy))
  }

  @Test
  fun `catalogsIn keys every catalog in a session by its id`() {
    val first = TrailblazeToolCatalog.idFor(listOf(tap))
    val second = TrailblazeToolCatalog.idFor(listOf(swipe, tap))
    val logs = listOf<TrailblazeLog>(
      TrailblazeLog.TrailblazeToolCatalogLog(
        toolCatalogId = first,
        toolOptions = listOf(tap),
        session = SessionId("s"),
        timestamp = Clock.System.now(),
      ),
      requestLog(toolCatalogId = first, toolNames = listOf("tap")),
      TrailblazeLog.TrailblazeToolCatalogLog(
        toolCatalogId = second,
        toolOptions = listOf(swipe, tap),
        session = SessionId("s"),
        timestamp = Clock.System.now(),
      ),
    )

    val catalogs = TrailblazeToolCatalog.catalogsIn(logs)

    assertEquals(setOf(first, second), catalogs.keys)
    assertEquals(listOf(tap), catalogs[first])
    assertEquals(listOf(swipe, tap), catalogs[second])
  }
}

/**
 * A request log as a build from before the catalog split declares it: `toolOptions` required.
 *
 * Only that one field, because the decoder ignores unknown keys — which is exactly why the
 * required/absent distinction is the only thing that can break an older reader.
 */
@Serializable
private data class PreSplitRequestLog(
  val toolOptions: List<TrailblazeToolDescriptor>,
)
