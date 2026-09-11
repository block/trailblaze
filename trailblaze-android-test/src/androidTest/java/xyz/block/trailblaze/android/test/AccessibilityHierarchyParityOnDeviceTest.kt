package xyz.block.trailblaze.android.test

import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.accessibility.filterImportantForAccessibility
import xyz.block.trailblaze.android.accessibility.toAccessibilityNode
import xyz.block.trailblaze.android.accessibility.toTrailblazeNode
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * Would a canonical `androidAccessibility` selector resolve to the same element if the in-process
 * driver read the REAL accessibility hierarchy instead of translating onto its own tree?
 *
 * The in-process driver publishes `androidView`/`compose` node shapes built from live `View`
 * objects and the Compose semantics tree, and `TrailblazeNodeSelectorResolver` bridges canonical
 * selectors onto them. The in-process driver proposes deleting that bridge by reading
 * the accessibility hierarchy through the platform API instead. This test measures both halves of
 * whether that is safe and affordable, on one screen, against the same frame:
 *
 * **Arm A — the real hierarchy.** `uiAutomation.rootInActiveWindow` through the accessibility
 * driver's own mapper (`toAccessibilityNode().toTrailblazeNode()`), which is the point: it is THE
 * mapper that driver uses, not a second copy whose fidelity would be the thing in question.
 *
 * **Arm B — today's in-process tree.** `AndroidHybridHierarchyCollector`, resolved through the
 * canonical-onto-native bridge.
 *
 * Bounds are the comparison, not node counts. The two trees are known to disagree on how many
 * nodes it takes to express a screen — a separate selector-portability suite covers that — while
 * agreeing on where
 * each element is. A selector is portable exactly when both trees send a tap to the same pixels, so
 * "same resolved bounds" is the property worth asserting and "same node count" is not.
 *
 * Reporting is deliberately exhaustive rather than reduced to a pass/fail, because the interesting
 * output is WHICH selectors diverge and in which direction. The `composeTestTagRegex` and
 * `resourceIdRegex` cases are expected to diverge in the real hierarchy's favour: they are two of
 * the predicates the bridge fails closed on.
 */
class AccessibilityHierarchyParityOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private var scenario: ActivityScenario<MixedUiFixtureActivity>? = null
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget

  @Before
  fun launchFixture() {
    val launched = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    scenario = launched
    var activity: MixedUiFixtureActivity? = null
    launched.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target =
      RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule)

    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    // FLAG_RETRIEVE_INTERACTIVE_WINDOWS is what makes `rootInActiveWindow` answer at all.
    // FLAG_REPORT_VIEW_IDS gates `viewIdResourceName`, which is null without it — so without this
    // the `resourceIdRegex` row below would report a divergence that is an artifact of the test's
    // own service configuration rather than a property of either tree.
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
      flags = flags or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
  }

  @After
  fun releaseFixture() {
    scenario?.close()
  }

  /**
   * Named for what it asserts. The parity table this logs is the output worth having, but it is
   * REPORTED, not asserted — the only failing conditions are an empty tree on either side. A green
   * run is therefore evidence that both hierarchies came back, not evidence that they agree; read
   * the `SELECTOR PARITY` block for that. Pinning the observed 7/15 as an assertion would freeze
   * whatever this one screen happens to do into a contract.
   */
  @Test
  fun reportsCanonicalSelectorParityAcrossBothHierarchies() {
    // Warm both paths before either is timed or compared: the first `rootInActiveWindow` after a
    // `serviceInfo` change pays a reconnect, and crediting that to the hierarchy read is the exact
    // measurement bug that produced an earlier bogus "7.6x slower" figure for this comparison.
    repeat(3) { realHierarchy(); hybridHierarchy() }

    val realTimings = (1..5).map { measure { realHierarchy() } }
    val hybridTimings = (1..5).map { measure { hybridHierarchy() } }

    val real = realHierarchy()
    val realFiltered = real.filterImportantForAccessibility()
    val hybrid = hybridHierarchy()

    Log.i(TAG, "=== HIERARCHY COST (5 warm runs each) ===")
    Log.i(TAG, "real a11y tree, FULL mapper : ${realTimings.render()} ms")
    Log.i(TAG, "hybrid in-process collector : ${hybridTimings.render()} ms")
    Log.i(
      TAG,
      "nodes: real=${real.aggregate().size} realFiltered=${realFiltered.aggregate().size} " +
        "hybrid=${hybrid.aggregate().size}",
    )

    Log.i(TAG, "=== REAL TREE, addressable fields ===")
    real.aggregate().forEach { node ->
      val d = node.driverDetail as? xyz.block.trailblaze.api.DriverNodeDetail.AndroidAccessibility
      if (d != null) {
        Log.i(
          TAG,
          "  cls=${d.className} text=${d.text?.let { "\"$it\"" }} " +
            "desc=${d.contentDescription?.let { "\"$it\"" }} resId=${d.resourceId} " +
            "tag=${d.composeTestTag} clickable=${d.isClickable} bounds=${node.bounds}",
        )
      }
    }
    Log.i(TAG, "=== HYBRID TREE, addressable fields ===")
    hybrid.aggregate().forEach { node ->
      Log.i(TAG, "  ${node.driverDetail} bounds=${node.bounds}")
    }

    Log.i(TAG, "=== SELECTOR PARITY (real vs hybrid, by resolved bounds) ===")
    var agreed = 0
    var diverged = 0
    for ((label, selector) in CASES) {
      val realVerdict = describe(TrailblazeNodeSelectorResolver.resolve(real, selector))
      val filteredVerdict = describe(TrailblazeNodeSelectorResolver.resolve(realFiltered, selector))
      val hybridVerdict = describe(TrailblazeNodeSelectorResolver.resolve(hybrid, selector))
      val agree = realVerdict == hybridVerdict
      if (agree) agreed++ else diverged++
      Log.i(
        TAG,
        "${if (agree) "AGREE " else "DIFFER"} $label\n" +
          "    real     = $realVerdict\n" +
          "    filtered = $filteredVerdict\n" +
          "    hybrid   = $hybridVerdict",
      )
    }
    Log.i(TAG, "=== TOTAL: $agreed agree / $diverged differ, of ${CASES.size} ===")

    // Deliberately weak for now. This run exists to produce the table above; the table is what
    // says which per-selector assertions are true, and asserting an outcome before observing it
    // would only pin whatever this screen happens to do.
    assertTrue(real.aggregate().size > 1, "the real accessibility hierarchy came back empty")
    assertTrue(hybrid.aggregate().size > 1, "the in-process hierarchy came back empty")
  }

  /** The accessibility driver's own pipeline, run in-process against the app's own window. */
  private fun realHierarchy(): TrailblazeNode {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
    checkNotNull(root) { "rootInActiveWindow was null even with FLAG_RETRIEVE_INTERACTIVE_WINDOWS" }
    return root.toAccessibilityNode().toTrailblazeNode()
  }

  private fun hybridHierarchy(): TrailblazeNode =
    AndroidHybridHierarchyCollector.collect(fixture, target).trailblazeTree

  private fun measure(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000.0
  }

  private fun List<Double>.render(): String = joinToString { "%.1f".format(it) }

  /**
   * A verdict reduced to what a tool would act on: how many elements matched and where they are.
   * Node ids are excluded on purpose — the two trees assign them from independent counters, so
   * including them would make every row differ for a reason no trail can observe.
   */
  private fun describe(result: TrailblazeNodeSelectorResolver.ResolveResult): String =
    when (result) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch ->
        "single ${result.node.bounds}"
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches ->
        "ambiguous(${result.nodes.size}) ${result.nodes.map { it.bounds }}"
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> "none"
    }

  private companion object {
    const val TAG = "A11yHierarchyParity"

    private fun accessibility(
      match: DriverNodeMatch.AndroidAccessibility,
    ): TrailblazeNodeSelector = TrailblazeNodeSelector(androidAccessibility = match)

    /**
     * Canonical selectors of the shape a recorded trail carries, spanning both halves of the mixed
     * screen plus two predicates the bridge is known to fail closed on.
     */
    val CASES: List<Pair<String, TrailblazeNodeSelector>> = listOf(
      "text=View Button (View-backed)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_BUTTON_LABEL,
          ),
        ),
      "text=View status (View TextView)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_STATUS_INITIAL,
          ),
        ),
      "text=Row label (inside a clickable row)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(textRegex = MixedUiFixtureActivity.VIEW_ROW_LABEL),
        ),
      "text=Left (ambiguous pair)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_AMBIGUOUS_LEFT,
          ),
        ),
      "text=Visible Action (visible sibling)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_VISIBLE_ACTION,
          ),
        ),
      "text=Hidden Action (GONE sibling — must NOT match)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_HIDDEN_ACTION,
          ),
        ),
      "text=Compose Button (Compose, merged label)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.COMPOSE_BUTTON_LABEL,
          ),
        ),
      "text=Compose status (Compose Text)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.COMPOSE_STATUS_INITIAL,
          ),
        ),
      "text=Nested Compose (Compose in Compose)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.NESTED_COMPOSE_LABEL,
          ),
        ),
      "text=Embedded View Button (AndroidView interop)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.EMBEDDED_VIEW_BUTTON_LABEL,
          ),
        ),
      "className=android.widget.Button" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.widget\\.Button"),
        ),
      "isClickable=true AND text=View Button" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = MixedUiFixtureActivity.VIEW_BUTTON_LABEL,
            isClickable = true,
          ),
        ),
      // Fails closed on the bridge today: the View tree cannot answer either predicate.
      "composeTestTagRegex=compose_button (bridge fails closed)" to
        accessibility(
          DriverNodeMatch.AndroidAccessibility(
            composeTestTagRegex = MixedUiFixtureActivity.COMPOSE_BUTTON_TAG,
          ),
        ),
      // ONE exact resource name, against the fixture's only declared id. A `.+` wildcard here
      // would not measure resourceId parity at all: it matches every non-empty identifier, and
      // because the canonical-to-Compose bridge evaluates `resourceIdRegex` against `testTag`
      // (TrailblazeNodeSelectorResolver, the Compose arm), the hybrid verdict would be decided by
      // whichever Compose nodes happen to carry tags rather than by any resourceId at all.
      "resourceIdRegex=:id/view_status (one exact resource name)" to
        accessibility(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = ".*:id/view_status")),
      "isEditable=true (View EditText + Compose field)" to
        accessibility(DriverNodeMatch.AndroidAccessibility(isEditable = true)),
    )
  }
}
