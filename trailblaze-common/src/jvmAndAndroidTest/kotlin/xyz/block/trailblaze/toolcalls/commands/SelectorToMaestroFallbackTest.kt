package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.TapOnElementCommand
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Verifies the Maestro projection in [TapOnByElementSelector.toMaestroCommands] and
 * [AssertVisibleBySelectorTrailblazeTool.toMaestroCommands] for the three input shapes
 * a recording can take:
 *
 * 1. **Mixed-shape** (`selector` + `nodeSelector` both set): the legacy [selector] is
 *    the canonical projection — behavior is identical to the pre-fallback world.
 * 2. **nodeSelector-only** (`selector` null, `nodeSelector` set): the nodeSelector is
 *    lowered through [TrailblazeNodeSelector.toTrailblazeElementSelector] so the
 *    Maestro orchestra remains the unified resolver for any agent that doesn't natively
 *    resolve nodeSelectors. This unblocks migrating equivalent mixed-shape recordings.
 * 3. **Both null**: malformed recording — emit empty list ([TapOnByElementSelector]) or
 *    throw ([AssertVisibleBySelectorTrailblazeTool]), preserving existing fail-loud
 *    behavior so the absence of any selector is surfaced rather than silently no-op'd.
 */
class SelectorToMaestroFallbackTest {

  private val emptyMemory = AgentMemory()

  // --- TapOnByElementSelector ---------------------------------------------------------

