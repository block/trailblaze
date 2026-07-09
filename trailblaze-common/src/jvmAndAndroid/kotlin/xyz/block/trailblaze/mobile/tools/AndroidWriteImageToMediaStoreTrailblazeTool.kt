package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Writes an image into the Android device's public Pictures directory and registers it as a
 * MediaStore Images row, so a gallery / photo picker can select it.
 *
 * Dual-mode, delegating to
 * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.writeFileToImages]:
 * - **On-device**: `MediaStore.Images` `ContentResolver.insert` — the scoped-storage-safe path that
 *   auto-registers the row, so the image is discoverable even where a raw filesystem write wouldn't be.
 * - **Host**: `adb push` to `/sdcard/Pictures/<fileName>` then a MediaStore `scan_file`.
 *
 * Unlike [AndroidWriteBytesToFileTrailblazeTool] (filesystem only, no MediaStore), this exists
 * precisely to produce an Images row in EITHER execution mode — the on-device RPC runner can't get
 * one from a plain path write under scoped storage. Bytes are carried over the (text-only) tool-args
 * wire as a base64 string.
 *
 * Not LLM-facing (`surfaceToLlm = false`) and not recordable (`isRecordable = false`): a composition
 * primitive, not an agent-visible action. Dual-mode (`requiresHost` defaults to `false`) so the
 * on-device runner registers it too — the underlying executor has both actuals.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_writeImageToMediaStore",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Writes an image into the Android device's public Pictures directory and registers it in MediaStore.")
data class AndroidWriteImageToMediaStoreTrailblazeTool(
  @param:LLMDescription("File name to create in the Pictures directory, e.g. 'photo.png'.")
  val fileName: String,
  @param:LLMDescription("Image content as a base64-encoded byte string; decoded to raw bytes before writing.")
  val base64Content: String,
  @param:LLMDescription("Image MIME type. Defaults to 'image/png'.")
  val mimeType: String = "image/png",
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage =
          "android_writeImageToMediaStore is only supported on Android devices (got ${deviceInfo.platform}).",
      )
    }
    // Author-fixable validation before infra checks, so a bad arg fails with a routable message
    // regardless of whether an executor happens to be wired (mirrors android_writeFileToDownloads).
    if (fileName.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeImageToMediaStore: fileName must not be blank.",
        command = this@AndroidWriteImageToMediaStoreTrailblazeTool,
      )
    }
    // Reject path separators / parent-dir references: the host transport concatenates this into
    // `/sdcard/Pictures/$fileName`, so a value like `a/b` or `..` could write outside Pictures.
    // Allowlist the filename: it is concatenated into a device path and passed to the shell
    // (scan_file), so reject anything but bare-filename characters — this covers path separators,
    // whitespace and shell metacharacters; the "."/".." dot segments (which the allowlist
    // itself accepts) are rejected explicitly so a name can't resolve to the Pictures dir/parent.
    if (!Regex("^[A-Za-z0-9._-]+$").matches(fileName) || fileName == "." || fileName == "..") {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeImageToMediaStore: fileName must be a bare filename matching " +
          "[A-Za-z0-9._-] and not '.'/'..' (got '$fileName').",
        command = this@AndroidWriteImageToMediaStoreTrailblazeTool,
      )
    }
    // Decode BEFORE reaching for the executor, so malformed base64 fails with a targeted message
    // rather than a generic write error.
    val bytes = try {
      Base64.getDecoder().decode(base64Content)
    } catch (e: IllegalArgumentException) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeImageToMediaStore could not base64-decode `base64Content`: ${e.message}",
        command = this@AndroidWriteImageToMediaStoreTrailblazeTool,
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
      )
    return try {
      executor.writeFileToImages(fileName, bytes, mimeType)
      TrailblazeToolResult.Success(
        message = "Wrote ${bytes.size} bytes to Pictures/$fileName and registered it in MediaStore.",
      )
    } catch (e: CancellationException) {
      // Let coroutine cancellation propagate — swallowing it as a tool error breaks structured
      // concurrency (session teardown / abort). Must precede the generic catch (it IS an Exception).
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to write Pictures/$fileName: ${e.message}",
        command = this@AndroidWriteImageToMediaStoreTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
