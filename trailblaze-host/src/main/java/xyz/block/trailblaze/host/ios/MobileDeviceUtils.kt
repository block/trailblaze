package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
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
   * @return AppVersionInfo with version details, or null if not installed or unsupported platform
   */
  fun getAppVersionInfo(trailblazeDeviceId: TrailblazeDeviceId, appId: String): AppVersionInfo? {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.getAppVersionInfo(
        deviceId = trailblazeDeviceId,
        packageName = appId,
      )

      TrailblazeDevicePlatform.IOS -> IosHostUtils.getAppVersionInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        appId = appId,
      )

      TrailblazeDevicePlatform.WEB -> null
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
    }.toSet()
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
