package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Before
import org.junit.Rule

/**
 * [AndroidTestTrailblazeTest] with the default harness for an app launched by intent — the shape
 * every "attach to the installed APK" module wants, supplied once so a consumer's base class is
 * pure data:
 *
 * ```kotlin
 * abstract class MyAppFarmTrailblazeTest : LaunchedAppTrailblazeTest() {
 *   override val hostAppTarget by lazy { AssetBackedHostAppTarget.fromAsset("trails/config/targets/myapp.yaml") }
 * }
 * ```
 *
 * What the default harness is:
 * - a Compose rule, created EMPTY because the Activity is launched by intent rather than by the
 *   rule (the driver never creates one itself — a second rule would install a second clock and
 *   idling bridge and can deadlock an app's existing harness);
 * - an `@Before` that launches the app under test at its launcher entry point
 *   ([AppUnderTestLauncher], which also initializes OkHttp's Android platform directly — see its
 *   KDoc for why that is not the same as running the app's startup init, which the instrumented
 *   process does for itself);
 * - a target whose Activity is resolved live on every capture, so recreation mid-trail can't
 *   stale it.
 *
 * An app whose harness differs — its own Activity rule, no launcher entry point, an existing
 * Compose rule — subclasses [AndroidTestTrailblazeTest] directly instead; this class is the
 * default, not the contract.
 */
abstract class LaunchedAppTrailblazeTest : AndroidTestTrailblazeTest() {

  /**
   * Default order (outside [trailblazeRule], which is `LAST`) so the rule chain has a live Compose
   * bridge before a trail runs.
   */
  @get:Rule val composeRule: AndroidComposeTestRule<*, *> =
    createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  @Before
  fun launchAppUnderTest() {
    AppUnderTestLauncher.launchAppUnderTest()
  }

  final override fun createTarget(): AndroidTestTarget =
    RuleBackedAndroidTestTarget(
      activityProvider = { AppUnderTestLauncher.awaitResumedActivity() },
      composeTestRule = composeRule,
    )
}
