package xyz.block.trailblaze.cli.yaml

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Pins the shared selector-YAML emitter that both `ShortcutYamlEmitter` and
 * `WaypointSuggestSelectorCommand` route through. Three forcing functions are load-bearing:
 *
 *  1. [`emit covers every field of every supported driver matcher`] — for each driver
 *     matcher in [SUPPORTED_DIALECTS], a maximal instance (every field set) must show up
 *     in the emitted YAML field-by-field, so an emitter regression that drops a
 *     `?.let { … }` line is caught. Expected field names come from the type's primary
 *     constructor, so a newly added field is checked without any hand-listed set to update.
 *  2. [`maximal driver matchers set every primary-constructor field`] — a new field on
 *     one of those types fails here first, with a clear "set a non-null value in the
 *     maximal fixture" message, rather than being silently unchecked by (1).
 *  3. [`EMITTED_TOP_LEVEL_SELECTOR_SLOTS covers every recursive TrailblazeNodeSelector slot`] —
 *     filters the primary constructor's parameters **by type** (`TrailblazeNodeSelector?`
 *     or `List<TrailblazeNodeSelector>?`) so a new recursive slot like `inFrontOf` is
 *     caught even if the developer forgets to update any hand-listed set.
 *
 * `compose` / `web` / `androidMaestro` have no field ladder yet, so their no-silent-drop
 * guard is [`emit fails fast on each unsupported driver matcher`] instead — the emitter
 * must refuse them rather than emit a selector missing the constraint.
 *
 * Field-name assertions use [linesContainKey] rather than `String.contains` so a future
 * field whose name is a prefix of an existing one (`isChecked` vs a hypothetical
 * `isCheckedByDefault`) can't satisfy the assertion by substring-matching the old line.
 */
class TrailblazeNodeSelectorYamlEmitterTest {

  @Test
  fun `emit covers every field of every supported driver matcher`() {
    for (dialect in SUPPORTED_DIALECTS) {
      val lines = emit(dialect.selector)
      assertTrue(
        lines.linesContainKey(dialect.selectorKey),
        "missing ${dialect.selectorKey}: header. yaml=\n${lines.joinToString("\n")}",
      )
      for (name in primaryCtorParameterNames(dialect.maximal::class)) {
        assertTrue(
          lines.linesContainKey(name),
          "emitter dropped ${dialect.typeName}.$name from output. yaml=\n${lines.joinToString("\n")}",
        )
      }
    }
  }

  @Test
  fun `maximal driver matchers set every primary-constructor field`() {
    // Independent of the emitter — this checks the maximal fixtures actually populate
    // every primary-constructor parameter on their type. A field left null is a field
    // the emitter-coverage test above can't see, so the coverage check would silently
    // pass while the emitter drops the constraint from every generated YAML.
    //
    // Uses `KClass.primaryConstructor` (not `.constructors.first()`) so a future
    // secondary constructor on the data class doesn't quietly start checking against
    // the wrong parameter list — `.constructors` ordering is JVM-impl-defined.
    for (dialect in SUPPORTED_DIALECTS) {
      val unset = primaryCtorParameterNames(dialect.maximal::class)
        .filter { fieldValue(dialect.maximal, it) == null }
      assertTrue(
        unset.isEmpty(),
        "${dialect.typeName} parameter(s) $unset are null in the maximal fixture in " +
          "TrailblazeNodeSelectorYamlEmitterTest. Set a non-null value for each, then wire " +
          "the field(s) through TrailblazeNodeSelectorYamlEmitter.",
      )
    }
  }

  @Test
  fun `EMITTED_TOP_LEVEL_SELECTOR_SLOTS covers every recursive TrailblazeNodeSelector slot`() {
    // Type-based reflection forcing function (not name-based). Walks the primary
    // constructor of `TrailblazeNodeSelector` and accepts any parameter whose type is
    // `TrailblazeNodeSelector?` or `List<TrailblazeNodeSelector>?` — those are the
    // recursive slots the emitter is responsible for descending into. Adds `index`
    // explicitly (the only non-recursive parameter the emitter terminates with).
    // Driver-match parameters (`androidAccessibility`, `iosMaestro`, …) are NOT
    // in this set — they're handled by [requireSelectorIsEmittable] + the per-dialect
    // field-coverage tests above.
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
    // Per-field-name companion to the per-dialect coverage tests, applied at
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
  fun `inputType 0 is dropped only on the dialect whose generator defaults it`() {
    // The two dialects disagree on what `inputType: 0` means, and the emitter has to follow
    // each one. `androidAccessibility`'s generator writes 0 as its "no input type" default, so
    // emitting it would pin a constraint the author never wrote. No `androidView` strategy sets
    // `inputType` at all, so a 0 there is deliberate and the resolver honours it —
    // `requireEqual(0, detail.inputType)` matches exactly the views that take no text input.
    // Dropping it would emit YAML matching a wider set than the selector handed in.
    val accessibility = emit(
      TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = "^rid$",
          inputType = 0,
        ),
      ),
    )
    assertFalse(
      accessibility.linesContainKey("inputType"),
      "androidAccessibility must drop its generator's default. yaml=\n${accessibility.joinToString("\n")}",
    )

