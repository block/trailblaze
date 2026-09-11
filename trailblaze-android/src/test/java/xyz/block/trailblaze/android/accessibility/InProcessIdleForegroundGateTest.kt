package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the policy that keeps turbo's in-process idle arm honest: it may only decide a settle while
 * the app the detector lives in is the app on screen.
 *
 * The bug this guards is silent and fast rather than loud and slow. A trail that leaves the
 * detector's app — `launchApp` to system Settings, an OS dialog — backgrounds the detector, and a
 * backgrounded app is trivially idle, so the arm answers `IDLE` in single-digit milliseconds about
 * a process nobody is looking at. Winning that race cancels the event-quiet heuristic that WAS
 * watching the real foreground, and the next action runs against a screen that is still drawing.
 * Nothing in the log says "wrong process"; it reads as an unusually fast settle.
 *
 * Every assertion here is on a pure function, so the whole policy is pinned without a device, a
 * live detector socket, or an accessibility service.
 */
class InProcessIdleForegroundGateTest {

  private val sampleApp = "xyz.block.trailblaze.examples.sampleapp"
  private val settings = "com.android.settings"

  @Test
  fun `turbo off never races, whatever the foreground is`() {
    assertFalse(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = false,
        foregroundAppId = sampleApp,
        helperAppId = sampleApp,
      ),
    )
  }

  @Test
  fun `the foreground being the detector's own app races`() {
    assertTrue(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = true,
        foregroundAppId = sampleApp,
        helperAppId = sampleApp,
      ),
    )
  }

  /**
   * THE regression. A known mismatch is the one case where the arm's answer is about the wrong
   * process, so it is the one case that must not race. Inverting the decision to "always race"
   * fails here and nowhere else.
   */
  @Test
  fun `a foreground that is not the detector's app does not race`() {
    assertFalse(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = true,
        foregroundAppId = settings,
        helperAppId = sampleApp,
      ),
    )
  }

  @Test
  fun `an unreadable foreground keeps racing rather than silently disabling turbo`() {
    assertTrue(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = true,
        foregroundAppId = null,
        helperAppId = sampleApp,
      ),
    )
  }

  @Test
  fun `an unidentified detector keeps racing rather than silently disabling turbo`() {
    assertTrue(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = true,
        foregroundAppId = settings,
        helperAppId = null,
      ),
    )
  }

  @Test
  fun `both sides unknown keeps racing`() {
    assertTrue(
      InProcessIdleForegroundGate.shouldRaceInProcessIdle(
        enabled = true,
        foregroundAppId = null,
        helperAppId = null,
      ),
    )
  }

  @Test
  fun `a PONG names the app the detector lives in`() {
    assertEquals(sampleApp, InProcessIdleForegroundGate.helperAppIdFromPong("PONG $sampleApp"))
  }

  /**
   * A reply this cannot read must produce "identity unknown" — which races — never an appId that
   * happens to mismatch and would switch every settle to heuristic-only on a healthy device.
   */
  @Test
  fun `anything that is not a well-formed PONG leaves the identity unknown`() {
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong(null))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong(""))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong("PONG"))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong("PONG "))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong("PONG   "))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong("IDLE 4"))
    assertNull(InProcessIdleForegroundGate.helperAppIdFromPong("pong $sampleApp"))
  }

  @Test
  fun `the settle line says which app it was watching and which one the detector is in`() {
    val settled = InProcessIdleForegroundGate.heuristicOnlyLabel(
      settled = true,
      foregroundAppId = settings,
      helperAppId = sampleApp,
    )
    assertEquals("event-quiet heuristic (foreground $settings is not the turbo app $sampleApp)", settled)
  }

  /** A heuristic that timed out must not read as a heuristic win — same rule the race path has. */
  @Test
  fun `a heuristic that did not settle says so instead of claiming the win`() {
    val timedOut = InProcessIdleForegroundGate.heuristicOnlyLabel(
      settled = false,
      foregroundAppId = settings,
      helperAppId = sampleApp,
    )
    assertTrue(timedOut.startsWith("timeout"), timedOut)
    assertTrue(timedOut.contains(settings) && timedOut.contains(sampleApp), timedOut)
  }

  @Test
  fun `leaving the detector's app announces the switch once`() {
    val first = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = null,
      foregroundAppId = settings,
      helperAppId = sampleApp,
      racing = false,
    )
    assertEquals(
      "[turbo] foreground $settings is not the turbo app $sampleApp — settling by heuristic until it returns",
      first.line,
    )
    assertEquals(settings, first.announcedMismatchFor)
  }

  /**
   * The gate is consulted twice per replayed action; without this the announcement would be noise
   * proportional to the trail rather than to the switches in it.
   */
  @Test
  fun `staying away from the detector's app announces nothing further`() {
    val repeat = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = settings,
      foregroundAppId = settings,
      helperAppId = sampleApp,
      racing = false,
    )
    assertNull(repeat.line)
    assertEquals(settings, repeat.announcedMismatchFor)
  }

  @Test
  fun `moving to a different foreign app announces the new one`() {
    val moved = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = settings,
      foregroundAppId = "com.android.chrome",
      helperAppId = sampleApp,
      racing = false,
    )
    assertEquals(
      "[turbo] foreground com.android.chrome is not the turbo app $sampleApp — " +
        "settling by heuristic until it returns",
      moved.line,
    )
    assertEquals("com.android.chrome", moved.announcedMismatchFor)
  }

  @Test
  fun `coming back to the detector's app announces the return once`() {
    val back = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = settings,
      foregroundAppId = sampleApp,
      helperAppId = sampleApp,
      racing = true,
    )
    assertEquals("[turbo] foreground back on $sampleApp — racing the helper again", back.line)
    assertNull(back.announcedMismatchFor)
  }

  @Test
  fun `a run that never left the detector's app announces nothing`() {
    val steady = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = null,
      foregroundAppId = sampleApp,
      helperAppId = sampleApp,
      racing = true,
    )
    assertNull(steady.line)
    assertNull(steady.announcedMismatchFor)
  }

  /**
   * Racing again because the foreground became UNREADABLE is not the turbo app coming back, and
   * saying so would send triage looking for a foreground switch that never happened.
   */
  @Test
  fun `an unreadable foreground says so instead of claiming the app came back`() {
    val unreadable = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = settings,
      foregroundAppId = null,
      helperAppId = sampleApp,
      racing = true,
    )
    assertEquals("[turbo] foreground unreadable — racing the helper again", unreadable.line)
    assertEquals(
      InProcessIdleForegroundGate.FOREGROUND_UNREADABLE,
      unreadable.announcedMismatchFor,
    )
  }

  @Test
  fun `a foreground that stays unreadable says it once`() {
    val repeat = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = InProcessIdleForegroundGate.FOREGROUND_UNREADABLE,
      foregroundAppId = null,
      helperAppId = sampleApp,
      racing = true,
    )
    assertNull(repeat.line)
    assertEquals(InProcessIdleForegroundGate.FOREGROUND_UNREADABLE, repeat.announcedMismatchFor)
  }

  /**
   * The unreadable state must not swallow a later genuine return: the sentinel is not a package
   * name, so it can never equal the foreground and every real transition out of it announces.
   */
  @Test
  fun `a genuine return after an unreadable stretch still announces`() {
    val back = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = InProcessIdleForegroundGate.FOREGROUND_UNREADABLE,
      foregroundAppId = sampleApp,
      helperAppId = sampleApp,
      racing = true,
    )
    assertEquals("[turbo] foreground back on $sampleApp — racing the helper again", back.line)
    assertNull(back.announcedMismatchFor)
  }

  @Test
  fun `a mismatch after an unreadable stretch still announces`() {
    val mismatch = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = InProcessIdleForegroundGate.FOREGROUND_UNREADABLE,
      foregroundAppId = settings,
      helperAppId = sampleApp,
      racing = false,
    )
    assertEquals(
      "[turbo] foreground $settings is not the turbo app $sampleApp — " +
        "settling by heuristic until it returns",
      mismatch.line,
    )
    assertEquals(settings, mismatch.announcedMismatchFor)
  }

  /**
   * Losing the DETECTOR's identity resumes the race too, and is likewise not a return — the
   * foreground has not moved at all, so the line names what actually changed.
   */
  @Test
  fun `an unidentified detector says so instead of claiming the app came back`() {
    val unidentified = InProcessIdleForegroundGate.announceSwitch(
      previouslyAnnouncedFor = settings,
      foregroundAppId = settings,
      helperAppId = null,
      racing = true,
    )
    assertEquals(
      "[turbo] turbo app unidentified — racing the helper again (foreground is still $settings)",
      unidentified.line,
    )
    assertEquals(
      InProcessIdleForegroundGate.HELPER_UNIDENTIFIED,
      unidentified.announcedMismatchFor,
    )
  }

  /**
   * The other end of the same window. Arming happens before the probe waits, and the foreground can
   * change while it waits — which is the very thing that makes a backgrounded detector answer. So
   * an armed probe is not a licence to accept whatever tree is on screen when the verdict lands.
   */
  @Test
  fun `an idle verdict is not spent on a tree belonging to another app`() {
    assertFalse(
      InProcessIdleForegroundGate.idleVerdictAppliesTo(
        treeAppId = settings,
        helperAppId = sampleApp,
      ),
    )
  }

  @Test
  fun `an idle verdict is spent on the detector's own tree`() {
    assertTrue(
      InProcessIdleForegroundGate.idleVerdictAppliesTo(
        treeAppId = sampleApp,
        helperAppId = sampleApp,
      ),
    )
  }

  /**
   * THE regression in the identity cache. Downgrading a KNOWN identity to unknown is the one
   * dangerous direction, because unknown races: one 250 ms probe timeout on a loaded emulator
   * would otherwise re-enable the wrong-process settle for a whole TTL. A detector that answered
   * once is still the same app, so a miss keeps the last answer.
   */
  @Test
  fun `a failed probe keeps the identity a successful probe already established`() {
    InProcessIdleForegroundGate.invalidate()
    assertEquals(sampleApp, InProcessIdleForegroundGate.helperAppId(1_000L) { "PONG $sampleApp" })

    val afterMiss = InProcessIdleForegroundGate.helperAppId(
      nowMs = 1_000L + InProcessIdleForegroundGate.HELPER_APP_ID_TTL_MS,
    ) { null }

    assertEquals(sampleApp, afterMiss, "a missed probe erased an identity we had already proven")
  }

  /**
   * The grace is bounded so the TTL keeps its real job — noticing a detector that changed app
   * behind our back, where nothing called `invalidate`.
   */
  @Test
  fun `a known identity degrades to unknown once the stale grace runs out`() {
    InProcessIdleForegroundGate.invalidate()
    InProcessIdleForegroundGate.helperAppId(1_000L) { "PONG $sampleApp" }

    val afterGrace = InProcessIdleForegroundGate.helperAppId(
      nowMs = 1_000L + InProcessIdleForegroundGate.HELPER_APP_ID_STALE_GRACE_MS,
    ) { null }

    assertNull(afterGrace, "a detector that stopped answering was trusted past the stale grace")
  }

  /**
   * The grace runs from the last CONFIRMATION, not the last probe. Re-stamping the probe clock on
   * a miss is what keeps an outage down to one probe per TTL, but if it also moved the confirmation
   * clock the grace would renew itself forever and a stale identity would never expire.
   */
  @Test
  fun `repeated misses do not renew the stale grace`() {
    InProcessIdleForegroundGate.invalidate()
    InProcessIdleForegroundGate.helperAppId(0L) { "PONG $sampleApp" }

    // Three misses, each far enough apart to re-probe, spanning exactly the grace window.
    assertEquals(sampleApp, InProcessIdleForegroundGate.helperAppId(5_000L) { null })
    assertEquals(sampleApp, InProcessIdleForegroundGate.helperAppId(10_000L) { null })

    assertNull(
      InProcessIdleForegroundGate.helperAppId(15_000L) { null },
      "each miss pushed the grace window forward, so the identity never expired",
    )
  }

  /** An explicit "forget it" has to beat the grace, or a re-attach cannot clear a dead detector. */
  @Test
  fun `invalidate defeats the stale grace`() {
    InProcessIdleForegroundGate.invalidate()
    InProcessIdleForegroundGate.helperAppId(1_000L) { "PONG $sampleApp" }

    InProcessIdleForegroundGate.invalidate()

    assertNull(
      InProcessIdleForegroundGate.helperAppId(1_100L) { null },
      "invalidate left the old identity reachable through the stale grace",
    )
  }

  @Test
  fun `a fresh identity is reused without probing again`() {
    InProcessIdleForegroundGate.invalidate()
    InProcessIdleForegroundGate.helperAppId(1_000L) { "PONG $sampleApp" }

    val reused = InProcessIdleForegroundGate.helperAppId(
      nowMs = 1_000L + InProcessIdleForegroundGate.HELPER_APP_ID_TTL_MS - 1,
    ) { fail("a cached identity inside its TTL must not pay another probe") }

    assertEquals(sampleApp, reused)
  }

  /**
   * The negative result is cached too: a device with no detector at all would otherwise pay a
   * connect+read on every settle, twice per replayed action.
   */
  @Test
  fun `a device with no detector is not probed again inside the TTL`() {
    InProcessIdleForegroundGate.invalidate()
    assertNull(InProcessIdleForegroundGate.helperAppId(1_000L) { null })

    assertNull(
      InProcessIdleForegroundGate.helperAppId(
        nowMs = 1_000L + InProcessIdleForegroundGate.HELPER_APP_ID_TTL_MS - 1,
      ) { fail("a cached negative inside its TTL must not pay another probe") },
    )
  }

  @Test
  fun `a detector that comes back is picked up on the next probe`() {
    InProcessIdleForegroundGate.invalidate()
    InProcessIdleForegroundGate.helperAppId(1_000L) { null }

    val recovered = InProcessIdleForegroundGate.helperAppId(
      nowMs = 1_000L + InProcessIdleForegroundGate.HELPER_APP_ID_TTL_MS,
    ) { "PONG $sampleApp" }

    assertEquals(sampleApp, recovered)
  }

  /**
   * Same unknown-means-accept direction as the arm decision: a root with no readable package, or a
   * detector whose identity is momentarily unknown, must not silently turn turbo off.
   */
  @Test
  fun `an unidentifiable tree or detector still spends the verdict`() {
    assertTrue(
      InProcessIdleForegroundGate.idleVerdictAppliesTo(treeAppId = null, helperAppId = sampleApp),
    )
    assertTrue(
      InProcessIdleForegroundGate.idleVerdictAppliesTo(treeAppId = settings, helperAppId = null),
    )
    assertTrue(
      InProcessIdleForegroundGate.idleVerdictAppliesTo(treeAppId = null, helperAppId = null),
    )
  }
}
