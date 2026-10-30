package xyz.block.trailblaze.quickjs.tools

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.AgentMemory
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
import xyz.block.trailblaze.exception.TrailheadException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.trailhead.TrailheadEventStream
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition

/**
 * Trailhead teardown end to end: a real SDK-authored trailhead
 * (`src/jvmTest/fixtures/trailhead-teardown/trailhead.ts`), bundled the production on-device way,
 * run through the real QuickJS engine and the shared runner across consecutive trails. The unit
 * suites fake one side each (`TeardownTest` the tool, `trailhead.test.ts` the host); this is the
 * one place both sides meet.
 */
class QuickJsTrailheadTeardownTest {

  private val sessionDir: File = Files.createTempDirectory("trailhead-teardown-e2e").toFile()
  private val hosts = mutableListOf<QuickJsToolHost>()

  // The hand-off is keyed by device, so a fresh device id isolates each test.
  private val device = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "emulator-${UUID.randomUUID()}",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    widthPixels = 1080,
    heightPixels = 1920,
  )
  private val memory = AgentMemory()
  private var session = newSession()
  private val results = mutableMapOf<String, MutableList<TrailblazeToolResult>>()

  @AfterTest
  fun cleanUp() {
    runBlocking { hosts.forEach { runCatching { it.shutdown() } } }
    sessionDir.deleteRecursively()
  }

  @Test
  fun `the next trail reuses the setup and finishes the cleanup teardown handed it`() = runBlocking {
    val util = runnerUtil(connect())

    val first = session.sessionId.value
    runTrail(util, addItem = "item-1")
    session = newSession()
    runTrail(util)

    assertEquals(listOf("full setup as merchant", "reused $first, removed item-1"), messages("fixture_signIn"))
    val (fullSetup, reused) = setupRecords()
    assertEquals(TrailheadEventStream.Setup.FULL_SETUP, fullSetup.string("setup"))
    assertEquals(TrailheadEventStream.Setup.REUSED, reused.string("setup"))
    assertEquals("still signed in", reused.string("reason"))
    assertEquals(first, reused.string("previousRunSessionId"))
  }

  @Test
  fun `a catch-up that throws fails the trailhead, is logged as a failed reuse, and hands on nothing`() = runBlocking {
    val util = runnerUtil(connect())

    val first = session.sessionId.value
    runTrail(util, addItem = "item-1")
    session = newSession()
    memory.remember("fixture_catchUpFails", "true")
    assertFailsWith<TrailheadException> { runTrail(util) }
    memory.remember("fixture_catchUpFails", "false")
    session = newSession()
    runTrail(util)

    val failed = setupRecords()[1]
    assertEquals(TrailheadEventStream.Setup.REUSE_FAILED, failed.string("setup"))
    assertEquals(first, failed.string("previousRunSessionId"))
    assertTrue(failed.string("error").contains("could not remove item-1"), failed.toString())
    // Teardown still ran for the failed trailhead, and said its setup is not reusable.
    assertEquals("full setup as merchant", messages("fixture_signIn").last())
    assertEquals("the last run was not reusable: trail setup-failed", previousRunReasons().last())
  }

  @Test
  fun `a handler that catches its failed catch-up still fails the trailhead and commits no memory`() = runBlocking {
    val util = runnerUtil(connect())

    runTrail(util, addItem = "item-1")
    session = newSession()
    memory.remember("fixture_catchUpFails", "true")
    memory.remember("fixture_swallowCatchUpFailure", "true")
    assertFailsWith<TrailheadException> { runTrail(util) }

    val failed = setupRecords()[1]
    assertEquals(TrailheadEventStream.Setup.REUSE_FAILED, failed.string("setup"))
    assertEquals("true", failed.string("failed"))
    assertNull(memory.variables["fixture_carriedOn"])
  }

  @Test
  fun `with reuse off every trail does a full setup`() = runBlocking {
    val util = runnerUtil(connect(), reuse = false)

    runTrail(util, addItem = "item-1")
    session = newSession()
    runTrail(util)

    assertEquals(listOf("full setup as merchant", "full setup as merchant"), messages("fixture_signIn"))
  }

  private suspend fun connect(): QuickJsToolHost {
    val path = System.getProperty("trailblaze.test.trailheadTeardownBundle")
    assumeTrue("run via Gradle so bundleTrailheadTeardownAuthorTool produces the fixture bundle", path != null)
    val bundle = File(path!!)
    assumeTrue("fixture bundle missing at ${bundle.absolutePath}", bundle.isFile)
    return QuickJsToolHost.connect(bundle.readText(), bundleFilename = "trailhead-teardown.bundle.js")
      .also { hosts.add(it) }
  }

  private fun runnerUtil(host: QuickJsToolHost, reuse: Boolean = true) = TrailblazeRunnerUtil(
    runTrailblazeTool = { tools: List<TrailblazeTool> ->
      runBlocking {
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (tool in tools) {
          last = when (tool) {
            is FixtureTool -> QuickJsTrailblazeTool(host, ToolName(tool.name), tool.args).execute(context())
              .also { results.getOrPut(tool.name) { mutableListOf() } += it }
            is ExecutableTrailblazeTool -> tool.execute(context())
            else -> error("unexpected tool $tool")
          }
          if (last is TrailblazeToolResult.Error) break
        }
        last
      }
    },
    trailblazeRunner = NoAiRunner,
    sessionProvider = { session },
    trailheadReuse = reuse,
  )

  /** One whole trail: the `fixture_signIn` trailhead, then optionally a step that adds an item. */
  private fun runTrail(util: TrailblazeRunnerUtil, addItem: String? = null) {
    val signIn = FixtureTool("fixture_signIn", buildJsonObject { put("persona", "merchant") })
    util.runTrailBlocking {
      util.runPrompt(
        listOf(TrailheadDefinition(step = "Start signed in", tools = listOf(signIn.wrapper())).toPromptStep()),
        useRecordedSteps = true,
        selfHeal = false,
      )
      if (addItem != null) {
        val add = FixtureTool("fixture_addItem", buildJsonObject { put("id", addItem) })
        util.runPrompt(
          listOf(DirectionStep(step = "add $addItem", recording = ToolRecording(listOf(add.wrapper())))),
          useRecordedSteps = true,
          selfHeal = false,
        )
      }
    }
  }

  /**
   * Stands in for a fixture tool in the trail; the dispatch turns it into a [QuickJsTrailblazeTool]
   * on the test's host, the way the runtime resolves a scripted tool by name.
   */
  private class FixtureTool(val name: String, val args: JsonObject) : TrailblazeTool {
    fun wrapper() = TrailblazeToolYamlWrapper(name = name, trailblazeTool = this)
  }

  private fun context() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = device,
    sessionProvider = TrailblazeSessionProvider { session },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = memory,
    sessionDirProvider = { sessionDir },
  )

  private fun newSession() =
    TrailblazeSession(sessionId = SessionId("s-${UUID.randomUUID()}"), startTime = Clock.System.now())

  private fun messages(tool: String): List<String?> =
    results[tool].orEmpty().map { (it as? TrailblazeToolResult.Success)?.message }

  private fun trailheadEvents(): List<JsonObject> =
    File(sessionDir, "events/trailhead.ndjson").readLines().map {
      TrailblazeJsonInstance.parseToJsonElement(it).jsonObject["data"]!!.jsonObject
    }

  private fun setupRecords(): List<JsonObject> =
    trailheadEvents().filter { it.string("type") == TrailheadEventStream.Type.SETUP }

  private fun previousRunReasons(): List<String> =
    trailheadEvents().filter { it.string("type") == TrailheadEventStream.Type.PREVIOUS_RUN }.mapNotNull {
      it["reason"]?.jsonPrimitive?.content
    }

  private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content ?: error("no $key in $this")

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
