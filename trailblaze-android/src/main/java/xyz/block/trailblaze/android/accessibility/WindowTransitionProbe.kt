package xyz.block.trailblaze.android.accessibility

import android.os.Build
import android.os.SystemClock
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.util.Console

/**
 * Whether the screen is still moving between activities: an activity opening or closing, the
 * launcher coming forward, a permission dialog appearing.
 *
 * The capture-time settle gate judges the screen by its accessibility tree going quiet. Two phases
 * of an activity change defeat that, and this probe covers both:
 *
 * - **Before the new window exists.** From the moment an activity is started until its window has
 *   drawn — a cold start of another app's process took ~450 ms on an API 34 emulator — the old
 *   screen sits there untouched and perfectly quiet. A capture taken then hands back the screen
 *   from before the tap. The window manager already knows: the incoming activity is
 *   `INITIALIZING` at the top of the task coming forward, or asked to be visible and not yet
 *   shown (`mVisibleRequested=true mVisible=false`). The outgoing one stays shown while asked to
 *   hide until the transition finishes. `dumpsys activity activities` lists both, see [activitiesShowVisibilityInFlux].
 * - **While the window animates.** The system animates the window's surface, not its views, so
 *   the tree can sit still while the window is still sliding in, and the bounds it reports (which
 *   element refs hash) jump only when the animation ends. SurfaceFlinger names the layer a shell
 *   transition animates under `Transition Root: ...` for exactly as long as it plays, see
 *   [listShowsPlayingTransition].
 *
 * Each read costs ~10-20 ms, so the gate asks only once the tree already looks settled.
 *
 * The accessibility window list lags the animation too: the system batches window updates while
 * windows keep moving and publishes the final bounds shortly after the transition ends. So the end
 * of a held transition is not itself the settled moment; see [QuietTreeVerdict.RESTART_QUIET_WINDOW].
 *
 * API 34 and up only, see [supportedOn]. Kill switch, read per call:
 * `adb shell setprop debug.trailblaze.settle.transitionGate 0`.
 */
internal object WindowTransitionProbe {

  private const val LIST_COMMAND = "dumpsys SurfaceFlinger --list"

  private const val ACTIVITIES_COMMAND = "dumpsys activity activities"

  private const val PROBE_TIMEOUT_MS = 1_000L

  /** See [SettleSwitches.TRANSITION_GATE_SYSPROP] and [supportedOn]. */
  fun enabled(): Boolean = supportedOn(Build.VERSION.SDK_INT) && SettleSwitches.transitionGateEnabled()

