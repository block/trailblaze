package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.KoogRunnableAgent
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
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
import xyz.block.trailblaze.mcp.agent.VerifyFastPath.Mode
import xyz.block.trailblaze.mcp.agent.VerifyFastPath.PhraseMatch
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.Test

class VerifyFastPathTest {

  @Test
  fun `a claim that only names visible phrases passes both gates`() {
    val claim = "Verify the \"Items\" and 'Settings' tabs are displayed"
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.STRICT)).isEqualTo(listOf("Items", "Settings"))
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE)).isEqualTo(listOf("Items", "Settings"))
  }

  @Test
  fun `a platform word inside a quoted phrase is part of the label, not a condition`() {
    val claim = "Verify \"Phone number\" and \"Web browser\" are visible"
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE)).isEqualTo(listOf("Phone number", "Web browser"))
  }

  @Test
  fun `negations, alternatives and conditions are left to the agent in either mode`() {
    listOf(
      "Verify \"Checkout\" is not visible",
      "Verify \"Checkout\" isn't shown",
      "Verify \"Save\" or \"Done\" is visible",
      "Verify only \"Items\" is listed",
      "If a dialog appears, verify \"OK\" is shown",
      "Verify there is no \"Error\" message",
      "Verify \"Error\" appears when the card is declined",
      "Verify \"Saved\" is shown once the upload finishes",
      "Verify \"Continue\" is visible on iOS",
      "Verify \"Home\" is shown on the tablet",
      "Verify \"Continue\" is visible on web",
      "Verify \"Sign in\" appears in the browser",
      "Verify \"Continue\" is visible on tablets",
      "Verify \"Continue\" is shown on iPhones",
    ).forEach { claim ->
      assertThat(VerifyFastPath.phrasesOf(claim, Mode.STRICT), claim).isNull()
      assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE), claim).isNull()
    }
  }

  @Test
  fun `an unquoted qualifier passes the loose gate but not the strict one`() {
    val claim = "Verify the confirmation message \"Saved\" appears"
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.STRICT)).isNull()
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE)).isEqualTo(listOf("Saved"))
  }

  @Test
  fun `a position or state qualifier is left to the agent even in loose mode`() {
    listOf(
      "Verify \"Manual card entry\" appears under the \"Disabled payment types\" heading",
      "Verify the \"Tips\" toggle is enabled",
      "Verify the \"Items\" tab is selected",
    ).forEach { claim -> assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE), claim).isNull() }
  }

  @Test
  fun `an interaction-state or count claim is left to the agent even in loose mode`() {
    listOf(
      "Verify the \"Save\" button is clickable",
      "Verify the \"Name\" field is editable",
      "Verify the \"Total\" field is read-only",
      "Verify two \"Remove\" buttons are shown",
      "Verify every \"Item\" row is visible",
      "Verify both \"Save\" buttons are visible",
      "Verify \"Save\", \"Done\" and \"Cancel\" are both visible",
    ).forEach { claim ->
      assertThat(VerifyFastPath.phrasesOf(claim, Mode.STRICT), claim).isNull()
      assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE), claim).isNull()
    }
  }

  @Test
  fun `both over exactly two phrases is a plain visibility claim`() {
    val claim = "Verify both \"Save\" and \"Done\" are visible"
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.STRICT)).isEqualTo(listOf("Save", "Done"))
    assertThat(VerifyFastPath.phrasesOf(claim, Mode.LOOSE)).isEqualTo(listOf("Save", "Done"))
  }

  @Test
  fun `a leading step tag is not part of the claim`() {
    assertThat(VerifyFastPath.phrasesOf("[ID 39cf45] Verify \"Items\" is visible", Mode.STRICT)).isEqualTo(listOf("Items"))
  }

  @Test
  fun `an unquoted amount is a value claim neither gate closes`() {
    assertThat(VerifyFastPath.phrasesOf("Verify \"Total\" shows \$5.00", Mode.LOOSE)).isNull()
    assertThat(VerifyFastPath.phrasesOf("Verify \"Total\" shows 5 items", Mode.LOOSE)).isNull()
    assertThat(VerifyFastPath.phrasesOf("Verify \"Total \$5.00\" is visible", Mode.STRICT)).isEqualTo(listOf("Total \$5.00"))
  }

  @Test
  fun `a claim with no quoted phrase is left to the agent`() {
    assertThat(VerifyFastPath.phrasesOf("Verify the home screen is displayed", Mode.LOOSE)).isNull()
  }

  @Test
  fun `an apostrophe inside a word is not a quote`() {
    assertThat(VerifyFastPath.phrasesOf("Verify the merchant's \"Items\" tab is visible", Mode.LOOSE)).isEqualTo(listOf("Items"))
  }

  @Test
  fun `each phrase maps to the ref whose label is exactly that phrase`() {
    assertThat(VerifyFastPath.matchOnScreen(listOf("Items", "Settings"), SCREEN)).isEqualTo(
      listOf(PhraseMatch("Items", "a12"), PhraseMatch("Settings", "b34")),
    )
  }

  @Test
  fun `a phrase that is only part of a label, or only on a ref-less line, does not match`() {
    assertThat(VerifyFastPath.matchOnScreen(listOf("Item"), SCREEN)).isNull()
    assertThat(VerifyFastPath.matchOnScreen(listOf("(555) 555-8900"), SCREEN)).isNull()
    assertThat(VerifyFastPath.matchOnScreen(listOf("Items", "Reports"), SCREEN)).isNull()
  }

  @Test
  fun `the env value picks the mode and anything else leaves the fast path off`() {
    assertThat(VerifyFastPath.modeFromEnv("1")).isEqualTo(Mode.STRICT)
    assertThat(VerifyFastPath.modeFromEnv("TRUE")).isEqualTo(Mode.STRICT)
    assertThat(VerifyFastPath.modeFromEnv("strict")).isEqualTo(Mode.STRICT)
    assertThat(VerifyFastPath.modeFromEnv("loose")).isEqualTo(Mode.LOOSE)
    assertThat(VerifyFastPath.modeFromEnv(null)).isNull()
    assertThat(VerifyFastPath.modeFromEnv("0")).isNull()
  }

  @Test
  fun `a matching claim is closed by asserting each phrase, with no LLM involved`() = runBlocking {
    val agent = RecordingAgent { TrailblazeToolResult.Success() }
    val result = tryVerifyFastPath("Verify \"Items\" and \"Settings\" are visible", Mode.STRICT, agent)
    assertThat(result).isNotNull().isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(agent.ran).containsExactly(
      AssertVisibleTrailblazeTool(ref = "a12", expectedText = "Items", reasoning = "verify fast path"),
      AssertVisibleTrailblazeTool(ref = "b34", expectedText = "Settings", reasoning = "verify fast path"),
    )
  }

  @Test
  fun `a phrase not on screen hands the step to the agent before asserting anything`() = runBlocking {
    val agent = RecordingAgent { TrailblazeToolResult.Success() }
    assertThat(tryVerifyFastPath("Verify \"Reports\" is visible", Mode.STRICT, agent)).isNull()
    assertThat(agent.ran).isEmpty()
  }

  @Test
  fun `a failed assertion hands the step to the agent instead of failing it`() = runBlocking {
    val agent = RecordingAgent { TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "text differs") }
    assertThat(tryVerifyFastPath("Verify \"Items\" and \"Settings\" are visible", Mode.STRICT, agent)).isNull()
    assertThat(agent.ran.size).isEqualTo(1)
  }

  @Test
  fun `an assertion that throws hands the step to the agent instead of failing the run`() = runBlocking {
    val agent = RecordingAgent { error("driver went away") }
    assertThat(tryVerifyFastPath("Verify \"Items\" is visible", Mode.STRICT, agent)).isNull()
    assertThat(agent.ran.size).isEqualTo(1)
  }

  @Test
  fun `a ref with a collision suffix is matched like any other`() {
    assertThat(VerifyFastPath.matchOnScreen(listOf("Refunds"), "$SCREEN\n[k42b] Button \"Refunds\"")).isEqualTo(
      listOf(PhraseMatch("Refunds", "k42b")),
    )
  }

  private suspend fun tryVerifyFastPath(claim: String, mode: Mode, agent: RecordingAgent): TrailblazeToolResult? {
    val screen = TextScreenState(SCREEN)
    return tryVerifyFastPath(
      claim = claim,
      mode = mode,
      agent = agent,
      screenProvider = { screen },
      elementComparator = NO_COMPARATOR,
      screenStateProvider = { screen },
      traceId = null,
    )
  }

  private class RecordingAgent(private val answer: (TrailblazeTool) -> TrailblazeToolResult) : KoogRunnableAgent {
    val ran = mutableListOf<TrailblazeTool>()
    override val trailblazeLogger: TrailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId = "fake", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fast-path-test"), startTime = Clock.System.now())
    }
    override val memory = AgentMemory()
    override fun buildKoogToolExecutionContext(
      traceId: TraceId?,
      screenStateProvider: () -> ScreenState,
    ): TrailblazeToolExecutionContext = error("not used by the fast path")

    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult {
      ran += tools
      return TrailblazeAgent.RunTrailblazeToolsResult(tools, tools, answer(tools.single()))
    }
  }

  private class TextScreenState(override val viewHierarchyTextRepresentation: String) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  private companion object {
    val SCREEN = """
      App: com.example.app
      Activity: MainActivity

      [i65] "Back"
      [a12] Button "Items"
      [x90] TextView "Items sold today"
      [w317] "Phone number" [disabled]
        "(555) 555-8900"
        [b34] Tab "Settings" [selected]
    """.trimIndent()

    val NO_COMPARATOR = object : ElementComparator {
      override fun getElementValue(prompt: String): String? = null
      override fun evaluateBoolean(statement: String) = BooleanAssertionTrailblazeTool(reason = statement, result = true)
      override fun evaluateString(query: String) = StringEvaluationTrailblazeTool(reason = query, result = "")
      override fun extractNumberFromString(input: String): Double? = null
    }
  }
}
