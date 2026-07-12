package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
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
    // the YAML/TS the author can fix, not at the host setup. The message identifies the extra by
    // index, never by value: `extras` arrive with memory tokens already resolved (dispatch
    // boundary), so echoing a value could put a resolved credential into an error message.
    val blankKeyIndex = extras.indexOfFirst { it.key.isBlank() }
    if (blankKeyIndex >= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_sendBroadcast: extra at index $blankKeyIndex has a blank key — " +
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
    // `{{token}}` / `${token}` references in the args are resolved by the dispatch boundary
    // (`interpolateMemoryInTool`) before execute() runs. That boundary is how a fully-TypeScript
    // caller passes a credential WITHOUT the plaintext ever entering the JS heap: the TS tool
    // forwards the opaque token (e.g. an extra value of "{{merchant_password}}"), and the secret
    // is materialized in the trusted Kotlin layer, against memory that still holds sensitiveKeys
    // (which are filtered out of the scripting envelope, so `ctx.memory` never carried the
    // value). The resolved value never reaches a recording or the model: the tool is
    // `isRecordable = false` + `surfaceToLlm = false`, tool logs scrub sensitive tokens
    // (`buildLogSafeResolvedPayload`), and failure metadata is swapped back to the authored
    // instance at the boundary (`withAuthoredCommandIdentity`).
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
    } catch (e: CancellationException) {
      // Propagate coroutine cancellation (session teardown / abort) instead of converting it to a
      // tool error. Must precede the generic catch since CancellationException IS an Exception.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to send broadcast action='$action': ${e.message}",
        command = this@AndroidSendBroadcastTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
