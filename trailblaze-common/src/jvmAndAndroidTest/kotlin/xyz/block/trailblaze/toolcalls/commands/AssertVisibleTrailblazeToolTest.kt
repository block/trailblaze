package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
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
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Covers the ref-resolution delegation in [AssertVisibleTrailblazeTool] — lookup by ref,
 * handling of bounds-less matches, and the happy path that flattens to an
 * [AssertVisibleBySelectorTrailblazeTool] carrying both a legacy selector and the upstream
 * `reasoning` propagated into `reason`.
 */
class AssertVisibleTrailblazeToolTest {

  @Test
  fun `throws when ref is not found on the tree`() {
    val root = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "b2",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Submit"),
        ),
      ),
    )
    val context = contextWithTree(trailblazeNodeTree = root)

    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      AssertVisibleTrailblazeTool(ref = "zz99").toExecutableTrailblazeTools(context)
    }
    val message = ex.message.orEmpty()
    assertContains(message, "zz99")
    // Prefix is load-bearing for StaleRefRecovery.STALE_REF_REGEX; the older "snapshot"
    // pointer was dropped because there is no `snapshot` LLM tool — see AssertVisibleTrailblazeTool.
    assertContains(message, "not found on current screen")
    assertContains(message, "view hierarchy")
  }

  @Test
  fun `throws when resolved node has no bounds`() {
    // ref matches but bounds=null → centerPoint() returns null before selector generation runs.
    val boundless = TrailblazeNode(
      nodeId = 2,
      ref = "y778",
      bounds = null,
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Ghost"),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(boundless),
    )
    val context = contextWithTree(trailblazeNodeTree = root)

    val ex = assertFailsWith<TrailblazeToolExecutionException> {
      AssertVisibleTrailblazeTool(ref = "y778").toExecutableTrailblazeTools(context)
    }
    assertContains(ex.message.orEmpty(), "no bounds")
  }

  @Test
  fun `happy path flattens to AssertVisibleBySelector with selector and reasoning`() {
    // Target bounds (100, 200, 300, 260) → center (200, 230). The ViewHierarchyTreeNode
    // mirror must expose a node at the same center so the DFS can map back to a node
    // TapSelectorV2 can generate a selector from.
    // Uses AndroidMaestro (non-accessibility) driver so the legacy selector path runs.
    val trailblazeTree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidMaestro(text = "Submit"),
        ),
      ),
    )
    val viewHierarchy = ViewHierarchyTreeNode(
      nodeId = 1,
      centerPoint = "500,500",
      dimensions = "1000x1000",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          text = "Submit",
          centerPoint = "200,230",
          dimensions = "200x60",
        ),
      ),
    )
    val context = contextWithTree(
      trailblazeNodeTree = trailblazeTree,
      viewHierarchy = viewHierarchy,
    )

    val executables: List<ExecutableTrailblazeTool> = AssertVisibleTrailblazeTool(
      ref = "y778",
      reasoning = "verify the submit button",
    ).toExecutableTrailblazeTools(context)

    assertEquals(1, executables.size)
    val delegated = assertIs<AssertVisibleBySelectorTrailblazeTool>(executables.single())
    assertEquals("verify the submit button", delegated.reason)
    val textRegex = delegated.nodeSelector?.androidMaestro?.textRegex
      ?: delegated.nodeSelector?.androidAccessibility?.textRegex
    assertEquals("Submit", textRegex)
    // nodeSelector is generated inside a try/catch that silently nulls on failure; a
    // regression in TrailblazeNodeSelectorGenerator would still produce a passing
    // TapSelectorV2-converted nodeSelector but strip the richer on-device playback path.
    // Assert it survives.
    assertNotNull(delegated.nodeSelector)
  }

  @Test
  fun `expectedText propagates through to AssertVisibleBySelector on the maestro path`() {
    val trailblazeTree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidMaestro(text = "Charge \$5.00"),
        ),
      ),
    )
    val viewHierarchy = ViewHierarchyTreeNode(
      nodeId = 1,
      centerPoint = "500,500",
      dimensions = "1000x1000",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          text = "Charge \$5.00",
          centerPoint = "200,230",
          dimensions = "200x60",
        ),
      ),
    )
    val context = contextWithTree(
      trailblazeNodeTree = trailblazeTree,
      viewHierarchy = viewHierarchy,
    )

    val delegated = assertIs<AssertVisibleBySelectorTrailblazeTool>(
      AssertVisibleTrailblazeTool(
        ref = "y778",
        expectedText = "Charge \$5.00",
        reasoning = "verify the checkout total",
      ).toExecutableTrailblazeTools(context).single(),
    )
    assertEquals("Charge \$5.00", delegated.expectedText)
    // The visibility-only fields still propagate as before so non-expectedText callers
    // are unaffected.
    assertEquals("verify the checkout total", delegated.reason)
    assertNotNull(delegated.nodeSelector)
  }

  @Test
  fun `expectedText propagates through on the accessibility path with nodeSelector-only`() {
    val trailblazeTree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Active"),
        ),
      ),
    )
    val context = contextWithTree(trailblazeNodeTree = trailblazeTree)

    val delegated = assertIs<AssertVisibleBySelectorTrailblazeTool>(
      AssertVisibleTrailblazeTool(
        ref = "y778",
        expectedText = "Active",
      ).toExecutableTrailblazeTools(context).single(),
    )
    assertEquals("Active", delegated.expectedText)
    assertNotNull(delegated.nodeSelector)
  }

  @Test
  fun `accessibility driver emits an accessibility-shaped nodeSelector`() {
    val trailblazeTree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Submit"),
        ),
      ),
    )
    val context = contextWithTree(trailblazeNodeTree = trailblazeTree)

    val executables = AssertVisibleTrailblazeTool(
      ref = "y778",
      reasoning = "accessibility driver path",
    ).toExecutableTrailblazeTools(context)

    assertEquals(1, executables.size)
    val delegated = assertIs<AssertVisibleBySelectorTrailblazeTool>(executables.single())
    assertEquals("accessibility driver path", delegated.reason)
    assertNotNull(delegated.nodeSelector)
  }

  @Test
  fun `android maestro assert on list row records the row's selector, not the scrollable container`() {
    // Regression companion to the tap mis-target after #4538: the TrailblazeNode hitTest
    // at the row's center climbs to the nearest interactive node — the scrollable list
    // container — so the modern generator describes the container (resourceId only). That
    // lowers to a non-blank Maestro selector, and asserting on the container is vacuously
    // true whenever the list is on screen. On ANDROID the recorded nodeSelector must carry
    // the target row's identity (its text), never just the container's.
    val bagelRow = TrailblazeNode(
      nodeId = 4,
      ref = "b1",
      bounds = TrailblazeNode.Bounds(0, 600, 1000, 800),
      driverDetail = DriverNodeDetail.AndroidMaestro(text = "Bagel"),
    )
    val trailblazeTree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          bounds = TrailblazeNode.Bounds(0, 200, 1000, 900),
          driverDetail = DriverNodeDetail.AndroidMaestro(
            resourceId = "com.example:id/library_list_recycler_view",
            className = "androidx.recyclerview.widget.RecyclerView",
            scrollable = true,
          ),
          children = listOf(
            TrailblazeNode(
              nodeId = 3,
              bounds = TrailblazeNode.Bounds(0, 200, 1000, 400),
              driverDetail = DriverNodeDetail.AndroidMaestro(text = "Beer (Bulk)"),
            ),
            bagelRow,
          ),
        ),
      ),
    )
    val viewHierarchy = ViewHierarchyTreeNode(
      nodeId = 1,
      x1 = 0, y1 = 0, x2 = 1000, y2 = 1000,
      centerPoint = "500,500",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          resourceId = "com.example:id/library_list_recycler_view",
          className = "androidx.recyclerview.widget.RecyclerView",
          scrollable = true,
          x1 = 0, y1 = 200, x2 = 1000, y2 = 900,
          centerPoint = "500,550",
          children = listOf(
            ViewHierarchyTreeNode(
              nodeId = 3,
              text = "Beer (Bulk)",
              x1 = 0, y1 = 200, x2 = 1000, y2 = 400,
              centerPoint = "500,300",
            ),
            ViewHierarchyTreeNode(
              nodeId = 4,
              text = "Bagel",
              x1 = 0, y1 = 600, x2 = 1000, y2 = 800,
              centerPoint = "500,700",
            ),
          ),
        ),
      ),
    )
    val context = contextWithTree(
      trailblazeNodeTree = trailblazeTree,
      viewHierarchy = viewHierarchy,
    )

    val delegated = assertIs<AssertVisibleBySelectorTrailblazeTool>(
      AssertVisibleTrailblazeTool(ref = "b1").toExecutableTrailblazeTools(context).single(),
    )
    val lowered = delegated.nodeSelector?.toTrailblazeElementSelector()
    assertEquals("Bagel", lowered?.textRegex)
    assertEquals(null, lowered?.idRegex)
  }

  // region helpers

  // toExecutableTrailblazeTools never reads maestroTrailblazeAgent, so leaving it null
  // keeps the fixture minimal. Existing tests that exercise execute() pass a CapturingAgent.
  private fun contextWithTree(
    trailblazeNodeTree: TrailblazeNode?,
    viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(),
  ): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = viewHierarchy
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode? = trailblazeNodeTree
    }
    return TrailblazeToolExecutionContext(
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
    )
  }

  // endregion
}
