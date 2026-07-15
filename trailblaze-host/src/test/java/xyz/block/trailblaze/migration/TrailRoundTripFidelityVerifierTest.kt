package xyz.block.trailblaze.migration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * The migrator reshapes recordings by decoding each tool into its typed model and re-encoding — a
 * lossy-looking step, because the encoder elides default-valued fields (`textMatchMode: EXACT`,
 * `CatalogMoney.currency: USD`) and re-types some scalars (a Maestro `timeout: 1000` emits as
 * `"1000"`). These tests pin the confidence guarantee that makes that safe: the emitted file DECODES
 * BACK to the identical typed tools, so the normalization is behavior-preserving and
 * [UnifiedTrailMigrator.Report.roundTripMismatches] stays empty — while a genuine value change is
 * caught.
 */
class TrailRoundTripFidelityVerifierTest {

  private val yaml = TrailblazeYaml.Default
  private val migrator = UnifiedTrailMigrator(yaml)

  private fun makeDir(vararg files: Pair<String, String>): File {
    val dir = Files.createTempDirectory("fidelity").toFile()
    for ((name, content) in files) File(dir, name).writeText(content)
    return dir
  }

  @Test
  fun `default-valued and numeric args round-trip with no fidelity mismatch`() {
    // `textMatchMode: EXACT` is the field's default (same mechanism as CatalogMoney.currency=USD),
    // and Maestro `timeout: 1000` is a numeric scalar Maestro re-coerces from a string. Both are
    // normalized on re-encode; both must decode back unchanged.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
        - prompts:
          - step: assert the item is visible
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Trailblaze Latte
                  expectedText: Trailblaze Latte
                  textMatchMode: EXACT
          - step: wait for the toast to clear
            recording:
              tools:
              - mobile_maestro:
                  commands:
                    - extendedWaitUntil:
                        notVisible: Gift card added
                        timeout: 1000
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    // The confidence guarantee: the file the migrator is about to write decodes back to exactly the
    // tools it intended. No mismatch means the normalization below is provably behavior-preserving.
    assertEquals(
      emptyList(),
      result.report.roundTripMismatches,
      "behavior-preserving normalization must not register as a fidelity loss",
    )

    // Document that the normalization genuinely happens in the emitted text (this is WHY a naive
    // text diff would false-positive, and why the check compares decoded objects instead).
    val emitted = yaml.encodeUnifiedTrailToString(trail = result.trail)
    assertFalse(emitted.contains("textMatchMode"), "EXACT default is elided on encode")
    assertTrue(emitted.contains("timeout: \"1000\""), "numeric timeout re-types to a quoted string")

    // And prove the round-trip directly: re-decoding the emitted file yields the identical typed
    // tools (textMatchMode restored to EXACT, timeout re-coerced) the migrator built.
    val reDecoded = yaml.decodeUnifiedTrail(emitted)
    assertEquals(result.trail.trail, reDecoded.trail)
  }

  @Test
  fun `nested raw-JSON tool args with mixed scalar types round-trip with no mismatch`() {
    // A seed-catalog-shaped tool (price {amount, currency}) NOT on this classpath decodes through the
    // OtherTrailblazeTool raw-JsonObject path, a different serializer than the typed one above. This is
    // where a nested numeric (amount: 450) or the number-vs-string distinction could produce a spurious
    // mismatch and falsely flag every real migration of such a tool. (A concrete typed version of this
    // shape — where the tool resolves to its real @Serializable model — is exercised on a downstream
    // classpath that has the tool on it.)
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
        - prompts:
          - step: seed a known catalog item
            recording:
              tools:
              - myApp_seedCatalog:
                  catalog:
                    items:
                    - tempId: fav-latte
                      name: Trailblaze Latte
                      price:
                        amount: 450
                        currency: USD
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    assertEquals(
      emptyList(),
      result.report.roundTripMismatches,
      "a nested raw-JSON tool (currency-shaped) must not register as a fidelity loss",
    )
    // And it genuinely round-trips: re-decoding the emitted file yields the identical raw args,
    // nested numeric and all.
    val emitted = yaml.encodeUnifiedTrailToString(trail = result.trail)
    assertTrue(emitted.contains("currency: USD"), "a non-default nested string is NOT elided")
    assertEquals(result.trail.trail, yaml.decodeUnifiedTrail(emitted).trail)
  }

