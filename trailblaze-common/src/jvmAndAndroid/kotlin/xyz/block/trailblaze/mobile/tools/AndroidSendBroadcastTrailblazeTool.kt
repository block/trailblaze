package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.BroadcastIntent
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Sends a broadcast intent to the connected Android device.
 *
 * Building block for Android-specific automation tools whose only effect is to emit an
 * `am broadcast` (or equivalent on-device call). Higher-level tools can delegate to this
 * one in YAML rather than reimplementing broadcast plumbing in Kotlin.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_sendBroadcast",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Sends a broadcast intent to the connected Android device.")
data class AndroidSendBroadcastTrailblazeTool(
  val action: String,
  val componentPackage: String,
  val componentClass: String,
  // Closed-shape list-of-objects instead of `Map<String, BroadcastExtra>` so the typed
  // scripted-tool surface (`client.tools.android_sendBroadcast`) can lower it — `asToolType`
  // supports `List<DataClass>` natively, but not `Map<String, V>`.
  val extras: List<BroadcastExtra> = emptyList(),
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_sendBroadcast is only supported on Android devices.",
      )
    }
    // Trail-authoring validation comes before infra checks so a malformed `extras:` block fails
    // the same way regardless of whether an executor happens to be wired — the error points at
    // the YAML/TS the author can fix, not at the host setup.
    val blankKeyExtra = extras.firstOrNull { it.key.isBlank() }
    if (blankKeyExtra != null) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_sendBroadcast: extra has a blank key (value='${blankKeyExtra.value}') — " +
          "each extra must have a non-blank key.",
        command = this@AndroidSendBroadcastTrailblazeTool,
      )
    }
    // Duplicate keys would silently shadow inside an Android Bundle — fail loudly so trail
    // authors notice at the broadcast site instead of debugging a missing extra downstream.
    val duplicates = extras.groupBy { it.key }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_sendBroadcast: duplicate extra key(s) ${duplicates.joinToString()} — " +
          "each extra key must appear at most once.",
        command = this@AndroidSendBroadcastTrailblazeTool,
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
      )
    return try {
      executor.sendBroadcast(
        BroadcastIntent(
          action = action,
          componentPackage = componentPackage,
          componentClass = componentClass,
          extras = extras.associate { it.key to it.toTypedValue() },
        ),
      )
      TrailblazeToolResult.Success(
        message = "Sent broadcast action='$action' to $componentPackage/$componentClass",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to send broadcast action='$action': ${e.message}",
        command = this@AndroidSendBroadcastTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
