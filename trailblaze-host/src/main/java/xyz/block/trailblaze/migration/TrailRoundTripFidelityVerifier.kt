package xyz.block.trailblaze.migration

import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * One place where the migrated unified file does NOT decode back to the tools the migrator intended
 * to write — a genuine, behavior-changing fidelity loss (a serializer that fails to round-trip a
 * non-default value, a nested arg the concrete tool model can't carry, a config field lost on
 * re-encode). Surfaced in [UnifiedTrailMigrator.Report.roundTripMismatches] and the migrated file's
 * leading comments, and (like [DroppedContentEntry]) gated by `--fail-on-dropped-content`.
 */
data class RoundTripFidelityEntry(
  /** Where the mismatch is (e.g. `config`, `trailhead`, `step 3 · recording[android-phone]`). */
  val location: String,
  /** Short human description of what differs (intended vs re-decoded). */
  val detail: String,
)

/**
 * Confidence check for the migrator's serialize-based reshape: encode the unified trail the migrator
 * built, decode that emitted text back through the SAME serializers, and compare the re-decoded trail
 * to the intended one at the **typed-object** level.
 *
 * Why object-equality and not a text diff: the encoders omit default-valued fields (`textMatchMode:
 * EXACT`, `currency: USD`, `recordable: true`) and normalize some scalars (a Maestro `timeout: 1000`
 * emits as `"1000"`, which Maestro re-coerces). A text diff flags all of those as changes even though
 * they decode back to the identical value — the exact false positives the sibling
 * [TrailRoundTripDropDetector] kdoc calls out. Comparing the DECODED objects tolerates every
 * behavior-preserving normalization (defaults restored, scalars re-coerced → equal) while catching a
 * real loss (a value that decodes to something different, or vanishes).
 *
 * Strictly diagnostic: never mutates what migrates and never throws — a verifier failure degrades to
 * "no mismatches found" so it can't break a migration. The one non-empty degrade is an emitted file
 * that fails to re-parse at all, which is itself a fidelity finding worth reporting.
 */
internal object TrailRoundTripFidelityVerifier {

  /** Cap on how much of a differing object we render into a finding, so a big tool stays readable. */
  private const val RENDER_MAX = 300

  fun verify(yaml: TrailblazeYaml, trail: UnifiedTrail): List<RoundTripFidelityEntry> =
    runCatching {
      val emitted = yaml.encodeUnifiedTrailToString(trail = trail, leadingComments = emptyList())
      val reDecoded = try {
        yaml.decodeUnifiedTrail(emitted)
      } catch (e: Throwable) {
        return@runCatching listOf(
          RoundTripFidelityEntry(
            location = "trail",
            detail = "emitted unified YAML failed to re-parse: ${e.message ?: e::class.simpleName}",
          ),
        )
      }
      diff(intended = trail, reDecoded = reDecoded)
    }.getOrDefault(emptyList())

  /**
   * Pure comparison of the trail the migrator built ([intended]) against the trail its emitted YAML
   * decodes back to ([reDecoded]). Extracted so the catch logic can be unit-tested with a tampered
   * [reDecoded] — no broken serializer required to exercise the negative path.
   */
  internal fun diff(intended: UnifiedTrail, reDecoded: UnifiedTrail): List<RoundTripFidelityEntry> {
    val out = mutableListOf<RoundTripFidelityEntry>()

    if (intended.config != reDecoded.config) {
      out += RoundTripFidelityEntry(
        location = "config",
        detail = renderMismatch(intended.config, reDecoded.config),
      )
    }

    diffStep("trailhead", intended.trailhead, reDecoded.trailhead, out)

    if (intended.trail.size != reDecoded.trail.size) {
      out += RoundTripFidelityEntry(
        location = "trail",
        detail = "step count changed: intended ${intended.trail.size}, re-decoded ${reDecoded.trail.size}",
      )
    }
    val stepCount = minOf(intended.trail.size, reDecoded.trail.size)
    for (i in 0 until stepCount) {
      diffStep("step ${i + 1}", intended.trail[i], reDecoded.trail[i], out)
    }
    return out
  }

  private fun diffStep(
    label: String,
    intended: UnifiedTrailStep?,
    reDecoded: UnifiedTrailStep?,
    out: MutableList<RoundTripFidelityEntry>,
  ) {
    if (intended == null && reDecoded == null) return
    if (intended == null || reDecoded == null) {
      out += RoundTripFidelityEntry(label, if (intended == null) "appeared only after re-decode" else "lost on re-decode")
      return
    }
    // Compare the step's scalar shape (NL, kind, retry budget, recordable) apart from recordings so a
    // finding names exactly what changed.
    if (
      intended.step != reDecoded.step ||
      intended.verify != reDecoded.verify ||
      intended.recordable != reDecoded.recordable ||
      intended.maxRetries != reDecoded.maxRetries
    ) {
      out += RoundTripFidelityEntry(
        location = label,
        detail = renderMismatch(
          "step=${intended.step.take(60)} verify=${intended.verify} recordable=${intended.recordable} maxRetries=${intended.maxRetries}",
          "step=${reDecoded.step.take(60)} verify=${reDecoded.verify} recordable=${reDecoded.recordable} maxRetries=${reDecoded.maxRetries}",
        ),
      )
    }
    // Sorted so the mismatch list (and the emitted WARNING block) is deterministic across runs —
    // the recordings maps iterate in unspecified order otherwise, which would make CI diffs noisy.
    val classifiers = (intended.recordings.keys + reDecoded.recordings.keys).sorted()
    for (classifier in classifiers) {
      val a = intended.recordings[classifier]
      val b = reDecoded.recordings[classifier]
      if (a != b) {
        out += RoundTripFidelityEntry(
          location = "$label · recording[$classifier]",
          detail = renderMismatch(a, b),
        )
      }
    }
  }

  private fun renderMismatch(intended: Any?, reDecoded: Any?): String =
    "intended: ${intended.toString().take(RENDER_MAX)}\n    re-decoded: ${reDecoded.toString().take(RENDER_MAX)}"
}
