package xyz.block.trailblaze.device

/**
 * Runs `args` on a device and returns its output, or null when it did not finish within
 * `timeoutMs` (or could not run at all). Every caller of [EnsureAppCompiled] already has something
 * with this shape: the tool's bounded executor call, the host's bounded adb shell.
 */
typealias DeviceShell = suspend (args: List<String>, timeoutMs: Long) -> String?

/**
 * Makes sure ART has compiled artifacts for an app, so its cold start does not verify the whole
 * APK from scratch on every process start.
 *
 * A sideloaded install compiles nothing. `adb install` writes the APK and returns (0.3s for a 63 MB
 * APK, measured on API 34) and leaves the package at `status=run-from-apk`: with no profile to
 * compile against, ART's install-time step produces no artifacts and defers to background dexopt,
 * which needs an idle, charging device and never runs on an emulator that lives for one job. So ART
 * loads and verifies the dex on **every** cold start, and nothing reports it: the app runs, only
 * slower. This is the normal state of a freshly installed debug app, not an accident — four of five
 * third-party apps on one developer emulator were in it. Once made, the artifacts live beside the
 * APK under `/data/app/~~…/oat/`, so they survive `pm clear` and reboots and die only on reinstall.
 *
 * Measured on two devices, a large app's cold start went from 9.6–10.2s to 2.2–3.1s once compiled,
 * and from 1.9s to 0.9s on a faster one. The one-time compile cost 27.6s and 2.6s respectively, so
 * it pays for itself after about four cold starts and is a loss below that — which is why the right
 * moment to run it is once after an install, not inside every trail. A provisioning script that
 * knows it just installed something should compile unconditionally right there (Trailblaze's own CI
 * does); this check exists for every install that did not come through such a script — a
 * developer's machine, a cloud workstation, another repository's pipeline.
 *
 * The device's own state is the marker: `dumpsys package <appId>` prints a `Dexopt state:` entry per
 * code path with a `status=`. Reading it is one shell round trip, so this checks first and compiles
 * only when the artifacts are missing — a healthy device pays the read and nothing else. There is no
 * fingerprint to store: the OS already keeps one, per install directory, and a reinstall puts the
 * package back at `run-from-apk` only until the next call.
 *
 * That marker is only readable from Android 9 up. 8.x prints the same fact in a shape that predates
 * `[status=…]`, so on API 26–27 the state reads as unknown and nothing is compiled — better than
 * mistaking "I cannot read this" for "there are no artifacts" and paying a full compile every
 * session for a device that was healthy all along.
 *
 * Transport-agnostic on purpose: a caller hands in a [DeviceShell] and reads one [Outcome]. The
 * caller is the host-side session start, which runs before any scripted runtime exists and so
 * cannot go through a tool.
 *
 * The same sequence is also available as a trail step for runs that have no host, but that one is
 * the TypeScript `android_ensureAppCompiled`, which composes `android_adbShell` and carries its own
 * port of the decision. The two are held together by matching the shapes below, not by shared code
 * — a change to what "compiled" means, to the compile argv, or to the post-compile verification
 * belongs in both. Both commands are whitespace-free argv tokens, so a shell with no interpreter
 * (the on-device `UiAutomation` shell) runs them identically to `adb shell`.
 *
 * After a compile the state is **read again from the device** and the result reports that, not
 * `pm compile`'s exit line: the only broken state observed in the wild (`[location is error]`) is
 * one ART may consider up to date, which is also why the compile passes `-f`. `-f` is also what
 * makes `force` on an already-compiled package mean anything — without it `pm compile` would see
 * existing artifacts and no-op regardless of what compile decided.
 */
object EnsureAppCompiled {

  const val DEFAULT_COMPILER_FILTER = "verify"

  /**
   * The filters `pm compile -m` accepts. Also the injection guard: the filter is interpolated into
   * a shell command, and a closed set is simpler to reason about than an escape.
   */
  val KNOWN_COMPILER_FILTERS: Set<String> = setOf(
    "assume-verified",
    "verify",
    "quicken",
    "space-profile",
    "space",
    "speed-profile",
    "speed",
    "everything-profile",
    "everything",
  )

