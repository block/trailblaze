package xyz.block.trailblaze.cli.yaml

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Pins the shared selector-YAML emitter that both `ShortcutYamlEmitter` and
 * `WaypointSuggestSelectorCommand` route through. Three forcing functions are load-bearing:
 *
 *  1. [`emit covers every DriverNodeMatch AndroidAccessibility field`] — a maximal
 *     `AndroidAccessibility` (every field set) must show up in the emitted YAML
 *     field-by-field, so an emitter regression that drops a `?.let { … }` line is caught.
 *  2. [`MAXIMAL_FIELD_NAMES tracks AndroidAccessibility primary constructor`] —
 *     the hand-listed maximal field set is checked against the type's primary
 *     constructor, so a new field added to `AndroidAccessibility` fails this test
 *     with a clear "update both MAXIMAL_FIELD_NAMES and MAXIMAL_ANDROID_ACCESSIBILITY"
 *     message before the emitter coverage check fires.
 *  3. [`EMITTED_TOP_LEVEL_SELECTOR_SLOTS covers every recursive TrailblazeNodeSelector slot`] —
 *     filters the primary constructor's parameters **by type** (`TrailblazeNodeSelector?`
 *     or `List<TrailblazeNodeSelector>?`) so a new recursive slot like `inFrontOf` is
 *     caught even if the developer forgets to update any hand-listed set.
 *
 * Field-name assertions use [linesContainKey] rather than `String.contains` so a future
 * field whose name is a prefix of an existing one (`isChecked` vs a hypothetical
 * `isCheckedByDefault`) can't satisfy the assertion by substring-matching the old line.
 */
class TrailblazeNodeSelectorYamlEmitterTest {

  @Test
  fun `emit covers every DriverNodeMatch AndroidAccessibility field`() {
    val selector = TrailblazeNodeSelector(androidAccessibility = MAXIMAL_ANDROID_ACCESSIBILITY)
    val lines = emit(selector)
    for (name in MAXIMAL_FIELD_NAMES) {
      assertTrue(
        lines.linesContainKey(name),
        "emitter dropped DriverNodeMatch.AndroidAccessibility.$name from output. yaml=\n${lines.joinToString("\n")}",
      )
    }
  }

  @Test
  fun `MAXIMAL_FIELD_NAMES tracks AndroidAccessibility primary constructor`() {
    // Independent of the emitter — this checks that the hand-listed [MAXIMAL_FIELD_NAMES]
    // covers every primary-constructor parameter on the data class. If a new field
    // lands but isn't added to MAXIMAL_FIELD_NAMES (and a non-null value isn't set in
    // MAXIMAL_ANDROID_ACCESSIBILITY), the emitter-coverage test silently passes
    // because the field simply isn't checked. Splitting the failure mode into its own
    // assertion gives the developer a clear "update MAXIMAL_FIELD_NAMES /
    // MAXIMAL_ANDROID_ACCESSIBILITY" message before they hit the emitter coverage check.
    //
    // Uses `KClass.primaryConstructor` (not `.constructors.first()`) so a future
    // secondary constructor on the data class doesn't quietly start checking against
    // the wrong parameter list — `.constructors` ordering is JVM-impl-defined.
    val ctorParams = primaryCtorParameterNames(DriverNodeMatch.AndroidAccessibility::class)
    val missing = ctorParams - MAXIMAL_FIELD_NAMES.toSet()
    assertTrue(
      missing.isEmpty(),
      "AndroidAccessibility gained primary-constructor parameter(s) ${missing.toList()} " +
        "but TrailblazeNodeSelectorYamlEmitterTest.MAXIMAL_FIELD_NAMES + " +
        "MAXIMAL_ANDROID_ACCESSIBILITY weren't updated. Add the field(s) to both " +
        "(non-null value in MAXIMAL_ANDROID_ACCESSIBILITY, field name in " +
        "MAXIMAL_FIELD_NAMES), then wire the field through TrailblazeNodeSelectorYamlEmitter.",
    )
  }

