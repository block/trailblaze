package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the capture-time settle gate keeps waiting on a quiet tree because a window transition is
 * still animating, and when it lets the capture through.
 */
class WindowTransitionProbeTest {

  /** Trimmed from `dumpsys SurfaceFlinger --list` on an API 34 emulator, mid activity launch. */
  private val midTransition =
    """
    Display 4619827259835644672 HWC layers:
    Transition Root: Task=37#1350
    Task=37#1350
    Transition Root: ActivityRecord{2892aad u0 com.android.settings/.homepage.DeepLinkHomepageActivity t37}#1354
    Surface(name=8394cc5 StatusBar)/@0x1e6f041 - animation-leash of insets_animation#120
    """.trimIndent()

  /** The same device at rest: the IME and status-bar insets leashes are always present. */
  private val atRest =
    """
    Display 4619827259835644672 HWC layers:
    Task=37#1350
    Surface(name=8394cc5 StatusBar)/@0x1e6f041 - animation-leash of insets_animation#120
    Surface(name=f4aef06 InputMethod)/@0x5d2 - animation-leash of insets_animation#130
    """.trimIndent()

  @Test
  fun `the probe runs from API 34 and never on the API 29 lanes, whose dumps it cannot read`() {
    assertFalse(WindowTransitionProbe.supportedOn(29))
    assertFalse(WindowTransitionProbe.supportedOn(33))
    assertTrue(WindowTransitionProbe.supportedOn(34))
    assertTrue(WindowTransitionProbe.supportedOn(36))
  }

  @Test
  fun `a Transition Root layer means a window transition is playing`() {
    assertTrue(WindowTransitionProbe.listShowsPlayingTransition(midTransition))
  }

  @Test
  fun `the always-present insets animation leashes are not a transition`() {
    assertFalse(WindowTransitionProbe.listShowsPlayingTransition(atRest))
  }

  /** One activity record from `dumpsys activity activities` on an API 34 emulator, trimmed. */
  private fun record(component: String, state: String, visibleRequested: Boolean, visible: Boolean) =
    """
      * Hist  #0: ActivityRecord{d71a9a5 u0 $component t158}
        packageName=${component.substringBefore('/')}
        state=$state delayedResume=false finishing=false
        keysPaused=false inHistory=true idle=true
        mVisibleRequested=$visibleRequested mVisible=$visible mClientVisible=$visible reportedDrawn=$visible reportedVisible=$visible
    """.trimIndent()

  private fun activitiesDump(vararg records: String) =
    """
    ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
    Display #0 (activities from top to bottom):
      * Task{d3c311f #158 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=2}
        topResumedActivity=ActivityRecord{7b57cbe u0 com.android.settings/.Settings t158}
    """.trimIndent() + "\n" + records.joinToString("\n")

  private val settingsShown = record("com.android.settings/.Settings", "RESUMED", visibleRequested = true, visible = true)
  private val launcherStopped =
    record("com.android.launcher3/.uioverrides.QuickstepLauncher", "STOPPED", visibleRequested = false, visible = false)

  @Test
  fun `activities whose requested and actual visibility agree are at rest`() {
    assertFalse(WindowTransitionProbe.activitiesShowVisibilityInFlux(activitiesDump(settingsShown, launcherStopped)))
  }

  /** Before the incoming activity's window is even asked for, the old screen is all there is. */
  @Test
  fun `an activity still initializing is an activity change under way`() {
    val incoming = record(
      "com.android.settings.intelligence/.search.SearchActivity",
      "INITIALIZING",
      visibleRequested = false,
      visible = false,
    )
    assertTrue(WindowTransitionProbe.activitiesShowVisibilityInFlux(activitiesDump(incoming, settingsShown)))
  }

