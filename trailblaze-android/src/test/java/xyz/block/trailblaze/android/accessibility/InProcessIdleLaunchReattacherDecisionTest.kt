package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  @Test
  fun inProcessIdlePackageNameFollowsLastDottedLabelConvention() {
    assertEquals(
      "xyz.block.trailblaze.inprocessidle.app",
      InProcessIdleLaunchReattacher.inProcessIdlePackageFor("com.example.app"),
    )
    assertEquals(
      "xyz.block.trailblaze.inprocessidle.app",
      InProcessIdleLaunchReattacher.inProcessIdlePackageFor("app"),
    )
  }
}
