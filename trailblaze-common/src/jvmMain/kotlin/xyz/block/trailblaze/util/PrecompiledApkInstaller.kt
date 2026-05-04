package xyz.block.trailblaze.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import java.nio.file.Files
import java.security.MessageDigest

object PrecompiledApkInstaller {

  /**
   * The resource path to the pre-compiled on-device runner APK.
   * All desktop apps bundle their own APK with this standardized name.
   */
  const val PRECOMPILED_APK_RESOURCE_PATH = "/apks/trailblaze-ondevice-runner.apk"

  /** Device-side path where the APK SHA marker is stored after installation. */
  private const val DEVICE_SHA_MARKER_PATH = "/data/local/tmp/trailblaze-runner-sha.txt"

  /** Cached SHA256 of the bundled APK resource, computed once per process. */
  private val bundledApkSha: String? by lazy { computeBundledApkSha() }

  /**
   * Checks whether the on-device APK matches the bundled APK by comparing SHA256 hashes.
   * Returns true if the installed version is up-to-date, false if a reinstall is needed.
   */
  fun isInstalledApkUpToDate(trailblazeDeviceId: TrailblazeDeviceId): Boolean {
    // If we can't compute the bundled SHA (e.g., resource missing in dev packaging),
    // assume up-to-date to avoid tearing down a working on-device server.
    val expectedSha = bundledApkSha ?: return true
    return try {
      val deviceSha = AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = trailblazeDeviceId,
        args = listOf("cat", DEVICE_SHA_MARKER_PATH),
      ).trim()
      val match = deviceSha == expectedSha
      if (!match) {
        Console.log(
          "APK version mismatch: device=$deviceSha, bundled=$expectedSha — will reinstall."
        )
      }
      match
    } catch (e: Exception) {
      Console.log("Could not read APK SHA marker from device: ${e.message}")
      false
    }
  }

  /**
   * Extracts a pre-compiled APK from resources and installs it on the device.
   * The APK is bundled during the desktop app build process, eliminating the need
   * for runtime Gradle compilation and significantly improving user experience.
   *
   * After successful installation a SHA256 marker is written to the device so that
   * subsequent connections can skip reinstallation when the APK hasn't changed.
   *
   * @param deviceId The device ID to install the APK on
   * @param sendProgressMessage Callback to send progress messages
   * @return true if installation was successful, false otherwise
   */
  suspend fun extractAndInstallPrecompiledApk(
    trailblazeDeviceId: TrailblazeDeviceId,
    sendProgressMessage: (String) -> Unit,
  ): Boolean {
    try {
      sendProgressMessage("Extracting pre-compiled test APK from resources...")

      val tempApkFile = withContext(Dispatchers.IO) {
        // Get the APK from resources
        val apkInputStream = PrecompiledApkInstaller::class.java.getResourceAsStream(PRECOMPILED_APK_RESOURCE_PATH)
          ?: return@withContext null

        // Create a temporary file to store the APK
        val tempFile = Files.createTempFile("trailblaze-test-", ".apk").toFile()
        tempFile.deleteOnExit()

        // Copy the APK from resources to the temp file
        apkInputStream.use { input ->
          tempFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }

        tempFile
      } ?: return run {
        sendProgressMessage("Error: Could not find APK at resource path: $PRECOMPILED_APK_RESOURCE_PATH")
        false
      }

      sendProgressMessage("Installing test APK on device (${tempApkFile.length() / 1024} KB)...")

      // Install the APK using adb
      val installResult = AndroidHostAdbUtils.installApkFile(
        apkFile = tempApkFile,
        trailblazeDeviceId = trailblazeDeviceId
      )

      if (installResult) {
        sendProgressMessage("Test APK installed successfully")
        // Write the SHA marker so future connections can skip reinstallation
        writeShaMarkerToDevice(trailblazeDeviceId)
      } else {
        sendProgressMessage("Failed to install test APK")
      }

      return installResult
    } catch (e: Exception) {
      sendProgressMessage("Error extracting/installing APK: ${e.message}")
      e.printStackTrace()
      return false
    }
  }

  /**
   * Checks if a pre-compiled APK is available for the given target.
   */
  fun hasPrecompiledApk(target: TrailblazeOnDeviceInstrumentationTarget): Boolean {
    return PrecompiledApkInstaller::class.java.getResource(PRECOMPILED_APK_RESOURCE_PATH) != null
  }

  private fun computeBundledApkSha(): String? = try {
    PrecompiledApkInstaller::class.java.getResourceAsStream(PRECOMPILED_APK_RESOURCE_PATH)
      ?.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
          digest.update(buffer, 0, bytesRead)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      }
  } catch (e: Exception) {
    Console.log("Failed to compute bundled APK SHA: ${e.message}")
    null
  }

  private fun writeShaMarkerToDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    val sha = bundledApkSha ?: return
    try {
      AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = trailblazeDeviceId,
        args = listOf("sh", "-c", "echo '$sha' > $DEVICE_SHA_MARKER_PATH"),
      )
    } catch (e: Exception) {
      Console.log("Failed to write APK SHA marker to device: ${e.message}")
    }
  }
}
