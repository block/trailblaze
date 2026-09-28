package xyz.block.trailblaze.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the argv that runs `simctl`: the tool's own path when `xcrun` can find it, otherwise
 * `xcrun simctl`.
 *
 * `xcrun simctl` looks the tool up on every call, about 50ms before simctl starts (median 0.32s vs
 * 0.27s for `simctl list devices booted` on a developer Mac). The device scan runs simctl for every
 * command that has to resolve a device, and the iOS tools run it on every launch, terminate and
 * pasteboard write, so the lookup is done once per process instead.
 *
 * The path is looked up again if it stops existing (Xcode moved or deleted). Switching Xcode with
 * `xcode-select` while a daemon runs keeps the old one until the daemon restarts.
 */
object SimctlCommand {

  private val locator = SimctlLocator(lookup = ::findSimctlWithXcrun, isExecutable = { File(it).canExecute() })

  /** `simctl` followed by [args], ready for a `ProcessBuilder`. */
  fun argv(vararg args: String): List<String> = locator.prefix() + args

  /** `simctl` followed by [args], ready for a `ProcessBuilder`. */
  fun argv(args: List<String>): List<String> = locator.prefix() + args

  private fun findSimctlWithXcrun(): String? = try {
    val process = ProcessBuilder("xcrun", "--find", "simctl").redirectErrorStream(false).start()
    process.outputStream.close()
    val finished = process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      null
    } else if (process.exitValue() != 0) {
      null
    } else {
      process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
    }
  } catch (_: Exception) {
    null
  }

  /**
   * `xcrun --find` answers in about 50ms. The two callers with tight budgets (the classifier's
   * screenshot probe and `AxeCli.type`) start their clocks before building argv, so it counts against
   * them; other callers pay it on top of their own timeout, so it is kept short. Past it, `xcrun`
   * itself is stuck and `xcrun simctl` would be too.
   */
  private const val LOOKUP_TIMEOUT_SECONDS = 2L
}

/**
 * The resolution rule behind [SimctlCommand], with the lookup and the file check passed in so it
 * can be exercised without Xcode.
 *
 * A failed lookup is remembered too: on a host without Xcode every call would otherwise pay the
 * failing `xcrun --find` before falling back to an `xcrun simctl` that fails the same way.
 */
internal class SimctlLocator(
  private val lookup: () -> String?,
  private val isExecutable: (String) -> Boolean,
) {
  private sealed interface Resolution {
    data class Found(val path: String) : Resolution
    data object NotFound : Resolution
  }

  @Volatile
  private var resolution: Resolution? = null

  fun prefix(): List<String> = cached(resolution) ?: resolve()

  /** Callers that miss together (the parallel device scan) share one lookup instead of each spawning `xcrun`. */
  @Synchronized
  private fun resolve(): List<String> {
    val seen = resolution
    cached(seen)?.let { return it }
    // Only a vanished path gets here with a resolution; the lookup answers again for it.
    val found = lookup()?.takeIf { File(it).isAbsolute && isExecutable(it) }
    resolution = found?.let { Resolution.Found(it) } ?: Resolution.NotFound
    return found?.let { listOf(it) } ?: XCRUN_SIMCTL
  }

  private fun cached(current: Resolution?): List<String>? = when {
    current is Resolution.Found && isExecutable(current.path) -> listOf(current.path)
    current == Resolution.NotFound -> XCRUN_SIMCTL
    else -> null
  }

  companion object {
    val XCRUN_SIMCTL: List<String> = listOf("xcrun", "simctl")
  }
}
