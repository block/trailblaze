package xyz.block.trailblaze.android.accessibility

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.device.AndroidPackageDump
import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.inprocessidle.AotCompileDecision
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.util.Console

/**
 * Deploys and attaches the Trailblaze in-process idle detector to a target app **from inside the
 * test APK**, so a device farm needs nothing beyond "install app APK + test APK, run the
 * instrumentation" — no host, no adb scripting, no farm-side setup.
 *
 * This is what lets the accessibility driver's settle gates race a true-idle signal
 * ([InProcessIdleSettleClient]) instead of waiting out the event-quiet heuristic.
 *
 * The detector is a tiny bare-`Instrumentation` APK, signature-matched to the target app, staged in
 * the calling test APK's assets at `inprocess-idle-apks/trailblaze-inprocess-idle-<flavor>.apk`
 * (flavor = the last dotted label of the target applicationId). The `inProcessIdle { }` block of the
 * Trailblaze Android Gradle plugin builds, signs and stages those APKs. Attach sequence:
 *
 *  1. PING `127.0.0.1:7777` — if the detector already answers for `targetAppId`, just (re)assert
 *     the settle-race sysprop and return. Never reinstall over an attached detector: a package
 *     update tears down the instrumentation host, killing the target app mid-run.
 *  2. Install the flavor APK from assets via [PackageInstaller] under the shell permission
 *     identity (INSTALL_PACKAGES) — no adb, no root, farm-portable on API 29+. Skipped when this
 *     process already installed that same APK and it is still on the device
 *     ([shouldReuseInstalledDetector]), which is the common case from the second trail of a shard
 *     onwards on any trail whose trailhead clears the app.
 *  3. Pre-warm the target with a normal foreground launch so its first-ever cold start pays the
 *     dexopt/page-cache cost with no instrument ANR bound before the instrumented restart.
 *  4. `am instrument` the detector via the UiAutomation shell. This restarts the target app's
 *     *process* (app data and sign-in state survive; foreground UI does not) — which is why trails
 *     launch the app with `launchMode: RESUME` after attach instead of force-stopping it.
 *  5. Bring the target back to the foreground once so the instrumented restart isn't killed as a
 *     background process-start ANR while heavy app init runs.
 *  6. Poll PING until `PONG <targetAppId>` (the detector's server is up inside the app process),
 *     then set [InProcessIdle.SETTLE_SYSPROP] — [InProcessIdleSettleClient] reads it per call, so
 *     every settle gate in this process starts racing the detector.
 *
 * A trailhead that force-stops or clears the app detaches the detector; `launchApp` re-attaches it
 * on its own ([InProcessIdleLaunchReattacher]), which is why this attach runs once before a trail
 * rather than around every launch.
 *
 * All failures throw. A run that asked for the detector should fail loudly rather than silently
 * replay at heuristic speed and be read as a measurement of the detector.
 */
object InProcessIdleAttacher {

  /**
   * Log prefix for every line this attach emits. Load-bearing: the CI shards that gate this
   * feature assert on `[inprocess-idle-farm] attached:` in logcat, so it is a contract with those
   * scripts and not a cosmetic label.
   */
  private const val LOG_TAG = "inprocess-idle-farm"

  /** Instrumentation arg that overrides which applicationId to attach to. See [resolveTargetAppId]. */
  const val TARGET_APP_ARG = "trailblaze.inProcessIdle.targetApp"

  /** Instrumentation arg that opts into the on-device AOT compile. See [maybeAotCompileTarget]. */
  const val AOT_COMPILE_ARG = "trailblaze.inProcessIdle.aotCompileTarget"

  @Volatile
  private var attachedByThisProcess: String? = null

  /**
   * The app THIS instrumentation process attached a detector to, or null if it never attached one.
   *
   * The settle-race sysprop is device-global and outlives the process that set it, so a `PONG` alone
   * cannot say whether the detector answering is one somebody still needs or one a killed run left
   * behind. This can: a fresh process starts with null, so only a run that did the attach itself can
   * claim the switch. [OnDeviceTurbo] reads it before sparing a switch it would otherwise clear.
   */
  val attachedAppId: String? get() = attachedByThisProcess

