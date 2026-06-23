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
 * **Low-level framework primitive**: writes raw bytes to an absolute path on the Android device,
 * creating parent directories and overwriting any existing file. Bytes are carried over the
 * (text-only) tool-args wire as a base64 string.
 *
 * ### Why this earns a framework slot (and `android_adbShell` doesn't cover it)
 *
 * The general device primitive is [AdbShellTrailblazeTool] (`android_adbShell`) — argv-complete,
 * so `chmod`, `chown`, `content insert`, `cmd media_scanner`, even a small `base64 -d > path` are
 * all expressible there with no extra surface. The one thing it can't do reliably is move a file
 * **body**:
 *  - on the **host**, piping bytes through `adb shell` stdin hits the EXIT-packet hang the executor
 *    avoids by using `adb push`;
 *  - **on-device**, passing a base64 blob as a shell argument hits `ARG_MAX` for large payloads.
 *
 * So this tool is deliberately narrow — it does exactly the thing `adb shell` composition can't:
 * transfer bytes to a path. Everything else (permissions, owner, MIME, MediaStore registration,
 * a specific public collection) stays a caller composition over `android_adbShell`, which keeps
 * this primitive free of the param sprawl (`mimeType`, `relativePath`, `mode`, `owner`, …) that a
 * "do everything" file tool would accrete. See
 * `docs/devlog/2026-06-22-framework-primitives-helpers-compose.md`.
 *
 * ### Filesystem-only — no MediaStore, no MIME
 *
 * This writes to the raw filesystem; it does **not** register the file with MediaStore (a plain
 * path write on a scoped-storage device isn't MediaStore-indexed). base64 carries no MIME — it's
 * just the bytes — and the filesystem has no MIME concept, so there's nothing to configure here.
 * If a consumer must find the file via a MediaStore query, register it separately via
 * `android_adbShell` (`cmd media_scanner scan <path>` / `content insert --bind mime_type:…`); for
 * the public Downloads MediaStore collection specifically, the executor's `writeFileToDownloads`
 * path is the MediaStore-registered one.
 *
 * ### Dual-mode (`requiresHost` defaulted false)
 *
 * Backed by the dual-mode [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.writeFileToDevice]
 * (host `adb push`; on-device direct `java.io.File` write, falling back to a temp-file + `cp` for
 * paths needing the `shell` UID). Registered on both ends of the host/on-device matrix — like
 * `android_adbShell` — so a scripted tool composing only dual-mode tools doesn't need
 * `requiresHost: true`.
 *
 * Android-only — surfaces a clear error on iOS / web / the Compose desktop driver.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_writeBytesToFile",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription(
  "Writes raw bytes (supplied as a base64 string) to an absolute path on the Android device, " +
    "creating parent directories and overwriting any existing file. The byte-transfer primitive — " +
    "use it to seed any file (text or binary) the device shell can't move reliably. Filesystem " +
    "only: it does not register MediaStore or set a MIME type (compose `android_adbShell` for that).",
)
data class AndroidWriteBytesToFileTrailblazeTool(
  @param:LLMDescription(
    "Absolute destination path on the device (must start with `/`), e.g. " +
      "`/storage/emulated/0/Download/setup.json` or `/sdcard/Pictures/logo.png`.",
  )
  val devicePath: String,
  @param:LLMDescription(
    "The file content as a base64-encoded byte string. It is decoded to raw bytes before " +
      "writing, so any binary payload is supported.",
  )
  val base64Content: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeBytesToFile is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    validateDevicePath(devicePath)?.let { reason ->
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeBytesToFile received an invalid devicePath: $reason",
        command = this,
      )
    }
    // Decode BEFORE reaching for the executor, so malformed base64 fails with a targeted message
    // rather than a generic write error.
    val bytes = try {
      Base64.getDecoder().decode(base64Content)
    } catch (e: IllegalArgumentException) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeBytesToFile could not base64-decode `base64Content`: ${e.message}",
        command = this,
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      executor.writeFileToDevice(devicePath = devicePath, content = bytes)
      TrailblazeToolResult.Success(
        message = "Wrote ${bytes.size} bytes to '$devicePath'.",
      )
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't swallowed. Precedent:
      // `AdbShellTrailblazeTool.execute`, `AndroidGrantPermissionTrailblazeTool.execute`.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to write '$devicePath': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  companion object {
    /**
     * Validates that [devicePath] is a usable absolute device path, returning a human-readable
     * reason when it isn't, or `null` when it's acceptable. Pure function so the contract is
     * unit-testable without a real
     * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor].
     *
     * Unlike the bare-filename validators elsewhere, this does **not** restrict the character set:
     * a path legitimately contains `/`, and the executor's two `actual`s single-quote-escape the
     * path before any shell `mkdir`/`cp` (and the host body transfer uses `adb push`, which never
     * touches the shell), so metacharacters can't split or inject. We only require the path to be
     * non-blank and absolute — a relative path is ambiguous against the device's working directory.
     */
    internal fun validateDevicePath(devicePath: String): String? = when {
      devicePath.isBlank() -> "must not be blank"
      !devicePath.startsWith("/") ->
        "must be an absolute path starting with '/' (got '$devicePath')"
      else -> null
    }
  }
}
