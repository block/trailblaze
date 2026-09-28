package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.mobile.tools.AndroidForceStopTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.tools.FakeTrailblazeAgent
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * `openApp` promises one thing a caller relies on: when it returns, the app is on screen. These pin
 * the readings that promise rests on — the platform's answers parsed right, and each wait ending for
 * the right reason — using real command output captured from an API 35 emulator and an iOS
 * simulator rather than a device.
 */
class OpenAppReadinessTest {

  @Test
  fun `the launcher component is read from resolve-activity`() {
    val output = """
      priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
      com.android.settings/.Settings
    """.trimIndent()
    assertThat(AndroidOpenAppReadiness.parseLauncherComponent(output, SETTINGS))
      .isEqualTo("com.android.settings/.Settings")
  }

  @Test
  fun `the system chooser is not taken for the app's launcher`() {
    // What resolve-activity answers for an app with two launcher activities of equal priority.
    val output = """
      priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false
      android/com.android.internal.app.ResolverActivity
    """.trimIndent()
    assertThat(AndroidOpenAppReadiness.parseLauncherComponent(output, SETTINGS)).isNull()
  }

  @Test
  fun `query-activities gives the app's first launcher activity`() {
    val output = """
      2 activities found:
        Activity #0:
          priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
          com.android.settings/.Settings
        Activity #1:
          priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
          com.android.settings/.SecondLauncher
    """.trimIndent()
    assertThat(AndroidOpenAppReadiness.parseLauncherComponent(output, SETTINGS))
      .isEqualTo("com.android.settings/.Settings")
  }

  @Test
  fun `an app with no launcher activity resolves to nothing`() {
    assertThat(AndroidOpenAppReadiness.parseLauncherComponent("No activity found", SETTINGS)).isNull()
    assertThat(AndroidOpenAppReadiness.parseLauncherComponent("No activities found", SETTINGS)).isNull()
  }

  @Test
  fun `an app id that is not a package name never reaches the device`() {
    // The host transport joins arguments into one shell line, so this would run `reboot`.
    val hostile = "com.example; reboot"
    for (tool in listOf(OpenAppTrailblazeTool(hostile), AndroidForceStopTrailblazeTool(hostile))) {
      val result = runBlocking { tool.execute(androidContextWithNoDevice()) }
      assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
      assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
        .contains("valid Android package name")
    }
  }

  @Test
  fun `simctl's not-running answers are all read as not running`() {
    listOf(
      "An error was encountered processing the command (domain=com.apple.CoreSimulator.SimError, code=164):\n" +
        "found nothing to terminate",
      "Unable to terminate com.example.app: No such process",
      "The application com.example.app is not running.",
    ).forEach { assertThat(IosHostSimctlUtils.isNotRunningTerminateOutput(it)).isTrue() }
    assertThat(IosHostSimctlUtils.isNotRunningTerminateOutput("Invalid device: ABC")).isFalse()
  }

  @Test
  fun `a cold start reports its launch state and first-frame time`() {
    val start = AndroidOpenAppReadiness.parseAmStartWait(
      """
      Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.android.settings/.Settings }
      Status: ok
      LaunchState: COLD
      Activity: com.android.settings/.homepage.SettingsHomepageActivity
      TotalTime: 245
      WaitTime: 247
      Complete
      """.trimIndent(),
    )
    assertThat(start.succeeded).isTrue()
    assertThat(start.launchState).isEqualTo("COLD")
    assertThat(start.totalTimeMs).isEqualTo(245L)
    assertThat(start.broughtToFront).isFalse()
  }

  @Test
  fun `a running app brought to the front is a success, not a new start`() {
    val start = AndroidOpenAppReadiness.parseAmStartWait(
      """
      Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.android.settings/.Settings }
      Warning: Activity not started, its current task has been brought to the front
      Status: ok
      LaunchState: HOT
      Activity: com.android.settings/.homepage.SettingsHomepageActivity
      TotalTime: 90
      WaitTime: 92
      Complete
      """.trimIndent(),
    )
    assertThat(start.succeeded).isTrue()
    assertThat(start.broughtToFront).isTrue()
  }

  @Test
  fun `an activity that does not exist is a failure`() {
    val start = AndroidOpenAppReadiness.parseAmStartWait(
      """
      Starting: Intent { cmp=com.nope/.X }
      Error type 3
      Error: Activity class {com.nope/com.nope.X} does not exist.
      """.trimIndent(),
    )
    assertThat(start.succeeded).isFalse()
  }