  /**
   * The single place a successful attach is recorded, so the in-process ownership record and the
   * settle gates' identity cache cannot drift apart. Both are set on every path that ends with a
   * detector serving [targetAppId] for this process.
   */
  private fun recordAttached(targetAppId: String) {
    attachedByThisProcess = targetAppId
    InProcessIdleForegroundGate.noteAttached(targetAppId)
  }

  /**
   * Whether an instrumentation-arg value reads as opted in.
   *
   * Pure, because these args gate work an on-device lane would otherwise pay unconditionally, and
   * a null arg (the shape a lane that never heard of the knob sees) must read as off rather than as
   * "unset, so try". Deliberately named for the parse and not for any one knob — [AOT_COMPILE_ARG]
   * and a caller's own attach gate share it, so a tightening here must be correct for all of them.
   */
  internal fun isArgTrue(argValue: String?): Boolean = argValue?.equals("true", ignoreCase = true) == true

  /**
   * Pure half of [resolveTargetAppId]. Trimmed before the blank check: the value is interpolated
   * into a package name, an asset path and several shell commands, and stray whitespace there
   * surfaces as a missing-asset failure several steps later instead of at the arg.
   *
   * A **non-blank** resolved id is then held to the Android package-name grammar, because this is
   * the last point before it reaches a shell. `pm compile $appId` and `am start -n $appId/...` are
   * built by string interpolation, and the UiAutomation shell splits its command on whitespace — so
   * an id carrying a space or a `;` supplies argv of the caller's choosing. `defaultAppId` is held
   * to it too: the check is about where the value is *used*, not which door it came in through.
   *
   * A **blank** result passes through untouched, because blank is a supported answer and not a bad
   * argument. `AndroidTrailblazeRule.turboTargetAppId` passes `agentAppId ?: ""` and converts a
   * blank result to null — "this run could not name its target", at which point turbo reports
   * itself off. That getter is evaluated on every accessibility-driver run whether or not turbo was
   * asked for, so throwing here would break runs that never wanted the detector at all.
   *
   * @throws IllegalArgumentException if the resolved id is non-blank and not a valid Android
   *   package name.
   */
  internal fun resolveTargetAppId(argValue: String?, defaultAppId: String): String {
    val resolved = argValue?.trim()?.takeIf { it.isNotBlank() } ?: defaultAppId
    if (resolved.isNotBlank()) {
      androidPackageNameViolation(resolved)?.let {
        throw IllegalArgumentException("$TARGET_APP_ARG: $it")
      }
    }
    return resolved
  }

  /**
   * Which applicationId to attach to: [TARGET_APP_ARG] when the run supplies one, else
   * [defaultAppId]. A bundle stages a detector flavor per applicationId it can target, so the
   * override only has to name one of them.
   */
  fun resolveTargetAppId(defaultAppId: String): String =
    resolveTargetAppId(InstrumentationArgUtil.getInstrumentationArg(TARGET_APP_ARG), defaultAppId)

