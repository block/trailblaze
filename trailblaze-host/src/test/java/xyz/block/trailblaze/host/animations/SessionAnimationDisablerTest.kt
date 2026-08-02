package xyz.block.trailblaze.host.animations

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.host.animations.SessionAnimationDisabler.IosReadOutcome
import xyz.block.trailblaze.host.ios.SimctlCli

/**
 * The pure decision seams behind [SessionAnimationDisabler]:
 *
 * - Android restore-command mapping: a setting the device reported as unset (`settings get`
 *   prints the literal `null`) must be restored via `delete` — writing the string `null` back
 *   would leave the device with a corrupt setting instead of its OS default.
 * - iOS pre-mutation read classification: only the specific absent-key failure means "unset";
 *   any other failure must decline the whole setup, because guessing "unset" would make the
 *   later restore delete a coefficient the user had deliberately set.
 */
class SessionAnimationDisablerTest {

  @Test
  fun `a previously-set scale is restored with its captured value`() {
    assertEquals(
      "settings put global animator_duration_scale 1.0",
      SessionAnimationDisabler.androidRestoreCommand("animator_duration_scale", "1.0"),
    )
  }

  @Test
  fun `a non-default previous value is restored verbatim`() {
    assertEquals(
      "settings put global window_animation_scale 0.5",
      SessionAnimationDisabler.androidRestoreCommand("window_animation_scale", "0.5"),
    )
  }

  @Test
  fun `an unset scale is restored by deleting the setting`() {
    assertEquals(
      "settings delete global transition_animation_scale",
      SessionAnimationDisabler.androidRestoreCommand("transition_animation_scale", "null"),
    )
  }

  @Test
  fun `a blank captured value is treated as unset`() {
    assertEquals(
      "settings delete global window_animation_scale",
      SessionAnimationDisabler.androidRestoreCommand("window_animation_scale", ""),
    )
  }

  @Test
  fun `a successful ios read yields the trimmed previous value`() {
    assertEquals(
      IosReadOutcome.Value("1.0"),
      SessionAnimationDisabler.classifyIosCoefficientRead(SimctlCli.Result(0, "1.0\n", "")),
    )
  }

  @Test
  fun `the absent-key ios read failure means the coefficient was unset`() {
    // Verified wording from a live `defaults read` against a booted simulator.
    val stderr =
      "2026-07-27 09:00:00.000 defaults[123:456]\n" +
        "The domain/default pair of (kCFPreferencesAnyApplication, UIAnimationDragCoefficient) does not exist"
    assertEquals(
      IosReadOutcome.Unset,
      SessionAnimationDisabler.classifyIosCoefficientRead(SimctlCli.Result(1, "", stderr)),
    )
  }

  @Test
  fun `a timed-out ios read is unknown, not unset`() {
    assertEquals(
      IosReadOutcome.Unknown,
      SessionAnimationDisabler.classifyIosCoefficientRead(
        SimctlCli.Result(-1, "", "simctl timed out after 10s"),
      ),
    )
  }

  @Test
  fun `an unexplained ios read failure is unknown, not unset`() {
    assertEquals(
      IosReadOutcome.Unknown,
      SessionAnimationDisabler.classifyIosCoefficientRead(SimctlCli.Result(1, "", "")),
    )
  }
}
