package xyz.block.trailblaze.ios.tools

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.SimctlCommand

/** The host processes behind [IosSimctl]: `simctl` and a few fixed Xcode tools. Faked in tests. */
interface IosSimctlRunner {
  /** `simctl` with [args]. [environment] is added to the daemon's; [stdin] is written to it. */
  suspend fun simctl(
    args: List<String>,
    timeoutSeconds: Long,
    environment: Map<String, String> = emptyMap(),
    stdin: String? = null,
    maxOutputBytes: Int? = null,
  ): SimctlCommand.Result

  /** A fixed host tool beside simctl (`PlistBuddy`, `plutil`, `segedit`, `codesign`, `swiftc`). */
  suspend fun host(command: List<String>, timeoutSeconds: Long): SimctlCommand.Result

  object Default : IosSimctlRunner {
    override suspend fun simctl(
      args: List<String>,
      timeoutSeconds: Long,
      environment: Map<String, String>,
      stdin: String?,
      maxOutputBytes: Int?,
    ): SimctlCommand.Result = SimctlCommand.runSuspending(args, timeoutSeconds, stdin, environment, maxOutputBytes)

    override suspend fun host(command: List<String>, timeoutSeconds: Long): SimctlCommand.Result =
      SimctlCommand.runCommandSuspending({ command }, timeoutSeconds)
  }
}

/**
 * `ios_simctl` and `ios_keychain` on one simulator ([udid]): argv only, never a shell
 * string, each argv checked by [IosSimctlArgv] first, and every process killed after its timeout.
 *
 * Throws [IllegalArgumentException] for a refused or malformed argv, and [IllegalStateException] when
 * a step it needs fails (resolving a container, building the keychain helper). The command itself
 * returns its exit code and output; it is never turned into an exception.
 */