  /** `dumpsys package <appId>` is a few hundred lines; well under a second on any device. */
  const val DUMP_TIMEOUT_MS = 15_000L

  /**
   * Generous because a compile scales with APK size: `verify` on a 200 MB APK is ~12–28s depending
   * on the device, `speed` on a release build can be minutes. Matches turbo mode's compile bound.
   */
  const val COMPILE_TIMEOUT_MS = 300_000L

  /** What one call found and did. Every case is distinct so a caller can say exactly what happened. */
  sealed interface Outcome {
    /** The app already had artifacts; nothing was run beyond the read. */
    data class AlreadyCompiled(val state: AndroidPackageDump.DexoptState) : Outcome

    /** `checkOnly`: the state as found, nothing compiled — whether or not it was compiled. */
    data class Reported(val state: AndroidPackageDump.DexoptState) : Outcome

    /**
     * The app is installed and the device answered, but its `Dexopt state:` block held no status
     * line in a shape this parser reads, so whether ART has artifacts is unknown. Nothing was
     * compiled: "uncompiled" was never established. Android 8.x is the known case; see
     * [AndroidPackageDump.DexoptState.statusUnreadable].
     */
    data class StatusUnreadable(val state: AndroidPackageDump.DexoptState) : Outcome

    /** Compiled, and the device confirms it now has artifacts. */
    data class Compiled(
      val before: AndroidPackageDump.DexoptState,
      val after: AndroidPackageDump.DexoptState,
      val compilerFilter: String,
      val elapsedMs: Long,
    ) : Outcome

    /**
     * `dumpsys package` came back and reported no dexopt entry for the app: it is genuinely not
     * installed. Distinct from [DumpTimedOut] — a caller iterating candidate app ids should treat
     * this as "try the next one" and that as "the device did not answer, stop and say so".
     */
    data class NotInstalled(val appId: String) : Outcome

    /**
     * The initial `dumpsys package` read did not come back within the dump timeout. Unlike
     * [NotInstalled], this says nothing about whether the app is there — only that the device did
     * not answer in time — so a caller must not read it as "nothing to compile".
     */
    data class DumpTimedOut(val appId: String, val timeoutMs: Long) : Outcome

    /** `pm compile` did not return within [COMPILE_TIMEOUT_MS]; the state was not re-read. */
    data class CompileTimedOut(
      val before: AndroidPackageDump.DexoptState,
      val compilerFilter: String,
      val timeoutMs: Long,
    ) : Outcome

    /**
     * `pm compile` returned but the device still reports no artifacts, the re-read failed, or (a
     * forced recompile of an already-compiled package, where the device would report the same
     * state either way) [compileOutput] itself did not say `Success` — `pm compile`'s exit code is
     * not trustworthy on its own; see the class doc. [compileOutput] is whatever `pm compile`
     * printed, which is the only clue to why.
     */
    data class StillUncompiled(
      val before: AndroidPackageDump.DexoptState,
      val after: AndroidPackageDump.DexoptState?,
      val compilerFilter: String,
      val elapsedMs: Long,
      val compileOutput: String,
    ) : Outcome
  }

  /** What to do after reading the state. Pure, so the table is unit-testable. */
  enum class Action { ALREADY_COMPILED, REPORT_ONLY, STATUS_UNREADABLE, COMPILE }

  /**
   * [statusUnreadable] wins over everything, [force] included: the device answered in a shape this
   * parser does not read, so "uncompiled" was never established and there is nothing to act on. On
   * such a device a compile would run on every session and the verification after it would read
   * the same unreadable dump, so forcing cannot succeed either — it can only ever report failure.
   *
   * After that [checkOnly] wins: a caller that asked only to look never compiles. Then a compiled
   * app is left alone unless forced, and an uncompiled one is compiled.
   */
  fun decide(compiled: Boolean, force: Boolean, checkOnly: Boolean, statusUnreadable: Boolean): Action = when {
    statusUnreadable -> Action.STATUS_UNREADABLE
    checkOnly -> Action.REPORT_ONLY
    compiled && !force -> Action.ALREADY_COMPILED
    else -> Action.COMPILE
  }