  /** Idempotent: cheap PING short-circuit when the idle detector is already attached and serving. */
  fun ensureAttached(targetAppId: String) {
    val inProcessIdlePackage = InProcessIdle.packageFor(targetAppId)

    // Suppress ANR / crash dialogs (standard device-farm setting): a heavy app's instrumented
    // cold start can trip a transient "isn't responding" dialog that then occludes the UI the
    // trails assert on.
    AdbCommandUtil.execShellCommand("settings put global hide_error_dialogs 1")

    val reply = ping()
    if (reply == "PONG $targetAppId") {
      Console.log("[$LOG_TAG] idle detector already attached to $targetAppId")
      recordAttached(targetAppId)
      enableSettleRace()
      return
    }
    if (reply != null && reply.startsWith("PONG ")) {
      // Port 7777 is shared across flavors — one attach at a time per device. A different app's
      // idle detector holds the port (dev-machine leftover; can't happen on a single-app farm
      // device). Detach it by stopping its host process, or our idle detector can never bind.
      val otherApp = reply.removePrefix("PONG ").trim()
      Console.log(
        "[$LOG_TAG] port ${InProcessIdle.PORT} held by the idle detector attached to $otherApp — detaching it",
      )
      AdbCommandUtil.execShellCommand("am force-stop $otherApp")
      awaitPortFree()
    }

    installInProcessIdleFromAssets(
      assetPath = InProcessIdle.assetPathFor(targetAppId),
      inProcessIdlePackage = inProcessIdlePackage,
    )

    maybeAotCompileTarget(targetAppId)

    // Pre-warm the target with a NORMAL foreground launch before attaching. `am instrument`
    // cold-starts the app process headless and enforces a ~20s process-start ANR on
    // Application.onCreate; a heavy app's *first-ever* cold start (cold dexopt, cold page cache)
    // blows past that and gets killed ("failed to complete startup"). A plain launch first pays
    // that one-time cost with no ANR bound (dexopt + page cache survive the instrument restart),
    // so the subsequent instrumented start completes fast enough to bind the idle detector.
    warmUpTargetApp(targetAppId)

    Console.log("[$LOG_TAG] starting idle detector instrumentation for $targetAppId")
    val instrumentOutput =
      AdbCommandUtil.execShellCommand(
        "am instrument $inProcessIdlePackage/${InProcessIdle.INSTRUMENTATION_CLASS}",
      )
    if (instrumentOutput.isNotBlank()) {
      Console.log("[$LOG_TAG] am instrument: ${instrumentOutput.trim()}")
    }
    // A rejected `am instrument` — the shape a signature mismatch takes — is final: the detector's
    // process was never started, so nothing will ever bind the port. Without this the run went on
    // to poll PING 120 times anyway, spending a minute (longer if something else holds the port)
    // to rediscover an answer the shell already gave. Same parser the re-attach path uses, so both
    // reject on the same evidence.
    check(!InProcessIdle.amInstrumentReportedFailure(instrumentOutput)) {
      "[$LOG_TAG] am instrument was rejected for $inProcessIdlePackage, so the idle detector never " +
        "started: ${instrumentOutput.trim()}"
    }
    // A single foreground bring-to-front after attach: keeps the instrumented restart visible
    // (foreground ANR window) without re-launching in a loop, which would restart the process
    // and perpetually reset onCreate.
    launchTargetForeground(targetAppId)
    awaitPong(targetAppId)
    enableSettleRace()
  }

  /**
   * Full AOT-compiles the target when the run opts in via [AOT_COMPILE_ARG]. `am instrument`
   * restarts the target at BACKGROUND process priority, and a heavy app's fresh-install JIT cold
   * start can starve past the platform's ~17s proc-start ANR watchdog — the ANR tears the
   * instrumentation down, so the idle detector never answers PING. Full AOT removes the JIT cost
   * from the instrumented cold start, making the attach deterministic instead of a scheduling race.
   *
   * Opt-in (and ~30s+ for a large APK) because a host-driven run can do this cheaper over adb
   * BEFORE the instrumentation starts; a remote device farm with no host adb is what needs it done
   * on-device. Failure is non-fatal — the attach then just races the watchdog like an un-compiled
   * run, and [awaitPong] reports the outcome loudly either way.
   *
   * Whether to compile at all is [AotCompileDecision]'s call, shared with the host installer so
   * the two paths cannot drift apart on the question; this only knows how to issue the compile on
   * the UiAutomation shell and how long it took.
   */
  private fun maybeAotCompileTarget(targetAppId: String) {
    val decision = AotCompileDecision.forTarget(
      requested = isArgTrue(InstrumentationArgUtil.getInstrumentationArg(AOT_COMPILE_ARG)),
      appId = targetAppId,
      // Probed only when the arg asked for a compile, so an opted-out run never pays for it.
      debuggable = { targetDebuggable(targetAppId) },
    )
    when (decision) {
      AotCompileDecision.NotRequested -> return
      is AotCompileDecision.Skip -> {
        Console.log("[$LOG_TAG] skipping the ahead-of-time compile: ${decision.reason}")
        return
      }
      is AotCompileDecision.Compile -> {
        val command = "pm compile ${decision.compilerFilterArgs.joinToString(" ")} $targetAppId"
        Console.log("[$LOG_TAG] AOT-compiling $targetAppId ($AOT_COMPILE_ARG=true, a no-op when already compiled)")
        // Elapsed, not just the outcome. This step is the whole reason the tablet lane regressed,
        // and the device-side log is the only place its cost is visible — a compile that is
        // supposed to be a fast no-op on a re-attach reads identically to a 75s recompile
        // otherwise. Also distinguishes both from the skip line above in a collected log.
        val startedAtMs = System.currentTimeMillis()
        val output = AdbCommandUtil.execShellCommand(command)
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        Console.log(
          "[$LOG_TAG] pm compile $targetAppId took ${elapsedMs}ms: " +
            output.trim().replace('\n', ' ').take(200),
        )
      }
    }
  }

