package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.MAX_RUN_AS_WRITE_CONTENT_BYTES
import xyz.block.trailblaze.device.redactBulkPayloadsForLog
import xyz.block.trailblaze.device.runAsAppIdViolation
import xyz.block.trailblaze.device.writeFileAs
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool
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
 * transfer bytes to a path. File *attributes* (permissions, owner, MIME, MediaStore registration,
 * a specific public collection) stay caller compositions over `android_adbShell`, which keeps this
 * primitive free of the param sprawl (`mimeType`, `relativePath`, `mode`, `owner`, …) that a "do
 * everything" file tool would accrete. See
 * `docs/devlog/2026-06-22-framework-primitives-helpers-compose.md`.
 *
 * [runAs] is the one admitted exception, and it marks where the line actually falls: the *identity*
 * a write runs under is not an attribute you can apply afterwards — it decides whether the bytes
 * can land at all — and getting it right means transport-correct shell quoting, which is the exact
 * footgun a scripted author should never hand-roll. Attributes stay compositions; identity doesn't.
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
 * ### Writing inside an app's private data directory ([runAs])
 *
 * The default write path can't cross into `/data/data/<package>/` — neither `adb push` nor the
 * `shell` UID may write there. Setting [runAs] switches the write to the shared `run-as` primitive
 * [xyz.block.trailblaze.device.writeFileAs], which is how a scripted tool seeds a debuggable app's
 * SharedPreferences XML or small config without hand-rolling a `printf | base64 -d > path` pipeline
 * through `android_adbShell`.
 *
 * That path is a different animal from the plain one and the differences are load-bearing: it needs
 * a debuggable target APK, it is capped at [MAX_RUN_AS_WRITE_CONTENT_BYTES] because the body rides
 * inside the command line, and it has no exit-code channel — so success is inferred from the
 * pipeline staying silent rather than reported by the shell.
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
  /**
   * Optional Android package id to perform the write as, via `run-as <appId>`. When set, the bytes
   * land with the target app's UID — the only supported way to seed a file inside its private
   * directory, e.g. `/data/data/com.example.app/shared_prefs/debug.xml`. [devicePath] stays
   * absolute here as everywhere else, even though `run-as` itself would resolve a relative path
   * against the app's data dir.
   *
   * **Requires the target app's APK to be marked `android:debuggable="true"`** in its manifest.
   * `run-as` is gated on debuggable APKs by the Android platform; release builds fail with
   * `run-as: package not debuggable`. Root is **not** required.
   *
   * The package name format is validated before invocation — any value containing shell
   * metacharacters (spaces, `;`, `&`, `|`, quotes, backticks) is rejected because it would smuggle
   * through the `run-as` shell wrapper. See [xyz.block.trailblaze.device.runAsAppIdViolation] for
   * the exact contract; it is the same grammar `android_adbShell`'s `runAs` enforces.
   *
   * Payload cap: a `run-as` write carries the bytes base64-encoded **inside the command line**, so
   * it is limited to [MAX_RUN_AS_WRITE_CONTENT_BYTES] (an over-limit write is rejected rather than
   * silently truncated). That's right-sized for preference/config files; larger bodies outside an
   * app sandbox should be written without [runAs].
   */
  val runAs: String? = null,
) : ExecutableTrailblazeTool, SensitiveArgsTrailblazeTool {

  /**
   * `base64Content` is masked in persisted session logs: it is opaque bulk bytes that routinely
   * carry secret material (seeded session/auth files with live tokens), and logging it verbatim
   * ships that material in CI artifacts. Masking also keeps multi-hundred-KB blobs out of session
   * logs. The write itself is unaffected — redaction applies only at the log-encode boundary.
   */
  override val sensitiveArgNames: Set<String>
    get() = setOf("base64Content")

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
    if (runAs != null) {
      validateRunAsWrite(runAs, devicePath, bytes.size)?.let { reason ->
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "android_writeBytesToFile cannot write as '$runAs': $reason",
          command = this,
        )
      }
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      if (runAs != null) {
        runAsWrite(executor, runAs, bytes)
      } else {
        executor.writeFileToDevice(devicePath = devicePath, content = bytes)
        TrailblazeToolResult.Success(message = "Wrote ${bytes.size} bytes to '$devicePath'.")
      }
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't swallowed. Precedent:
      // `AdbShellTrailblazeTool.execute`, `AndroidGrantPermissionsTrailblazeTool.execute`.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to write '$devicePath'" +
          (runAs?.let { " as '$it'" } ?: "") + ": ${redactBulkPayloadsForLog(e.message.orEmpty())}",
        command = this,
        // Redacted too: the on-device transport embeds the failing command — which carries the
        // file body — in its exception message, and this trace is persisted to session logs.
        stackTrace = redactBulkPayloadsForLog(e.stackTraceToString()),
      )
    }
  }

  /**
   * The `run-as` write: delegates to the shared [writeFileAs] primitive, which owns the
   * per-transport wrapping of the run-as pipeline.
   *
   * **The pipeline has no exit-code channel on either transport** (see [writeFileAs]), so a
   * returned Success has to be earned some other way. The command it runs — `mkdir -p … && printf
   * %s <b64> | base64 -d > path` — prints nothing when it works, while every realistic failure
   * announces itself on the merged output (`run-as: package not debuggable`, `run-as: unknown
   * package`, `sh: can't create …: Permission denied`). So any output at all is treated as a
   * failure. Reporting Success on a write that never landed is the worse error: the trail marches
   * on against an app whose state was never seeded.
   *
   * On the shell-less (on-device) transport the call lands in `UiAutomation`'s separate process via
   * `Runtime.exec`, where a wedged command leaves the result-pipe read blocked with no way to
   * surface the failure across the Binder. That path is therefore bounded on an interruptible IO
   * dispatcher, exactly as [AdbShellTrailblazeTool] bounds its own on-device dispatch — otherwise a
   * wedge parks the agent silently until the session inactivity watchdog fires ~13 minutes later.
   *
   * The bound turns an invisible park into a reported failure; it does not recover the device.
   * Interrupting the IO thread is not guaranteed to abort a blocked read on a pipe descriptor, and
   * that read holds `InstrumentationUtil`'s UiAutomation monitor, so a genuinely wedged write can
   * leave every later device call queued behind it. The session still has to be torn down — the
   * difference is that the failure is attributed to this write instead of to whatever ran next.
   */
  private suspend fun runAsWrite(
    executor: AndroidDeviceCommandExecutor,
    appId: String,
    bytes: ByteArray,
  ): TrailblazeToolResult {
    val output = if (executor.usesShellInterpreter) {
      executor.writeFileAs(appId = appId, devicePath = devicePath, content = bytes)
    } else {
      withTimeoutOrNull(AdbShellTrailblazeTool.ON_DEVICE_SHELL_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) {
          executor.writeFileAs(appId = appId, devicePath = devicePath, content = bytes)
        }
      } ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_writeBytesToFile did not return within " +
          "${AdbShellTrailblazeTool.ON_DEVICE_SHELL_TIMEOUT_MS}ms writing '$devicePath' as " +
          "'$appId' on the on-device transport. A wedged command leaves the UiAutomation result " +
          "pipe blocked, and that read holds the UiAutomation monitor — expect later device calls " +
          "to queue behind it and the session to need a restart.",
        command = this,
      )
    }
    if (output.isNotBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to write '$devicePath' as '$appId' — the run-as pipeline has no " +
          "exit-code channel, and it printed output, which it does not do on a successful write:\n" +
          redactBulkPayloadsForLog(output.trim()),
        command = this,
      )
    }
    return TrailblazeToolResult.Success(
      message = "Wrote ${bytes.size} bytes to '$devicePath' as '$appId'.",
    )
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

    /**
     * Why a `run-as` write can't be dispatched, or `null` when it can. Covers every precondition
     * the shared [writeFileAs] primitive enforces, so the caller learns which one it violated
     * instead of reading a generic failure out of the pipeline's `require`s.
     *
     * All three are pure properties of the arguments, so they are checked before the executor is
     * even looked up — otherwise an over-cap payload on a mis-wired context reports "executor is
     * not provided", which names the wrong problem and hides the real one until the wiring is
     * fixed. The primitive keeps its own `require`s as the backstop for its other callers.
     *
     * The package-name rule delegates to [runAsAppIdViolation] rather than re-deriving the
     * grammar: that grammar IS the escaping scheme for this token, so a second copy of it is a
     * place for an injection hole to open up.
     */
    internal fun validateRunAsWrite(runAs: String, devicePath: String, contentSize: Int): String? = when {
      runAsAppIdViolation(runAs) != null -> runAsAppIdViolation(runAs)
      devicePath.endsWith("/") ->
        "devicePath must name a file, not a directory (got '$devicePath') — the run-as pipeline " +
          "has no exit-code channel, so a redirect onto a directory would fail silently"
      contentSize > MAX_RUN_AS_WRITE_CONTENT_BYTES ->
        "content is $contentSize bytes, over the $MAX_RUN_AS_WRITE_CONTENT_BYTES-byte cap — a " +
          "run-as write carries the payload base64-encoded inside a single shell argument. Omit " +
          "`runAs` to write a larger body outside an app's private directory."
      else -> null
    }
  }
}
