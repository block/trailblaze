package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.AndroidCompactElementList
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.IosCompactElementList
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.toViewHierarchyTreeNode
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

/**
 * `assertVisible ref=… expectedText=…` checks the ref's own text, and says what it found when it fails.
 *
 * Each test runs the full round trip a CLI call takes: the ref tool expands into the recorded
 * [AssertVisibleBySelectorTrailblazeTool] against a screen, and that recording then runs against
 * the same screen.
 */
class AssertVisibleRefTextTest {

  // region iOS

  /**
   * Settings > General > About on an iOS 26 simulator, as IOS_HOST captured it: the cell and a
   * nested cell both carry id `ProductModelName` and label "Model Name, iPhone 17", and the two
   * halves of the row are StaticText children. The snapshot prints `[f823] "Model Name, iPhone 17"`.
   * The nested duplicate forces the selector generator to disambiguate with `containsChild` on the
   * "Model Name" child, and the text check used to bind to that child and report
   * `Actual text(s): 'Model Name'`.
   */
  @Test
  fun `ios ref on a settings cell passes with the label the snapshot showed`(): Unit = runBlocking {
    val tree = iosAboutRow()
    assertContains(IosCompactElementList.build(tree).text, "\"Model Name, iPhone 17\"")

    val result = captureThenReplay(tree, ref = "f823", expectedText = "Model Name, iPhone 17", ios = true)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  /**
   * A cell whose left label is wide enough to cover the cell's center. The hit-test at the ref's
   * center settles on that label, so a recording taken from the hit-test described the label and
   * the text check read "Model Name" off it.
   */
  @Test
  fun `ios ref whose center lands on a child label still checks the ref's own text`(): Unit = runBlocking {
    val tree = iosAboutRow(nameLabelBounds = TrailblazeNode.Bounds(36, 256, 260, 276))

    val result = captureThenReplay(tree, ref = "f823", expectedText = "Model Name, iPhone 17", ios = true)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `ios ref still fails on text the cell does not show, naming the selector`(): Unit = runBlocking {
    val result = captureThenReplay(iosAboutRow(), ref = "f823", expectedText = "Model Name, iPhone 18", ios = true)

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertContains(error.errorMessage, "Actual text(s): 'Model Name, iPhone 17'")
    assertFalse(
      error.errorMessage.contains("matched 'element'"),
      "the message must describe the selector: ${error.errorMessage}",
    )
  }

  /**
   * Settings > General > About's "iOS Version, 26.5" row on an iOS 26 simulator, as IOS_HOST
   * captured it: a nested cell repeats the cell's id and label, and the row's chevron is disabled.
   * The legacy selector search matches through Maestro's tree format, and the disabled chevron
   * came back enabled from that round trip, so no match ever equaled the cell. The index fallback
   * then threw "Index fallback failed … This should never happen." and took the tool down, even
   * though iOS records the modern selector. `tap` on the same ref succeeded.
   */
  @Test
  fun `ios ref on a cell with a disabled chevron asserts presence`(): Unit = runBlocking {
    val tree = iosVersionRow()
    assertContains(IosCompactElementList.build(tree).text, "\"iOS Version, 26.5\"")

    val result = captureThenReplay(tree, ref = "h927", expectedText = null, ios = true)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `ios ref on a cell with a disabled chevron checks its text`(): Unit = runBlocking {
    assertIs<TrailblazeToolResult.Success>(
      captureThenReplay(iosVersionRow(), ref = "h927", expectedText = "iOS Version, 26.5", ios = true),
    )
    val error = assertIs<TrailblazeToolResult.Error>(
      captureThenReplay(iosVersionRow(), ref = "h927", expectedText = "iOS Version, 27.0", ios = true),
    )
    assertContains(error.errorMessage, "Actual text(s): 'iOS Version, 26.5'")
  }

  /**
   * The recording the simulator run produced before the fix: a `containsChild` selector with no
   * text or id of its own. Its failure message read "element matched 'element'".
   */
  @Test
  fun `a structural selector's failure names the selector`(): Unit = runBlocking {
    val recorded = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector(
        containsChild = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Model Name")),
      ),
      expectedText = "Model Name, iPhone 17",
    )

    val result = recorded.execute(context(iosAboutRow(), ios = true, agent = AlwaysVisibleAgent()))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertFalse(error.errorMessage.contains("matched 'element'"), error.errorMessage)
    assertContains(error.errorMessage, "Model Name")
  }

  /** The failure message is saved with the session, so a secure field's value must not be in it. */
  @Test
  fun `a failure on an axe secure field hides its value`(): Unit = runBlocking {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      driverDetail = DriverNodeDetail.IosAxe(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          bounds = TrailblazeNode.Bounds(20, 200, 382, 244),
          driverDetail = DriverNodeDetail.IosAxe(role = "AXSecureTextField", label = "Password", value = "hunter2"),
        ),
      ),
    )
    val recorded = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "Password")),
      expectedText = "Passcode",
    )

    val result = recorded.execute(context(tree, ios = true, agent = AlwaysVisibleAgent()))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertFalse(error.errorMessage.contains("hunter2"), error.errorMessage)
    assertContains(error.errorMessage, "Text fields: label='Password', value='(hidden)'")
  }

  // endregion

  // region Android

  /**
   * A filled field with a hint prints as `"hint: value"` in the snapshot, which is no single field
   * of the element. The check compares the field's own text and does not reconstruct the display,
   * so it fails, and the error lists each field by name so the caller can pick the one it meant.
   */
  @Test
  fun `android ref on the snapshot's hint and value fails and lists each field`(): Unit = runBlocking {
    val tree = androidEmailField()
    assertContains(AndroidCompactElementList.build(tree).text, "\"Email address: user@example.com\"")

    val result = captureThenReplay(tree, ref = "e1", expectedText = "Email address: user@example.com", ios = false)

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertContains(error.errorMessage, "Text fields: text='user@example.com', hintText='Email address'")
  }

  @Test
  fun `android ref on a filled field still passes with just its value`(): Unit = runBlocking {
    val result = captureThenReplay(androidEmailField(), ref = "e1", expectedText = "user@example.com", ios = false)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `android ref on a filled field fails on a different value`(): Unit = runBlocking {
    val result = captureThenReplay(
      androidEmailField(),
      ref = "e1",
      expectedText = "Email address: someone@example.com",
      ios = false,
    )

    assertIs<TrailblazeToolResult.Error>(result)
  }

  /**
   * Two rows share their own label and differ only by a child, so a child anchor is the natural
   * way to tell them apart. The text check reads a `containsChild` selector's child, so the
   * recording must not use one to check the row's own label.
   */
  @Test
  fun `android ref on a row whose label repeats still checks the row's own label`(): Unit = runBlocking {
    val result = captureThenReplay(androidRepeatedRows(), ref = "r1", expectedText = "Item", ios = false)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `a failure on an android password field hides its value`(): Unit = runBlocking {
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          bounds = TrailblazeNode.Bounds(100, 200, 900, 300),
          driverDetail = DriverNodeDetail.AndroidAccessibility(
            resourceId = "password",
            text = "hunter2",
            hintText = "Password",
            isPassword = true,
            isVisibleToUser = true,
          ),
        ),
      ),
    )
    val recorded = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "password")),
      expectedText = "Passcode",
    )

    val result = recorded.execute(context(tree, ios = false, agent = AlwaysVisibleAgent()))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertFalse(error.errorMessage.contains("hunter2"), error.errorMessage)
    assertContains(error.errorMessage, "Actual text(s): '(hidden)'")
    assertContains(error.errorMessage, "Text fields: text='(hidden)', hintText='Password'")
  }

  /** Textless wrappers above the text don't use up the failure message's field list. */
  @Test
  fun `a failure lists the fields of text under several textless wrappers`(): Unit = runBlocking {
    var inner = TrailblazeNode(
      nodeId = 100,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Total", isVisibleToUser = true),
    )
    for (id in 101L..106L) {
      inner = TrailblazeNode(
        nodeId = id,
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          resourceId = if (id == 106L) "summary" else null,
          isVisibleToUser = true,
        ),
        children = listOf(inner),
      )
    }
    val tree = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(inner),
    )
    val recorded = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "summary")),
      expectedText = "Subtotal",
    )

    val result = recorded.execute(context(tree, ios = false, agent = AlwaysVisibleAgent()))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertContains(error.errorMessage, "Text fields: text='Total'")
  }

  // endregion

  // region helpers

  private suspend fun captureThenReplay(
    tree: TrailblazeNode,
    ref: String,
    expectedText: String?,
    ios: Boolean,
  ): TrailblazeToolResult {
    val recorded = assertIs<AssertVisibleBySelectorTrailblazeTool>(
      AssertVisibleTrailblazeTool(ref = ref, expectedText = expectedText)
        .toExecutableTrailblazeTools(context(tree, ios, agent = null))
        .single(),
    )
    return recorded.execute(context(tree, ios, agent = AlwaysVisibleAgent()))
  }

  private fun iosAboutRow(
    nameLabelBounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(36, 256, 132, 276),
  ): TrailblazeNode {
    val nestedCell = TrailblazeNode(
      nodeId = 43,
      ref = "f823b",
      bounds = TrailblazeNode.Bounds(36, 256, 366, 276),
      driverDetail = DriverNodeDetail.IosMaestro(
        resourceId = "ProductModelName",
        accessibilityText = "Model Name, iPhone 17",
      ),
      children = listOf(
        TrailblazeNode(
          nodeId = 44,
          ref = "q583",
          bounds = nameLabelBounds,
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = "Model Name"),
        ),
        TrailblazeNode(
          nodeId = 45,
          ref = "i381",
          bounds = TrailblazeNode.Bounds(292, 256, 366, 276),
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = "iPhone 17"),
        ),
      ),
    )
    val wrapper = TrailblazeNode(
      nodeId = 42,
      bounds = TrailblazeNode.Bounds(20, 239, 382, 292),
      driverDetail = DriverNodeDetail.IosMaestro(),
      children = listOf(nestedCell),
    )
    val cell = TrailblazeNode(
      nodeId = 40,
      ref = "f823",
      bounds = TrailblazeNode.Bounds(20, 239, 382, 292),
      driverDetail = DriverNodeDetail.IosMaestro(
        resourceId = "ProductModelName",
        accessibilityText = "Model Name, iPhone 17",
      ),
      children = listOf(
        TrailblazeNode(
          nodeId = 41,
          bounds = TrailblazeNode.Bounds(20, 239, 382, 292),
          driverDetail = DriverNodeDetail.IosMaestro(),
          children = listOf(wrapper),
        ),
      ),
    )
    return TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      driverDetail = DriverNodeDetail.IosMaestro(),
      children = listOf(cell),
    )
  }

  private fun iosVersionRow(): TrailblazeNode {
    val rowBounds = TrailblazeNode.Bounds(20, 186, 382, 239)
    val labelsBounds = TrailblazeNode.Bounds(20, 186, 351, 239)
    fun textless(id: Long, bounds: TrailblazeNode.Bounds, children: List<TrailblazeNode> = emptyList()) =
      TrailblazeNode(nodeId = id, bounds = bounds, driverDetail = DriverNodeDetail.IosMaestro(), children = children)
    val nestedCell = TrailblazeNode(
      nodeId = 33,
      ref = "s714",
      bounds = TrailblazeNode.Bounds(36, 203, 343, 223),
      driverDetail = DriverNodeDetail.IosMaestro(resourceId = "SW_VERSION_SPECIFIER", accessibilityText = "iOS Version, 26.5"),
      children = listOf(
        TrailblazeNode(
          nodeId = 34,
          ref = "y60",
          bounds = TrailblazeNode.Bounds(36, 203, 124, 223),
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = "iOS Version"),
        ),
        TrailblazeNode(
          nodeId = 35,
          ref = "v734",
          bounds = TrailblazeNode.Bounds(309, 203, 343, 223),
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = "26.5"),
        ),
      ),
    )
    val cell = TrailblazeNode(
      nodeId = 30,
      ref = "h927",
      bounds = rowBounds,
      driverDetail = DriverNodeDetail.IosMaestro(resourceId = "SW_VERSION_SPECIFIER", accessibilityText = "iOS Version, 26.5"),
      children = listOf(
        textless(31, labelsBounds, listOf(textless(32, labelsBounds, listOf(nestedCell)))),
        textless(36, rowBounds, listOf(textless(37, rowBounds))),
        textless(38, TrailblazeNode.Bounds(36, 238, 366, 239)),
        TrailblazeNode(
          nodeId = 39,
          bounds = TrailblazeNode.Bounds(351, 206, 362, 220),
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = "chevron", enabled = false),
        ),
      ),
    )
    return TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      driverDetail = DriverNodeDetail.IosMaestro(),
      children = listOf(cell),
    )
  }

  private fun androidEmailField(): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    children = listOf(
      TrailblazeNode(
        nodeId = 2,
        ref = "e1",
        bounds = TrailblazeNode.Bounds(100, 200, 900, 300),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.widget.EditText",
          text = "user@example.com",
          hintText = "Email address",
          isEditable = true,
          isVisibleToUser = true,
        ),
      ),
    ),
  )

  private fun androidRepeatedRows(): TrailblazeNode {
    fun row(id: Long, ref: String, top: Int, detail: String) = TrailblazeNode(
      nodeId = id,
      ref = ref,
      bounds = TrailblazeNode.Bounds(0, top, 1000, top + 100),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.view.ViewGroup",
        contentDescription = "Item",
        isVisibleToUser = true,
      ),
      children = listOf(
        TrailblazeNode(
          nodeId = id + 1,
          bounds = TrailblazeNode.Bounds(600, top + 20, 950, top + 80),
          driverDetail = DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.TextView",
            text = detail,
            isVisibleToUser = true,
          ),
        ),
      ),
    )
    return TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      children = listOf(row(10, "r1", 100, "\$4.00"), row(20, "r2", 200, "\$5.00")),
    )
  }

  private fun context(
    tree: TrailblazeNode,
    ios: Boolean,
    agent: MaestroTrailblazeAgent?,
  ): TrailblazeToolExecutionContext {
    val platform = if (ios) TrailblazeDevicePlatform.IOS else TrailblazeDevicePlatform.ANDROID
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy: ViewHierarchyTreeNode = tree.toViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = platform
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode = tree
    }
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo(platform),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
    )
  }

  private fun deviceInfo(platform: TrailblazeDevicePlatform) = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(instanceId = "t", trailblazeDevicePlatform = platform),
    trailblazeDriverType = if (platform == TrailblazeDevicePlatform.IOS) {
      TrailblazeDriverType.IOS_HOST
    } else {
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
    },
    widthPixels = 1000,
    heightPixels = 1000,
  )

  /** Reports the visibility check as passed so `execute()` reaches the text post-pass. */
  private inner class AlwaysVisibleAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { deviceInfo(TrailblazeDevicePlatform.ANDROID) },
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
