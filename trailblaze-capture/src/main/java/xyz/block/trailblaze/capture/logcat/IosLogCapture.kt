package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.AppScopedCaptureStream
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.IosAppProcess
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.isMacOs

/**
 * Captures iOS Simulator system log using `xcrun simctl spawn log stream`.
 *
 * This is the iOS equivalent of Android's logcat. The log is streamed directly to a file, filtered to
 * the app under test when one is known, and to errors and faults otherwise.
 *
 * ### Output format
 *
 * ```
 * 2026-03-10 14:23:45.678901-0700  MyApp[12345]: (subsystem) message
 * ```
 *
 * ### Which clock
 * Host, for both bookends — and unlike Android's logcat, that is also the clock the log lines
 * themselves are stamped with, so the two agree without conversion. A simulator runs on this Mac
 * and reads this Mac's clock, so there is no device-host skew on this platform at all.
 */
class IosLogCapture : CaptureStream, AppScopedCaptureStream {
  override val type = CaptureType.LOGCAT
  override var isAppScoped: Boolean = false
    private set

  override var appProcessName: String? = null
    private set

  private var process: Process? = null
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    if (!isMacOs()) return
    isAppScoped = false
    appProcessName = null
    startTimestampMs = System.currentTimeMillis()
    outputFile = File(sessionDir, CaptureFilenames.DEVICE_LOG)

    val installed = appId?.let { IosHostSimctlUtils.getInstalledAppProcess(deviceId, it) }
    val (command, appScoped) = buildIosLogStreamCommand(deviceId, appId, installed)
    isAppScoped = appScoped
    // The install's extensions log into the same device.log, and a crash of theirs is not the app's.
    // Only appinfo's executable name can tell them apart: the bundle-id guess may not be the app's
    // real name, and trusting it would discard the app's own crashes.
    if (appScoped) appProcessName = installed?.executableName
    if (!appScoped && appId != null) {
      Console.log(
        "iOS log capture: appId '$appId' resolved to a blank process name; " +
          "capturing only errors and faults from all simulator processes.",
      )
    } else if (appId != null && installed == null) {
      Console.log(
        "iOS log capture: '$appId' is not installed yet, so its process name is guessed from the " +
          "bundle id ('${appId.substringAfterLast(".")}'); lines from a differently named " +
          "executable will be missing from device.log.",
      )
    }

    try {
      val pb = ProcessBuilder(command).redirectOutput(outputFile).redirectErrorStream(true)
      process = pb.start()
    } catch (e: Exception) {
      Console.log("Failed to start iOS log capture: ${e.message}")
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val p = process
    if (p != null) {
      // Try a graceful SIGTERM first so xcrun has a chance to flush its buffered output to
      // device.log; only escalate to SIGKILL if it refuses to exit. Forced termination mid-write
      // can leave a truncated tail, so log the path taken for postmortem visibility.
      p.destroy()
      val exitedGracefully = p.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!exitedGracefully) {
        Console.log(
          "iOS log capture: xcrun did not exit within ${SHUTDOWN_TIMEOUT_SECONDS}s — force-killing; " +
            "device.log may be truncated.",
        )
        p.destroyForcibly()
        p.waitFor(1, TimeUnit.SECONDS)
      }
    }
    process = null

    val file = outputFile ?: return null
    if (!file.exists() || file.length() == 0L) return null

    return CaptureArtifact(
      file = file,
      type = CaptureType.LOGCAT,
      startTimestampMs = startTimestampMs,
      endTimestampMs = System.currentTimeMillis(),
    )
  }

  private companion object {
    /** How long to give `xcrun simctl spawn log stream` to drain after `destroy()`. */
    const val SHUTDOWN_TIMEOUT_SECONDS = 2L
  }
}

/** The `log stream` argv for [deviceId], and whether it is scoped to the app under test. */
internal data class IosLogStreamCommand(val argv: List<String>, val isAppScoped: Boolean)

/**
 * Keeps only errors and faults, from every process. Used when there is no app to scope to (target
 * `default`, or an unusable app id): the unfiltered simulator log runs to megabytes a minute of
 * system-daemon chatter (`dasd`, `runningboardd`, SpringBoard), about 20x what this keeps, and
 * crashes and assertion failures still come through.
 */
internal const val IOS_UNSCOPED_LOG_PREDICATE = "messageType == error OR messageType == fault"

/**
 * Builds the `xcrun simctl spawn <device> log stream` argv. Scoped to the app under test when
 * [appId] yields a predicate (see [buildIosLogStreamPredicate]); otherwise limited to
 * [IOS_UNSCOPED_LOG_PREDICATE] rather than capturing the whole simulator log.
 */
