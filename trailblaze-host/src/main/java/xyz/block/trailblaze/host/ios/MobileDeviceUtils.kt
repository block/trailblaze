package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files

object MobileDeviceUtils {
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
   * **No caching.** Reads installed app ids fresh on each call. Two reasons: (1) the install
   * state can change between test steps (an earlier step may install the app, a later step
   * may uninstall it), and a stale cache would silently use the wrong value; (2) the existing
   * UI/MCP path already maintains a `StateFlow`-backed cache via
   * `TrailblazeDeviceManager.getInstalledAppIdsFlow` for reactive UI; tools should not maintain
   * a parallel cache that can drift from it. If profiling later shows `listapps`/`pm list` is
   * a hot-path bottleneck, the right fix is to thread the existing `TrailblazeDeviceManager`
   * cache through to tool execution rather than re-cache here.
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
  ): String {
    val installed = getInstalledAppIds(trailblazeDeviceId)
    val platform = trailblazeDeviceId.trailblazeDevicePlatform
    return target.getAppIdIfInstalled(platform, installed)
      ?: error(
        "Target '${target.id}' declares ${platform.name} app ids " +
          "${target.getPossibleAppIdsForPlatform(platform)} but none are installed on device " +
          "'${trailblazeDeviceId.instanceId}'. Installed: $installed. Either install one of the " +
          "declared ids on the device, or add the actually-installed id to the target's declared list."
      )
  }

  fun ensureAppsAreForceStopped(
    possibleAppIds: Set<String>,
    trailblazeDeviceId: TrailblazeDeviceId,
  ) {
    val installedAppIds = getInstalledAppIds(trailblazeDeviceId)
    when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> {
        installedAppIds
          .filter { installedAppId -> possibleAppIds.any { installedAppId == it } }
          .forEach { appId ->
            AndroidHostAdbUtils.forceStopApp(
              deviceId = trailblazeDeviceId,
              appId = appId,
            )
          }
      }

      TrailblazeDevicePlatform.IOS -> {
        possibleAppIds.forEach { appId ->
          IosHostUtils.killAppOnSimulator(
            deviceId = trailblazeDeviceId.instanceId,
            appId = appId,
          )
        }
      }

      TrailblazeDevicePlatform.WEB -> {
        // Currently nothing to do here
      }

      TrailblazeDevicePlatform.DESKTOP -> {
        // Compose desktop driver doesn't manage app processes — the desktop *is* the
        // app. No-op for parity with WEB.
      }
    }
  }

  data class InstallResult(
    val success: Boolean,
    val message: String,
  )

  suspend fun installFromUrl(
    trailblazeDeviceId: TrailblazeDeviceId,
    appUrl: String,
  ): InstallResult {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> installAndroidApkFromUrl(trailblazeDeviceId, appUrl)
      TrailblazeDevicePlatform.IOS -> installIosSimulatorZipFromUrl(trailblazeDeviceId, appUrl)
      TrailblazeDevicePlatform.WEB -> InstallResult(
        success = false,
        message = "Web browser devices do not support app installation.",
      )

      TrailblazeDevicePlatform.DESKTOP -> InstallResult(
        success = false,
        message = "Compose desktop driver does not support app installation — it drives the host app directly.",
      )
    }
  }

  private suspend fun installAndroidApkFromUrl(
    trailblazeDeviceId: TrailblazeDeviceId,
    appUrl: String,
  ): InstallResult {
    val apkFile = downloadToTempFile(appUrl, suffix = ".apk")
    return try {
      val installed = withContext(Dispatchers.IO) {
        AndroidHostAdbUtils.installApkFile(apkFile, trailblazeDeviceId)
      }
      if (installed) {
        InstallResult(true, "APK installed successfully on ${trailblazeDeviceId.instanceId}.")
      } else {
        InstallResult(false, "APK installation failed on ${trailblazeDeviceId.instanceId}.")
      }
    } finally {
      apkFile.delete()
    }
  }

  private suspend fun installIosSimulatorZipFromUrl(
    trailblazeDeviceId: TrailblazeDeviceId,
    appUrl: String,
  ): InstallResult {
    val tempDir = withContext(Dispatchers.IO) {
      Files.createTempDirectory("trailblaze-ios-app-").toFile()
    }
    val zipFile = File(tempDir, "app.zip")
    val extractDir = File(tempDir, "app")

    return try {
      // Download using curl -L (follows redirects, with timeouts)
      val downloadResult = withContext(Dispatchers.IO) {
        TrailblazeProcessBuilderUtils.createProcessBuilder(
          listOf(
            "curl", "-L",
            "--max-time", "300",
            "--connect-timeout", "30",
            "-o", zipFile.absolutePath,
            appUrl,
          ),
        ).runProcess {}
      }

      if (downloadResult.exitCode != 0) {
        return InstallResult(
          success = false,
          message = "Failed to download iOS app: ${downloadResult.fullOutput}",
        )
      }

      // Extract using system unzip (preserves permissions and symlinks needed for .app bundles)
      withContext(Dispatchers.IO) {
        extractDir.mkdirs()
      }
      val unzipResult = withContext(Dispatchers.IO) {
        TrailblazeProcessBuilderUtils.createProcessBuilder(
          listOf("unzip", "-q", zipFile.absolutePath, "-d", extractDir.absolutePath),
        ).runProcess {}
      }

      if (unzipResult.exitCode != 0) {
        return InstallResult(
          success = false,
          message = "Failed to extract iOS app archive: ${unzipResult.fullOutput}",
        )
      }

      val appBundle = withContext(Dispatchers.IO) {
        findAppBundle(extractDir)
      }
        ?: return InstallResult(
          success = false,
          message = "No .app bundle found in the downloaded archive.",
        )

      val installResult = withContext(Dispatchers.IO) {
        TrailblazeProcessBuilderUtils.createProcessBuilder(
          listOf(
            "xcrun",
            "simctl",
            "install",
            trailblazeDeviceId.instanceId,
            appBundle.absolutePath,
          ),
        ).runProcess {}
      }

      if (installResult.exitCode == 0) {
        InstallResult(true, "iOS app installed successfully on ${trailblazeDeviceId.instanceId}.")
      } else {
        InstallResult(
          false,
          "iOS app installation failed: ${installResult.fullOutput}",
        )
      }
    } finally {
      tempDir.deleteRecursively()
    }
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

  private fun findAppBundle(rootDir: File): File? {
    return rootDir
      .walkTopDown()
      .firstOrNull { file -> file.isDirectory && file.name.endsWith(".app") }
  }
}
