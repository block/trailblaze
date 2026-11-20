package xyz.block.trailblaze.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object IosHostSimctlUtils {

  /**
   * `simctl pbcopy` can synchronize through the host pasteboard, so all pasteboard reads, writes,
   * and compound stage-and-paste transactions must share one process-wide lock.
   */
  private val pasteboardLock = ReentrantLock()
  // Accessed only under pasteboardLock. A failed kill must not permit another transaction.
  private val pendingPasteboardProcesses = mutableListOf<Process>()

  private fun requirePasteboardIdle() {
    pendingPasteboardProcesses.removeAll { !it.isAlive }
    check(pendingPasteboardProcesses.isEmpty()) {
      "A previous simulator pasteboard process has not exited; refusing overlapping clipboard access"
    }
  }

  /** Runs [action] while excluding every pasteboard operation issued by this process. */
  fun <T> withPasteboardLock(action: () -> T): T = pasteboardLock.withLock {
    requirePasteboardIdle()
    action()
  }

  /**
   * Runs [action] under the shared pasteboard lock, returning [onTimeout] if another clipboard
   * operation keeps the lock longer than [timeoutMillis].
   */
  fun <T> withPasteboardLock(
    timeoutMillis: Long,
    onTimeout: () -> T,
    action: () -> T,
  ): T {
    require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
    if (!pasteboardLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return onTimeout()
    return try {
      requirePasteboardIdle()
      action()
    } finally {
      pasteboardLock.unlock()
    }
  }

  /**
   * Lists the UDIDs of currently booted iOS simulators via `xcrun simctl list devices booted`.
   * Returns empty on non-macOS hosts or when none are booted.
   *
   * Exists so callers can iterate real device ids instead of passing the `booted` alias directly
   * to a `simctl` subcommand — with more than one simulator booted, `booted` is ambiguous for some
   * subcommands, where enumerating and trying each real UDID is unambiguous regardless.
   */
  fun listBootedDeviceIds(): List<String> {
    if (!isMacOs()) return emptyList()
    val output = readSimctl(listOf("list", "devices", "booted"))
    val udidRegex = Regex("\\(([0-9A-Fa-f-]{36})\\)\\s*\\(Booted\\)")
    return output.stdout.lines().mapNotNull { udidRegex.find(it)?.groupValues?.get(1) }
  }

  /**
   * Resolves the on-disk `.app` bundle directory for [appId] on [deviceId] via
   * `xcrun simctl get_app_container <deviceId> <appId> app` — the app's installed bundle itself,
   * as opposed to [clearAppDataContainer]'s `data` container. Returns null on non-macOS hosts, or
   * when the app isn't installed on that device (non-zero exit / no output).
   */
  fun getAppBundlePath(deviceId: String, appId: String): File? {
    if (!isMacOs()) return null
    val output = readSimctl(listOf("get_app_container", deviceId, appId, "app"))
    if (!output.success) return null
    val path = output.stdout.lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return File(path).takeIf { it.isDirectory }
  }

  /**
   * The process identity of [appId] as installed on [deviceId], read from
   * `xcrun simctl appinfo <deviceId> <appId>`: its `CFBundleExecutable`, which is the name the
   * unified log records as `process`, and the install's own `.app` directory. A bundle id's last
   * component is only a guess at either: `com.example.wallet.dogfood` can run as `Wallet` from
   * `Wallet.app`. Returns null on non-macOS hosts, when the app isn't installed, when appinfo
   * reports no executable, or when appinfo doesn't answer within [APPINFO_TIMEOUT_SECONDS]: log
   * capture starts synchronously with the session, so a stalled probe must not hold it up.
   */
  fun getInstalledAppProcess(deviceId: String, appId: String): IosAppProcess? {
    if (!isMacOs()) return null
    val output = readSimctl(listOf("appinfo", deviceId, appId), APPINFO_TIMEOUT_SECONDS)
    if (output.timedOut) {
      Console.log("simctl appinfo for '$appId' timed out after ${APPINFO_TIMEOUT_SECONDS}s")
      return null
    }
    if (!output.success) return null
    return parseAppProcessFromAppInfo(output.stdout.lines())
  }

  private const val APPINFO_TIMEOUT_SECONDS = 10L

  /**
   * Parses [IosAppProcess] from `xcrun simctl appinfo` output, the same plist-style dictionary
   * one `listapps` entry holds (`    CFBundleExecutable = Wallet;`, `    Path = "/…/Wallet.app";`).
   *
   * [IosAppProcess.installDir] is the `.app` directory with its parent, the per-install container
   * (`/<container-id>/Wallet.app/`). The container is what tells this install apart from another
   * build of the same app that shares the executable and `.app` names.
   *
   * `internal` (widened just enough for the same-module parser unit test): the only production
   * caller is [getInstalledAppProcess].
   */
  internal fun parseAppProcessFromAppInfo(outputLines: List<String>): IosAppProcess? {
    fun field(key: String): String? {
      val regex = Regex("^\\s+$key\\s*=\\s*\"?([^;\"]*)\"?\\s*;")
      return outputLines.firstNotNullOfOrNull { regex.matchEntire(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }
    }
    val executable = field("CFBundleExecutable") ?: return null
    val pathParts = field("Path")?.trimEnd('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
    val installDir =
      if (pathParts.size >= 2 && pathParts.last().endsWith(".app")) {
        "/${pathParts[pathParts.size - 2]}/${pathParts.last()}/"
      } else {
        null
      }
    return IosAppProcess(executableName = executable, installDir = installDir)
  }

  /**
   * `xcrun simctl launch <deviceId> <appId> [arguments…]`: starts the app, or brings it to the front
   * if it is already running (same pid, nothing restarted). [arguments] become the process's argv
   * after its executable, so they reach only a launch that starts the process: an app already
   * running keeps its own and silently drops these. [secrets] are masked wherever the command line
   * or its output is logged or thrown. Throws with simctl's output on failure, which for an app that
   * is not installed names the bundle id it could not open.
   */
  fun launchApp(
    deviceId: String,
    appId: String,
    arguments: List<String> = emptyList(),
    secrets: Collection<String> = emptyList(),
  ) {
    requireMacOs("simctl launch")
    val output = runSimctlBlocking(
      "launch $appId",
      listOf("launch", deviceId, appId) + arguments,
      SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS,
      secrets,
    )
    check(output.success) {
      redactSensitiveValuesIn(
        "simctl launch $appId failed (exit ${output.exitCode}): ${output.output}",
        secrets,
      )
    }
  }

  /**
   * `xcrun simctl terminate <deviceId> <appId>`. Returns false when the app was not running
   * (simctl exits nonzero with "found nothing to terminate" — the state the caller wanted anyway);
   * throws on any other failure. simctl returns once the signal is sent, so this then waits for the
   * process to exit: a launch right after must start a new one.
   */
  suspend fun terminateApp(deviceId: String, appId: String): Boolean {
    requireMacOs("simctl terminate")
    val output = runSimctl("terminate $appId", listOf("terminate", deviceId, appId), SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS)
    if (output.success) {
      awaitAppExit(deviceId, appId)
      return true
    }
    val text = output.output
    if (isNotRunningTerminateOutput(text)) return false
    error("simctl terminate $appId failed (exit ${output.exitCode}): $text")
  }

  /**
   * `xcrun simctl privacy <deviceId> <action> <service> <appId>`, where [action] is `grant`,
   * `revoke` or `reset`. simctl terminates the app if it is running when a grant changes, so set
   * these before launching. Throws with simctl's output on failure (e.g. an unknown service name).
   */
  suspend fun setPrivacy(deviceId: String, appId: String, action: String, service: String) {
    requireMacOs("simctl privacy")
    val output = runSimctl(
      "privacy $action $service $appId",
      listOf("privacy", deviceId, action, service, appId),
      SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS,
    )
    check(output.success) {
      "simctl privacy $action $service $appId failed (exit ${output.exitCode}): ${output.output}"
    }
  }

  /**
   * `xcrun simctl keychain <deviceId> reset`: empties the simulator's keychain for every app.
   * Deleting an app's data container leaves its keychain items behind, so a signed-in app comes
   * back signed in without this.
   */
  suspend fun resetKeychain(deviceId: String) {
    requireMacOs("simctl keychain reset")
    val output = runSimctl("keychain reset", listOf("keychain", deviceId, "reset"))
    check(output.success) {
      "simctl keychain reset failed (exit ${output.exitCode}): ${output.output}"
    }
  }

  /**
   * Runs simctl with [args], killing it after [timeoutSeconds] or on cancellation: a stalled
   * CoreSimulator otherwise hangs the step, and the session teardown behind it, indefinitely.
   * Throws on timeout; the caller judges the exit code. [secrets] are masked in the logged command.
   */
  private suspend fun runSimctl(
    what: String,
    args: List<String>,
    timeoutSeconds: Long = SIMCTL_ACTION_TIMEOUT_SECONDS,
    secrets: Collection<String> = emptyList(),
  ): SimctlCommand.Result {
    logCommand(args, secrets)
    val output = SimctlCommand.runSuspending(args, timeoutSeconds)
    check(!output.timedOut) { "simctl $what did not finish within ${timeoutSeconds}s" }
    return output
  }

  /** [runSimctl] for the blocking callers. */
  private fun runSimctlBlocking(
    what: String,
    args: List<String>,
    timeoutSeconds: Long = SIMCTL_ACTION_TIMEOUT_SECONDS,
    secrets: Collection<String> = emptyList(),
  ): SimctlCommand.Result = runBlocking { runSimctl(what, args, timeoutSeconds, secrets) }

  /**
   * A read whose failure the caller turns into an empty answer: never throws, and a timeout is just
   * an unsuccessful [SimctlCommand.Result].
   */
  private fun readSimctl(args: List<String>, timeoutSeconds: Long = SIMCTL_READ_TIMEOUT_SECONDS): SimctlCommand.Result {
    logCommand(args)
    return SimctlCommand.run(args, timeoutSeconds)
  }

  private fun logCommand(args: List<String>, secrets: Collection<String> = emptyList()) {
    Console.log(redactSensitiveValuesIn("Running simctl ${args.joinToString(" ")}", secrets))
  }

  private const val SIMCTL_ACTION_TIMEOUT_SECONDS = 10L

  /**
   * Device listings and app inventory. Generous, since `listapps` on a simulator with many apps is
   * slow; it only stops a wedged CoreSimulator from hanging the caller forever.
   */
  private const val SIMCTL_READ_TIMEOUT_SECONDS = 60L

  /**
   * Launch, privacy grant and the app-data clear's lookup, terminate and exit wait can wait on a
   * cold or loaded simulator, as on a fresh CI agent. Generous: it costs nothing when simctl is quick.
   */
  private const val SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS = 120L

  /** simctl's wording for "that app is not running" varies across Xcode versions. */
  internal fun isNotRunningTerminateOutput(output: String): Boolean {
    val text = output.lowercase()
    return NOT_RUNNING_TERMINATE_MARKERS.any { it in text }
  }

  private val NOT_RUNNING_TERMINATE_MARKERS =
    listOf("found nothing to terminate", "no such process", "not running")

  private fun requireMacOs(what: String) {
    check(isMacOs()) { "$what needs a macOS host with Xcode" }
  }

  /** What [clearAppDataContainer] found. */
  enum class ClearAppDataOutcome { CLEARED, NOT_INSTALLED }

  /**
   * Empties [appId]'s data container in place: terminates the app, waits for its process to exit
   * (a dying app can flush state back into the container after the wipe), then deletes the
   * container's children without following symlinks, so a link inside the container can't reach
   * host files. Throws when the lookup or any deletion fails: a partial wipe that reports success
   * would start the next step against stale data. The keychain and privacy grants are untouched,
   * except when the container has lost its [CONTAINER_METADATA_FILE]: then the app is reinstalled
   * from its own bundle, the only way to get an owned container back, which also drops its grants.
   */
  suspend fun clearAppDataContainer(deviceId: String, appId: String): ClearAppDataOutcome {
    requireMacOs("simctl get_app_container")
    val lookup = runSimctl(
      "get_app_container $appId data",
      listOf("get_app_container", deviceId, appId, "data"),
      SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS,
    )
    val lookupText = lookup.output
    if (!lookup.success) {
      if (isNotInstalledContainerOutput(lookupText)) return ClearAppDataOutcome.NOT_INSTALLED
      error("simctl get_app_container $appId data failed (exit ${lookup.exitCode}): $lookupText")
    }
    val dataPath = Paths.get(lookup.stdout.lines().first { it.isNotBlank() }.trim())

    terminateApp(deviceId, appId)

    // A wipe that deleted the owner record (as this one did before it kept it) orphans the
    // container, and the simulator deletes an orphan on reboot. Launching then rebuilds only part
    // of it, which crashes apps on their first save, so repair it rather than wipe it.
    if (needsContainerRepair(appId, dataPath)) {
      Console.log("$appId's data container at $dataPath has no owner record; reinstalling to repair it")
      reinstallFromInstalledBundle(deviceId, appId)
      return ClearAppDataOutcome.CLEARED
    }
    Console.log("Clearing $appId data container under $dataPath")
    val undeleted = resetAppDataContainer(dataPath)
    check(undeleted.isEmpty()) { "failed to delete from $appId's data container: ${undeleted.joinToString(", ")}" }
    return ClearAppDataOutcome.CLEARED
  }

  /**
   * Reinstalls [appId] from a copy of its own installed bundle, which gives it a fresh data
   * container with a new [CONTAINER_METADATA_FILE]. Once the uninstall has started, the install
   * runs to the end even if the uninstall failed, timed out or was cancelled, so the app is never
   * left missing by an interrupted repair; if every install attempt fails, the copy is kept and
   * named in the error so it can be reinstalled by hand. Throws if either step failed.
   */
  suspend fun reinstallFromInstalledBundle(deviceId: String, appId: String) {
    val lookup = runSimctl(
      "get_app_container $appId app",
      listOf("get_app_container", deviceId, appId, "app"),
      SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS,
    )
    check(lookup.success) { "simctl get_app_container $appId app failed (exit ${lookup.exitCode}): ${lookup.output}" }
    val bundle = Paths.get(lookup.stdout.lines().first { it.isNotBlank() }.trim())
    val tmpDir = Files.createTempDirectory("ios-app-repair-")
    val copy = tmpDir.resolve(bundle.fileName.toString())
    var keepCopy = false
    try {
      copyDirectoryPreservingAttributes(bundle, copy)
      keepCopy = true
      val uninstall = runCatching {
        runSimctl("uninstall $appId", listOf("uninstall", deviceId, appId), SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS)
      }
      withContext(NonCancellable) { installWithRetry(deviceId, appId, copy) }
      keepCopy = false
      val uninstalled = uninstall.getOrThrow()
      check(uninstalled.success) { "simctl uninstall $appId failed (exit ${uninstalled.exitCode}): ${uninstalled.output}" }
    } finally {
      if (keepCopy) {
        Console.log("Kept $appId's bundle at $copy; reinstall it with `xcrun simctl install $deviceId $copy`")
      } else {
        runCatching { deleteRecursivelyNoFollowLinks(copy) }
        runCatching { Files.deleteIfExists(tmpDir) }
      }
    }
  }

  /**
   * Recursive copy that preserves POSIX permissions. `copyToRecursively`'s default action drops
   * file modes, and `simctl install` only repairs the main executable's, so embedded extension
   * binaries in a reinstalled bundle would lose their execute bit and crash on launch. Symlinks
   * are replicated as links, never followed, matching `cp -R`.
   */
  fun copyDirectoryPreservingAttributes(source: Path, target: Path) {
    @OptIn(ExperimentalPathApi::class)
    source.copyToRecursively(target, followLinks = false) { src, dst ->
      Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
      CopyActionResult.CONTINUE
    }
  }

  /**
   * Deletes [path] recursively, removing symlinks as links: `File.deleteRecursively()` follows
   * directory links, and an absolute link inside a copied bundle would have its target deleted.
   */
  @OptIn(ExperimentalPathApi::class)
  fun deleteRecursivelyNoFollowLinks(path: Path) {
    path.deleteRecursively()
  }

  /**
   * `simctl install` from [bundle], up to three tries with backoff. Install is idempotent, so a
   * retry after a timed-out attempt that finished in the background is safe; a single failed
   * install right after an uninstall would leave the app missing for every later step.
   */
  private suspend fun installWithRetry(deviceId: String, appId: String, bundle: Path) {
    var failure = ""
    for (backoffMs in longArrayOf(0L, 1_000L, 3_000L)) {
      delay(backoffMs)
      val attempt = runCatching {
        runSimctl("install $appId", listOf("install", deviceId, bundle.toString()), SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS)
      }
      val result = attempt.getOrNull()
      if (result?.success == true) return
      failure = result?.let { "exit ${it.exitCode}: ${it.output}" } ?: attempt.exceptionOrNull().toString()
    }
    error("simctl install $appId failed after 3 tries ($failure); its bundle is kept at $bundle")
  }

  /**
   * `simctl terminate` returns once the signal is sent. Polls the simulator's launchd until [appId]
   * has no running process, failing after [timeoutMillis] rather than reporting a live app stopped.
   */
  private suspend fun awaitAppExit(
    deviceId: String,
    appId: String,
    timeoutMillis: Long = SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS * 1_000,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
      if (!isAppRunning(deviceId, appId)) return
      check(System.currentTimeMillis() < deadline) { "$appId was still running ${timeoutMillis}ms after terminate" }
      delay(250)
    }
  }

  /** Whether the simulator's launchd lists a live process for [appId]. Throws if it can't be read. */
  suspend fun isAppRunning(deviceId: String, appId: String): Boolean {
    requireMacOs("simctl spawn launchctl list")
    val list = runSimctl(
      "spawn launchctl list",
      listOf("spawn", deviceId, "launchctl", "list"),
      SIMCTL_SLOW_ACTION_TIMEOUT_SECONDS,
    )
    check(list.success) {
      "simctl spawn launchctl list failed (exit ${list.exitCode}): ${list.output}"
    }
    return isAppRunning(list.stdout.lines(), appId)
  }

  /** A `launchctl list` row for a UIKit app with a live pid (first column numeric, not `-`). */
  internal fun isAppRunning(launchctlList: List<String>, appId: String): Boolean =
    launchctlList.any { line ->
      line.contains("UIKitApplication:$appId[") && line.substringBefore('\t').trim().toIntOrNull() != null
    }

  /** `get_app_container` exits with POSIX ENOENT when the app isn't installed. */
  internal fun isNotInstalledContainerOutput(output: String): Boolean =
    "NSPOSIXErrorDomain, code=2)" in output

  /** Clears stale state, then restores the container layout an installed app expects. */
  internal fun resetAppDataContainer(root: Path): List<String> {
    val undeleted = deleteChildrenWithoutFollowingLinks(root)
    if (undeleted.isEmpty()) recreateStockAppDataDirectories(root)
    return undeleted
  }

  /**
   * Installing an app creates these directories, but launching an already-installed app does not
   * restore them after a wipe. Apps assume they exist: a URLSession download, for one, fails with
   * ENOENT creating its temporary file when `tmp` is missing.
   */
  fun recreateStockAppDataDirectories(root: Path) {
    listOf("Documents", "Library/Caches", "Library/Preferences", "tmp").forEach {
      Files.createDirectories(root.resolve(it))
    }
  }

  /**
   * The container manager's record that a container belongs to its app, written by install. Wiping
   * it orphans the container: the simulator later deletes the folder, and the app's next launch
   * starts in a half-built one (an app can crash saving into `Library/Caches`). Wipes keep it.
   */
  const val CONTAINER_METADATA_FILE = ".com.apple.mobile_container_manager.metadata.plist"

  /**
   * Whether [appId]'s container at [dataPath] lost its [CONTAINER_METADATA_FILE] and needs the
   * reinstall repair. Never for an Apple system app (`com.apple.*`): the OS refuses to uninstall
   * one, so it gets the in-place wipe instead.
   */
  fun needsContainerRepair(appId: String, dataPath: Path): Boolean =
    !appId.startsWith("com.apple.") && !Files.isRegularFile(dataPath.resolve(CONTAINER_METADATA_FILE))

  /**
   * Deletes everything under [root] except its [CONTAINER_METADATA_FILE], leaving [root] itself.
   * Symlinks are deleted as links, never followed. Returns the paths that could not be deleted.
   */
  fun deleteChildrenWithoutFollowingLinks(root: Path): List<String> {
    val undeleted = mutableListOf<String>()
    Files.newDirectoryStream(root) { it.fileName.toString() != CONTAINER_METADATA_FILE }.use { children ->
      children.forEach { child ->
        Files.walkFileTree(
          child,
          object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (!tryDelete(file)) undeleted += file.toString()
              return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
              undeleted += file.toString()
              return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
              if (exc != null || !tryDelete(dir)) undeleted += dir.toString()
              return FileVisitResult.CONTINUE
            }
          },
        )
      }
    }
    return undeleted
  }

  private fun tryDelete(path: Path): Boolean = try {
    Files.deleteIfExists(path)
    true
  } catch (e: IOException) {
    false
  }

  /**
   * Sets the iOS simulator's pasteboard (clipboard) to the given text via
   * `xcrun simctl pbcopy <deviceId>`, which reads the new pasteboard content from
   * stdin. (The `pasteboard` subcommand does not exist in current Xcode versions;
   * `pbcopy`/`pbpaste`/`pbsync` are the supported pasteboard subcommands.) Used by
   * the cross-platform `mobile_setClipboard` tool's iOS branch.
   *
   * Text stays off process arguments and diagnostics; stderr is discarded rather than mixing it
   * with clipboard contents.
   *
   * Returns only once `pbpaste` reads [text] back, and throws otherwise. While Xcode 27's
   * DeviceHub.app syncs its pasteboard, `pbcopy` exits 0 and changes nothing; CI quits DeviceHub
   * before booting a simulator, and anywhere else the error says to quit it. [timeoutMillis] bounds
   * the whole write, lock included. AXe text entry uses this to stage what it pastes.
   */
  fun setPasteboard(deviceId: String, text: String, timeoutMillis: Long = PASTEBOARD_WRITE_TIMEOUT_MS) {
    if (!isMacOs()) error("setPasteboard is only supported on macOS hosts")
    writePasteboardVerified(deviceId, text, timeoutMillis)
  }

  /**
   * [setPasteboard] without the macOS guard. One [timeoutMillis] deadline covers lock acquisition,
   * every command and the readback waits. [runCommand] is [runPasteboardCommand]'s
   * `(argv, stdin, timeoutMillis) -> stdout`, and [sleep] and [nanoTime] drive the deadline; all
   * are replaceable for tests.
   */
  internal fun writePasteboardVerified(
    deviceId: String,
    text: String,
    timeoutMillis: Long = PASTEBOARD_WRITE_TIMEOUT_MS,
    runCommand: (args: List<String>, stdin: String, timeoutMillis: Long) -> String =
      { args, stdin, timeout -> runPasteboardCommand(args, stdin, timeout) },
    sleep: (Long) -> Unit = Thread::sleep,
    nanoTime: () -> Long = System::nanoTime,
  ) {
    val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
    fun run(args: List<String>, stdin: String = ""): String {
      val remaining = remainingMillis()
      check(remaining > 0) { "Setting the simulator pasteboard took longer than ${timeoutMillis}ms" }
      return runCommand(args, stdin, remaining)
    }

    withPasteboardLock(
      timeoutMillis = remainingMillis().coerceAtLeast(0),
      onTimeout = { error("Timed out waiting for the simulator pasteboard lock") },
    ) {
      run(SimctlCommand.argv("pbcopy", deviceId), text)
      repeat(PASTEBOARD_READBACK_ATTEMPTS) { attempt ->
        if (attempt > 0) sleep(PASTEBOARD_READBACK_INTERVAL_MS.coerceAtMost(remainingMillis().coerceAtLeast(0)))
        if (run(SimctlCommand.argv("pbpaste", deviceId)) == text) return@withPasteboardLock
      }
      error(
        "simctl pbcopy exited 0 but $deviceId's pasteboard did not take the text. Xcode's DeviceHub " +
          "(or Simulator.app) syncing its pasteboard drops these writes; quit it and retry.",
      )
    }
  }

  private const val PASTEBOARD_WRITE_TIMEOUT_MS = 30_000L
  private const val PASTEBOARD_READBACK_ATTEMPTS = 10
  private const val PASTEBOARD_READBACK_INTERVAL_MS = 100L

  /**
   * Reads the iOS simulator's current pasteboard (clipboard) content via
   * `xcrun simctl pbpaste <deviceId>`. Counterpart to [setPasteboard]. Used by the iOS branch
   * of the cross-platform `mobile_pasteClipboard` tool to read the value `mobile_setClipboard`
   * wrote, so it can be typed directly (mirroring the Android branch's `InputTextCommand`
   * approach) instead of relying on Maestro's iOS `PasteTextCommand` (long-press + tap "Paste"
   * on the system edit menu), which does not reliably complete in simulator/CI environments.
   */
  fun getPasteboard(deviceId: String): String {
    if (!isMacOs()) error("getPasteboard is only supported on macOS hosts")
    return runPasteboardCommand(SimctlCommand.argv("pbpaste", deviceId))
  }

  /** One deadline covers lock acquisition, pipe IO, and process completion. */
  internal fun runPasteboardCommand(
    args: List<String>,
    stdin: String = "",
    timeoutMillis: Long = 30_000,
    startProcess: (ProcessBuilder) -> Process = { it.start() },
  ): String {
    require(timeoutMillis > 0)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    fun remaining(): Long = (deadline - System.nanoTime()).coerceAtLeast(0)
    return withPasteboardLock(
      timeoutMillis = timeoutMillis,
      onTimeout = { error("Timed out waiting for the simulator pasteboard lock") },
    ) {
      check(remaining() > 0) { "Simulator pasteboard deadline expired before dispatch" }
      val process = startProcess(TrailblazeProcessBuilderUtils.createProcessBuilder(args)
        .redirectErrorStream(false)
        .redirectError(File("/dev/null")))
      val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "simulator-pasteboard-io").apply { isDaemon = true }
      }
      try {
        val output = executor.submit<String> {
          process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val writer = executor.submit<Unit> {
          process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        }
        check(process.waitFor(remaining(), TimeUnit.NANOSECONDS)) {
          "Simulator pasteboard command timed out"
        }
        check(process.exitValue() == 0) {
          "Simulator pasteboard command failed with exit code ${process.exitValue()}"
        }
        writer.get(remaining(), TimeUnit.NANOSECONDS)
        output.get(remaining(), TimeUnit.NANOSECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
      } finally {
        // Kill before closing pipes: a blocked stdin writer owns the stream monitor.
        try {
          awaitPasteboardProcessExit(process)
        } finally {
          executor.shutdownNow()
        }
      }
    }
  }

  /** Bounded cleanup while locked; cancellation cannot skip termination confirmation. */
  private fun awaitPasteboardProcessExit(process: Process) {
    var interrupted = Thread.interrupted()
    try {
      if (process.isAlive) process.destroyForcibly()
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
      while (process.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) break
        try {
          process.waitFor(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
          interrupted = true
        }
      }
    } finally {
      if (process.isAlive) pendingPasteboardProcesses += process
      if (interrupted) Thread.currentThread().interrupt()
    }
  }

  /**
   * Lists the bundle identifiers of the apps installed on the given iOS simulator via
   * `xcrun simctl listapps <deviceId>`. Backs the iOS branch of the cross-platform
   * `mobile_listInstalledApps` tool.
   *
   * Returns an empty list on non-macOS hosts (iOS simulators only exist on a Mac) so callers
   * on Linux/Windows get a benign empty inventory rather than a process-spawn failure — the
   * same shape `IosHostUtils.getInstalledAppIds` has always returned off-Mac.
   */
  fun listInstalledAppIds(deviceId: String): List<String> {
    if (!isMacOs()) return emptyList()
    return parseInstalledAppIdsFromListApps(readSimctl(listOf("listapps", deviceId)).stdout.lines())
  }

  /**
   * Parses bundle identifiers from `xcrun simctl listapps` output lines.
   *
   * The output is a plist-style format where each app's block opens with a header line:
   * ```
   *     "com.example.app" =     {
   * ```
   *
   * Extracts the quoted bundle identifiers from those header lines, filtering out app group
   * identifiers (those starting with `group.`) and blank entries.
   *
   * The value must be the opening brace `{` — i.e. only a block-opening header counts, never an
   * arbitrary `"key" = "value";` line. Without that anchor, nested `GroupContainers` entries (e.g.
   * `"243LU875E5.groups.com.apple.podcasts" = "file://…";`) would be mis-parsed as installed apps.
   * This matches the sibling [IosHostUtils.parseInstalledAppsWithDisplayNames] bundle-id regex.
   *
   * `internal` rather than public: the only legitimate caller is [listInstalledAppIds] in this
   * same object; visibility is widened just enough for the same-module parser unit test.
   */
  internal fun parseInstalledAppIdsFromListApps(outputLines: List<String>): List<String> {
    // Handles variable leading whitespace, a quoted bundle id, and variable spacing around the
    // `=` before the opening brace that starts the app's dictionary block.
    val regex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*\\{")
    return outputLines
      .mapNotNull { line -> regex.matchEntire(line)?.groupValues?.get(1) }
      .filter { it.isNotBlank() && !it.startsWith("group.") }
      .distinct()
  }

  /**
   * Lists the installed apps on the given iOS simulator with structured per-app metadata
   * ([InstalledApp.appId], [InstalledApp.label], [InstalledApp.type], [InstalledApp.version]) via
   * `xcrun simctl listapps <deviceId>`. Backs the iOS branch of `mobile_listInstalledApps` when
   * called with `detailed = true`.
   *
   * iOS gets every field for free: a single `listapps` invocation already reports
   * `ApplicationType`, the display name, and the version, so unlike the Android adb path there's no
   * label/version gap here.
   *
   * Returns an empty list on non-macOS hosts (iOS simulators only exist on a Mac), matching
   * [listInstalledAppIds].
   */
  fun listInstalledAppsDetailed(deviceId: String): List<InstalledApp> {
    if (!isMacOs()) return emptyList()
    return parseInstalledAppsFromListApps(readSimctl(listOf("listapps", deviceId)).stdout.lines())
  }

  /**
   * Parses structured [InstalledApp] metadata from `xcrun simctl listapps` output lines.
   *
   * Each app's block opens with a header line — `"<bundleId>" = {` — under which the relevant
   * fields appear:
   * ```
   *     "com.apple.Preferences" =     {
   *         ApplicationType = System;
   *         CFBundleDisplayName = Settings;
   *         CFBundleName = Settings;
   *         CFBundleShortVersionString = "1.0";
   *         CFBundleVersion = 1;
   *         ...
   *     };
   * ```
   *
   * Mapping:
   * - [InstalledApp.appId] ← the quoted bundle id in the header line
   * - [InstalledApp.isSystemApp] ← `ApplicationType` (`System` → `true`; `User` and anything else /
   *   absent → `false`, so an unrecognized type is never guessed into the system bucket)
   * - [InstalledApp.label] ← `CFBundleDisplayName`, falling back to `CFBundleName`
   * - [InstalledApp.version] ← `CFBundleShortVersionString` (the user-visible version), falling
   *   back to `CFBundleVersion`
   * - [InstalledApp.buildNumber] ← `CFBundleVersion` (the machine build number)
   * - [InstalledApp.installPath] ← `Path` (the `.app` bundle path)
   *
   * Like [parseInstalledAppIdsFromListApps], only the block-opening header (`= {`) is treated as an
   * app, so nested `GroupContainers` entries (`"<id>" = "file://…";`) and `group.*` ids are never
   * mistaken for installed apps. Field lines are only attributed to the most recent app header, and
   * fields are read with last-write-wins (the rare repeated key keeps the latest value).
   *
   * `internal` (widened just enough for the same-module parser unit test): the only production
   * caller is [listInstalledAppsDetailed].
   */
  internal fun parseInstalledAppsFromListApps(outputLines: List<String>): List<InstalledApp> {
    val headerRegex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*\\{")
    // Field lines look like `    Key = Value;` or `    Key = "Value";` — capture the unquoted body.
    // Stops at the first `"` or `;`, so a value containing an escaped quote (`= "a \"b\" c";`) would
    // truncate — not seen in real `simctl listapps` output (display names aren't quote-escaped there),
    // so this is an accepted limitation rather than full plist-string parsing.
    fun fieldRegex(key: String) = Regex("^\\s+$key\\s*=\\s*\"?([^;\"]*)\"?\\s*;")
    val applicationTypeRegex = fieldRegex("ApplicationType")
    val displayNameRegex = fieldRegex("CFBundleDisplayName")
    val bundleNameRegex = fieldRegex("CFBundleName")
    val shortVersionRegex = fieldRegex("CFBundleShortVersionString")
    val bundleVersionRegex = fieldRegex("CFBundleVersion")
    val pathRegex = fieldRegex("Path")

    // Preserve first-seen header order; ignore `group.*` headers like the id-only parser.
    val ordered = LinkedHashMap<String, MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null

    for (line in outputLines) {
      val header = headerRegex.matchEntire(line)?.groupValues?.get(1)
      if (header != null) {
        if (header.isNotBlank() && !header.startsWith("group.")) {
          current = ordered.getOrPut(header) { mutableMapOf() }
        } else {
          current = null
        }
        continue
      }
      val fields = current ?: continue
      applicationTypeRegex.matchEntire(line)?.let { fields["type"] = it.groupValues[1].trim() }
      displayNameRegex.matchEntire(line)?.let { fields["displayName"] = it.groupValues[1].trim() }
      bundleNameRegex.matchEntire(line)?.let { fields["bundleName"] = it.groupValues[1].trim() }
      shortVersionRegex.matchEntire(line)?.let { fields["shortVersion"] = it.groupValues[1].trim() }
      bundleVersionRegex.matchEntire(line)?.let { fields["bundleVersion"] = it.groupValues[1].trim() }
      pathRegex.matchEntire(line)?.let { fields["path"] = it.groupValues[1].trim() }
    }

    return ordered.map { (appId, fields) ->
      val isSystemApp = when (fields["type"]) {
        "System" -> true
        // `User` and the essentially-never non-standard ApplicationType both map to false — we
        // don't guess an unrecognized type into the system bucket.
        else -> false
      }
      val label = (fields["displayName"] ?: fields["bundleName"])?.takeIf { it.isNotBlank() }
      val version = (fields["shortVersion"] ?: fields["bundleVersion"])?.takeIf { it.isNotBlank() }
      val buildNumber = fields["bundleVersion"]?.takeIf { it.isNotBlank() }
      val installPath = fields["path"]?.takeIf { it.isNotBlank() }
      InstalledApp(
        appId = appId,
        isSystemApp = isSystemApp,
        label = label,
        version = version,
        buildNumber = buildNumber,
        installPath = installPath,
      )
    }
  }
}

/**
 * How an installed iOS app shows up in the unified log: [executableName] is its `process`, and
 * [installDir] (`/<container-id>/Wallet.app/`) is the directory every one of its binaries,
 * extensions included, runs from. Null when appinfo reported no `.app` path.
 */
data class IosAppProcess(val executableName: String, val installDir: String?)
