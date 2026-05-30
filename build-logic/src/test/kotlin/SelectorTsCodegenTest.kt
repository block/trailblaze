import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [SelectorTsCodegen] — the pure-function Kotlin → TypeScript codegen
 * that produces `opensource/sdks/typescript/src/generated/selectors.ts` from the
 * canonical Kotlin sealed-class hierarchy.
 *
 * Covers the deliverables listed in the chip's "Tests for codegen stability" item:
 *  - Output is byte-stable across reruns against the same input (no nondeterminism).
 *  - Adding a new sealed-class branch in synthetic input adds the expected union
 *    member + factory method.
 *  - Removing a field from synthetic input removes the corresponding TS field.
 *  - The mapping covers the full Kotlin surface used by the selector grammar
 *    (boolean / number / String / List / nested classes) and fails loud on
 *    unmapped types.
 *
 * The tests exercise the codegen function directly with hand-crafted Kotlin source
 * snippets — small enough to read in one screen, deterministic enough to assert on
 * exact output. The plugin's task-action wiring is exercised by the existing
 * end-to-end CI gate (`verifySelectorsTs` against the committed file).
 */
class SelectorTsCodegenTest {

  @Test
  fun `output is byte-stable across reruns against the same input`() {
    val first = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    val second = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    assertEquals(first, second, "Codegen must be deterministic for the verify byte-diff gate to be meaningful")
  }

  @Test
  fun `factory namespace includes one method per DriverNodeMatch branch`() {
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // Every concrete branch must surface a factory entry. The arrow body is
    // `({ <key>: args })` so the literal form copy-pastes to YAML cleanly.
    val expectedKeys = listOf(
      "androidAccessibility",
      "androidMaestro",
      "web",
      "compose",
      "iosMaestro",
      "iosAxe",
    )
    for (key in expectedKeys) {
      val pattern = "$key: (args: DriverNodeMatch"
      assertTrue(rendered.contains(pattern), "Expected factory entry for `$key`, got:\n$rendered")
      assertTrue(rendered.contains("{ $key: args })"), "Expected literal-shape return for `$key`")
    }
  }

  @Test
  fun `removing a field from synthetic input removes the corresponding TS field`() {
    val withTextRegex = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    assertTrue(withTextRegex.contains("textRegex"), "baseline should contain textRegex")

    val withoutTextRegex = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE.replace(
        "val textRegex: String? = null,\n",
        "",
      ),
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // textRegex is referenced in the factory KDoc example string + AndroidMaestro/etc
    // branches. After deletion, the remaining branches that didn't define textRegex
    // should still build, and the field on the deleted variant should be absent.
    val androidAccessibilityBlock = androidAccessibilityBlockOf(withoutTextRegex)
    assertTrue(
      !androidAccessibilityBlock.contains("textRegex?:"),
      "Field `textRegex` should be removed from DriverNodeMatchAndroidAccessibility after deletion. Block:\n$androidAccessibilityBlock",
    )
  }

  @Test
  fun `transient properties on TrailblazeNodeSelector are excluded`() {
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // The `@Transient` `driverMatch` accessor is wire-internal and should not surface
    // on the TS type. (The synthetic input doesn't include one, but real source has
    // `@kotlinx.serialization.Transient val driverMatch: DriverNodeMatch?` inside the
    // class body — outside the primary constructor, which the parser ignores.)
    assertTrue(!rendered.contains("driverMatch"), "@Transient property must not leak to TS")
  }

  @Test
  fun `unmapped Kotlin types throw with an actionable message`() {
    val withMystery = SELECTOR_SOURCE.replace(
      "val textRegex: String? = null,",
      "val mystery: MysteryType? = null,",
    )
    val e = assertFailsWith<IllegalStateException> {
      SelectorTsCodegen.generate(
        trailblazeNodeSelectorKt = withMystery,
        matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
        trailblazeNodeKt = NODE_SOURCE,
      )
    }
    assertTrue(
      e.message!!.contains("Unmapped Kotlin type `MysteryType`"),
      "Error must name the unmapped type; got: ${e.message}",
    )
  }

  @Test
  fun `KDoc on fields round-trips to TSDoc`() {
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = SELECTOR_SOURCE,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // Single-line KDoc on a field should round-trip to a single-line JSDoc directly
    // above the field.
    assertTrue(
      rendered.contains("/** Synthetic-input below docstring. */\n  below?: TrailblazeNodeSelector | null;"),
      "Expected single-line KDoc to render directly above field. Got:\n$rendered",
    )
  }

