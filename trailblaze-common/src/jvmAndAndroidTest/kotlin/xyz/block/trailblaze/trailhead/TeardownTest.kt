package xyz.block.trailblaze.trailhead

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailheadException
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.events.SessionEventsHostFunctions
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher
import xyz.block.trailblaze.toolcalls.DispatchCancellation
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Teardown through the shared runner: `teardown` runs once after every trail whatever its
 * outcome, and the next matching trailhead on the device receives what it returned.
 */
class TeardownTest {

  private val sessionDir: File = Files.createTempDirectory("trailhead-teardown").toFile()

  @BeforeTest
  fun reset() {
    TrailheadRuns.clear()
    TrailheadHandoffStore.clear()
  }

  @AfterTest
  fun cleanUp() {
    sessionDir.deleteRecursively()
    TrailheadRuns.clear()
    TrailheadHandoffStore.clear()
  }

  /** A trailhead tool as the scripted runtime drives it: begin, handler, complete. */
  private inner class FakeTrailhead(
    override val trailheadToolName: String = "signIn",
    override val trailheadCodeVersion: String = "v1",
    var defines: Boolean = true,
    var teardown: (JsonObject, JsonObject) -> JsonObject = { _, _ -> result(reusable = true, reason = "clean") },
    var handler: (PreviousRun?) -> JsonObject? = { null },
    /** The handler throws after reporting [handler]'s meta, the way a failed catch-up does. */
    var handlerFails: Boolean = false,
  ) : TeardownCapableTool {
    val seenPreviousRun = mutableListOf<PreviousRun?>()
    val teardownCalls = mutableListOf<Pair<JsonObject, JsonObject>>()

    override suspend fun definesTeardown(): Boolean = defines

    override suspend fun teardown(
      context: TrailblazeToolExecutionContext,
      args: JsonObject,
      trailRun: JsonObject,
    ): JsonObject {
      teardownCalls += args to trailRun
      return teardown(args, trailRun)
    }

    suspend fun run(context: TrailblazeToolExecutionContext, args: JsonObject): TrailblazeToolResult {
      val invocation = TrailheadRuns.beginInvocation(context, this, args)
      seenPreviousRun += invocation?.previousRun
      val meta = handler(invocation?.previousRun)
      TrailheadRuns.completeInvocation(context, trailheadToolName, invocation, meta, failed = handlerFails)
      return if (handlerFails) TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "catch-up failed") else TrailblazeToolResult.Success()
    }
  }

  /** Set to make [context] a multi-device one. */
  private var bindings: SessionDeviceBindings? = null

  private var session = TrailblazeSession(sessionId = SessionId("s-1"), startTime = Clock.System.now())
  private val deviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
  private val buyerId = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)

  /** The device [context] runs on. */
  private var activeDeviceId = deviceId

  private fun context() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = activeDeviceId,
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider { session },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    sessionDirProvider = { sessionDir },
    deviceBindings = bindings,
  )

  /** Tools the fake dispatch knows by the text of their InputTextTrailblazeTool stand-in. */
  private val trailheads = mutableMapOf<String, FakeTrailhead>()
  private val stepTools = mutableMapOf<String, suspend (TrailblazeToolExecutionContext) -> TrailblazeToolResult>()
  private val argsByTool = mutableMapOf<String, JsonObject>()

  /** Makes the runner's dispatch of the Teardown step throw before the step runs. */
  private var teardownDispatchFails = false

  private fun runnerUtil(
    reuse: Boolean = true,
    logger: TrailblazeLogger? = null,
    screen: () -> ScreenState = NoAiRunner.screenStateProvider,
  ) = TrailblazeRunnerUtil(
    runTrailblazeTool = { tools: List<TrailblazeTool> ->
      runBlocking {
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (tool in tools) {
          val ctx = context()
          last = when (tool) {
            // InputTextTrailblazeTool stands in for the trail's own tools; check it before the
            // generic executable branch, which it also matches.
            is InputTextTrailblazeTool -> trailheads[tool.text]?.run(ctx, argsByTool[tool.text] ?: JsonObject(emptyMap()))
              ?: stepTools.getValue(tool.text)(ctx)
            is TeardownTrailblazeTool -> if (teardownDispatchFails) error("screen capture failed") else tool.execute(ctx)
            is ExecutableTrailblazeTool -> tool.execute(ctx)
            else -> error("unexpected tool $tool")
          }
          if (last is TrailblazeToolResult.Error) break
        }
        last
      }
    },
    trailblazeRunner = object : TestAgentRunner by NoAiRunner {
      override val screenStateProvider: () -> ScreenState = screen
    },
    trailblazeLogger = logger,
    sessionProvider = { session },
    trailheadReuse = reuse,
  )

  private fun wrapper(name: String) = TrailblazeToolYamlWrapper(name = name, trailblazeTool = InputTextTrailblazeTool(text = name))

  private fun trailheadStep(vararg names: String) =
    TrailheadDefinition(step = "Start signed in", tools = names.map(::wrapper)).toPromptStep()

  private fun step(name: String) = DirectionStep(step = "do $name", recording = ToolRecording(listOf(wrapper(name))))

  /** One whole trail: its trailhead, then [steps]. */
  private fun runTrail(util: TrailblazeRunnerUtil, vararg steps: String, trailhead: List<String> = listOf("signIn")) {
    util.runTrailBlocking {
      util.runPrompt(listOf(trailheadStep(*trailhead.toTypedArray())), useRecordedSteps = true, selfHeal = false)
      util.runPrompt(steps.map(::step), useRecordedSteps = true, selfHeal = false)
    }
  }

  private fun trailheadItems(vararg names: String): List<TrailYamlItem> =
    listOf(TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(tools = names.map(::wrapper))))

  /** One whole trail with no trailhead, only [steps]. */
  private fun runTrailWithoutTrailhead(util: TrailblazeRunnerUtil, vararg steps: String) {
    util.runTrailBlocking { util.runPrompt(steps.map(::step), useRecordedSteps = true, selfHeal = false) }
  }

  private fun nextSession(id: String) {
    session = TrailblazeSession(sessionId = SessionId(id), startTime = Clock.System.now())
  }

  @Test
  fun `a single passing trail still gets its teardown, with the trailhead's args and outcome passed`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    argsByTool["signIn"] = buildJsonObject { put("persona", "merchant") }
    stepTools["tap"] = { TrailblazeToolResult.Success() }

    runTrail(runnerUtil(), "tap")

    assertEquals(1, signIn.teardownCalls.size)
    val (args, trailRun) = signIn.teardownCalls.single()
    assertEquals("merchant", args["persona"]?.jsonPrimitive?.content)
    assertEquals("passed", trailRun["outcome"]?.jsonPrimitive?.content)
  }

  @Test
  fun `the next trail on the device receives a reusable result as previousRun`() {
    val signIn = FakeTrailhead(teardown = { _, _ -> result(true, "signed in, nothing changed", buildJsonObject { put("account", "a1") }) })
      .also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    // What the runners check before their pre-trail force-stop; asking does not consume it.
    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[0])
    val last = assertNotNull(signIn.seenPreviousRun[1])
    assertEquals("signed in, nothing changed", last.reason)
    assertEquals("a1", last.state?.get("account")?.jsonPrimitive?.content)
    assertEquals("s-1", last.sessionId)
  }

  @Test
  fun `a failed trail's failure screenshot shows the screen before teardown changed it`() {
    val logs = mutableListOf<TrailblazeLog>()
    val logger = TrailblazeLogger(LogEmitter(logs::add), ScreenStateLogger { "screen.png" })
    var screen = "failed screen"
    FakeTrailhead(teardown = { _, _ ->
      screen = "signed out"
      result(false, "signed out")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "button missing") }

    assertFailsWith<TrailblazeException> { runTrail(runnerUtil(logger = logger, screen = { LabeledScreen(screen) }), "tap") }

    val shots = logs.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
      .filter { it.displayName == TrailblazeLogger.FAILURE_SNAPSHOT_NAME }
    assertEquals(listOf("failed screen"), shots.map { it.viewHierarchy.className })
    assertEquals("signed out", screen)
    // So the session's end-of-run capture, which would see the torn-down screen, skips its own.
    assertTrue(logger.hasFailureSnapshot(session.sessionId))
  }

  @Test
  fun `a failure capture that asserts still lets teardown run and keeps the trail's error`() {
    val logger = TrailblazeLogger(LogEmitter {}, ScreenStateLogger { "screen.png" })
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "button missing") }

    val error = assertFailsWith<TrailblazeException> {
      runTrail(runnerUtil(logger = logger, screen = { throw AssertionError("two compose roots") }), "tap")
    }

    assertTrue(error.message.orEmpty().contains("button missing"), error.message)
    assertEquals(1, signIn.teardownCalls.size)
  }

  @Test
  fun `a teardown whose dispatch fails still drops what an earlier trail handed on`() {
    FakeTrailhead(teardown = { _, _ -> result(true, "signed in, nothing changed") }).also { trailheads["signIn"] = it }
    FakeTrailhead(trailheadToolName = "other").also { trailheads["other"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()
    runTrail(util, "tap")
    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))

    // A trail with another trailhead changes the device, then its Teardown step never runs.
    nextSession("s-2")
    teardownDispatchFails = true
    runTrail(util, "tap", trailhead = listOf("other"))

    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
  }

  @Test
  fun `a failed trail runs teardown with outcome failed, keeps its own error, and hands on nothing`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "button missing") }
    val util = runnerUtil()

    val error = assertFailsWith<TrailblazeException> { runTrail(util, "tap") }
    assertTrue(error.message!!.contains("button missing"))
    assertEquals("failed", signIn.teardownCalls.single().second["outcome"]?.jsonPrimitive?.content)

    stepTools["tap"] = { TrailblazeToolResult.Success() }
    nextSession("s-2")
    runTrail(util, "tap")
    assertNull(signIn.seenPreviousRun[1])
    val why = previousRunReasons().last()
    assertTrue(why.startsWith("the last run was not reusable:") && why.contains("failed"), why)
  }

  @Test
  fun `a trailhead that fails still gets teardown, with outcome setup-failed`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    // The trailhead tool started, then a second trailhead tool failed the setup.
    stepTools["seedData"] = { TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "seed failed") }

    assertFailsWith<TrailheadException> { runTrail(runnerUtil(), trailhead = listOf("signIn", "seedData")) }

    assertEquals("setup-failed", signIn.teardownCalls.single().second["outcome"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a cancelled trail still runs teardown, with outcome cancelled, and hands on nothing`() = runBlocking<Unit> {
    // Its teardown would say reusable; a cancelled trail must not hand that on.
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    val util = runnerUtil()
    val setUp = CompletableDeferred<Unit>()

    val trail = launch(Dispatchers.Default) {
      util.runTrail {
        util.runPrompt(listOf(trailheadStep("signIn")), useRecordedSteps = true, selfHeal = false)
        setUp.complete(Unit)
        awaitCancellation()
      }
    }
    setUp.await()
    trail.cancelAndJoin()

    assertEquals("cancelled", signIn.teardownCalls.single().second["outcome"]?.jsonPrimitive?.content)
    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
  }

  @Test
  fun `cancelling a trail cancels the recorded tool it is waiting on`() = runBlocking<Unit> {
    FakeTrailhead().also { trailheads["signIn"] = it }
    val waiting = CompletableDeferred<Unit>()
    // Waits the way a dispatched host-local tool does: in its own scope, linked to the trail.
    stepTools["wait"] = { coroutineScope { DispatchCancellation.linked(this) { waiting.complete(Unit); awaitCancellation() } } }
    val util = runnerUtil()

    // Its own scope, so a wait the cancel never reaches fails the test instead of hanging it.
    val trail = CoroutineScope(Dispatchers.Default).launch {
      util.runTrail {
        util.runPromptSuspend(listOf(trailheadStep("signIn")), useRecordedSteps = true, selfHeal = false)
        util.runPromptSuspend(listOf(step("wait")), useRecordedSteps = true, selfHeal = false)
      }
    }
    withTimeout(5_000) { waiting.await() }
    trail.cancel()

    withTimeout(5_000) { trail.join() }
  }

  @Test
  fun `cancelling a trail cancels a tools block it is waiting on`() = runBlocking<Unit> {
    val waiting = CompletableDeferred<Unit>()
    stepTools["wait"] = { coroutineScope { DispatchCancellation.linked(this) { waiting.complete(Unit); awaitCancellation() } } }
    val util = runnerUtil()

    // A YAML `tools:` block: dispatched straight through, not as a recorded step.
    val trail = CoroutineScope(Dispatchers.Default).launch {
      util.runTrail { util.runTrailblazeTool(listOf(InputTextTrailblazeTool(text = "wait"))) }
    }
    withTimeout(5_000) { waiting.await() }
    trail.cancel()

    withTimeout(5_000) { trail.join() }
  }

  @Test
  fun `a passing trail whose teardown says not reusable hands on nothing, and the log says why`() {
    val signIn = FakeTrailhead(teardown = { _, _ -> result(false, "changed the device locale") })
      .also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertEquals("the last run was not reusable: changed the device locale", previousRunReasons().last())
  }

  @Test
  fun `with reuse off the trailhead gets no previousRun, and the hand-off is consumed anyway`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil(reuse = false)

    runTrail(util, "tap")
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertEquals(2, signIn.teardownCalls.size)
  }

  @Test
  fun `different args mean no previousRun`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    argsByTool["signIn"] = buildJsonObject { put("persona", "merchant") }
    runTrail(util, "tap")
    argsByTool["signIn"] = buildJsonObject { put("persona", "buyer") }
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertEquals("args differed: persona", previousRunReasons().last())
  }

  @Test
  fun `a registered veto forces reusable false, so nothing is handed on`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    TeardownReuseVetoes.register { "holds a leased persona" }.use {
      runTrail(util, "tap")
    }
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertTrue(previousRunReasons().last().contains("holds a leased persona"), previousRunReasons().last())
  }

  @Test
  fun `a teardown that throws leaves the trail passing and hands on nothing`() {
    val signIn = FakeTrailhead(teardown = { _, _ -> error("cleanup API down") }).also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    nextSession("s-2")
    signIn.teardown = { _, _ -> result(true, "clean") }
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertTrue(previousRunReasons().last().contains("cleanup API down"), previousRunReasons().last())
  }

  @Test
  fun `notes from trail tools reach teardown and a reused setup is logged`() {
    val signIn = FakeTrailhead(handler = { last ->
      if (last != null) reuseMeta("already signed in", ok = true) else null
    }).also { trailheads["signIn"] = it }
    stepTools["makeItem"] = { ctx ->
      TrailheadRuns.completeInvocation(ctx, "makeItem", null, meta {
        put("notes", buildJsonArray { add(buildJsonObject { put("created", buildJsonObject { put("kind", "item"); put("id", "i-7") }) }) })
      })
      TrailblazeToolResult.Success()
    }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "makeItem")
    val notes = signIn.teardownCalls.single().second["notes"]!!.jsonArray
    assertEquals("i-7", notes.single().jsonObject["created"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals("makeItem", notes.single().jsonObject["tool"]!!.jsonPrimitive.content)

    nextSession("s-2")
    runTrail(util, "tap")
    // Notes never carry across trails.
    assertEquals(0, signIn.teardownCalls[1].second["notes"]!!.jsonArray.size)
    val setup = trailheadEvents().last { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.SETUP }
    assertEquals(TrailheadEventStream.Setup.REUSED, setup["setup"]?.jsonPrimitive?.content)
    assertEquals("s-1", setup["previousRunSessionId"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a reuse whose catch-up threw is logged as a failed reuse of that session, and fails the trailhead`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()
    runTrail(util, "tap")

    nextSession("s-2")
    signIn.handler = { last -> if (last != null) reuseMeta("on home", ok = false, error = "cart would not clear") else null }
    signIn.handlerFails = true
    assertFailsWith<TrailheadException> { runTrail(util, "tap") }

    val setup = trailheadEvents().last { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.SETUP }
    assertEquals(TrailheadEventStream.Setup.REUSE_FAILED, setup["setup"]?.jsonPrimitive?.content)
    assertEquals("s-1", setup["previousRunSessionId"]?.jsonPrimitive?.content)
    assertEquals("cart would not clear", setup["error"]?.jsonPrimitive?.content)
    assertEquals("setup-failed", signIn.teardownCalls.last().second["outcome"]?.jsonPrimitive?.content)

    // A failed reuse never hands on a setup.
    nextSession("s-3")
    signIn.handler = { null }
    signIn.handlerFails = false
    runTrail(util, "tap")
    assertNull(signIn.seenPreviousRun.last())
  }

  @Test
  fun `a handler that throws after a reuse it reported as finished is still a failed reuse`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()
    runTrail(util, "tap")

    nextSession("s-2")
    signIn.handler = { last -> if (last != null) reuseMeta("on home", ok = true) else null }
    signIn.handlerFails = true
    assertFailsWith<TrailheadException> { runTrail(util, "tap") }

    val setup = trailheadEvents().last { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.SETUP }
    assertEquals(TrailheadEventStream.Setup.REUSE_FAILED, setup["setup"]?.jsonPrimitive?.content)
    assertEquals(true, setup["failed"]?.jsonPrimitive?.content?.toBoolean())
  }

  @Test
  fun `a trailhead without teardown consumes the hand-off and gets no teardown call`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    signIn.defines = false
    nextSession("s-2")
    runTrail(util, "tap")
    signIn.defines = true
    nextSession("s-3")
    runTrail(util, "tap")

    assertEquals(2, signIn.teardownCalls.size)
    assertNull(signIn.seenPreviousRun[2])
  }

  @Test
  fun `a trailhead with no recorded tools still gives event queries the trail's start`() {
    var sinceTrail: TrailblazeHostFunctionDispatcher.Outcome? = null
    stepTools["tap"] = { ctx ->
      FileEventSink(sessionDir).use { it.append("network", System.currentTimeMillis(), buildJsonObject { put("url", "/v1/items") }) }
      sinceTrail = runBlocking {
        TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.QUERY, """{"stream":"network","since":"trail"}""", ctx)
      }
      TrailblazeToolResult.Success()
    }
    // An event before the trail started is outside the trail's window.
    FileEventSink(sessionDir).use { it.append("network", 1L, buildJsonObject { put("url", "/v1/old") }) }
    val util = runnerUtil()

    util.runTrailBlocking {
      util.runPrompt(listOf(TrailheadDefinition(step = "Start signed in").toPromptStep()), useRecordedSteps = true, selfHeal = false)
      util.runPrompt(listOf(step("tap")), useRecordedSteps = true, selfHeal = false)
    }

    val outcome = sinceTrail
    assertTrue(outcome is TrailblazeHostFunctionDispatcher.Outcome.Success, outcome.toString())
    val urls = outcome.value.jsonObject["events"]!!.jsonArray.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content }
    assertEquals(listOf("/v1/items"), urls)
  }

  @Test
  fun `events query filters the trail window in Kotlin, and only inside teardown`() {
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    var queried: Any? = null
    var outsideError: String? = null
    FakeTrailhead(teardown = { _, _ ->
      queried = runBlocking {
        TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network","match":{"method":"POST","url":{"${'$'}regex":"/v1/ITEMS","${'$'}flags":"i"}}}""",
          context(),
        )
      }
      result(false, "made items")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = { ctx ->
      FileEventSink(sessionDir).use { sink ->
        val now = System.currentTimeMillis()
        sink.append("network", now, buildJsonObject { put("method", "POST"); put("url", "/v1/items/9") })
        sink.append("network", now, buildJsonObject { put("method", "GET"); put("url", "/v1/items") })
      }
      outsideError = (runBlocking {
        TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST, "{}", ctx)
      } as TrailblazeHostFunctionDispatcher.Outcome.Failure).message
      TrailblazeToolResult.Success()
    }
    // An event before the trail started is outside the default window.
    FileEventSink(sessionDir).use {
      it.append("network", 1L, buildJsonObject { put("method", "POST"); put("url", "/v1/items/old") })
    }

    runTrail(runnerUtil(), "tap")

    val records = (queried as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
    assertEquals(listOf("/v1/items/9"), records.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content })
    assertTrue(outsideError!!.contains("only available inside"), outsideError)
  }

  @Test
  fun `a mark lasts through its trail's teardown, and the next trail in the session can't read from it`() {
    fun query(ctx: TrailblazeToolExecutionContext) = runBlocking {
      TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.QUERY, """{"stream":"network","since":"x"}""", ctx)
    }
    var inTeardown: TrailblazeHostFunctionDispatcher.Outcome? = null
    FakeTrailhead(teardown = { _, _ -> if (inTeardown == null) inTeardown = query(context()); result(false, "done") }).also { trailheads["signIn"] = it }
    var marked: TrailblazeHostFunctionDispatcher.Outcome? = null
    stepTools["mark"] = { ctx ->
      marked = runBlocking { TrailblazeHostFunctionDispatcher.call(SessionEventsHostFunctions.MARK, """{"name":"x"}""", ctx) }
      TrailblazeToolResult.Success()
    }
    var nextTrail: TrailblazeHostFunctionDispatcher.Outcome? = null
    stepTools["read"] = { ctx -> nextTrail = query(ctx); TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "mark")
    runTrail(util, "read")

    assertTrue(marked is TrailblazeHostFunctionDispatcher.Outcome.Success, marked.toString())
    assertTrue(inTeardown is TrailblazeHostFunctionDispatcher.Outcome.Success, inTeardown.toString())
    val failure = nextTrail as? TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertTrue(failure?.message?.contains("no mark named 'x'") == true, nextTrail.toString())
  }

  @Test
  fun `events query puts device-stamped records on the host clock before applying the window`() {
    var queried: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      queried = runBlocking {
        (TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY, """{"stream":"app"}""", context())
          as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    // The device clock runs an hour ahead: raw, its stamp would land after the trail ended.
    val aheadMs = 3_600_000L
    stepTools["tap"] = {
      val now = System.currentTimeMillis()
      DeviceClockSyncFile.appendSample(sessionDir, deviceId.instanceId, DeviceClockSample(now, now, now + aheadMs))
      FileEventSink(sessionDir).use { sink ->
        sink.append("app", now + aheadMs, buildJsonObject { put("name", "checkout") }, TrailblazeClockDomain.DEVICE)
      }
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    val record = queried!!.single().jsonObject
    assertEquals("checkout", record["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertTrue(record["timeMs"]!!.jsonPrimitive.content.toLong() < System.currentTimeMillis(), record.toString())
  }

  @Test
  fun `a trail without a capable trailhead in between drops the hand-off`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    nextSession("s-2")
    runTrailWithoutTrailhead(util, "tap")
    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
    nextSession("s-3")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
  }

  @Test
  fun `a device holds a reusable setup only after a teardown said reusable`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val otherDevice = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)

    runTrail(runnerUtil(), "tap")
    assertTrue(TrailheadHandoffStore.holdsReusable(deviceId))
    assertFalse(TrailheadHandoffStore.holdsReusable(otherDevice))

    signIn.teardown = { _, _ -> result(reusable = false, reason = "left a sale open") }
    nextSession("s-2")
    runTrail(runnerUtil(), "tap")
    assertFalse(TrailheadHandoffStore.holdsReusable(deviceId))
  }

  @Test
  fun `only a trail whose trailhead takes the hand-off skips the clean start`() {
    FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }

    runTrail(runnerUtil(), "tap")

    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("openSettings")))
    assertFalse(TrailheadHandoffStore.holdsReusableFor(deviceId, emptyList()))
    // The runners decode the trail with no scripted tools registered; the name still comes through.
    val decoded = createTrailblazeYaml().decodeTrail(
      yaml = """
        trailhead:
          step: Start signed in
          recording:
            android-phone:
              signIn: {}
        trail:
          - step: tap
      """.trimIndent(),
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, decoded))
  }

  @Test
  fun `two capable trailheads on one device each receive their own previousRun`() {
    val signIn = FakeTrailhead(teardown = { _, _ -> result(true, "signed in") }).also { trailheads["signIn"] = it }
    val seed = FakeTrailhead(trailheadToolName = "seedCatalog", teardown = { _, _ -> result(true, "catalog seeded") })
      .also { trailheads["seedCatalog"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap", trailhead = listOf("signIn", "seedCatalog"))
    nextSession("s-2")
    runTrail(util, "tap", trailhead = listOf("signIn", "seedCatalog"))

    assertEquals("signed in", signIn.seenPreviousRun[1]?.reason)
    assertEquals("catalog seeded", seed.seenPreviousRun[1]?.reason)
  }

  @Test
  fun `a step's invalidates note forces reusable false, naming the note`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["changeLocale"] = { ctx ->
      TrailheadRuns.completeInvocation(ctx, "changeLocale", null, meta {
        put("notes", buildJsonArray { add(buildJsonObject { put("invalidates", "device locale") }) })
      })
      TrailblazeToolResult.Success()
    }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "changeLocale")
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertTrue(previousRunReasons().last().contains("invalidated the setup: device locale"), previousRunReasons().last())
  }

  @Test
  fun `a state over the cap is dropped and forces reusable false`() {
    val big = buildJsonObject { put("blob", "x".repeat(TrailheadRuns.MAX_STATE_BYTES + 1)) }
    val signIn = FakeTrailhead(teardown = { _, _ -> result(true, "clean", big) }).also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "tap")
    nextSession("s-2")
    runTrail(util, "tap")

    assertNull(signIn.seenPreviousRun[1])
    assertTrue(previousRunReasons().last().contains("over 16 KB"), previousRunReasons().last())
  }

  @Test
  fun `a trailhead set up on one device is not torn down from another, and hands on nothing`() {
    val seller = deviceId
    val buyer = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(seller, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(buyer, null, null, null),
      ),
    )
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["handOver"] = { bindings!!.switchTo("buyer"); TrailblazeToolResult.Success() }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    runTrail(util, "handOver")
    assertEquals(0, signIn.teardownCalls.size)
    val teardown = trailheadEvents().last { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.TEARDOWN }
    assertEquals(false, teardown["reusable"]?.jsonPrimitive?.content?.toBoolean())
    assertTrue(teardown["reason"]!!.jsonPrimitive.content.contains("set up device seller"), teardown.toString())
    assertEquals("buyer", teardown[TrailheadEventLog.DEVICE_FIELD]?.jsonPrimitive?.content)

    // Back on the seller, the next trail does a full setup.
    bindings!!.switchTo("seller")
    nextSession("s-2")
    runTrail(util, "tap")
    assertNull(signIn.seenPreviousRun[1])
    assertEquals(1, signIn.teardownCalls.size)
  }

  @Test
  fun `a multi-device trail drops the hand-off on a bound device its trailhead never set up`() {
    FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val util = runnerUtil()

    // A trail on the buyer alone hands its setup on.
    activeDeviceId = buyerId
    runTrail(util, "tap")
    assertTrue(TrailheadHandoffStore.holdsReusableFor(buyerId, trailheadItems("signIn")))

    // A seller-and-buyer trail sets up only the seller, but could have changed the buyer.
    activeDeviceId = deviceId
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(buyerId, null, null, null),
      ),
    )
    nextSession("s-2")
    runTrail(util, "tap")

    assertFalse(TrailheadHandoffStore.holdsReusableFor(buyerId, trailheadItems("signIn")))
    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
  }

  @Test
  fun `in a multi-device trail a query naming the other device's stream is refused`() {
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(buyerId, null, null, null),
      ),
    )
    var outcome: TrailblazeHostFunctionDispatcher.Outcome? = null
    FakeTrailhead(teardown = { _, _ ->
      outcome = runBlocking {
        TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network.buyer"}""",
          context(),
        )
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      // The buyer's capture, whose records carry no device field.
      File(sessionDir, "events").mkdirs()
      File(sessionDir, "events/network.buyer.ndjson").appendText(
        """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/buyer"}""" + "\n",
      )
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertTrue(outcome !is TrailblazeHostFunctionDispatcher.Outcome.Success, outcome.toString())
    assertTrue(outcome.toString().contains("device buyer's stream"), outcome.toString())
  }

  @Test
  fun `teardown still runs when logging its section fails`() {
    val signIn = FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val logger = TrailblazeLogger(
      LogEmitter { log ->
        val step = (log as? TrailblazeLog.ObjectiveStartLog)?.promptStep?.prompt
          ?: (log as? TrailblazeLog.ObjectiveCompleteLog)?.promptStep?.prompt
        if (step == TeardownWire.OBJECTIVE_STEP) throw java.io.IOException("disk full")
      },
      ScreenStateLogger { "screen.png" },
    )

    runTrail(runnerUtil(logger = logger), "tap")

    assertEquals(1, signIn.teardownCalls.size)
    assertTrue(TrailheadHandoffStore.holdsReusableFor(deviceId, trailheadItems("signIn")))
  }

  @Test
  fun `in a multi-device trail a stream is owned by the device with the longest matching name`() {
    val kiosk = TrailblazeDeviceId(instanceId = "emulator-5558", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "kiosk.buyer" to SessionDeviceBindings.BoundDevice(kiosk, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(buyerId, null, null, null),
        "buyer_device" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
      ),
    )
    bindings!!.switchTo("kiosk.buyer")
    var listed: JsonArray? = null
    var own: TrailblazeHostFunctionDispatcher.Outcome? = null
    var alias: TrailblazeHostFunctionDispatcher.Outcome? = null
    FakeTrailhead(teardown = { _, _ ->
      runBlocking {
        listed = (TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST, "{}", context())
          as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
        own = TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network.kiosk.buyer"}""",
          context(),
        )
        // Written to disk as `network.buyer_device`, so it names that device's file.
        alias = TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network.buyer/device"}""",
          context(),
        )
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      val now = System.currentTimeMillis()
      File(sessionDir, "events").mkdirs()
      listOf("kiosk.buyer", "buyer", "buyer_device").forEach { name ->
        File(sessionDir, "events/network.$name.ndjson").appendText(
          """{"timestampMs":$now,"url":"/v1/$name"}""" + "\n",
        )
      }
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertEquals(listOf("network"), listed!!.map { it.jsonPrimitive.content }.filter { it.startsWith("network") })
    val ownRecords = ((own as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray)
    assertEquals(listOf("/v1/kiosk.buyer"), ownRecords.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content })
    assertTrue(alias.toString().contains("device buyer_device's stream"), alias.toString())
  }

  @Test
  fun `in a multi-device trail a query skips the other device's trailhead records`() {
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(
          TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
          null,
          null,
          null,
        ),
      ),
    )
    var queried: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      queried = (runBlocking {
        TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"trailhead","window":"trailhead-and-trail"}""",
          context(),
        )
      } as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["buyerNote"] = { ctx ->
      bindings!!.switchTo("buyer")
      TrailheadEventLog.append(ctx, TrailheadEventStream.Type.NOTE) { put("tool", "buyerNote") }
      bindings!!.switchTo("seller")
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "buyerNote")

    val devices = queried!!.map { it.jsonObject["data"]!!.jsonObject[TrailheadEventLog.DEVICE_FIELD]?.jsonPrimitive?.content }
    assertTrue(devices.isNotEmpty())
    assertEquals(setOf("seller"), devices.toSet())
  }

  @Test
  fun `a single-device query reads the network capture written at the session root`() {
    var queried: JsonArray? = null
    var listed: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      runBlocking {
        listed = (TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST, "{}", context())
          as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
        queried = (TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network","match":{"method":"POST"}}""",
          context(),
        ) as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      // A capture's own schema, timed by `timestampMs`, the way web and mitmproxy capture write it.
      File(sessionDir, "network.ndjson").appendText(
        """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/items"}""" + "\n",
      )
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertTrue(listed!!.map { it.jsonPrimitive.content }.contains("network"), listed.toString())
    assertEquals(listOf("/v1/items"), queried!!.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content })
  }

  @Test
  fun `teardown reads a capture's last record even when it has no closing newline`() {
    var queried: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      runBlocking {
        queried = (TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network","match":{"method":"POST"}}""",
          context(),
        ) as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      File(sessionDir, "network.ndjson").appendText(
        """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/first"}""" + "\n" +
          """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/last"}""",
      )
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertEquals(
      listOf("/v1/first", "/v1/last"),
      queried!!.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun `in a multi-device trail a query reads the active device's network capture at the session root`() {
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
        "buyer" to SessionDeviceBindings.BoundDevice(buyerId, null, null, null),
      ),
    )
    var queried: JsonArray? = null
    var listed: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      runBlocking {
        listed = (TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST, "{}", context())
          as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
        queried = (TrailblazeHostFunctionDispatcher.call(
          TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY,
          """{"stream":"network"}""",
          context(),
        ) as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      // A multi-device capture: one file per device at the session root.
      for (device in listOf("seller", "buyer")) {
        File(sessionDir, "network.$device.ndjson").appendText(
          """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/$device"}""" + "\n",
        )
      }
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertTrue(listed!!.map { it.jsonPrimitive.content }.contains("network"), listed.toString())
    assertEquals(listOf("/v1/seller"), queried!!.map { it.jsonObject["data"]!!.jsonObject["url"]!!.jsonPrimitive.content })
  }

  @Test
  fun `a root network capture is matched by the device's own label, not a sanitized one`() {
    bindings = SessionDeviceBindings(
      linkedMapOf(
        "buyer tablet" to SessionDeviceBindings.BoundDevice(deviceId, null, null, null),
        "buyer_tablet" to SessionDeviceBindings.BoundDevice(buyerId, null, null, null),
      ),
    )
    var queried: JsonArray? = null
    FakeTrailhead(teardown = { _, _ ->
      queried = runBlocking {
        (TrailblazeHostFunctionDispatcher.call(TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY, """{"stream":"network"}""", context())
          as TrailblazeHostFunctionDispatcher.Outcome.Success).value as JsonArray
      }
      result(false, "queried")
    }).also { trailheads["signIn"] = it }
    stepTools["tap"] = {
      // Only the sibling captured anything; its file is what sanitizing the active label would name.
      File(sessionDir, "network.buyer_tablet.ndjson").appendText(
        """{"timestampMs":${System.currentTimeMillis()},"method":"POST","url":"/v1/sibling"}""" + "\n",
      )
      TrailblazeToolResult.Success()
    }

    runTrail(runnerUtil(), "tap")

    assertEquals(emptyList(), queried!!.toList())
  }

  @Test
  fun `with no session directory on disk the trailhead writes no files`() {
    val missing = File(sessionDir, "never-created")
    FakeTrailhead().also { trailheads["signIn"] = it }
    stepTools["tap"] = { TrailblazeToolResult.Success() }
    val noLogging = TrailblazeRunnerUtil(
      runTrailblazeTool = { tools: List<TrailblazeTool> ->
        runBlocking {
          val ctx = context().let {
            TrailblazeToolExecutionContext(
              screenState = null,
              traceId = null,
              trailblazeDeviceInfo = it.trailblazeDeviceInfo,
              sessionProvider = it.sessionProvider,
              trailblazeLogger = it.trailblazeLogger,
              memory = it.memory,
              sessionDirProvider = { missing },
            )
          }
          var last: TrailblazeToolResult = TrailblazeToolResult.Success()
          for (tool in tools) {
            last = when (tool) {
              is InputTextTrailblazeTool -> trailheads[tool.text]?.run(ctx, JsonObject(emptyMap())) ?: stepTools.getValue(tool.text)(ctx)
              is ExecutableTrailblazeTool -> tool.execute(ctx)
              else -> error("unexpected tool $tool")
            }
          }
          last
        }
      },
      trailblazeRunner = NoAiRunner,
      sessionProvider = { session },
      trailheadReuse = true,
    )

    runTrail(noLogging, "tap")

    assertFalse(missing.exists())
  }

  private fun trailheadEvents(): List<JsonObject> =
    File(sessionDir, "events/trailhead.ndjson").readLines().map {
      xyz.block.trailblaze.logs.client.TrailblazeJsonInstance.parseToJsonElement(it).jsonObject["data"]!!.jsonObject
    }

  /** Why each trailhead got `previousRun: null`, in order, from the `trailhead` event stream. */
  private fun previousRunReasons(): List<String> = trailheadEvents()
    .filter { it["type"]?.jsonPrimitive?.content == TrailheadEventStream.Type.PREVIOUS_RUN }
    .mapNotNull { it["reason"]?.jsonPrimitive?.content }

  private fun reuseMeta(reason: String, ok: Boolean, error: String? = null): JsonObject = meta {
    put("reuse", buildJsonObject {
      put("reason", reason)
      put("startedAtMs", 1_000L)
      put("ok", ok)
      if (error != null) put("error", error)
    })
  }

  private fun meta(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject { put("trailblaze", buildJsonObject { put(TeardownWire.META_KEY_TRAILHEAD, buildJsonObject(block)) }) }

  private fun result(reusable: Boolean, reason: String, state: JsonObject? = null): JsonObject = buildJsonObject {
    put("reusable", reusable)
    put("reason", reason)
    if (state != null) put("state", state)
  }

  /** A screen told apart by its root's class name. */
  private class LabeledScreen(label: String) : ScreenState {
    // logSnapshot logs nothing for a screen without screenshot bytes.
    override val screenshotBytes: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(nodeId = 1, className = label)
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private object NoAiRunner : TestAgentRunner {
    override val screenStateProvider: () -> ScreenState = { error("no screen") }
    override fun run(prompt: xyz.block.trailblaze.yaml.PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus = done(prompt)
    override suspend fun runSuspend(prompt: xyz.block.trailblaze.yaml.PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus = done(prompt)
    override fun recover(promptStep: xyz.block.trailblaze.yaml.PromptStep, recordingResult: PromptRecordingResult.Failure): AgentTaskStatus =
      error("recover not expected")
    override fun appendToSystemPrompt(context: String) = Unit

    private fun done(prompt: xyz.block.trailblaze.yaml.PromptStep) = AgentTaskStatus.Success.ObjectiveComplete(
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
