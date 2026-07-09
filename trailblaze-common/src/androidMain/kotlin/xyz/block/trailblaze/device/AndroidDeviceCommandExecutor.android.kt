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

  // On-device transport: executeShellCommand routes through UiAutomationConnection.executeShellCommand
  // → Runtime.exec, which whitespace-splits and execs tokens directly with NO shell interpreter.
  // Shell quoting and `$?` exit sentinels do not work here — see the expect-class KDoc.
  actual val usesShellInterpreter: Boolean = false

  actual fun executeShellCommand(command: String): String {
    return AdbCommandUtil.execShellCommand(command)
  }

  actual fun executeShellCommandArgs(vararg args: String): String {
    require(args.none { it.any(Char::isWhitespace) }) {
      "executeShellCommandArgs: arguments must not contain whitespace on Android — " +
        "UiAutomationConnection.executeShellCommand routes through Runtime.exec(String), " +
        "which splits on whitespace and execs tokens directly without a shell interpreter. " +
        "Whitespace-containing args would be silently re-split into extra tokens. " +
        "Offending args: ${args.filter { it.any(Char::isWhitespace) }}"
    }
    return AdbCommandUtil.execShellCommand(args.joinToString(" "))
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

  actual fun grantRuntimePermission(appId: String, permission: String) {
    validateGrantRuntimePermissionArgs(appId, permission)
    // [AdbCommandUtil.grantPermission] no-ops if the permission is already granted and
    // returns Unit on success; failures (e.g. permission not declared in the target
    // manifest) throw. Routes through the shared `handleGrantRuntimePermissionOutcome`
    // helper so the swallow-and-log contract matches the JVM `actual` and is covered by
    // the same test suite. Block returns `null` so the helper's stderr-string branch is
    // skipped — there is no stderr to surface from this transport.
    handleGrantRuntimePermissionOutcome(appId, permission) {
      AdbCommandUtil.grantPermission(
        targetAppPackageName = appId,
        permission = permission,
      )
      null
    }
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

  actual fun writeFileToImages(fileName: String, content: ByteArray, mimeType: String) {
    val context = InstrumentationRegistry.getInstrumentation().context
    FileReadWriteUtil.writeToImagesFile(
      context = context,
      fileName = fileName,
      contentBytes = content,
      mimeType = mimeType,
    )
  }

  actual fun deleteFileFromDownloads(fileName: String) {
    val context = InstrumentationRegistry.getInstrumentation().context
    FileReadWriteUtil.deleteFromDownloadsIfExists(context, fileName)
  }

  actual fun writeFileToDevice(devicePath: String, content: ByteArray) {
    val destFile = File(devicePath)
    try {
      // Direct write for paths our UID can reach. Bytes never transit a shell argument (no
      // ARG_MAX ceiling).
      destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
      FileOutputStream(destFile).use { it.write(content) }
    } catch (_: Exception) {
      // Public storage denies our UID's direct write (API < 29: storage gid fixed at fork;
      // 30+: no MANAGE_EXTERNAL_STORAGE on the runner) — stage in the app-EXTERNAL files dir
      // (shell-readable, unlike the 0700 private cacheDir) and `cp` into place as the shell UID,
      // via raw argv only: this transport has no shell, so quoted paths silently break (see
      // buildShellCpFallbackCommands). Validated on-device on API 28 + 36.
      val stagingDir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        ?: error(
          "writeFileToDevice: cannot write $devicePath directly and no app-external files dir " +
            "is available to stage the shell-cp fallback.",
        )
      val tempFile = File(stagingDir, destFile.name)
      try {
        FileOutputStream(tempFile).use { it.write(content) }
        buildShellCpFallbackCommands(tempFile.absolutePath, devicePath).forEach { argv ->
          executeShellCommandArgs(*argv.toTypedArray())
        }
        // No exit-code channel on this transport — verify the file landed (`ls` prints the path
        // on stdout only when it exists).
        val listed = executeShellCommandArgs("ls", devicePath)
        if (!listed.contains(devicePath)) {
          error(
            "writeFileToDevice: shell-cp fallback did not produce $devicePath " +
              "(ls output: '${listed.trim()}').",
          )
        }
      } finally {
        tempFile.delete()
      }
    }
  }

  actual fun listInstalledApps(): List<String> {
    return AdbCommandUtil.listInstalledApps()
  }

  actual fun listInstalledAppsDetailed(includeLabelsAndVersions: Boolean): List<InstalledApp> {
    return AdbCommandUtil.listInstalledAppsDetailed(includeLabelsAndVersions)
  }

  actual fun disablePackageForUser(packageId: String) {
    AdbCommandUtil.execShellCommand("pm disable-user ${packageId.shellEscape()}")
  }

  actual fun enablePackageForUser(packageId: String) {
    AdbCommandUtil.execShellCommand("pm enable ${packageId.shellEscape()}")
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
    lastSetClipboard = text
  }

  actual fun getClipboard(): String = lastSetClipboard ?: ""

  // In-process cache of the last `setClipboard` text. Read by `getClipboard`
  // because Android 10+ restricts `ClipboardManager.getPrimaryClip` to the
  // currently-focused app (instrumentation runs in its own process, so a
  // direct read after a setPrimaryClip from the test would return null/empty).
  // The OS clipboard write still happens above for any other observers; this
  // cache is just for our own `mobile_pasteClipboard` round-trip path.
  @Volatile private var lastSetClipboard: String? = null

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
      // Fallback: stage where the shell UID can read (app-EXTERNAL files dir, not the 0700
      // app-private cacheDir), then raw-argv `cp` — same two transport constraints as
      // writeFileToDevice's fallback above; see the comment there.
      val stagingDir = instrumentation.targetContext.getExternalFilesDir(null)
        ?: error(
          "copyTestResourceToDevice: cannot write $devicePath directly and no app-external " +
            "files dir is available to stage the shell-cp fallback.",
        )
      val tempFile = File(stagingDir, destFile.name)
      try {
        instrumentation.context.assets.open(resourcePath).use { input ->
          FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
          }
        }
        buildShellCpFallbackCommands(tempFile.absolutePath, devicePath).forEach { argv ->
          executeShellCommandArgs(*argv.toTypedArray())
        }
      } finally {
        tempFile.delete()
      }
    }
  }

}
