package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import maestro.DeviceOrientation
import maestro.ScrollDirection
import maestro.orchestra.AirplaneValue
import maestro.orchestra.ElementSelector
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.ToggleAirplaneModeCommand
import xyz.block.trailblaze.api.DriverNodeMatch

/**
 * Tests [MaestroCommandConverter.convert] handling of device setting, navigation,
 * and scroll commands that achieve parity with [MaestroAndroidUiAutomatorDriver].
 */
class MaestroCommandConverterDeviceSettingsTest {

  @Test
  fun `converts OpenLinkCommand to OpenLink action`() {
    val command = OpenLinkCommand(link = "https://example.com/deep-link")

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.OpenLink>(actions.single())
    assertEquals("https://example.com/deep-link", action.link)
  }

  @Test
  fun `converts SetOrientationCommand portrait to rotation 0`() {
    val command = SetOrientationCommand(orientation = DeviceOrientation.PORTRAIT)

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.SetOrientation>(actions.single())
    assertEquals(0, action.rotation)
  }

  @Test
  fun `converts SetOrientationCommand landscape_left to rotation 1`() {
    val command = SetOrientationCommand(orientation = DeviceOrientation.LANDSCAPE_LEFT)

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.SetOrientation>(actions.single())
    assertEquals(1, action.rotation)
  }

  @Test
  fun `converts SetAirplaneModeCommand enable`() {
    val command = SetAirplaneModeCommand(value = AirplaneValue.Enable)

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.SetAirplaneMode>(actions.single())
    assertEquals(true, action.enabled)
  }

  @Test
  fun `converts SetAirplaneModeCommand disable`() {
    val command = SetAirplaneModeCommand(value = AirplaneValue.Disable)

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.SetAirplaneMode>(actions.single())
    assertEquals(false, action.enabled)
  }

  @Test
  fun `converts ToggleAirplaneModeCommand`() {
    val command = ToggleAirplaneModeCommand()

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    assertIs<AccessibilityAction.ToggleAirplaneMode>(actions.single())
  }

  @Test
  fun `converts ScrollUntilVisibleCommand to ScrollUntilVisible action`() {
    val command = ScrollUntilVisibleCommand(
      selector = ElementSelector(textRegex = "Load More"),
      direction = ScrollDirection.DOWN,
      timeout = "10000",
      scrollDuration = "500",
      visibilityPercentage = 100,
      centerElement = false,
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.ScrollUntilVisible>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("Load More", match.textRegex)
    assertEquals(AccessibilityAction.Direction.DOWN, action.direction)
    assertEquals(10_000L, action.timeoutMs)
    assertEquals(500L, action.scrollDurationMs)
  }

  @Test
  fun `TakeScreenshotCommand produces no actions`() {
    val command = TakeScreenshotCommand(path = "/tmp/screenshot.png")

    val actions = MaestroCommandConverter.convert(command)

    assertTrue(actions.isEmpty())
  }
}
