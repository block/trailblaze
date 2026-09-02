package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The trail-file path, end to end on a device: no Kotlin describes what this test does.
 *
 * The `@Test` body is the shell the `xyz.block.trailblaze.android-gradle` plugin generates, and
 * [AndroidTestTrailblazeTest] supplies everything else, so this is also the smallest example of
 * what a consumer app writes. The steps live in
 * `assets/trails/AndroidTestTrailReplayOnDeviceTest/replaysMixedViewAndComposeTrail.trail.yaml`.
 *
 * It doubles as the CI lane's report producer: replaying through the rule logs a session, per-step
 * frames and a trace to `Download/trailblaze-logs`, which the farm step pulls and turns into the
 * Trailblaze report. A run of only the hand-written driver tests leaves a JUnit verdict and
 * nothing to look at.
 */
class AndroidTestTrailReplayOnDeviceTest : AndroidTestTrailblazeTest() {

  // Owned here, as a consumer app owns its harness: the driver never creates a Compose rule.
  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var launched: MixedUiFixtureActivity? = null
    scenario.onActivity { launched = it }
    fixture = checkNotNull(launched)
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  override fun createTarget(): AndroidTestTarget =
    RuleBackedAndroidTestTarget(
      activityProvider = { fixture },
      composeTestRule = composeRule,
    )

  @Test
  fun replaysMixedViewAndComposeTrail() = runFromAsset()
}
