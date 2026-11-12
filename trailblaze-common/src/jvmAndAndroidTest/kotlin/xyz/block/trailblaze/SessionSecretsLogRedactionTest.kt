package xyz.block.trailblaze

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.yaml.DirectionStep

/**
 * A credential a sign-in tool is handed must not reach any session log, including the logs of the
 * tools it hands the credential on to. Runs the real dispatch loop and the real logger, and reads
 * every log as it is written (JSON), so no field or log type is assumed safe.
 */
class SessionSecretsLogRedactionTest {

  private val password = "Staging-Pw-0451!"

  @Test
  fun `a declared password is masked in the logs of the tools it is typed with`() {
    val agent = CapturingAgent()

    agent.run(DeclaredSignIn(email = "owner@example.com", password = password))

    assertNoLogContains(agent, password)
    // The password still reached the screen, and the typing step was still logged.
    assertEquals(listOf("owner@example.com", password), agent.typed)
    assertEquals(
      JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER),
      agent.toolLog("fixture_inputText").trailblazeTool.raw["text"],
    )
    assertEquals(JsonPrimitive("owner@example.com"), agent.toolLog("fixture_declaredSignIn").trailblazeTool.raw["email"])
  }

  @Test
  fun `an undeclared password arg is masked by its name, and so where it is typed`() {
    // The shape of a sign-in step that forgot `sensitiveArgNames`.
    val agent = CapturingAgent()

    agent.run(UndeclaredSignIn(email = "owner@example.com", password = password))

    assertNoLogContains(agent, password)
    assertEquals(listOf(password), agent.typed)
    assertEquals(
      JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER),
      agent.toolLog("fixture_undeclaredSignIn").trailblazeTool.raw["password"],
    )
  }

  @Test
  fun `a session that handled no secret logs typed text unchanged`() {
    val agent = CapturingAgent()

    agent.run(TypeText("Staging-Pw-0451!"))

    assertEquals(JsonPrimitive("Staging-Pw-0451!"), agent.toolLog("fixture_inputText").trailblazeTool.raw["text"])
  }

  @Test
  fun `a failed session's end log doesn't repeat the password its error carries`() {
    val agent = CapturingAgent()
    agent.run(DeclaredSignIn(email = "owner@example.com", password = password))
    val session = agent.sessionProvider.invoke()

    TrailblazeSessionManager(LogEmitter { agent.emitted += it })
      .endSession(session, isSuccess = false, exception = IllegalStateException("sign-in rejected '$password'"))

    assertTrue(agent.emitted.last() is TrailblazeLog.TrailblazeSessionStatusChangeLog)
    assertNoLogContains(agent, password)
  }

  @Test
  fun `a step's prompt stays as authored, so its start and completion still pair`() {
    val agent = CapturingAgent()
    agent.run(DeclaredSignIn(email = "owner@example.com", password = password))
    val sessionId = agent.sessionProvider.invoke().sessionId
    val step = DirectionStep(step = "Sign in with $password")

    val logged = agent.trailblazeLogger.let { logger ->
      logger.log(
        agent.sessionProvider.invoke(),
        TrailblazeLog.ObjectiveCompleteLog(
          promptStep = step,
          objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
            statusData = AgentTaskStatusData(
              taskId = TaskId.generate(),
              prompt = step.prompt,
              callCount = 1,
              taskStartTime = Clock.System.now(),
              totalDurationMs = 1,
            ),
            llmExplanation = "Signed in with $password",
          ),
          session = sessionId,
          timestamp = Clock.System.now(),
        ),
      )
      agent.emitted.last() as TrailblazeLog.ObjectiveCompleteLog
    }

    assertEquals(step.prompt, logged.promptStep.prompt)
    assertTrue(password !in (logged.objectiveResult as AgentTaskStatus.Success.ObjectiveComplete).llmExplanation)
  }

  @Test
  fun `a real password that merely ends in the word is still masked where it is typed`() {
    val agent = CapturingAgent()

    agent.run(DeclaredSignIn(email = "owner@example.com", password = "tenant-unique-password"))

    assertNoLogContains(agent, "tenant-unique-password")
  }

  @Test
  fun `the shared placeholder password is not masked session-wide`() {
    val agent = CapturingAgent()

    agent.run(DeclaredSignIn(email = "owner@example.com", password = "password"))

    assertEquals(
      JsonPrimitive("password"),
      agent.toolLog("fixture_inputText").trailblazeTool.raw["text"],
    )
  }

  private fun assertNoLogContains(agent: CapturingAgent, secret: String) {
    assertTrue(agent.emitted.isNotEmpty())
    agent.emitted.forEach { log ->
      val written = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
      assertTrue(!written.contains(secret), "a ${log::class.simpleName} carried the secret: $written")
    }
  }

  // ── Fixture tools ──

  @Serializable
  @TrailblazeToolClass("fixture_inputText")
  private class TypeText(val text: String) : ExecutableTrailblazeTool {
    @Transient var onType: (String) -> Unit = {}

    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      onType(text)
      return TrailblazeToolResult.Success()
    }
  }

  /** Types its password through a nested dispatch, as a scripted sign-in does with `inputText`. */
  @Serializable
  @TrailblazeToolClass("fixture_declaredSignIn")
  private class DeclaredSignIn(val email: String, val password: String) :
    ExecutableTrailblazeTool, SensitiveArgsTrailblazeTool {
    override val sensitiveArgNames: Set<String> get() = setOf("password")

    @Transient var dispatch: (TrailblazeTool) -> Unit = {}

    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      dispatch(TypeText(email))
      dispatch(TypeText(password))
      return TrailblazeToolResult.Success()
    }
  }

  @Serializable
  @TrailblazeToolClass("fixture_undeclaredSignIn")
  private class UndeclaredSignIn(val email: String, val password: String) : ExecutableTrailblazeTool {
    @Transient var dispatch: (TrailblazeTool) -> Unit = {}

    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
      dispatch(TypeText(password))
      return TrailblazeToolResult.Success()
    }
  }

  // ── Agent: the real dispatch loop, logging each call as a driver does ──

  private class CapturingAgent : BaseTrailblazeAgent() {
    val emitted = mutableListOf<TrailblazeLog>()
    val typed = mutableListOf<String>()

    override val trailblazeLogger = TrailblazeLogger(
      logEmitter = LogEmitter { emitted += it },
      screenStateLogger = ScreenStateLogger { "" },
    )
    override val trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }

    // A session of its own per test: the registry is process-wide.
    private val session = TrailblazeSession(sessionId = SessionId("secrets-${UUID.randomUUID()}"), startTime = Clock.System.now())
    override val sessionProvider = TrailblazeSessionProvider { session }

    fun run(tool: TrailblazeTool) {
      runTrailblazeTools(tools = listOf(tool), elementComparator = NoOpElementComparator)
    }

    fun toolLog(name: String): TrailblazeLog.TrailblazeToolLog =
      emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().last { it.toolName == name }

    override fun buildExecutionContext(
      traceId: TraceId,
      screenState: ScreenState?,
      screenStateProvider: (() -> ScreenState)?,
    ) = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
      sessionProvider = sessionProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
    )

    override fun executeTool(
      tool: TrailblazeTool,
      context: TrailblazeToolExecutionContext,
      toolsExecuted: MutableList<TrailblazeTool>,
    ): TrailblazeToolResult {
      val nested: (TrailblazeTool) -> Unit = { run(it) }
      when (tool) {
        is TypeText -> tool.onType = { typed += it }
        is DeclaredSignIn -> tool.dispatch = nested
        is UndeclaredSignIn -> tool.dispatch = nested
      }
      toolsExecuted += tool
      val start = Clock.System.now()
      val result = runBlocking { (tool as ExecutableTrailblazeTool).execute(context) }
      logToolExecution(tool as ExecutableTrailblazeTool, start, context, result)
      return result
    }
  }
}