    val view = emit(
      TrailblazeNodeSelector(
        androidView = DriverNodeMatch.AndroidView(resourceIdRegex = "^rid$", inputType = 0),
      ),
    )
    assertTrue(
      view.any { it.trim() == "inputType: 0" },
      "androidView must keep a deliberate 0. yaml=\n${view.joinToString("\n")}",
    )
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
     * non-default value. If the type gains a new field, add it here with a non-null
     * value; if you forget, the
     * `maximal driver matchers set every primary-constructor field` test fails and
     * names the field.
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

    /** Maximal `AndroidView` — every primary-constructor argument set non-default. */
    private val MAXIMAL_ANDROID_VIEW = DriverNodeMatch.AndroidView(
      classNameRegex = "^cls$",
      resourceIdRegex = "^rid$",
      tagRegex = "^tag$",
      textRegex = "^txt$",
      contentDescriptionRegex = "^desc$",
      hintTextRegex = "^hint$",
      stateDescriptionRegex = "^state$",
      errorTextRegex = "^err$",
      isEnabled = true,
      isClickable = true,
      isChecked = false,
      isSelected = true,
      isFocused = false,
      isEditable = true,
      isPassword = false,
      inputType = 33,
    )

    /** Maximal `IosMaestro` — every primary-constructor argument set non-default. */
    private val MAXIMAL_IOS_MAESTRO = DriverNodeMatch.IosMaestro(
      textRegex = "^txt$",
      resourceIdRegex = "^rid$",
      accessibilityTextRegex = "^a11y$",
      classNameRegex = "^cls$",
      hintTextRegex = "^hint$",
      focused = true,
      selected = false,
    )

    /** Maximal `IosAxe` — every primary-constructor argument set non-default. */
    private val MAXIMAL_IOS_AXE = DriverNodeMatch.IosAxe(
      roleRegex = "^AXButton$",
      subroleRegex = "^AXSecureTextField$",
      labelRegex = "^lbl$",
      valueRegex = "^val$",
      uniqueId = "uid-7",
      typeRegex = "^Button$",
      titleRegex = "^title$",
      customAction = "activate",
      enabled = true,
    )

    /**
     * One entry per driver matcher the emitter has a field ladder for. Adding a driver
     * to [TrailblazeNodeSelectorYamlEmitter] means adding it here — the emitter's
     * [requireSelectorIsEmittable] guard (pinned by
     * `emit fails fast on each unsupported driver matcher`) covers the rest.
     */
    private val SUPPORTED_DIALECTS = listOf(
      Dialect(
        selectorKey = "androidAccessibility",
        maximal = MAXIMAL_ANDROID_ACCESSIBILITY,
        selector = TrailblazeNodeSelector(androidAccessibility = MAXIMAL_ANDROID_ACCESSIBILITY),
      ),
      Dialect(
        selectorKey = "androidView",
        maximal = MAXIMAL_ANDROID_VIEW,
        selector = TrailblazeNodeSelector(androidView = MAXIMAL_ANDROID_VIEW),
      ),
      Dialect(
        selectorKey = "iosMaestro",
        maximal = MAXIMAL_IOS_MAESTRO,
        selector = TrailblazeNodeSelector(iosMaestro = MAXIMAL_IOS_MAESTRO),
      ),
      Dialect(
        selectorKey = "iosAxe",
        maximal = MAXIMAL_IOS_AXE,
        selector = TrailblazeNodeSelector(iosAxe = MAXIMAL_IOS_AXE),
      ),
    )

    private class Dialect(
      val selectorKey: String,
      val maximal: DriverNodeMatch,
      val selector: TrailblazeNodeSelector,
    ) {
      val typeName: String get() = "DriverNodeMatch.${maximal::class.simpleName}"
    }

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

    private fun fieldValue(match: DriverNodeMatch, fieldName: String): Any? {
      val property = match::class.memberProperties.firstOrNull { it.name == fieldName }
        ?: error("${match::class} has no property named $fieldName")
      @Suppress("UNCHECKED_CAST")
      return (property as KProperty1<DriverNodeMatch, Any?>).get(match)
    }

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
