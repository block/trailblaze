package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.block.trailblaze.android.accessibility.InProcessIdleLaunchReattacher.Decision

/**
 * The pure attach policy behind [InProcessIdleLaunchReattacher.attachBeforeLaunch] — the side
 * effects (sysprop read, shell `pm path`, PING socket) are injected as plain values so the
 * policy is testable without a device.
 */
class InProcessIdleLaunchReattacherDecisionTest {

  private val appId = "com.example.app"

  @Test
  fun syspropOff_skipsRegardlessOfEverythingElse() {
    assertEquals(
      Decision.SKIP_DISABLED,
      InProcessIdleLaunchReattacher.decide(
        syspropEnabled = false,
        inProcessIdleInstalled = { fail("disabled must not probe install state") },
        pingReply = null,
        appId = appId,
      ),
    )
  }

  @Test
  fun alreadyServingInProcessIdleForThisApp_shortCircuitsWithoutInstallProbe() {
    assertEquals(
      Decision.SKIP_ALREADY_ATTACHED,
      InProcessIdleLaunchReattacher.decide(
        syspropEnabled = true,
        // The live PONG outranks install state — the shell probe must not even run.
        inProcessIdleInstalled = { fail("a live PONG must not probe install state") },
        pingReply = "PONG $appId",
        appId = appId,
      ),
    )
  }

  @Test
  fun portHeldByDifferentAppsIdleDetector_isSkippedNotDetached() {
    assertEquals(
      Decision.SKIP_PORT_HELD_BY_OTHER,
      InProcessIdleLaunchReattacher.decide(
        syspropEnabled = true,
        inProcessIdleInstalled = { fail("another app's PONG must not probe install state") },
        pingReply = "PONG com.other.app",
        appId = appId,
      ),
    )
  }

  @Test
  fun noInProcessIdlePackageInstalled_skips() {
    assertEquals(
      Decision.SKIP_NOT_INSTALLED,
      InProcessIdleLaunchReattacher.decide(
        syspropEnabled = true,
        inProcessIdleInstalled = { false },
        pingReply = null,
        appId = appId,
      ),
    )
  }

  @Test
  fun enabledInstalledAndNothingServing_attaches() {
    assertEquals(
      Decision.ATTACH,
      InProcessIdleLaunchReattacher.decide(
        syspropEnabled = true,
        inProcessIdleInstalled = { true },
        pingReply = null,
        appId = appId,
      ),
    )
  }

  @Test
  fun amInstrumentCleanOutput_isNotAFailure() {
    // A successful `am instrument` (without -w) returns immediately with empty/benign output.
    assertFalse(InProcessIdleLaunchReattacher.amInstrumentReportedFailure(""))
    assertFalse(InProcessIdleLaunchReattacher.amInstrumentReportedFailure("INSTRUMENTATION_STATUS: id=..."))
  }

  @Test
  fun amInstrumentErrorLine_isAFailure() {
    // An unresolvable component / signature mismatch surfaces as an error line, not an exit code.
    assertTrue(
      InProcessIdleLaunchReattacher.amInstrumentReportedFailure(
        "java.lang.SecurityException: not allowed to instrument process",
      ),
    )
    assertTrue(
      InProcessIdleLaunchReattacher.amInstrumentReportedFailure(
        "INSTRUMENTATION_FAILED: unable to find instrumentation target package",
      ),
    )
    // Matching is case-insensitive.
    assertTrue(InProcessIdleLaunchReattacher.amInstrumentReportedFailure("UNABLE to resolve"))
  }

  /**
   * THE regression. A PONG from another app is still proof that THAT app's detector is live, and
   * the settle gates need it: without it they hold "identity unknown", and unknown means they race
   * the detector — the wrong-process settle the foreground gate exists to prevent.
   */
  @Test
  fun anotherAppsPong_namesThatAppAsTheLiveDetector() {
    assertEquals(
      "com.other.app",
      InProcessIdleLaunchReattacher.provenHelperAppId(
        decision = Decision.SKIP_PORT_HELD_BY_OTHER,
        pingReply = "PONG com.other.app",
        appId = appId,
      ),
    )
  }

