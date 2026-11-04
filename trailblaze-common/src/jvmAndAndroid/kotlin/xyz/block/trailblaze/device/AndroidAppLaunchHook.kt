package xyz.block.trailblaze.device

/**
 * Work the on-device Android driver does around an app launch, for launches it does not run itself
 * (`openApp`, which starts the app with `am start`).
 *
 * The driver's own launch re-attaches the in-process idle detector just before it starts the app and
 * confirms it after, because clearing or stopping the app detaches it. A launch that skipped that
 * would leave the rest of the trail settling at heuristic speed, and fail a run that requires turbo.
 * The driver installs [installed] when it starts; elsewhere (host runs) it stays null and a launch is
 * just the launch.
 */
object AndroidAppLaunchHook {

  interface Hook {
    /**
     * Runs right before [appId] is started. Returns true when [afterLaunch] must follow the start.
     * Must not restart an app that is already running. May throw when the run requires turbo and
     * the detector cannot be attached.
     */
    fun beforeLaunch(appId: String): Boolean

    /**
     * Runs right after the start, when [beforeLaunch] returned true — even when the start threw,
     * because the command may have run before its shell call failed. Must not block.
     */
    fun afterLaunch(appId: String)
  }

  @Volatile var installed: Hook? = null

  /** Runs [launch] between the installed hook's two halves, or on its own when none is installed. */
  fun <T> aroundLaunch(appId: String, launch: () -> T): T {
    val hook = installed ?: return launch()
    val confirm = hook.beforeLaunch(appId)
    if (!confirm) return launch()
    try {
      return launch()
    } finally {
      hook.afterLaunch(appId)
    }
  }
}
