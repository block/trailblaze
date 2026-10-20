package xyz.block.trailblaze.quickjs.tools

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.history.HistorySectionKind
import xyz.block.trailblaze.history.ToolCallHistory
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.trailhead.TrailheadEventStream
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition

/**
 * `ctx.history` end to end, as its first consumer uses it: a real SDK-authored trailhead
 * (`fixture_historyTrailhead` in `src/jvmTest/fixtures/trailhead-teardown/trailhead.ts`) whose
 * `teardown` reads the trail section's tool calls, run through the real QuickJS engine, the real
 * agent dispatch and the shared runner. What `teardown` saw comes back as its `state`, read from
 * the trailhead event stream.
 */
class QuickJsToolHistoryTest {

  private val sessionDir: File = Files.createTempDirectory("tool-history-e2e").toFile()
  private val hosts = mutableListOf<QuickJsToolHost>()
  private val memory = AgentMemory()
  private val session = TrailblazeSession(sessionId = SessionId("s-${UUID.randomUUID()}"), startTime = Clock.System.now())

  // The production binding, so `ctx.host` reaches the framework host functions behind `ctx.history`.
  private val binding = SessionScopedHostBinding(
    toolRepo = TrailblazeToolRepo(TrailblazeToolSet.DynamicTrailblazeToolSet("empty", emptySet())),
    sessionId = session.sessionId,
  )
  private val device = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "emulator-${UUID.randomUUID()}",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    widthPixels = 1080,
    heightPixels = 1920,
  )

  @AfterTest
  fun cleanUp() {
    runBlocking { hosts.forEach { runCatching { it.shutdown() } } }
    sessionDir.deleteRecursively()
  }

  @Test
  fun `teardown reads exactly the trail's calls, not the setup's`() = runBlocking {
    val host = connect()

    runTrail(host, "item-1", "item-2")

    assertEquals(
      listOf(
        TrailCall("fixture_addItem", "item-1", success = true),
        TrailCall("fixture_addItem", "item-2", success = true),
      ),
      callsTeardownSaw(),
    )
    // The same calls as `formatToolCalls` prints them, durations aside.
    assertEquals(
      listOf("✓ fixture_addItem(id: \"item-1\")", "✓ fixture_addItem(id: \"item-2\")"),
      teardownState()["text"]!!.jsonPrimitive.content.lines().map { it.substringBefore("  ") },
    )
    // Teardown ran in its own section, opening the instant the trail's closed.
    val (_, trailhead, trail, teardown) = ToolCallHistory.sections(session.sessionId)
    assertEquals(
      listOf(HistorySectionKind.RUN, HistorySectionKind.TRAILHEAD, HistorySectionKind.TRAIL, HistorySectionKind.TEARDOWN),
      ToolCallHistory.sections(session.sessionId).map { it.kind },
    )
    assertEquals(trailhead.endedAt, trail.startedAt)
    assertEquals(trail.endedAt, teardown.startedAt)
  }

  @Test
  fun `teardown sees the trail's failed call after the trail fails`() = runBlocking {
    val host = connect()

    memory.remember("fixture_addItemThrowsCyclic", "true")
    assertFails { runTrail(host, "item-1") }

    assertEquals(listOf(TrailCall("fixture_addItem", "item-1", success = false)), callsTeardownSaw())
  }

  /** The `fixture_historyTrailhead` trailhead, then one `fixture_addItem` step per id. */
  private fun runTrail(host: QuickJsToolHost, vararg itemIds: String) {
    val agent = HistoryAgent()
    val util = TrailblazeRunnerUtil(
      runTrailblazeTool = { tools: List<TrailblazeTool> ->
        agent.runTrailblazeTools(tools, elementComparator = NoOpElementComparator).result
      },
      trailblazeRunner = NoAiRunner,
      sessionProvider = { session },
    )
    val trailhead = tool(host, "fixture_historyTrailhead", buildJsonObject { put("persona", "merchant") })
    util.runTrailBlocking {
      util.runPrompt(
        listOf(TrailheadDefinition(step = "Start signed in", tools = listOf(trailhead)).toPromptStep()),
        useRecordedSteps = true,
        selfHeal = false,
      )
      for (id in itemIds) {
        val add = tool(host, "fixture_addItem", buildJsonObject { put("id", id) })
        util.runPrompt(
          listOf(DirectionStep(step = "add $id", recording = ToolRecording(listOf(add)))),
          useRecordedSteps = true,
          selfHeal = false,
        )
      }
    }
  }

  /** A trail step as teardown reported it; `nested` counts the calls it made. */
  private data class TrailCall(val name: String, val id: String?, val success: Boolean, val nested: Int = 0)

  /** What `teardown` returned as its `state`. */
  private fun teardownState(): JsonObject {
    val teardown = File(sessionDir, "events/trailhead.ndjson").readLines()
      .map { TrailblazeJsonInstance.parseToJsonElement(it).jsonObject["data"]!!.jsonObject }
      .single { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.TEARDOWN }
    return teardown["state"]?.jsonObject ?: error("teardown returned no state: $teardown")
  }

  /** The top-level calls `teardown` returned in its `state`. */
  private fun callsTeardownSaw(): List<TrailCall> {
    val calls = teardownState()["calls"] as? JsonArray ?: error("teardown returned no calls: ${teardownState()}")
    return calls.map { it.jsonObject }.map { call ->
      TrailCall(
        name = call["name"]!!.jsonPrimitive.content,
        id = call["args"]?.jsonObject?.get("id")?.jsonPrimitive?.content,
        success = call["success"]!!.jsonPrimitive.content.toBooleanStrict(),
        nested = call["nested"]!!.jsonPrimitive.content.toInt(),
      )
    }
  }

  private fun tool(host: QuickJsToolHost, name: String, args: JsonObject) =
    TrailblazeToolYamlWrapper(name = name, trailblazeTool = QuickJsTrailblazeTool(host, ToolName(name), args, binding))

  private suspend fun connect(): QuickJsToolHost {
    val path = System.getProperty("trailblaze.test.trailheadTeardownBundle")
    assumeTrue("run via Gradle so bundleTrailheadTeardownAuthorTool produces the fixture bundle", path != null)
    val bundle = File(path!!)
    assumeTrue("fixture bundle missing at ${bundle.absolutePath}", bundle.isFile)
    return QuickJsToolHost.connect(bundle.readText(), bundleFilename = "trailhead-teardown.bundle.js", hostBinding = binding)
      .also { hosts.add(it) }
  }

  /** The production dispatch loop with nothing behind it: every tool here is a scripted (host-local) one. */
  private inner class HistoryAgent : BaseTrailblazeAgent(memory) {
    override val trailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider = { device }
    override val sessionProvider = TrailblazeSessionProvider { session }

    override fun buildExecutionContext(
      traceId: TraceId,
      screenState: ScreenState?,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeToolExecutionContext {
      lateinit var context: TrailblazeToolExecutionContext
      context = TrailblazeToolExecutionContext(
        screenState = null,
        traceId = traceId,
        trailblazeDeviceInfo = device,
        sessionProvider = sessionProvider,
        trailblazeLogger = trailblazeLogger,
        memory = memory,
        sessionDirProvider = { sessionDir },
        nestedToolExecutor = nestedToolExecutorFor { context },
      )
      return context
    }

    override fun executeTool(
      tool: TrailblazeTool,
      context: TrailblazeToolExecutionContext,
      toolsExecuted: MutableList<TrailblazeTool>,
    ): TrailblazeToolResult = error("unexpected driver tool $tool")
  }

  private object NoAiRunner : TestAgentRunner {
    override val screenStateProvider: () -> ScreenState = { error("no screen") }
    override fun run(prompt: PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus = done(prompt)
    override suspend fun runSuspend(prompt: PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus = done(prompt)
    override fun recover(promptStep: PromptStep, recordingResult: PromptRecordingResult.Failure): AgentTaskStatus =
      error("recover not expected")
    override fun appendToSystemPrompt(context: String) = Unit

    private fun done(prompt: PromptStep) = AgentTaskStatus.Success.ObjectiveComplete(
      statusData = AgentTaskStatusData(
        taskId = TaskId.generate(),
        prompt = prompt.prompt,
        callCount = 0,
        taskStartTime = Clock.System.now(),
        totalDurationMs = 0L,
      ),
      llmExplanation = "done",
    )
  }
}
