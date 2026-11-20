package xyz.block.trailblaze.host.install

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.ios.MobileDeviceUtils.InstallResult
import xyz.block.trailblaze.host.lease.RemoteDevicePools
import xyz.block.trailblaze.host.lease.RemoteInstallReply
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File
import java.nio.file.Files

/**
 * Installs an app on any device this daemon can use. Its own devices install through this machine's
 * adb or simctl. A borrowed device's app goes to the machine that lent it, which installs it the
 * same way, so a borrower needs neither tool nor a connection to the device.
 */
class DeviceAppInstaller(
  private val isBorrowed: (TrailblazeDeviceId) -> Boolean = { RemoteDevicePools.shared.isRemote(it) },
  /** Sends the file to the borrowed device's lender; throws, with a message for the user, on failure. */
  private val installOnLender: (TrailblazeDeviceId, File) -> RemoteInstallReply = { device, file -> RemoteDevicePools.shared.install(device, file) },
  private val installHere: suspend (TrailblazeDeviceId, File) -> InstallResult = MobileDeviceUtils::installAppFile,
  /**
   * Runs an install on this machine's own device as a use of it: refused, naming the borrower, while
   * a borrower is using it, and busy to borrowers until it is done.
   */
  private val inUse: suspend (TrailblazeDeviceId, suspend () -> InstallResult) -> Result<InstallResult> =
    { device, install -> RemoteDevicePools.shared.whileConnecting(device, install) },
) {

  /** Installs [appFile]: an `.apk` on Android; an `.app` bundle, or a `.zip` holding one, on an iOS simulator. */
  suspend fun install(device: TrailblazeDeviceId, appFile: File): InstallResult {
    if (!isBorrowed(device)) {
      return inUse(device) { installHere(device, appFile) }
        .getOrElse { InstallResult(false, it.message ?: "${device.toFullyQualifiedDeviceId()} can't be used right now") }
    }
    // Checked here too, so a wrong file is refused before it is uploaded.
    MobileDeviceUtils.installableAppProblem(device.trailblazeDevicePlatform, appFile)?.let { return InstallResult(false, it) }
    return withContext(Dispatchers.IO) {
      // The lender takes one file, so an .app bundle travels zipped; it unzips it as for a download.
      val upload = if (!appFile.isDirectory) {
        appFile
      } else {
        runCatching { zipAppBundle(appFile) }.getOrElse { return@withContext InstallResult(false, it.message ?: "Could not zip ${appFile.name}") }
      }
      try {
        val reply = installOnLender(device, upload)
        InstallResult(reply.success, reply.message)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Any failure reaches the user as a result, never as a bare server error.
        InstallResult(false, e.message ?: "install on ${device.toFullyQualifiedDeviceId()} failed")
      } finally {
        if (upload != appFile) upload.delete()
      }
    }
  }
}

/** Zips [appBundle] with its symlinks and permissions kept, as `simctl install` needs them. */
private fun zipAppBundle(appBundle: File): File {
  val zip = Files.createTempFile("trailblaze-app-upload-", ".zip").toFile()
  zip.delete()
  val result = TrailblazeProcessBuilderUtils.createProcessBuilder(
    // `--`: a bundle named like an option (`-T.app`) is still read as a file.
    listOf("zip", "-qry", zip.absolutePath, "--", appBundle.name),
    workingDir = appBundle.absoluteFile.parentFile,
  ).runProcess {}
  if (result.exitCode != 0) {
    zip.delete()
    error("Could not zip ${appBundle.name} to send it: ${result.fullOutput}")
  }
  return zip
}
