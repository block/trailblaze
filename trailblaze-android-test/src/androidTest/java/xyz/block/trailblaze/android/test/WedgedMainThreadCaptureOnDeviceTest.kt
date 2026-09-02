package xyz.block.trailblaze.android.test

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException

/**
 * Capturing the screen while the app's main thread is wedged.
 *
 * Every capture read happens inside one block posted to the UI thread, so a main thread that
 * never runs posted work is a main thread that never answers a capture — and capture is what the
 * host's readiness probe reads. Before the bounded hop, that park was ETERNAL and silent: the
 * host abandoned each probe after its own short cap and reported only "probe timed out", while on
 * the device the Ktor worker sat in the hop with nothing logged. The contract under test here is
 * the containment: a capture taken against a wedged main thread must come back as the not-ready
 * classification the host already polls through, within the hop's deadline, and the same capture
 * path must work again once the main thread does.
 *
 * The wedge itself is a latch, never released longer than the hop deadline plus slack. That stays
 * under the system's patience for a focused window with no pending input, so no ANR dialog
 * appears to steal window focus from the recovery capture — nothing here dispatches input while
 * the main thread is held.
 */
class WedgedMainThreadCaptureOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget

  /**
   * Releases whatever wedge a test parked on the main thread. A field counted down in [tearDown]
   * so that a test failing BEFORE its own release still frees the main thread — otherwise
   * [ActivityScenario.close] would park behind the wedge and hang the whole instrumentation run
   * instead of failing one test.
   */
  private val releaseWedge = CountDownLatch(1)

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target = RuleBackedAndroidTestTarget(
      activityProvider = { fixture },
      composeTestRule = composeRule,
    )
  }

  @After
  fun tearDown() {
    captureHopDeadlineMs = CAPTURE_HOP_DEADLINE_MS
    releaseWedge.countDown()
    scenario.close()
  }

  /**
   * The shipped deadline is sized for hang containment.
   *
   * [aWedgedMainThreadSurfacesNotReadyWithinTheBoundAndRecoversAfterwards] shortens the deadline so
   * it can assert the full-deadline wait in seconds, which leaves the shipped value itself
   * unexercised — and a deadline quietly dropped to a couple of seconds would reintroduce exactly
   * the failure the bound must not have: a busy-but-healthy screen reported as not-ready. The
   * floor, not the exact number, is the contract.
   */
  @Test
  fun theShippedCaptureDeadlineStaysSizedForHangContainment() {
    assertTrue(
      CAPTURE_HOP_DEADLINE_MS >= TimeUnit.SECONDS.toMillis(10),
      "The capture hop deadline is ${CAPTURE_HOP_DEADLINE_MS}ms — too short to be hang containment, " +
        "so a slow frame or a long animation would surface as not-ready",
    )
  }

  /**
   * One wedge, both capture entry points, then recovery.
   *
   * The two captors run CONCURRENTLY during the same wedge — [AndroidHybridHierarchyCollector.collect]
   * is the path every screen-state request takes, and [AndroidTestTarget.composeRootsIn] is the
   * entry an off-thread caller reaches directly — because each independently owns a hop that
   * could regress to the unbounded one. A third captor is interrupted mid-wait, the way a host
   * request tearing down its worker cancels the thread the hop is parked on.
   *
   * Four claims, each with its own failure mode:
   * - Bounded at all: a captor still parked after deadline-plus-slack is exactly the eternal
   *   park this exists to prevent, so the join bound is itself an assertion.
   * - Not-ready, not generic: the host's probe treats [OnDeviceScreenStateNotReadyException] as
   *   "ask again shortly"; any other type turns a wedge into a stack-trace-logging failure.
   * - Waited the FULL deadline: the bound is hang containment, not a performance budget, and a
   *   hop that gives up early would fail healthy-but-busy screens. The lower-bound assert is
   *   what separates "bounded" from "flaky".
   * - Interruption answers early with the SAME classification, interrupt flag restored — the old
   *   `runOnMainSync` path swallowed interrupts, so this is the one way a wait can end that never
   *   existed before this hop.
   */
  @Test
  fun aWedgedMainThreadSurfacesNotReadyWithinTheBoundAndRecoversAfterwards() {
    // Every claim below is about the hop's behaviour RELATIVE to its deadline, so a shorter one
    // proves them identically and costs seconds instead of half a minute. The shipped value's own
    // floor is asserted by theShippedCaptureDeadlineStaysSizedForHangContainment.
    captureHopDeadlineMs = WEDGED_DEADLINE_MS
    wedgeMainThread()
    val collectOutcome = captureConcurrently("trailblaze-test-wedged-collect") {
      AndroidHybridHierarchyCollector.collect(fixture, target)
    }
    val rootsOutcome = captureConcurrently("trailblaze-test-wedged-roots") {
      target.composeRootsIn(fixture.window.decorView)
    }
    val interruptedOutcome = captureConcurrently("trailblaze-test-wedged-interrupted") {
      AndroidHybridHierarchyCollector.collect(fixture, target)
    }

    interruptedOutcome.captor.awaitParkedInHop()
    interruptedOutcome.captor.interrupt()
    interruptedOutcome.captor.join(TimeUnit.SECONDS.toMillis(30))
    assertTrue(
      !interruptedOutcome.captor.isAlive,
      "The interrupted captor is still parked — interruption must end the wait, not be swallowed",
    )
    assertIs<OnDeviceScreenStateNotReadyException>(
      interruptedOutcome.thrown.get(),
      "An interrupted capture must surface as the same not-ready classification",
    )
    assertTrue(
      interruptedOutcome.elapsedMs.get() < WEDGED_DEADLINE_MS,
      "The interrupted captor waited out the full deadline — the interrupt was swallowed",
    )
    assertTrue(
      interruptedOutcome.interruptFlagAfter.get(),
      "The hop must restore the thread's interrupt flag, not clear it",
    )

    collectOutcome.assertSurfacedNotReadyOnlyAtTheFullDeadline("The hybrid capture")
    rootsOutcome.assertSurfacedNotReadyOnlyAtTheFullDeadline("The Compose-roots capture")

    // Recovery: once released, the main thread SKIPS the abandoned capture blocks (running stale
    // walks would only delay this), then a fresh capture through the same entry point answers.
    releaseWedge.countDown()
    composeRule.waitForIdle()
    val recovered = AndroidHybridHierarchyCollector.collect(fixture, target)
    assertTrue(
      recovered.containsComposeTag(MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
      "Capture did not recover once the main thread was released",
    )
  }

  /**
   * The other half of the deadline's contract: a busy-but-healthy main thread still wins.
   *
   * The bound exists to contain a hang, not to race the app — so a main thread that is held past
   * the warn threshold but well inside the deadline must produce a real capture, not a not-ready.
   * A hop that gave up early passes the wedge test above (it still throws the right type) and
   * fails only here.
   */
  @Test
  fun aCaptureWaitsOutABusyMainThreadAndSucceeds() {
    wedgeMainThread()
    // The hold is real wall-clock on purpose — the tolerance under test IS time, and faking it
    // would need a clock seam inside the hop's latch wait. It is as short as the claim allows:
    // just past CAPTURE_HOP_WARN_MS, the one threshold a hop that confuses "slow" with "wedged"
    // would give up at. A hold below the warn threshold would let that regression pass.
    val holdMs = CAPTURE_HOP_WARN_MS + 500
    val releaser = thread(name = "trailblaze-test-wedge-releaser") {
      Thread.sleep(holdMs)
      releaseWedge.countDown()
    }
    val collected = AndroidHybridHierarchyCollector.collect(fixture, target)
    releaser.join()
    assertTrue(
      collected.containsComposeTag(MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
      "A capture across a briefly-held main thread must return the real tree, not give up",
    )
  }

  /**
   * An exception from inside the capture block surfaces as ITSELF on the calling thread.
   *
   * Two wrong outcomes guard this: reclassifying it as not-ready would make the host poll a
   * genuinely broken capture forever, and letting it escape on the UI thread would crash the app
   * under test. The follow-up capture proves the hop is still serviceable after a failed block.
   */
  @Test
  fun anExceptionFromTheCaptureBlockPropagatesAsItself() {
    val thrown = onAnotherThread {
      runCatching { onMainThreadForCapture { error("capture block failure marker") } }
        .exceptionOrNull()
    }
    assertIs<IllegalStateException>(
      thrown,
      "The block's own exception must cross back to the caller as itself",
    )
    assertTrue(
      thrown.message == "capture block failure marker",
      "The block's own message must survive the hop, got: ${thrown.message}",
    )
    val collected = onAnotherThread { AndroidHybridHierarchyCollector.collect(fixture, target) }
    assertTrue(
      collected.containsComposeTag(MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
      "The hop must still capture normally after a block threw",
    )
  }

  /** Parks the main thread on [releaseWedge] and returns once the wedge is confirmed in place. */
  private fun wedgeMainThread() {
    val wedgeParked = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
      wedgeParked.countDown()
      releaseWedge.await()
    }
    // Hang containment for the wedge reaching the front of the main queue on a loaded emulator,
    // not a scheduling budget.
    assertTrue(
      wedgeParked.await(WEDGE_PARK_CONTAINMENT_SECONDS, TimeUnit.SECONDS),
      "The wedge never reached the main thread, so nothing below tests a wedged app",
    )
  }

  /**
   * Waits (milliseconds, not a fixed sleep) until this captor is parked in the hop's timed await,
   * so an interrupt provably lands mid-wait. The hop's `await` is the first timed wait on the
   * capture path, so [Thread.State.TIMED_WAITING] is unambiguous. Bounded only as hang
   * containment for a captor that never gets there.
   */
  private fun Thread.awaitParkedInHop() {
    val deadline =
      SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(WEDGE_PARK_CONTAINMENT_SECONDS)
    while (state != Thread.State.TIMED_WAITING) {
      assertTrue(
        SystemClock.uptimeMillis() < deadline,
        "Captor '$name' never parked in the hop's await (state: $state)",
      )
      Thread.sleep(10)
    }
  }

  private class CaptureOutcome(
    val captor: Thread,
    val thrown: AtomicReference<Throwable?>,
    val elapsedMs: AtomicLong,
    val interruptFlagAfter: AtomicBoolean,
  )

  private fun captureConcurrently(name: String, capture: () -> Any?): CaptureOutcome {
    val thrown = AtomicReference<Throwable?>(null)
    val elapsedMs = AtomicLong(-1)
    val interruptFlagAfter = AtomicBoolean(false)
    val captor = thread(name = name) {
      val startedAt = SystemClock.uptimeMillis()
      thrown.set(runCatching(capture).exceptionOrNull())
      elapsedMs.set(SystemClock.uptimeMillis() - startedAt)
      interruptFlagAfter.set(Thread.currentThread().isInterrupted)
    }
    return CaptureOutcome(captor, thrown, elapsedMs, interruptFlagAfter)
  }

  private fun CaptureOutcome.assertSurfacedNotReadyOnlyAtTheFullDeadline(what: String) {
    captor.join(WEDGED_DEADLINE_MS + TimeUnit.SECONDS.toMillis(30))
    assertTrue(
      !captor.isAlive,
      "$what is still parked past the deadline plus slack — the eternal park the bound exists to prevent",
    )
    assertIs<OnDeviceScreenStateNotReadyException>(
      thrown.get(),
      "$what must surface a wedged main thread as the not-ready classification the host polls through",
    )
    // Small slop because the hop's latch and this measurement read different clocks.
    assertTrue(
      elapsedMs.get() >= WEDGED_DEADLINE_MS - 250,
      "$what gave up after ${elapsedMs.get()}ms — the bound is hang containment and must not fail a " +
        "merely busy main thread before the full ${WEDGED_DEADLINE_MS}ms deadline",
    )
  }

  private fun AndroidHybridHierarchyCollector.Collected.containsComposeTag(tag: String): Boolean =
    trailblazeTree.aggregate().any { node ->
      (node.driverDetail as? DriverNodeDetail.Compose)?.testTag == tag
    }

  /** Runs [block] on a thread that is neither the UI thread nor the instrumentation thread. */
  private fun <T> onAnotherThread(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    thread(name = "trailblaze-test-captor") { result.set(runCatching(block)) }.join()
    return result.get().getOrThrow()
  }

  private companion object {
    const val WEDGE_PARK_CONTAINMENT_SECONDS = 60L

    /**
     * The deadline the wedge test runs the hop under. Long enough that the emulator's own
     * scheduling jitter cannot look like an early give-up, short enough that asserting the
     * full-deadline wait is cheap. The busy-main-thread test deliberately does NOT shorten the
     * deadline — its margin over the hold is what keeps it from flaking under load.
     */
    const val WEDGED_DEADLINE_MS = 5_000L
  }
}