  @Test
  fun `tap mixed-shape projects legacy selector (preserves pre-fallback behavior)`() {
    val tap = TapOnByElementSelector(
      selector = TrailblazeElementSelector(textRegex = "Legacy"),
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "FromNode"),
      ),
    )
    val command = tap.toMaestroCommands(emptyMemory).single()
    assertIs<TapOnElementCommand>(command)
    // Legacy wins — the textRegex on the Maestro command reflects [selector], not nodeSelector.
    assertEquals("Legacy", command.selector.textRegex)
  }

  @Test
  fun `tap nodeSelector-only lowers via toTrailblazeElementSelector`() {
    val tap = TapOnByElementSelector(
      selector = null,
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
  fun `tap nodeSelector-only with iosMaestro shape lowers correctly`() {
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
  fun `tap nodeSelector-only preserves structural fields (containsChild)`() {
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
  fun `tap both null emits empty command list (caller's contract)`() {
    val tap = TapOnByElementSelector()
    assertEquals(emptyList(), tap.toMaestroCommands(emptyMemory))
  }

  @Test
  fun `tap equivalent mixed-shape produces same Maestro command as nodeSelector-only`() {
    // Two recordings of "the same intent" — one carrying legacy + nodeSelector, the other
    // nodeSelector only. After this fallback lands, both produce identical Maestro
    // commands, which is the prerequisite for safely dropping the legacy field from
    // equivalent recordings in a follow-up migration.
    val text = "Submit"
    val mixed = TapOnByElementSelector(
      selector = TrailblazeElementSelector(textRegex = text),
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = text),
      ),
    )
    val nodeOnly = TapOnByElementSelector(
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = text),
      ),
    )
    val mixedCmd = mixed.toMaestroCommands(emptyMemory).single() as TapOnElementCommand
    val nodeOnlyCmd = nodeOnly.toMaestroCommands(emptyMemory).single() as TapOnElementCommand
    assertEquals(mixedCmd.selector.textRegex, nodeOnlyCmd.selector.textRegex)
    assertEquals(mixedCmd.longPress, nodeOnlyCmd.longPress)
  }

  // --- AssertVisibleBySelectorTrailblazeTool ------------------------------------------

  @Test
  fun `assert mixed-shape projects legacy selector`() {
    val assert = AssertVisibleBySelectorTrailblazeTool(
      selector = TrailblazeElementSelector(textRegex = "Legacy"),
      nodeSelector = TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "FromNode"),
      ),
    )
    val command = assert.toMaestroCommands(emptyMemory).single()
    assertIs<AssertConditionCommand>(command)
    assertEquals("Legacy", command.condition.visible?.textRegex)
  }

  @Test
  fun `assert nodeSelector-only lowers via toTrailblazeElementSelector`() {
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
  fun `assert both null throws (malformed recording)`() {
    val assert = AssertVisibleBySelectorTrailblazeTool()
    assertFailsWith<IllegalStateException> {
      assert.toMaestroCommands(emptyMemory)
    }
  }

  // --- Blank-selector guards (lossy conversion safety) ---

  @Test
  fun `tap nodeSelector-only with driver-only predicates throws (lossy lowering guard)`() {
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
  fun `assert nodeSelector-only with driver-only predicates throws (lossy lowering guard)`() {
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
  fun `tap nodeSelector-only with structural-only fields does NOT trigger guard (containsChild rescues)`() {
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
  fun `tap nodeSelector-only with NESTED blank inside containsChild throws (recursive guard)`() {
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

  // --- Round-trip equivalence (parameterized) ---

  /**
   * For every (legacy selector, equivalent nodeSelector) pair, asserts that the mixed-shape
   * recording and the nodeSelector-only recording produce IDENTICAL Maestro commands. This
   * is the property the safe-only YAML migration relies on: dropping `selector:` from
   * recordings where it equals `nodeSelector.toTrailblazeElementSelector()` cannot change
   * observable behavior because both paths reach the same Maestro orchestra call.
   */
  @Test
  fun `round-trip equivalence for varied selector shapes`() {
    data class Case(
      val name: String,
      val legacy: TrailblazeElementSelector,
      val node: TrailblazeNodeSelector,
    )

    val cases = listOf(
      Case(
        "textRegex only",
        TrailblazeElementSelector(textRegex = "Submit"),
        TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "Submit"),
        ),
      ),
      Case(
        "idRegex only (via resourceIdRegex)",
        TrailblazeElementSelector(idRegex = "com.app:id/button"),
        TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(resourceIdRegex = "com.app:id/button"),
        ),
      ),
      Case(
        "textRegex + idRegex + focused",
        TrailblazeElementSelector(textRegex = "Login", idRegex = "login_btn", focused = true),
        TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(
            textRegex = "Login",
            resourceIdRegex = "login_btn",
            focused = true,
          ),
        ),
      ),
      Case(
        "android state booleans",
        TrailblazeElementSelector(selected = true, enabled = false, checked = true),
        TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(
            selected = true,
            enabled = false,
            checked = true,
          ),
        ),
      ),
      Case(
        "iosMaestro shape",
        TrailblazeElementSelector(textRegex = "Save"),
        TrailblazeNodeSelector(
          iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Save"),
        ),
      ),
      Case(
        "containsChild structural with textRegex inner",
        TrailblazeElementSelector(
          containsChild = TrailblazeElementSelector(textRegex = "X"),
        ),
        TrailblazeNodeSelector(
          containsChild = TrailblazeNodeSelector(
            androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "X"),
          ),
        ),
      ),
      Case(
        "childOf structural",
        TrailblazeElementSelector(
          textRegex = "OK",
          childOf = TrailblazeElementSelector(textRegex = "Dialog"),
        ),
        TrailblazeNodeSelector(
          androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "OK"),
          childOf = TrailblazeNodeSelector(
            androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "Dialog"),
          ),
        ),
      ),
      Case(
        "index",
        TrailblazeElementSelector(index = "5"),
        TrailblazeNodeSelector(index = 5),
      ),
    )

    cases.forEach { case ->
      val mixed = TapOnByElementSelector(selector = case.legacy, nodeSelector = case.node)
      val nodeOnly = TapOnByElementSelector(nodeSelector = case.node)
      val mixedCmd = mixed.toMaestroCommands(emptyMemory).single() as TapOnElementCommand
      val nodeOnlyCmd = nodeOnly.toMaestroCommands(emptyMemory).single() as TapOnElementCommand
      assertEquals(
        mixedCmd.selector,
        nodeOnlyCmd.selector,
        "round-trip selector mismatch for case '${case.name}'",
      )
      assertEquals(
        mixedCmd.longPress,
        nodeOnlyCmd.longPress,
        "round-trip longPress mismatch for case '${case.name}'",
      )
    }
  }

  @Test
  fun `assert round-trip equivalence across varied shapes`() {
    // Mirrors the tap round-trip test but for assertVisible. Verifies the Condition.visible
    // ElementSelector matches between the mixed-shape and nodeSelector-only paths.
    val legacy = TrailblazeElementSelector(textRegex = "Home", idRegex = "home_tab")
    val node = TrailblazeNodeSelector(
      androidMaestro = DriverNodeMatch.AndroidMaestro(
        textRegex = "Home",
        resourceIdRegex = "home_tab",
      ),
    )
    val mixed = AssertVisibleBySelectorTrailblazeTool(selector = legacy, nodeSelector = node)
    val nodeOnly = AssertVisibleBySelectorTrailblazeTool(nodeSelector = node)
    val mixedCmd = mixed.toMaestroCommands(emptyMemory).single() as AssertConditionCommand
    val nodeOnlyCmd = nodeOnly.toMaestroCommands(emptyMemory).single() as AssertConditionCommand
    assertEquals(mixedCmd.condition.visible, nodeOnlyCmd.condition.visible)
  }
}