  @Test
  fun `EMITTED_TOP_LEVEL_SELECTOR_SLOTS covers every recursive TrailblazeNodeSelector slot`() {
    // Type-based reflection forcing function (not name-based). Walks the primary
    // constructor of `TrailblazeNodeSelector` and accepts any parameter whose type is
    // `TrailblazeNodeSelector?` or `List<TrailblazeNodeSelector>?` — those are the
    // recursive slots the emitter is responsible for descending into. Adds `index`
    // explicitly (the only non-recursive parameter the emitter terminates with).
    // Driver-match parameters (`androidAccessibility`, `iosMaestro`, …) are NOT
    // in this set — they're handled by [requireSelectorIsEmittable] + the per-field
    // AndroidAccessibility tests above.
    //
    // Crucially: this filter does NOT consult any hand-listed name set. If a future
    // recursive slot like `inFrontOf: TrailblazeNodeSelector? = null` is added to
    // `TrailblazeNodeSelector`, it appears here automatically and the test fails
    // until EMITTED_TOP_LEVEL_SELECTOR_SLOTS is updated and the emitter learns to
    // descend into it.
    val expectedSlots = recursiveSelectorSlotNames(TrailblazeNodeSelector::class) + "index"
    val missing = expectedSlots - EMITTED_TOP_LEVEL_SELECTOR_SLOTS.toSet()
    assertTrue(
      missing.isEmpty(),
      "TrailblazeNodeSelector exposes recursive slot(s) ${missing.toList()} but " +
        "EMITTED_TOP_LEVEL_SELECTOR_SLOTS doesn't include them. Add them to the set " +
        "and confirm TrailblazeNodeSelectorYamlEmitter.emit descends into them.",
    )
  }

