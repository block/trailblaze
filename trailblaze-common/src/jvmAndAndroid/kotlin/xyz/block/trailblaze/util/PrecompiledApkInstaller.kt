package xyz.block.trailblaze.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import java.nio.file.Files

object PrecompiledApkInstaller {

  /**
   * The resource path to the pre-compiled on-device runner APK.
   * All desktop apps bundle their own APK with this standardized name.
   */
  const val PRECOMPILED_APK_RESOURCE_PATH = "/apks/trailblaze-ondevice-runner.apk"

  /**
   * Extracts a pre-compiled APK from resources and installs it on the device.
   * The APK is bundled during the desktop app build process, eliminating the need
   * for runtime Gradle compilation and significantly improving user experience.
   *
   * @param resourcePath The path to the APK resource
   * @param deviceId The device ID to install the APK on
   * @param sendProgressMessage Callback to send progress messages
   * @return true if installation was successful, false otherwise
   */
  suspend fun extractAndInstallPrecompiledApk(
    resourcePath: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    sendProgressMessage: (String) -> Unit,
  ): Boolean {
    try {
      sendProgressMessage("Extracting pre-compiled test APK from resources...")

      val tempApkFile = withContext(Dispatchers.IO) {
        // Get the APK from resources
        val apkInputStream = PrecompiledApkInstaller::class.java.getResourceAsStream(resourcePath)
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
        sendProgressMessage("Error: Could not find APK at resource path: $resourcePath")
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
}
