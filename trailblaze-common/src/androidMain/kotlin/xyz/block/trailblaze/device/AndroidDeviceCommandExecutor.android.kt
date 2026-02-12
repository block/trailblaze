package xyz.block.trailblaze.device

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.FileReadWriteUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Android implementation of AndroidDeviceCommandExecutor that delegates to AdbCommandUtil
 * and uses InstrumentationRegistry for broadcasts.
 */
actual class AndroidDeviceCommandExecutor actual constructor(
  actual val deviceId: TrailblazeDeviceId,
) {

  actual fun executeShellCommand(command: String): String {
    return AdbCommandUtil.execShellCommand(command)
  }

  actual fun sendBroadcast(intent: BroadcastIntent) {
    val context = InstrumentationRegistry.getInstrumentation().context
    val androidIntent = Intent(intent.action).apply {
      if (intent.componentPackage.isNotEmpty() && intent.componentClass.isNotEmpty()) {
        setPackage(intent.componentPackage)
        setClassName(intent.componentPackage, intent.componentClass)
      }
      intent.extras.forEach { (key, value) ->
        when (value) {
          is String -> putExtra(key, value)
          is Boolean -> putExtra(key, value)
          is Int -> putExtra(key, value)
          is Long -> putExtra(key, value)
          else -> putExtra(key, value.toString())
        }
      }
    }
    context.sendBroadcast(androidIntent)
  }

  actual fun forceStopApp(appId: String) {
    AdbCommandUtil.forceStopApp(appId)
  }

  actual fun clearAppData(appId: String) {
    AdbCommandUtil.clearPackageData(appId)
  }

  actual fun isAppRunning(appId: String): Boolean {
    return AdbCommandUtil.isAppRunning(appId)
  }

  actual fun writeFileToDownloads(fileName: String, content: ByteArray) {
    val context = InstrumentationRegistry.getInstrumentation().context
    FileReadWriteUtil.writeToDownloadsFile(
      context = context,
      fileName = fileName,
      contentBytes = content,
      directory = null,
    )
  }

  actual fun deleteFileFromDownloads(fileName: String) {
    val context = InstrumentationRegistry.getInstrumentation().context
    FileReadWriteUtil.deleteFromDownloadsIfExists(context, fileName)
  }

  actual fun listInstalledApps(): List<String> {
    return AdbCommandUtil.listInstalledApps()
  }
}
