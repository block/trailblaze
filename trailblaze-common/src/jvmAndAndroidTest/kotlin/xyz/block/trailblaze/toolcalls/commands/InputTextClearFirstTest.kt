package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.TapOnElementCommand
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
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `inputText` with `clearFirst` leaves the field holding only the new text — one step in place of a
 * recorded tap + `eraseText`/`clearText` + `inputText` sequence — and empties the field the text
 * lands in, not whichever field held focus when the step began.
 *
 * Asserted against a fake screen of two prefilled fields, so every test reads what the fields end
 * up holding rather than which commands ran.
 */
class InputTextClearFirstTest {

  private val emailSelector =
    TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Email"))

  /**
   * Two text fields, Name focused. A tap focuses a field; typing appends to the focused field and
   * erasing drops characters off its end, the way every driver's keys behave.
   *
   * [nativeClear] stands in for a driver with its own clear (the Android accessibility and AXe
   * drivers); [nativeFocusAndType] for one that focuses and types a selector's field itself.
   * [iosMaestroCapture] captures the way iOS through Maestro does: no field marked focused, each
   * field's value as its text (none when empty) and its placeholder as its hint, plus a label
   * beside each field. A tap on the label focuses its field.
   */
  private class FakeScreen(
    private val nativeClear: Boolean = false,
    private val nativeClearResult: TrailblazeToolResult = TrailblazeToolResult.Success(),
    private val nativeFocusAndType: Boolean = false,
    private val iosMaestroCapture: Boolean = false,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val fields = linkedMapOf("Name" to "Old Name", "Email" to "old.address@example.com")
    var focused = "Name"
    val erases: MutableList<Int> = mutableListOf()

    override val usesAccessibilityDriver: Boolean get() = nativeFocusAndType

    private fun type(text: String) {
      fields[focused] = fields.getValue(focused) + text
    }

    private fun erase(characters: Int) {
      erases += characters
      fields[focused] = fields.getValue(focused).dropLast(characters)
    }

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      commands.forEach { command ->
        when (command) {
          is TapOnElementCommand -> focused = fields.keys.single { Regex(command.selector.textRegex!!).matches(it) }
          is InputTextCommand -> type(command.text)
          is EraseTextCommand -> erase(command.charactersToErase!!)
          is HideKeyboardCommand -> Unit
          else -> error("Unexpected command $command")
        }
      }
      return TrailblazeToolResult.Success()
    }

    override suspend fun executeNodeSelectorTap(
      nodeSelector: TrailblazeNodeSelector,
      longPress: Boolean,
      tapRoute: TapRouteOverride?,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      nodeSelector.iosMaestro?.let { match ->
        focused = fields.keys.single { name ->
          match.accessibilityTextRegex?.let { Regex(it).matches(name) } ?: Regex(match.textRegex!!).matches("$name label")
        }
        return TrailblazeToolResult.Success()
      }
      if (!nativeFocusAndType) return null
      focused = fieldNamed(nodeSelector)
      return TrailblazeToolResult.Success()
    }

    override suspend fun executeNodeSelectorInputText(
      nodeSelector: TrailblazeNodeSelector,
      text: String,
      hideKeyboardAfter: Boolean,
      clearFirst: Boolean,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      if (!nativeFocusAndType) return null
      focused = fieldNamed(nodeSelector)
      if (clearFirst) fields[focused] = ""
      type(text)
      return TrailblazeToolResult.Success()
    }

    override suspend fun clearFocusedTextField(traceId: TraceId?): TrailblazeToolResult? {
      if (!nativeClear) return null
      if (nativeClearResult is TrailblazeToolResult.Success) fields[focused] = ""
      return nativeClearResult
    }

    private fun fieldNamed(selector: TrailblazeNodeSelector): String =
      fields.keys.single { Regex(selector.androidAccessibility!!.textRegex!!).matches(it) }

