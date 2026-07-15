package xyz.block.trailblaze.migration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.trailrunner.BundleMigration
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * The other migrator tests prove the round-trip fidelity verifier stays QUIET on real tools (no
 * false positives) and that its pure `diff` catches a tampered object. What none of them can prove
 * is the thing the feature actually exists for: that a genuine serializer that fails to round-trip a
 * value is caught **end-to-end** — through the real `migrate()` decode → build → re-encode → re-decode
 * chain and the real `BundleMigration.migrateFolder` input-retention decision.
 *
 * It can't be proven with a real tool, because no shipped tool has a lossy serializer (if one did we'd
 * fix it, not test against it). So this test injects a synthetic [LossyTestTool] whose serializer
 * deliberately drops a non-default field on encode, registers it on a real [createTrailblazeYaml]
 * instance (Default + this one tool, no global mutation), and runs the actual migration. This is the
 * regression guard: if the verifier were ever wired out of `migrate()`, or the bundle path stopped
 * treating a mismatch as lossy, these fail.
 */
class LossyToolRoundTripDetectionTest {

  // Default + the one lossy tool, on a real yaml instance. The migrator and the fidelity verifier
  // share THIS instance, so the lossy serializer is active on both the encode and re-decode legs.
  private val yaml = createTrailblazeYaml(customTrailblazeToolClasses = setOf(LossyTestTool::class))
  private val migrator = UnifiedTrailMigrator(yaml)

  private fun makeDir(fileName: String, content: String): File {
    val dir = Files.createTempDirectory("lossy-tool").toFile()
    File(dir, fileName).writeText(content.trimIndent())
    return dir
  }

  /** A recording whose `lossyTestTool` carries a NON-default `dropped` value — the value that is lost. */
  private fun bundleText(dropped: String) = """
    - config:
        id: x/y
        target: x
        platform: android
    - prompts:
      - step: exercise the lossy tool
        recording:
          tools:
          - lossyTestTool:
              kept: survives
              dropped: $dropped
  """

  @Test
  fun `migrate surfaces a round-trip fidelity mismatch when a tool serializer drops a non-default value`() {
    val dir = makeDir("android-phone.trail.yaml", bundleText(dropped = "must-survive"))

    val result = migrator.migrate(dir)

    // The migrator decoded LossyTestTool(kept, dropped="must-survive"), but re-encoding drops
    // `dropped`, so re-decoding restores the default — a genuine, behavior-changing loss.
    val mismatches = result.report.roundTripMismatches
    assertTrue(
      mismatches.any { it.location.contains("recording[android-phone]") },
      "a serializer that drops a non-default value must be caught end-to-end through migrate(); got $mismatches",
    )
    // And it flips the shared lossy decision the CLI gate and bundle retention both read.
    assertTrue(
      UnifiedTrailMigrator.isLossyMigration(result.report),
      "a round-trip mismatch must make the migration count as lossy",
    )
    // The detail names the intended value that was lost, so a reviewer can see WHAT changed.
    assertTrue(
      mismatches.any { it.detail.contains("must-survive") },
      "the mismatch detail should surface the lost value; got ${mismatches.map { it.detail }}",
    )
  }

  @Test
  fun `migrate does NOT flag a mismatch when the dropped field is already at its default`() {
    // Control: the serializer omits `dropped` on encode either way, but when its value IS the default,
    // re-decode restores the same value — no behavior change, so the verifier must stay quiet. This is
    // what separates "cosmetic default elision" (fine) from "a non-default value lost" (a real bug).
    val dir = makeDir("android-phone.trail.yaml", bundleText(dropped = LossyTestTool.DROPPED_DEFAULT))

    val result = migrator.migrate(dir)

    assertEquals(
      emptyList(),
      result.report.roundTripMismatches,
      "dropping a field that already holds its default value round-trips equal and must not be flagged",
    )
  }

  @Test
  fun `bundle migration retains the v1 inputs and emits a round-trip WARNING on a lossy serializer`() {
    // The Trail Runner "Migrate to unified" path. A mismatch with NO dropped-content keys must still
    // retain the v1 input (it's the only faithful copy of the lost value) and surface the warning —
    // the exact serializer-loss case this whole feature guards, driven through the real file-mutation layer.
    val parent = Files.createTempDirectory("lossy-bundle").toFile()
    val dir = File(parent, "case_lossy").apply { mkdirs() }
    File(dir, "android-phone.trail.yaml").writeText(bundleText(dropped = "must-survive").trimIndent())

    val outcome = BundleMigration.migrateFolder(dir, yaml)

    assertTrue(outcome.inputsRetained, "a round-trip mismatch must retain the v1 inputs")
    assertTrue(File(dir, "android-phone.trail.yaml").isFile, "the v1 input must be left on disk")
    assertTrue(File(dir, "case_lossy.trail.yaml").isFile, "the unified file is still written")
    assertEquals(emptyList(), outcome.removed, "nothing should be deleted on a lossy migration")
    assertTrue(
      outcome.driftComments.any { it.contains("did NOT round-trip") },
      "the round-trip WARNING must surface in driftComments; got ${outcome.driftComments}",
    )
    assertTrue(
      outcome.driftComments.any { it.contains("kept the v1 input file(s)") },
      "the retention reason must surface in driftComments; got ${outcome.driftComments}",
    )
    // The written unified file also carries the warning so a reviewer opening it sees the loss.
    assertTrue(
      "did NOT round-trip" in File(dir, "case_lossy.trail.yaml").readText(),
      "the migrated file must carry the round-trip WARNING comment",
    )
  }
}

/**
 * A deliberately LOSSY tool: [LossyTestToolSerializer] reads both fields but writes only [kept], so
 * the emitted YAML can't reconstruct [dropped] — encode→decode returns it as its default. This is the
 * precise serializer defect the round-trip fidelity verifier exists to catch. Top-level (not local) so
 * `kClass.serializer()` reflection resolves the custom serializer.
 */
@Serializable(with = LossyTestToolSerializer::class)
@TrailblazeToolClass("lossyTestTool")
private data class LossyTestTool(
  val kept: String,
  val dropped: String = DROPPED_DEFAULT,
) : TrailblazeTool {
  companion object {
    const val DROPPED_DEFAULT = "__dropped_default__"
  }
}

private object LossyTestToolSerializer : KSerializer<LossyTestTool> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("lossyTestTool") {
    element<String>("kept")
    element<String>("dropped", isOptional = true)
  }

  override fun deserialize(decoder: Decoder): LossyTestTool =
    decoder.decodeStructure(descriptor) {
      var kept = ""
      var dropped = LossyTestTool.DROPPED_DEFAULT
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> kept = decodeStringElement(descriptor, 0)
          1 -> dropped = decodeStringElement(descriptor, 1)
          CompositeDecoder.DECODE_DONE -> break
          else -> error("Unexpected element index $index")
        }
      }
      LossyTestTool(kept = kept, dropped = dropped)
    }

  override fun serialize(encoder: Encoder, value: LossyTestTool) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.kept)
      // Deliberately DO NOT encode element 1 (`dropped`). This omission is the round-trip loss the
      // fidelity verifier must detect — the whole point of this fixture.
    }
  }
}