  @Test
  fun `the idle flag is read from the app's own record, not a neighbour's`() {
    // The launcher below is idle; the app on top is not yet. Reading past the app's record into the
    // next one would report the app idle while its main thread is still starting up.
    val dump = dumpsys(appIdle = false)
    assertThat(AndroidOpenAppReadiness.activityIdleState(dump, "com.android.settings")).isEqualTo(false)
    assertThat(AndroidOpenAppReadiness.activityIdleState(dumpsys(appIdle = true), "com.android.settings"))
      .isEqualTo(true)
  }

  @Test
  fun `an app with no activity record has no idle answer`() {
    assertThat(AndroidOpenAppReadiness.activityIdleState(dumpsys(appIdle = true), "com.example.absent")).isNull()
  }

  @Test
  fun `the idle wait returns as soon as the app goes idle`() {
    val clock = FakeClock()
    val dumps = ArrayDeque(listOf(dumpsys(appIdle = false), dumpsys(appIdle = false), dumpsys(appIdle = true)))
    val idle = AndroidOpenAppReadiness.awaitActivityIdle(
      appId = "com.android.settings",
      nowMs = clock::now,
      sleep = clock::advance,
    ) { dumps.removeFirst() }
    assertThat(idle.idle).isTrue()
    assertThat(idle.waitedMs).isEqualTo(500L)
  }

  @Test
  fun `the idle wait gives up at its cap instead of failing`() {
    val clock = FakeClock()
    val idle = AndroidOpenAppReadiness.awaitActivityIdle(
      appId = "com.android.settings",
      timeoutMs = 1_000L,
      nowMs = clock::now,
      sleep = clock::advance,
    ) { "a dump this cannot read" }
    assertThat(idle.idle).isFalse()
    assertThat(idle.waitedMs).isEqualTo(1_000L)
  }

  @Test
  fun `iOS waits past the unchanged pre-launch screen until the new one holds still`() {
    val clock = FakeClock()
    val captures = ArrayDeque(listOf("springboard", "splash", "home", "home"))
    val readiness = awaitIosScreenReady(baseline = "springboard", nowMs = clock::now, sleep = clock::advance) {
      captures.removeFirst()
    }
    assertThat(readiness.description).contains("settled")
    assertThat(captures.size).isEqualTo(0)
  }

  @Test
  fun `iOS treats a screen that never changes as an app already in front`() {
    val clock = FakeClock()
    val readiness = awaitIosScreenReady(
      baseline = "home",
      unchangedGraceMs = 1_000L,
      nowMs = clock::now,
      sleep = clock::advance,
    ) { "home" }
    assertThat(readiness.description).contains("already in front")
    assertThat(clock.now()).isEqualTo(1_000L)
  }

  @Test
  fun `iOS stops waiting at its cap on a screen that keeps changing`() {
    val clock = FakeClock()
    var frame = 0
    val readiness = awaitIosScreenReady(
      baseline = "springboard",
      timeoutMs = 2_000L,
      nowMs = clock::now,
      sleep = clock::advance,
    ) { "frame ${frame++}" }
    assertThat(readiness.description).contains("still changing")
  }

  /**
   * An Android context with no device executor: a tool that got past its own input check would
   * fail on the missing executor instead, with a different message.
   */
  private fun androidContextWithNoDevice(): TrailblazeToolExecutionContext {
    val agent = FakeTrailblazeAgent()
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
    )
  }

  private class FakeClock {
    private var t = 0L
    fun now(): Long = t
    fun advance(ms: Long) {
      t += ms
    }
  }

  private companion object {
    const val SETTINGS = "com.android.settings"
  }

  /** Trimmed from a real `dumpsys activity activities` on an API 35 emulator. */
  private fun dumpsys(appIdle: Boolean) = """
    ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
    Display #0 (activities from top to bottom):
      * Task{7626a77 #20 type=standard A=1000:com.android.settings U=0 visible=true visibleRequested=true mode=fullscreen}
        topResumedActivity=ActivityRecord{7626a77 u0 com.android.settings/.Settings t20}
        * Hist  #0: ActivityRecord{7626a77 u0 com.android.settings/.Settings t20}
          packageName=com.android.settings processName=com.android.settings
          state=RESUMED delayedResume=false finishing=false
          keysPaused=false inHistory=true idle=$appIdle
      * Task{7768ebd #6 type=home A=10141:com.google.android.apps.nexuslauncher U=0 visible=false}
        * Hist  #1: ActivityRecord{7768ebd u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t6}
          state=STOPPED delayedResume=false finishing=false
          keysPaused=false inHistory=true idle=true
  """.trimIndent()
}
