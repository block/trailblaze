package xyz.block.trailblaze.inprocessidle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Not the feature — the shared *facts* every participant in the in-process idle detector must agree
 * on: the localhost port the detector serves, the `PING`/`PONG` liveness exchange, the detector's
 * package naming convention, the asset path its APK is staged at, the instrumentation component that
 * attaches it, and the sysprop that turns the settle race on. The behavior lives in
 * [InProcessIdleAttacher], [InProcessIdleLaunchReattacher] and [InProcessIdleSettleClient].
 *
 * One home because the writer and the readers are separate objects — [InProcessIdleAttacher]
 * deploys the detector and writes the sysprop, [InProcessIdleLaunchReattacher] re-attaches it
 * around `launchApp`, and [InProcessIdleSettleClient] reads the sysprop and speaks the `AWAIT_IDLE`
 * half of the same protocol. A port, package-name or sysprop change that lands in only some of them
 * doesn't fail loudly; it silently drops the run back to heuristic settle speed.
 *
 * Shared by the on-device participants and by the host: `InProcessIdleApkInstaller` installs the
 * same build over adb and writes the same sysprop, so it has to agree on all of this too. That
 * is why this lives in the shared module rather than beside the Android-only behavior.
 *
 * Public rather than internal so a test module that flips the sysprop itself — an A/B baseline leg
 * forcing the race off, say — names it from here instead of re-typing the string.
 */
object InProcessIdle {

  /** Localhost port the detector's server binds inside the target app's process. */
  const val PORT = 7777

  /** Detector package prefix; see [packageFor]. */
  const val PACKAGE_PREFIX = "xyz.block.trailblaze.inprocessidle."

  /** The bare-`Instrumentation` component `am instrument` starts to attach the detector. */
  const val INSTRUMENTATION_CLASS = "xyz.block.trailblaze.inprocessidle.InProcessIdleInstrumentation"

  /**
   * The `split=` name of the detector's OTHER host: a split APK of the target app's own package
   * whose ContentProvider starts the same server at every process start of the app. Preferred over
   * the instrumentation host whenever a test APK stages one ([splitAssetPathFor]), because it
   * costs a launch nothing — a foreign-package instrumentation makes the framework open the app's
   * APK in a second class loader on every cold start, seconds per launch for a heavy app.
   *
   * A split must carry the base's exact `versionCode` and signature, so it can only be staged by a
   * build that knew the exact app APK (the Gradle plugin's `trailblaze.inProcessIdle.targetApk`).
   * Same name as the plugin's `InProcessIdleConventions.SPLIT_NAME`; the two are how the build and
   * the attach agree on what `pm path` should list.
   */
  const val SPLIT_NAME = "trailblaze_inprocess_idle"

  /**
   * Per-device on/off switch for the settle race, written by the attach and read per settle gate.
   * A sysprop rather than an env var because the reader runs on-device with no host env to inherit.
   */
  const val SETTLE_SYSPROP = "debug.trailblaze.settle.inProcessIdle"

  /** Read bound on a `PING` — the detector answers immediately or not at all. */
  private const val PING_READ_TIMEOUT_MS = 2_000

  /**
   * The one label that identifies a detector build: the target applicationId's last dotted segment
   * (`com.example.app` -> `app`). [packageFor] and [assetPathFor] both derive from this, so the
   * package the attach installs and the asset it installs it from can never name different builds.
   *
   * Consequence worth knowing before adding a target: two applicationIds sharing a last label
   * (`com.a.pos` and `com.b.pos`) collapse to the same flavor, so they cannot have distinct
   * detectors. Only one detector can serve a device at a time anyway ([PORT] is shared), but a
   * bundle staging both would silently install one build for the other's appId.
   */
  fun flavorFor(appId: String): String = appId.substringAfterLast('.')

  /**
   * Detector package for a target applicationId: [flavorFor] appended to [PACKAGE_PREFIX] (e.g.
   * `com.example.app` -> `xyz.block.trailblaze.inprocessidle.app`). The detector APK build uses the
   * same convention, so the two agree without configuration.
   */
  fun packageFor(appId: String): String = PACKAGE_PREFIX + flavorFor(appId)