  /**
   * Whether the target is a debug build, asked of `PackageManager` in-process.
   *
   * Deliberately **not** `dumpsys package`: that call runs through the UiAutomation shell, which
   * reads its subprocess to EOF with no timeout while holding a process-wide lock, so a stalled
   * package manager would wedge every other shell call, screenshot and hierarchy dump in the run
   * with nothing to time it out. Being inside an instrumentation, the same fact is one Binder call
   * away with no text to parse and no package name interpolated into a command.
   *
   * Cached because the answer is a property of the installed build, and the probe is on the attach
   * path — which runs again on every `clearAppData` and re-launch.
   *
   * A package we cannot read reads as [AndroidPackageDump.Debuggable.UNKNOWN]; see
   * [AotCompileDecision.forTarget] for what that resolves to and why. The likely cause is API 30+
   * package-visibility filtering in a test APK that declares neither the target as its
   * instrumentation target nor `QUERY_ALL_PACKAGES`.
   */
  private fun targetDebuggable(targetAppId: String): AndroidPackageDump.Debuggable =
    debuggableByAppId.computeIfAbsent(targetAppId) {
      try {
        val flags = InstrumentationRegistry.getInstrumentation()
          .context
          .packageManager
          .getApplicationInfo(targetAppId, 0)
          .flags
        if (flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
          AndroidPackageDump.Debuggable.YES
        } else {
          AndroidPackageDump.Debuggable.NO
        }
      } catch (t: Throwable) {
        Console.log("[$LOG_TAG] could not read ApplicationInfo for $targetAppId (${t.message})")
        AndroidPackageDump.Debuggable.UNKNOWN
      }
    }

  private val debuggableByAppId = ConcurrentHashMap<String, AndroidPackageDump.Debuggable>()

  /**
   * Normal (un-instrumented) launch of the target, waited until its launcher activity is
   * resumed in the foreground — i.e. Application.onCreate completed once without the instrument
   * ANR bound. This warms dexopt and the page cache so the following instrumented restart binds
   * the idle detector in time.
   */
  private fun warmUpTargetApp(targetAppId: String) {
    Console.log("[$LOG_TAG] pre-warming $targetAppId with a normal launch")
    launchTargetForeground(targetAppId)
    if (!AdbCommandUtil.waitUntilAppInForeground(targetAppId, maxWaitMs = 60_000)) {
      Console.log("[$LOG_TAG] $targetAppId never reached the foreground during pre-warm; attaching anyway")
      return
    }
    // Grace past first-frame so heavy first-run init behind the splash finishes warming too.
    Thread.sleep(2_000)
    Console.log("[$LOG_TAG] $targetAppId warm (foreground reached)")
  }