  @Test
  fun `verify runs end-to-end and surfaces a finding when the emitted trail cannot re-parse`() {
    // Proves verify() is not a silent no-op: it really encodes, decodes, and reports. An empty trail
    // encodes to a document decodeUnifiedTrail rejects (a trail must have >= 1 step), so the decode
    // leg throws and verify() must degrade to a non-empty finding rather than swallowing it.
    val findings = TrailRoundTripFidelityVerifier.verify(
      yaml,
      xyz.block.trailblaze.yaml.unified.UnifiedTrail(
        config = xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig(id = "x/y"),
        trail = emptyList(),
      ),
    )
    assertTrue(
      findings.any { it.detail.contains("failed to re-parse") },
      "verify() must surface a re-parse failure rather than silently returning empty; got $findings",
    )
  }

  @Test
  fun `diff catches a real tool-arg value change`() {
    // A genuine loss: the re-decoded trail carries a different expectedText than intended. expectedText
    // is non-default, so it can't hide behind default elision — the verifier must report it.
    val intended = yaml.decodeUnifiedTrail(unifiedWithExpectedText("Hello"))
    val tampered = yaml.decodeUnifiedTrail(unifiedWithExpectedText("Goodbye"))

    val findings = TrailRoundTripFidelityVerifier.diff(intended = intended, reDecoded = tampered)

    assertTrue(
      findings.any { it.location.contains("recording") },
      "a changed tool arg must be reported as a recording mismatch; got $findings",
    )
  }

  @Test
  fun `diff catches a config field change`() {
    val intended = yaml.decodeUnifiedTrail(unifiedWithTarget("targetA"))
    val tampered = yaml.decodeUnifiedTrail(unifiedWithTarget("targetB"))

    val findings = TrailRoundTripFidelityVerifier.diff(intended = intended, reDecoded = tampered)

    assertTrue(findings.any { it.location == "config" }, "a changed config field must be reported; got $findings")
  }

  @Test
  fun `diff is empty for identical trails`() {
    val trail = yaml.decodeUnifiedTrail(unifiedWithExpectedText("Hello"))
    assertEquals(emptyList(), TrailRoundTripFidelityVerifier.diff(intended = trail, reDecoded = trail))
  }

  @Test
  fun `diff reports mismatched classifiers in a stable sorted order`() {
    // Two classifiers both mismatch. Their order in the finding list must be deterministic (sorted),
    // not whatever the recordings map happens to iterate — otherwise the emitted WARNING block and CI
    // diffs are noisy. The intended step lists them z-first to prove the output isn't just echoing input order.
    val intended = yaml.decodeUnifiedTrail(twoClassifierStep(text = "Hello", first = "z-phone", second = "a-phone"))
    val tampered = yaml.decodeUnifiedTrail(twoClassifierStep(text = "Goodbye", first = "z-phone", second = "a-phone"))

    val locations = TrailRoundTripFidelityVerifier.diff(intended = intended, reDecoded = tampered)
      .map { it.location }
      .filter { it.contains("recording[") }

    assertEquals(
      listOf("step 1 · recording[a-phone]", "step 1 · recording[z-phone]"),
      locations,
      "classifier mismatches must be reported in sorted order regardless of map iteration order",
    )
  }

  private fun unifiedWithExpectedText(text: String): String = """
    config:
      id: x/y
      target: x
    trail:
      - step: assert
        recording:
          android-phone:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: $text
                expectedText: $text
  """.trimIndent()

  private fun twoClassifierStep(text: String, first: String, second: String): String = """
    config:
      id: x/y
      target: x
    trail:
      - step: assert
        recording:
          $first:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: $text
                expectedText: $text
          $second:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: $text
                expectedText: $text
  """.trimIndent()

  private fun unifiedWithTarget(target: String): String = """
    config:
      id: x/y
      target: $target
    trail:
      - step: assert
        recording:
          android-phone:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Hello
                expectedText: Hello
  """.trimIndent()
}
