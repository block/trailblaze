package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers the Maestro-style [TapOnTrailblazeTool] — `relativePoint` parser, center-tap default,
 * and end-to-end selector resolution dispatching a Maestro TapOnPointV2 at the computed
 * bounds-relative coordinate. Because [TrailblazeNodeSelector] spans all five driver
 * variants, the same mechanic Just Works across AndroidAccessibility / AndroidMaestro /
 * IosMaestro / Web / Compose.
 */
class TapOnTrailblazeToolTest {

  // region parseRelativePoint

  @Test
  fun `parseRelativePoint accepts 90%,50% style`() {
    assertEquals(90.0 to 50.0, TapOnTrailblazeTool.parseRelativePoint("90%,50%"))
  }

  @Test
  fun `parseRelativePoint tolerates whitespace`() {
    assertEquals(25.0 to 75.0, TapOnTrailblazeTool.parseRelativePoint(" 25% , 75% "))
  }

  @Test
  fun `parseRelativePoint accepts raw numbers without percent sign`() {
    assertEquals(50.0 to 50.0, TapOnTrailblazeTool.parseRelativePoint("50,50"))
  }

  @Test
  fun `parseRelativePoint clamps out-of-range values`() {
    assertEquals(100.0 to 0.0, TapOnTrailblazeTool.parseRelativePoint("150%,-10%"))
  }

  @Test
  fun `parseRelativePoint returns null for malformed input`() {
    assertNull(TapOnTrailblazeTool.parseRelativePoint("not,a,point"))
    assertNull(TapOnTrailblazeTool.parseRelativePoint("90%"))
    assertNull(TapOnTrailblazeTool.parseRelativePoint("a%,b%"))
  }

  // endregion

  // region execute