  /**
   * Foreground-launches the target via its shell-resolved launcher component. The usual
   * `monkey -p <pkg> -c LAUNCHER 1` one-liner silently no-ops for some apps/images (observed with a
   * large first-party app on an API-35 emulator: monkey parses its args and exits without launching
   * anything or printing an error), so resolve the component with the shell — which needs no
   * `<queries>` package-visibility declaration — and `am start` it explicitly. Falls back to
   * monkey when nothing resolves. Output is logged either way so a silent launch failure is
   * visible in logcat instead of surfacing 60s later as a dead foreground wait.
   */
  private fun launchTargetForeground(targetAppId: String) {
    val resolved =
      AdbCommandUtil.execShellCommand(
        "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
          "-c android.intent.category.LAUNCHER $targetAppId",
      ).trim().lines().lastOrNull()?.trim()
    val component = resolved?.takeIf { it.startsWith("$targetAppId/") }
    val output = if (component != null) {
      AdbCommandUtil.execShellCommand("am start -n $component")
    } else {
      Console.log("[$LOG_TAG] no launcher activity resolved for $targetAppId (got '$resolved') — falling back to monkey")
      AdbCommandUtil.execShellCommand("monkey -p $targetAppId -c android.intent.category.LAUNCHER 1")
    }
    Console.log("[$LOG_TAG] launch $targetAppId: ${output.trim().replace('\n', ' ').take(200)}")
  }

  /**
   * Wider connect bound than the settle path's: this probe runs a handful of times around an
   * attach, not twice per tap, and a loaded emulator mid-cold-start can be slow to refuse.
   */
  private const val PING_CONNECT_TIMEOUT_MS = 500

  private fun ping(): String? = InProcessIdle.ping(PING_CONNECT_TIMEOUT_MS)

  /**
   * Generous window on the attach handshake: the instrumented restart of a heavy app can still
   * take a while on a loaded emulator even when warm, and giving up early leaves a half-started
   * process behind for the next attempt to trip over. The PING short-circuit makes this a one-time
   * cost per device.
   *
   * Wall clock rather than a probe count, because a probe count is not a budget at all — the same
   * 120 probes cost wildly different amounts of time depending on how the attach is failing. With
   * nothing listening each probe is a fast connect refusal, so 120 of them spent about 60s; if
   * something holds the port without answering, every probe also pays the 2s PING read timeout and
   * the same 120 stretch past five minutes. That second case is not hypothetical: an app-clearing
   * action landing during the attach kills the detector, after which the handshake polls a dead
   * process for minutes and then, under `turboRequired`, fails the trail.
   *
   * So this is a real change in both directions, not just a cap. The pathological case drops from
   * ~5min to 2min. The plain "detector never started" case RISES, from ~60s to the full 2min,
   * because the two used to be the same number of probes and now they are the same amount of time.
   * That trade is deliberate: the wait that matters is the one a slow cold start needs, and 60s was
   * only ever the incidental cost of the fast-refusal path. [awaitPortFree] moves the same way
   * (~10s to 30s).
   *
   * Measured against [SystemClock.elapsedRealtime], not wall-clock time: a device whose clock is
   * corrected mid-attach — and a test device syncing time after boot is the normal case, not an
   * exotic one — would otherwise either wait past the cap or give up early and fail a trail under
   * `turboRequired`.
   */
  private const val PONG_DEADLINE_MS = 120_000L
  private const val PONG_POLL_INTERVAL_MS = 500L

