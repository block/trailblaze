package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcTrailblazeAgent
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.utils.ElementComparator
import java.net.ServerSocket
import kotlin.test.Test

/**
 * Integration tests for [ComposeRpcTrailblazeAgent] driving a real [ComposeRpcServer] over HTTP.
 *
 * Where [ComposeRpcServerTest] exercises the raw client/server round-trip, these tests cover the
 * *agent's* dispatch: [ComposeRpcTrailblazeAgent] is now a [BaseTrailblazeAgent], so its tool
 * execution flows through the shared base loop (`runTrailblazeTools` → `executeTool`) — the exact
 * seam the `KOOG_STRATEGY_GRAPH` runner drives. The tests pin:
 *  - the agent is a [BaseTrailblazeAgent] (so `KoogTestAgentRunner` can take it), and exposes the
 *    `buildKoogToolExecutionContext` seam the Koog registry needs,
 *  - single- and multi-tool batches dispatch over RPC and succeed,
 *  - exactly ONE post-batch screenshot ([TrailblazeLog.AgentDriverLog]) is logged per
 *    `runTrailblazeTools` call (the pre-migration behavior — preserved for the default
 *    TRAILBLAZE_RUNNER path's recorded `tools:` blocks),
 *  - a failing tool stops the batch and only the executed tool is reported.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeRpcTrailblazeAgentTest {

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "compose-rpc-agent-test",
      // DESKTOP is the Compose-desktop platform (hidden=true in the enum); use it rather than WEB
      // so the test's device identity matches the driver under test.
      trailblazeDevicePlatform = TrailblazeDevicePlatform.DESKTOP,
    ),
    trailblazeDriverType = TrailblazeDriverType.COMPOSE,
    widthPixels = 1280,
    heightPixels = 800,
  )

  private val session = TrailblazeSession(
    sessionId = SessionId("compose-rpc-agent-test-session"),
    startTime = Clock.System.now(),
  )

  /** No-op element comparator — these tests don't drive any memory/assertion-by-LLM tools. */
  private val stubElementComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)
    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  /** Logger that records every emitted [TrailblazeLog] so tests can count screenshot/tool logs. */
  private fun capturingLogger(captured: MutableList<TrailblazeLog>) = TrailblazeLogger(
    logEmitter = LogEmitter { captured.add(it) },
    screenStateLogger = ScreenStateLogger { "screenshot.png" },
  )

  private fun agentFor(
    client: ComposeRpcClient,
    captured: MutableList<TrailblazeLog>,
  ) = ComposeRpcTrailblazeAgent(
    rpcClient = client,
    trailblazeLogger = capturingLogger(captured),
    sessionProvider = TrailblazeSessionProvider { session },
    trailblazeDeviceInfoProvider = { deviceInfo },
  )

  /** Runs [block] against a started server + connected client + agent, then tears everything down. */
  private fun withAgent(
    captured: MutableList<TrailblazeLog>,
    block: (ComposeRpcTrailblazeAgent) -> Unit,
  ) = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    val agent = agentFor(client, captured)
    try {
      runBlocking { client.waitForServer() }
      block(agent)
    } finally {
      agent.close()
      server.stop()
    }
  }

  @Test
  fun `the RPC agent is a BaseTrailblazeAgent and exposes a working Koog tool-execution context seam`() {
    val captured = mutableListOf<TrailblazeLog>()
    withAgent(captured) { agent ->
      // The migration's whole point: KoogTestAgentRunner only accepts a BaseTrailblazeAgent.
      assertThat(agent).isInstanceOf(BaseTrailblazeAgent::class)
      // The Koog registry builds dynamic-tool contexts through this seam. Don't just null-check the
      // provider — INVOKE it and confirm it fetches a real ScreenState over RPC, so the test proves
      // the seam works end-to-end rather than merely existing.
      val context = agent.buildKoogToolExecutionContext(
        traceId = null,
        screenStateProvider = agent.screenStateProvider,
      )
      val provider = context.screenStateProvider
      assertThat(provider).isNotNull()
      val screenState = provider!!.invoke()
      assertThat(screenState.deviceWidth).isGreaterThan(0)
      assertThat(screenState.deviceHeight).isGreaterThan(0)
    }
  }

  @Test
  fun `compose_request_details is applied client-side, succeeds, and is logged`() {
    val captured = mutableListOf<TrailblazeLog>()
    withAgent(captured) { agent ->
      // compose_request_details never goes over RPC — it arms the next getScreenState's detail
      // level client-side. Exercise that branch of executeTool.
      val detailsTool = ComposeRequestDetailsTool(include = listOf(ComposeViewHierarchyDetail.BOUNDS))
      val result = agent.runTrailblazeTools(
        tools = listOf(detailsTool),
        traceId = null,
        screenState = null,
        elementComparator = stubElementComparator,
        screenStateProvider = agent.screenStateProvider,
      )
      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat((result.result as TrailblazeToolResult.Success).message ?: "").contains("BOUNDS")
      assertThat(result.executedTools).containsExactly(detailsTool)
      // The client-side dispatch still emits a TrailblazeToolLog like every other tool.
      assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).hasSize(1)
    }
  }

  @Test
  fun `single tool dispatches over RPC and logs one screenshot`() {
    val captured = mutableListOf<TrailblazeLog>()
    withAgent(captured) { agent ->
      val result = agent.runTrailblazeTools(
        tools = listOf(ComposeTypeTool(text = "Buy milk", testTag = SampleTodoApp.TAG_TODO_INPUT)),
        traceId = null,
        screenState = null,
        elementComparator = stubElementComparator,
        screenStateProvider = agent.screenStateProvider,
      )
      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)

      // Exactly one post-batch AgentDriverLog screenshot for the batch.
      assertThat(captured.filterIsInstance<TrailblazeLog.AgentDriverLog>()).hasSize(1)
    }
  }

  @Test
  fun `multi-tool batch logs exactly one screenshot but one tool log per tool`() {
    val captured = mutableListOf<TrailblazeLog>()
    withAgent(captured) { agent ->
      val result = agent.runTrailblazeTools(
        tools = listOf(
          ComposeTypeTool(text = "Batch item", testTag = SampleTodoApp.TAG_TODO_INPUT),
          ComposeClickTool(testTag = SampleTodoApp.TAG_ADD_BUTTON),
        ),
        traceId = null,
        screenState = null,
        elementComparator = stubElementComparator,
        screenStateProvider = agent.screenStateProvider,
      )
      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(result.executedTools).hasSize(2)

      // One screenshot for the whole batch (NOT one per tool) — this is the behavior the
      // default TRAILBLAZE_RUNNER path's recorded `tools:` blocks rely on.
      assertThat(captured.filterIsInstance<TrailblazeLog.AgentDriverLog>()).hasSize(1)
      // ...but each dispatched tool still produces its own TrailblazeToolLog.
      assertThat(captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()).hasSize(2)
    }
  }

  @Test
  fun `a failing tool stops the batch and only the executed tool is reported`() {
    val captured = mutableListOf<TrailblazeLog>()
    withAgent(captured) { agent ->
      val failing = ComposeVerifyTextVisibleTool(text = "this text is not on screen")
      val neverRuns = ComposeClickTool(testTag = SampleTodoApp.TAG_ADD_BUTTON)
      val result = agent.runTrailblazeTools(
        tools = listOf(failing, neverRuns),
        traceId = null,
        screenState = null,
        elementComparator = stubElementComparator,
        screenStateProvider = agent.screenStateProvider,
      )

      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
      // Base loop early-exits on the first failure — the click after it never executes.
      assertThat(result.executedTools).containsExactly(failing)
      // The post-batch screenshot still fires once (best-effort; report shows the failed state).
      assertThat(captured.filterIsInstance<TrailblazeLog.AgentDriverLog>()).hasSize(1)
    }
  }
}