  /**
   * Where a test APK stages the detector build for a target applicationId, relative to its own
   * assets. Written by the `inProcessIdle { }` block of the Trailblaze Android Gradle plugin and
   * read by [InProcessIdleAttacher].
   */
  fun assetPathFor(appId: String): String = "inprocess-idle-apks/trailblaze-inprocess-idle-${flavorFor(appId)}.apk"

  /**
   * Where a test APK stages the split-APK host ([SPLIT_NAME]) for a target applicationId, relative
   * to its own assets. Absent from a test APK built without `trailblaze.inProcessIdle.targetApk`,
   * which is how "no split for this app build" reads as an absence and falls back to the
   * instrumentation host at [assetPathFor].
   */
  fun splitAssetPathFor(appId: String): String = "inprocess-idle-apks/trailblaze-inprocess-idle-split-${flavorFor(appId)}.apk"

  /**
   * Whether `pm path <app>` output lists the split host: the package manager names an installed
   * split's file `split_<name>.apk` next to `base.apk`.
   */
  fun pmPathListsSplit(pmPathOutput: String): Boolean = pmPathOutput.lineSequence().any {
    it.trim().removePrefix("package:").endsWith("/split_$SPLIT_NAME.apk")
  }

  /**
   * Removes just the split host, leaving the app installed. `pm uninstall <package> <split>` is
   * the documented form; it force-stops the app, like any change to its package.
   */
  fun removeSplitShellArgs(appId: String): List<String> = listOf("pm", "uninstall", appId, SPLIT_NAME)

  /**
   * Opens an install session that INHERITS the app's installed base and adds a split to it —
   * `pm install-create -p <app>`, the same session `adb install-multiple -p` uses.
   *
   * Deliberately the package manager's own command rather than a [android.content.pm.PackageInstaller]
   * session from the test process under the shell identity. An inheriting session created that way
   * is refused on API 34 with `INSTALL_FAILED_ALREADY_EXISTS` ("Attempt to re-install <app> without
   * first uninstalling") — the same rule that makes a same-version re-install fail — while the
   * identical session created by `pm` installs the split. API 29 accepts both, so the failure only
   * shows up on the newer cells.
   */
  fun splitInstallCreateShellArgs(appId: String): List<String> = listOf("pm", "install-create", "-p", appId)

  /**
   * The session id out of `pm install-create` output (`Success: created install session [1234]`),
   * or null when the output names none — which is what a refused create looks like.
   */
  fun parseInstallSessionId(installCreateOutput: String): Int? =
    INSTALL_SESSION_ID.find(installCreateOutput)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * The shell pipeline that streams a split's bytes into install session [sessionId] over stdin:
   * `printf %s <base64> | base64 -d | pm install-write -S <size> <session> <name> -`.
   *
   * Streamed rather than staged as a file because the writer and the reader are different users:
   * the test process cannot write anywhere `pm` (uid 2000) can read, and the device shell this runs
   * through cannot read the test APK's assets. Wrap the result for the transport before running it
   * (`wrapShellPipelineForTransport`) — it is a pipeline, so it needs a real device-side shell.
   *
   * [sizeBytes] is mandatory for a streamed write: `pm` sizes the session from it, and a wrong
   * number truncates or stalls the write rather than failing it.
   */
  fun splitInstallWriteInnerCommand(sessionId: Int, apkBase64: String, sizeBytes: Long): String =
    "printf %s $apkBase64 | base64 -d | pm install-write -S $sizeBytes $sessionId split_$SPLIT_NAME.apk -"

  /** Commits install session [sessionId], installing everything written to it. */
  fun splitInstallCommitShellArgs(sessionId: Int): List<String> = listOf("pm", "install-commit", sessionId.toString())

  /** Discards install session [sessionId]; how a session that was never committed is cleaned up. */
  fun splitInstallAbandonShellArgs(sessionId: Int): List<String> = listOf("pm", "install-abandon", sessionId.toString())