  /**
   * The first sample of a cold launch on an API 34 emulator: the incoming activity is not yet asked
   * to show, the outgoing one still reads shown, and only the starting record gives it away.
   */
  @Test
  fun `the activity a cold launch is starting is an activity change under way`() {
    val dump =
      """
      ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
      Display #0 (activities from top to bottom):
        * Task{f21e107 #9 type=standard A=10113:com.android.settings.intelligence U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
          * Hist  #0: ActivityRecord{e75db46 u0 com.android.settings.intelligence/.search.SearchActivity t9}
            state=INITIALIZING delayedResume=false finishing=false
            mVisibleRequested=false mVisible=false mClientVisible=false reportedDrawn=false reportedVisible=false
        * Task{7329cb1 #8 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
          * Hist  #0: ActivityRecord{32ef358 u0 com.android.settings/.Settings t8}
            state=PAUSING delayedResume=false finishing=false
            mVisibleRequested=true mVisible=true mClientVisible=true reportedDrawn=true reportedVisible=true
      """.trimIndent()
    assertTrue(WindowTransitionProbe.activitiesShowVisibilityInFlux(dump))
  }

  /** Reproduced on an API 36 emulator: a `startActivities` app left in recents behind Settings. */
  @Test
  fun `an activity parked initializing in a background task is not an activity change`() {
    val parked = record("com.example.deeplink/.DetailActivity", "INITIALIZING", visibleRequested = false, visible = false)
    val dump = activitiesDump(settingsShown) + "\n" +
      """
        * Task{a1b2c3d #160 type=standard A=10200:com.example.deeplink U=0 visible=false visibleRequested=false mode=fullscreen translucent=false sz=2}
      """.trimIndent() + "\n" + record("com.example.deeplink/.HomeActivity", "STOPPED", false, false) + "\n" + parked
    assertFalse(WindowTransitionProbe.activitiesShowVisibilityInFlux(dump))
  }

  /** A deep link's synthesized back stack: the activity under the shown one waits to be navigated to. */
  @Test
  fun `an activity initializing below the top of a shown task is not an activity change`() {
    val shownTop = record("com.example.deeplink/.DetailActivity", "RESUMED", visibleRequested = true, visible = true)
    val below = record("com.example.deeplink/.HomeActivity", "INITIALIZING", visibleRequested = false, visible = false)
    assertFalse(WindowTransitionProbe.activitiesShowVisibilityInFlux(activitiesDump(shownTop, below)))
  }

  /** A cold start of another app's process sat in this state for ~300 ms on an API 34 emulator. */
  @Test
  fun `an activity asked to show that is not shown yet is an activity change under way`() {
    val incoming = record(
      "com.android.settings.intelligence/.search.SearchActivity",
      "RESUMED",
      visibleRequested = true,
      visible = false,
    )
    assertTrue(WindowTransitionProbe.activitiesShowVisibilityInFlux(activitiesDump(incoming, settingsShown)))
  }

  @Test
  fun `an activity asked to hide that is still shown is an activity change under way`() {
    val outgoing = record("com.android.settings/.Settings", "PAUSED", visibleRequested = false, visible = true)
    assertTrue(WindowTransitionProbe.activitiesShowVisibilityInFlux(activitiesDump(outgoing, launcherStopped)))
  }

  @Test
  fun `an activity change seen by the window manager holds without asking SurfaceFlinger`() {
    var layerListRead = false
    val playing = WindowTransitionProbe.combineProbeReads(
      activitiesInFlux = { true },
      transitionLayerPresent = {
        layerListRead = true
        false
      },
    )
    assertEquals(true, playing)
    assertFalse(layerListRead, "the second read is wasted time once the first says the screen is moving")
  }

