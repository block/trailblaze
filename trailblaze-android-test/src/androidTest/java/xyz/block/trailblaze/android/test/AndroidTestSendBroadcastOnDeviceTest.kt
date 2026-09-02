package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.mobile.tools.AndroidSendBroadcastTrailblazeTool
import xyz.block.trailblaze.mobile.tools.BroadcastExtra
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * A dual-mode primitive dispatched through the in-process driver reaches the device, with no host.
 *
 * `android_sendBroadcast` is how an app harness puts the app under test into a starting state —
 * signed in, seeded, feature-flagged — instead of driving its UI there. On this driver there is no
 * CLI and no daemon to fall back on: the farm replays the trail inside the app's own process, so
 * either the tool works through `InstrumentationRegistry` or the harness cannot reach the app at
 * all. Real apps publish exactly such a receiver for sign-in, addressed by component; that is the
 * shape [BroadcastProofReceiver] stands in for.
 *
 * The assertion is the broadcast ARRIVING with its payload intact, not the tool reporting success.
 * A tool that resolved no executor returns an error and the latch times out; one that dropped the
 * extras returns success anyway, and only reading the delivered value catches that.
 *
 * What this does NOT cover: the receiver here belongs to the instrumentation's own package, so
 * delivery never crosses a package boundary. A real app's receiver lives in the TARGET package,
 * and reaching it relies on the instrumentation process running under the app's own UID. That is
 * what makes this driver work at all, and it is not something a self-instrumenting module can
 * assert about itself — proving it needs a lane whose test APK and app under test are separately
 * packaged.
 */
class AndroidTestSendBroadcastOnDeviceTest : AndroidTestTrailblazeTest() {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity

  @Before
  fun launchFixture() {
    BroadcastProofReceiver.reset()
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
  fun sendBroadcastReachesAComponentAddressedReceiverWithItsExtras() {
    // The instrumentation's own context, which is the package the manifest receiver belongs to.
    val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName

    val result = trailblazeRule.agent.runTrailblazeTools(
      listOf(
        AndroidSendBroadcastTrailblazeTool(
          action = BroadcastProofReceiver.ACTION,
          componentPackage = testPackage,
          componentClass = BroadcastProofReceiver::class.java.name,
          extras = listOf(BroadcastExtra(key = BroadcastProofReceiver.EXTRA_KEY, value = EXTRA_VALUE)),
        ),
      ),
      elementComparator = NoOpElementComparator,
    )

    assertTrue(
      BroadcastProofReceiver.delivered.await(BROADCAST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
      "The broadcast never reached the receiver. Tool result was: ${result.result}",
    )
    assertEquals(EXTRA_VALUE, BroadcastProofReceiver.lastExtraValue)
  }

  private companion object {
    const val EXTRA_VALUE = "carried-through"

    /** Generous on purpose: this bound exists so a lost broadcast fails instead of parking. */
    const val BROADCAST_TIMEOUT_SECONDS = 30L
  }
}
