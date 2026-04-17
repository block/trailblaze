package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.StopAppCommand

/**
 * Tests [MaestroCommandConverter.convert] handling of app lifecycle commands:
 * [StopAppCommand], [KillAppCommand], and [ClearStateCommand].
 */
class MaestroCommandConverterAppLifecycleTest {

  @Test
  fun `converts StopAppCommand to StopApp action`() {
    val command = StopAppCommand(appId = "com.example.app")

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.StopApp>(actions.single())
    assertEquals("com.example.app", action.appId)
  }

  @Test
  fun `converts KillAppCommand to KillApp action`() {
    val command = KillAppCommand(appId = "com.example.app")

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.KillApp>(actions.single())
    assertEquals("com.example.app", action.appId)
  }

  @Test
  fun `converts ClearStateCommand to ClearState action`() {
    val command = ClearStateCommand(appId = "com.example.app")

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.ClearState>(actions.single())
    assertEquals("com.example.app", action.appId)
  }

  @Test
  fun `convertAll handles mixed app lifecycle commands`() {
    val commands = listOf(
      StopAppCommand(appId = "com.example.first"),
      KillAppCommand(appId = "com.example.second"),
      ClearStateCommand(appId = "com.example.third"),
    )

    val actions = MaestroCommandConverter.convertAll(commands)

    assertEquals(3, actions.size)
    val stop = assertIs<AccessibilityAction.StopApp>(actions[0])
    assertEquals("com.example.first", stop.appId)
    val kill = assertIs<AccessibilityAction.KillApp>(actions[1])
    assertEquals("com.example.second", kill.appId)
    val clear = assertIs<AccessibilityAction.ClearState>(actions[2])
    assertEquals("com.example.third", clear.appId)
  }
}