  /**
   * The compile as argv. `-f` because a compile is only reached once the artifacts are known to be
   * missing or the caller forced it; see the class doc.
   */
  fun compileArgs(compilerFilter: String, appId: String): List<String> =
    listOf("pm", "compile", "-m", compilerFilter, "-f", appId)

  /**
   * Reads [appId]'s dexopt state through [shell] and compiles it when needed (or when [force]d),
   * then reads it again so the [Outcome] describes the device, not the compile's exit line.
   *
   * [dumpTimeoutMs] and [compileTimeoutMs] default to the bounds documented above; a caller whose
   * transport has its own hazards around an abandoned read (the on-device `UiAutomation` shell —
   * see `AndroidShellBounds`) should pass its own, larger values instead of trusting these.
   *
   * Throws only for a programming error ([compilerFilter] outside [KNOWN_COMPILER_FILTERS]); a
   * device that cannot answer is an [Outcome], not an exception. Whatever [shell] throws propagates.
   */
  suspend fun ensure(
    appId: String,
    compilerFilter: String = DEFAULT_COMPILER_FILTER,
    force: Boolean = false,
    checkOnly: Boolean = false,
    dumpTimeoutMs: Long = DUMP_TIMEOUT_MS,
    compileTimeoutMs: Long = COMPILE_TIMEOUT_MS,
    shell: DeviceShell,
  ): Outcome {
    require(compilerFilter in KNOWN_COMPILER_FILTERS) {
      "unknown compilerFilter '$compilerFilter'. Known filters: ${KNOWN_COMPILER_FILTERS.joinToString(", ")}"
    }
    val before = when (val result = readState(appId, dumpTimeoutMs, shell)) {
      is ReadResult.TimedOut -> return Outcome.DumpTimedOut(appId, dumpTimeoutMs)
      ReadResult.Absent -> return Outcome.NotInstalled(appId)
      is ReadResult.Present -> result.state
    }
    return when (
      decide(
        compiled = before.compiled,
        force = force,
        checkOnly = checkOnly,
        statusUnreadable = before.statusUnreadable,
      )
    ) {
      Action.ALREADY_COMPILED -> Outcome.AlreadyCompiled(before)
      Action.REPORT_ONLY -> Outcome.Reported(before)
      Action.STATUS_UNREADABLE -> Outcome.StatusUnreadable(before)
      Action.COMPILE -> {
        val startedAtMs = System.currentTimeMillis()
        val compileOutput = shell(compileArgs(compilerFilter, appId), compileTimeoutMs)
          ?: return Outcome.CompileTimedOut(before, compilerFilter, compileTimeoutMs)
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val after = when (val result = readState(appId, dumpTimeoutMs, shell)) {
          is ReadResult.Present -> result.state
          else -> null
        }
        // A forced recompile of a package that was already compiled is the one case where
        // `after.compiled` alone cannot tell success from a no-op or a refusal: both leave
        // artifacts in place, and a same-filter recompile leaves the device reporting the exact
        // same status either way. `pm compile`'s own report is the only other signal available
        // then — AOSP's shell command prints exactly `Success` on success and something else
        // (typically `Failure`) otherwise.
        val forcedNoOp = force && before.compiled && !compileOutput.contains("Success", ignoreCase = true)
        if (after == null || !after.compiled || forcedNoOp) {
          Outcome.StillUncompiled(before, after, compilerFilter, elapsedMs, compileOutput)
        } else {
          Outcome.Compiled(before, after, compilerFilter, elapsedMs)
        }
      }
    }
  }

  /** [readState]'s three-way answer — timeout, genuinely absent, and present are not the same fact. */
  private sealed interface ReadResult {
    data class Present(val state: AndroidPackageDump.DexoptState) : ReadResult
    object Absent : ReadResult
    object TimedOut : ReadResult
  }

  private suspend fun readState(appId: String, timeoutMs: Long, shell: DeviceShell): ReadResult {
    val dump = shell(listOf("dumpsys", "package", appId), timeoutMs) ?: return ReadResult.TimedOut
    val state = AndroidPackageDump.dexoptState(dumpsysPackageOutput = dump, appId = appId)
    return if (state != null) ReadResult.Present(state) else ReadResult.Absent
  }
}