  /**
   * Whether a `pm install-*` step reported success. The transport carries no exit status, so its
   * stdout token is the only signal: `Success` / `Success: streamed N bytes` versus
   * `Failure [INSTALL_FAILED_…]`. Empty output is NOT success — a command that printed nothing
   * never ran.
   */
  fun installOutputSucceeded(pmOutput: String): Boolean =
    pmOutput.lineSequence().any { it.trim().startsWith("Success") }

  /**
   * Bound on the length of the wrapped `install-write` command. The on-device transport execs the
   * command string with no shell, so the whole base64 body rides in one argument, and the kernel
   * caps a single argument (`MAX_ARG_STRLEN`, 128 KiB) — past that the exec fails with a message
   * about the argument list, naming neither the APK nor its size. The detector split is ~13 KB
   * (~23 KB wrapped), so this is headroom, not a limit anyone is near; it exists so a split that
   * one day grows fails saying so.
   */
  const val MAX_INSTALL_WRITE_COMMAND_LENGTH = 100_000

  private val INSTALL_SESSION_ID = Regex("""install session \[(\d+)]""")

  /**
   * Where the CLI binary bundles the detector build for a target applicationId, as a classpath
   * resource. The host-side attach installs from here over adb; a test APK installs the same build
   * from its own assets ([assetPathFor]). Both derive from [flavorFor], so the two delivery
   * mechanisms can never name different builds.
   *
   * A build that bundles no detector for an appId simply has no such resource, which is how "this
   * app cannot be turbo here" reads as an absence rather than an error.
   */
  fun cliResourcePathFor(appId: String): String =
    "/apks/inprocess-idle/trailblaze-inprocess-idle-${flavorFor(appId)}.apk"

  /**
   * Whether `am instrument`'s output says the attach failed.
   *
   * `am instrument` without `-w` exits zero immediately, so a rejected attach — an unresolvable
   * component, or the signature mismatch that is by far the most common cause — shows up only as
   * an error line in its output. Every participant that starts an attach has to read the same
   * thing, or one of them reports success on a detector that never came up.
   */
  fun amInstrumentReportedFailure(output: String): Boolean =
    output.contains("Exception", ignoreCase = true) || output.contains("unable", ignoreCase = true)

  /**
   * Whether `am set-debug-app`'s output says it refused.
   *
   * The command is silent on success, so anything it prints — an `Error: ...` line for a package
   * that isn't installed, or the usage text for a rejected argument — is a refusal. An attach that
   * ignores this runs its `am instrument` anyway and reports an attached target that has none of
   * the ANR protection the debug app exists to give it.
   */
  fun setDebugAppReportedFailure(output: String): Boolean = output.isNotBlank()

  /**
   * The shell command every attach must run immediately before its `am instrument`, so that a
   * target which stalls behind a tap is left stalled rather than killed.
   *
   * Attaching the detector makes the target an *instrumented* process, and ActivityManager treats an
   * input-dispatch timeout in an instrumented process as the end of that instrumentation, not as an
   * ANR: `inputDispatchingTimedOut` calls `finishInstrumentationLocked(keyDispatchingTimedOut)`
   * before the ANR path is ever reached, and finishing an instrumentation force-stops its target
   * package (`Force stopping <app> … finished inst`). So the same 5 s stall that gets an
   * un-instrumented app an "isn't responding" dialog — which a trail dismisses with Wait — kills an
   * attached app outright, with no `am_anr` in the log and no dialog on screen. Measured on an API 34
   * emulator: a SIGSTOPped foreground app plus one tap dies with `finished inst` when attached and
   * reaches `am_anr` when not.
   *
   * Naming the target as the debug app flips the check that runs BEFORE the instrumentation one:
   * `ProcessRecord.isDebugging()` makes `inputDispatchingTimedOut` return false and
   * `appNotResponding` return early, so the stall is waited out and the tap is simply dropped.
   *
   * `--persistent` because every re-attach restarts the target and a transient debug app is consumed
   * by the first process start after it is set. It is stored in `Settings.Global.debug_app`, so it
   * outlives the process that set it — which is why its lifetime is tied to turbo's, not the run's:
   * every site that runs this also runs [clearDebugAppShellArgs] when its `am instrument` is
   * rejected, and every site that turns the settle race off runs it too. A device is never left
   * with a debug app that turbo set and no turbo. `am set-debug-app` also force-stops the package it
   * names, which is why it belongs right before the `am instrument` that restarts the target anyway
   * and never on a path that keeps a running, attached target.
   *
   * Only turbo's attach paths run this. A session that never asked for turbo never names a debug
   * app, and never clears one either.
   *
   * `am set-debug-app` is matched against the app's PROCESS name at process start, not its package
   * name; the default process is the package name unless the manifest puts `android:process` on
   * `<application>`, which none of the apps this repo stages a detector for does. An app that does
   * would need its process name passed here instead.
   */
  fun keepAliveThroughAnrShellArgs(appId: String): List<String> =
    listOf("am", "set-debug-app", "--persistent", appId)