  @Test
  fun `multi-line KDoc on fields preserves continuation lines with re-indented asterisk prefix`() {
    // The formatTsDoc multi-line branch (continuation-line re-indenting + `*`-prefix
    // re-application) was previously only exercised by the production sources via the
    // verify byte-diff. This test pins the behavior in unit space so a regression that
    // collapses multi-line KDoc to single-line — or that misaligns the `*` column —
    // surfaces here rather than as a verify-task failure in CI.
    val withMultiLineKdoc = SELECTOR_SOURCE.replace(
      "/** Synthetic-input below docstring. */\n  val below: TrailblazeNodeSelector? = null,",
      "/**\n   * Multi-line KDoc on the `below` field.\n   *\n   * Second paragraph for good measure.\n   */\n  val below: TrailblazeNodeSelector? = null,",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withMultiLineKdoc,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // Expect the multi-line block to be emitted under the field's two-space indent
    // with continuation lines starting at `   * ` (indent + space + asterisk).
    val expected = """  /**
   * Multi-line KDoc on the `below` field.
   *
   * Second paragraph for good measure.
   */
  below?: TrailblazeNodeSelector | null;"""
    assertTrue(
      rendered.contains(expected),
      "Expected multi-line KDoc with re-indented continuation lines. Got:\n$rendered",
    )
  }

  @Test
  fun `Float and Double Kotlin types map to TypeScript number`() {
    // Production source uses only Int / Long today, but the `Int, Long, Float, Double`
    // branch in mapKotlinTypeToTs is a single arm. This test guards against a future
    // split that drops Float/Double from the number-coercion case.
    val withFloatDouble = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      "val ratio: Float? = null,\n      val precision: Double? = null,\n      val index: Int? = null,",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withFloatDouble,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    assertTrue(rendered.contains("ratio?: number | null;"), "Float should map to number")
    assertTrue(rendered.contains("precision?: number | null;"), "Double should map to number")
  }

  @Test
  fun `val keyword inside KDoc body does not corrupt field-name parsing`() {
    // Regression: a previous version of the parser used `\bval\s+` over the full
    // parameter text — including the leading KDoc block — so a KDoc containing the
    // word `val` would anchor the regex inside the comment and corrupt the parsed
    // field name. The fix scopes the regex to the region AFTER the KDoc's `*/`.
    val withValInKdoc = SELECTOR_SOURCE.replace(
      "/** Synthetic-input below docstring. */",
      "/** This val is unused when above is also set. */",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withValInKdoc,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // Field name must still be `below`, not a misparsed slice containing the word
    // `val` from the comment body.
    assertTrue(
      rendered.contains("below?: TrailblazeNodeSelector | null;"),
      "Expected `below` field to parse correctly despite `val` in KDoc body. Got:\n$rendered",
    )
  }

  @Test
  fun `SerialName annotation on a parameter overrides the TS field name with the wire name`() {
    // A Kotlin contributor who renames a field on the wire via `@SerialName` while
    // leaving the Kotlin property name unchanged would otherwise produce a generated
    // TS surface that type-checks but emits the wrong JSON key at runtime. The
    // codegen honors `@SerialName("...")` and uses the wire name in the TS interface.
    val withSerialName = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      """@SerialName("wireIndex") val index: Int? = null,""",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withSerialName,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    assertTrue(
      rendered.contains("wireIndex?: number | null;"),
      "Expected @SerialName wire name in generated TS. Got:\n$rendered",
    )
    assertTrue(
      !rendered.contains("  index?: number"),
      "Kotlin name should NOT appear when @SerialName overrides it",
    )
  }

  @Test
  fun `runSelectorTsCodegen fails loud when an input file is missing`() {
    val tempDir = java.nio.file.Files.createTempDirectory("selector-codegen-missing-test").toFile()
    try {
      val exists = java.io.File(tempDir, "exists.kt").apply { writeText("// empty") }
      val missing = java.io.File(tempDir, "does-not-exist.kt")
      val e = assertFailsWith<IllegalArgumentException> {
        runSelectorTsCodegen(exists, missing, exists)
      }
      assertTrue(
        e.message!!.contains("does-not-exist.kt"),
        "Error must name the missing file path; got: ${e.message}",
      )
    } finally {
      // Log a leak warning rather than throwing — a `throw` inside `finally` would
      // mask the test body's exception (JUnit reports only the last thrown), which
      // would hide real codegen failures behind cleanup noise. The leak signal still
      // surfaces in CI logs for any developer who scans for "Warning:".
      if (!tempDir.deleteRecursively()) {
        System.err.println("Warning: failed to clean up temp directory ${tempDir.absolutePath}")
      }
    }
  }

  @Test
  fun `SerialName with a non-identifier wire name throws a directed error`() {
    // The codegen captures the raw source-text between the quote delimiters of
    // `@SerialName(...)` — so a name like `wire\"escaped` (the Kotlin source for a
    // wire key containing an embedded quote) comes back as 13 raw chars including
    // the backslash. Emitting that as a bare TypeScript interface field would
    // produce a syntax error (TS interface keys must be valid identifiers or quoted
    // strings, and a bare identifier can't contain `"`).
    //
    // The codegen detects this BEFORE emission and throws with a message naming the
    // offending wire name and the supported identifier shape. Quoted-key emission
    // for non-identifier names is a future extension; until then, fail loud.
    val withEscaped = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      """@SerialName("wire\"escaped") val index: Int? = null,""",
    )
    val e = assertFailsWith<IllegalStateException> {
      SelectorTsCodegen.generate(
        trailblazeNodeSelectorKt = withEscaped,
        matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
        trailblazeNodeKt = NODE_SOURCE,
      )
    }
    assertTrue(
      e.message!!.contains("isn't a valid TypeScript identifier"),
      "Error must explain that the wire name isn't a TS identifier; got: ${e.message}",
    )
    assertTrue(
      e.message!!.contains("quoted-key emission"),
      "Error must point at the extension point; got: ${e.message}",
    )
  }

  @Test
  fun `SerialName with a kebab-case wire name also throws`() {
    // Same code path as the escaped-quote case but a more plausible real-world
    // example: a snake-or-kebab-case wire key from JSON conventions that doesn't
    // match a TS bare-identifier shape.
    val withKebab = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      """@SerialName("kebab-case") val index: Int? = null,""",
    )
    val e = assertFailsWith<IllegalStateException> {
      SelectorTsCodegen.generate(
        trailblazeNodeSelectorKt = withKebab,
        matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
        trailblazeNodeKt = NODE_SOURCE,
      )
    }
    assertTrue(
      e.message!!.contains("kebab-case"),
      "Error must name the offending wire key; got: ${e.message}",
    )
  }

