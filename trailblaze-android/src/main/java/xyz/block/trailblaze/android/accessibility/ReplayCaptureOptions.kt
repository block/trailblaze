package xyz.block.trailblaze.android.accessibility

/**
 * Device-side switches for the dispatch work that surrounds a replayed action.
 *
 * Every switch here defaults to **whatever turbo is doing**: on when the in-process idle helper is
 * attached ([InProcessIdleSettleClient.isEnabled], which turbo sets on the device when it attaches),
 * and off when it is not. Turbo is already opt-in, and each of these was measured under it with the
 * screenshot/hierarchy pairing unchanged; with turbo off the replay path stays exactly what it was.
 *
 * Each switch is also an explicit kill switch, read per call so it can be flipped against a runner
 * that stays up. `0`/`false` forces the behaviour off even under turbo; `1`/`true` forces it on even
 * without turbo:
 *
 *     adb shell setprop debug.trailblaze.replay.asyncScreenshot  0   # off under turbo
 *     adb shell setprop debug.trailblaze.replay.deferLogFlush    0
 *     adb shell setprop debug.trailblaze.replay.reuseToolBundles 0
 *
 * Same sysprop plumbing as [InProcessIdleSettleClient]: reflection onto `android.os.SystemProperties`
 * so nothing here depends on a hidden API being linkable at compile time.
 */
object ReplayCaptureOptions {

  /**
   * `0` keeps the screenshot join on the capture's critical path; `1` moves it off.
   *
   * The per-action logging screenshot is requested at the same instant either way — immediately
   * after the tree reads and before the caller dispatches its gesture — so the frame paired with
   * the tree is the same frame. Only the WAIT moves: the first read of `screenshotBytes` joins the
   * thread instead of the capture constructor. Measured on a turbo replay this takes ~40 ms off a
   * sample-app capture and leaves pairing skew unchanged (30 ms median sample app, 27 ms Square,
   * against 33 ms and 27 ms before). `[pair-skew]` is the line that proves it per capture.
   */
  const val ASYNC_SCREENSHOT_SYSPROP: String = "debug.trailblaze.replay.asyncScreenshot"

  /**
   * `0` joins the driver's async log lane before every reply; `1` joins it after the next action.
   *
   * A replayed action's screenshot encode, screenshot upload and `AgentDriverLog` upload all START
   * when the action finishes, and `AndroidTrailblazeRule.runSuspend` used to wait for all three
   * before writing the RPC reply. On the host-drives-the-loop path each "run" is a single action,
   * so that join sat in front of every reply: measured at 156.5 ms per action on the sample app and
   * 178 ms on Square, spent uploading logs the host does not read until the session ends.
   *
   * Moved into the next action — after its gesture, where the lane has had a whole action to drain
   * — the join measures 0 ms with 0 uploads pending on both apps, so the wait disappears rather
   * than relocating. The backlog stays bounded at one action, so the bitmap-residency argument in
   * [AccessibilityTrailRunner.logAsync] still holds and log order is unchanged.
   *
   * A dispatch that owns either end of its session still joins at the end of the run — see
   * [shouldDeferDispatchLogJoin]. What this gives up is nothing at the session boundary: the
   * dispatch that carries the session-end log joins before emitting it, so a report is never
   * generated with uploads in flight.
   */
  const val DEFER_LOG_FLUSH_SYSPROP: String = "debug.trailblaze.replay.deferLogFlush"

  /**
   * `0` launches the session's scripted-tool bundles inside every dispatch; `1` launches them once.
   *
   * On the host-drives-the-loop path each replayed action is its own `run_yaml` dispatch, and each
   * dispatch stood up every catalog QuickJS bundle, registered their tools, and tore the whole lot
   * down again — for a recorded action that calls none of them. Measured at 20 ms per action on the
   * sample app and 248 ms on Square, whose catalog carries far more bundles; removing the teardown
   * as well is worth a further ~45 ms on Square.
   *
   * The reuse is keyed on (session, tool repo instance) — see [ScriptedToolBundleReuse] — so a
   * dispatch that builds its own repo simply misses the cache and launches as before.
   */
  const val REUSE_TOOL_BUNDLES_SYSPROP: String = "debug.trailblaze.replay.reuseToolBundles"

  private val getSysprop: java.lang.reflect.Method? by lazy {
    try {
      Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
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

  /**
   * Resolves one switch: an explicit sysprop value wins, and an unset (or unrecognised) one follows
   * [turboOn]. Pure, so the defaulting rule is testable without a device — and it is the rule that
   * decides whether the non-turbo path changed at all, which is the thing most worth a test.
   */
  internal fun resolve(raw: String, turboOn: Boolean): Boolean = when (raw.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> turboOn
  }

  private fun enabled(name: String): Boolean =
    resolve(sysprop(name), turboOn = InProcessIdleSettleClient.isEnabled())

  /**
   * True when the capture should hand the screenshot thread to the first reader of
   * `screenshotBytes` instead of joining it before the constructor returns.
   */
  fun asyncLoggingScreenshot(): Boolean = enabled(ASYNC_SCREENSHOT_SYSPROP)

  /**
   * True when the driver's log lane should be joined after the next action instead of before this
   * action's reply. See [DEFER_LOG_FLUSH_SYSPROP] for what that trades away.
   */
  fun deferLogFlushEnabled(): Boolean = enabled(DEFER_LOG_FLUSH_SYSPROP)

  /**
   * Whether THIS dispatch may skip the end-of-run join of the driver's log lane.
   *
   * Only a dispatch that owns NEITHER end of its session may: for those the "run" is a single
   * action and the host reads nothing yet. Either log makes the dispatch session-owning:
   *
   *  - `sendSessionStartLog` — the JUnit rule and the desktop whole-trail dispatch, where the run
   *    IS the session.
   *  - `sendSessionEndLog` — the last host-driven dispatch, which emits `SessionEnded` right after
   *    this join would have been skipped. The host generates the report off that log, so deferring
   *    here would let a trail report before its last screenshot and hierarchy landed. It also
   *    covers a final dispatch that carries no actions, which never reaches the deferred join in
   *    [AccessibilityTrailRunner] at all.
   *
   * Read from the config rather than inferred from the start-log flag, because the RPC handler
   * suppresses `sendSessionStartLog` before it calls the rule — a whole-trail RPC looks exactly
   * like a per-action one from that flag alone.
   *
   * Pure so the rule that decides it is testable without a device.
   */
  internal fun shouldDeferDispatchLogJoin(
    gateOn: Boolean,
    sendSessionStartLog: Boolean,
    sendSessionEndLog: Boolean,
  ): Boolean = gateOn && !sendSessionStartLog && !sendSessionEndLog

  /** True when the session's scripted-tool bundle launch should be reused across dispatches. */
  fun reuseToolBundlesEnabled(): Boolean = enabled(REUSE_TOOL_BUNDLES_SYSPROP)
}