  /**
   * Whether this release prints what the probe parses, as verified on API 34. API 29 lists
   * `TaskRecord`s with a bare `visible=` and no `mVisibleRequested`, and has no shell-transition
   * layer, so neither read can say "moving" there and asking would only add their cost to every
   * capture. API 30-33 are unverified. Below 34 the gate judges the tree alone, as before the probe.
   */
  internal fun supportedOn(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

  /**
   * True while an activity change is under way, false when none is, null when neither source could
   * be read within [budgetMs]. Null must be treated as "no evidence", never as "still moving": a device where the
   * reads fail would otherwise hold every capture to the gate's hard cap.
   */
  fun isTransitionPlaying(budgetMs: Long = PROBE_TIMEOUT_MS): Boolean? {
    val deadlineUptimeMs = SystemClock.uptimeMillis() + budgetMs
    return combineProbeReads(
      activitiesInFlux = { readOrNull(ACTIVITIES_COMMAND, deadlineUptimeMs)?.let(::activitiesShowVisibilityInFlux) },
      transitionLayerPresent = { readOrNull(LIST_COMMAND, deadlineUptimeMs)?.let(::listShowsPlayingTransition) },
    )
  }

  /** Both reads share one budget, so a slow shell cannot stretch a capture past its own cap. */
  private fun readOrNull(command: String, deadlineUptimeMs: Long): String? {
    val timeoutMs = minOf(PROBE_TIMEOUT_MS, deadlineUptimeMs - SystemClock.uptimeMillis())
    if (timeoutMs <= 0) return null
    return try {
      AdbCommandUtil.runShellCommand(command, command, timeoutMs = timeoutMs)
    } catch (t: Throwable) {
      null
    }
  }

  /**
   * Either source saying "moving" is enough, so the second read is skipped when the first already
   * says so. "Not moving" needs at least one source that could be read.
   */
  internal fun combineProbeReads(
    activitiesInFlux: () -> Boolean?,
    transitionLayerPresent: () -> Boolean?,
  ): Boolean? {
    val activities = activitiesInFlux()
    if (activities == true) return true
    val layers = transitionLayerPresent()
    if (layers == true) return true
    return if (activities == null && layers == null) null else false
  }

  /** Pure parse of the layer list, so what counts as a playing transition is unit-testable. */
  internal fun listShowsPlayingTransition(layerList: String): Boolean =
    layerList.lineSequence().any { it.trimStart().startsWith("Transition Root") }

  private val VISIBILITY_FIELDS = Regex("""\bmVisibleRequested=(true|false) mVisible=(true|false)\b""")

  private val STATE_FIELD = Regex("""^\s*state=([A-Z_]+)\b""")

  private val TASK_LINE = Regex("""^\s*\* Task\{.*\bvisible=(true|false) visibleRequested=(true|false)\b""")

  private val RECORD_LINE = Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{""")

  /**
   * Pure parse of `dumpsys activity activities`: whether any activity is between "asked to change
   * visibility" and "shown that way". That is one asked to show that is not shown yet, one asked to
   * hide that is still shown, or an activity being started: `INITIALIZING` at the top of a task
   * that is shown or asked to show. At rest every activity's requested and actual visibility agree.
   *
   * `INITIALIZING` alone is not enough. Activities below the top of a back stack built by
   * `startActivities` (a deep link, a notification) stay `INITIALIZING` until navigated to, and
   * so does a background task's, so counting those would hold every capture to the gate's cap.
   * Only the activity being started sits at the top of the task coming forward. On an API 34
   * emulator that record read `INITIALIZING`, `mVisibleRequested=false mVisible=false`, in a task
   * already `visible=true`, for the first ~70 ms of a cold launch, while the outgoing activity
   * still read shown-and-asked-to-show.
   */
  internal fun activitiesShowVisibilityInFlux(activitiesDump: String): Boolean {
    var taskShown = false
    var recordsSeenInTask = 0
    var recordStartsShownTask = false
    for (line in activitiesDump.lineSequence()) {
      val task = TASK_LINE.find(line)
      if (task != null) {
        taskShown = task.groupValues[1] == "true" || task.groupValues[2] == "true"
        recordsSeenInTask = 0
        recordStartsShownTask = false
        continue
      }
      if (RECORD_LINE.containsMatchIn(line)) {
        // Records are listed top of the task first.
        recordStartsShownTask = taskShown && recordsSeenInTask == 0
        recordsSeenInTask++
        continue
      }
      if (VISIBILITY_FIELDS.find(line)?.let { it.groupValues[1] != it.groupValues[2] } == true) return true
      if (recordStartsShownTask && STATE_FIELD.find(line)?.groupValues?.get(1) == "INITIALIZING") return true
    }
    return false
  }
}

/**
 * One capture's use of [WindowTransitionProbe]. Every read is bounded by what is left of the
 * capture's cap, and after one slow read (a wedged or overloaded shell) the probe is not read again
 * this capture, so the capture pays for at most one slow read.
 *
 * A transition last seen playing when the reads stop is still reported as playing, so the capture
 * holds until its cap. Treating the silence as the transition ending would return the screen
 * mid-animation once the shorter post-transition quiet window ran out.
 */
internal class CaptureTransitionProbe(
  private val enabled: Boolean,
  private val deadlineUptimeMs: Long,
  private val slowReadMs: Long,
  private val nowUptimeMs: () -> Long,
  private val read: (budgetMs: Long) -> Boolean?,
) {
  private var gaveUp = false
  private var lastAnswer: Boolean? = null

  /** True while a transition plays, false when none does, null when there is no evidence. */
  fun ask(): Boolean? {
    if (!enabled) return null
    if (gaveUp) return if (lastAnswer == true) true else null
    val askedAt = nowUptimeMs()
    val remainingMs = deadlineUptimeMs - askedAt
    if (remainingMs <= 0) return null
    val playing = read(remainingMs)
    lastAnswer = playing
    val tookMs = nowUptimeMs() - askedAt
    if (tookMs >= slowReadMs) {
      Console.log("[settle] window transition probe took ${tookMs}ms; not asking again this capture")
      gaveUp = true
    }
    return playing
  }
}

/** What the capture-time settle gate does with a tree that is quiet and complete. */
internal enum class QuietTreeVerdict {
  /** Nothing is animating: the tree is the settled screen. */
  ACCEPT,

  /** A window transition is still playing: keep sampling, and ask again on the next sample. */
  HOLD,

  /**
   * A transition the gate was holding for has just ended. The window's final bounds reach the
   * accessibility tree only after its last frame, so the quiet window starts over from now, and
   * runs longer than usual.
   */
  RESTART_QUIET_WINDOW,
}

/**
 * The gate's decision for a quiet, complete tree, given what [WindowTransitionProbe] reported
 * ([transitionPlaying], null when unreadable or not asked) and whether the gate was already holding
 * for a transition.
 */
internal fun decideQuietTree(transitionPlaying: Boolean?, heldForTransition: Boolean): QuietTreeVerdict =
  when {
    transitionPlaying == true -> QuietTreeVerdict.HOLD
    heldForTransition -> QuietTreeVerdict.RESTART_QUIET_WINDOW
    else -> QuietTreeVerdict.ACCEPT
  }