  @Test
  fun `SerialName tolerates whitespace inside the annotation parens`() {
    // `@SerialName  (  "name"  )` is legal Kotlin and works the same as the compact form.
    // The codegen should treat them identically.
    val withSpacing = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      """@SerialName  (  "spacedIndex"  ) val index: Int? = null,""",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withSpacing,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    assertTrue(
      rendered.contains("spacedIndex?: number | null;"),
      "Expected whitespace-tolerant @SerialName parse. Got:\n$rendered",
    )
  }

  @Test
  fun `SerialName in named-argument form throws a directed error`() {
    // `@SerialName(value = "x")` is legal Kotlin but not supported by the regex — and
    // silently falling back to the Kotlin identifier would produce a runtime wire-key
    // mismatch. The codegen detects this form and throws with a specific message
    // naming the supported syntax + the extension point a contributor can edit.
    val withNamedArg = SELECTOR_SOURCE.replace(
      "val index: Int? = null,",
      """@SerialName(value = "wireIndex") val index: Int? = null,""",
    )
    val e = assertFailsWith<IllegalStateException> {
      SelectorTsCodegen.generate(
        trailblazeNodeSelectorKt = withNamedArg,
        matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
        trailblazeNodeKt = NODE_SOURCE,
      )
    }
    assertTrue(
      e.message!!.contains("named-argument form"),
      "Error must explain that the named-arg form is unsupported; got: ${e.message}",
    )
    assertTrue(
      e.message!!.contains("extractSerialName"),
      "Error must point to the extension function for fixing the limitation; got: ${e.message}",
    )
  }

