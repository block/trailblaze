package xyz.block.trailblaze.android.accessibility

/**
 * Device-side switches for the accessibility driver's settle gates, read per call so each can be
 * flipped against a runner that stays up:
 *
 *     adb shell setprop debug.trailblaze.settle.transitionGate     0   # default on
 *     adb shell setprop debug.trailblaze.settle.reuseQuietVerdict  1   # default off
 */
internal object SettleSwitches {

  /** See [WindowTransitionProbe]. On unless set to `0`/`false`. */
  const val TRANSITION_GATE_SYSPROP: String = "debug.trailblaze.settle.transitionGate"

  /**
   * EXPERIMENTAL, off by default. When on, a pre-action [TrailblazeAccessibilityService.waitForSettled]
   * returns at once if an earlier one already found the screen quiet and nothing has happened since:
   * no UI event and no input from this driver. A direct tool call runs three of these back to back
   * (the dispatch gate, the ref resolution, the action runner) before it touches the device, and each
   * otherwise waits out its full 50 ms grace for an event that cannot come.
   *
   * The same switch lets the reply's capture skip its grace when the action's own settle saw the
   * screen react and go quiet, and no UI event has happened since. A settle that saw no reaction at
   * all (a tap on a static label, or a launch slower than the settle's grace) is never reused.
   */
  const val REUSE_QUIET_VERDICT_SYSPROP: String = "debug.trailblaze.settle.reuseQuietVerdict"

  fun transitionGateEnabled(): Boolean = defaultOn(sysprop(TRANSITION_GATE_SYSPROP))

  fun reuseQuietVerdictEnabled(): Boolean = defaultOff(sysprop(REUSE_QUIET_VERDICT_SYSPROP))

  /** `0`/`false` turns a default-on switch off; anything else, including unset, leaves it on. */
  internal fun defaultOn(raw: String): Boolean = raw.trim().lowercase() !in setOf("0", "false")

  /** `1`/`true` turns a default-off switch on; anything else, including unset, leaves it off. */
  internal fun defaultOff(raw: String): Boolean = raw.trim().lowercase() in setOf("1", "true")

  private val getSysprop: java.lang.reflect.Method? by lazy {
    try {
      Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
    } catch (t: Throwable) {
      null
    }
  }

  private fun sysprop(name: String): String =
    try {
      getSysprop?.invoke(null, name, "") as? String ?: ""
    } catch (t: Throwable) {
      ""
    }
}
