package xyz.block.trailblaze.host.axe

import maestro.Point
import maestro.SwipeDirection
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaestroCommandToAxeActionConverterTest {

  @Test
  fun `TapOnPointV2 with absolute coords maps to Tap`() {
    val cmd = TapOnPointV2Command(point = "120,240", longPress = false)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.Tap>(out.single())
    assertEquals(120, action.x)
    assertEquals(240, action.y)
  }

  @Test
  fun `TapOnPointV2 with absolute coords + longPress maps to LongPress`() {
    val cmd = TapOnPointV2Command(point = "10,20", longPress = true)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.LongPress>(out.single())
    assertEquals(10, action.x)
    assertEquals(20, action.y)
  }

  @Test
  fun `TapOnPointV2 with percent coords maps to TapRelative`() {
    val cmd = TapOnPointV2Command(point = "25%,75%", longPress = false)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.TapRelative>(out.single())
    assertEquals(25.0, action.percentX)
    assertEquals(75.0, action.percentY)
  }

  @Test
  fun `TapOnPointV2 with percent coords + longPress falls back to TapRelative (no percent-hold primitive)`() {
    // The warning is logged — we can't assert console output without stubbing. The
    // important invariant is that we don't crash and don't silently drop the action.
    val cmd = TapOnPointV2Command(point = "50%,50%", longPress = true)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.TapRelative>(out.single())
    assertEquals(50.0, action.percentX)
    assertEquals(50.0, action.percentY)
  }

  @Test
  fun `TapOnElement without longPress maps to TapOnElement with longPress=false`() {
    val cmd = TapOnElementCommand(selector = ElementSelector(textRegex = "Sign In"), longPress = false)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.TapOnElement>(out.single())
    assertEquals(false, action.longPress)
  }

  @Test
  fun `TapOnElement with longPress=true preserves the flag`() {
    val cmd = TapOnElementCommand(selector = ElementSelector(textRegex = "Edit"), longPress = true)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    val action = assertIs<AxeAction.TapOnElement>(out.single())
    assertEquals(true, action.longPress)
  }

  @Test
  fun `SwipeCommand with explicit start and end points maps to Swipe`() {
    val cmd = SwipeCommand(startPoint = Point(10, 20), endPoint = Point(100, 200), duration = 500L)
    val action = assertIs<AxeAction.Swipe>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(10, action.startX)
    assertEquals(20, action.startY)
    assertEquals(100, action.endX)
    assertEquals(200, action.endY)
    assertEquals(500L, action.durationMs)
  }

  @Test
  fun `SwipeCommand with direction maps to SwipeDirection`() {
    val cmd = SwipeCommand(direction = SwipeDirection.UP, duration = 300L)
    val action = assertIs<AxeAction.SwipeDirection>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(AxeAction.Direction.UP, action.direction)
    assertEquals(300L, action.durationMs)
  }

  @Test
  fun `SwipeCommand with neither points nor direction is dropped`() {
    val cmd = SwipeCommand(duration = 100L)
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    assertTrue(out.isEmpty(), "swipe with no points/direction should be dropped, got: $out")
  }

  @Test
  fun `InputTextCommand maps to InputText`() {
    val cmd = InputTextCommand(text = "hello world")
    val action = assertIs<AxeAction.InputText>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals("hello world", action.text)
  }

  @Test
  fun `EraseTextCommand uses the explicit count when provided`() {
    val cmd = EraseTextCommand(charactersToErase = 7)
    val action = assertIs<AxeAction.EraseText>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(7, action.characters)
  }

  @Test
  fun `EraseTextCommand falls back to 50 when count is null`() {
    val cmd = EraseTextCommand(charactersToErase = null)
    val action = assertIs<AxeAction.EraseText>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(50, action.characters)
  }

  @Test
  fun `BackPressCommand maps to PressHome (iOS has no back)`() {
    val out = MaestroCommandToAxeActionConverter.convert(BackPressCommand())
    assertEquals(AxeAction.PressHome, out.single())
  }

  @Test
  fun `HideKeyboardCommand maps to a corner tap at (10, 80)`() {
    val out = MaestroCommandToAxeActionConverter.convert(HideKeyboardCommand())
    val action = assertIs<AxeAction.Tap>(out.single())
    assertEquals(10, action.x)
    assertEquals(80, action.y)
  }

  @Test
  fun `ScrollCommand maps to ScrollDown (Maestro default forward scroll)`() {
    val out = MaestroCommandToAxeActionConverter.convert(ScrollCommand())
    assertEquals(AxeAction.ScrollDown, out.single())
  }

  @Test
  fun `WaitForAnimationToEndCommand uses provided timeout`() {
    val cmd = WaitForAnimationToEndCommand(timeout = 2000L)
    val action = assertIs<AxeAction.WaitForSettle>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(2000L, action.timeoutMs)
  }

  @Test
  fun `WaitForAnimationToEndCommand falls back to 5000ms when timeout is null`() {
    val cmd = WaitForAnimationToEndCommand(timeout = null)
    val action = assertIs<AxeAction.WaitForSettle>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(5_000L, action.timeoutMs)
  }

  @Test
  fun `AssertConditionCommand with visible maps to AssertVisible`() {
    val cmd = AssertConditionCommand(condition = Condition(visible = ElementSelector(textRegex = "Welcome")))
    val action = assertIs<AxeAction.AssertVisible>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals(5_000L, action.timeoutMs)
  }

  @Test
  fun `AssertConditionCommand with notVisible maps to AssertNotVisible`() {
    val cmd = AssertConditionCommand(condition = Condition(notVisible = ElementSelector(textRegex = "Loading")))
    assertIs<AxeAction.AssertNotVisible>(MaestroCommandToAxeActionConverter.convert(cmd).single())
  }

  @Test
  fun `AssertConditionCommand with neither visible nor notVisible is dropped`() {
    val cmd = AssertConditionCommand(condition = Condition())
    val out = MaestroCommandToAxeActionConverter.convert(cmd)
    assertTrue(out.isEmpty())
  }

  @Test
  fun `LaunchAppCommand maps to LaunchApp`() {
    val cmd = LaunchAppCommand(appId = "com.example.app")
    val action = assertIs<AxeAction.LaunchApp>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `StopAppCommand maps to StopApp`() {
    val cmd = StopAppCommand(appId = "com.example.app")
    val action = assertIs<AxeAction.StopApp>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `KillAppCommand maps to StopApp (no distinct kill primitive)`() {
    val cmd = KillAppCommand(appId = "com.example.app")
    val action = assertIs<AxeAction.StopApp>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals("com.example.app", action.bundleId)
  }

  @Test
  fun `OpenLinkCommand maps to OpenLink`() {
    val cmd = OpenLinkCommand(link = "https://example.com")
    val action = assertIs<AxeAction.OpenLink>(MaestroCommandToAxeActionConverter.convert(cmd).single())
    assertEquals("https://example.com", action.url)
  }

  @Test
  fun `TakeScreenshotCommand maps to TakeScreenshot`() {
    val out = MaestroCommandToAxeActionConverter.convert(TakeScreenshotCommand(path = "/tmp/foo.png"))
    assertEquals(AxeAction.TakeScreenshot, out.single())
  }

  @Test
  fun `convertAll flatMaps multiple commands`() {
    val out = MaestroCommandToAxeActionConverter.convertAll(
      listOf(
        InputTextCommand(text = "abc"),
        TakeScreenshotCommand(path = "/tmp/x.png"),
        LaunchAppCommand(appId = "com.example"),
      ),
    )
    assertEquals(3, out.size)
    assertIs<AxeAction.InputText>(out[0])
    assertEquals(AxeAction.TakeScreenshot, out[1])
    assertIs<AxeAction.LaunchApp>(out[2])
  }
}