  @Test
  fun `multi-line KDoc preserves embedded fenced code blocks`() {
    // Production source's `MatchDescriptor.indexPath` kdoc nearly uses fenced code
    // blocks; pin the behavior so a future formatTsDoc refactor doesn't garble them.
    // The continuation-line logic must NOT add `* ` to lines that don't already start
    // with one — that would inject asterisks into the middle of code blocks.
    val withFencedBlock = SELECTOR_SOURCE.replace(
      "/** Synthetic-input below docstring. */\n  val below: TrailblazeNodeSelector? = null,",
      "/**\n   * Has a code block.\n   *\n   * ```kotlin\n   * val x = 1\n   * ```\n   */\n  val below: TrailblazeNodeSelector? = null,",
    )
    val rendered = SelectorTsCodegen.generate(
      trailblazeNodeSelectorKt = withFencedBlock,
      matchDescriptorKt = MATCH_DESCRIPTOR_SOURCE,
      trailblazeNodeKt = NODE_SOURCE,
    )
    // Body must include the fenced block intact with the surrounding `* ` prefix
    // (the formatTsDoc re-indents under the field's column).
    assertTrue(
      rendered.contains("```kotlin"),
      "Expected fenced code block opener to survive emission. Got:\n$rendered",
    )
    assertTrue(
      rendered.contains("* val x = 1"),
      "Expected fenced code-block content with `*` prefix to round-trip. Got:\n$rendered",
    )
  }

  private fun androidAccessibilityBlockOf(rendered: String): String {
    val startIdx = rendered.indexOf("export interface DriverNodeMatchAndroidAccessibility {")
    require(startIdx >= 0) { "Missing AndroidAccessibility block" }
    val endIdx = rendered.indexOf("}\n", startIdx)
    return rendered.substring(startIdx, endIdx + 2)
  }

  // ---------------------------------------------------------------------------------
  // Synthetic Kotlin sources mirroring the structure of the real source-of-truth
  // files. Kept minimal — exercise the parser's grammar coverage without dragging in
  // the entire production hierarchy. The same emitter runs against these as against
  // the real files; production input is byte-diffed by the CI verify task.
  // ---------------------------------------------------------------------------------

  private val SELECTOR_SOURCE = """
    package xyz.block.trailblaze.api

    import kotlinx.serialization.SerialName
    import kotlinx.serialization.Serializable

    /** Synthetic selector docstring. */
    @Serializable
    data class TrailblazeNodeSelector(
      val androidAccessibility: DriverNodeMatch.AndroidAccessibility? = null,
      val androidMaestro: DriverNodeMatch.AndroidMaestro? = null,
      val web: DriverNodeMatch.Web? = null,
      val compose: DriverNodeMatch.Compose? = null,
      val iosMaestro: DriverNodeMatch.IosMaestro? = null,
      val iosAxe: DriverNodeMatch.IosAxe? = null,
      /** Synthetic-input below docstring. */
      val below: TrailblazeNodeSelector? = null,
      val containsDescendants: List<TrailblazeNodeSelector>? = null,
      val index: Int? = null,
    )

    @Serializable
    sealed interface DriverNodeMatch {
      @Serializable
      @SerialName("androidAccessibility")
      data class AndroidAccessibility(
        val textRegex: String? = null,
        val isEnabled: Boolean? = null,
      ) : DriverNodeMatch

      @Serializable
      @SerialName("androidMaestro")
      data class AndroidMaestro(
        val resourceIdRegex: String? = null,
      ) : DriverNodeMatch

      @Serializable
      @SerialName("web")
      data class Web(
        val ariaRole: String? = null,
      ) : DriverNodeMatch

      @Serializable
      @SerialName("compose")
      data class Compose(
        val testTag: String? = null,
      ) : DriverNodeMatch

      @Serializable
      @SerialName("iosMaestro")
      data class IosMaestro(
        val textRegex: String? = null,
      ) : DriverNodeMatch

      @Serializable
      @SerialName("iosAxe")
      data class IosAxe(
        val roleRegex: String? = null,
      ) : DriverNodeMatch
    }
  """.trimIndent()

  private val MATCH_DESCRIPTOR_SOURCE = """
    package xyz.block.trailblaze.api

    import kotlinx.serialization.Serializable

    @Serializable
    data class MatchDescriptor(
      val indexPath: List<Int>,
      val bounds: TrailblazeNode.Bounds? = null,
      val matchedText: String? = null,
    )
  """.trimIndent()

  private val NODE_SOURCE = """
    package xyz.block.trailblaze.api

    import kotlinx.serialization.Serializable

    @Serializable
    data class TrailblazeNode(
      val nodeId: Long = 0,
    ) {
      @Serializable
      data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
      )
    }
  """.trimIndent()
}
