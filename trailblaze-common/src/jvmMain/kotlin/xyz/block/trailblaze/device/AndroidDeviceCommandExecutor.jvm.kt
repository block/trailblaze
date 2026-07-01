package xyz.block.trailblaze.device

import java.io.File
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.PollingUtils

/**
 * JVM implementation of AndroidDeviceCommandExecutor that delegates to AndroidHostAdbUtils.
 */
actual class AndroidDeviceCommandExecutor actual constructor(
  actual val deviceId: TrailblazeDeviceId,
) {

  // Host transport: commands travel over dadb to `adbd`, which runs them via `sh -c`. Shell
  // quoting and `$?` exit sentinels are honored — see the expect-class KDoc.
  actual val usesShellInterpreter: Boolean = true

  actual fun executeShellCommand(command: String): String {
    return AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = command.split(" "),
    )
  }

  actual fun executeShellCommandArgs(vararg args: String): String = shellCommand(*args)

  actual fun executeShellCommandAs(appId: String, command: String): String {
    // Delegates to `adb shell`, which runs as UID 2000 (shell). That's the privilege
    // `run-as` needs to switch into a debuggable app's UID. See the expect-class KDoc
    // for the full rationale.
    validateRunAsArgs(appId, command)
    return executeShellCommand("run-as $appId $command")
  }

  actual fun sendBroadcast(intent: BroadcastIntent) {
    // Only emit -n when both pieces are present so action-only broadcasts work
    // and we don't ship an invalid "/" component. Matches the androidMain path.
    val component = if (intent.componentPackage.isNotEmpty() && intent.componentClass.isNotEmpty()) {
      "${intent.componentPackage}/${intent.componentClass}"
    } else {
      ""
    }
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = intent.action,
      component = component,
      extras = intent.extras,
    )
    AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = args,
    )
  }

  actual fun forceStopApp(appId: String) {
    AndroidHostAdbUtils.forceStopApp(
      deviceId = deviceId,
      appId = appId,
    )
  }

  actual fun clearAppData(appId: String) {
    AndroidHostAdbUtils.clearAppData(
      deviceId = deviceId,
      appId = appId,
    )
  }

  actual fun isAppRunning(appId: String): Boolean {
    return AndroidHostAdbUtils.isAppRunning(
      deviceId = deviceId,
      appId = appId,
    )
  }

  actual fun grantAppOpsPermission(appId: String, permission: String) {
    shellCommand("appops", "set", appId, permission, "allow")
  }

  actual fun grantRuntimePermission(appId: String, permission: String) {
    // Validate before the shared swallow helper runs — a malformed appId/permission would
    // be silently logged-and-swallowed otherwise, and a future caller with less-trusted
    // input could smuggle shell metacharacters into the device `sh`.
    validateGrantRuntimePermissionArgs(appId, permission)
    // Swallow-and-log lives in the shared `handleGrantRuntimePermissionOutcome` helper so
    // both `actual`s share one tested contract. The dadb shell transport returns stderr
    // from `pm grant` as a non-empty string (not an exception) — passing it through the
    // helper's `result.isNotEmpty()` branch is what surfaces e.g. manifest-mismatch
    // diagnostics without aborting the conservative-superset loop callers rely on.
    handleGrantRuntimePermissionOutcome(appId, permission) {
      shellCommand("pm", "grant", appId, permission)
    }
  }

  /**
   * Writes a file to the device Downloads directory via `adb push` (sync protocol).
   *
   * This is the JVM/host transport for the cross-platform contract documented on the
   * `expect` declaration: "On Android, this uses MediaStore/ContentResolver for Q+
   * compatibility. On JVM, this uses adb to push the file." `adb push` is the sync-protocol
   * primitive — it bypasses the device shell entirely.
   *
   * **Why not match the on-device MediaStore semantics through `adb shell content
   * insert/query/write`?** That shell-pipe path goes through dadb's shell-v2 stream and
   * the `readAll()` blocks indefinitely when the host ADB server never sends an EXIT
   * packet — a class of hang we hit when piping the file body to `content write` via
   * stdin. The push primitive does not touch the shell service, so it isn't subject to
   * that hang.
   *
   * The pushed file lands at `/storage/emulated/0/Download/<fileName>` — the same path
   * MediaStore would write to. Apps with `MANAGE_EXTERNAL_STORAGE` (granted by the
   * trailhead before any file is written) can read it via `FileReadWriteUtil`'s
   * filesystem-fallback branch, even though no MediaStore row exists.
   */
  actual fun writeFileToDownloads(fileName: String, content: ByteArray) {
    val remotePath = "/storage/emulated/0/Download/$fileName"
    shellCommand("rm", "-f", remotePath)
    val tempFile = File.createTempFile("trailblaze-dl-", ".tmp")
    try {
      tempFile.writeBytes(content)
      val pushed = AndroidHostAdbUtils.pushFile(deviceId, tempFile, remotePath)
      if (!pushed) {
        error("Failed to push $fileName to $remotePath")
      }
      Console.log("Wrote ${content.size} bytes to $remotePath via adb push")
    } finally {
      tempFile.delete()
    }
  }

  actual fun writeFileToDevice(devicePath: String, content: ByteArray) {
    // Stage the bytes in a host temp file and `adb push` them (sync protocol) — never through
    // the shell, so we avoid the stdin/EXIT-packet hang that piping a body to `adb shell` hits.
    val tempFile = File.createTempFile("trailblaze-write-", ".tmp")
    try {
      tempFile.writeBytes(content)
      // `adb push` does not reliably create missing parent dirs; create them first. The path is
      // single-quote-escaped (execAdbShellCommand joins args with spaces and hands them to the
      // device shell unescaped) so a path with spaces/metacharacters can't split or inject.
      File(devicePath).parent?.let { parent ->
        shellCommand("mkdir", "-p", parent.shellEscape())
      }
      val pushed = AndroidHostAdbUtils.pushFile(deviceId, tempFile, devicePath)
      if (!pushed) {
        error("Failed to push ${content.size} bytes to $devicePath")
      }
      Console.log("Wrote ${content.size} bytes to $devicePath via adb push")
    } finally {
      tempFile.delete()
    }
  }

  actual fun deleteFileFromDownloads(fileName: String) {
    // writeFileToDownloads pushes to the raw filesystem path (no MediaStore row), so the
    // primary cleanup is `rm -f` on that path. The `content delete` call is a best-effort
    // backstop for any MediaStore rows left by earlier framework versions that wrote
    // through the content provider — without it, those legacy rows would remain after a
    // delete on hosts that have been upgraded mid-run.
    val remotePath = "/storage/emulated/0/Download/$fileName"
    try {
      shellCommand("rm", "-f", remotePath)
    } catch (e: Exception) {
      Console.log("Warning: could not rm $remotePath: ${e.message}")
    }
    try {
      shellCommand(
        "content", "delete",
        "--uri", MEDIASTORE_DOWNLOADS_URI,
        "--where", whereDisplayName(fileName),
      )
    } catch (e: Exception) {
      Console.log("Warning: could not delete MediaStore entry for $fileName: ${e.message}")
    }
  }

  /**
   * Builds a SQL WHERE clause for matching a file by display name.
   *
   * Single quotes around the value must survive the device shell when passed via
   * `adb shell content ...`. Since ProcessBuilder passes each arg directly to adb,
   * and adb joins all post-"shell" args with spaces before passing to the device's sh,
   * the device shell interprets unescaped single quotes as shell quoting (stripping them).
   * Escaping with backslash (\') produces a literal single quote in the device shell,
   * so the `content` command receives proper SQL: `_display_name='filename'`.
   */
  private fun whereDisplayName(fileName: String): String {
    return "_display_name=\\'$fileName\\'"
  }

  private fun shellCommand(vararg args: String): String {
    return AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = args.toList(),
    )
  }

  actual fun listInstalledApps(): List<String> {
    return AndroidHostAdbUtils.listInstalledPackages(deviceId)
  }

  actual fun listInstalledAppsDetailed(includeLabelsAndVersions: Boolean): List<InstalledApp> {
    // The host/adb path reads everything from one `dumpsys package packages` call (isSystemApp,
    // version, buildNumber, installPath); only the human label is unavailable. So the
    // includeLabelsAndVersions flag has no effect here — see AndroidHostAdbUtils.
    return AndroidHostAdbUtils.listInstalledAppsDetailed(deviceId)
  }

  actual fun disablePackageForUser(packageId: String) {
    shellCommand("pm", "disable-user", packageId)
  }

  actual fun enablePackageForUser(packageId: String) {
    shellCommand("pm", "enable", packageId)
  }

  actual fun addContact(contact: DeviceContact) {
    // Insert raw contact
    val rawContactInsertOutput = shellCommand(
      "content", "insert",
      "--uri", CONTACTS_RAW_URI,
      "--bind", "account_type:s:${contact.accountType.orEmpty()}",
      "--bind", "account_name:s:${contact.accountName.orEmpty()}",
    )

    // Resolve the newly-inserted raw contact's ID. `parseInsertedContentId` extracts it from the
    // `content insert` shell output (same call that just ran — exact, no race window).
    // `queryLastRawContactId` is a fallback that does a separate `content query ORDER BY _id DESC
    // LIMIT 1`; it's only correct under the assumption that no other process inserted a raw contact
    // between the insert above and the query below — true for typical single-threaded-per-device
    // test runs but worth knowing if this method ever gets called concurrently in the future.
    val rawContactId = parseInsertedContentId(rawContactInsertOutput)
      ?: queryLastRawContactId()
      ?: error("Failed to insert raw contact for '${contact.displayName}'")

    // Insert display name
    shellCommand(
      "content", "insert",
      "--uri", CONTACTS_DATA_URI,
      "--bind", "raw_contact_id:i:$rawContactId",
      "--bind", "mimetype:s:vnd.android.cursor.item/name",
      "--bind", "data1:s:${contact.displayName}",
    )

    // Insert phone number
    shellCommand(
      "content", "insert",
      "--uri", CONTACTS_DATA_URI,
      "--bind", "raw_contact_id:i:$rawContactId",
      "--bind", "mimetype:s:vnd.android.cursor.item/phone_v2",
      "--bind", "data1:s:${contact.phoneNumber}",
      "--bind", "data2:i:${contact.phoneType}",
    )
  }

  actual fun insertSmsIntoInbox(message: DeviceSmsMessage) {
    shellCommand(
      "content", "insert",
      "--uri", "content://sms",
      "--bind", "address:s:${message.fromNumber}",
      "--bind", "body:s:${message.body}",
      "--bind", "type:i:1", // MESSAGE_TYPE_INBOX
      "--bind", "read:i:${if (message.isRead) 1 else 0}",
      "--bind", "date:l:${message.dateMillis}",
      "--bind", "date_sent:l:${message.dateSentMillis}",
    )
  }

  actual fun setClipboard(text: String) {
    // Each arg is passed individually via ProcessBuilder → adb, so no shell
    // escaping is needed — adb passes args directly to `am` on the device.
    shellCommand(
      "am", "broadcast",
      "-a", "clipper.set",
      "--es", "text", text,
    )
    lastSetClipboard = text
  }

  actual fun getClipboard(): String = lastSetClipboard ?: ""

  // In-process cache of the last `setClipboard` text. Mirrors the on-device
  // actual's behaviour so `mobile_pasteClipboard` round-trips deterministically
  // regardless of which Android driver is in use. The Clipper broadcast above
  // still writes to the OS clipboard for any other observers (e.g. the
  // foreground app's manual paste); this cache is just for our own paste path.
  @Volatile private var lastSetClipboard: String? = null

  actual fun waitUntilAppInForeground(
    appId: String,
    maxWaitMs: Long,
    checkIntervalMs: Long,
  ): Boolean = PollingUtils.tryUntilSuccessOrTimeout(
    maxWaitMs = maxWaitMs,
    intervalMs = checkIntervalMs,
    conditionDescription = "App $appId should be in foreground",
  ) {
    val output = AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("dumpsys", "window", "windows"),
    )
    output.lineSequence()
      .any { it.contains("mCurrentFocus") && it.contains(appId) }
  }

  actual fun copyTestResourceToDevice(resourcePath: String, devicePath: String) {
    // Read resource from classpath (equivalent of test APK assets on JVM)
    val inputStream = this::class.java.classLoader?.getResourceAsStream(resourcePath)
      ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
      ?: error("Test resource not found on classpath: $resourcePath")

    val tempFile = File.createTempFile("trailblaze_push_", ".tmp")
    try {
      tempFile.outputStream().use { output ->
        inputStream.use { input -> input.copyTo(output) }
      }

      // Ensure parent directory exists on device
      val parentDir = File(devicePath).parent
      if (parentDir != null) {
        shellCommand("mkdir", "-p", parentDir)
      }

      // Push file to device via dadb (over the adb wire protocol)
      AndroidHostAdbUtils.pushFile(
        deviceId = deviceId,
        localFile = tempFile,
        remotePath = devicePath,
      )
    } finally {
      tempFile.delete()
    }
  }

  private fun queryLastRawContactId(): String? {
    val output = shellCommand(
      "content", "query",
      "--uri", CONTACTS_RAW_URI,
      "--projection", "_id",
      "--sort", "_id DESC LIMIT 1",
    )
    return ID_PATTERN.find(output)?.groupValues?.get(1)
  }
  private fun parseInsertedContentId(insertOutput: String): String? {
    return INSERTED_CONTENT_ID_PATTERN.find(insertOutput)?.groupValues?.get(1)
  }

  companion object {
    /**
     * MediaStore Downloads collection URI — equivalent to
     * `MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)` on device.
     *
     * This is the same collection that `FileReadWriteUtil.getDownloadsFileUri()` queries,
     * so files inserted here are immediately visible to apps reading via ContentResolver.
     */
    private const val MEDIASTORE_DOWNLOADS_URI = "content://media/external_primary/downloads"
    private const val CONTACTS_RAW_URI = "content://com.android.contacts/raw_contacts"
    private const val CONTACTS_DATA_URI = "content://com.android.contacts/data"
    // Matches the URI in `adb shell content insert ...` output, e.g.
    //   "Inserted as content://com.android.contacts/raw_contacts/42"
    // Inside a Kotlin raw string the backslashes pass through verbatim, so `\s` and `\d`
    // reach the regex engine as the shorthand classes. The historical value here used
    // `\\s` / `\\d`, which made the regex try to match literal backslash characters and
    // never matched any real insert output — callers in `addContact` silently fell through
    // to the slower `queryLastRawContactId` fallback. Visibility is `internal` so the
    // matching contract is testable from `jvmTest` within this module; this codebase has
    // no Java callers (and `trailblaze-common` is consumed only by other Kotlin modules),
    // so the Kotlin-internal scope is the appropriate level — though strictly speaking
    // `internal` compiles to mangled-name `public` at the JVM bytecode level, that
    // surface isn't part of any public Kotlin API consumers see.
    internal val INSERTED_CONTENT_ID_PATTERN = Regex("""content://[^\s]+/(\d+)""")

    private val ID_PATTERN = Regex("""_id=(\d+)""")
  }
}