class IosSimctl(
  /** The session's simulator: every command runs on it. */
  val udid: String,
  private val runner: IosSimctlRunner = IosSimctlRunner.Default,
  private val helperCacheRoot: Path = defaultHelperCacheRoot(),
  private val hostArch: String = hostSimulatorArch(),
  /** The simulator's data directory; null asks simctl for it (honoring a custom device set). */
  private val deviceDataRoot: Path? = null,
  private val log: (String) -> Unit = { Console.log(it) },
) {

  init {
    require(udid.isNotBlank()) { "No simulator id on the device context." }
  }

  /**
   * Runs one `ios_simctl` argv after [IosSimctlArgv] has checked it, and returns how it exited: a
   * non-zero exit or a timeout is a result, not an exception. [launchEnvironment] must be empty for
   * now ([IosSimctlArgv.LAUNCH_ENVIRONMENT_REFUSED]); [stdin] is only for `push` and `pbcopy`.
   */
  suspend fun run(
    argv: List<String>,
    launchEnvironment: Map<String, String>,
    timeoutSeconds: Long,
    stdin: String? = null,
  ): IosSimctlResult = when (val command = IosSimctlArgv.parse(argv, udid, launchEnvironment, stdin)) {
    is IosSimctlArgv.Command.Plain -> {
      val what = command.args.first()
      if (what in PASTEBOARD_COMMANDS) {
        underPasteboardLock(what, timeoutSeconds) { remaining ->
          simctl(what, command.args, remaining, stdin = command.stdin, failOnTimeout = false).toResult()
        }
      } else {
        simctl(what, command.args, timeoutSeconds, stdin = command.stdin, failOnTimeout = false).toResult()
      }
    }
    is IosSimctlArgv.Command.Terminate -> terminate(command.appId, timeoutSeconds)
    is IosSimctlArgv.Command.Defaults -> defaults(command, timeoutSeconds)
    is IosSimctlArgv.Command.LogShow -> {
      // The whole capture, uncut: callers filter it, and a tail cut would drop an early match.
      val result = simctl("log show", command.args, timeoutSeconds, maxOutputBytes = IosSimctlArgv.MAX_LOG_BYTES, failOnTimeout = false)
      check(!result.outputCapped) {
        "log show printed more than ${IosSimctlArgv.MAX_LOG_BYTES} bytes and was stopped; narrow --predicate or --last."
      }
      result.toResult()
    }
  }

  /**
   * `simctl terminate`, then waits for the process to exit, so a launch right after starts a new one.
   * An app that isn't running is already in the state the caller wanted, so that exits 0 too.
   */
  private suspend fun terminate(appId: String, timeoutSeconds: Long): IosSimctlResult {
    val result = simctl("terminate $appId", listOf("terminate", udid, appId), timeoutSeconds, failOnTimeout = false)
    if (!result.success) {
      if (IosHostSimctlUtils.isNotRunningTerminateOutput(result.output)) return IosSimctlResult(0, "$appId was not running.", "", false)
      return result.toResult()
    }
    val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
    while (true) {
      val list = simctl("spawn launchctl list", listOf("spawn", udid, "launchctl", "list"), timeoutSeconds)
      check(list.success) { "simctl spawn launchctl list failed (exit ${list.exitCode}): ${list.output}" }
      if (!IosHostSimctlUtils.isAppRunning(list.stdout.lines(), appId)) return IosSimctlResult(0, "Terminated $appId.", "", false)
      if (System.nanoTime() >= deadline) {
        return IosSimctlResult(-1, "", "$appId was still running ${timeoutSeconds}s after terminate", timedOut = true)
      }
      delay(EXIT_POLL_MILLIS)
    }
  }

  /**
   * `defaults` through the simulator's own `defaults`, so its cfprefsd (which owns the plist) stays
   * in step. A bundle-id domain is rewritten to the app's container plist path, because a domain
   * name can't reach a sandboxed app's preferences. A path domain must resolve inside this
   * simulator's app containers; otherwise `defaults write /any/path` would write a plist anywhere.
   */
  private suspend fun defaults(command: IosSimctlArgv.Command.Defaults, timeoutSeconds: Long): IosSimctlResult {
    val domainArg = when (val domain = command.domain) {
      IosSimctlArgv.DefaultsDomain.Global -> "-g"
      IosSimctlArgv.DefaultsDomain.SchemeApproval -> "com.apple.launchservices.schemeapproval"
      // Checked too: the app controls its container, so Library/Preferences may be a symlink out.
      is IosSimctlArgv.DefaultsDomain.App ->
        "${appContainer(domain.appId, "data", timeoutSeconds)}/Library/Preferences/${domain.appId}"
          .also { requireInsideAppContainers(it) }
      is IosSimctlArgv.DefaultsDomain.Path -> domain.path.also { requireInsideAppContainers(it) }
    }
    val args = listOf("spawn", udid, "defaults", command.verb, domainArg) + command.rest
    return simctl("defaults ${command.verb}", args, timeoutSeconds, failOnTimeout = false).toResult()
  }

  /**
   * Refuses [path] unless it, and the `.plist` file `defaults` actually opens for it, resolve
   * inside this simulator's app data or app group containers, after following symlinks and `..`.
   */
  internal suspend fun requireInsideAppContainers(path: String) {
    val dataRoot = dataRoot()
    withContext(Dispatchers.IO) { requireInside(dataRoot, path) }
  }

  private fun requireInside(dataRoot: Path, path: String) {
    val roots = APP_CONTAINER_DIRS.map { dataRoot.resolve(it) }.filter { Files.isDirectory(it) }.map { it.toRealPath() }
    val candidates = listOf(path, if (path.endsWith(".plist")) path else "$path.plist")
    for (candidate in candidates) {
      val real = realPath(Paths.get(candidate))
      require(roots.any { root -> real != root && real.startsWith(root) }) {
        "defaults path '$path' is outside this simulator's app containers; use the app's bundle id as the domain."
      }
    }
  }

  private var resolvedDataRoot: Path? = deviceDataRoot

  /**
   * The simulator's data directory, from its own `HOME`, so a custom device set (`simctl --set`, or
   * a farm's own location) resolves where CoreSimulator actually keeps it.
   */
  private suspend fun dataRoot(): Path = resolvedDataRoot ?: run {
    val result = simctl("getenv HOME", listOf("getenv", udid, "HOME"), DATA_ROOT_TIMEOUT_SECONDS)
    val home = result.stdout.trim()
    check(result.success && home.startsWith("/")) { "simctl getenv HOME printed no path: ${result.output}" }
    val path = withContext(Dispatchers.IO) { Paths.get(home).toRealPath() }
    check(withContext(Dispatchers.IO) { Files.isDirectory(path) }) { "The simulator's data directory $path does not exist." }
    path.also { resolvedDataRoot = it }
  }

  /** `simctl get_app_container`: the absolute path of [appId]'s [container] (`app` or `data`). */
  private suspend fun appContainer(appId: String, container: String, timeoutSeconds: Long): String {
    requireAppId(appId)
    val result = simctl("get_app_container $appId", listOf("get_app_container", udid, appId, container), timeoutSeconds)
    if (!result.success) {
      check(!IosHostSimctlUtils.isNotInstalledContainerOutput(result.output)) { "$appId is not installed." }
      error("simctl get_app_container $appId failed (exit ${result.exitCode}): ${result.output}")
    }
    val path = result.stdout.trim()
    check(path.startsWith("/")) { "simctl get_app_container $appId printed no path: ${result.output}" }
    return path
  }

  /**
   * Runs [action] on [appId]'s keychain group, every query pinned to that group: `set` stores a
   * generic password, `get` reads one, `delete` removes them, `list` names the generic and internet
   * passwords there. Names only come back, except `get`'s value in [IosKeychainOutcome.value], for
   * the caller to put in sensitive memory. `set` updates the item at [service]/[account] in place
   * (adding it when missing, so a failed write never loses the old item) and reads [value] back.
   *
   * The host can't reach the simulator keychain, so a small helper built for the simulator does it
   * from inside with `simctl spawn`. The simulator lets the helper into the app's group because the
   * helper carries that group in its `__TEXT,__entitlements` section, read from the installed app.
   * `simctl spawn` refuses a `codesign --entitlements` signature ("Codesigning issue"); the section
   * plus a plain ad-hoc signature is what the simulator reads. See [keychainHelper] for its cache.
   *
   * [value] reaches the helper only through `SIMCTL_CHILD_` environment forwarding: never argv,
   * never a log line. The helper prints data only on success, and a failure quotes only its stderr,
   * scrubbed of [value], so no value reaches an error.
   */
  suspend fun keychain(
    action: IosKeychainAction,
    appId: String,
    service: String?,
    account: String?,
    value: String?,
    accessible: IosKeychainAccessibility,
    accessGroup: String?,
    timeoutSeconds: Long,
  ): IosKeychainOutcome {
    requireAppId(appId)
    service?.let { requireNoControlChars(it, "service") }
    account?.let { requireNoControlChars(it, "account") }
    val name = action.name.lowercase()
    when (action) {
      IosKeychainAction.SET -> {
        require(!service.isNullOrEmpty() && !account.isNullOrEmpty()) { "keychain set needs a service and an account." }
        require(!value.isNullOrEmpty()) { "keychain set needs a non-empty value." }
      }
      IosKeychainAction.GET -> require(!service.isNullOrEmpty() && !account.isNullOrEmpty()) { "keychain get needs a service and an account." }
      IosKeychainAction.DELETE -> require(!service.isNullOrEmpty()) { "keychain delete needs a service (and optionally an account)." }
      IosKeychainAction.LIST -> Unit
    }
    require(value == null || action == IosKeychainAction.SET) { "value is only for keychain set." }
    val secrets = listOfNotNull(value)
    try {
      val group = accessGroup ?: applicationIdentifier(appContainer(appId, "app", timeoutSeconds), timeoutSeconds)
      require(KEYCHAIN_GROUP.matches(group)) { "Keychain group '$group' must match ${KEYCHAIN_GROUP.pattern}." }
      val helper = keychainHelper(group, timeoutSeconds)
      val result = simctl(
        "spawn keychain helper $name",
        listOf("spawn", udid, helper.toString(), name, group, service.orEmpty(), account.orEmpty(), accessible.helperName),
        timeoutSeconds,
        value?.let { mapOf("$SIMCTL_CHILD_PREFIX$KEYCHAIN_VALUE_ENV" to it) } ?: emptyMap(),
      )
      // stdout is never quoted: on success it may hold get's value.
      check(result.success) { "the keychain helper failed (exit ${result.exitCode}): ${result.stderr.trim()}" }
      val json = result.stdout.trim().lines().lastOrNull().orEmpty()
      val output = try {
        TrailblazeJsonInstance.decodeFromString(KeychainHelperOutput.serializer(), json)
      } catch (e: SerializationException) {
        // The parser quotes its input, which may hold the value.
        throw IllegalStateException("the keychain helper printed unreadable output.")
      }
      return IosKeychainOutcome(found = output.found, items = output.items, value = output.value)
    } catch (e: CancellationException) {
      throw e // A JVM CancellationException is an IllegalStateException; never turn it into a failure.
    } catch (e: IllegalStateException) {
      throw IllegalStateException(redactSensitiveValuesIn(e.message ?: "keychain $name failed", secrets))
    }
  }

  /** The app's `application-identifier` entitlement: its default keychain group. */
  private suspend fun applicationIdentifier(appBundle: String, timeoutSeconds: Long): String {
    val exe = host(
      "PlistBuddy CFBundleExecutable",
      listOf(PLIST_BUDDY, "-c", "Print :CFBundleExecutable", "$appBundle/Info.plist"),
      timeoutSeconds,
    )
    val executable = exe.stdout.trim()
    check(exe.success && executable.isNotEmpty() && '/' !in executable && executable != "..") {
      "$appBundle/Info.plist has no CFBundleExecutable: ${exe.output}"
    }
    val work = withContext(Dispatchers.IO) { Files.createTempDirectory("trailblaze-ios-entitlements") }
    try {
      val entitlements = work.resolve("app.plist")
      // Simulator builds carry their entitlements in a Mach-O section; a signed build in its signature.
      val section = host(
        "segedit",
        listOf("xcrun", "segedit", "$appBundle/$executable", "-extract", "__TEXT", "__entitlements", entitlements.toString()),
        timeoutSeconds,
      )
      if (!section.success || !withContext(Dispatchers.IO) { Files.isRegularFile(entitlements) && Files.size(entitlements) > 0 }) {
        val signature = host("codesign -d --entitlements", listOf("codesign", "-d", "--entitlements", ":-", appBundle), timeoutSeconds)
        check(signature.success && signature.stdout.isNotBlank()) { "$appBundle has no entitlements: ${signature.output}" }
        withContext(Dispatchers.IO) { Files.write(entitlements, signature.stdout.toByteArray()) }
      }
      val id = host(
        "plutil application-identifier",
        listOf("plutil", "-extract", "application-identifier", "raw", "-o", "-", entitlements.toString()),
        timeoutSeconds,
      )
      val group = id.stdout.trim()
      check(id.success && group.isNotEmpty()) { "$appBundle has no application-identifier entitlement: ${id.output}" }
      return group
    } finally {
      deleteQuietly(work)
    }
  }

  /**
   * The cached keychain helper for [group], built and installed atomically if missing, one per
   * helper version, simulator SDK, host architecture and group. It receives the value, so it is only reused from a cache root private to this user, and only while it is
   * this user's own file that nobody else can write; anything else is rebuilt.
   */
  private suspend fun keychainHelper(group: String, timeoutSeconds: Long): Path {
    val dir = helperCacheRoot.resolve("v$KEYCHAIN_HELPER_VERSION").resolve(simulatorSdkVersion(timeoutSeconds))
      .resolve(hostArch).resolve(group)
    val helper = dir.resolve(KEYCHAIN_HELPER_NAME)
    withContext(Dispatchers.IO) { requirePrivateDirectory(helperCacheRoot) }
    if (withContext(Dispatchers.IO) { isOwnPrivateExecutable(helper) }) return helper
    val work = withContext(Dispatchers.IO) {
      Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
      Files.createTempDirectory(dir, "build-").also { work ->
        Files.write(work.resolve("helper.swift"), KEYCHAIN_HELPER_SWIFT.toByteArray())
        Files.write(work.resolve("helper.plist"), helperEntitlementsPlist(group).toByteArray())
      }
    }
    try {
      val built = work.resolve(KEYCHAIN_HELPER_NAME)
      val compile = host(
        "swiftc keychain helper",
        listOf(
          "xcrun", "-sdk", "iphonesimulator", "swiftc", "-O",
          "-target", "$hostArch-apple-ios15.0-simulator",
          work.resolve("helper.swift").toString(), "-o", built.toString(),
          "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__entitlements",
          "-Xlinker", work.resolve("helper.plist").toString(),
        ),
        HELPER_BUILD_TIMEOUT_SECONDS,
      )
      check(compile.success) { "the keychain helper did not build: ${compile.output.lines().takeLast(5).joinToString(" ")}" }
      val sign = host("codesign keychain helper", listOf("codesign", "-f", "-s", "-", built.toString()), HELPER_BUILD_TIMEOUT_SECONDS)
      check(sign.success) { "the keychain helper could not be signed: ${sign.output}" }
      // Atomic, so a launch on another simulator never runs a half-written helper.
      withContext(Dispatchers.IO) {
        Files.setPosixFilePermissions(built, OWNER_ONLY)
        Files.move(built, helper, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
      return helper
    } finally {
      deleteQuietly(work)
    }
  }

  private var sdkVersion: String? = null

  /** The iOS simulator SDK the helper is built against: a new Xcode gets a fresh build. */
  private suspend fun simulatorSdkVersion(timeoutSeconds: Long): String = sdkVersion ?: run {
    val result = host("simulator SDK version", listOf("xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"), timeoutSeconds)
    val version = result.stdout.trim()
    check(result.success && SDK_VERSION.matches(version)) { "xcrun printed no iphonesimulator SDK version: ${result.output}" }
    version.also { sdkVersion = it }
  }

  /**
   * Runs simctl, logging the argv but never [environment] values or [stdin] (the keychain value,
   * pasteboard text). A timeout throws unless [failOnTimeout] is false, when it is the caller's result.
   */
  private suspend fun simctl(
    what: String,
    args: List<String>,
    timeoutSeconds: Long,
    environment: Map<String, String> = emptyMap(),
    stdin: String? = null,
    maxOutputBytes: Int? = null,
    failOnTimeout: Boolean = true,
  ): SimctlCommand.Result {
    val env = if (environment.isEmpty()) "" else " (environment: ${environment.keys.joinToString()})"
    val input = if (stdin == null) "" else " (stdin: ${stdin.length} chars)"
    log("Running simctl ${args.joinToString(" ")}$env$input")
    val result = runner.simctl(args, timeoutSeconds, environment, stdin, maxOutputBytes)
    check(!failOnTimeout || !result.timedOut) { "simctl $what did not finish within ${timeoutSeconds}s" }
    return result
  }

  private fun SimctlCommand.Result.toResult() = IosSimctlResult(exitCode, stdout, stderr, timedOut)

  /**
   * Runs [block] under the host-wide pasteboard lock that `inputText` holds between staging the
   * clipboard and pasting, since a simulator's `pbcopy` can reach every other simulator's pasteboard.
   * Waiting for the lock counts against [timeoutSeconds]; [block] gets what is left. The lock belongs
   * to a thread, so it is held on one IO thread for the whole command. The wait is taken in short
   * slices so cancelling the caller stops it, and once the lock is held, cancelling still cancels
   * [block] (killing its process) before the lock is released.
   */
  private suspend fun underPasteboardLock(
    what: String,
    timeoutSeconds: Long,
    block: suspend (remainingSeconds: Long) -> IosSimctlResult,
  ): IosSimctlResult = withContext(Dispatchers.IO) {
    val job = coroutineContext[Job]!!
    val start = System.nanoTime()
    val deadline = start + timeoutSeconds * 1_000_000_000L
    var result: IosSimctlResult? = null
    while (result == null) {
      ensureActive()
      val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
      result = if (remainingMillis <= 0) {
        IosSimctlResult(-1, "", "$what waited ${timeoutSeconds}s for another clipboard operation to finish", timedOut = true)
      } else {
        IosHostSimctlUtils.withPasteboardLock(remainingMillis.coerceAtMost(LOCK_WAIT_SLICE_MILLIS), onTimeout = { null }) {
          val waitedSeconds = (System.nanoTime() - start) / 1_000_000_000L
          runBlocking(job) { block((timeoutSeconds - waitedSeconds).coerceAtLeast(1)) }
        }
      }
    }
    result
  }

  private suspend fun host(what: String, command: List<String>, timeoutSeconds: Long): SimctlCommand.Result {
    log("Running ${command.joinToString(" ")}")
    val result = runner.host(command, timeoutSeconds)
    check(!result.timedOut) { "$what did not finish within ${timeoutSeconds}s" }
    return result
  }

  companion object {
    /** Bump when [KEYCHAIN_HELPER_SWIFT] changes, so hosts rebuild their cached helper. */
    const val KEYCHAIN_HELPER_VERSION = "4"
    const val KEYCHAIN_HELPER_NAME = "keychain-helper"
    /** The first helper build compiles Swift; after that it is cached. */
    const val HELPER_BUILD_TIMEOUT_SECONDS = 180L

    private const val EXIT_POLL_MILLIS = 250L

    /** Commands that read or write the simulator pasteboard, which `inputText` stages text through. */
    private val PASTEBOARD_COMMANDS = setOf("pbcopy", "pbpaste")

    /** How long one wait for the pasteboard lock lasts before checking whether the caller was cancelled. */
    private const val LOCK_WAIT_SLICE_MILLIS = 100L
    private const val DATA_ROOT_TIMEOUT_SECONDS = 10L
    private const val SIMCTL_CHILD_PREFIX = "SIMCTL_CHILD_"
    private const val KEYCHAIN_VALUE_ENV = "TRAILBLAZE_KEYCHAIN_VALUE"
    private const val PLIST_BUDDY = "/usr/libexec/PlistBuddy"

    /** Under the device's data directory: every app's data container, and the shared app group containers. */
    private val APP_CONTAINER_DIRS = listOf("Containers/Data/Application", "Containers/Shared/AppGroup")

    private val APP_ID = IosSimctlArgv.APP_ID
    private val SDK_VERSION = Regex("^\\d+(\\.\\d+)*$")
    private val KEYCHAIN_GROUP = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    internal fun requireAppId(appId: String) {
      require(APP_ID.matches(appId)) { "App id '$appId' must be a bundle id (${APP_ID.pattern})." }
    }

    private fun requireNoControlChars(value: String, what: String) {
      require(value.none { it.isISOControl() }) { "The $what must not contain control characters." }
    }

    /**
     * [path] as the OS would resolve it: each existing component through its symlinks, `..` after
     * that, so `a/link/..` lands beside the link's target, not beside the link. A dangling symlink
     * is refused, since writing through it would create its target wherever it points.
     */
    internal fun realPath(path: Path): Path {
      require(path.isAbsolute) { "'$path' is not an absolute path." }
      var current: Path = path.root
      for (part in path) {
        current = when (val name = part.toString()) {
          "." -> current
          ".." -> current.parent ?: current
          else -> {
            val next = current.resolve(name)
            when {
              Files.exists(next) -> next.toRealPath()
              Files.isSymbolicLink(next) -> throw IllegalArgumentException("'$path' goes through a dangling symlink.")
              else -> next
            }
          }
        }
      }
      return current
    }

    /**
     * Under the user's own Caches, never a temp directory: another local user who could plant the
     * helper there would receive the keychain value it is handed.
     */
    internal fun defaultHelperCacheRoot(home: String = System.getProperty("user.home")): Path =
      Paths.get(home, "Library", "Caches", "trailblaze", "ios-keychain-helper")

    private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    private val WRITABLE_BY_OTHERS = setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

    private fun currentUser() =
      Paths.get("").fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))

    /**
     * Creates [root] if missing, refuses it if it is a symlink or another user's, and tightens it to
     * 0700, so only this user can put anything below it.
     */
    internal fun requirePrivateDirectory(root: Path) {
      if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(root)
      check(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        "The keychain helper cache $root must be a real directory, not a symlink."
      }
      check(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS) == currentUser()) {
        "The keychain helper cache $root belongs to another user; refusing to use it."
      }
      Files.setPosixFilePermissions(root, OWNER_ONLY)
    }

    /** Whether [file] is this user's own executable, not a symlink, and writable by nobody else. */
    internal fun isOwnPrivateExecutable(file: Path): Boolean =
      Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
        Files.isExecutable(file) &&
        Files.getOwner(file, LinkOption.NOFOLLOW_LINKS) == currentUser() &&
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).none { it in WRITABLE_BY_OTHERS }

    /** The simulator architecture `swiftc -target` wants for this host. */
    internal fun hostSimulatorArch(osArch: String = System.getProperty("os.arch").orEmpty()): String =
      when (osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x86_64"
      }

    internal fun helperEntitlementsPlist(group: String): String {
      val escaped = group.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      return """<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0"><dict>
        |<key>application-identifier</key><string>$escaped</string>
        |<key>keychain-access-groups</key><array><string>$escaped</string></array>
        |</dict></plist>
        |""".trimMargin()
    }

    /**
     * `helper <set|get|delete|list> <group> <service> <account> <accessible>`, an empty service or
     * account matching any, and for `set` the value in `$TRAILBLAZE_KEYCHAIN_VALUE`. Every query is
     * pinned to the group. On success it prints one line, `{"found":…,"items":[…]}` with names only
     * (plus `"value"` for get); a failure prints only to stderr and never data.
     */
    internal val KEYCHAIN_HELPER_SWIFT = """
      |import Foundation
      |import Security
      |let a = CommandLine.arguments
      |func fail(_ message: String, _ code: Int32 = 1) -> Never {
      |  FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
      |  exit(code)
      |}
      |guard a.count == 6 else { fail("usage: keychain-helper <set|get|delete|list> <group> <service> <account> <accessible>", 2) }
      |let (action, group, service, account) = (a[1], a[2], a[3], a[4])
      |let accessibility: [(CFString, String)] = [
      |  (kSecAttrAccessibleWhenUnlocked, "WhenUnlocked"),
      |  (kSecAttrAccessibleWhenUnlockedThisDeviceOnly, "WhenUnlockedThisDeviceOnly"),
      |  (kSecAttrAccessibleAfterFirstUnlock, "AfterFirstUnlock"),
      |  (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, "AfterFirstUnlockThisDeviceOnly"),
      |  (kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, "WhenPasscodeSetThisDeviceOnly"),
      |]
      |// The only output on stdout, and only on success.
      |func emit(found: Bool, items: [[String: String]] = [], value: String? = nil) -> Never {
      |  var out: [String: Any] = ["found": found, "items": items]
      |  if let value = value { out["value"] = value }
      |  let data = try! JSONSerialization.data(withJSONObject: out, options: [.sortedKeys])
      |  print(String(data: data, encoding: .utf8)!)
      |  exit(0)
      |}
      |let classes: [(CFString, String)] = [(kSecClassGenericPassword, "genericPassword"), (kSecClassInternetPassword, "internetPassword")]
      |func serviceKey(_ className: String) -> String {
      |  return (className == "internetPassword" ? kSecAttrServer : kSecAttrService) as String
      |}
      |func query(_ itemClass: CFString, _ className: String, synchronizable: Bool = true) -> [String: Any] {
      |  var q: [String: Any] = [
      |    kSecClass as String: itemClass,
      |    kSecAttrAccessGroup as String: group,
      |    kSecUseDataProtectionKeychain as String: true,
      |  ]
      |  if synchronizable { q[kSecAttrSynchronizable as String] = kSecAttrSynchronizableAny }
      |  if !service.isEmpty { q[serviceKey(className)] = service }
      |  if !account.isEmpty { q[kSecAttrAccount as String] = account }
      |  return q
      |}
      |func names(_ itemClass: CFString, _ className: String) -> [[String: String]] {
      |  var q = query(itemClass, className)
      |  q[kSecReturnAttributes as String] = true
      |  q[kSecMatchLimit as String] = kSecMatchLimitAll
      |  var out: CFTypeRef?
      |  let status = SecItemCopyMatching(q as CFDictionary, &out)
      |  if status == errSecItemNotFound { return [] }
      |  guard status == errSecSuccess, let rows = out as? [[String: Any]] else { fail("SecItemCopyMatching \(status)") }
      |  return rows.map { row in [
      |    "itemClass": className,
      |    "service": row[serviceKey(className)] as? String ?? "",
      |    "account": row[kSecAttrAccount as String] as? String ?? "",
      |    "accessGroup": row[kSecAttrAccessGroup as String] as? String ?? group,
      |  ] }
      |}
      |switch action {
      |case "list":
      |  let items = classes.flatMap { names(${'$'}0.0, ${'$'}0.1) }
      |  emit(found: !items.isEmpty, items: items)
      |case "get":
      |  guard !service.isEmpty, !account.isEmpty else { fail("get needs a service and an account", 2) }
      |  var q = query(kSecClassGenericPassword, "genericPassword")
      |  q[kSecReturnData as String] = true
      |  q[kSecMatchLimit as String] = kSecMatchLimitOne
      |  var out: CFTypeRef?
      |  let status = SecItemCopyMatching(q as CFDictionary, &out)
      |  if status == errSecItemNotFound { emit(found: false) }
      |  guard status == errSecSuccess, let data = out as? Data else { fail("SecItemCopyMatching \(status)") }
      |  guard let value = String(data: data, encoding: .utf8) else { fail("the item's value is not UTF-8 text") }
      |  emit(found: true, value: value)
      |case "delete":
      |  guard !service.isEmpty else { fail("delete needs a service", 2) }
      |  let items = names(kSecClassGenericPassword, "genericPassword")
      |  let status = SecItemDelete(query(kSecClassGenericPassword, "genericPassword") as CFDictionary)
      |  guard status == errSecSuccess || status == errSecItemNotFound else { fail("SecItemDelete \(status)") }
      |  emit(found: !items.isEmpty, items: items)
      |case "set":
      |  guard !service.isEmpty, !account.isEmpty, let value = ProcessInfo.processInfo.environment["$KEYCHAIN_VALUE_ENV"], !value.isEmpty else {
      |    fail("set needs a service, an account and the value in ${'$'}$KEYCHAIN_VALUE_ENV", 2)
      |  }
      |  guard let accessible = accessibility.first(where: { ${'$'}0.1 == a[5] })?.0 else { fail("unknown accessibility \(a[5])", 2) }
      |  // The item set writes: not synchronizable, so match exactly that one.
      |  let item = query(kSecClassGenericPassword, "genericPassword", synchronizable: false)
      |  // Update in place, add only when missing: a failed replacement must not lose the old item.
      |  let attributes: [String: Any] = [kSecAttrAccessible as String: accessible, kSecValueData as String: Data(value.utf8)]
      |  let updated = SecItemUpdate(item as CFDictionary, attributes as CFDictionary)
      |  if updated == errSecItemNotFound {
      |    let added = SecItemAdd(item.merging(attributes) { ${'$'}1 } as CFDictionary, nil)
      |    guard added == errSecSuccess else { fail("SecItemAdd \(added)") }
      |  } else if updated != errSecSuccess {
      |    fail("SecItemUpdate \(updated)")
      |  }
      |  var read = item
      |  read[kSecReturnData as String] = true
      |  var out: CFTypeRef?
      |  let found = SecItemCopyMatching(read as CFDictionary, &out)
      |  guard found == errSecSuccess, (out as? Data) == Data(value.utf8) else { fail("read back \(found)") }
      |  emit(found: true, items: names(kSecClassGenericPassword, "genericPassword"))
      |default:
      |  fail("unknown action \(action)", 2)
      |}
      |""".trimMargin()

    @OptIn(ExperimentalPathApi::class)
    private suspend fun deleteQuietly(path: Path) {
      withContext(Dispatchers.IO) { runCatching { path.deleteRecursively() } }
    }
  }
}

