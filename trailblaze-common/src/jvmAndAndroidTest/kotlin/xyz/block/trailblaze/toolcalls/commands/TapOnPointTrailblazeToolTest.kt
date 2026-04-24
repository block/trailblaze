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
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Record-time silent upgrade: [TapOnPointTrailblazeTool.execute] rewrites the *recorded*
 * step as [TapOnTrailblazeTool] when `(x, y)` hit-tests to a uniquely-resolvable element in
 * the [TrailblazeNode] tree, while still firing the raw-coordinate tap in-session.
 *
 * Uses [xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator.resolveFromTap] under the
 * hood — the same selector-generation and round-trip-validation path used by the ref-based
 * tap tooling.
 */
class TapOnPointTrailblazeToolTest {

  @Test
  fun `hit-testable tap stamps TapOnTrailblazeTool with rich selector and relativePoint`(): Unit = runBlocking {
    val button = node(
      DriverNodeDetail.AndroidAccessibility(text = "Submit", isClickable = true),
      TrailblazeNode.Bounds(100, 200, 300, 260),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(button),
    )

    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    // (280, 230) = 90% × 50% within the button bounds.
    TapOnPointTrailblazeTool(x = 280, y = 230).execute(context)

    val override = context.recordedToolOverride
    assertNotNull(override, "record-time upgrade should produce a recordedToolOverride")
    val stamped = assertIs<TapOnTrailblazeTool>(override)
    // Selector comes out typed as AndroidAccessibility because the driver detail said so —
    // no lossy Maestro-flat conversion.
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(stamped.selector.driverMatch)
    assertEquals("Submit", match.textRegex)
    assertEquals("90%,50%", stamped.relativePoint)
    assertEquals(false, stamped.longPress)

    // The actual tap still fires at the raw (x, y) — upgrade is recording-only.
    assertEquals(280 to 230, agent.lastTapPoint)
  }

