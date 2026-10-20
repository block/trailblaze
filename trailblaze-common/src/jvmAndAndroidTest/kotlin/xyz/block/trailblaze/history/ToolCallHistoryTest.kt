package xyz.block.trailblaze.history

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionDispatcher.Outcome
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.SensitiveValuesTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import kotlin.test.Test

/**
 * `ctx.history` as the framework writes it: through the production dispatch loop, the shared trail
 * runner, and the host functions a script reads it with. Each test runs in its own session.
 */
class ToolCallHistoryTest {

  private val session = TrailblazeSession(sessionId = SessionId("history-${UUID.randomUUID()}"), startTime = Clock.System.now())
  private val sessionId = session.sessionId
  private val agent = Agent()

  @Test
  fun `a call another tool makes names the call it ran inside`() {
    run(Outer("outer", inner = Leaf("inner")))

    val calls = ToolCallHistory.toolCalls(sessionId)
    assertThat(calls.map { it.name }).containsExactly("outer", "inner")
    val (outer, inner) = calls
    assertThat(outer.parentId).isNull()
    assertThat(inner.parentId).isEqualTo(outer.id)
    assertThat(outer.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "outer done"))
    assertThat(inner.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "inner done"))
    assertThat(inner.startedAt >= outer.startedAt && inner.endedAt!! <= outer.endedAt!!).isEqualTo(true)
  }

  @Test
  fun `a failed call keeps its error, with secrets in it masked`() {
    agent.memory.rememberSensitive("password", "hunter2")

    run(Leaf("signIn", TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "wrong password hunter2")))

    val outcome = ToolCallHistory.toolCalls(sessionId).single().outcome!!
    assertThat(outcome.success).isEqualTo(false)
    assertThat(outcome.message!!).contains("wrong password")
    assertThat(outcome.message!!).doesNotContain("hunter2")
  }

  @Test
  fun `args hold the values the tool received and rawArgs the tokens as written, never a secret`() {
    agent.memory.remember("newItemId", "item-1")
    agent.memory.rememberSensitive("password", "hunter2")

    run(
      WithArgs(
        "signIn",
        buildJsonObject {
          put("id", "{{newItemId}}")
          put("password", "{{password}}")
        },
      ),
    )

    val call = ToolCallHistory.toolCalls(sessionId).single()
    assertThat(call.args).isEqualTo(
      buildJsonObject {
        put("id", "item-1")
        put("password", "{{password}}")
      },
    )
    assertThat(call.rawArgs).isEqualTo(
      buildJsonObject {
        put("id", "{{newItemId}}")
        put("password", "{{password}}")
      },
    )
  }

  @Test
  fun `a secret a tool declares as a memory token is masked once resolved, in args and outcome`() {
    agent.memory.remember("token", "abc123")

    run(Shell(command = listOf("printf", "abc123"), secrets = listOf("{{token}}")))

    val call = ToolCallHistory.toolCalls(sessionId).single()
    assertThat(call.args.toString()).doesNotContain("abc123")
    assertThat(call.rawArgs.toString()).doesNotContain("abc123")
    assertThat(call.outcome!!.message!!).doesNotContain("abc123")
  }

  @Test
  fun `a secret inside a JSON message is masked in its escaped form too`() {
    agent.memory.rememberSensitive("password", "hun\"ter\\2")

    run(Leaf("signIn", TrailblazeToolResult.Success(message = """{"password":"hun\"ter\\2"}""")))

    assertThat(ToolCallHistory.toolCalls(sessionId).single().outcome!!.message)
      .isEqualTo("""{"password":"{{password}}"}""")
  }

  @Test
  fun `a declared secret with a quote in it is masked where a JSON message escapes it`() {
    run(JsonWithSecret("signIn", secret = "s\"3cret", message = """{"pin":"s\"3cret"}"""))

    assertThat(ToolCallHistory.toolCalls(sessionId).single().outcome!!.message!!).doesNotContain("3cret")
  }

  @Test
  fun `a tool whose result is a credential keeps its outcome but not the credential`() {
    run(Credential("resolveSession", TrailblazeToolResult.Success(message = """{"sessionToken":"tok-123"}""")))
    run(Credential("resolveSession", TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "sign-in refused")))

    assertThat(ToolCallHistory.toolCalls(sessionId).map { it.outcome }).containsExactly(
      HistoryToolCallOutcome(success = true, message = "<redacted>"),
      HistoryToolCallOutcome(success = false, message = "sign-in refused"),
    )
  }

  @Test
  fun `a credential result the tool log records is masked too`() {
    agent.logToolExecution(
      tool = Credential("resolveSession"),
      timeBeforeExecution = Clock.System.now(),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      result = TrailblazeToolResult.Success(message = "tok-123"),
    )

    assertThat(ToolCallHistory.toolCalls(sessionId).single().outcome!!.message).isEqualTo("<redacted>")
  }

  @Test
  fun `a tool a daemon route runs with no execution context is recorded under the session it names`() = runBlocking {
    val result = ToolCallHistory.recordExecution(sessionId, Leaf("sentToDevice"), device = "phone") {
      TrailblazeToolResult.Success(message = "done on device")
    }

    val call = ToolCallHistory.toolCalls(sessionId).single()
    assertThat(result).isEqualTo(TrailblazeToolResult.Success(message = "done on device"))
    assertThat(listOf(call.name, call.device)).containsExactly("sentToDevice", "phone")
    assertThat(call.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "done on device"))
  }

  @Test
  fun `a call another tool makes with a secret already resolved records the token, not the secret`() {
    agent.memory.rememberSensitive("password", "hunter2")

    run(Outer("signIn", inner = WithArgs("inputText", buildJsonObject { put("text", "hunter2") })))

    val inner = ToolCallHistory.toolCalls(sessionId).single { it.name == "inputText" }
    assertThat(inner.args).isEqualTo(buildJsonObject { put("text", "{{password}}") })
    assertThat(inner.rawArgs).isEqualTo(buildJsonObject { put("text", "{{password}}") })
  }

  @Test
  fun `a secret the call replaces while it runs stays masked in its outcome`() {
    agent.memory.rememberSensitive("password", "hunter2")
    run(RotatesSecret("returns", thenThrow = false))
    agent.memory.rememberSensitive("password", "hunter2")
    runCatching { run(RotatesSecret("throws", thenThrow = true)) }

    val messages = ToolCallHistory.toolCalls(sessionId).map { it.outcome!!.message!! }
    assertThat(messages.size).isEqualTo(2)
    messages.forEach {
      assertThat(it).contains("rejected")
      assertThat(it).doesNotContain("hunter2")
    }
  }

  @Test
  fun `a session still running survives the sessions that start and end after it`() {
    ToolCallHistory.openSection(sessionId, HistorySectionKind.TRAIL, "trail")
    run(Leaf("first"))
    val idle = SessionId("history-idle-${UUID.randomUUID()}")
    ToolCallHistory.end(ToolCallHistory.begin(idle, "done", JsonObject(emptyMap()), device = null), success = true, message = null)

    repeat(ToolCallHistory.MAX_SESSIONS + 8) {
      val other = SessionId("history-other-${UUID.randomUUID()}")
      ToolCallHistory.end(ToolCallHistory.begin(other, "x", JsonObject(emptyMap()), device = null), success = true, message = null)
    }

    assertThat(ToolCallHistory.toolCalls(sessionId).map { it.name }).containsExactly("first")
    assertThat(ToolCallHistory.toolCalls(idle)).isEqualTo(emptyList())
  }

  @Test
  fun `reading an idle session makes it the most recently used, so older ones go first`() {
    fun finishedIn(id: SessionId) =
      ToolCallHistory.end(ToolCallHistory.begin(id, "done", JsonObject(emptyMap()), device = null), success = true, message = null)
    // Sessions other tests left behind would take eviction slots.
    ToolCallHistory.clear()
    val read = SessionId("history-read-${UUID.randomUUID()}")
    val unread = SessionId("history-unread-${UUID.randomUUID()}")
    finishedIn(read)
    finishedIn(unread)
    repeat(ToolCallHistory.MAX_SESSIONS - 2) { finishedIn(SessionId("history-other-${UUID.randomUUID()}")) }

    ToolCallHistory.toolCalls(read)
    finishedIn(SessionId("history-newest-${UUID.randomUUID()}"))

    assertThat(ToolCallHistory.toolCalls(read).map { it.name }).containsExactly("done")
    assertThat(ToolCallHistory.toolCalls(unread)).isEqualTo(emptyList())
  }

  @Test
  fun `a session past its call cap drops its oldest finished calls`() {
    repeat(ToolCallHistory.MAX_CALLS_PER_SESSION + 2) { n ->
      ToolCallHistory.end(ToolCallHistory.begin(sessionId, "call$n", JsonObject(emptyMap()), device = null), success = true, message = null)
    }

    val names = ToolCallHistory.toolCalls(sessionId).map { it.name }
    assertThat(names.size).isEqualTo(ToolCallHistory.MAX_CALLS_PER_SESSION)
    assertThat(names.first()).isEqualTo("call2")
    assertThat(names.last()).isEqualTo("call${ToolCallHistory.MAX_CALLS_PER_SESSION + 1}")
  }

  @Test
  fun `a session past its section cap drops its oldest closed run, with the sections inside it`() {
    val runs = ToolCallHistory.MAX_SECTIONS_PER_SESSION / 2 + 1
    repeat(runs) { n ->
      val run = ToolCallHistory.openSection(sessionId, HistorySectionKind.RUN, "run$n")
      ToolCallHistory.openSection(sessionId, HistorySectionKind.TRAIL, "trail$n")
      ToolCallHistory.closeSection(sessionId, run.id)
    }
    val open = ToolCallHistory.openSection(sessionId, HistorySectionKind.RUN, "running")

    val names = ToolCallHistory.sections(sessionId).map { it.name }
    assertThat(names.take(2)).containsExactly("run2", "trail2")
    assertThat(names.last()).isEqualTo(open.name)
    assertThat(names.size).isEqualTo(ToolCallHistory.MAX_SECTIONS_PER_SESSION - 1)
  }

  @Test
  fun `each tool a delegating tool expands to is recorded as a call it made`() {
    run(Delegate("signIn", children = listOf(Leaf("tapUser"), Leaf("tapSubmit"))))

    val calls = ToolCallHistory.toolCalls(sessionId)
    assertThat(calls.map { it.name }).containsExactly("signIn", "tapUser", "tapSubmit")
    val (delegate, first, second) = calls
    assertThat(listOf(first.parentId, second.parentId)).containsExactly(delegate.id, delegate.id)
    assertThat(second.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "tapSubmit done"))
  }

  @Test
  fun `a tool run outside dispatch, as the AI agent runs a scripted tool, is recorded with the calls it makes`() =
    runBlocking {
      val context = agent.contextForTest()
      val tool = Outer("aiPicked", inner = Leaf("inner"))

      ToolCallHistory.recordExecution(context, tool) { tool.execute(context) }

      val calls = ToolCallHistory.toolCalls(sessionId)
      assertThat(calls.map { it.name }).containsExactly("aiPicked", "inner")
      val (outer, inner) = calls
      assertThat(inner.parentId).isEqualTo(outer.id)
      assertThat(outer.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "aiPicked done"))
    }

  @Test
  fun `a trail's bare tool items land in its trail section`() {
    val util = TrailblazeRunnerUtil(
      runTrailblazeTool = { tools -> agent.runTrailblazeTools(tools, elementComparator = NoOpElementComparator).result },
      trailblazeRunner = NoAiRunner,
      sessionProvider = { session },
    )
    util.runTrailBlocking { util.runToolItem(listOf(Leaf("tap"))) }

    val sections = ToolCallHistory.sections(sessionId)
    assertThat(sections.map { it.kind }).containsExactly(HistorySectionKind.RUN, HistorySectionKind.TRAIL)
    val trail = sections.last()
    assertThat(ToolCallHistory.toolCalls(sessionId, trail.id).map { it.name }).containsExactly("tap")
  }

  @Test
  fun `a call that throws is recorded as failed`() {
    runCatching { run(Throwing("crash")) }

    val call = ToolCallHistory.toolCalls(sessionId).single()
    assertThat(call.name).isEqualTo("crash")
    assertThat(call.outcome!!.success).isEqualTo(false)
    assertThat(call.outcome!!.message!!).contains("boom")
  }

  @Test
  fun `a call logged without a dispatch is recorded once`() {
    val started = Clock.System.now()
    agent.logToolExecution(
      tool = Leaf("composeTap"),
      timeBeforeExecution = started,
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      result = TrailblazeToolResult.Success(message = "tapped"),
    )

    val call = ToolCallHistory.toolCalls(sessionId).single()
    assertThat(call.name).isEqualTo("composeTap")
    assertThat(call.outcome).isEqualTo(HistoryToolCallOutcome(success = true, message = "tapped"))
    assertThat(call.startedAt).isEqualTo(started.toEpochMilliseconds())
  }

  @Test
  fun `the runner splits a trail into back-to-back trailhead and trail sections`() {
    val util = runnerUtil()
    util.runTrailBlocking {
      util.runPrompt(
        listOf(TrailheadDefinition(step = "signed in", tools = listOf(wrap(Leaf("signIn")))).toPromptStep()),
        useRecordedSteps = true,
        selfHeal = false,
      )
      util.runPrompt(
        listOf(DirectionStep(step = "add two", recording = ToolRecording(listOf(wrap(Leaf("add")), wrap(Leaf("add")))))),
        useRecordedSteps = true,
        selfHeal = false,
      )
    }

    val (run, trailhead, trail) = ToolCallHistory.sections(sessionId)
    assertThat(ToolCallHistory.sections(sessionId).map { it.kind })
      .containsExactly(HistorySectionKind.RUN, HistorySectionKind.TRAILHEAD, HistorySectionKind.TRAIL)
    assertThat(listOf(trailhead.parentId, trail.parentId)).containsExactly(run.id, run.id)
    assertThat(trailhead.name).isEqualTo("signIn")
    assertThat(trail.startedAt).isEqualTo(trailhead.endedAt)
    assertThat(trail.endedAt).isNotNull()
    assertThat(ToolCallHistory.toolCalls(sessionId, trailhead.id).map { it.name }).containsExactly("signIn")
    assertThat(ToolCallHistory.toolCalls(sessionId, trail.id).map { it.name }).containsExactly("add", "add")
  }

  @Test
  fun `trailheads for two devices run one call at a time share one setup section before one trail section`() {
    val util = runnerUtil()
    util.runTrailBlocking {
      for (device in listOf("phone", "tablet")) {
        util.runPrompt(
          listOf(TrailheadDefinition(step = "$device signed in", tools = listOf(wrap(Leaf("signIn_$device")))).toPromptStep()),
          useRecordedSteps = true,
          selfHeal = false,
        )
      }
      util.runPrompt(
        listOf(DirectionStep(step = "add", recording = ToolRecording(listOf(wrap(Leaf("add")))))),
        useRecordedSteps = true,
        selfHeal = false,
      )
    }

    val sections = ToolCallHistory.sections(sessionId)
    assertThat(sections.map { it.kind })
      .containsExactly(HistorySectionKind.RUN, HistorySectionKind.TRAILHEAD, HistorySectionKind.TRAIL)
    val (_, trailhead, trail) = sections
    assertThat(ToolCallHistory.toolCalls(sessionId, trailhead.id).map { it.name }).containsExactly("signIn_phone", "signIn_tablet")
    assertThat(ToolCallHistory.toolCalls(sessionId, trail.id).map { it.name }).containsExactly("add")
    assertThat(trail.startedAt).isEqualTo(trailhead.endedAt)
  }

  @Test
  fun `a script reads the latest run's trail, so a run whose setup failed has none`() = runBlocking {
    val util = runnerUtil()
    util.runTrailBlocking {
      util.runPrompt(
        listOf(TrailheadDefinition(step = "signed in", tools = listOf(wrap(Leaf("signIn")))).toPromptStep()),
        useRecordedSteps = true,
        selfHeal = false,
      )
      util.runToolItem(listOf(Leaf("addFromFirstRun")))
    }
    val firstRun = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"trail"}""") as Outcome.Success
    runCatching {
      util.runTrailBlocking {
        util.runPrompt(
          listOf(TrailheadDefinition(step = "signed in", tools = listOf(wrap(Throwing("signIn")))).toPromptStep()),
          useRecordedSteps = true,
          selfHeal = false,
        )
      }
    }
    val secondRun = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"trail"}""") as Outcome.Success
    val secondSetup = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"trailhead"}""") as Outcome.Success

    assertThat(names(firstRun.value)).containsExactly("addFromFirstRun")
    assertThat(names(secondRun.value)).isEmpty()
    assertThat(names(secondSetup.value)).containsExactly("signIn")
  }

  @Test
  fun `a section listener hears each section start and end`() {
    val heard = mutableListOf<String>()
    val listener = object : HistorySectionListener {
      override fun onSectionStart(sessionId: SessionId, section: HistorySection) {
        if (sessionId == this@ToolCallHistoryTest.sessionId) heard += "start ${section.kind} ended=${section.endedAt != null}"
      }

      override fun onSectionEnd(sessionId: SessionId, section: HistorySection) {
        if (sessionId == this@ToolCallHistoryTest.sessionId) heard += "end ${section.kind} ended=${section.endedAt != null}"
      }
    }
    ToolCallHistory.addSectionListener(listener)
    try {
      val outer = ToolCallHistory.openSection(sessionId, HistorySectionKind.TRAIL, "trail", startedAt = 1)
      ToolCallHistory.openSection(sessionId, "loop", "iteration 1", startedAt = 2)
      // Closing the outer section closes the one inside it first.
      ToolCallHistory.closeSection(sessionId, outer.id, endedAt = 3)
    } finally {
      ToolCallHistory.removeSectionListener(listener)
    }

    assertThat(heard).containsExactly(
      "start trail ended=false",
      "start loop ended=false",
      "end loop ended=true",
      "end trail ended=true",
    )
  }

  @Test
  fun `a script reads a part of the run by name, including the sections inside it`() = runBlocking {
    val trailhead = ToolCallHistory.openSection(sessionId, HistorySectionKind.TRAILHEAD, "setup", startedAt = 1)
    run(Leaf("signIn"))
    ToolCallHistory.closeSection(sessionId, trailhead.id)
    val trail = ToolCallHistory.openSection(sessionId, HistorySectionKind.TRAIL, "trail", startedAt = 2)
    run(Leaf("first"))
    ToolCallHistory.openSection(sessionId, "loop", "iteration 1", startedAt = 3)
    run(Leaf("second"))
    ToolCallHistory.closeSection(sessionId, trail.id)
    run(Leaf("afterwards"))

    val all = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, "{}") as Outcome.Success
    val setup = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"trailhead"}""") as Outcome.Success
    val scoped = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"trail"}""") as Outcome.Success
    val notYet = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"teardown"}""") as Outcome.Success

    assertThat(names(all.value)).containsExactly("signIn", "first", "second", "afterwards")
    assertThat(names(setup.value)).containsExactly("signIn")
    assertThat(names(scoped.value)).containsExactly("first", "second")
    // A part of the run that hasn't started has no calls yet; that is not an error.
    assertThat(names(notYet.value)).isEmpty()
    // Every field is present, nulls included, so a script never reads `undefined`.
    assertThat((scoped.value as JsonArray).first().jsonObject["parentId"]).isEqualTo(JsonNull)
  }

  @Test
  fun `a script naming a section that is not a part of the run, or an unknown argument, is told so`() = runBlocking {
    val unknownSection = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":"s1"}""")
    val unknownArg = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"since":1}""")
    val notAString = hostCall(ToolCallHistoryHostFunctions.TOOL_CALLS, """{"section":3}""")

    assertThat((unknownSection as Outcome.Failure).message)
      .contains("""section must be one of "trailhead", "trail", "teardown", not "s1"""")
    assertThat((unknownArg as Outcome.Failure).message).contains("'since'")
    assertThat((notAString as Outcome.Failure).message).contains("section must be one of")
  }

  private fun runnerUtil() = TrailblazeRunnerUtil(
    runTrailblazeTool = { tools -> agent.runTrailblazeTools(tools, elementComparator = NoOpElementComparator).result },
    trailblazeRunner = NoAiRunner,
    sessionProvider = { session },
  )

  private fun names(value: kotlinx.serialization.json.JsonElement) =
    (value as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private suspend fun hostCall(name: String, argsJson: String) =
    TrailblazeHostFunctionDispatcher.call(name, argsJson, agent.contextForTest())

  private fun run(tool: TrailblazeTool) {
    agent.runTrailblazeTools(listOf(tool), elementComparator = NoOpElementComparator)
  }

  private fun wrap(tool: HostLocalExecutableTrailblazeTool) =
    TrailblazeToolYamlWrapper(name = tool.advertisedToolName, trailblazeTool = tool)

  private class Leaf(
    override val advertisedToolName: String,
    private val result: TrailblazeToolResult = TrailblazeToolResult.Success(message = "$advertisedToolName done"),
  ) : HostLocalExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) = result
  }

  /** Runs [inner] the way a scripted tool's `ctx.tools.X()` does, then succeeds. */
  private class Outer(override val advertisedToolName: String, private val inner: TrailblazeTool) :
    HostLocalExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      toolExecutionContext.nestedToolExecutor!!.invoke(inner)
      return TrailblazeToolResult.Success(message = "$advertisedToolName done")
    }
  }

  private class WithArgs(override val advertisedToolName: String, override val rawToolArguments: JsonObject) :
    HostLocalExecutableTrailblazeTool, RawArgumentTrailblazeTool {
    override val instanceToolName: String get() = advertisedToolName
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) = TrailblazeToolResult.Success()
  }

  /** Declares its secrets the way a shell tool does: by value, possibly as a memory token. */
  @Serializable
  private data class Shell(val command: List<String>, val secrets: List<String>) :
    HostLocalExecutableTrailblazeTool, SensitiveValuesTrailblazeTool {
    override val advertisedToolName: String get() = "shell"
    override val sensitiveValues: Collection<String> get() = secrets
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) =
      TrailblazeToolResult.Success(message = "ran ${command.joinToString(" ")}")
  }

  /** Declares [secret] by value and answers with [message], which may hold it JSON-escaped. */
  private class JsonWithSecret(
    override val advertisedToolName: String,
    private val secret: String,
    private val message: String,
  ) : HostLocalExecutableTrailblazeTool, SensitiveValuesTrailblazeTool {
    override val sensitiveValues: Collection<String> get() = listOf(secret)
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) =
      TrailblazeToolResult.Success(message = message)
  }

  /** Answers with a credential, the way a session-resolving tool does. */
  private class Credential(
    override val advertisedToolName: String,
    private val result: TrailblazeToolResult = TrailblazeToolResult.Success(message = "tok-123"),
  ) : HostLocalExecutableTrailblazeTool, SecretResultTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) = result
  }

  private class Throwing(override val advertisedToolName: String) : HostLocalExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      throw IllegalStateException("boom")
  }

  /** Replaces the `password` secret while it runs, then fails with the old value in its message. */
  private class RotatesSecret(override val advertisedToolName: String, private val thenThrow: Boolean) :
    HostLocalExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      toolExecutionContext.memory.rememberSensitive("password", "rotated")
      if (thenThrow) throw IllegalStateException("rejected hunter2")
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "rejected hunter2")
    }
  }

  private class Delegate(override val instanceToolName: String, private val children: List<ExecutableTrailblazeTool>) :
    DelegatingTrailblazeTool, RawArgumentTrailblazeTool {
    override val rawToolArguments = JsonObject(emptyMap())
    override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext) = children
  }

  /** The production dispatch loop, with the nested executor wired the way every real agent wires it. */
  private inner class Agent : BaseTrailblazeAgent() {
    override val trailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId = "test", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider { session }

    fun contextForTest() = buildExecutionContext(TraceId.generate(TraceId.Companion.TraceOrigin.TOOL), null, null)

    override fun buildExecutionContext(
      traceId: TraceId,
      screenState: ScreenState?,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeToolExecutionContext {
      lateinit var context: TrailblazeToolExecutionContext
      context = TrailblazeToolExecutionContext(
        screenState = null,
        traceId = traceId,
        trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
        sessionProvider = sessionProvider,
        trailblazeLogger = trailblazeLogger,
        memory = memory,
        nestedToolExecutor = nestedToolExecutorFor { context },
      )
      return context
    }

    override fun executeTool(
      tool: TrailblazeTool,
      context: TrailblazeToolExecutionContext,
      toolsExecuted: MutableList<TrailblazeTool>,
    ): TrailblazeToolResult = when (tool) {
      is DelegatingTrailblazeTool ->
        executeDelegatingTool(tool, context, toolsExecuted) { runBlocking { it.execute(context) } }
      else -> error("every other tool here is host-local: $tool")
    }
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
