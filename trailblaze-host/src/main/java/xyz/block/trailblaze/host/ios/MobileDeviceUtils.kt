package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcessWithTimeout
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import xyz.block.trailblaze.util.SimctlCommand

object MobileDeviceUtils {

  /**
   * Bounds `simctl install` of a downloaded app. Generous: GB-plus production bundles have outrun
   * 30s; it only stops a wedged CoreSimulator from hanging the install forever.
   */
  private const val DOWNLOADED_APP_INSTALL_TIMEOUT_SECONDS = 300L

  /** How long extracting an `.app` from an archive may take before it is stopped. */
  private const val UNZIP_TIMEOUT_SECONDS = 300L
  /**
   * Gets version information for an installed app on the specified device.
   *
   * @param trailblazeDeviceId The device to query
   * @param appId The app package name (Android) or bundle identifier (iOS)
   * @param additionalPlistKeys Extra iOS plist keys to read in the same pass (ignored for Android/Web).
   *   Results are available via [AppVersionInfo.additionalPlistData].
   * @return AppVersionInfo with version details, or null if not installed or unsupported platform
   */
  fun getAppVersionInfo(
    trailblazeDeviceId: TrailblazeDeviceId,
    appId: String,
    additionalPlistKeys: List<String> = emptyList(),
  ): AppVersionInfo? {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.getAppVersionInfo(
        deviceId = trailblazeDeviceId,
        packageName = appId,
      )

      TrailblazeDevicePlatform.IOS -> IosHostUtils.getAppVersionInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        appId = appId,
        additionalPlistKeys = additionalPlistKeys,
      )

