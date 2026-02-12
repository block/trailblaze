package xyz.block.trailblaze.device

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils

/**
 * JVM implementation of AndroidDeviceCommandExecutor that delegates to AndroidHostAdbUtils.
 */
actual class AndroidDeviceCommandExecutor actual constructor(
  actual val deviceId: TrailblazeDeviceId,
) {

  actual fun executeShellCommand(command: String): String {
    return AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = command.split(" "),
    )
  }

  actual fun sendBroadcast(intent: BroadcastIntent) {
    val component = "${intent.componentPackage}/${intent.componentClass}"
    val extras = intent.extras.mapValues { it.value.toString() }
    val args = AndroidHostAdbUtils.intentToAdbBroadcastCommandArgs(
      action = intent.action,
      component = component,
      extras = extras,
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

  /**
   * Writes a file to Downloads via the MediaStore content provider.
   *
   * Uses `adb shell content insert/write` to go through the same MediaStore APIs that
   * the on-device Android implementation uses via ContentResolver. This ensures the file
   * is properly registered in MediaStore and accessible to all apps through scoped storage,
   * without requiring root or special permissions. Works on both emulators and physical devices.
   */
  actual fun writeFileToDownloads(fileName: String, content: ByteArray) {
    // Remove any stale entry with the same name first
    deleteFileFromDownloads(fileName)

    // Step 1: Insert a MediaStore entry directly into the Downloads collection.
    // This matches what the on-device Android implementation does via
    // MediaStore.Downloads.getContentUri(VOLUME_EXTERNAL_PRIMARY).
    // Note: using the "file" collection with relative_path=Download does NOT make
    // entries visible in the "downloads" collection that apps query.
    shellCommand(
      "content", "insert",
      "--uri", MEDIASTORE_DOWNLOADS_URI,
      "--bind", "_display_name:s:$fileName",
      "--bind", "mime_type:s:application/json",
    )

    // Step 2: Get the content:// URI ID for the newly created entry
    val mediaStoreId = queryMediaStoreId(fileName)
      ?: error("Failed to create MediaStore entry for $fileName in Downloads")

    // Step 3: Write the actual file content through the content provider.
    // Piping content via stdin to `adb shell content write` is the equivalent
    // of ContentResolver.openOutputStream(uri) on device.
    val writeProcess = AndroidHostAdbUtils.createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf("shell", "content", "write", "--uri", "$MEDIASTORE_DOWNLOADS_URI/$mediaStoreId"),
    ).start()

    writeProcess.outputStream.use { stdin ->
      stdin.write(content)
      stdin.flush()
    }

    val exitCode = writeProcess.waitFor()
    if (exitCode != 0) {
      val stderr = writeProcess.errorStream.bufferedReader().readText()
      error("Failed to write content to MediaStore entry $mediaStoreId: $stderr")
    }

    println("Wrote ${content.size} bytes to MediaStore Downloads: $fileName (id=$mediaStoreId)")
  }

  actual fun deleteFileFromDownloads(fileName: String) {
    try {
      // Delete via MediaStore content provider — this removes both the MediaStore entry
      // and the underlying file on the filesystem. Works across all API levels with
      // scoped storage (no root required).
      shellCommand(
        "content", "delete",
        "--uri", MEDIASTORE_DOWNLOADS_URI,
        "--where", whereDisplayName(fileName),
      )
    } catch (e: Exception) {
      // Best-effort cleanup
      println("Warning: could not delete MediaStore entry for $fileName: ${e.message}")
    }
  }

  /**
   * Queries MediaStore for a file's _id by display name in the Downloads collection.
   */
  private fun queryMediaStoreId(fileName: String): String? {
    val output = shellCommand(
      "content", "query",
      "--uri", MEDIASTORE_DOWNLOADS_URI,
      "--projection", "_id",
      "--where", whereDisplayName(fileName),
    )
    // Output format: "Row: 0 _id=123"
    return ID_PATTERN.find(output)?.groupValues?.get(1)
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

  companion object {
    /**
     * MediaStore Downloads collection URI — equivalent to
     * `MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)` on device.
     *
     * This is the same collection that `FileReadWriteUtil.getDownloadsFileUri()` queries,
     * so files inserted here are immediately visible to apps reading via ContentResolver.
     */
    private const val MEDIASTORE_DOWNLOADS_URI = "content://media/external_primary/downloads"

    private val ID_PATTERN = Regex("""_id=(\d+)""")
  }
}
