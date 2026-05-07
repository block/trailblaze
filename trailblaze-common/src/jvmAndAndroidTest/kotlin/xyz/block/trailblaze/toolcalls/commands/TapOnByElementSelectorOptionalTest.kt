package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.TapOnElementCommand
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeElementSelector
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that the `optional` flag on [TrailblazeElementSelector] is honored when
 * lowering [TapOnByElementSelector] into a Maestro [TapOnElementCommand]. Without this
 * propagation the field is silently dropped at deserialization, and trail recordings
 * that rely on `optional: true` (typical pattern for best-effort popup dismissal)
 * fail with "Element not found" whenever the target isn't on screen.
 */
class TapOnByElementSelectorOptionalTest {

  private val emptyMemory = AgentMemory()

  @Test
  fun `optional defaults to false on Maestro command`() {
    val tap = TapOnByElementSelector(
      selector = TrailblazeElementSelector(textRegex = "Login"),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    assertEquals(false, command.optional)
  }

  @Test
  fun `optional true on selector propagates to Maestro command`() {
    val tap = TapOnByElementSelector(
      selector = TrailblazeElementSelector(textRegex = "Dismiss", optional = true),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    assertTrue(command.optional, "expected Maestro command.optional to mirror selector.optional=true")
  }

  @Test
  fun `optional false on selector keeps Maestro command optional false`() {
    val tap = TapOnByElementSelector(
      selector = TrailblazeElementSelector(textRegex = "Save", optional = false),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    assertEquals(false, command.optional)
  }
}
