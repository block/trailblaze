package xyz.block.trailblaze.android.accessibility

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Test-only Activity that renders a deterministic accessibility tree for
 * [HierarchyCoverageOnDeviceTest]. The intent extra [EXTRA_LAYOUT] selects between:
 *
 * - [LAYOUT_FULL_WIDTH]: content-bearing `TextView`s spanning the full screen width — a normal
 *   screen the coverage check must leave alone.
 * - [LAYOUT_RIGHT_EDGE]: the same content jammed into a narrow band flush against the right edge,
 *   with the left ~75% of the screen empty — the "rightmost slice" truncation symptom this gate
 *   targets (`x=864..1080 on a 1080px screen`).
 * - [LAYOUT_LATE_FILL]: the right-edge slice first, then the rest of the screen's content is added
 *   [LATE_FILL_DELAY_MS] later (via a delayed post on the main thread). This reproduces the failure
 *   mode the fix targets: at first the captured tree is *quiet* (nothing animating) yet *partial*
 *   (only the right-edge slice), and the missing content arrives shortly after — so a capture that
 *   settles on stability alone freezes the partial tree, while the completeness gate holds for the
 *   full one. The async post stands in for Compose committing its semantics late; the gate is
 *   geometry-based and driver-agnostic, so the `View` tree exercises the exact same gate path.
 *
 * Plain Android `View`s (no Compose) on purpose: keeps the Compose toolchain out of this module's
 * androidTest while still producing a real on-device `AccessibilityNodeInfo` hierarchy. (A faithful
 * Compose reproduction of the same before/after lives in the sample app — see
 * `AccessibilityTruncationReproScreen`.)
 */
class CoverageFixtureActivity : Activity() {
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
        // The rest of the screen's content arrives after a short delay — quiet-but-partial now,
        // complete soon. Posted to the main thread so it lands as a real later layout pass.
        root.postDelayed({ root.addView(fullWidthColumn(LATE_LABELS)) }, LATE_FILL_DELAY_MS)
      }
      else -> root.addView(fullWidthColumn(RIGHT_LABELS))
    }
    setContentView(root)
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

  companion object {
    const val EXTRA_LAYOUT = "coverage_fixture_layout"
    const val LAYOUT_FULL_WIDTH = "full_width"
    const val LAYOUT_RIGHT_EDGE = "right_edge"
    const val LAYOUT_LATE_FILL = "late_fill"

    /**
     * How long the [LAYOUT_LATE_FILL] screen stays a quiet right-edge slice before the rest of the
     * content is added. Comfortably above the settle gate's quiet window (so a stability-only
     * capture lands on the partial tree) and below its 1s hard cap (so the completeness gate can
     * recover the full tree before giving up).
     */
    const val LATE_FILL_DELAY_MS = 600L

    private val RIGHT_LABELS = listOf("Alpha", "Bravo", "Charlie", "Delta", "Foxtrot", "Golf")
    private val LATE_LABELS = listOf("Hotel", "India", "Juliet", "Kilo", "Lima", "Mike")
  }
}
