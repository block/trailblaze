package xyz.block.trailblaze.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.FileReadWriteUtil
import xyz.block.trailblaze.android.tools.shellEscape
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

  actual fun executeShellCommandAs(appId: String, command: String): String {
    // Delegates to executeShellCommand → UiDevice.executeShellCommand → UiAutomation, which
    // runs the wrapping shell as UID 2000 (shell). That's the privilege `run-as` needs to
    // switch into a debuggable app's UID, even though our test process has its own unrelated
    // UID. See the expect-class KDoc for the full rationale.
    validateRunAsArgs(appId, command)
    return executeShellCommand("run-as $appId $command")
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
          is Float -> putExtra(key, value)
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

  actual fun grantAppOpsPermission(appId: String, permission: String) {
    AdbCommandUtil.grantAppOpsPermission(
      targetAppPackageName = appId,
      permission = permission,
    )
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

  actual fun addContact(contact: DeviceContact) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val ops = ArrayList<ContentProviderOperation>()

    // Insert raw contact
    ops.add(
      ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, contact.accountType)
        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, contact.accountName)
        .build(),
    )

    // Insert display name
    ops.add(
      ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        .withValue(
          ContactsContract.Data.MIMETYPE,
          ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        )
        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
        .build(),
    )

    // Insert phone number
    ops.add(
      ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        .withValue(
          ContactsContract.Data.MIMETYPE,
          ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        )
        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phoneNumber)
        .withValue(
          ContactsContract.CommonDataKinds.Phone.TYPE,
          contact.phoneType,
        )
        .build(),
    )

    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
  }

  actual fun insertSmsIntoInbox(message: DeviceSmsMessage) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val contentValues = ContentValues().apply {
      put(Telephony.Sms.ADDRESS, message.fromNumber)
      put(Telephony.Sms.BODY, message.body)
      put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
      put(Telephony.Sms.READ, if (message.isRead) 1 else 0)
      put(Telephony.Sms.DATE, message.dateMillis)
      put(Telephony.Sms.DATE_SENT, message.dateSentMillis)
    }

    context.contentResolver.insert(Telephony.Sms.CONTENT_URI, contentValues)
      ?: error("Failed to insert SMS — content provider returned null URI.")
  }

  actual fun setClipboard(text: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("trailblaze", text)

    // ClipboardManager.setPrimaryClip must be called on the main thread
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      clipboard.setPrimaryClip(clip)
    }
  }

  actual fun waitUntilAppInForeground(
    appId: String,
    maxWaitMs: Long,
    checkIntervalMs: Long,
  ): Boolean = AdbCommandUtil.waitUntilAppInForeground(
    appId = appId,
    maxWaitMs = maxWaitMs,
    checkIntervalMs = checkIntervalMs,
  )

  actual fun copyTestResourceToDevice(resourcePath: String, devicePath: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val destFile = File(devicePath)

    destFile.parentFile?.let { parent ->
      if (!parent.exists()) parent.mkdirs()
    }

    try {
      // Try direct file write first
      instrumentation.context.assets.open(resourcePath).use { input ->
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
    } catch (_: Exception) {
      // Fallback: write to app-accessible temp dir, then shell cp
      val tempFile = File(instrumentation.targetContext.cacheDir, destFile.name)
      instrumentation.context.assets.open(resourcePath).use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output)
        }
      }
      destFile.parentFile?.let { parent ->
        executeShellCommand("mkdir -p ${parent.absolutePath.shellEscape()}")
      }
      executeShellCommand("cp ${tempFile.absolutePath.shellEscape()} ${devicePath.shellEscape()}")
      tempFile.delete()
    }
  }

}
