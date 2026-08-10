package xyz.block.trailblaze.host.ios

import maestro.KeyCode
import maestro.Point
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.exception.TrailblazeException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaestroCommandToIosDriverActionConverterTest {

  /** Unwraps a supported command's conversion, failing the test if it reads as unsupported. */
  private fun convert(command: Command): List<IosDriverAction> =
    assertNotNull(MaestroCommandToIosDriverActionConverter.convert(command))

  @Test
  fun `TapOnPointV2 with absolute coords maps to Tap`() {
    val cmd = TapOnPointV2Command(point = "120,240", longPress = false)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.Tap>(out.single())
    assertEquals(120, action.x)
    assertEquals(240, action.y)
  }

  @Test
  fun `TapOnPointV2 with absolute coords + longPress maps to LongPress`() {
    val cmd = TapOnPointV2Command(point = "10,20", longPress = true)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.LongPress>(out.single())
    assertEquals(10, action.x)
    assertEquals(20, action.y)
  }

  @Test
  fun `TapOnPointV2 with percent coords maps to TapRelative`() {
    val cmd = TapOnPointV2Command(point = "25%,75%", longPress = false)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.TapRelative>(out.single())
    assertEquals(25.0, action.percentX)
    assertEquals(75.0, action.percentY)
  }

  @Test
  fun `TapOnPointV2 with percent coords + longPress falls back to TapRelative (no percent-hold primitive)`() {
    // The warning is logged — we can't assert console output without stubbing. The
    // important invariant is that we don't crash and don't silently drop the action.
    val cmd = TapOnPointV2Command(point = "50%,50%", longPress = true)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.TapRelative>(out.single())
    assertEquals(50.0, action.percentX)
    assertEquals(50.0, action.percentY)
  }

  @Test
  fun `TapOnElement without longPress maps to TapOnElement with longPress=false`() {
    val cmd = TapOnElementCommand(selector = ElementSelector(textRegex = "Sign In"), longPress = false)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.TapOnElement>(out.single())
    assertEquals(false, action.longPress)
  }

  @Test
  fun `TapOnElement with longPress=true preserves the flag`() {
    val cmd = TapOnElementCommand(selector = ElementSelector(textRegex = "Edit"), longPress = true)
    val out = convert(cmd)
    val action = assertIs<IosDriverAction.TapOnElement>(out.single())
    assertEquals(true, action.longPress)
  }

  @Test
  fun `TapOnElement preserves Maestro optional flag`() {
    val cmd = TapOnElementCommand(
      selector = ElementSelector(textRegex = "dismiss popup"),
      longPress = false,
      optional = true,
    )
    val action = assertIs<IosDriverAction.TapOnElement>(convert(cmd).single())
    assertEquals(true, action.optional)
  }

  @Test
  fun `SwipeCommand with explicit start and end points maps to Swipe`() {
    val cmd = SwipeCommand(startPoint = Point(10, 20), endPoint = Point(100, 200), duration = 500L)
    val action = assertIs<IosDriverAction.Swipe>(convert(cmd).single())
    assertEquals(10, action.startX)
    assertEquals(20, action.startY)
    assertEquals(100, action.endX)
    assertEquals(200, action.endY)
    assertEquals(500L, action.durationMs)
  }

  @Test
  fun `SwipeCommand with direction maps to SwipeDirection`() {
    val cmd = SwipeCommand(direction = SwipeDirection.UP, duration = 300L)
    val action = assertIs<IosDriverAction.SwipeDirection>(convert(cmd).single())
    assertEquals(IosDriverAction.Direction.UP, action.direction)
    assertEquals(300L, action.durationMs)
  }

  @Test
  fun `SwipeCommand with relative points maps to SwipeRelative`() {
    // The iOS hide-keyboard gesture appended after every inputText
    // (HideKeyboardTrailblazeTool.hideIosKeyboardWithGentleScrollCommands) is exactly this shape.
    val cmd = SwipeCommand(startRelative = "50%,60%", endRelative = "50%, 63%", duration = 50L)
    val action = assertIs<IosDriverAction.SwipeRelative>(convert(cmd).single())
    assertEquals(50.0, action.startXPercent)
    assertEquals(60.0, action.startYPercent)
    assertEquals(50.0, action.endXPercent)
    assertEquals(63.0, action.endYPercent)
    assertEquals(50L, action.durationMs)
  }

  @Test
  fun `SwipeCommand with unparseable relative points is unsupported`() {
    val cmd = SwipeCommand(startRelative = "half,60%", endRelative = "50%,63%", duration = 50L)
    val out = MaestroCommandToIosDriverActionConverter.convert(cmd)
    assertNull(out, "swipe with unparseable relative points should read as unsupported, got: $out")
  }

  @Test
  fun `SwipeCommand with neither points nor direction is unsupported`() {
    val cmd = SwipeCommand(duration = 100L)
    val out = MaestroCommandToIosDriverActionConverter.convert(cmd)
    assertNull(out, "swipe with no points/direction should read as unsupported, got: $out")
  }

  @Test
  fun `InputTextCommand maps to InputText`() {
    val cmd = InputTextCommand(text = "hello world")
    val action = assertIs<IosDriverAction.InputText>(convert(cmd).single())
    assertEquals("hello world", action.text)
  }

  @Test
  fun `EraseTextCommand uses the explicit count when provided`() {
    val cmd = EraseTextCommand(charactersToErase = 7)
    val action = assertIs<IosDriverAction.EraseText>(convert(cmd).single())
    assertEquals(7, action.characters)
  }

  @Test
  fun `EraseTextCommand falls back to 50 when count is null`() {
    val cmd = EraseTextCommand(charactersToErase = null)
    val action = assertIs<IosDriverAction.EraseText>(convert(cmd).single())
    assertEquals(50, action.characters)
  }

  @Test
  fun `BackPressCommand is skipped (Maestro iOS parity)`() {
    // Maestro's iOS driver no-ops BACK; mapping it to Home would background the app mid-trail.
    val out = convert(BackPressCommand())
    assertTrue(out.isEmpty())
  }

  @Test
  fun `HideKeyboardCommand maps to a corner tap at (10, 80)`() {
    val out = convert(HideKeyboardCommand())
    val action = assertIs<IosDriverAction.Tap>(out.single())
    assertEquals(10, action.x)
    assertEquals(80, action.y)
  }

  @Test
  fun `ScrollCommand maps to ScrollDown (Maestro default forward scroll)`() {
    val out = convert(ScrollCommand())
    assertEquals(IosDriverAction.ScrollDown, out.single())
  }

  @Test
  fun `WaitForAnimationToEndCommand uses provided timeout`() {
    val cmd = WaitForAnimationToEndCommand(timeout = "2000")
    val action = assertIs<IosDriverAction.WaitForSettle>(convert(cmd).single())
    assertEquals(2000L, action.timeoutMs)
  }

  @Test
  fun `WaitForAnimationToEndCommand falls back to 5000ms when timeout is null`() {
    val cmd = WaitForAnimationToEndCommand(timeout = null)
    val action = assertIs<IosDriverAction.WaitForSettle>(convert(cmd).single())
    assertEquals(5_000L, action.timeoutMs)
  }

  @Test
  fun `WaitForAnimationToEndCommand falls back to 5000ms when timeout is non-numeric`() {
    // Maestro 2.6.1's timeout is a String and may be an unresolved expression; the converter must
    // degrade to the default rather than throwing NumberFormatException.
    val cmd = WaitForAnimationToEndCommand(timeout = "\${animationTimeout}")
    val action = assertIs<IosDriverAction.WaitForSettle>(convert(cmd).single())
    assertEquals(5_000L, action.timeoutMs)
  }

  @Test
  fun `AssertConditionCommand with visible maps to AssertVisible`() {
    val cmd = AssertConditionCommand(condition = Condition(visible = ElementSelector(textRegex = "Welcome")))
    val action = assertIs<IosDriverAction.AssertVisible>(convert(cmd).single())
    assertEquals(5_000L, action.timeoutMs)
  }

  @Test
  fun `AssertConditionCommand with notVisible maps to AssertNotVisible`() {
    val cmd = AssertConditionCommand(condition = Condition(notVisible = ElementSelector(textRegex = "Loading")))
    assertIs<IosDriverAction.AssertNotVisible>(convert(cmd).single())
  }

  @Test
  fun `AssertConditionCommand preserves Maestro optional flag on both assert shapes`() {
    val visible = AssertConditionCommand(
      condition = Condition(visible = ElementSelector(textRegex = ".*view these items.*")),
      optional = true,
    )
    val visibleAction = assertIs<IosDriverAction.AssertVisible>(convert(visible).single())
    assertEquals(true, visibleAction.optional)

    val notVisible = AssertConditionCommand(
      condition = Condition(notVisible = ElementSelector(textRegex = "Loading")),
      optional = true,
    )
    val notVisibleAction = assertIs<IosDriverAction.AssertNotVisible>(convert(notVisible).single())
    assertEquals(true, notVisibleAction.optional)
  }

  @Test
  fun `AssertConditionCommand with neither visible nor notVisible is unsupported`() {
    val cmd = AssertConditionCommand(condition = Condition())
    assertNull(MaestroCommandToIosDriverActionConverter.convert(cmd))
  }

  @Test
  fun `LaunchAppCommand maps to LaunchApp`() {
    // Maestro treats a launchApp with no stopApp field as stop-then-launch (cold start), so the
    // omitted-field default must be stopFirst=true — a warm resume here is a parity break that
    // turns deterministic replays into state-dependent ones.
    val cmd = LaunchAppCommand(appId = "com.example.app")
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
    assertEquals(true, action.stopFirst)
  }

  @Test
  fun `LaunchAppCommand with explicit stopApp=false warm-resumes`() {
    val cmd = LaunchAppCommand(appId = "com.example.app", stopApp = false)
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
    assertEquals(false, action.stopFirst)
  }

  @Test
  fun `LaunchApp constructed without stopFirst cold-starts`() {
    // A producer that omits stopFirst gets Maestro's omitted-stopApp behavior. Flipping this
    // default back to false is what reintroduces the warm-resume inversion, and it would do so
    // silently — every call site below keeps compiling.
    assertEquals(true, IosDriverAction.LaunchApp(bundleId = "com.example.app").stopFirst)
  }

  @Test
  fun `StopAppCommand maps to StopApp`() {
    val cmd = StopAppCommand(appId = "com.example.app")
    val action = assertIs<IosDriverAction.StopApp>(convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `KillAppCommand maps to StopApp (no distinct kill primitive)`() {
    val cmd = KillAppCommand(appId = "com.example.app")
    val action = assertIs<IosDriverAction.StopApp>(convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `OpenLinkCommand maps to OpenLink`() {
    val cmd = OpenLinkCommand(link = "https://example.com")
    val action = assertIs<IosDriverAction.OpenLink>(convert(cmd).single())
    assertEquals("https://example.com", action.url)
  }

  @Test
  fun `TakeScreenshotCommand maps to TakeScreenshot`() {
    val out = convert(TakeScreenshotCommand(path = "/tmp/foo.png"))
    assertEquals(IosDriverAction.TakeScreenshot, out.single())
  }

  @Test
  fun `LaunchAppCommand with FORCE_RESTART stop-if-running semantics maps to LaunchApp with stopFirst`() {
    // Mirrors LaunchAppTrailblazeTool's iOS-system-app FORCE_RESTART shape (stopApp=true,
    // clearState=false) — e.g. com.apple.MobileAddressBook, which can't be reinstalled/cleared.
    // stopFirst must carry through so the app cold-starts instead of resuming prior state
    // (e.g. a search filter left behind by an earlier trail).
    val cmd = LaunchAppCommand(appId = "com.apple.MobileAddressBook", stopApp = true, clearState = false)
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals("com.apple.MobileAddressBook", action.bundleId)
    assertEquals(true, action.stopFirst)
    assertEquals(false, action.clearState)
  }

  @Test
  fun `LaunchAppCommand with REINSTALL clean-state semantics maps to LaunchApp with clearState`() {
    // Mirrors LaunchAppTrailblazeTool's default launch mode (REINSTALL → clearState=true,
    // stopApp=true). clearState must carry through — dropping it silently downgrades every
    // default-mode launch to FORCE_RESTART semantics and loses the clean-state guarantee.
    val cmd = LaunchAppCommand(appId = "com.example.app", stopApp = true, clearState = true)
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
    assertEquals(true, action.stopFirst)
    assertEquals(true, action.clearState)
    assertEquals(false, action.clearKeychain)
  }

  @Test
  fun `LaunchAppCommand permissions map carries through for pre-granting`() {
    // Maestro pre-grants app permissions before every launch (dropping the map silently
    // reintroduces the permission dialogs the pre-grant exists to prevent). Null stays null —
    // the executor owns Maestro's all=allow default.
    val cmd = LaunchAppCommand(
      appId = "com.example.app",
      permissions = mapOf("location" to "always", "notifications" to "allow"),
    )
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals(mapOf("location" to "always", "notifications" to "allow"), action.permissions)

    val defaulted = assertIs<IosDriverAction.LaunchApp>(convert(LaunchAppCommand(appId = "com.example.app")).single())
    assertNull(defaulted.permissions)
  }

  @Test
  fun `LaunchAppCommand with clearKeychain carries the flag through`() {
    // Sign-in flows rely on clearKeychain to force a signed-out start — iOS keychain entries
    // survive even the clearState reinstall, so dropping this flag silently resumes the previous
    // session (found live: a signed-in app launch skipped the whole sign-in screen).
    val cmd = LaunchAppCommand(appId = "com.example.app", stopApp = true, clearKeychain = true)
    val action = assertIs<IosDriverAction.LaunchApp>(convert(cmd).single())
    assertEquals(true, action.stopFirst)
    assertEquals(false, action.clearState)
    assertEquals(true, action.clearKeychain)
  }

  @Test
  fun `standalone ClearStateCommand maps to ClearState without a launch`() {
    // The dashboard iOS launch tool emits a bare `clearState:` (no launchApp) — it must wipe
    // state and leave the app stopped, not piggyback on LaunchApp machinery.
    val action = assertIs<IosDriverAction.ClearState>(convert(ClearStateCommand(appId = "com.example.app")).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `standalone ClearKeychainCommand maps to ClearKeychain`() {
    assertEquals(IosDriverAction.ClearKeychain, convert(ClearKeychainCommand()).single())
  }

  @Test
  fun `PressKeyCommand ENTER maps to the HID Return keycode`() {
    val action = assertIs<IosDriverAction.PressKey>(
      convert(PressKeyCommand(code = KeyCode.ENTER)).single(),
    )
    assertEquals(40, action.keycode)
  }

  @Test
  fun `PressKeyCommand BACKSPACE maps to the HID Backspace keycode`() {
    val action = assertIs<IosDriverAction.PressKey>(
      convert(PressKeyCommand(code = KeyCode.BACKSPACE)).single(),
    )
    assertEquals(42, action.keycode)
  }

  @Test
  fun `PressKeyCommand HOME maps to the hardware home button`() {
    val out = convert(PressKeyCommand(code = KeyCode.HOME))
    assertEquals(IosDriverAction.PressHome, out.single())
  }

  @Test
  fun `PressKeyCommand BACK is skipped (Maestro iOS parity)`() {
    val out = convert(PressKeyCommand(code = KeyCode.BACK))
    assertTrue(out.isEmpty())
  }

  @Test
  fun `PressKeyCommand with no iOS mapping is dropped`() {
    val out = convert(PressKeyCommand(code = KeyCode.VOLUME_UP))
    assertTrue(out.isEmpty())
  }

  @Test
  fun `AssertConditionCommand visible with textRegex carries the selector through as an IosAxe labelRegex match`() {
    val cmd = AssertConditionCommand(condition = Condition(visible = ElementSelector(textRegex = "Contacts")))
    val action = assertIs<IosDriverAction.AssertVisible>(convert(cmd).single())
    val iosAxeMatch = assertIs<DriverNodeMatch.IosAxe>(action.nodeSelector.iosAxe)
    assertEquals("Contacts", iosAxeMatch.labelRegex)
  }

  @Test
  fun `AssertConditionCommand notVisible with textRegex carries the selector through as an IosAxe labelRegex match`() {
    val cmd = AssertConditionCommand(condition = Condition(notVisible = ElementSelector(textRegex = "Loading")))
    val action = assertIs<IosDriverAction.AssertNotVisible>(convert(cmd).single())
    val iosAxeMatch = assertIs<DriverNodeMatch.IosAxe>(action.nodeSelector.iosAxe)
    assertEquals("Loading", iosAxeMatch.labelRegex)
  }

  @Test
  fun `convertAll flatMaps multiple commands`() {
    val out = MaestroCommandToIosDriverActionConverter.convertAll(
      listOf(
        InputTextCommand(text = "abc"),
        TakeScreenshotCommand(path = "/tmp/x.png"),
        LaunchAppCommand(appId = "com.example"),
      ),
    )
    assertEquals(3, out.size)
    assertIs<IosDriverAction.InputText>(out[0])
    assertEquals(IosDriverAction.TakeScreenshot, out[1])
    assertIs<IosDriverAction.LaunchApp>(out[2])
  }

  @Test
  fun `convertAll fails loudly on a mixed batch containing an unsupported command`() {
    val e = assertFailsWith<TrailblazeException> {
      MaestroCommandToIosDriverActionConverter.convertAll(
        listOf(
          InputTextCommand(text = "abc"),
          SwipeCommand(duration = 100L),
          LaunchAppCommand(appId = "com.example"),
        ),
      )
    }
    assertTrue(e.message!!.contains("SwipeCommand"))
  }

  @Test
  fun `convertAll skips an intentional no-op without failing the batch`() {
    // pressKey with no iOS mapping converts to an empty (non-null) list — a deliberate skip,
    // not an unsupported command — so the surrounding batch still converts.
    val out = MaestroCommandToIosDriverActionConverter.convertAll(
      listOf(
        InputTextCommand(text = "abc"),
        PressKeyCommand(code = KeyCode.VOLUME_UP),
        LaunchAppCommand(appId = "com.example"),
      ),
    )
    assertEquals(2, out.size)
    assertIs<IosDriverAction.InputText>(out[0])
    assertIs<IosDriverAction.LaunchApp>(out[1])
  }
}
