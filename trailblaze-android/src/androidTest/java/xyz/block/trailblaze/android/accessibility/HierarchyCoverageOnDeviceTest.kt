package xyz.block.trailblaze.android.accessibility

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.util.Console

/**
 * On-device (connected) validation of the accessibility-tree completeness gate — the fix for the
 * "truncated/partial hierarchy" failure where the screenshot shows a full screen but the captured
 * accessibility tree only contains a one-edge slice, so selector taps/asserts report
 * "element not found" for plainly-visible content.
 *
 * Exercises the real on-device capture path: the Trailblaze accessibility service is enabled on a
 * normal emulator, a real Activity is brought to the foreground, and the tree is captured through
 * [TrailblazeAccessibilityService.captureMergedScreenTrees] (which runs the
 * [TrailblazeAccessibilityService.awaitTreeStable] gate, including the [HierarchyCoverageAssessor]
 * completeness check). The captured [AccessibilityNode] tree is then assessed exactly as the gate
 * assesses it.
 *
 * Two directions against a REAL accessibility tree (not synthetic bounds — that's covered by the
 * JVM `HierarchyCoverageAssessorTest`):
 * - a full-width screen must NOT be flagged (no false-positive; capture still works end-to-end), and
 * - a right-edge slice MUST be flagged.
 *
 * Note: the right-edge fixture is a *genuine, static* slice, so the gate's refresh-and-retry can't
 * "recover" it — it holds to the 1s hard cap, logs `[capture-coverage] proceeding … TRUNCATED`, and
 * returns the slice. That's the correct terminal behavior; this test asserts the assessment of the
 * returned tree, not recovery (which only applies to the transient mid-commit case).
 *
 * Lives in this module's wired androidTest harness (alongside `AndroidCanvasSetOfMarkTest`) so the
 * `internal` [HierarchyCoverageAssessor] / [AccessibilityNode] stay internal.
 */
class HierarchyCoverageOnDeviceTest {

  @Before
  fun enableAccessibilityService() {
    OnDeviceAccessibilityServiceSetup.ensureUiAutomationDoesNotSuppressAccessibility()
    OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady(timeoutMs = 15_000)
  }

  @Test
  fun fullWidthContent_capturesCompleteHierarchy() {
    val assessment = assessFixture(CoverageFixtureActivity.LAYOUT_FULL_WIDTH)
    assertFalse(
      "A full-width real accessibility tree must NOT be flagged truncated: ${assessment.reason}",
      assessment.looksTruncated,
    )
    assertTrue(
      "Expected several content nodes from the fixture, got ${assessment.contentNodes}",
      assessment.contentNodes >= 4,
    )
    assertTrue(
      "Expected wide horizontal coverage on a full-width screen, got ${assessment.horizontalCoverage}",
      assessment.horizontalCoverage > 0.6,
    )
  }

  @Test
  fun rightEdgeSlice_isFlaggedTruncated() {
    val assessment = assessFixture(CoverageFixtureActivity.LAYOUT_RIGHT_EDGE)
    assertTrue(
      "A right-edge slice in a real accessibility tree SHOULD be flagged truncated: ${assessment.reason}",
      assessment.looksTruncated,
    )
  }