    /** What a capture reports right now: every field, the focused one marked. */
    fun capture(): ScreenState = if (iosMaestroCapture) {
      FixtureScreenState(
        viewHierarchy = ViewHierarchyTreeNode(children = fields.values.map { ViewHierarchyTreeNode(text = it) }),
        trailblazeNodeTree = TrailblazeNode(
          driverDetail = DriverNodeDetail.IosMaestro(),
          children = fields.flatMap { (name, value) ->
            listOf(
              TrailblazeNode(driverDetail = DriverNodeDetail.IosMaestro(text = "$name label")),
              TrailblazeNode(
                driverDetail = DriverNodeDetail.IosMaestro(
                  text = value.ifEmpty { null },
                  hintText = "Enter $name",
                  accessibilityText = name,
                ),
              ),
            )
          },
        ),
      )
    } else {
      FixtureScreenState(
        ViewHierarchyTreeNode(
          children = fields.map { (name, value) -> ViewHierarchyTreeNode(text = value, focused = name == focused) },
        ),
      )
    }
  }

  private class FixtureScreenState(
    override val viewHierarchy: ViewHierarchyTreeNode,
    override val trailblazeNodeTree: TrailblazeNode? = null,
  ) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  /** The step's screen state is captured before it runs, as the trail runner does; captures after that are live. */
  private fun contextFor(screen: FakeScreen, stepScreenState: ScreenState? = screen.capture()) =
    TrailblazeToolExecutionContext(
      screenState = stepScreenState,
      traceId = null,
      trailblazeDeviceInfo = screen.trailblazeDeviceInfoProvider(),
      sessionProvider = screen.sessionProvider,
      screenStateProvider = screen::capture,
      trailblazeLogger = screen.trailblazeLogger,
      memory = AgentMemory(),
      maestroTrailblazeAgent = screen,
    )

  @Test
  fun `a selector field is emptied before typing and the focused field is left alone`() = runBlocking {
    val screen = FakeScreen()

    val result = InputTextTrailblazeTool(text = "new@example.com", selector = emailSelector, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    // The step's own screen state shows Name focused, holding a shorter value; erasing by that
    // would leave most of the Email address in place.
    assertThat(screen.fields).isEqualTo(mapOf("Name" to "Old Name", "Email" to "new@example.com"))
  }

  @Test
  fun `without a selector the focused field is emptied before typing`() = runBlocking {
    val screen = FakeScreen()

    InputTextTrailblazeTool(text = "Blaze", clearFirst = true).execute(contextFor(screen))

    assertThat(screen.fields.getValue("Name")).isEqualTo("Blaze")
  }

  @Test
  fun `without clearFirst typing adds to what the field holds, as recorded trails expect`() = runBlocking {
    val screen = FakeScreen()

    InputTextTrailblazeTool(text = "!", selector = emailSelector).execute(contextFor(screen))

    assertThat(screen.fields.getValue("Email")).isEqualTo("old.address@example.com!")
  }

  @Test
  fun `a field the hierarchy shows no focus for still ends up holding only the new text`() = runBlocking {
    val screen = FakeScreen()

    // No screen state at all: the erase falls back to a count far past any realistic field.
    InputTextTrailblazeTool(text = "Blaze", clearFirst = true).execute(contextFor(screen, stepScreenState = null))

    assertThat(screen.fields.getValue("Name")).isEqualTo("Blaze")
  }

  private val longAddress = "a.much.longer.address.than.maestro.erases.by.default@example.com"

  @Test
  fun `a selector field on a capture that marks no focus is emptied by erasing its length`() = runBlocking {
    val screen = FakeScreen(iosMaestroCapture = true)
    screen.fields["Email"] = longAddress
    val emailField = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(accessibilityTextRegex = "Email"))

    InputTextTrailblazeTool(text = "new@example.com", selector = emailField, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(screen.fields).isEqualTo(mapOf("Name" to "Old Name", "Email" to "new@example.com"))
    // Not the blind fallback: through Maestro on iOS, every erased character costs a key event.
    assertThat(screen.erases.single()).isLessThan(ClearTextTrailblazeTool.FALLBACK_ERASE_COUNT)
  }

  @Test
  fun `an empty selector field on a capture that marks no focus is not erased at all`() = runBlocking {
    val screen = FakeScreen(iosMaestroCapture = true)
    screen.fields["Email"] = ""
    val emailField = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(accessibilityTextRegex = "Email"))

    InputTextTrailblazeTool(text = "new@example.com", selector = emailField, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(screen.fields.getValue("Email")).isEqualTo("new@example.com")
    assertThat(screen.erases).isEmpty()
  }

  @Test
  fun `a selector naming the label beside a field still empties all of the field`() = runBlocking {
    val screen = FakeScreen(iosMaestroCapture = true)
    screen.fields["Email"] = longAddress
    val emailLabel = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Email label"))

    InputTextTrailblazeTool(text = "new@example.com", selector = emailLabel, clearFirst = true)
      .execute(contextFor(screen))

    // The label's length says nothing about the field's, so this erases the full fallback.
    assertThat(screen.fields).isEqualTo(mapOf("Name" to "Old Name", "Email" to "new@example.com"))
  }

  @Test
  fun `a driver's own clear empties the field the tap focused`() = runBlocking {
    val screen = FakeScreen(nativeClear = true)

    InputTextTrailblazeTool(text = "new@example.com", selector = emailSelector, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(screen.fields).isEqualTo(mapOf("Name" to "Old Name", "Email" to "new@example.com"))
  }

  @Test
  fun `a driver that focuses and types the field itself is asked to clear it`() = runBlocking {
    val screen = FakeScreen(nativeFocusAndType = true)

    InputTextTrailblazeTool(text = "new@example.com", selector = emailSelector, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(screen.fields).isEqualTo(mapOf("Name" to "Old Name", "Email" to "new@example.com"))
  }

  @Test
  fun `a clear that fails types nothing and returns the failure`() = runBlocking {
    val failure = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "field still holds text")
    val screen = FakeScreen(nativeClear = true, nativeClearResult = failure)

    val result = InputTextTrailblazeTool(text = "new@example.com", selector = emailSelector, clearFirst = true)
      .execute(contextFor(screen))

    assertThat(result).isEqualTo(failure)
    assertThat(screen.fields.getValue("Email")).isEqualTo("old.address@example.com")
  }
}