  /**
   * Undoes [keepAliveThroughAnrShellArgs]. The slot is device-wide and unnamed, so this clears
   * whatever debug app is set — acceptable because turbo owns the slot on any device it has
   * attached to, and the command is only ever run where turbo is being turned off or an attach was
   * just rejected. Clearing does not restart anything.
   */
  fun clearDebugAppShellArgs(): List<String> = listOf("am", "clear-debug-app")

  /**
   * The other half of keeping an attached app alive: make sure the device is allowed to SHOW an
   * ANR dialog.
   *
   * [keepAliveThroughAnrShellArgs] only covers input-dispatch timeouts — the check it flips runs
   * before the ANR path. Every other ANR (a service or broadcast the stalled main thread cannot
   * run, a foreground service that never called `startForeground`) still goes through
   * `appNotResponding`, and for a foreground app that ends at the "isn't responding" dialog. On a
   * device with `hide_error_dialogs` set, ActivityManager cannot show that dialog and **kills the
   * app instead** (`killAppAtUsersRequest`, no dialog, launcher in the foreground). A trail can
   * dismiss a dialog with Wait; nothing recovers a killed app.
   *
   * The on-device attach used to set `hide_error_dialogs 1` on every trail, to keep a transient
   * dialog from occluding the UI. Measured on the device farm's slow phone, that traded an
   * occlusion the trails already handle for the kill above, on the same startup stall the control
   * arm survives by tapping Wait. So every attach now puts the setting back to its default. It is a
   * persistent global setting: an earlier run of the old code leaves it set until something writes
   * it again, which is why this is an unconditional write and not a "restore what was there".
   */
  fun showErrorDialogsShellArgs(): List<String> = listOf("settings", "put", "global", "hide_error_dialogs", "0")

  /**
   * One `PING` exchange with whatever holds [PORT]. Returns the reply line (`PONG <appId>` from an
   * attached detector) or null when nothing answers.
   *
   * Never logs: "nothing answers" is the normal state before an attach, and the callers that do
   * care about a failure say so with their own context. Catches `Exception` and not `Throwable` on
   * purpose — a probe that reads a null must mean "the detector isn't there", so an `Error` has to
   * keep propagating rather than be reported as an absent detector.
   *
   * [port] is injectable for tests only; every caller uses the default. A test that bound [PORT]
   * itself would collide with a real detector, and with any other test on the same agent.
   *
   * `@JvmOverloads` keeps the shorter signatures on the class after [readTimeoutMs] was added, so
   * a Java caller compiled against `ping(int, int)` still links. This module is published; Kotlin
   * callers bind to the synthetic `ping$default`, which no annotation can pin, so they still
   * recompile — which they do, since the detector APK this talks to ships from the same build.
   */
  @JvmOverloads
  fun ping(
    connectTimeoutMs: Int,
    port: Int = PORT,
    readTimeoutMs: Int = PING_READ_TIMEOUT_MS,
  ): String? = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
      // Two separate bounds, because a detector that ACCEPTS the connection and then never
      // replies is a different failure from one that isn't listening — and only the read bound
      // caps it. A caller on a latency-sensitive path passes its own, so `connectTimeoutMs`
      // alone is never mistaken for the probe's total cost.
      socket.soTimeout = readTimeoutMs
      socket.getOutputStream().apply {
        write("PING\n".toByteArray())
        flush()
      }
      BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
    }
  } catch (e: Exception) {
    null
  }
}
