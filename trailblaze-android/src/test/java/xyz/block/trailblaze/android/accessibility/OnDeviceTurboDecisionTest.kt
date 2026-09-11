package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.accessibility.OnDeviceTurbo.Outcome

/**
 * The pure policy behind [OnDeviceTurbo.start] — the side effects (instrumentation-arg reads, the
 * attach, the sysprop write) are injected as plain values so every branch is testable without a
 * device.
 *
 * The property worth protecting here is the switch. The settle gates' idle request is not
 * app-scoped, so a `debug.trailblaze.settle.inProcessIdle` left on without a detector behind it
 * lets a wait end early on whatever process still holds the detector port. Every failure path has
 * to clear it, and a failure path is exactly where that is easiest to forget.
 */
class OnDeviceTurboDecisionTest {

  private val appId = "com.example.app"

  @Test
  fun `a run that never asked for turbo attaches nothing and clears the switch`() {
    val plan = OnDeviceTurbo.decide(requested = false, appId = null, attachError = null)
    assertEquals(Outcome.NOT_REQUESTED, plan.outcome)
    assertTrue(plan.clearSwitch, "a turbo-off run left the switch for whatever the last run set")
    // Silent on purpose: clearIfOn says its own piece, and only when there was a switch to clear.
    assertNull(plan.message(scope = "test", deviceLabel = "Pixel (API 35)"))
  }

  @Test
  fun `not asking for turbo is not a failure`() {
    // Load-bearing for `trailblaze.turbo.required`: the required flag escalates failures, and the
    // overwhelming majority of runs are turbo-off. If this were a failure, the flag would redden
    // every lane that merely inherited it.
    assertFalse(Outcome.NOT_REQUESTED.isFailure)
  }

  @Test
  fun `turbo asked for with no app resolved says so and clears the switch`() {
    val plan = OnDeviceTurbo.decide(requested = true, appId = null, attachError = null)
    assertEquals(Outcome.NO_APP, plan.outcome)
    assertTrue(plan.clearSwitch)
    assertTrue(Outcome.NO_APP.isFailure, "a lane that requires turbo must not pass without an app")
    val message = plan.message(scope = "session-1", deviceLabel = "Pixel (API 35)")
    assertTrue(message!!.contains("resolved no app"), message)
    assertTrue(message.contains("running at normal speed"), message)
    // The line says the run continues at normal speed, so it must not also read as an announcement
    // that turbo engaged — "turbo is on ... running at normal speed" is a line a reader scanning a
    // log will take the wrong half of.
    assertFalse(message.contains("turbo is on"), message)
  }

  @Test
  fun `a failed attach clears the switch and names the reason`() {
    val plan = OnDeviceTurbo.decide(
      requested = true,
      appId = appId,
      attachError = IllegalStateException("detector never answered PING"),
    )
    assertEquals(Outcome.ATTACH_FAILED, plan.outcome)
    // THE regression this guards. `am instrument` may well have written the switch before the
    // attach failed, so a failure that skipped the clear would leave the gates racing a detector
    // that isn't there.
    assertTrue(plan.clearSwitch, "a failed attach left the settle-race switch on")
    val message = plan.message(scope = "session-1", deviceLabel = "Pixel (API 35)")
    assertTrue(message!!.contains(appId), message)
    assertTrue(message.contains("detector never answered PING"), "drops the reason: $message")
    assertTrue(message.contains("IllegalStateException"), "drops the exception type: $message")
    assertTrue(message.contains("running at normal speed"), message)
  }

  @Test
  fun `a successful attach keeps the switch on and says which app on which device`() {
    val plan = OnDeviceTurbo.decide(requested = true, appId = appId, attachError = null)
    assertEquals(Outcome.ATTACHED, plan.outcome)
    // The one outcome that must NOT clear: this is the run that wanted the switch.
    assertFalse(plan.clearSwitch, "cleared the switch the successful attach just earned")
    assertFalse(Outcome.ATTACHED.isFailure)
    val message = plan.message(scope = "session-1", deviceLabel = "Pixel (API 35)")
    assertTrue(message!!.contains(appId), message)
    assertTrue(message.contains("Pixel (API 35)"), message)
  }

  @Test
  fun `only the two turbo-was-wanted-but-unavailable outcomes are failures`() {
    // Spelled out as a set rather than per-case so adding an Outcome forces a decision here about
    // whether `trailblaze.turbo.required` should redden on it.
    assertEquals(
      setOf(Outcome.NO_APP, Outcome.ATTACH_FAILED),
      Outcome.entries.filter { it.isFailure }.toSet(),
    )
  }

