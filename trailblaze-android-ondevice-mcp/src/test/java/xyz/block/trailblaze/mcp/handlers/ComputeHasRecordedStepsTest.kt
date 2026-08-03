package xyz.block.trailblaze.mcp.handlers

import org.junit.jupiter.api.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the recorded-vs-agent classification the on-device session emitter uses to decide whether a
 * report shows the "Recording" pill ([computeHasRecordedSteps]). The regression this guards: a
 * fully-recorded unified trail decoded with NO device classifiers trips the decode guard, the throw
 * is swallowed, and the run is mislabeled as having no recordings — so the pill disappears on the
 * CLI device-driven path even though every step replayed from recordings.
 */
class ComputeHasRecordedStepsTest {

  private val yaml = createTrailblazeYaml()

  /**
   * A unified trail whose single step carries per-family recordings (android + ios). Lowering it
   * closest-wins requires the running device's classifiers to select a slot.
   */
  private val unifiedRecordedTrail = """
    config:
      id: passcode/update
      target: square
    trail:
      - step: Tap a keypad key
        recording:
          android:
            - tapOnPoint:
                x: 1
                y: 1
          ios:
            - tapOnPoint:
                x: 2
                y: 2
  """.trimIndent()

  private val androidPhoneClassifiers =
    listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))

  @Test
  fun `unified recorded trail with the device's classifiers is recorded`() {
    assertTrue(
      computeHasRecordedSteps(yaml, unifiedRecordedTrail, androidPhoneClassifiers),
      "A fully-recorded unified trail lowered against its device's classifiers must report recorded",
    )
  }

  /**
   * THE regression pin. Decoding the same recorded trail with NO classifiers trips the
   * "no classifiers would drop every recording" guard inside `decodeTrail`; the helper swallows the
   * throw to `false`. If a future refactor drops the classifiers at the handler's call site, the
   * report's "Recording" pill silently regresses on the CLI device path — this is the guardrail.
   */
  @Test
  fun `unified recorded trail with empty classifiers is mislabeled as not recorded`() {
    assertFalse(
      computeHasRecordedSteps(yaml, unifiedRecordedTrail, emptyList()),
      "Empty classifiers trip the decode guard and swallow to false — the exact failure mode the " +
        "handler must avoid by passing the device's own classifiers",
    )
  }

  /**
   * The host-drives-the-loop shape: a bare single-tool envelope arrives on the same `yaml` field.
   * It is a recording regardless of classifiers, and must decode via `decodeTrailOrToolEnvelope`
   * (plain `decodeTrail` throws on this shape).
   */
  @Test
  fun `bare tool envelope is recorded regardless of classifiers`() {
    val toolEnvelope = """
      - tapOnPoint:
          x: 5
          y: 6
    """.trimIndent()
    assertTrue(computeHasRecordedSteps(yaml, toolEnvelope, emptyList()))
    assertTrue(computeHasRecordedSteps(yaml, toolEnvelope, androidPhoneClassifiers))
  }

  /**
   * Negative control: a recording-less unified trail genuinely returns `false` — NOT via the
   * swallowed guard. An empty-classifier decode of a trail with nothing to drop doesn't throw, so
   * `false` here proves the helper distinguishes "no recordings" from "recordings we failed to see".
   */
  @Test
  fun `unified trail with no recordings is not recorded`() {
    val noRecordings = """
      config:
        id: passcode/update
        target: square
      trail:
        - step: Tap a keypad key
    """.trimIndent()
    assertFalse(computeHasRecordedSteps(yaml, noRecordings, emptyList()))
    assertFalse(computeHasRecordedSteps(yaml, noRecordings, androidPhoneClassifiers))
  }
}
