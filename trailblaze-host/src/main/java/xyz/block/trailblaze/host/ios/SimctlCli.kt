package xyz.block.trailblaze.host.ios

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import xyz.block.trailblaze.util.Console

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

  // 60s default: 30s proved tight for large (GB-plus) production app bundles.
  fun install(udid: String, appBundlePath: String, timeoutSeconds: Long = 60): Result =
    run(listOf("xcrun", "simctl", "install", udid, appBundlePath), timeoutSeconds)

  /** Path of the installed .app bundle — the explicit `app` container arg (also simctl's default). */
  fun getAppContainer(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "get_app_container", udid, bundleId, "app"), timeoutSeconds)

  fun openUrl(udid: String, url: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "openurl", udid, url), timeoutSeconds)

  /**
   * `simctl privacy` — grants/revokes/resets a TCC service for an app without prompting
   * (`action` is `grant`/`revoke`/`reset`; `service` is e.g. `all`, `photos`,
   * `location-always`). What Maestro's pre-launch permission setup uses for location; the AXe
   * driver uses it for every service simctl supports (see [IosSimulatorPermissions]).
   */
  fun privacy(udid: String, action: String, service: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "privacy", udid, action, service, bundleId), timeoutSeconds)

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
    // `terminate` returns as soon as the signal is sent; a still-dying process can flush
    // state back to disk after the wipe. Poll until it's actually gone (Maestro does the same).
    ensureStopped(udid, bundleId)
    val container = getAppContainer(udid, bundleId)
    if (!container.success) return container
    val appPath = Paths.get(container.stdout.trim())
    if (!Files.isDirectory(appPath)) {
      return Result(-1, "", "app bundle not found at $appPath")
    }
    val tmpDir = Files.createTempDirectory("axe-reinstall-")
    val tmpBundle = tmpDir.resolve(appPath.fileName.toString())
    try {
      copyDirectoryPreservingAttributes(appPath, tmpBundle)
      val uninstalled = uninstall(udid, bundleId)
      if (!uninstalled.success) {
        // A timed-out uninstall may have half-removed the app. Best-effort reinstall from the
        // copy we still hold so a failed clearState can't leave the app missing for every
        // subsequent trail in the suite (POC finding 9), then still report the uninstall
        // failure — install over a half-removed app doesn't guarantee a wiped data container.
        val recovered = installWithRetry(udid, tmpBundle.toString())
        if (!recovered.success) {
          // A silent recovery failure leaves the app missing and points triage at whichever
          // later trail first hits the missing-app launch error.
          Console.log(
            "[SimctlCli] best-effort reinstall of $bundleId after failed uninstall also failed: " +
              recovered.stderr.trim(),
          )
        }
        return uninstalled
      }
      return installWithRetry(udid, tmpBundle.toString())
    } finally {
      // Cleanup must never mask the primary Result (or exception) from the try block.
      runCatching { deleteRecursivelyNoFollowLinks(tmpBundle) }
      runCatching { Files.deleteIfExists(tmpDir) }
    }
  }

  /**
   * Symlink-safe recursive delete for the temp bundle. `java.io.File.deleteRecursively()`
   * follows directory symlinks, and [copyDirectoryPreservingAttributes] preserves links — an
   * absolute symlink inside the app bundle would otherwise have its *target's* contents
   * deleted. `kotlin.io.path`'s variant deletes the link itself without following it.
   */
  @OptIn(ExperimentalPathApi::class)
  internal fun deleteRecursivelyNoFollowLinks(path: Path) {
    path.deleteRecursively()
  }

  /**
   * [install] with retry/backoff. A single timed-out install after the uninstall leaves the
   * app missing for every subsequent trail in the suite (POC finding 9: 4 consecutive
   * `NSPOSIXErrorDomain code=2` casualties until a manual reinstall). `simctl install` is
   * idempotent — even if a timed-out attempt actually completed in the background, retrying
   * is safe — and the backoff gives a busy installd time to recover.
   */
  private fun installWithRetry(udid: String, appBundlePath: String): Result {
    var result = install(udid, appBundlePath)
    for (backoffMs in longArrayOf(1_000L, 3_000L)) {
      if (result.success) return result
      Thread.sleep(backoffMs)
      result = install(udid, appBundlePath)
    }
    return result
  }

  /**
   * Polls (up to [timeoutMs]) until the app's process is actually gone, via `launchctl list`
   * inside the simulator. Best-effort: a poll that can't read launchctl, or one that times
   * out, proceeds anyway — matching [terminate]'s best-effort contract.
   */
  fun ensureStopped(udid: String, bundleId: String, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val list = spawn(udid, listOf("launchctl", "list"))
      if (!list.success || !isAppRunning(list.stdout, bundleId)) return
      Thread.sleep(250)
    }
  }

  /**
   * Pure parse of `launchctl list` output (`PID\tStatus\tLabel` rows): the app is running
   * while its `UIKitApplication:<bundleId>[…]` job is listed with a numeric PID — launchctl
   * shows `-` for a loaded-but-not-running job. The `[` terminator keeps `com.example.app`
   * from matching `com.example.app2`.
   */
  internal fun isAppRunning(launchctlList: String, bundleId: String): Boolean =
    launchctlList.lineSequence().any { line ->
      line.contains("UIKitApplication:$bundleId[") &&
        line.substringBefore('\t').trim().toIntOrNull() != null
    }

  /**
   * Recursive copy that preserves POSIX permissions. Kotlin's `File.copyRecursively` drops file
   * modes — and so does `copyToRecursively`'s default copy action — while `simctl install` only
   * repairs the main executable's mode, so embedded app-extension binaries in the reinstalled
   * bundle would lose their execute bit and crash on launch. The custom copy action adds
   * `COPY_ATTRIBUTES`; symlinks are replicated as links (not followed), matching `cp -R`.
   */
  @OptIn(ExperimentalPathApi::class)
  internal fun copyDirectoryPreservingAttributes(source: Path, target: Path) {
    source.copyToRecursively(target, followLinks = false) { src, dst ->
      Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
      CopyActionResult.CONTINUE
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
