package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeElementSelector
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
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Covers [TextMatchMode] on [AssertVisibleBySelectorTrailblazeTool]: the mode-driven replay
 * compare in `verifyTextEquality`, the legacy Maestro `textRegex` lowering, the capture-time
 * volatile-token detector on [AssertVisibleTrailblazeTool], and back-compat deserialization.
 */
class AssertVisibleTextMatchModeTest {

  // region replay compare (verifyTextEquality, accessibility node-selector path)

  @Test
  fun `EXACT passes only when live text equals expected verbatim`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n3 items",
      expectedText = "Review sale\n3 items",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Success)
  }

  @Test
  fun `EXACT fails when live text differs from expected`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale\n3 items",
      mode = TextMatchMode.EXACT,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `PREFIX passes against live text with a differing volatile tail`() = runBlocking {
    val three = runReplay(
      liveText = "Review sale\n3 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    val two = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    assertTrue(three is TrailblazeToolResult.Success, "3-item tail should pass under PREFIX")
    assertTrue(two is TrailblazeToolResult.Success, "2-item tail should pass under PREFIX")
  }

  @Test
  fun `PREFIX fails when the stable head is not present`() = runBlocking {
    val result = runReplay(
      liveText = "Add items\n3 items",
      expectedText = "Review sale",
      mode = TextMatchMode.PREFIX,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `REGEX matches a wildcarded count pattern`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\n7 items",
      expectedText = "Review sale\\n\\d+ items",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Success)
  }

  @Test
  fun `REGEX fails when the pattern does not match`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale\nno items",
      expectedText = "Review sale\\n\\d+ items",
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Error)
  }

  @Test
  fun `detector-produced pattern replays against a screen that dropped the count`() = runBlocking {
    // End-to-end: a "Review sale\n3 items" capture is rewritten by the detector, then replayed
    // against a screen showing just "Review sale" (the motivating absent-count failure).
    val captured = VolatileTextDetector.resolve("Review sale\n3 items")
    val result = runReplay(
      liveText = "Review sale",
      expectedText = captured.expectedText!!,
      mode = captured.mode,
    )
    assertTrue(result is TrailblazeToolResult.Success, "absent count must replay green")
  }

  @Test
  fun `textless container match finds the asserted text on a child (regression - MaxCalls loop)`() = runBlocking {
    // Selector lands on a structural container (no readable text of its own) while the asserted
    // text lives on a child — a mode-card-style container whose label is on a child node. Pre-fix
    // this failed with "Matched element has no readable text" and the runtime LLM re-issued the
    // identical assertion until its per-step call budget was exhausted.
    val result = runReplayOnContainer(
      containerResourceId = "mode_card",
      childText = "Active on 12 devices",
      expectedText = "Active on 12 devices",
    )
    assertTrue(result is TrailblazeToolResult.Success, "text on a child of the matched container must be found")
  }

  @Test
  fun `textless container without the expected text anywhere still fails (no false green)`() = runBlocking {
    val result = runReplayOnContainer(
      containerResourceId = "mode_card",
      childText = "Standard mode",
      expectedText = "Active on 12 devices",
    )
    assertTrue(result is TrailblazeToolResult.Error, "absent text must still fail — the subtree fallback must not auto-pass")
  }

  // endregion

  // region legacy Maestro textRegex lowering (toMaestroCommands)

  @Test
  fun `EXACT lowers the full expectedText into the Maestro textRegex unchanged`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale\n3 items", mode = TextMatchMode.EXACT)
    assertEquals("Review sale\n3 items", regex)
  }

  @Test
  fun `PREFIX lowers only the escaped stable head with a tolerant tail`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale", mode = TextMatchMode.PREFIX)
    // The volatile tail must not be pinned; the head is escaped and any tail (incl. newline) is allowed.
    assertTrue(Regex(regex).containsMatchIn("Review sale\n3 items"), "should match a 3-item tail")
    assertTrue(Regex(regex).containsMatchIn("Review sale\n2 items"), "should match a 2-item tail")
    assertTrue(regex.startsWith(Regex.escape("Review sale")), "head should be escaped verbatim")
  }

  @Test
  fun `REGEX forwards the expectedText through as the Maestro pattern`() {
    val regex = lowerMaestroTextRegex(expectedText = "Review sale.*", mode = TextMatchMode.REGEX)
    assertEquals("Review sale.*", regex)
  }

  @Test
  fun `REGEX with a malformed pattern lowers to a compilable Maestro textRegex`() {
    // A bad pattern must not reach Maestro raw (it would throw at compile time). It is escaped
    // to a literal so Maestro always gets something it can compile.
    val regex = lowerMaestroTextRegex(expectedText = "Review [sale", mode = TextMatchMode.REGEX)
    assertTrue(runCatching { Regex(regex) }.isSuccess, "lowered pattern must compile")
    assertTrue(Regex(regex).containsMatchIn("Review [sale"), "literal must match its own text")
  }

  // endregion

  // region capture detector

  @Test
  fun `detector pins the head and tolerates the count changing or disappearing`() {
    val resolved = VolatileTextDetector.resolve("Review sale\n3 items")
    assertEquals(TextMatchMode.REGEX, resolved.mode)
    val pattern = Regex(resolved.expectedText!!)
    // The changing count is tolerated, and so is the count vanishing entirely (the motivating
    // replay where "Review sale\n3 items" later renders as just "Review sale").
    assertTrue(pattern.matches("Review sale\n3 items"))
    assertTrue(pattern.matches("Review sale\n2 items"))
    assertTrue(pattern.matches("Review sale"), "count disappearing must still pass")
    // ...but the stable head stays an exact requirement: unrelated tails must NOT pass.
    assertTrue(!pattern.matches("Review sale\nInventory unavailable"), "unrelated tail must fail")
    assertTrue(!pattern.matches("Add items\n3 items"), "different head must fail")
  }

  @Test
  fun `detector keeps a count-only label EXACT so a pinned count still catches a wrong count`() {
    val resolved = VolatileTextDetector.resolve("1 item")
    assertEquals("1 item", resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `detector leaves inline item-count copy EXACT (only a trailing subtitle is volatile)`() {
    // The count is part of stable authored copy, not a trailing volatile subtitle, so it must
    // stay an exact assertion rather than being relaxed.
    for (stable in listOf("Buy 2 items get 1 free", "Minimum 3 items required", "3 items in cart")) {
      val resolved = VolatileTextDetector.resolve(stable)
      assertEquals(stable, resolved.expectedText, "should not rewrite: $stable")
      assertEquals(TextMatchMode.EXACT, resolved.mode, "should stay EXACT: $stable")
    }
  }

  @Test
  fun `detector leaves stable currency text as EXACT`() {
    val resolved = VolatileTextDetector.resolve("Charge \$5.00")
    assertEquals("Charge \$5.00", resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `detector leaves null as EXACT`() {
    val resolved = VolatileTextDetector.resolve(null)
    assertEquals(null, resolved.expectedText)
    assertEquals(TextMatchMode.EXACT, resolved.mode)
  }

  @Test
  fun `capture forwards a volatile item count as a tolerant REGEX through the delegate`() {
    val delegated = captureDelegate(expectedText = "Review sale\n3 items")
    assertEquals(TextMatchMode.REGEX, delegated.textMatchMode)
    val pattern = Regex(delegated.expectedText!!)
    assertTrue(pattern.matches("Review sale\n2 items"))
    assertTrue(pattern.matches("Review sale"))
    assertTrue(!pattern.matches("Review sale\narchived"))
  }

  @Test
  fun `capture leaves stable text as EXACT through the delegate`() {
    val delegated = captureDelegate(expectedText = "Charge \$5.00")
    assertEquals("Charge \$5.00", delegated.expectedText)
    assertEquals(TextMatchMode.EXACT, delegated.textMatchMode)
  }

  // endregion

  // region malformed REGEX is a clean assertion failure, not an infra crash

  @Test
  fun `malformed REGEX pattern fails the assertion instead of throwing`() = runBlocking {
    val result = runReplay(
      liveText = "Review sale",
      expectedText = "Review [sale", // unbalanced bracket — invalid pattern
      mode = TextMatchMode.REGEX,
    )
    assertTrue(result is TrailblazeToolResult.Error, "invalid regex should surface as a failure")
  }

  // endregion

  // region back-compat deserialization

  @Test
  fun `tool deserialized from YAML lacking textMatchMode defaults to EXACT`() {
    val yaml = TrailblazeYaml.Default.getInstance()
    val decoded = yaml.decodeFromString(
      AssertVisibleBySelectorTrailblazeTool.serializer(),
      """
      |selector:
      |  textRegex: "Review sale"
      |expectedText: "Review sale"
      """.trimMargin(),
    )
    assertEquals(TextMatchMode.EXACT, decoded.textMatchMode)
    assertEquals("Review sale", decoded.expectedText)
  }

  @Test
  fun `recorded assertVisibleBySelector keeps an exact item-count assertion at replay`() = runBlocking {
    // Recordings store the lowered assertVisibleBySelector (not the ref-based assertVisible), so
    // the capture-time VolatileTextDetector never re-runs at replay. A trail that deliberately
    // pins "Review sale\n3 items" must therefore still fail when the live count differs — the
    // detector does not silently relax an already-recorded exact assertion.
    val yaml = TrailblazeYaml.Default.getInstance()
    val decoded = yaml.decodeFromString(
      AssertVisibleBySelectorTrailblazeTool.serializer(),
      """
      |expectedText: "Review sale\n3 items"
      """.trimMargin(),
    )
    assertEquals(TextMatchMode.EXACT, decoded.textMatchMode, "no rewrite happens on a recorded tool")
    val result = runReplay(
      liveText = "Review sale\n2 items",
      expectedText = "Review sale\n3 items",
      mode = decoded.textMatchMode,
    )
    assertTrue(result is TrailblazeToolResult.Error, "a pinned exact count must still fail on a mismatch")
  }

  // endregion

  // region helpers

  private suspend fun runReplay(
    liveText: String,
    expectedText: String,
    mode: TextMatchMode,
  ): TrailblazeToolResult {
    val matchedNode = TrailblazeNode(
      nodeId = 2,
      ref = "y778",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        text = liveText,
        resourceId = "review_sale_row",
      ),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(matchedNode),
    )
    // Resolve by a stable resourceId so the post-pass re-resolution is independent of the
    // volatile text under test — the mode-driven compare is what we're exercising.
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "review_sale_row"),
    )
    val tool = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = nodeSelector,
      expectedText = expectedText,
      textMatchMode = mode,
    )
    return tool.execute(replayContext(tree))
  }

  /**
   * Resolves to a textless structural container (matched by resourceId) whose asserted text lives
   * on a [childText] child — exercises the subtree fallback in `collectTextCandidates`.
   */
  private suspend fun runReplayOnContainer(
    containerResourceId: String,
    childText: String,
    expectedText: String,
    mode: TextMatchMode = TextMatchMode.EXACT,
  ): TrailblazeToolResult {
    val child = TrailblazeNode(
      nodeId = 3,
      ref = "child1",
      bounds = TrailblazeNode.Bounds(110, 210, 290, 250),
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = childText),
    )
    val container = TrailblazeNode(
      nodeId = 2,
      ref = "card",
      bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
      driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = containerResourceId),
      children = listOf(child),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(container),
    )
    val tool = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(resourceIdRegex = containerResourceId),
      ),
      expectedText = expectedText,
      textMatchMode = mode,
    )
    return tool.execute(replayContext(tree))
  }

  private fun lowerMaestroTextRegex(expectedText: String, mode: TextMatchMode): String {
    val tool = AssertVisibleBySelectorTrailblazeTool(
      selector = TrailblazeElementSelector(textRegex = "Review sale"),
      expectedText = expectedText,
      textMatchMode = mode,
    )
    val commands: List<Command> = tool.toMaestroCommands(AgentMemory())
    val assertCommand = assertIs<AssertConditionCommand>(commands.single())
    return assertCommand.condition.visible?.textRegex
      ?: error("expected a visible textRegex on the lowered Maestro selector")
  }

  private fun captureDelegate(expectedText: String): AssertVisibleBySelectorTrailblazeTool {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          ref = "y778",
          bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = expectedText),
        ),
      ),
    )
    return assertIs(
      AssertVisibleTrailblazeTool(ref = "y778", expectedText = expectedText)
        .toExecutableTrailblazeTools(captureContext(tree))
        .single(),
    )
  }

  private fun replayContext(tree: TrailblazeNode): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode = tree
    }
    val agent = AlwaysVisibleAgent()
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
    )
  }

  private fun captureContext(tree: TrailblazeNode): TrailblazeToolExecutionContext {
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode = tree
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

  /** Reports the visibility check as passed so `execute()` reaches the text post-pass. */
  private class AlwaysVisibleAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-instance",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
  ) {
    override suspend fun executeNodeSelectorAssertVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  // endregion
}