  @Test
  fun ourOwnPong_namesUs() {
    assertEquals(
      appId,
      InProcessIdleLaunchReattacher.provenHelperAppId(
        decision = Decision.SKIP_ALREADY_ATTACHED,
        pingReply = "PONG $appId",
        appId = appId,
      ),
    )
  }

  /**
   * A decision reached without a live PONG proves nothing about the port, and claiming it does
   * would pin an identity that is either absent or, on the ATTACH path, about to change.
   */
  @Test
  fun decisionsReachedWithoutALivePong_proveNoIdentity() {
    listOf(Decision.SKIP_DISABLED, Decision.SKIP_NOT_INSTALLED, Decision.ATTACH).forEach { decision ->
      assertNull(
        InProcessIdleLaunchReattacher.provenHelperAppId(
          decision = decision,
          pingReply = null,
          appId = appId,
        ),
        "$decision must not claim to prove an identity",
      )
    }
  }

  @Test
  fun onlyTheDecisionsThatEndWithNoDetectorCountAsLosingTurbo() {
    // This set is what `turboRequired` fails a run on, so it is a contract and not a detail. An
    // over-broad set reds a lane that had turbo the whole time; an under-broad one reinstates the
    // silent green the flag exists to remove. Asserted as the whole partition, so adding a Decision
    // forces a deliberate choice rather than defaulting into "fine".
    assertEquals(
      setOf(Decision.SKIP_PORT_HELD_BY_OTHER, Decision.SKIP_NOT_INSTALLED),
      Decision.entries.filter { it.leavesAppWithoutDetector }.toSet(),
    )
  }

  @Test
  fun anAlreadyAttachedLaunchHasNotLostTurbo() {
    // The whole point of the short-circuit: a RESUME launch that never killed the app still has its
    // detector. Failing a required-turbo run here would red every trail that launches without
    // clearing.
    assertFalse(Decision.SKIP_ALREADY_ATTACHED.leavesAppWithoutDetector)
  }

  @Test
  fun aRunThatNeverAskedForTurboHasNotLostIt() {
    // A run without turbo is not a run that lost turbo. SKIP_DISABLED is also unreachable from
    // attachBeforeLaunch, which returns before deciding — but the policy stays total, so the
    // classification has to be right anyway.
    assertFalse(Decision.SKIP_DISABLED.leavesAppWithoutDetector)
  }

  @Test
  fun anAttachInFlightIsNotYetAFailure() {
    // ATTACH means the re-attach was started; whether the app ends up with a detector is decided by
    // awaitAttachedAfterLaunch's PONG wait, not here. Classifying it as lost would fail every
    // required-turbo launch before the attach it just started had a chance to land.
    assertFalse(Decision.ATTACH.leavesAppWithoutDetector)
  }

  @Test
  fun onlyTheAppTurboAttachedToIsHeldToTheStrictContract() {
    // THE false positive. This re-attach runs around EVERY launch, with whatever appId the trail is
    // launching — not the turbo target. A trail that opens a second app, a browser or Settings has
    // no detector staged for it and never will, because only one app per device can be turbo. So
    // strict mode scoped to "the launched app lost its detector" would fail a required-turbo trail
    // for launching something that was never meant to be turbo.
    //
    // No attach has happened in this test process, so nothing is the target — which is also the
    // state a run has before its first attach, and it must not be strict there either.
    assertFalse(OnDeviceTurbo.isTurboTarget(appId))
    assertFalse(OnDeviceTurbo.isTurboTarget("com.android.settings"))
    assertFalse(OnDeviceTurbo.isTurboTarget(""))
  }

  // The detector-package naming convention this class relies on is asserted where it now lives,
  // in InProcessIdleTest — it is shared with the attach, not owned by the re-attach.
}