      TrailblazeDevicePlatform.WEB -> null
      // Compose desktop is the host app itself — it doesn't have an installable app
      // bundle, so version info doesn't apply. Same shape as WEB.
      TrailblazeDevicePlatform.DESKTOP -> null
    }
  }

  fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String> {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.listInstalledPackages(
        deviceId = trailblazeDeviceId
      )

      TrailblazeDevicePlatform.IOS -> IosHostUtils.getInstalledAppIds(
        deviceId = trailblazeDeviceId.instanceId
      )

      TrailblazeDevicePlatform.WEB -> emptyList()
      TrailblazeDevicePlatform.DESKTOP -> emptyList()
    }.toSet()
  }

  /**
   * The full per-app inventory (label / system flag / version) counterpart of [getInstalledAppIds]
   * — same cross-platform dispatch, same "web/desktop have no installable inventory" shape. On the
   * Android host/adb path only `label` is null (dumpsys limitation; see
   * [AndroidHostAdbUtils.listInstalledAppsDetailed]); iOS via `simctl listapps` has everything.
   */
  fun listInstalledAppsDetailed(trailblazeDeviceId: TrailblazeDeviceId): List<xyz.block.trailblaze.device.InstalledApp> {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.listInstalledAppsDetailed(
        deviceId = trailblazeDeviceId,
      )

      TrailblazeDevicePlatform.IOS -> IosHostSimctlUtils.listInstalledAppsDetailed(
        deviceId = trailblazeDeviceId.instanceId,
      )

      TrailblazeDevicePlatform.WEB -> emptyList()
      TrailblazeDevicePlatform.DESKTOP -> emptyList()
    }
  }

  /**
   * Resolves the app id this [target] has installed on [trailblazeDeviceId] — picks the first
   * entry from `target.getPossibleAppIdsForPlatform(deviceId.platform)` that's actually present
   * on the device. Cross-platform via [getInstalledAppIds]: iOS routes through `simctl listapps`,
   * Android through `pm list packages`.
   *
   * Why this exists: a target may declare multiple app ids (e.g. a primary build + a fallback
   * variant), and which one is "current" depends on the device — different simulators or
   * emulators may have different builds installed. Picking the first DECLARED id without
   * consulting the device is wrong for production launch flows; this helper consults the device.
   *
   * **No caching.** Reads installed app ids fresh on each call, because the install state can
   * change between test steps (an earlier step may install the app, a later step may uninstall
   * it) and a stale cache would silently use the wrong value. Nothing maintains a background
   * cache to defer to: `TrailblazeDeviceManager.refreshAppInventory` probes on demand and
   * publishes what it read for reactive UI. If profiling shows `listapps`/`pm list` is a
   * hot-path bottleneck here, route these calls through that shared (coalescing) probe rather
   * than adding a cache in this file.
   *
   * **Runtime YAML targets are supported.** The signature takes the abstract
   * [TrailblazeHostAppTarget], so both buildtime Kotlin `data object` targets and runtime
   * [xyz.block.trailblaze.config.YamlBackedHostAppTarget] instances flow through the same
   * polymorphic `getPossibleAppIdsForPlatform` and `getAppIdIfInstalled` calls — no special
   * casing.
   *
   * Call sites that only have a target id string (e.g. JS/TS-driven flows once tool migration
   * lands) should resolve it to an instance via the canonical registry —
   * `mcpBridge.getAvailableAppTargets().firstOrNull { it.id == id }` or the existing
   * `Iterable<TrailblazeHostAppTarget>.findById(id)` extension — and pass the resolved instance
   * here. That keeps the helper itself stateless and avoids baking a registry into a static
   * utility.
   *
   * Throws [IllegalStateException] when none of the target's declared app ids are installed on
   * the device — the error names the target id, the device, and the declared-vs-installed gap
   * so the oncaller can either install the right build or update the target's declared list.
   */
  fun findInstalledAppIdForTarget(
    target: TrailblazeHostAppTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
  ): String = try {
    target.requireInstalledAppIdForDevice(
      platform = trailblazeDeviceId.trailblazeDevicePlatform,
      installedAppIds = getInstalledAppIds(trailblazeDeviceId),
    )
  } catch (e: IllegalStateException) {
    // Re-throw with the concrete device id appended. Multi-device runs (parallel simulator
    // / emulator shards) need to know *which* device's inventory was wrong to triage — the
    // shared helper has no access to a [TrailblazeDeviceId], so each call site that has
    // one adds its own device label here. Preserve the cause so the original diagnostic
    // (declared ids, installed set) stays available in the stack trace.
    throw IllegalStateException(
      "${e.message} (device='${trailblazeDeviceId.instanceId}')",
      e,
    )
  }

  /**
   * Which of [target]'s declared app ids for the device's platform is installed, or null.
   *
   * Lists the device's apps only when the target declares one for this platform. The listing is a
   * device round trip (`simctl listapps` takes about a second), and with nothing to look for the
   * answer is null anyway — which is the default target on every CLI tool call.
   */
  fun installedAppIdForTarget(
    target: TrailblazeHostAppTarget?,
    trailblazeDeviceId: TrailblazeDeviceId,
    listInstalledAppIds: (TrailblazeDeviceId) -> Set<String> = ::getInstalledAppIds,
  ): String? {
    target ?: return null
    val platform = trailblazeDeviceId.trailblazeDevicePlatform
    if (target.getPossibleAppIdsForPlatform(platform).isNullOrEmpty()) return null
    return target.getAppIdIfInstalled(platform, listInstalledAppIds(trailblazeDeviceId))
  }

  /**
   * Best-effort snapshot of the app under test for the session-start log: resolves which of
   * [target]'s declared app ids is installed on [trailblazeDeviceId] (unless the caller already
   * resolved one and passes [resolvedAppId]), then probes its version via [getAppVersionInfo].
   *
   * Never throws — a probe failure (unreachable device, app not installed, web/desktop platform)
   * returns null so capturing app info can never block a session from starting.
   */
  fun resolveTargetAppInfo(
    target: TrailblazeHostAppTarget?,
    trailblazeDeviceId: TrailblazeDeviceId,
    resolvedAppId: String? = null,
    listInstalledAppIds: (TrailblazeDeviceId) -> Set<String> = ::getInstalledAppIds,
  ): TrailblazeTargetAppInfo? {
    val appId = resolvedAppId ?: runCatching {
      installedAppIdForTarget(target, trailblazeDeviceId, listInstalledAppIds)
    }.getOrNull() ?: return null
    val version = runCatching { getAppVersionInfo(trailblazeDeviceId, appId) }.getOrNull()
    return TrailblazeTargetAppInfo(
      appId = appId,
      versionName = version?.versionName,
      versionCode = version?.versionCode,
      buildNumber = version?.buildNumber,
    )
  }

  /**
   * Returns whether the apps were stopped, so a caller can tell a real stop from a no-op: on Android,
   * at least one installed app was force-stopped; on iOS, every candidate is confirmed not running.
   */
  fun ensureAppsAreForceStopped(
    possibleAppIds: Set<String>,
    trailblazeDeviceId: TrailblazeDeviceId,
  ): Boolean {
    val installedAppIds = getInstalledAppIds(trailblazeDeviceId)
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> {
        val stoppedAppIds = installedAppIds.filter { installedAppId -> possibleAppIds.any { installedAppId == it } }
        stoppedAppIds.forEach { appId ->
          AndroidHostAdbUtils.forceStopApp(
            deviceId = trailblazeDeviceId,
            appId = appId,
          )
        }
        stoppedAppIds.isNotEmpty()
      }

      TrailblazeDevicePlatform.IOS -> {
        // Every candidate must be confirmed not running; a failed terminate leaves the restart owed.
        val confirmed = possibleAppIds.map { appId ->
          IosHostUtils.killAppOnSimulator(
            deviceId = trailblazeDeviceId.instanceId,
            appId = appId,
          )
        }
        confirmed.isNotEmpty() && confirmed.all { it }
      }

      TrailblazeDevicePlatform.WEB -> {
        // Currently nothing to do here
        false
      }

      TrailblazeDevicePlatform.DESKTOP -> {
        // Compose desktop driver doesn't manage app processes — the desktop *is* the
        // app. No-op for parity with WEB.
        false
      }
    }
  }

  data class InstallResult(
    val success: Boolean,
    val message: String,
  )

  /**
   * Downloads the app at [appUrl] (an `.apk` for Android, a `.zip` holding an `.app` for an iOS
   * simulator) and installs it on [trailblazeDeviceId] with [install], by default [installAppFile],
   * which works only on this machine's own devices.
   */
  suspend fun installFromUrl(
    trailblazeDeviceId: TrailblazeDeviceId,
    appUrl: String,
    install: suspend (TrailblazeDeviceId, File) -> InstallResult = ::installAppFile,
  ): InstallResult {
    val platform = trailblazeDeviceId.trailblazeDevicePlatform
    unsupportedInstallReason(platform)?.let { return InstallResult(success = false, message = it) }
    val download = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> downloadToTempFile(appUrl, suffix = ".apk")
      else -> downloadWithCurl(appUrl, suffix = ".zip").getOrElse {
        return InstallResult(success = false, message = "Failed to download iOS app: ${it.message}")
      }
    }
    return try {
      install(trailblazeDeviceId, download)
    } finally {
      download.delete()
    }
  }

  /**
   * Installs the app in [appFile] on [trailblazeDeviceId], a device attached to this machine: an
   * `.apk` on Android; on an iOS simulator an `.app` bundle, or a `.zip` holding one.
   */
  suspend fun installAppFile(
    trailblazeDeviceId: TrailblazeDeviceId,
    appFile: File,
  ): InstallResult {
    val platform = trailblazeDeviceId.trailblazeDevicePlatform
    installableAppProblem(platform, appFile)?.let { return InstallResult(success = false, message = it) }
    val instanceId = trailblazeDeviceId.instanceId
    if (platform == TrailblazeDevicePlatform.ANDROID) {
      val installed = withContext(Dispatchers.IO) { AndroidHostAdbUtils.installApkFile(appFile, trailblazeDeviceId) }
      return if (installed) {
        InstallResult(true, "APK installed successfully on $instanceId.")
      } else {
        InstallResult(false, "APK installation failed on $instanceId.")
      }
    }
    if (appFile.isDirectory) return simctlInstall(trailblazeDeviceId, appFile)
    val extractDir = withContext(Dispatchers.IO) { Files.createTempDirectory("trailblaze-ios-app-").toFile() }
    return try {
      // System unzip preserves the permissions and symlinks an .app bundle needs. The archive may
      // come from a borrower, so unzip never asks a question (-n: keep the first of two entries
      // with one name; no input to read) and is stopped if it runs too long.
      val (unzipResult, timedOut) = TrailblazeProcessBuilderUtils
        .createProcessBuilder(listOf("unzip", "-n", "-q", appFile.absolutePath, "-d", extractDir.absolutePath))
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .runProcessWithTimeout(timeoutSeconds = UNZIP_TIMEOUT_SECONDS)
      if (timedOut) {
        return InstallResult(false, "Extracting ${appFile.name} took longer than ${UNZIP_TIMEOUT_SECONDS}s; gave up.")
      }
      if (unzipResult.exitCode != 0) {
        return InstallResult(false, "Failed to extract iOS app archive: ${unzipResult.fullOutput}")
      }
      val appBundle = withContext(Dispatchers.IO) { findAppBundle(extractDir) }
        ?: return InstallResult(false, "No .app bundle found in ${appFile.name}.")
      simctlInstall(trailblazeDeviceId, appBundle)
    } finally {
      // Never through a symlink: the archive may come from a borrower, and a link in it could point
      // anywhere on this machine.
      IosHostSimctlUtils.deleteRecursivelyNoFollowLinks(extractDir.toPath())
    }
  }

  /**
   * Why [appFile] can't be installed on a [platform] device, or null when it is the right kind: an
   * `.apk` for Android; an `.app` bundle, or a `.zip` holding one, for an iOS simulator.
   */
  fun installableAppProblem(platform: TrailblazeDevicePlatform, appFile: File): String? {
    unsupportedInstallReason(platform)?.let { return it }
    return when {
      platform == TrailblazeDevicePlatform.ANDROID ->
        if (appFile.isFile && appFile.name.endsWith(".apk", ignoreCase = true)) null
        else "An Android device installs an .apk file, not ${appFile.name}."
      appFile.isDirectory && appFile.name.endsWith(".app") -> null
      appFile.isFile && appFile.name.endsWith(".zip", ignoreCase = true) -> null
      else -> "An iOS simulator installs an .app bundle, or a .zip holding one, not ${appFile.name}."
    }
  }

  /** Why [platform] can't have an app installed on it, or null when it can. */
  private fun unsupportedInstallReason(platform: TrailblazeDevicePlatform): String? = when (platform) {
    TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> null
    TrailblazeDevicePlatform.WEB -> "Web browser devices do not support app installation."
    TrailblazeDevicePlatform.DESKTOP ->
      "Compose desktop driver does not support app installation — it drives the host app directly."
  }

  private suspend fun simctlInstall(trailblazeDeviceId: TrailblazeDeviceId, appBundle: File): InstallResult {
    val installResult = SimctlCommand.runSuspending(
      listOf("install", trailblazeDeviceId.instanceId, appBundle.absolutePath),
      DOWNLOADED_APP_INSTALL_TIMEOUT_SECONDS,
    )
    return if (installResult.success) {
      InstallResult(true, "iOS app installed successfully on ${trailblazeDeviceId.instanceId}.")
    } else {
      InstallResult(false, "iOS app installation failed: ${installResult.output}")
    }
  }

  /** Downloads with `curl -L` (follows redirects, with timeouts); the failure carries curl's output. */
  private suspend fun downloadWithCurl(appUrl: String, suffix: String): Result<File> {
    val file = withContext(Dispatchers.IO) { Files.createTempFile("trailblaze-app-download-", suffix).toFile() }
    val result = withContext(Dispatchers.IO) {
      TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf("curl", "-L", "--max-time", "300", "--connect-timeout", "30", "-o", file.absolutePath, appUrl),
      ).runProcess {}
    }
    if (result.exitCode == 0) return Result.success(file)
    file.delete()
    return Result.failure(IllegalStateException(result.fullOutput))
  }

  private suspend fun downloadToTempFile(
    appUrl: String,
    suffix: String,
  ): File = withContext(Dispatchers.IO) {
    val tempFile = Files.createTempFile("trailblaze-app-download-", suffix).toFile()
    URL(appUrl).openStream().use { input ->
      FileOutputStream(tempFile).use { output ->
        input.copyTo(output)
      }
    }
    tempFile
  }

  /** The first `.app` under [rootDir], never found through a symlink out of it. */
  private fun findAppBundle(rootDir: File): File? {
    return rootDir
      .walkTopDown()
      .onEnter { !Files.isSymbolicLink(it.toPath()) }
      .firstOrNull { file -> !Files.isSymbolicLink(file.toPath()) && file.isDirectory && file.name.endsWith(".app") }
  }
}
