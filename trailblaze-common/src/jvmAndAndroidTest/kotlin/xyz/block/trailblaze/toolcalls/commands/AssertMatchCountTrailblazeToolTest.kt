package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssertMatchCountTrailblazeToolTest {

  // -- Pure predicate: the cardinality boundaries this tool exists to enforce --

  @Test
  fun `min is an inclusive lower bound`() {
    // min = 1 -> the "at least one row" case: 0 fails, 1 and above pass.
    assertFalse(satisfies(count = 0, min = 1))
    assertTrue(satisfies(count = 1, min = 1))
    assertTrue(satisfies(count = 5, min = 1))

    assertFalse(satisfies(count = 1, min = 2))
    assertTrue(satisfies(count = 2, min = 2))
    assertTrue(satisfies(count = 3, min = 2))
  }

  @Test
  fun `exact requires equality`() {
    assertFalse(satisfies(count = 1, exact = 2))
    assertTrue(satisfies(count = 2, exact = 2))
    assertFalse(satisfies(count = 3, exact = 2))

    // exact = 0 is the "none present" assertion.
    assertTrue(satisfies(count = 0, exact = 0))
    assertFalse(satisfies(count = 1, exact = 0))
  }

  @Test
  fun `max is an inclusive upper bound`() {
    assertTrue(satisfies(count = 0, max = 3))
    assertTrue(satisfies(count = 3, max = 3))
    assertFalse(satisfies(count = 4, max = 3))

    // max = 0 asserts nothing matches.
    assertTrue(satisfies(count = 0, max = 0))
    assertFalse(satisfies(count = 1, max = 0))
  }

  @Test
  fun `min and max together form an inclusive range`() {
    assertFalse(satisfies(count = 1, min = 2, max = 4))
    assertTrue(satisfies(count = 2, min = 2, max = 4))
    assertTrue(satisfies(count = 3, min = 2, max = 4))
    assertTrue(satisfies(count = 4, min = 2, max = 4))
    assertFalse(satisfies(count = 5, min = 2, max = 4))
  }

  @Test
  fun `exact overrides min and max when all three are set`() {
    assertTrue(satisfies(count = 2, min = 5, exact = 2, max = 10))
    assertFalse(satisfies(count = 5, min = 5, exact = 2, max = 10))
  }

  @Test
  fun `expectation descriptions read naturally`() {
    assertEquals("exactly 3", AssertMatchCountTrailblazeTool.describeExpectation(min = null, exact = 3, max = null))
    assertEquals("at least 1", AssertMatchCountTrailblazeTool.describeExpectation(min = 1, exact = null, max = null))
    assertEquals("at most 5", AssertMatchCountTrailblazeTool.describeExpectation(min = null, exact = null, max = 5))
    assertEquals("between 2 and 4", AssertMatchCountTrailblazeTool.describeExpectation(min = 2, exact = null, max = 4))
  }

  // -- countMatches against a resolved tree: 0 / 1 / N --

  @Test
  fun `countMatches reports zero, one, and many`() {
    val emptyTree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Other")))
    assertEquals(0, AssertMatchCountTrailblazeTool.countMatches(emptyTree, itemSelector()))

    val oneTree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Item")))
    assertEquals(1, AssertMatchCountTrailblazeTool.countMatches(oneTree, itemSelector()))

    val threeTree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Item", bounds = TrailblazeNode.Bounds(0, 10, 100, 50)),
        androidNode(nodeId = 3, text = "Item", bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
        androidNode(nodeId = 4, text = "Item", bounds = TrailblazeNode.Bounds(0, 110, 100, 150)),
      ),
    )
    assertEquals(3, AssertMatchCountTrailblazeTool.countMatches(threeTree, itemSelector()))
  }

  // -- execute() end-to-end --

  @Test
  fun `execute passes when the count satisfies the bound`() = runBlocking {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Item"),
        androidNode(nodeId = 3, text = "Item", bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
      ),
    )

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), min = 1).execute(ctx(tree))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(success.message!!.contains("found 2"), "message should report the observed count: ${success.message}")
  }

  @Test
  fun `execute fails with count and expectation when the bound is not met`() = runBlocking {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Item"),
        androidNode(nodeId = 3, text = "Item", bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
      ),
    )

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), exact = 1).execute(ctx(tree))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(error.errorMessage.contains("exactly 1"), "should name the expectation: ${error.errorMessage}")
    assertTrue(error.errorMessage.contains("found 2"), "should name the observed count: ${error.errorMessage}")
  }

  @Test
  fun `execute fails on a max bound that is exceeded`() = runBlocking {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Item"),
        androidNode(nodeId = 3, text = "Item", bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
        androidNode(nodeId = 4, text = "Item", bounds = TrailblazeNode.Bounds(0, 110, 100, 150)),
      ),
    )

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), max = 2).execute(ctx(tree))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(error.errorMessage.contains("at most 2"))
    assertTrue(error.errorMessage.contains("found 3"))
  }

  @Test
  fun `execute passes an exact-zero bound when nothing matches`() = runBlocking {
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Other")))

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), exact = 0).execute(ctx(tree))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(success.message!!.contains("found 0"), "message should report zero matches: ${success.message}")
  }

  @Test
  fun `execute errors when no cardinality bound is provided`() = runBlocking {
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Item")))

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector()).execute(ctx(tree))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(error.errorMessage.contains("min"), "should name the missing bounds: ${error.errorMessage}")
  }

  @Test
  fun `execute errors when the selector is null`() = runBlocking {
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Item")))

    val result = AssertMatchCountTrailblazeTool(min = 1).execute(ctx(tree))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(error.errorMessage.contains("nodeSelector"))
  }

  @Test
  fun `execute errors when the driver produces no node tree`() = runBlocking {
    val context = ctxNoTree()

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), min = 1).execute(context)

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(
      error.errorMessage.contains("does not produce a TrailblazeNode tree"),
      "should surface the missing-tree shape: ${error.errorMessage}",
    )
  }

  // -- Target app-id template expansion (bug fix: count against the expanded selector) --

  @Test
  fun `countMatches expands the target app-id template when a context is supplied`() {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        rowNode(nodeId = 2, bounds = TrailblazeNode.Bounds(0, 10, 100, 50)),
        rowNode(nodeId = 3, bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
      ),
    )

    // With the target context, `{{target.appId}}:id/row` expands to `$TEST_APP_ID:id/row` and
    // matches both real rows.
    assertEquals(2, AssertMatchCountTrailblazeTool.countMatches(tree, rowSelector(), targetContext()))
    // Negative control: without a context the placeholder stays literal, matching nothing.
    assertEquals(0, AssertMatchCountTrailblazeTool.countMatches(tree, rowSelector()))
  }

  @Test
  fun `execute resolves the target app-id template against the live tree`() = runBlocking {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        rowNode(nodeId = 2, bounds = TrailblazeNode.Bounds(0, 10, 100, 50)),
        rowNode(nodeId = 3, bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
      ),
    )

    val result = AssertMatchCountTrailblazeTool(nodeSelector = rowSelector(), exact = 2)
      .execute(ctxWithTarget(tree))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(success.message!!.contains("found 2"), "template should resolve and count: ${success.message}")
  }

  @Test
  fun `execute counts zero for an unexpanded template when no target context is present`() = runBlocking {
    // Negative control for the fix: with no resolvedTarget the `{{target.appId}}` placeholder is
    // never substituted, so the same selector against the same rows matches nothing.
    val tree = androidNode(
      nodeId = 1,
      children = listOf(rowNode(nodeId = 2)),
    )

    val result = AssertMatchCountTrailblazeTool(nodeSelector = rowSelector(), exact = 0).execute(ctx(tree))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(success.message!!.contains("found 0"), "literal placeholder should match nothing: ${success.message}")
  }

  // -- Cardinality-bound validation (bug fix: reject vacuous / malformed bounds) --

  @Test
  fun `validateBounds accepts coherent bounds and rejects negatives and inverted ranges`() {
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = 1, exact = null, max = null))
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = 0, exact = null, max = 3))
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = null, exact = 0, max = null))

    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = -1, exact = null, max = null))
    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = null, exact = -1, max = null))
    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = null, exact = null, max = -2))
    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = 5, exact = null, max = 2))
  }

  @Test
  fun `validateBounds ignores min and max when exact overrides them`() {
    // `exact` overrides min/max in cardinalitySatisfied, so an inverted or negative min/max is a
    // dead value that must not reject the assertion. Before the fix these returned the
    // incoherent-bounds / negative-bound error.
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = 5, exact = 2, max = 1))
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = -1, exact = 3, max = null))
    assertNull(AssertMatchCountTrailblazeTool.validateBounds(min = null, exact = 0, max = -2))

    // `exact`'s own negativity is still caught.
    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = null, exact = -1, max = null))
    assertNotNull(AssertMatchCountTrailblazeTool.validateBounds(min = 5, exact = -1, max = 1))
  }

  @Test
  fun `execute passes an exact bound even when the ignored min and max are inverted`() = runBlocking {
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Item"),
        androidNode(nodeId = 3, text = "Item", bounds = TrailblazeNode.Bounds(0, 60, 100, 100)),
      ),
    )

    // exact = 2 with an inverted min/max range: the assertion is "exactly 2" and must count, not be
    // rejected up front for incoherent bounds.
    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), min = 5, exact = 2, max = 1)
      .execute(ctx(tree))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(success.message!!.contains("found 2"), "should count rather than reject: ${success.message}")
  }

  @Test
  fun `execute rejects a negative bound as malformed input rather than passing vacuously`() = runBlocking {
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Item")))

    val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), min = -1).execute(ctx(tree))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(error.errorMessage.contains("min"), "should name the offending bound: ${error.errorMessage}")
    // A negative lower bound must be rejected up front, not resolved into an always-true assertion.
    assertFalse(error.errorMessage.contains("found"), "should reject before counting: ${error.errorMessage}")
  }

  @Test
  fun `execute still accepts a coherent bound`() {
    runBlocking {
      val tree = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Item")))

      val result = AssertMatchCountTrailblazeTool(nodeSelector = itemSelector(), min = 1).execute(ctx(tree))

      assertIs<TrailblazeToolResult.Success>(result)
    }
  }

  // -- Fixtures --

  private fun satisfies(count: Int, min: Int? = null, exact: Int? = null, max: Int? = null): Boolean =
    AssertMatchCountTrailblazeTool.cardinalitySatisfied(count, min, exact, max)

  private fun androidNode(
    text: String? = null,
    bounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    nodeId: Long = 0,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = children,
    bounds = bounds,
    driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
  )

  private fun itemSelector() = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = "Item"),
  )

  /** A node whose resource id is the concrete, target-scoped id a real device would report. */
  private fun rowNode(
    nodeId: Long,
    bounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = emptyList(),
    bounds = bounds,
    driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = "$TEST_APP_ID:id/row"),
  )

  /** The hand-authored, target-templated selector — a literal `{{target.appId}}` until expanded. */
  private fun rowSelector() = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "{{target.appId}}:id/row"),
  )

  private fun targetContext() = TargetTemplateContext(appId = TEST_APP_ID, appIds = listOf(TEST_APP_ID))

  private object FakeAppIdTarget :
    TrailblazeHostAppTarget(id = "fake", displayName = "Fake") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf(TEST_APP_ID)

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private class FakeScreenState(val root: TrailblazeNode?) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = root
    override val annotationElements: List<AnnotationElement>? = null
  }

  private fun contextFor(
    state: FakeScreenState,
    resolvedTarget: ResolvedTarget? = null,
    appId: String? = null,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = state,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
    },
    screenStateProvider = { state },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    resolvedTarget = resolvedTarget,
    appId = appId,
  )

  private fun ctx(tree: TrailblazeNode): TrailblazeToolExecutionContext = contextFor(FakeScreenState(tree))

  private fun ctxWithTarget(tree: TrailblazeNode): TrailblazeToolExecutionContext = contextFor(
    FakeScreenState(tree),
    resolvedTarget = ResolvedTarget(
      FakeAppIdTarget,
      TrailblazeDeviceId(instanceId = "test", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
    ),
    appId = TEST_APP_ID,
  )

  private fun ctxNoTree(): TrailblazeToolExecutionContext = contextFor(FakeScreenState(root = null))

  private companion object {
    const val TEST_APP_ID = "com.example.app"
  }
}