  private fun awaitPong(targetAppId: String) {
    val expected = "PONG $targetAppId"
    val deadline = SystemClock.elapsedRealtime() + PONG_DEADLINE_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      if (ping() == expected) {
        Console.log("[$LOG_TAG] attached: $expected")
        recordAttached(targetAppId)
        return
      }
      Thread.sleep(PONG_POLL_INTERVAL_MS)
    }
    val last = ping()
    // The idle detector may have bound right at the deadline — one last check before failing the run.
    if (last == expected) {
      Console.log("[$LOG_TAG] attached: $expected")
      recordAttached(targetAppId)
      return
    }
    error(
      "[$LOG_TAG] idle detector never answered PING for $targetAppId (last reply: $last)" +
        if (last != null) {
          " — port ${InProcessIdle.PORT} is held by another idle detector; only one idle detector can serve per device"
        } else {
          ""
        },
    )
  }

  /**
   * Same reason as [PONG_DEADLINE_MS] for using wall clock: each probe's cost is not fixed, so the
   * old count of 40 was ~10s against a refused connect and far more against a held port. Same
   * trade, too — the fast case now waits the full 30s before reporting the port as stuck.
   */
  private const val PORT_FREE_DEADLINE_MS = 30_000L
  private const val PORT_FREE_POLL_INTERVAL_MS = 250L

  private fun awaitPortFree() {
    val deadline = SystemClock.elapsedRealtime() + PORT_FREE_DEADLINE_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      if (ping() == null) return
      Thread.sleep(PORT_FREE_POLL_INTERVAL_MS)
    }
    error(
      "[$LOG_TAG] port ${InProcessIdle.PORT} still answering after detaching the previous idle detector",
    )
  }

  /**
   * Which asset this process last installed which detector package from, or null before its first
   * install. Read by [shouldReuseInstalledDetector]; see [detectorInstallKey] for the key.
   *
   * One slot, so a run that attaches to two different target apps reinstalls on each switch. That
   * is no worse than the unconditional reinstall this replaces, and only one detector can serve per
   * device anyway (they share port [InProcessIdle.PORT]).
   *
   * Never cleared, and does not need to be: a detector package name and its asset path are both
   * derived from the target applicationId, so the same package always yields the same key. A
   * mismatch therefore always names a DIFFERENT package — one the uninstall below did not touch —
   * and can never wrongly match after it.
   *
   * PROCESS-SCOPED ON PURPOSE, and that is what lets the key be a name rather than a content hash.
   * The detector APK ships as an asset INSIDE the test APK, so changing it means a new test APK and
   * therefore a new instrumentation process, which starts this field null and reinstalls. A
   * source-built dev loop can never be served a stale detector. Persist this across processes — to
   * make the install once-per-device instead of once-per-run — and that stops being true: such a
   * version MUST key on the APK's content hash, because the same package + asset path would then
   * span builds that staged different bytes.
   */
  @Volatile
  private var installedFromAssetByThisProcess: String? = null

  /**
   * Identifies one detector install: the package that was installed, and the asset it came from.
   *
   * Both halves matter. The package alone would call a detector for a *different* target app a
   * match, and the asset alone would ignore that the package can be uninstalled underneath us.
   */
  internal fun detectorInstallKey(inProcessIdlePackage: String, assetPath: String): String =
    "$inProcessIdlePackage@$assetPath"

  /**
   * Whether the detector already on the device is the one this process would install anyway, so the
   * install can be skipped.
   *
   * Takes TWO pieces of evidence, and deliberately does not try to be clever about a detector this
   * process did not install. A package installed before this process started may be from an older
   * test APK — nothing readable here says otherwise — so it is reinstalled, which costs one install
   * per instrumentation run and is not the cost worth chasing. What IS worth chasing is the repeat:
   * [ensureAttached] is reached once per trail on any trail whose trailhead force-stops or clears
   * the app (the clear kills the detector, so the PING short-circuit above cannot fire), and
   * reinstalling a byte-identical APK there costs a `pm uninstall` plus a commit-latched
   * [PackageInstaller] session on every trail in the shard.
   *
   * [alreadyInstalled] is the guard that makes the record safe to trust: if anything removed the
   * package after this process installed it, the record still names it and only this says so.
   *
   * KNOWN GAP, measured on a device and deliberately left alone. When a detector is ALREADY serving
   * as this process starts — a device a previous run left attached — [ensureAttached] short-circuits
   * on PING and returns before ever reaching the install, so no record is written; the first attach
   * after a trailhead clears the app then installs after all, and only the ones after that reuse.
   * That costs one extra install per run, never one per trail. Writing the record on the
   * short-circuit would close it and is the WRONG trade: that path trusts a detector this process
   * knows nothing about, so claiming it as ours would let a stale package from an older test APK be
   * reused for the rest of the run instead of being replaced on the next install.
   */
  internal fun shouldReuseInstalledDetector(
    alreadyInstalled: Boolean,
    installedFromAssetByThisProcess: String?,
    installKey: String,
  ): Boolean = alreadyInstalled && installedFromAssetByThisProcess == installKey

  /**
   * The reuse record after an install attempt: [installKey] only when the [PackageInstaller] session
   * reported [PackageInstaller.STATUS_SUCCESS], otherwise [previousRecord] left exactly as it was.
   *
   * [installStatus] is null when no status ever arrived — the commit timed out, or something threw
   * before the broadcast. That is NOT success.
   *
   * A function rather than a well-placed assignment because "never claim an install that did not
   * land" is the property the entire reuse rests on, and placement alone only holds while every
   * failure on the path keeps throwing. Someone who later swallows an install failure instead of
   * rethrowing would otherwise start recording installs that never happened — a device would then
   * skip a needed install and leave `am instrument` with nothing to run — and no test would notice.
   * Taking the installer's own status code means the rule is checked against the real observable.
   */
  internal fun recordAfterInstallAttempt(
    previousRecord: String?,
    installKey: String,
    installStatus: Int?,
  ): String? = if (installStatus == PackageInstaller.STATUS_SUCCESS) installKey else previousRecord

  /**
   * Installs the idle detector APK from the calling test APK's assets via [PackageInstaller] with
   * the shell permission identity — unless this process already installed that exact APK and it is
   * still there ([shouldReuseInstalledDetector]).
   *
   * Reaching this point means the target's idle detector is NOT serving (PING failed or answered for
   * a different app), so a stale install is safe to drop first: at worst uninstalling kills a
   * half-attached idle detector inside the target app's process, and the trails RESUME-launch the
   * app right after. Not serving does NOT imply not installed, though — a trailhead that clears the
   * target app kills the detector's host process while leaving the detector package in place, which
   * is why the reuse check exists.
   */
  private fun installInProcessIdleFromAssets(assetPath: String, inProcessIdlePackage: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // The test APK's own assets live on the instrumentation *context* (not targetContext).
    val context = instrumentation.context
    val uiAutomation = instrumentation.uiAutomation

    val alreadyInstalled =
      try {
        context.packageManager.getPackageInfo(inProcessIdlePackage, 0)
        true
      } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
      }
    val installKey = detectorInstallKey(inProcessIdlePackage, assetPath)
    // Checked before the asset is opened, which is safe precisely because the record only exists
    // once this process opened that asset and installed it successfully.
    //
    // Skipping the uninstall also gives up a SIDE EFFECT it had: uninstalling the detector kills
    // whatever process hosts its code, i.e. the target app — a blunt way of stopping anything still
    // running in there. Deliberately not replaced with an `am force-stop` here, because the
    // `am instrument` further down already force-stops and restarts the target as part of starting
    // instrumentation (see this class's KDoc step 4). Two kills would leave it unreadable which one
    // the attach actually depends on. If a wedged detector ever survives this, the symptom is an
    // `awaitPong` timeout with the package installed — that log line, not a hunch, is what should
    // put an explicit stop back.
    if (shouldReuseInstalledDetector(alreadyInstalled, installedFromAssetByThisProcess, installKey)) {
      Console.log(
        "[$LOG_TAG] reusing $inProcessIdlePackage already installed from asset $assetPath by this run",
      )
      return
    }

    // Open the asset FIRST, so a bundle with no staged detector for this flavor fails with a
    // message naming it while the device is still untouched — before the stale uninstall below and
    // before a PackageInstaller session exists. Uninstalling and then discovering there is nothing
    // to install would leave the device worse off than when the attach started.
    val apkStream = try {
      context.assets.open(assetPath)
    } catch (e: java.io.IOException) {
      error(
        "[$LOG_TAG] no idle detector APK bundled at assets/$assetPath — the target appId's flavor " +
          "has no staged idle detector in this test APK (check the module's inprocess-idle-apks packaging)",
      )
    }

    apkStream.use { apk ->
      if (alreadyInstalled) {
        // PackageInstaller sessions from the shell identity refuse same-version re-installs
        // (INSTALL_FAILED_ALREADY_EXISTS) — drop the stale package first.
        Console.log("[$LOG_TAG] uninstalling stale $inProcessIdlePackage before fresh install")
        AdbCommandUtil.execShellCommand("pm uninstall $inProcessIdlePackage")
      }

      Console.log("[$LOG_TAG] installing $inProcessIdlePackage from asset $assetPath")
      uiAutomation.adoptShellPermissionIdentity()
      try {
        val installer = context.packageManager.packageInstaller
        val params =
          PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        // Abandon the staged session on ANY pre-commit failure: `.use{}` only closes the client
        // handle — the staged session survives system-side until commit, and `ensureAttached`
        // retries per trail, so a repeated failure would otherwise leak sessions until the
        // installer's active-session cap.
        var committed = false
        // The installer's own verdict, hoisted so the record below is derived from it rather than
        // from having reached this line. Stays null if no status ever arrives.
        var installStatus: Int? = null
        try {
          installer.openSession(sessionId).use { session ->
            session.openWrite("inprocess-idle.apk", 0, -1).use { out ->
              apk.copyTo(out)
              session.fsync(out)
            }

            val statusRef = AtomicReference<Pair<Int, String?>>()
            val latch = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
              override fun onReceive(receiverContext: Context, intent: Intent) {
                statusRef.set(
                  intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) to
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
                latch.countDown()
              }
            }
            // Derived from the test package so the action is unique per test bundle by construction.
            val installAction = "${context.packageName}.INSTALL_COMPLETE"
            // RECEIVER_EXPORTED: the status broadcast comes from the system package installer,
            // not from this package. Mandatory flag choice on API 34+.
            context.registerReceiver(receiver, IntentFilter(installAction), Context.RECEIVER_EXPORTED)
            try {
              val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(installAction).setPackage(context.packageName),
                // Mutable: the installer fills in the status extras.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
              )
              session.commit(pendingIntent.intentSender)
              // Commit transfers session ownership to the installer — only pre-commit failures
              // leave a staged session that we must abandon.
              committed = true
              check(latch.await(60, TimeUnit.SECONDS)) {
                "[$LOG_TAG] PackageInstaller commit for $inProcessIdlePackage timed out"
              }
              val (status, message) = statusRef.get()
              installStatus = status
              check(status == PackageInstaller.STATUS_SUCCESS) {
                "[$LOG_TAG] install of $inProcessIdlePackage failed: status=$status message=$message"
              }
            } finally {
              context.unregisterReceiver(receiver)
            }
          }
        } catch (t: Throwable) {
          if (!committed) runCatching { installer.abandonSession(sessionId) }
          throw t
        }
        // Derived from the installer's status, not from having reached this line: every failure
        // above throws today, and [recordAfterInstallAttempt] keeps the record honest if one ever
        // stops throwing.
        installedFromAssetByThisProcess = recordAfterInstallAttempt(
          previousRecord = installedFromAssetByThisProcess,
          installKey = installKey,
          installStatus = installStatus,
        )
        Console.log("[$LOG_TAG] installed $inProcessIdlePackage")
      } finally {
        uiAutomation.dropShellPermissionIdentity()
      }
    }
  }

  private fun enableSettleRace() {
    AdbCommandUtil.execShellCommand("setprop ${InProcessIdle.SETTLE_SYSPROP} 1")
    Console.log("[$LOG_TAG] ${InProcessIdle.SETTLE_SYSPROP}=1 — settle gates race the idle detector")
  }
}