internal fun buildIosLogStreamCommand(
  deviceId: String,
  appId: String?,
  installed: IosAppProcess? = null,
): IosLogStreamCommand {
  val argv =
    mutableListOf(
      "xcrun",
      "simctl",
      "spawn",
      deviceId,
      "log",
      "stream",
      "--style",
      "compact",
      // info-and-above (notice/error/fault too) rather than `debug`: the unified log emits an
      // enormous volume of debug-level chatter, and a test report wants the app's meaningful
      // output, not the firehose. The iOS analog of not dumping every logcat VERBOSE line.
      "--level",
      "info",
    )
  val appPredicate = buildIosLogStreamPredicate(appId, installed)
  argv.addAll(listOf("--predicate", appPredicate ?: IOS_UNSCOPED_LOG_PREDICATE))
  return IosLogStreamCommand(argv, isAppScoped = appPredicate != null)
}

/**
 * Builds the `log stream --predicate` that scopes capture to the app under test — the
 * logcat-equivalent app filter, NOT the full system-log firehose (which is orders of magnitude
 * noisier than Android logcat). Returns `null` when [appId] is null or its last component is
 * blank, in which case the caller falls back to [IOS_UNSCOPED_LOG_PREDICATE].
 *
 * Matches the app three ways, because an app's logs don't all carry the same identity (matching
 * ONLY `process == <last-bundle-component>` missed apps whose process name differs from that
 * component, or that emit `os_log` under a subsystem — the near-empty capture in PR #3944):
 * - `process ==[c] "<name>"`         — NSLog / print / os_log from the main executable
 * - `subsystem BEGINSWITH[c] "<id>"` — os_log via `Logger(subsystem: <bundleId>, …)`
 * - `processImagePath CONTAINS[c]`   — helper / XPC processes whose name differs
 *
 * Those three clauses are only the fallback, for an app whose install directory isn't known
 * (usually: not installed yet). `<name>` is then the executable appinfo reported, or else the last
 * bundle-id component. That guess is wrong for any app whose executable isn't named after its
 * bundle id: `com.example.wallet.dogfood` running as `Wallet` matched none of the clauses, so an
 * app-scoped `device.log` held none of its lines, crashes included.
 *
 * Once [installed] is known, the predicate is scoped to that one install instead:
 * - `processImagePath CONTAINS[c] "/<container-id>/<Name>.app/"` — the main executable and its
 *   extensions, all of which run from the install's own directory
 * - `subsystem ==[c] "<id>" OR subsystem BEGINSWITH[c] "<id>."` — os_log under the bundle id
 * Neither matches another build of the same app installed alongside it, which shares the
 * executable and `.app` names but has its own container and bundle id. A bare `process ==` or
 * bundle-id prefix clause would, and that build's crash would then fail this run.
 *
 * `[c]` = case-insensitive: a bundle id's last component is often lowercase (`sampleapp`,
 * `mobilesafari`) while the real process name / image path uses the app's actual casing
 * (`MobileSafari`), so exact-case matching can silently capture nothing.
 *
 * Every form excludes [IOS_XCTEST_SUBSYSTEM]: XCTest's accessibility queries run inside the app's
 * own process, so an app-scoped clause matches them, and they outnumber the app's lines 30 to 1.
 * It also keeps only the errors and faults of [IOS_NETWORK_SUBSYSTEM], Apple's networking stack
 * in the same process, whose connection bookkeeping was most of what was left.
 *
 * Internal (not private) purely so it can be unit-tested without spawning a process.
 */
internal fun buildIosLogStreamPredicate(appId: String?, installed: IosAppProcess? = null): String? {
  if (appId == null) return null
  val appClauses = installed?.installDir?.let { installDir ->
    "processImagePath CONTAINS[c] \"$installDir\" OR " +
      "subsystem ==[c] \"$appId\" OR " +
      "subsystem BEGINSWITH[c] \"$appId.\""
  } ?: run {
    val processName = iosAppProcessName(appId, installed)
    if (processName.isBlank()) return null
    "process ==[c] \"$processName\" OR " +
      "subsystem BEGINSWITH[c] \"$appId\" OR " +
      "processImagePath CONTAINS[c] \"$processName\""
  }
  return "($appClauses) AND NOT subsystem BEGINSWITH[c] \"$IOS_XCTEST_SUBSYSTEM\" AND " +
    "NOT (subsystem BEGINSWITH[c] \"$IOS_NETWORK_SUBSYSTEM\" AND messageType != error AND messageType != fault)"
}

/**
 * The subsystem XCTest logs under from inside the app it drives. Every element it reads logs a
 * fetch, a reply, and the attributes it faulted in: one iPad trail wrote 2.7 million of these lines
 * (300 MB) to `device.log`, beside 93,000 of the app's own.
 */
internal const val IOS_XCTEST_SUBSYSTEM = "com.apple.dt.xctest"

/**
 * Apple's networking stack, logging every connection's state changes from inside the app: 14.6 MB
 * of the 18.5 MB that same session kept once XCTest was gone. The app's own requests are captured
 * separately, so only this subsystem's errors and faults (a failed TLS handshake) are worth a line.
 */
internal const val IOS_NETWORK_SUBSYSTEM = "com.apple.network"

/** The app's process name: the executable appinfo reported, or else the last bundle-id component. */
internal fun iosAppProcessName(appId: String, installed: IosAppProcess?): String =
  installed?.executableName ?: appId.substringAfterLast(".")
