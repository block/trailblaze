package xyz.block.trailblaze.android.accessibility

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only Activity that renders a deterministic accessibility tree for
 * [HierarchyCoverageOnDeviceTest]. The intent extra [EXTRA_LAYOUT] selects between:
 *
 * - [LAYOUT_FULL_WIDTH]: content-bearing `TextView`s spanning the full screen width — a normal
 *   screen the coverage check must leave alone.
 * - [LAYOUT_RIGHT_EDGE]: the same content jammed into a narrow band flush against the right edge,
 *   with the left ~75% of the screen empty — the "rightmost slice" truncation symptom this gate
 *   targets (`x=864..1080 on a 1080px screen`).
 * - [LAYOUT_LATE_FILL]: the right-edge slice only, with the rest of the screen's content WITHHELD
 *   until the test calls [releaseLateFill]. This reproduces the failure mode the fix targets: the
 *   captured tree is *quiet* (nothing animating) yet *partial* (only the right-edge slice), and the
 *   missing content arrives later — so a capture that settles on stability alone freezes the partial
 *   tree, while the completeness gate holds for the full one. The deferred fill stands in for
 *   Compose committing its semantics late; the gate is geometry-based and driver-agnostic, so the
 *   `View` tree exercises the exact same gate path.
 *
 *   The fill is released by the test rather than by a timer in here. A timer made the
 *   quiet-but-partial window a fixed race against however long the host took to get from launching
 *   this Activity to capturing a tree, and a host that won that race left the test asserting
 *   nothing.
 * - [LAYOUT_UNFETCHABLE_CHILD]: the full-width content plus one [UnfetchableChildView], whose
 *   accessibility node advertises a child that the app never hands over (`childCount` says 1,
 *   the fetch returns null). That is exactly what a service sees from an app whose main thread
 *   is blocked — every child fetch times out to null — reproduced deterministically, without
 *   blocking anything. Used by `PartialCaptureOnDeviceTest` to prove a capture knows it dropped
 *   a node and that `assertNotVisible` refuses to conclude anything from such a capture.
 *
 * Plain Android `View`s (no Compose) on purpose: keeps the Compose toolchain out of this module's
 * androidTest while still producing a real on-device `AccessibilityNodeInfo` hierarchy. (A faithful
 * Compose reproduction of the same before/after lives in the sample app — see
 * `AccessibilityTruncationReproScreen`.)
 */
class CoverageFixtureActivity : Activity() {

  /** The withheld fill THIS instance published, if any. Retracted in [onDestroy]. */
  private var ownLateFill: LateFill? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    // Push content below the status bar so every label is `isVisibleToUser` under the
    // edge-to-edge layout that targetSdk 35+ enables (otherwise the top labels render behind
    // the status bar and report not-visible, thinning the content count below the floor).
    root.setPadding(0, dp(120), 0, 0)

