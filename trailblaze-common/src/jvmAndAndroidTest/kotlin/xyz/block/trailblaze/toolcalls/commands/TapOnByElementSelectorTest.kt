package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.TapOnElementCommand
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sanity coverage for [TapOnByElementSelector.toMaestroCommands] after the removal of the
 * legacy `optional` propagation. Locks in that a basic `nodeSelector` still lowers to a
 * single Maestro [TapOnElementCommand] with the expected matching predicate — a regression
 * in the lowering would otherwise only surface in integration runs.
 */
class TapOnByElementSelectorTest {


  @Test
  fun `basic textRegex selector lowers to a single TapOnElementCommand`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
    )
    val command = tap.toMaestroCommands().single()
    assertIs<TapOnElementCommand>(command)
    assertEquals("Login", command.selector.textRegex)
    assertEquals(false, command.longPress)
  }

  @Test
  fun `longPress=true is propagated onto the Maestro command`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Edit")),
      longPress = true,
    )
    val command = tap.toMaestroCommands().single()
    assertIs<TapOnElementCommand>(command)
    assertEquals(true, command.longPress)
  }

  /**
   * Locks in the issue #2910 removal: a selector lowered through `toMaestroCommands` must
   * never produce a Maestro command with `optional = true`. The selector-level "no-op when
   * missing" escape hatch is gone, and a silent reintroduction in the lowering (e.g. a
   * future commit that adds an `optional` parameter back through some convenience path)
   * would otherwise only surface as a hard-to-diagnose pass-when-the-element-vanished
   * regression in CI.
   */
  @Test
  fun `lowering never sets optional=true on the Maestro command`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Allow")),
    )
    val command = tap.toMaestroCommands().single()
    assertIs<TapOnElementCommand>(command)
    assertEquals(false, command.optional)
  }

  /**
   * Selector fields other than `textRegex` route through `toMaestroElementSelector` field
   * by field, so a regression that accidentally drops one (e.g. `idRegex` no longer
   * propagated through the lowering) wouldn't be caught by the `textRegex`-only cases
   * above. Spot-checks a non-text selector to keep the lowering field-routing honest.
   */
  @Test
  fun `idRegex-only selector lowers with idRegex propagated`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "login_button"),
      ),
    )
    val command = tap.toMaestroCommands().single()
    assertIs<TapOnElementCommand>(command)
    assertEquals("login_button", command.selector.idRegex)
    assertEquals(null, command.selector.textRegex)
  }

  /**
   * The Maestro path has no per-tap wait of its own, so a set [TapOnByElementSelector.timeoutMs]
   * must become a visibility wait on the same element ahead of the tap — otherwise a driver that
   * falls back to Maestro silently applies Maestro's default instead of the author's budget.
   */
  @Test
  fun `timeoutMs lowers to a visibility wait on the same element before the tap`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "More cart options")),
      timeoutMs = 30_000,
    )
    val commands = tap.toMaestroCommands()
    assertEquals(2, commands.size)
    val wait = assertIs<AssertConditionCommand>(commands[0])
    assertEquals("30000", wait.timeout)
    assertEquals("More cart options", wait.condition.visible?.textRegex)
    val tapCommand = assertIs<TapOnElementCommand>(commands[1])
    assertEquals("More cart options", tapCommand.selector.textRegex)
  }
}
