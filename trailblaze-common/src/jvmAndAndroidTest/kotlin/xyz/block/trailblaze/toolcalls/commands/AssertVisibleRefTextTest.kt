package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
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
 * `assertVisible ref=…` records a selector that requires the ref's own text, so replay fails when
 * that text changes. Also covers a step recorded before `expectedText` was removed.
 *
 * The ref tests run the full round trip a CLI call takes: the ref tool expands into the recorded
 * [AssertVisibleBySelectorTrailblazeTool] against one screen, and that recording then runs against
 * a screen whose visibility check resolves the selector for real.
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
  fun `ios ref on a settings cell replays against the cell it named`(): Unit = runBlocking {
    val tree = iosAboutRow()
    assertContains(IosCompactElementList.build(tree).text, "\"Model Name, iPhone 17\"")

    val result = captureThenReplay(tree, ref = "f823", ios = true)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  /**
   * A cell whose left label is wide enough to cover the cell's center. The hit-test at the ref's
   * center settles on that label, so a recording taken from the hit-test described the label and
   * the text check read "Model Name" off it.
   */
  @Test
  fun `ios ref whose center lands on a child label still records the ref's own text`(): Unit = runBlocking {
    val tree = iosAboutRow(nameLabelBounds = TrailblazeNode.Bounds(36, 256, 260, 276))

    assertIs<TrailblazeToolResult.Success>(captureThenReplay(tree, ref = "f823", ios = true))
    assertIs<TrailblazeToolResult.Error>(
      captureThenReplay(
        tree,
        ref = "f823",
        ios = true,
        replayTree = iosAboutRow(nameLabelBounds = TrailblazeNode.Bounds(36, 256, 260, 276), model = "iPhone 18"),
      ),
    )
  }

  @Test
  fun `ios ref fails when the cell no longer shows the text it was recorded with`(): Unit = runBlocking {
    val result = captureThenReplay(iosAboutRow(), ref = "f823", ios = true, replayTree = iosAboutRow(model = "iPhone 18"))

    assertIs<TrailblazeToolResult.Error>(result)
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

    val result = captureThenReplay(tree, ref = "h927", ios = true)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `ios ref on a cell with a disabled chevron checks its text`(): Unit = runBlocking {
    val result = captureThenReplay(iosVersionRow(), ref = "h927", ios = true, replayTree = iosVersionRow(version = "27.0"))

    assertIs<TrailblazeToolResult.Error>(result)
  }

  /**
   * The recording the simulator run produced before the fix: a `containsChild` selector with no
   * text or id of its own. Its result message read "element matched 'element'".
   */
  @Test
  fun `a structural selector's result names the selector`(): Unit = runBlocking {
    val recorded = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector(
        containsChild = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Model Name")),
      ),
    )

    val result = recorded.execute(context(iosAboutRow(), ios = true, agent = AlwaysVisibleAgent()))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertFalse(success.message.orEmpty().contains("'element'"), success.message)
    assertContains(success.message.orEmpty(), "Model Name")
  }

  /**
   * A step recorded before `expectedText` was removed still decodes, because trail decoding keeps
   * the key. It must fail, not pass on presence alone, and must not repeat the value: it can be a
   * remembered secret.
   */
  @Test
  fun `a step that still sets expectedText fails instead of checking presence alone`(): Unit = runBlocking {
    val recorded = Json { ignoreUnknownKeys = true }.decodeFromString(
      AssertVisibleBySelectorTrailblazeTool.serializer(),
      """{"nodeSelector":{"androidAccessibility":{"resourceIdRegex":"password"}},"expectedText":"hunter2"}""",
    )

    val result = recorded.execute(context(androidPasswordField(), ios = false, agent = AlwaysVisibleAgent()))

    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertContains(error.errorMessage, "no longer supports `expectedText`")
    assertFalse(error.errorMessage.contains("hunter2"), error.errorMessage)
  }

  // endregion

  // region Android


  /**
   * A filled field's value is what the user typed, which the selector generator never uses to
   * tell an element apart. An assertion on the field is about that value, so it is recorded anyway.
   */
  @Test
  fun `android ref on a filled field records its value`(): Unit = runBlocking {
    assertIs<TrailblazeToolResult.Success>(captureThenReplay(androidEmailField(), ref = "e1", ios = false))
    assertIs<TrailblazeToolResult.Error>(
      captureThenReplay(
        androidEmailField(),
        ref = "e1",
        ios = false,
        replayTree = androidEmailField(value = "someone@example.com"),
      ),
    )
  }

  @Test
  fun `android ref on an element with a unique id records its text too`(): Unit = runBlocking {
    val recorded = record(androidSubmissionResult(), ref = "s1", ios = false)

    assertEquals("result", recorded.nodeSelector?.androidAccessibility?.resourceIdRegex)
    assertEquals("Name: Jane Doe\nEmail: ", recorded.nodeSelector?.androidAccessibility?.textRegex)
  }

  /** A secret typed into an ordinary field is never written into the recording, like a password. */
  @Test
  fun `android ref on a field holding a sensitive value never records it`(): Unit = runBlocking {
    val memory = AgentMemory().apply { rememberSensitive("email", "jane@example.com") }

    val recorded = record(androidEmailField(value = "jane@example.com"), ref = "e1", ios = false, memory = memory)

    assertFalse(recorded.nodeSelector.toString().contains("jane@example.com"), "recorded: ${recorded.nodeSelector}")
  }

  /** An arg that copied a secret keeps it after the memory key it came from is deleted. */
  @Test
  fun `android ref on a field holding a sensitive arg's value never records it`(): Unit = runBlocking {
    val memory = AgentMemory().apply {
      rememberSensitive("email", "jane@example.com")
      putArg("login", JsonPrimitive("jane@example.com"))
      delete("email")
    }

    val recorded = record(androidEmailField(value = "jane@example.com"), ref = "e1", ios = false, memory = memory)

    assertFalse(recorded.nodeSelector.toString().contains("jane@example.com"), "recorded: ${recorded.nodeSelector}")
  }

  /** An object arg that copied a secret into one of its fields keeps it out of the recording too. */
  @Test
  fun `android ref on a field holding a sensitive object arg's field never records it`(): Unit = runBlocking {
    val memory = AgentMemory().apply {
      rememberSensitive("email", "jane@example.com")
      putArg("credentials", JsonObject(mapOf("email" to JsonPrimitive("jane@example.com"))))
      delete("email")
    }

    val recorded = record(androidEmailField(value = "jane@example.com"), ref = "e1", ios = false, memory = memory)

    assertFalse(recorded.nodeSelector.toString().contains("jane@example.com"), "recorded: ${recorded.nodeSelector}")
  }

  /** A textless row whose label shows a secret is not recorded by that label. */
  @Test
  fun `android ref on a textless row never records a sensitive label`(): Unit = runBlocking {
    val memory = AgentMemory().apply { rememberSensitive("card", "4111 1111") }

    val recorded = record(androidTextlessRow(label = "4111 1111"), ref = "w1", ios = false, memory = memory)

    assertFalse(recorded.nodeSelector.toString().contains("4111"), "recorded: ${recorded.nodeSelector}")
  }

  /** A remembered secret that happens to spell a selector field's name leaves the selector intact. */
  @Test
  fun `a sensitive value naming a selector field still records the element's text`(): Unit = runBlocking {
    val memory = AgentMemory().apply { rememberSensitive("secret", "text") }

    val recorded = record(androidEmailField(), ref = "e1", ios = false, memory = memory)

    assertContains(recorded.nodeSelector?.androidAccessibility?.textRegex.orEmpty(), "user@example")
  }

  /**
   * A row whose ref shows no text, and whose center lands on a textless spacer: the label inside
   * it is the text the ref was shown with, so that is what the assertion checks.
   */
  @Test
  fun `android ref on a textless row checks the label inside it`(): Unit = runBlocking {
    assertIs<TrailblazeToolResult.Success>(captureThenReplay(androidTextlessRow(), ref = "w1", ios = false))
    assertIs<TrailblazeToolResult.Error>(
      captureThenReplay(androidTextlessRow(), ref = "w1", ios = false, replayTree = androidTextlessRow(label = "Refunds")),
    )
  }

  /** The snapshot names a textless row by its direct child's label, not by text nested deeper. */
  @Test
  fun `android ref on a textless row checks its own label, not a nested badge`(): Unit = runBlocking {
    val recorded = record(androidTextlessRow(badge = "3"), ref = "w1", ios = false)

    assertEquals("Payments", recorded.nodeSelector?.androidAccessibility?.textRegex)
  }

  @Test
  fun `android ref on a password field never records its value`(): Unit = runBlocking {
    val recorded = record(androidPasswordField(), ref = "p1", ios = false)

    assertFalse(recorded.nodeSelector.toString().contains("hunter2"), "recorded: ${recorded.nodeSelector}")
  }

  // region Multi-line text

  /** The sample app's Forms result reads `Name: Jane Doe\nEmail: `, line break and all. */
  @Test
  fun `android ref on a multi-line label replays against the same text`(): Unit = runBlocking {
    val result = captureThenReplay(androidSubmissionResult(), ref = "s1", ios = false)

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  @Test
  fun `android ref on a multi-line label fails when a line changes`(): Unit = runBlocking {
    val result = captureThenReplay(
      androidSubmissionResult(),
      ref = "s1",
      ios = false,
      replayTree = androidSubmissionResult(text = "Name: John Doe\nEmail: "),
    )

    assertIs<TrailblazeToolResult.Error>(result)
  }

  /** A line break is part of the text: the same words on one line are a different text. */
  @Test
  fun `android ref on a multi-line label fails when the line break becomes a space`(): Unit = runBlocking {
    val result = captureThenReplay(
      androidSubmissionResult(),
      ref = "s1",
      ios = false,
      replayTree = androidSubmissionResult(text = "Name: Jane Doe Email: "),
    )

    assertIs<TrailblazeToolResult.Error>(result)
  }

  /**
   * The negative check matches an element's whole text, so a quote of a one-line rendering never
   * matched the two-line element and the check passed with the text on screen. Quoting the label
   * the snapshot prints now finds the element, and the check fails.
   */
  @Test
  fun `assertNotVisibleWithText quoting a multi-line label that is on screen fails`(): Unit = runBlocking {
    val tree = androidSubmissionResult()

    val result = AssertNotVisibleWithTextTrailblazeTool(text = agentQuoteOf(tree, id = "result"))
      .execute(context(tree, ios = false, agent = ResolvingAgent(tree)))

    assertIs<TrailblazeToolResult.Error>(result, "the text is on screen, got: $result")
  }

  @Test
  fun `assertNotVisibleWithText quoting text that is not on screen passes`(): Unit = runBlocking {
    val tree = androidSubmissionResult()

    val result = AssertNotVisibleWithTextTrailblazeTool(text = "Name: John Doe\nEmail:")
      .execute(context(tree, ios = false, agent = ResolvingAgent(tree)))

    assertIs<TrailblazeToolResult.Success>(result, "got: $result")
  }

  /**
   * The quoted label of the element with resource id [id], as an agent's tool argument carries it:
   * copied into a JSON string, where the snapshot's `\n` is a line break.
   */
  private fun agentQuoteOf(tree: TrailblazeNode, id: String): String {
    val line = AndroidCompactElementList.build(tree).text.lines().single { "[id=$id]" in it }
    val label = Regex("\"(.*)\" \\[id=").find(line)?.groupValues?.get(1) ?: error("no quoted label in: $line")
    return Json.decodeFromString<String>("\"$label\"")
  }

  // endregion

  /** Two rows share their own label and differ only by a child, so a child anchor tells them apart. */
  @Test
  fun `android ref on a row whose label repeats replays against that row`(): Unit = runBlocking {
    val tree = androidRepeatedRows()
    val recorded = record(tree, ref = "r1", ios = false)

    val resolved = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      TrailblazeNodeSelectorResolver.resolve(tree, recorded.nodeSelector!!),
    )
    assertEquals(10, resolved.node.nodeId)
  }

  // endregion

  // region helpers

  /** Records `assertVisible ref=[ref]` on [tree], then replays the recording on [replayTree]. */
  private suspend fun captureThenReplay(
    tree: TrailblazeNode,
    ref: String,
    ios: Boolean,
    replayTree: TrailblazeNode = tree,
  ): TrailblazeToolResult =
    record(tree, ref, ios).execute(context(replayTree, ios, agent = ResolvingAgent(replayTree)))

  private fun record(
    tree: TrailblazeNode,
    ref: String,
    ios: Boolean,
    memory: AgentMemory = AgentMemory(),
  ): AssertVisibleBySelectorTrailblazeTool =
    assertIs(
      AssertVisibleTrailblazeTool(ref = ref)
        .toExecutableTrailblazeTools(context(tree, ios, agent = null, memory = memory))
        .single(),
    )

  private fun iosAboutRow(
    nameLabelBounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(36, 256, 132, 276),
    model: String = "iPhone 17",
  ): TrailblazeNode {
    val nestedCell = TrailblazeNode(
      nodeId = 43,
      ref = "f823b",
      bounds = TrailblazeNode.Bounds(36, 256, 366, 276),
      driverDetail = DriverNodeDetail.IosMaestro(
        resourceId = "ProductModelName",
        accessibilityText = "Model Name, $model",
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
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = model),
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
        accessibilityText = "Model Name, $model",
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

  private fun iosVersionRow(version: String = "26.5"): TrailblazeNode {
    val rowBounds = TrailblazeNode.Bounds(20, 186, 382, 239)
    val labelsBounds = TrailblazeNode.Bounds(20, 186, 351, 239)
    fun textless(id: Long, bounds: TrailblazeNode.Bounds, children: List<TrailblazeNode> = emptyList()) =
      TrailblazeNode(nodeId = id, bounds = bounds, driverDetail = DriverNodeDetail.IosMaestro(), children = children)
    val nestedCell = TrailblazeNode(
      nodeId = 33,
      ref = "s714",
      bounds = TrailblazeNode.Bounds(36, 203, 343, 223),
      driverDetail = DriverNodeDetail.IosMaestro(resourceId = "SW_VERSION_SPECIFIER", accessibilityText = "iOS Version, $version"),
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
          driverDetail = DriverNodeDetail.IosMaestro(accessibilityText = version),
        ),
      ),
    )
    val cell = TrailblazeNode(
      nodeId = 30,
      ref = "h927",
      bounds = rowBounds,
      driverDetail = DriverNodeDetail.IosMaestro(resourceId = "SW_VERSION_SPECIFIER", accessibilityText = "iOS Version, $version"),
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

  private fun androidEmailField(value: String = "user@example.com"): TrailblazeNode = TrailblazeNode(
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
          text = value,
          hintText = "Email address",
          isEditable = true,
          isVisibleToUser = true,
        ),
      ),
    ),
  )

  private fun androidTextlessRow(label: String = "Payments", badge: String? = null): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    children = listOf(
      TrailblazeNode(
        nodeId = 2,
        ref = "w1",
        bounds = TrailblazeNode.Bounds(0, 100, 1000, 200),
        driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = "row", isVisibleToUser = true),
        children = listOfNotNull(
          badge?.let {
            TrailblazeNode(
              nodeId = 5,
              bounds = TrailblazeNode.Bounds(0, 100, 20, 200),
              driverDetail = DriverNodeDetail.AndroidAccessibility(isVisibleToUser = true),
              children = listOf(
                TrailblazeNode(
                  nodeId = 6,
                  bounds = TrailblazeNode.Bounds(0, 120, 20, 180),
                  driverDetail = DriverNodeDetail.AndroidAccessibility(text = it, isVisibleToUser = true),
                ),
              ),
            )
          },
          TrailblazeNode(
            nodeId = 3,
            bounds = TrailblazeNode.Bounds(20, 120, 300, 180),
            driverDetail = DriverNodeDetail.AndroidAccessibility(
              className = "android.widget.TextView",
              text = label,
              isVisibleToUser = true,
            ),
          ),
          TrailblazeNode(
            nodeId = 4,
            bounds = TrailblazeNode.Bounds(300, 100, 1000, 200),
            driverDetail = DriverNodeDetail.AndroidAccessibility(isVisibleToUser = true),
          ),
        ),
      ),
    ),
  )

  private fun androidPasswordField(): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    children = listOf(
      TrailblazeNode(
        nodeId = 2,
        ref = "p1",
        bounds = TrailblazeNode.Bounds(100, 200, 900, 300),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.widget.EditText",
          resourceId = "password",
          text = "hunter2",
          hintText = "Password",
          isEditable = true,
          isPassword = true,
          isVisibleToUser = true,
        ),
      ),
    ),
  )

  private fun androidSubmissionResult(text: String = "Name: Jane Doe\nEmail: "): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    children = listOf(
      TrailblazeNode(
        nodeId = 2,
        ref = "s1",
        bounds = TrailblazeNode.Bounds(100, 200, 900, 300),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.widget.TextView",
          resourceId = "result",
          text = text,
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
    memory: AgentMemory = AgentMemory(),
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
      memory = memory,
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

  /** Resolves visibility checks against [tree] the way the accessibility driver does on device. */
  private inner class ResolvingAgent(private val tree: TrailblazeNode) : MaestroTrailblazeAgent(
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
    ): TrailblazeToolResult = when (TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch ->
        TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no match for ${nodeSelector.description()}")
      else -> TrailblazeToolResult.Success()
    }

    override suspend fun executeNodeSelectorAssertNotVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult = when (TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> TrailblazeToolResult.Success()
      else -> TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "matched ${nodeSelector.description()}")
    }

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = error("the visibility check must resolve against the tree, not fall back to Maestro")
  }

  // endregion
}
