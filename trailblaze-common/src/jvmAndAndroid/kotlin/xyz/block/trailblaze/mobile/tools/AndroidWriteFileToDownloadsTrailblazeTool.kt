package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Writes a UTF-8 text file into the Android device's public Downloads directory.
 *
 * Low-level building block for Android automation that needs to stage a file an app reads on
 * launch — e.g. an instrumentation/setup JSON an app's `ContentProvider` picks up during init.
 * Higher-level flows compose this in TypeScript/YAML rather than reimplementing the file-write
 * plumbing in Kotlin.
 *
 * Dual-mode, delegating to
 * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.writeFileToDownloads]:
 * - **On-device**: writes via `MediaStore.Downloads`, so the file is registered with MediaStore
 *   and therefore discoverable by a consuming app's `MediaStore` query — the API 30+ scoped-storage
 *   path.
 * - **Host**: `adb push` to `/storage/emulated/0/Download/<fileName>` (raw filesystem path, no
 *   MediaStore row).
 *
 * **Why Downloads-specific, not a generic write-to-path.** The on-device MediaStore registration is
 * exactly what makes the file visible to a consuming app's `ContentProvider` under scoped storage;
 * a raw write to an arbitrary path does not provide that. A general write-bytes-to-arbitrary-path
 * tool would be a *different* primitive (app-private / sdcard staging), not a superset this composes
 * from — so this stays its own focused primitive, mirroring the executor's own `writeFileToDownloads`
 * boundary.
 *
 * [content] is UTF-8 text — the common case (JSON / config). JSON-shaped tool args can't carry raw
 * bytes, so a binary payload would need a separate base64 variant; deliberately not added until
 * there is a use for it.
 *
 * Not LLM-facing (`surfaceToLlm = false`) and not recordable (`isRecordable = false`): it's a
 * composition primitive, not an agent-visible action. Dual-mode (`requiresHost` defaults to `false`)
 * so the on-device runner registers it too — the underlying executor has both actuals.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_writeFileToDownloads",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Writes a UTF-8 text file into the Android device's public Downloads directory.")
data class AndroidWriteFileToDownloadsTrailblazeTool(
  @param:LLMDescription("File name to create in the Downloads directory, e.g. 'setup.json'.")
  val fileName: String,
  @param:LLMDescription("UTF-8 text content to write to the file.")
  val content: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage =
          "android_writeFileToDownloads is only supported on Android devices (got ${deviceInfo.platform}).",
      )
    }
    // Author-fixable validation before infra checks, so a bad arg fails with a routable message
    // regardless of whether an executor happens to be wired (mirrors android_sendBroadcast).
    if (fileName.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeFileToDownloads: fileName must not be blank.",
        command = this@AndroidWriteFileToDownloadsTrailblazeTool,
      )
    }
    // Reject path separators / parent-dir references: the host transport concatenates this into
    // `/storage/emulated/0/Download/$fileName`, so a value like `a/b` or `..` could write outside
    // Downloads. This is a bare-filename primitive; a caller needing another directory uses a
    // path-taking tool (e.g. android_writeBytesToFile), not a traversal here.
    if (fileName.contains('/') || fileName.contains('\\') || fileName == "." || fileName == "..") {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeFileToDownloads: fileName must be a bare filename with no path " +
          "separators or '.'/'..' segments (got '$fileName').",
        command = this@AndroidWriteFileToDownloadsTrailblazeTool,
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
      )
    val bytes = content.toByteArray()
    return try {
      executor.writeFileToDownloads(fileName, bytes)
      TrailblazeToolResult.Success(
        message = "Wrote ${bytes.size} bytes to Downloads/$fileName.",
      )
    } catch (e: CancellationException) {
      // Let coroutine cancellation propagate — swallowing it as a tool error breaks structured
      // concurrency (session teardown / abort). Must precede the generic catch (it IS an Exception).
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to write Downloads/$fileName: ${e.message}",
        command = this@AndroidWriteFileToDownloadsTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