  @Test
  fun `only a run that never asked for turbo spares a live detector's switch`() {
    // THE ownership rule. The settle sysprop is device-global with two possible owners: turbo, and
    // a bundle that attaches the detector directly instead of through the arg, as the A/B benchmark
    // bundles do. A turbo-off run that clears unconditionally turns that bundle's switch off
    // underneath it — it keeps replaying, at heuristic speed, reporting green.
    //
    // The set is the contract. Widening it to a failure outcome would leave waits racing whatever
    // app happens to hold the port, which is what the clear exists to prevent; narrowing it to
    // nothing brings the stolen switch back.
    assertEquals(
      setOf(Outcome.NOT_REQUESTED),
      Outcome.entries.filter { it.sparesLiveDetector }.toSet(),
    )
  }

  @Test
  fun `a run whose own attach failed still clears the switch`() {
    // Not covered by the set assertion above on its own: this is the case where sparing would be
    // actively wrong. Turbo was wanted for one app and could not be had, so a detector answering on
    // the port belongs to some other app, and racing this run's waits against it ends waits early
    // on a process the trail is not driving.
    assertFalse(Outcome.ATTACH_FAILED.sparesLiveDetector)
    assertFalse(Outcome.NO_APP.sparesLiveDetector)
    assertTrue(
      OnDeviceTurbo.decide(requested = true, appId = appId, attachError = RuntimeException("boom")).clearSwitch,
    )
  }

  @Test
  fun `a switch is only spared when this process attached the detector answering now`() {
    assertEquals(
      appId,
      OnDeviceTurbo.switchOwner(
        spareLiveDetector = true,
        attachedInThisProcess = appId,
        liveDetectorAppId = { appId },
      ),
    )
  }

  @Test
  fun `a run that never attached anything cannot own the switch, and does not probe to find out`() {
    // THE hazard a PONG alone cannot see. The sysprop is device-global and outlives the process
    // that set it, so a run killed before its cleanup leaves the switch on AND its detector
    // answering. Sparing on that reply would hand turbo to a run that never asked — and a turbo-off
    // run silently racing a detector is precisely the reading an A/B control arm must not produce.
    //
    // Asserted through an exploding probe because no answer can change the verdict here: a process
    // that never attached has nothing to claim. That also keeps the ordinary turbo-off run — most
    // runs on most devices — from paying a socket connect to be told something already known.
    assertNull(
      OnDeviceTurbo.switchOwner(
        spareLiveDetector = true,
        attachedInThisProcess = null,
        liveDetectorAppId = { error("probed the port for a process that never attached a detector") },
      ),
      "spared a switch on a detector this process never attached",
    )
  }

  @Test
  fun `a detector that moved to another app does not keep this run's claim`() {
    assertNull(
      OnDeviceTurbo.switchOwner(
        spareLiveDetector = true,
        attachedInThisProcess = appId,
        liveDetectorAppId = { "com.example.otherapp" },
      ),
      "kept a claim after the port changed hands",
    )
    // Our detector is gone and nothing answers: the switch is leftover, which is what the clear is for.
    assertNull(
      OnDeviceTurbo.switchOwner(
        spareLiveDetector = true,
        attachedInThisProcess = appId,
        liveDetectorAppId = { null },
      ),
    )
  }

  @Test
  fun `an outcome that does not spare never pays the socket probe`() {
    // The end-of-run hook and every failure outcome come through here. Beyond being wrong to spare,
    // probing would add a connect timeout to a path that runs on every turbo-off run on every device.
    assertNull(
      OnDeviceTurbo.switchOwner(
        spareLiveDetector = false,
        attachedInThisProcess = appId,
        liveDetectorAppId = { error("probed the port on a branch that cannot spare the switch") },
      ),
    )
  }

  @Test
  fun `a run that did not ask for turbo has nothing to attach to`() {
    assertNull(
      OnDeviceTurbo.attachTarget(requested = false, targetAppId = appId),
      "resolved an attach target for a run that never asked for turbo",
    )
  }

  @Test
  fun `a blank or whitespace-only app id is no app at all`() {
    // The value is interpolated into a package name, an asset path and several shell commands, so
    // whitespace here surfaces as a missing-asset failure several steps later instead of as "this
    // run could not say which app".
    assertNull(OnDeviceTurbo.attachTarget(requested = true, targetAppId = null))
    assertNull(OnDeviceTurbo.attachTarget(requested = true, targetAppId = ""))
    assertNull(OnDeviceTurbo.attachTarget(requested = true, targetAppId = "   "))
  }

  @Test
  fun `an app id is trimmed before use`() {
    assertEquals(appId, OnDeviceTurbo.attachTarget(requested = true, targetAppId = "  $appId  "))
  }

  @Test
  fun `the instrumentation arg names match what the runner scripts forward`() {
    // The scripts bridge TRAILBLAZE_TURBO / TRAILBLAZE_TURBO_REQUIRED to these exact strings. A
    // rename on either side is silent — turbo simply never engages — so pin the names here, on the
    // side that can see the constants. The scripts are pinned by a test that reads them off disk.
    assertEquals("trailblaze.turbo", OnDeviceTurbo.TURBO_ARG)
    assertEquals("trailblaze.turbo.required", OnDeviceTurbo.TURBO_REQUIRED_ARG)
  }
}