    when (intent?.getStringExtra(EXTRA_LAYOUT)) {
      LAYOUT_RIGHT_EDGE -> root.addView(rightEdgeColumn(RIGHT_LABELS))
      LAYOUT_LATE_FILL -> {
        root.addView(rightEdgeColumn(RIGHT_LABELS))
        // The rest of the screen's content is WITHHELD until the test calls [releaseLateFill],
        // rather than posted on a timer. A self-timed fill made the quiet-but-partial window a
        // fixed race against however long the host took to get from launching this Activity to
        // capturing a tree — a race the test could only lose silently. Held indefinitely, the
        // partial state is observable on every host, however fast.
        val lateFill = LateFill(root) { root.addView(fullWidthColumn(LATE_LABELS)) }
        ownLateFill = lateFill
        pendingLateFill.set(lateFill)
      }
      LAYOUT_UNFETCHABLE_CHILD -> {
        val column = fullWidthColumn(RIGHT_LABELS)
        column.addView(
          UnfetchableChildView(this).apply {
            contentDescription = UNFETCHABLE_HOST_LABEL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
          },
        )
        root.addView(column)
      }
      else -> root.addView(fullWidthColumn(RIGHT_LABELS))
    }
    setContentView(root)
  }

  /**
   * Retracts this instance's withheld fill so it cannot outlive the Activity that owns it. Without
   * this, a case that never reaches [releaseLateFill] — an assertion ahead of it throwing — leaves a
   * destroyed Activity's root pending for the rest of the process, and the next call posts to a
   * detached `View` whose run queue never drains: a 10s timeout blaming a wedged main thread, not
   * the stale state that [releaseLateFill]'s single-shot contract promises to report.
   *
   * Compare-and-set rather than an unconditional clear, because a newer fixture's `onCreate` can run
   * before this `onDestroy`: only retract the entry still pointing at us.
   */
  override fun onDestroy() {
    ownLateFill?.let { pendingLateFill.compareAndSet(it, null) }
    ownLateFill = null
    super.onDestroy()
  }

  /**
   * A view whose accessibility node claims one virtual child and then refuses to produce it. The
   * host node is normal (bounds, class, content description); `addChild` makes `childCount` read
   * 1 on the service side, and the provider returns null for that id, so the service's
   * `getChild(0)` comes back null — the same shape as a fetch that timed out against a blocked
   * app.
   */
  private class UnfetchableChildView(context: Context) : View(context) {
    private val provider = object : AccessibilityNodeProvider() {
      override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId != HOST_VIEW_ID) return null
        val info = AccessibilityNodeInfo.obtain(this@UnfetchableChildView)
        onInitializeAccessibilityNodeInfo(info)
        info.addChild(this@UnfetchableChildView, PHANTOM_CHILD_ID)
        return info
      }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

    private companion object {
      const val PHANTOM_CHILD_ID = 1
    }
  }

  /** A narrow column of labelled rows jammed flush against the right edge. */
  private fun rightEdgeColumn(labels: List<String>): LinearLayout =
    column(labels).apply {
      layoutParams =
        FrameLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.TOP)
    }

  /** A full-width column of labelled rows. */
  private fun fullWidthColumn(labels: List<String>): LinearLayout =
    column(labels).apply {
      layoutParams =
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

  private fun column(labels: List<String>): LinearLayout {
    val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    labels.forEach { label ->
      ll.addView(
        TextView(this).apply {
          text = label
          layoutParams =
            LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        },
      )
    }
    return ll
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  /** The remainder of a [LAYOUT_LATE_FILL] screen, and the view to add it to, once released. */
  private class LateFill(val root: ViewGroup, val addContent: () -> Unit)

  companion object {
    const val EXTRA_LAYOUT = "coverage_fixture_layout"
    const val LAYOUT_FULL_WIDTH = "full_width"
    const val LAYOUT_RIGHT_EDGE = "right_edge"
    const val LAYOUT_LATE_FILL = "late_fill"
    const val LAYOUT_UNFETCHABLE_CHILD = "unfetchable_child"

    /** Content description of the host whose child never fetches, so a test can find it. */
    const val UNFETCHABLE_HOST_LABEL = "Phantom host"

    /**
     * Set by a foregrounded [LAYOUT_LATE_FILL] fixture; consumed once by [releaseLateFill], or
     * retracted by [onDestroy] if the owning Activity goes away first. Never outlives its Activity.
     */
    private val pendingLateFill = AtomicReference<LateFill?>(null)

    /**
     * Adds the withheld remainder of the [LAYOUT_LATE_FILL] screen, and returns once it is on the
     * view tree.
     *
     * This is what makes the completeness gate's recovery observable without racing the host: the
     * screen stays quiet-but-partial for exactly as long as the caller leaves it that way, and
     * becomes complete at a moment the caller picks. Single-shot — the pending fill is consumed, so
     * a second call (or a call with no late-fill fixture in the foreground) fails loudly instead of
     * silently doing nothing.
     *
     * Safe to call from a background thread; the content is added on the main thread, as a real
     * later layout pass, exactly as the previous timer-posted version did.
     *
     * @return the [SystemClock.elapsedRealtime] ms at which the content actually landed on the view
     *   tree, read on the main thread inside the post. Monotonic rather than wall-clock, so a
     *   caller bracketing against it is not thrown off by the device retiming its clock mid-test.
     *   A caller that timestamped around this call instead would name
     *   the wrong moment in both directions: before it, a moment the screen was still partial;
     *   after it, a moment however long it took the calling thread to be scheduled once the latch
     *   dropped. Callers assert against the release instant, so the fixture reports it.
     */
    fun releaseLateFill(): Long {
      val lateFill = checkNotNull(pendingLateFill.getAndSet(null)) {
        "No withheld late fill to release — is a LAYOUT_LATE_FILL fixture in the foreground?"
      }
      val added = CountDownLatch(1)
      val addedAtMs = AtomicLong(0)
      lateFill.root.post {
        lateFill.addContent()
        addedAtMs.set(SystemClock.elapsedRealtime())
        added.countDown()
      }
      check(added.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        "The late content was not added within ${RELEASE_TIMEOUT_MS}ms — the fixture's main thread " +
          "never ran the post, so the screen never became complete."
      }
      return addedAtMs.get()
    }

    /**
     * Hang containment on [releaseLateFill], not a performance budget: adding a handful of
     * `TextView`s to an already-laid-out screen is immediate, so this bound only turns a wedged
     * fixture main thread into an attributable failure instead of a parked test run.
     */
    private const val RELEASE_TIMEOUT_MS = 10_000L

    /**
     * First label rendered by every layout. Exposed so a test can build a selector that genuinely
     * resolves against this screen — see [RecordedCoordinateFallbackOnDeviceTest].
     */
    const val FIRST_LABEL = "Alpha"

    private val RIGHT_LABELS = listOf(FIRST_LABEL, "Bravo", "Charlie", "Delta", "Foxtrot", "Golf")
    private val LATE_LABELS = listOf("Hotel", "India", "Juliet", "Kilo", "Lima", "Mike")
  }
}