/** What `ios_keychain` does. */
@Serializable(with = IosKeychainAction.Serializer::class)
enum class IosKeychainAction {
  SET,
  GET,
  DELETE,
  LIST,
  ;

  object Serializer : LenientEnumSerializer<IosKeychainAction>("IosKeychainAction", entries)
}

/** One keychain item, as `ios_keychain` names it: never its value. */
@Serializable
data class IosKeychainItem(
  /** `kSecAttrService`, or `kSecAttrServer` for an internet password. */
  val service: String,
  val account: String,
  val accessGroup: String,
  /** `genericPassword` or `internetPassword`. */
  val itemClass: String,
)

/** What an `ios_keychain` call returns. Never a value: `get` puts it in sensitive memory. */
@Serializable
data class IosKeychainResult(
  val action: IosKeychainAction,
  /** `get`/`list`: whether anything matched. `set`: the item now exists. `delete`: an item existed. */
  val found: Boolean,
  /** `get` only, when found: the memory key now holding the value, for `{{key}}` in later steps. */
  val memoryKey: String? = null,
  /** `list` only: every generic and internet password in the group that matched. */
  val items: List<IosKeychainItem>? = null,
)

/** What [IosSimctl.keychain] found. [value] is `get`'s secret, for sensitive memory only. */
class IosKeychainOutcome(val found: Boolean, val items: List<IosKeychainItem>, val value: String?) {
  override fun toString() = "IosKeychainOutcome(found=$found, items=$items, value=${if (value == null) "null" else "***"})"
}

/** The helper's one line of output. */
@Serializable
internal data class KeychainHelperOutput(
  val found: Boolean = false,
  val items: List<IosKeychainItem> = emptyList(),
  val value: String? = null,
) {
  override fun toString() = "KeychainHelperOutput(found=$found, items=$items, value=${if (value == null) "null" else "***"})"
}

/** `kSecAttrAccessible` for `ios_keychain` set. */
@Serializable(with = IosKeychainAccessibility.Serializer::class)
enum class IosKeychainAccessibility(val helperName: String) {
  WHEN_UNLOCKED("WhenUnlocked"),
  WHEN_UNLOCKED_THIS_DEVICE_ONLY("WhenUnlockedThisDeviceOnly"),
  AFTER_FIRST_UNLOCK("AfterFirstUnlock"),
  AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY("AfterFirstUnlockThisDeviceOnly"),
  WHEN_PASSCODE_SET_THIS_DEVICE_ONLY("WhenPasscodeSetThisDeviceOnly"),
  ;

  object Serializer : LenientEnumSerializer<IosKeychainAccessibility>("IosKeychainAccessibility", entries)
}
