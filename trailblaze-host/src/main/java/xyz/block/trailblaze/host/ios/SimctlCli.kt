package xyz.block.trailblaze.host.ios

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around `xcrun simctl` for iOS Simulator app-lifecycle operations that AXe
 * intentionally does not cover (launch, terminate, install, uninstall, openurl).
 *
 * Maestro uses the same tool internally on iOS — we just call it directly from the AXe
 * driver path.
 */
object SimctlCli {

  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
  }

  fun launch(udid: String, bundleId: String, timeoutSeconds: Long = 15): Result =
    run(listOf("xcrun", "simctl", "launch", udid, bundleId), timeoutSeconds)

  fun terminate(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "terminate", udid, bundleId), timeoutSeconds)

  fun uninstall(udid: String, bundleId: String, timeoutSeconds: Long = 20): Result =
    run(listOf("xcrun", "simctl", "uninstall", udid, bundleId), timeoutSeconds)

  fun install(udid: String, appBundlePath: String, timeoutSeconds: Long = 30): Result =
    run(listOf("xcrun", "simctl", "install", udid, appBundlePath), timeoutSeconds)

  /** Path of the installed .app bundle — the explicit `app` container arg (also simctl's default). */
  fun getAppContainer(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "get_app_container", udid, bundleId, "app"), timeoutSeconds)

  fun openUrl(udid: String, url: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "openurl", udid, url), timeoutSeconds)

  /** Runs an arbitrary command inside the simulator via `simctl spawn` (e.g. `defaults write`). */
  fun spawn(udid: String, command: List<String>, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "spawn", udid) + command, timeoutSeconds)

  /**
   * Resets the simulator's keychain — the whole device keychain, same as Maestro's
   * `clearKeychain` on simulators. iOS keychain entries survive app uninstall/reinstall, so
   * [clearAppState] alone can leave an app "signed in" after a wipe; this is the missing half.
   */
  fun keychainReset(udid: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "keychain", udid, "reset"), timeoutSeconds)

  /**
   * Clears app state the way Maestro does on simulators (`LocalSimulatorUtils.clearAppState`):
   * terminate, then reinstall the app from its own installed bundle — reinstalling is the most
   * stable way to wipe the data container, and `get_app_container` recovers the .app path so no
   * external artifact is needed. Returns the first failing step's [Result] so callers can decide
   * severity. Not usable on iOS system apps (the OS prohibits uninstalling them) — callers
   * upstream already downgrade REINSTALL for `com.apple.*` bundle ids.
   */
  fun clearAppState(udid: String, bundleId: String): Result {
    terminate(udid, bundleId) // nonzero when the app isn't running — a fine starting state
    val container = getAppContainer(udid, bundleId)
    if (!container.success) return container
    val appPath = Paths.get(container.stdout.trim())
    if (!Files.isDirectory(appPath)) {
      return Result(-1, "", "app bundle not found at $appPath")
    }
    val tmpDir = Files.createTempDirectory("axe-reinstall-")
    val tmpBundle = tmpDir.resolve(appPath.fileName.toString())
    try {
      appPath.toFile().copyRecursively(tmpBundle.toFile())
      val uninstalled = uninstall(udid, bundleId)
      if (!uninstalled.success) return uninstalled
      return install(udid, tmpBundle.toString())
    } finally {
      tmpBundle.toFile().deleteRecursively()
      Files.deleteIfExists(tmpDir)
    }
  }

  private fun run(args: List<String>, timeoutSeconds: Long): Result {
    val proc = ProcessBuilder(args).redirectErrorStream(false).start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      return Result(-1, "", "simctl timed out after ${timeoutSeconds}s")
    }
    val stdout = proc.inputStream.bufferedReader().readText()
    val stderr = proc.errorStream.bufferedReader().readText()
    return Result(proc.exitValue(), stdout, stderr)
  }
}