  @Test
  fun `selector plus relativePoint dispatches tap at bounds-relative coord`(): Unit = runBlocking {
    // Target element at (100, 200)-(300, 260): width 200, height 60.
    // relativePoint "90%,50%" → (100 + 180, 200 + 30) = (280, 230).
    val target = node(
      DriverNodeDetail.AndroidAccessibility(text = "A text with a hyperlink"),
      TrailblazeNode.Bounds(100, 200, 300, 260),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    val tool = TapOnTrailblazeTool(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = ".*hyperlink.*"),
      ),
      relativePoint = "90%,50%",
    )
    val result = tool.execute(context)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(280 to 230, agent.lastTapPoint)
  }

  @Test
  fun `omitting relativePoint taps element center`(): Unit = runBlocking {
    val target = node(
      DriverNodeDetail.AndroidAccessibility(text = "Submit"),
      TrailblazeNode.Bounds(100, 200, 300, 260),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    TapOnTrailblazeTool(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
      ),
    ).execute(context)

    assertEquals(200 to 230, agent.lastTapPoint)
  }

  @Test
  fun `works across driver variants - Compose`(): Unit = runBlocking {
    val target = node(
      DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"),
      TrailblazeNode.Bounds(0, 0, 200, 100),
    )
    val root = node(
      DriverNodeDetail.Compose(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    TapOnTrailblazeTool(
      selector = TrailblazeNodeSelector(
        compose = DriverNodeMatch.Compose(testTag = "submit_btn"),
      ),
      relativePoint = "25%,25%",
    ).execute(context)

    assertEquals(50 to 25, agent.lastTapPoint)
  }

  @Test
  fun `works across driver variants - iOS Maestro`(): Unit = runBlocking {
    val target = node(
      DriverNodeDetail.IosMaestro(text = "Buy Now"),
      TrailblazeNode.Bounds(0, 500, 400, 600),
    )
    val root = node(
      DriverNodeDetail.IosMaestro(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    TapOnTrailblazeTool(
      selector = TrailblazeNodeSelector(
        iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Buy Now"),
      ),
    ).execute(context)

    assertEquals(200 to 550, agent.lastTapPoint)
  }

  @Test
  fun `no matching element throws`(): Unit = runBlocking {
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(
        node(
          DriverNodeDetail.AndroidAccessibility(text = "Buy"),
          TrailblazeNode.Bounds(0, 0, 100, 50),
        ),
      ),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Missing"),
        ),
        relativePoint = "50%,50%",
      ).execute(context)
    }
  }

  @Test
  fun `ambiguous selector throws with a helpful message`(): Unit = runBlocking {
    val first = node(
      DriverNodeDetail.AndroidAccessibility(text = "Edit"),
      TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val second = node(
      DriverNodeDetail.AndroidAccessibility(text = "Edit"),
      TrailblazeNode.Bounds(200, 200, 300, 250),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(first, second),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Edit"),
        ),
        relativePoint = "50%,50%",
      ).execute(context)
    }
    assertEquals(true, (ex.message ?: "").contains("ambiguous"))
  }

  @Test
  fun `bad relativePoint throws`(): Unit = runBlocking {
    val target = node(
      DriverNodeDetail.AndroidAccessibility(text = "Hello"),
      TrailblazeNode.Bounds(0, 0, 100, 100),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Hello"),
        ),
        relativePoint = "not-a-point",
      ).execute(context)
    }
  }

  @Test
  fun `null screenState throws with a helpful message`(): Unit = runBlocking {
    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "t",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1000,
        heightPixels = 1000,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = CapturingAgent(),
    )
    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "X"),
        ),
      ).execute(context)
    }
    assertEquals(true, (ex.message ?: "").contains("screen state"))
  }

  @Test
  fun `null trailblazeNodeTree throws with a pointer to the Maestro fallback tool`(): Unit = runBlocking {
    // A Maestro-only driver produces a null trailblazeNodeTree. The error message must
    // direct the author to TapOnByElementSelector so they have an actionable next step.
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = null)
    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "X"),
        ),
      ).execute(context)
    }
    assertEquals(true, (ex.message ?: "").contains("TapOnByElementSelector"))
  }

  @Test
  fun `null bounds on resolved element throws`(): Unit = runBlocking {
    // Selector matches a node that (unusually) has no bounds — tapOn has nothing to aim at.
    val boundless = TrailblazeNode(
      nodeId = 1,
      bounds = null,
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Ghost"),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(boundless),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Ghost"),
        ),
      ).execute(context)
    }
    assertEquals(true, (ex.message ?: "").contains("no bounds"))
  }

  @Test
  fun `null maestro agent throws`(): Unit = runBlocking {
    // Running in a context without a MaestroTrailblazeAgent — the tool has no way to dispatch.
    val target = node(
      DriverNodeDetail.AndroidAccessibility(text = "Submit"),
      TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode? = root
    }
    val context = TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "t",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1000,
        heightPixels = 1000,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = null,
    )
    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      TapOnTrailblazeTool(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
        ),
      ).execute(context)
    }
    assertEquals(true, (ex.message ?: "").contains("MaestroTrailblazeAgent"))
  }

  @Test
  fun `index disambiguates duplicates via selector`(): Unit = runBlocking {
    val first = node(
      DriverNodeDetail.AndroidAccessibility(text = "Edit"),
      TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val second = node(
      DriverNodeDetail.AndroidAccessibility(text = "Edit"),
      TrailblazeNode.Bounds(200, 200, 400, 260),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(first, second),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    TapOnTrailblazeTool(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Edit"),
        index = 1,
      ),
      relativePoint = "50%,50%",
    ).execute(context)

    assertEquals(300 to 230, agent.lastTapPoint)
  }

  // endregion

  // region accessibility-driver branch (TapTrailblazeTool.toExecutableTrailblazeTools)

  @Test
  fun `accessibility-driver path emits single-shape nodeSelector-only recording`() {
    // When the tree's driverDetail is AndroidAccessibility, the tool should produce a
    // single TapOnByElementSelector whose `nodeSelector.androidAccessibility` is populated
    // — the recording must NOT carry a Maestro-shaped fallback selector for that driver.
    val target = TrailblazeNode(
      nodeId = nextId++,
      ref = "p1",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        text = "Continue",
        className = "android.widget.Button",
        isClickable = true,
      ),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)

    val executable = TapTrailblazeTool(ref = "p1", longPress = true)
      .toExecutableTrailblazeTools(context)

    assertEquals(1, executable.size)
    val tap = assertIs<TapOnByElementSelector>(executable[0])
    assertEquals(true, tap.nodeSelector?.androidAccessibility != null)
    assertEquals(true, tap.longPress)
  }

  @Test
  fun `accessibility-driver path with longPress=false propagates`() {
    val target = TrailblazeNode(
      nodeId = nextId++,
      ref = "p2",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val context = contextWithTree(CapturingAgent(), tree = root)

    val tap = assertIs<TapOnByElementSelector>(
      TapTrailblazeTool(ref = "p2", longPress = false).toExecutableTrailblazeTools(context).single(),
    )
    assertEquals(false, tap.longPress)
    assertEquals(true, tap.nodeSelector?.androidAccessibility != null)
  }

  // endregion

  // region TapOnByElementSelector accessibility error paths

  @Test
  fun `TapOnByElementSelector accessibility selector with null agent returns ExceptionThrown`() = runBlocking {
    val tap = TapOnByElementSelector(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "x"),
      longPress = false,
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Continue"),
      ),
    )
    val agentForCtx = CapturingAgent()
    val ctxWithAgent = contextWithTree(agentForCtx, tree = null)
    // Re-build context with maestroTrailblazeAgent = null while reusing the rest.
    val context = TrailblazeToolExecutionContext(
      screenState = ctxWithAgent.screenState,
      traceId = null,
      trailblazeDeviceInfo = ctxWithAgent.trailblazeDeviceInfo,
      sessionProvider = ctxWithAgent.sessionProvider,
      trailblazeLogger = ctxWithAgent.trailblazeLogger,
      memory = AgentMemory(),
      maestroTrailblazeAgent = null,
    )

    val result = tap.execute(context)
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertEquals(true, error.errorMessage.contains("AccessibilityTrailblazeAgent"))
  }

  @Test
  fun `TapOnByElementSelector accessibility selector with null dispatch returns ExceptionThrown`() = runBlocking {
    // AccessibilityCapableCapturingAgent advertises [usesAccessibilityDriver] but doesn't
    // override [executeNodeSelectorTap], so the base class returns null — the "agent ran
    // but didn't resolve" branch that the tool refuses to fall back from under an
    // accessibility-capable agent.
    val tap = TapOnByElementSelector(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "x"),
      longPress = false,
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Continue"),
      ),
    )
    val context = contextWithTree(AccessibilityCapableCapturingAgent(), tree = null)

    val result = tap.execute(context)
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertEquals(true, error.errorMessage.contains("Refusing Maestro fallback"))
  }

  @Test
  fun `TapOnByElementSelector accessibility selector with non-accessibility agent falls back to Maestro`(): Unit = runBlocking {
    // Recording carries both the legacy Maestro [selector] and an [androidAccessibility]
    // nodeSelector (cross-driver-portable). Under a non-accessibility runtime agent
    // (e.g. AndroidMaestroTrailblazeAgent on the on-device test farm), the strict
    // accessibility refusal must NOT fire — the tool falls through to the Maestro path
    // so legacy/cross-driver recordings stay runnable. CapturingAgent.executeMaestroCommands
    // captures the resulting tap and returns Success.
    val tap = TapOnByElementSelector(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "Continue"),
      longPress = false,
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Continue"),
      ),
    )
    val context = contextWithTree(CapturingAgent(), tree = null)

    val result = tap.execute(context)
    assertIs<TrailblazeToolResult.Success>(result)
  }

  @Test
  fun `TapOnByElementSelector with FORCE_NODE_SELECTOR mode dispatches via agent before fallback`(): Unit = runBlocking {
    // FORCE_NODE_SELECTOR (non-accessibility-shaped recording, e.g. Maestro selector with no
    // nodeSelector field). The tool must (a) try agent.executeNodeSelectorTap with the
    // synthesized nodeSelector first, and (b) fall back to super.execute (Maestro path)
    // when the agent returns null. Pre-merge no test exercised this branch.
    val tap = TapOnByElementSelector(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "Continue"),
      longPress = false,
      // nodeSelector deliberately null — `selector.toTrailblazeNodeSelector` will synthesize one
      // based on the runtime device platform inside the FORCE_NODE_SELECTOR branch.
      nodeSelector = null,
    )
    val agent = CapturingAgent()
    val baseContext = contextWithTree(agent, tree = null)
    val context = TrailblazeToolExecutionContext(
      screenState = baseContext.screenState,
      traceId = null,
      trailblazeDeviceInfo = baseContext.trailblazeDeviceInfo,
      sessionProvider = baseContext.sessionProvider,
      trailblazeLogger = baseContext.trailblazeLogger,
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = NodeSelectorMode.FORCE_NODE_SELECTOR,
    )

    val result = tap.execute(context)
    // CapturingAgent doesn't override executeNodeSelectorTap → returns null → falls to
    // super.execute which dispatches the Maestro TapOnElementCommand via executeMaestroCommands.
    assertIs<TrailblazeToolResult.Success>(result)
  }

  // endregion

  // region helpers

  private var nextId = 1L

  private fun node(
    driverDetail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds?,
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(
    nodeId = nextId++,
    bounds = bounds,
    children = children,
    driverDetail = driverDetail,
  )

  private fun contextWithTree(agent: MaestroTrailblazeAgent, tree: TrailblazeNode?): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode? = tree
    }
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
    )
  }

  private class CapturingAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "t",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1000,
        heightPixels = 1000,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
    },
  ) {
    var lastTapPoint: Pair<Int, Int>? = null
      private set

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      commands.filterIsInstance<TapOnPointV2Command>().lastOrNull()?.let { cmd ->
        val parts = cmd.point.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 2) lastTapPoint = parts[0] to parts[1]
      }
      return TrailblazeToolResult.Success()
    }
  }

  /**
   * Capturing agent variant that advertises [usesAccessibilityDriver] = true so the strict
   * accessibility-refusal branch in [TapOnByElementSelector.execute] applies. Mirrors the
   * runtime contract of [HostOnDeviceRpcTrailblazeAgent] / [AccessibilityTrailblazeAgent]
   * without overriding [executeNodeSelectorTap] — base class returns null, exercising the
   * "agent ran but didn't resolve" path.
   */
  private class AccessibilityCapableCapturingAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "t",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1000,
        heightPixels = 1000,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
    },
  ) {
    override val usesAccessibilityDriver: Boolean = true

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  // endregion
}
