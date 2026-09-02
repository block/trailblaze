package xyz.block.trailblaze.android.test

import org.junit.Rule
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * Base class for trail-file tests that replay in-process through the ANDROID_TEST driver.
 *
 * An app subclasses this once, wiring [createTarget] to its existing test harness — its Activity
 * rule, its Compose rule, its DI fixtures. From then on a test is a trail file:
 *
 * ```kotlin
 * class CheckoutTest : MyAppTrailblazeTest() {
 *   @Test fun addsItemToCart() = runFromAsset()
 * }
 * ```
 *
 * with `src/androidTest/assets/trails/CheckoutTest/addsItemToCart.trail.yaml` supplying the steps.
 * Point `trailblazeAndroid.baseClassFqn` at the app's subclass and the
 * `xyz.block.trailblaze.android-gradle` plugin generates even that shell, one `@Test` per trail.
 *
 * ## Rule ordering
 *
 * The app's own rules must be **outside** this one — the target resolves an Activity, so the
 * Activity has to exist by the time a trail runs. Subclasses that own rules therefore declare them
 * with a lower [Rule.order] than [trailblazeRule]'s (JUnit applies lower order outermost).
 */
abstract class AndroidTestTrailblazeTest {

  /**
   * The app's live test surface. Called once per test, after the app's own rules have run, so it
   * is free to read an Activity a rule just launched.
   */
  protected abstract fun createTarget(): AndroidTestTarget

  /**
   * Per-tool timing sink. Default discards; override to collect
   * [AndroidTestTiming] for benchmarking.
   */
  protected open val metricsSink: AndroidTestMetricsSink = AndroidTestMetricsSink.NONE

  /**
   * See [AndroidTestTrailblazeRule.captureStepSnapshots]. Defaults on, and off under
   * `-e trailblaze.captureStepSnapshots false` so a timing run can drop the per-step screenshot
   * without every app having to expose its own switch.
   */
  protected open val captureStepSnapshots: Boolean =
    AndroidTestInstrumentation.booleanArg("trailblaze.captureStepSnapshots", default = true)

  /**
   * See [AndroidTestTrailblazeRule.logToolCalls]. Override to `false` in an app whose
   * kotlinx.serialization runtime is older than Trailblaze's — the report loses its steps, but the
   * trails run.
   */
  protected open val logToolCalls: Boolean = true

  /**
   * See [AndroidTestTrailblazeRule.hostAppTarget]. Override with the app's target to turn on
   * scripted tools for these trails; the default null keeps them off.
   */
  protected open val hostAppTarget: TrailblazeHostAppTarget? = null

  @get:Rule(order = LAST)
  val trailblazeRule: AndroidTestTrailblazeRule by lazy {
    AndroidTestTrailblazeRule(
      targetProvider = { createTarget() },
      metricsSink = metricsSink,
      captureStepSnapshots = captureStepSnapshots,
      logToolCalls = logToolCalls,
      hostAppTarget = hostAppTarget,
    )
  }

  /** Runs the trail asset named by this test's class + method. */
  fun runFromAsset() = trailblazeRule.runFromAsset()

  protected companion object {
    /**
     * Innermost rule position. Subclasses declaring app rules leave them at the default order
     * (`Rule.DEFAULT_ORDER` = 0), which is outside this — see the class docs.
     */
    const val LAST: Int = Int.MAX_VALUE
  }
}
