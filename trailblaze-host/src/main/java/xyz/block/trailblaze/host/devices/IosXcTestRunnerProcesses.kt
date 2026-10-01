package xyz.block.trailblaze.host.devices

import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence
import xyz.block.trailblaze.util.Console

/**
 * Finds and stops the `xcodebuild test-without-building` processes that host Maestro's XCTest
 * runner on an iOS device.
 *
 * Maestro's `LocalXCTestInstaller` spawns one per driver and only stops it when the driver is
 * closed with `reinstallDriver` set, so a daemon that exits, or a driver that is simply dropped,
 * leaves it running. An orphan is not idle: once its runner is killed (every fresh driver clears the
 * XCTest port first) xcodebuild reports "Restarting after unexpected exit, crash, or test timeout"
 * and relaunches the runner bundle, and launching that bundle terminates the runner the new driver
 * just started. Each crash also starts a `simctl diagnose` under the xcodebuild, which is why a
 * runner host is always stopped together with its descendants.
 */
internal object IosXcTestRunnerProcesses {

  /**
   * Prefix of the `-derivedDataPath` directory every runner Maestro starts (`XCRunnerCLIUtils`
   * creates it as a temp directory), so an `xcodebuild` someone runs by hand against the same
   * simulator is never taken for one.
   */
  private const val MAESTRO_DERIVED_DATA_MARKER = "maestro_xctestrunner_xcodebuild_output"

  private const val GRACEFUL_STOP_MS = 2_000L

  /**
   * True iff [argv] is Maestro's XCTest runner host, for device [udid] or, when [udid] is null, for
   * any device.
   */
  fun isRunnerHost(argv: List<String>, udid: String?): Boolean {
    val executable = argv.firstOrNull() ?: return false
    if (executable != "xcodebuild" && !executable.endsWith("/xcodebuild")) return false
    if ("test-without-building" !in argv) return false
    val derivedData = valueOf(argv, "-derivedDataPath") ?: return false
    if (!derivedData.trimEnd('/').substringAfterLast('/').startsWith(MAESTRO_DERIVED_DATA_MARKER)) return false
    if (udid == null) return true
    val destination = valueOf(argv, "-destination") ?: return false
    return destination.split(",").any { it.trim() == "id=$udid" }
  }

  private fun valueOf(argv: List<String>, flag: String): String? =
    argv.zipWithNext().firstOrNull { (name, _) -> name == flag }?.second

  /**
   * Stops every runner host for [udid], whoever started it, except [owner]'s own when [spareOwn].
   * Called before building a driver for [udid]: a runner host left alive would kill the runner about
   * to be started. The exception is a rebuild that reattaches: Maestro's installer reuses a runner
   * that still answers instead of starting one, so [owner]'s host for it is not stale.
   *
   * @return the pids of the runner hosts that were stopped.
   */
  fun stopRunnerHostsFor(
    udid: String,
    spareOwn: Boolean = false,
    owner: ProcessHandle = ProcessHandle.current(),
    processes: () -> Sequence<ProcessHandle> = { ProcessHandle.allProcesses().asSequence() },
    argvOf: (ProcessHandle) -> List<String>? = ::argvOf,
  ): List<Long> {
    val spared = if (spareOwn) owner.descendants().asSequence().map { it.pid() }.toSet() else emptySet()
    return stopTrees(
      processes().filter { it.pid() !in spared && argvOf(it)?.let { argv -> isRunnerHost(argv, udid) } == true },
    )
  }

  /**
   * Stops the runner hosts [owner] started, for every device. Called as this JVM exits, and limited
   * to its own descendants so it can never stop a runner another Trailblaze process is using.
   */
  fun stopRunnerHostsStartedBy(
    owner: ProcessHandle = ProcessHandle.current(),
    argvOf: (ProcessHandle) -> List<String>? = ::argvOf,
  ): List<Long> =
    stopTrees(owner.descendants().asSequence().filter { argvOf(it)?.let { argv -> isRunnerHost(argv, null) } == true })

  private fun stopTrees(roots: Sequence<ProcessHandle>): List<Long> {
    // Descendants are collected before anything is signalled: once a root exits, its children are
    // re-parented to launchd and no walk from the root can find them again.
    val trees = roots.toList().map { root -> listOf(root) + root.descendants().asSequence().toList() }
    if (trees.isEmpty()) return emptyList()
    val all = trees.flatten()
    all.forEach { it.destroy() }
    val deadline = System.currentTimeMillis() + GRACEFUL_STOP_MS
    all.forEach { process ->
      val remaining = deadline - System.currentTimeMillis()
      if (remaining > 0) runCatching { process.onExit().get(remaining, TimeUnit.MILLISECONDS) }
      if (process.isAlive) process.destroyForcibly()
    }
    val stopped = trees.map { it.first().pid() }
    Console.log("Stopped XCTest runner host(s) ${stopped.joinToString()} and their child processes")
    return stopped
  }

  /** [process]'s argv, or null when the OS won't say (another user's process, or already gone). */
  private fun argvOf(process: ProcessHandle): List<String>? {
    val info = process.info()
    val command = info.command().orElse(null) ?: return null
    val arguments = info.arguments().orElse(null) ?: return null
    return listOf(command) + arguments
  }
}
