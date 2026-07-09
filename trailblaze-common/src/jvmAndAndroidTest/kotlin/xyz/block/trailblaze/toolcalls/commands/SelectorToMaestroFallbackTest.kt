package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.TapOnElementCommand
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Verifies the Maestro projection in [TapOnByElementSelector.toMaestroCommands] and
 * [AssertVisibleBySelectorTrailblazeTool.toMaestroCommands]:
 *
 * 1. **nodeSelector set**: lowered through [TrailblazeNodeSelector.toTrailblazeElementSelector]
 *    so the Maestro orchestra remains the unified resolver for any agent that doesn't natively
 *    resolve nodeSelectors.
 * 2. **nodeSelector null**: malformed recording — emit empty list ([TapOnByElementSelector]) or
 *    throw ([AssertVisibleBySelectorTrailblazeTool]), preserving existing fail-loud
 *    behavior so the absence of any selector is surfaced rather than silently no-op'd.
 */
class SelectorToMaestroFallbackTest {

  private val emptyMemory = AgentMemory()

  // --- TapOnByElementSelector ---------------------------------------------------------

  @Test
  fun `tap nodeSelector lowers via toTrailblazeElementSelector`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(
          textRegex = "Login",
          resourceIdRegex = "com.app:id/btn",
        ),
      ),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    assertEquals("Login", command.selector.textRegex)
    assertEquals("com.app:id/btn", command.selector.idRegex)
  }

  @Test
  fun `tap nodeSelector with iosMaestro shape lowers correctly`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Save"),
      ),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    assertEquals("Save", command.selector.textRegex)
  }

  @Test
  fun `tap nodeSelector preserves structural fields (containsChild)`() {
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        containsChild = TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "Catalog"),
        ),
      ),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    val inner = command.selector.containsChild
    assertNotNull(inner, "expected containsChild on the Maestro command")
    assertEquals("Catalog", inner.textRegex)
  }

  @Test
  fun `tap with no nodeSelector emits empty command list (caller's contract)`() {
    val tap = TapOnByElementSelector()
    assertEquals(emptyList(), tap.toMaestroCommands(emptyMemory))
  }

  // --- AssertVisibleBySelectorTrailblazeTool ------------------------------------------

  @Test
  fun `assert nodeSelector lowers via toTrailblazeElementSelector`() {
    val assert = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Home"),
      ),
    )
    val command = assert.toMaestroCommands(emptyMemory).single()
    assertIs<AssertConditionCommand>(command)
    assertEquals("Home", command.condition.visible?.textRegex)
  }

  @Test
  fun `assert with no nodeSelector throws (malformed recording)`() {
    val assert = AssertVisibleBySelectorTrailblazeTool()
    assertFailsWith<IllegalStateException> {
      assert.toMaestroCommands(emptyMemory)
    }
  }

  // --- Blank-selector guards (lossy conversion safety) ---

  @Test
  fun `tap nodeSelector with driver-only predicates throws (lossy lowering guard)`() {
    // androidMaestro.classNameRegex doesn't survive the lowering to TrailblazeElementSelector
    // (the Maestro orchestra shape has no classNameRegex). Without a guard, the converted
    // selector would be blank, and Maestro would silently match an arbitrary element.
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(classNameRegex = "android.widget.Button"),
      ),
    )
    val ex = assertFailsWith<IllegalStateException> {
      tap.toMaestroCommands(emptyMemory)
    }
    assertContains(ex.message ?: "", "no matchable predicates")
  }

  @Test
  fun `assert nodeSelector with driver-only predicates throws (lossy lowering guard)`() {
    val assert = AssertVisibleBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View"),
      ),
    )
    val ex = assertFailsWith<IllegalStateException> {
      assert.toMaestroCommands(emptyMemory)
    }
    assertContains(ex.message ?: "", "no matchable predicates")
  }

  @Test
  fun `tap nodeSelector with structural-only fields does NOT trigger guard (containsChild rescues)`() {
    // The top-level driver block is empty (only classNameRegex which gets dropped), but the
    // structural containsChild carries a textRegex that survives the conversion. The outer
    // ElementSelector ends up with `containsChild` set AND its inner has a matchable
    // textRegex — recursive blank check passes, so no guard fires.
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(classNameRegex = "Button"),
        containsChild = TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "Submit"),
        ),
      ),
    )
    val command = tap.toMaestroCommands(emptyMemory).single() as TapOnElementCommand
    val inner = command.selector.containsChild
    assertNotNull(inner)
    assertEquals("Submit", inner.textRegex)
  }

  @Test
  fun `tap nodeSelector with NESTED blank inside containsChild throws (recursive guard)`() {
    // The outer ElementSelector has `containsChild` set (so a naive `== TrailblazeElementSelector()`
    // check would pass), but the inner containsChild lowers to a blank selector (its only
    // predicate, classNameRegex, drops). Recursive isBlank() must detect this — otherwise
    // Maestro would receive a `containsChild` with no inner predicates and match any element
    // with any child, defeating the guard.
    val tap = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        containsChild = TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(classNameRegex = "Button"),
        ),
      ),
    )
    val ex = assertFailsWith<IllegalStateException> {
      tap.toMaestroCommands(emptyMemory)
    }
    assertContains(ex.message ?: "", "no matchable predicates")
  }

  @Test
  fun `tap empty nodeSelector throws (recursive guard catches the trivial case)`() {
    // Empty nodeSelector — no driver match, no structural, no index. Lowers to a default
    // TrailblazeElementSelector(). Guard must catch this.
    val tap = TapOnByElementSelector(nodeSelector = TrailblazeNodeSelector())
    val ex = assertFailsWith<IllegalStateException> {
      tap.toMaestroCommands(emptyMemory)
    }
    assertContains(ex.message ?: "", "no matchable predicates")
  }
}