  /**
   * Before/after proof of the fix on the exact failure mode it targets: a screen that is *quiet but
   * partial* (only a right-edge slice present) before the rest of its content lands — the
   * [CoverageFixtureActivity.LAYOUT_LATE_FILL] fixture, standing in for Compose committing its
   * semantics late.
   *
   * - BEFORE — a raw capture that does NOT wait for completeness (`awaitStable = false`) sees the
   *   partial right-edge slice. (The pre-fix stability-only gate lands on the same partial tree
   *   here: the right-edge content is already quiet and the rest hasn't been added, so stability has
   *   nothing to wait for.) Selectors/asserts run against this would miss the late content — the
   *   reported bug.
   * - AFTER — the production gated capture (`awaitStable = true`) detects the right-edge slice,
   *   holds (within the 1s cap), and recovers the COMPLETE tree once the late content lands.
   *
   * **The fixture, not the host, decides when the screen fills in.** It withholds the rest of its
   * content until [CoverageFixtureActivity.releaseLateFill], which this test calls on a background
   * thread [LATE_FILL_RELEASE_DELAY_MS] after starting the gated capture. That inverts what used to
   * be a race: the fixture previously filled itself in on a 600ms timer, so on any host quick enough
   * to have already rendered by the time the check ran, the BEFORE state was unobservable — and the
   * case answered that by `Assume`-ing itself away. A silent skip on exactly the fast hosts this
   * runs on is the worst of both worlds: it reports green while asserting nothing, so a regression
   * in the gate it exists to protect lands unnoticed. Both states are now reachable on every host,
   * and both are asserted.
   */
  @Test
  fun lateRenderingScreen_gatedCaptureRecoversWhatARawCaptureMisses() {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent =
      Intent(context, CoverageFixtureActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(CoverageFixtureActivity.EXTRA_LAYOUT, CoverageFixtureActivity.LAYOUT_LATE_FILL)

    ActivityScenario.launch<CoverageFixtureActivity>(intent).use {
      // BEFORE: the screen holds its partial state until we release it, so one raw capture (no
      // completeness wait) is enough — no polling for a window that might already have closed.
      TrailblazeAccessibilityService.waitForSettled(timeoutMs = 5_000)
      val before =
        assessTree(TrailblazeAccessibilityService.captureMergedScreenTrees(awaitStable = false).accessibilityNode)

      // AFTER: release the withheld content a fixed interval after the gated capture STARTS, so the
      // gate has to actually hold a quiet partial tree and re-fetch — the recovery under test.
      // Released off this thread because the gated capture blocks it.
      //
      // The delay is measured from `gateStartedAtMs`, which the main thread publishes immediately
      // before entering the capture, rather than from when this thread was spawned. Anchoring it to
      // the capture is what guarantees the gate's FIRST sample lands on the partial screen: timed
      // from any earlier point, a capture that took longer than the delay to get going would begin
      // against an already-complete screen and never exercise the hold at all, while still
      // satisfying the "returned after the release" assertion below.
      //
      // The delay also has to sit AFTER the gate's first completeness assessment, which the
      // stability layer reaches once the tree has been quiet for its 100ms window — and this screen
      // is quiet from the start, nothing is animating. That the hold is genuinely exercised is
      // verified by mutation rather than assumed: removing only the completeness hold from
      // `awaitTreeStable` (leaving the detector intact) fails the AFTER assertion below.
      val gateStartedAtMs = AtomicLong(0)
      val releasedAtMs = AtomicLong(0)
      val releaseFailure = AtomicReference<Throwable?>(null)
      // Daemon so a releaser left parked by an assertion failure above cannot hold the
      // instrumentation process open for the fixture's 10s release timeout.
      val releaser = thread(name = "late-fill-release", isDaemon = true) {
        try {
          while (gateStartedAtMs.get() == 0L) Thread.sleep(1)
          val waitMs = gateStartedAtMs.get() + LATE_FILL_RELEASE_DELAY_MS - SystemClock.elapsedRealtime()
          if (waitMs > 0) Thread.sleep(waitMs)
          // The fixture reports the instant the content landed, read on its own main thread. This
          // thread cannot time that itself: before the call names a moment the screen was still
          // partial, and after it names whenever this thread happened to be rescheduled.
          releasedAtMs.set(CoverageFixtureActivity.releaseLateFill())
        } catch (t: Throwable) {
          releaseFailure.set(t)
        }
      }
      gateStartedAtMs.set(SystemClock.elapsedRealtime())
      val after =
        assessTree(TrailblazeAccessibilityService.captureMergedScreenTrees(awaitStable = true).accessibilityNode)
      val gatedCaptureReturnedAtMs = SystemClock.elapsedRealtime()
      releaser.join()
      // Fail this case on a releaser-thread throwable — a missing withheld fill, or the fixture's
      // release timeout. Left uncaught it reaches Android's default handler, which takes the whole
      // instrumentation process down as a crash while `join()` returns normally and the assertions
      // below decide the outcome without the release ever having happened.
      releaseFailure.get()?.let {
        throw AssertionError("Releasing the withheld late content failed: ${it.message}", it)
      }

      Console.log(
        "[capture-coverage-test] late-fill BEFORE(raw): truncated=${before.looksTruncated} " +
          "content=${before.contentNodes} hCov=${before.horizontalCoverage} :: ${before.reason}",
      )
      Console.log(
        "[capture-coverage-test] late-fill AFTER(gated): truncated=${after.looksTruncated} " +
          "content=${after.contentNodes} hCov=${after.horizontalCoverage} :: ${after.reason}",
      )

      assertTrue(
        "BEFORE (raw capture, content still withheld) must see the partial right-edge slice — " +
          "this is the state the gate exists to detect: ${before.reason}",
        before.looksTruncated,
      )
      assertFalse(
        "AFTER (gated capture) should recover the complete tree: ${after.reason}",
        after.looksTruncated,
      )
      assertTrue(
        "AFTER should contain the late content the raw capture missed " +
          "(${before.contentNodes} -> ${after.contentNodes})",
        after.contentNodes > before.contentNodes,
      )
      // The gate cannot have seen the complete tree before the content was released, so returning
      // no earlier than the release is proof it HELD rather than lucking into an already-full
      // screen. Paired with anchoring the release to the capture's start, this brackets the hold on
      // both sides: the gate began against a partial screen and did not return until it filled in.
      // The instant compared against is the fixture's own — the ms the content went onto the view
      // tree, not when this thread noticed — so the bracket has no scheduling slack in it. Both
      // ends read `SystemClock.elapsedRealtime`, so a wall-clock adjustment during the capture
      // cannot collapse or invert the interval being asserted.
      assertTrue(
        "The gated capture returned before the late content was released, so it cannot have held " +
          "for completeness (returned ${gatedCaptureReturnedAtMs - releasedAtMs.get()}ms relative to release)",
        gatedCaptureReturnedAtMs >= releasedAtMs.get(),
      )
    }
  }

  /**
   * Launches the fixture in [layout], captures the live tree through the real capture path (which
   * runs the gate), and returns the [HierarchyCoverageAssessor] verdict over the captured nodes.
   */
  private fun assessFixture(layout: String): CaptureCoverage {
    // The fixture Activity is packaged in the instrumentation (androidTest) APK, so address it with
    // the instrumentation context, not targetContext. (This module is self-instrumenting, so the
    // two resolve to the same package today — but `.context` is the correct one by construction.)
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent =
      Intent(context, CoverageFixtureActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(CoverageFixtureActivity.EXTRA_LAYOUT, layout)

    ActivityScenario.launch<CoverageFixtureActivity>(intent).use {
      TrailblazeAccessibilityService.waitForSettled(timeoutMs = 5_000)
      val assessment =
        assessTree(TrailblazeAccessibilityService.captureMergedScreenTrees().accessibilityNode)
      Console.log(
        "[capture-coverage-test] layout=$layout truncated=${assessment.looksTruncated} " +
          "content=${assessment.contentNodes} hCov=${assessment.horizontalCoverage} :: ${assessment.reason}",
      )
      return assessment
    }
  }

  /**
   * The before/after on the REAL Compose `AccessibilityTruncationReproScreen` in the sample app:
   * the same right-edge-slice shape, on a genuine `AndroidComposeView`, rendered in two
   * deterministic states (no timing race) so the gate's verdict is reproducible.
   *
   * - PARTIAL (the mid-commit shape a capture lands on): right-edge content only → the gate flags it.
   * - COMPLETE (fully rendered): right-edge + the rest of the screen → the gate leaves it alone.
   *
   * This proves the detection that drives the fix works on real Compose geometry. The *recovery*
   * (holding a quiet partial tree until it fills in) is proven deterministically by the in-process
   * [lateRenderingScreen_gatedCaptureRecoversWhatARawCaptureMisses]; the gate is geometry-based, so
   * the recovery is identical regardless of whether the tree came from Compose or `View`s.
   *
   * Skips when the sample app isn't installed (it isn't for this module's own connected run in CI —
   * install it with `:examples:android-sample-app:installDebug` to exercise this locally).
   */
  @Test
  fun sampleAppComposeScreen_gateFlagsPartialTreeButNotComplete() {
    val pkg = "xyz.block.trailblaze.examples.sampleapp"
    Assume.assumeTrue(
      "Sample app ($pkg) not installed — skipping the Compose repro " +
        "(run :examples:android-sample-app:installDebug to enable it).",
      isPackageInstalled(pkg),
    )

    // PARTIAL Compose tree (right-edge slice only) — the shape a mid-commit capture sees.
    val partial = captureReproScreen(pkg, filled = false, expectTruncated = true)
    // COMPLETE Compose tree (right edge + the rest of the screen content).
    val complete = captureReproScreen(pkg, filled = true, expectTruncated = false)

    Console.log(
      "[capture-coverage-test] sample-app PARTIAL: truncated=${partial.looksTruncated} " +
        "content=${partial.contentNodes} hCov=${partial.horizontalCoverage} :: ${partial.reason}",
    )
    Console.log(
      "[capture-coverage-test] sample-app COMPLETE: truncated=${complete.looksTruncated} " +
        "content=${complete.contentNodes} hCov=${complete.horizontalCoverage} :: ${complete.reason}",
    )

    assertTrue(
      "A partial right-edge Compose tree SHOULD be flagged: ${partial.reason}",
      partial.looksTruncated,
    )
    assertFalse(
      "A complete Compose tree should NOT be flagged: ${complete.reason}",
      complete.looksTruncated,
    )
    assertTrue(
      "The complete tree should carry more content than the partial one " +
        "(${partial.contentNodes} -> ${complete.contentNodes})",
      complete.contentNodes > partial.contentNodes,
    )
  }

  /**
   * Launches the sample app's Compose repro screen in the requested [filled] state (clearing any
   * prior task so the new state takes), then captures + assesses through the gated path. Polls
   * briefly until the captured tree matches [expectTruncated] so a slow cross-process launch can't
   * make us capture the previous screen.
   */
  private fun captureReproScreen(
    pkg: String,
    filled: Boolean,
    expectTruncated: Boolean,
  ): CaptureCoverage {
    val context = InstrumentationRegistry.getInstrumentation().context
    context.startActivity(
      Intent()
        .setClassName(pkg, "$pkg.SampleAppActivity")
        .putExtra("tb_accessibility_truncation_repro", true)
        .putExtra("tb_accessibility_truncation_repro_filled", filled)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    val deadline = SystemClock.elapsedRealtime() + 8_000
    var assessment =
      assessTree(TrailblazeAccessibilityService.captureMergedScreenTrees().accessibilityNode)
    while (assessment.looksTruncated != expectTruncated && SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(100)
      assessment =
        assessTree(TrailblazeAccessibilityService.captureMergedScreenTrees().accessibilityNode)
    }
    return assessment
  }

  private fun isPackageInstalled(pkg: String): Boolean =
    try {
      InstrumentationRegistry.getInstrumentation().context.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Exception) {
      false
    }

  /** Runs the [HierarchyCoverageAssessor] over a captured tree exactly as the gate does. */
  private fun assessTree(node: AccessibilityNode?): CaptureCoverage {
    val (screenWidth, screenHeight) = TrailblazeAccessibilityService.getScreenDimensions()
    val bounds = ArrayList<HierarchyCoverageAssessor.NodeBounds>()
    collectBounds(node, bounds)
    return HierarchyCoverageAssessor.assess(bounds, screenWidth, screenHeight)
  }

  /** Flattens the captured [AccessibilityNode] tree into the bounds the assessor consumes. */
  private fun collectBounds(
    node: AccessibilityNode?,
    out: MutableList<HierarchyCoverageAssessor.NodeBounds>,
  ) {
    if (node == null) return
    val b = node.boundsInScreen
    out.add(
      HierarchyCoverageAssessor.NodeBounds(
        left = b?.left ?: 0,
        top = b?.top ?: 0,
        right = b?.right ?: 0,
        bottom = b?.bottom ?: 0,
        isVisibleToUser = node.isVisibleToUser,
        hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank(),
      ),
    )
    node.children.forEach { collectBounds(it, out) }
  }

  companion object {
    /**
     * How long after the gated capture starts the withheld content is released.
     *
     * Sized against the capture gate's own 1s hard cap: late enough that the gate provably has to
     * hold a quiet partial tree and re-fetch (rather than finding the screen already complete), and
     * early enough to leave the gate most of its budget to notice and recover. Unlike the timer it
     * replaces, this is not a race — the fixture stays partial until this fires, however fast or
     * slow the host is.
     */
    private const val LATE_FILL_RELEASE_DELAY_MS = 200L
  }
}