  @Test
  fun `emit walks every spatial + hierarchy child slot of TrailblazeNodeSelector`() {
    // Per-field-name companion to the AndroidAccessibility coverage tests, applied at
    // the parent level. The maximal selector below sets every recursive slot the
    // emitter must descend into; the assertion confirms each name appears in the
    // emitted YAML. Pairs with the reflection check above: that one catches "slot
    // added to type without test update," this one catches "slot in test but emitter
    // doesn't descend."
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^leaf$"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^cc$"),
      ),
      childOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^co$"),
      ),
      containsDescendants = listOf(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^cd$"),
        ),
      ),
      above = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^ab$"),
      ),
      below = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^be$"),
      ),
      leftOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^lo$"),
      ),
      rightOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^ro$"),
      ),
      index = 3,
    )
    val lines = emit(selector)
    for (name in EMITTED_TOP_LEVEL_SELECTOR_SLOTS) {
      assertTrue(
        lines.linesContainKey(name),
        "emitter did not produce a `$name:` line for the maximal selector. yaml=\n${lines.joinToString("\n")}",
      )
    }
  }

  @Test
  fun `emit fails fast on each unsupported driver matcher`() {
    // Loops over every unsupported driver so a regression that forgets to add a new
    // driver to the `listOfNotNull(...)` block in `requireSelectorIsEmittable` (or
    // accidentally removes one) fails here rather than going undetected.
    val unsupported: List<Pair<String, TrailblazeNodeSelector>> = listOf(
      "androidMaestro" to TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "^Foo$"),
      ),
      "iosMaestro" to TrailblazeNodeSelector(
        iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "^Foo$"),
      ),
      "iosAxe" to TrailblazeNodeSelector(iosAxe = DriverNodeMatch.IosAxe(labelRegex = "^Foo$")),
      "web" to TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaNameRegex = "^Foo$")),
      "compose" to TrailblazeNodeSelector(
        compose = DriverNodeMatch.Compose(textRegex = "^Foo$"),
      ),
    )
    for ((driverName, selector) in unsupported) {
      val ex = kotlin.runCatching {
        TrailblazeNodeSelectorYamlEmitter.emit(selector, indent = 0) { /* discard */ }
      }.exceptionOrNull()
      assertTrue(
        ex is IllegalArgumentException,
        "expected IllegalArgumentException for $driverName; got $ex",
      )
      assertTrue(
        ex.message?.contains(driverName) == true,
        "exception message for $driverName must name the unsupported matcher; got: ${ex.message}",
      )
    }
  }

  @Test
  fun `yamlQuote escapes backslashes and quotes minimally`() {
    assertEquals("\"plain\"", TrailblazeNodeSelectorYamlEmitter.yamlQuote("plain"))
    assertEquals("\"a\\\"b\"", TrailblazeNodeSelectorYamlEmitter.yamlQuote("""a"b"""))
    assertEquals("\"\\\\foo\"", TrailblazeNodeSelectorYamlEmitter.yamlQuote("""\foo"""))
  }

  @Test
  fun `yamlQuote preserves literal regex blocks unchanged except for backslash doubling`() {
    // Pin the kdoc contract: `\Q…\E` literal regex blocks must survive emission intact
    // (each backslash is doubled by the escape rule and re-collapsed by the YAML
    // parser on read). The runtime regex engine reads the parsed scalar, so the
    // round-trip end-state is exactly the source string.
    val literal = """^\Qapp.MyClass\E$"""
    val quoted = TrailblazeNodeSelectorYamlEmitter.yamlQuote(literal)
    // Each `\` in the source becomes `\\` in the YAML scalar; the YAML parser
    // collapses `\\` back to `\` on read, producing the original `\Q…\E` block.
    assertEquals(""""^\\Qapp.MyClass\\E${'$'}"""", quoted)
  }

  private fun emit(selector: TrailblazeNodeSelector): List<String> {
    val lines = mutableListOf<String>()
    TrailblazeNodeSelectorYamlEmitter.emit(selector, indent = 0) { lines.add(it) }
    return lines
  }

  /**
   * `true` if any line, after stripping leading indent, starts with `<key>:`. Stronger
   * than `String.contains("$key:")` because it rejects substring matches against
   * longer-named neighbours (e.g. `isChecked` matching inside a hypothetical
   * `isCheckedByDefault:` line).
   */
  private fun List<String>.linesContainKey(key: String): Boolean =
    any { it.trimStart().startsWith("$key:") }

  companion object {
    /**
     * Maximal `AndroidAccessibility` — every primary-constructor argument set to a
     * non-default value. If the type gains a new field, add it here (with a non-null
     * value) and to [MAXIMAL_FIELD_NAMES]; if you forget, the
     * `MAXIMAL_FIELD_NAMES tracks AndroidAccessibility primary constructor` test
     * fails with a clear pointer to both files.
     */
    private val MAXIMAL_ANDROID_ACCESSIBILITY = DriverNodeMatch.AndroidAccessibility(
      classNameRegex = "^cls$",
      resourceIdRegex = "^rid$",
      uniqueId = "uid-7",
      composeTestTagRegex = "^tag$",
      textRegex = "^txt$",
      contentDescriptionRegex = "^desc$",
      hintTextRegex = "^hint$",
      labeledByTextRegex = "^lbl$",
      stateDescriptionRegex = "^state$",
      paneTitleRegex = "^pane$",
      roleDescriptionRegex = "^role$",
      isEnabled = true,
      isClickable = true,
      isCheckable = false,
      isChecked = true,
      isSelected = false,
      isFocused = true,
      isEditable = false,
      isScrollable = true,
      isPassword = false,
      isHeading = true,
      isMultiLine = false,
      inputType = 33,
      collectionItemRowIndex = 4,
      collectionItemColumnIndex = 9,
    )

    private val MAXIMAL_FIELD_NAMES = listOf(
      "classNameRegex",
      "resourceIdRegex",
      "uniqueId",
      "composeTestTagRegex",
      "textRegex",
      "contentDescriptionRegex",
      "hintTextRegex",
      "labeledByTextRegex",
      "stateDescriptionRegex",
      "paneTitleRegex",
      "roleDescriptionRegex",
      "isEnabled",
      "isClickable",
      "isCheckable",
      "isChecked",
      "isSelected",
      "isFocused",
      "isEditable",
      "isScrollable",
      "isPassword",
      "isHeading",
      "isMultiLine",
      "inputType",
      "collectionItemRowIndex",
      "collectionItemColumnIndex",
    )

    /**
     * Every recursive / spatial-anchor slot the emitter is required to descend into,
     * plus the terminal `index:` slot. The
     * `EMITTED_TOP_LEVEL_SELECTOR_SLOTS covers every recursive TrailblazeNodeSelector slot`
     * test cross-checks this against `TrailblazeNodeSelector`'s primary constructor
     * **by type** — a new `TrailblazeNodeSelector?` / `List<TrailblazeNodeSelector>?`
     * parameter shows up automatically.
     */
    private val EMITTED_TOP_LEVEL_SELECTOR_SLOTS = listOf(
      "containsChild",
      "childOf",
      "containsDescendants",
      "above",
      "below",
      "leftOf",
      "rightOf",
      "index",
    )

    private fun primaryCtorParameterNames(kClass: KClass<*>): List<String> =
      (kClass.primaryConstructor ?: error("$kClass has no primary constructor"))
        .parameters
        .mapNotNull { it.name }

    /**
     * Returns the primary-constructor parameter names of [kClass] whose declared type is
     * `TrailblazeNodeSelector?` or `List<TrailblazeNodeSelector>?` — i.e. the recursive
     * slots that the YAML emitter must descend into. Type-based (not name-based) so a
     * future slot like `inFrontOf` is caught the moment it lands on the type.
     */
    private fun recursiveSelectorSlotNames(kClass: KClass<*>): List<String> {
      val ctor = kClass.primaryConstructor
        ?: error("$kClass has no primary constructor")
      return ctor.parameters.mapNotNull { p ->
        val t = p.type
        val classifier = t.classifier
        val isDirectSelector = classifier == TrailblazeNodeSelector::class
        val isSelectorList = classifier == List::class &&
          t.arguments.firstOrNull()?.type?.classifier == TrailblazeNodeSelector::class
        if (isDirectSelector || isSelectorList) p.name else null
      }
    }
  }
}
