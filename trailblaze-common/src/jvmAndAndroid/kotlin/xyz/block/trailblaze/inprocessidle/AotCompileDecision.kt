package xyz.block.trailblaze.inprocessidle

import xyz.block.trailblaze.device.AndroidPackageDump

/**
 * Whether to ahead-of-time compile a turbo target before attaching the idle detector, and why.
 *
 * The compile exists because `am instrument` restarts the target at BACKGROUND process priority: a
 * heavy app's JIT-everything cold start can starve past the platform's ~17s proc-start ANR
 * watchdog, and the ANR tears the instrumentation down before the detector answers `PING`. Full AOT
 * takes the JIT cost off that cold start, turning the attach from a scheduling race into something
 * deterministic.
 *
 * Shared rather than owned by either caller because the host installer and the on-device attacher
 * run the same compile for the same reason, off different transports. A guard that lands in only
 * one of them is invisible — the run still passes, just slower — which is exactly how the tablet
 * regression got in. What is *not* shared is how the compile is issued: the host has adb
 * (`cmd package compile`), the device has the UiAutomation shell (`pm compile`). Each caller builds
 * its own command around [Compile.compilerFilterArgs].
 */
sealed interface AotCompileDecision {

  /** Nothing asked for a compile. No probe is worth doing — no answer could change this. */
  data object NotRequested : AotCompileDecision

  /** A compile was asked for but would buy nothing. [reason] is written for a log reader. */
  data class Skip(val reason: String) : AotCompileDecision

  /** Compile, passing [compilerFilterArgs] to whichever compile command the caller speaks. */
  data class Compile(val compilerFilterArgs: List<String>) : AotCompileDecision

  companion object {

    /**
     * The compiler filter, and deliberately **no `-f`**.
     *
     * Forcing recompiles a package already sitting at `speed`, and an attach is not once per run:
     * anything that drops the detector — a `clearAppData`, a re-launch — re-attaches. On an
     * Android tablet, two app-data clears per trail each paid a full forced recompile,
     * ~150s of dead wall clock, which made turbo a net loss on that device. Unforced, the second
     * call returns at once, and a reinstalled APK invalidates its own compiled code so a genuine
     * recompile still happens when it is genuinely needed.
     *
     * A constant so the absent `-f` is assertable rather than a comment repeated in two callers.
     */
    val COMPILER_FILTER_ARGS: List<String> = listOf("-m", "speed")

    /**
     * The decision for [appId], given whether a compile was [requested] and a way to find out
     * whether the target is a debug build.
     *
     * [debuggable] is a lambda and is called at most once: the probe costs a device round trip, and
     * an opted-out run must not pay it to be told something that cannot change the answer.
     */
    fun forTarget(
      requested: Boolean,
      appId: String,
      debuggable: () -> AndroidPackageDump.Debuggable,
    ): AotCompileDecision {
      if (!requested) return NotRequested
      return when (debuggable()) {
        // ART compiles a debuggable package at `verify` whatever is asked, so the compile does the
        // full work and changes nothing. Every dev / eng build and every debug-variant CI run
        // is in this case — including the runs that measure turbo, which is why this was pure cost on all of
        // its devices.
        AndroidPackageDump.Debuggable.YES ->
          Skip("$appId is debuggable, which ART only ever compiles at 'verify'")
        AndroidPackageDump.Debuggable.NO -> Compile(COMPILER_FILTER_ARGS)
        // Compile rather than skip. Losing the compile is the outcome that actually breaks an
        // attach — it is what keeps the instrumented cold start inside the ANR window — while an
        // unnecessary compile only costs time, and unforced it costs almost none the second time.
        AndroidPackageDump.Debuggable.UNKNOWN -> Compile(COMPILER_FILTER_ARGS)
      }
    }
  }
}