  @Test
  fun `center tap stamps selector with null relativePoint - center is the default`(): Unit = runBlocking {
    // Dead-center tap: a "50%,50%" relativePoint would just duplicate the tool's null default.
    // Elide it so recordings stay clean — relativePoint is for non-center intents only.
    val button = node(
      DriverNodeDetail.AndroidAccessibility(text = "Submit", isClickable = true),
      TrailblazeNode.Bounds(100, 200, 300, 260),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(button),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    // (200, 230) is the exact center of (100, 200)-(300, 260).
    TapOnPointTrailblazeTool(x = 200, y = 230).execute(context)

    val stamped = assertIs<TapOnTrailblazeTool>(context.recordedToolOverride)
    assertNull(stamped.relativePoint, "dead-center tap should omit relativePoint")
  }

  @Test
  fun `near-center tap on tiny element still elides relativePoint`(): Unit = runBlocking {
    // Small 19x19 icon where the "center" quantizes to 47% or 52% in percent terms
    // (no longer within a percent-based tolerance). Absolute-pixel tolerance still
    // recognizes this as a center tap — which is correct, since hitting the exact-center
    // pixel of a tiny element is physically unrealistic and not meaningfully off-center.
    val icon = node(
      DriverNodeDetail.AndroidAccessibility(text = "X", isClickable = true),
      TrailblazeNode.Bounds(0, 0, 19, 19),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(icon),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    // Bounds center = (9, 9). Tap at (10, 10) — 1px off in each axis.
    TapOnPointTrailblazeTool(x = 10, y = 10).execute(context)

    val stamped = assertIs<TapOnTrailblazeTool>(context.recordedToolOverride)
    assertNull(stamped.relativePoint)
  }

  @Test
  fun `upgrade skips entirely when target center is covered by a different element`(): Unit = runBlocking {
    // Subtle case: outer row (0,0)-(100,100) has a small decorative icon at (48,48)-(52,52)
    // sitting right over its visual center. A tap inside the row but outside the icon lands
    // on the row via hit-test — but the row's *center* pixel (50,50) belongs to the icon.
    // The existing roundTripValid machinery in TrailblazeNodeSelectorGenerator.resolveFromTap
    // already detects this (center-of-selector's-resolution hits icon, not row), so the
    // whole upgrade is skipped and the raw tapOnPoint stays recorded — which is the correct
    // fallback. The in-session tap still fires unchanged.
    val outer = node(
      DriverNodeDetail.AndroidAccessibility(
        text = "Outer row",
        resourceId = "com.example:id/row",
        isClickable = true,
      ),
      TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(
        node(
          DriverNodeDetail.AndroidAccessibility(text = "Icon", isClickable = true),
          TrailblazeNode.Bounds(48, 48, 52, 52),
        ),
      ),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(outer),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    TapOnPointTrailblazeTool(x = 47, y = 47).execute(context)

    assertNull(
      context.recordedToolOverride,
      "roundTripValid should be false here — center of 'row' selector lands on the icon, so upgrade is refused",
    )
    assertEquals(47 to 47, agent.lastTapPoint)
  }

  @Test
  fun `tap 3px off-center on large element stamps sub-percent-precision relativePoint`(): Unit = runBlocking {
    // On a 400px button, 3px off-center is 0.75% — a deliberate non-center intent that
    // integer-percent encoding would silently lose (rounding to "50%,50%" which looks like
    // center). With 0.1% precision we preserve it: (203-0)/400*100 = 50.75 → "50.8%".
    val button = node(
      DriverNodeDetail.AndroidAccessibility(text = "Submit", isClickable = true),
      TrailblazeNode.Bounds(0, 0, 400, 100),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(button),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    // Bounds center = (200, 50). Tap 3px off in x → outside the 2px tolerance.
    TapOnPointTrailblazeTool(x = 203, y = 50).execute(context)

    val stamped = assertIs<TapOnTrailblazeTool>(context.recordedToolOverride)
    assertEquals("50.8%,50%", stamped.relativePoint)
  }

  @Test
  fun `upgrade carries longPress through to the recorded step`(): Unit = runBlocking {
    val button = node(
      DriverNodeDetail.AndroidAccessibility(text = "Menu", isClickable = true),
      TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(button),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    TapOnPointTrailblazeTool(x = 50, y = 25, longPress = true).execute(context)

    val stamped = assertIs<TapOnTrailblazeTool>(context.recordedToolOverride)
    assertEquals(true, stamped.longPress)
  }

  @Test
  fun `upgrade works for Compose nodes via DriverNodeMatch Compose`(): Unit = runBlocking {
    val target = node(
      DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit", hasClickAction = true),
      TrailblazeNode.Bounds(100, 100, 200, 150),
    )
    val root = node(
      DriverNodeDetail.Compose(),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
      children = listOf(target),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    TapOnPointTrailblazeTool(x = 150, y = 125).execute(context)

    val stamped = assertIs<TapOnTrailblazeTool>(context.recordedToolOverride)
    assertIs<DriverNodeMatch.Compose>(stamped.selector.driverMatch)
  }

  @Test
  fun `override stays null when no tree is available`(): Unit = runBlocking {
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = null)
    TapOnPointTrailblazeTool(x = 200, y = 230).execute(context)

    assertNull(context.recordedToolOverride, "no tree → no upgrade")
    assertEquals(200 to 230, agent.lastTapPoint)
  }

  @Test
  fun `override stays null when tap is outside every element`(): Unit = runBlocking {
    // Tap point (5000, 5000) is outside the root bounds — resolveFromTap's hit-test
    // finds no containing node and returns null, so no upgrade can be stamped.
    val root = node(
      DriverNodeDetail.AndroidAccessibility(text = "App", isClickable = true),
      TrailblazeNode.Bounds(0, 0, 1000, 1000),
    )
    val agent = CapturingAgent()
    val context = contextWithTree(agent, tree = root)
    TapOnPointTrailblazeTool(x = 5000, y = 5000).execute(context)

    assertNull(
      context.recordedToolOverride,
      "no node contains the point → resolveFromTap returns null → no override stamped",
    )
    assertEquals(5000 to 5000, agent.lastTapPoint, "raw tap still fires at the original coordinates")
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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
}