  @Test
  fun `a transition layer alone is enough to hold`() {
    assertEquals(true, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { false }, transitionLayerPresent = { true }))
    assertEquals(true, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { null }, transitionLayerPresent = { true }))
  }

  @Test
  fun `one readable source saying nothing moves is enough to accept`() {
    assertEquals(false, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { false }, transitionLayerPresent = { false }))
    assertEquals(false, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { null }, transitionLayerPresent = { false }))
    assertEquals(false, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { false }, transitionLayerPresent = { null }))
  }

  @Test
  fun `no readable source is no evidence`() {
    assertEquals(null, WindowTransitionProbe.combineProbeReads(activitiesInFlux = { null }, transitionLayerPresent = { null }))
  }

  @Test
  fun `a quiet tree with no transition playing is accepted`() {
    assertEquals(QuietTreeVerdict.ACCEPT, decideQuietTree(transitionPlaying = false, heldForTransition = false))
  }

  @Test
  fun `a quiet tree under a playing transition is held`() {
    assertEquals(QuietTreeVerdict.HOLD, decideQuietTree(transitionPlaying = true, heldForTransition = false))
    assertEquals(QuietTreeVerdict.HOLD, decideQuietTree(transitionPlaying = true, heldForTransition = true))
  }

  /** The window's bounds move to their final place on the transition's last frame. */
  @Test
  fun `the end of a held transition restarts the quiet window instead of accepting`() {
    assertEquals(
      QuietTreeVerdict.RESTART_QUIET_WINDOW,
      decideQuietTree(transitionPlaying = false, heldForTransition = true),
    )
  }

  /** A device whose layer list cannot be read must not hold every capture to the hard cap. */
  @Test
  fun `an unreadable layer list is no evidence and the tree is accepted`() {
    assertEquals(QuietTreeVerdict.ACCEPT, decideQuietTree(transitionPlaying = null, heldForTransition = false))
  }

  @Test
  fun `the transition gate is on unless its sysprop turns it off`() {
    assertTrue(SettleSwitches.defaultOn(""))
    assertTrue(SettleSwitches.defaultOn("1"))
    assertFalse(SettleSwitches.defaultOn("0"))
    assertFalse(SettleSwitches.defaultOn("FALSE"))
  }

  @Test
  fun `quiet-verdict reuse is off unless its sysprop turns it on`() {
    assertFalse(SettleSwitches.defaultOff(""))
    assertFalse(SettleSwitches.defaultOff("0"))
    assertTrue(SettleSwitches.defaultOff("1"))
    assertTrue(SettleSwitches.defaultOff("true"))
  }

  /** A capture's probe on a fake clock where every read takes [readMs] and answers [answers] in turn. */
  private class SlowShell(private val readMs: Long, vararg answers: Boolean?) {
    var nowMs = 0L
    val budgetsAsked = mutableListOf<Long>()
    private val answers = ArrayDeque(answers.toList())

    fun probe() = CaptureTransitionProbe(
      enabled = true,
      deadlineUptimeMs = 1_000L,
      slowReadMs = 250L,
      nowUptimeMs = { nowMs },
      read = { budgetMs ->
        budgetsAsked += budgetMs
        nowMs += readMs
        answers.removeFirst()
      },
    )
  }

  @Test
  fun `a transition seen by a slow read keeps the capture holding after the reads stop`() {
    val shell = SlowShell(readMs = 300L, true)
    val probe = shell.probe()

    assertEquals(QuietTreeVerdict.HOLD, decideQuietTree(probe.ask(), heldForTransition = false))
    shell.nowMs += 200L
    // The transition is never seen ending, so it is not treated as ended.
    assertEquals(QuietTreeVerdict.HOLD, decideQuietTree(probe.ask(), heldForTransition = true))
    assertEquals(listOf(1_000L), shell.budgetsAsked)
  }

  @Test
  fun `after a slow read that saw no transition the capture judges the tree alone`() {
    val shell = SlowShell(readMs = 300L, false)
    val probe = shell.probe()

    assertEquals(false, probe.ask())
    assertEquals(null, probe.ask())
    assertEquals(QuietTreeVerdict.ACCEPT, decideQuietTree(probe.ask(), heldForTransition = false))
    assertEquals(1, shell.budgetsAsked.size)
  }

  @Test
  fun `fast reads keep being asked, each within what is left of the capture's cap`() {
    val shell = SlowShell(readMs = 20L, true, false)
    val probe = shell.probe()

    assertEquals(true, probe.ask())
    shell.nowMs += 100L
    assertEquals(false, probe.ask())
    assertEquals(QuietTreeVerdict.RESTART_QUIET_WINDOW, decideQuietTree(false, heldForTransition = true))
    assertEquals(listOf(1_000L, 880L), shell.budgetsAsked)
  }
}
