package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
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
    // Resolve `{{token}}` / `${token}` references in the data-bearing fields against the full
    // agent memory — the same per-tool interpolation InputTextTrailblazeTool et al. perform in
    // execute(). This is how a fully-TypeScript caller passes a credential WITHOUT the plaintext
    // ever entering the JS heap: the TS tool forwards the opaque token (e.g. an extra
    // value of "{{merchant_password}}"), and the secret is materialized HERE, in the trusted
    // Kotlin layer, against memory that still holds sensitiveKeys (which are filtered out of the
    // scripting envelope, so `ctx.memory` never carried the value). Resolving a sensitive token
    // here is safe precisely because this tool is `isRecordable = false` + `surfaceToLlm = false`
    // — the resolved value is never written to a recording or shown to the model. Only the
    // value-carrying fields are interpolated (`action` + each extra's `value`); `key` and the
    // component package/class are structural identifiers, left verbatim. Validation above ran on
    // the pre-interpolation `extras` so its error messages surface the token, not a resolved
    // secret.
    val (resolvedAction, resolvedExtras) =
      interpolateBroadcastArgs(action, extras, toolExecutionContext.memory)
    return try {
      executor.sendBroadcast(
        BroadcastIntent(
          action = resolvedAction,
          componentPackage = componentPackage,
          componentClass = componentClass,
          extras = resolvedExtras.associate { it.key to it.toTypedValue() },
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

/**
 * Resolves `{{token}}` / `${token}` references in the value-carrying broadcast fields
 * (`action` and each extra's `value`) against [memory], leaving `key` and the component
 * package/class verbatim. Extracted as a pure function so the interpolation contract is
 * unit-testable without a device executor ([AndroidDeviceCommandExecutor] is an `expect class`,
 * not mockable here) — including the load-bearing property that a SENSITIVE token (one
 * `rememberSensitive` withholds from the scripting envelope) STILL resolves here, in the trusted
 * Kotlin layer. That is what lets a fully-TypeScript caller forward an opaque `{{credential}}`
 * token through [AndroidSendBroadcastTrailblazeTool] without the plaintext ever entering the JS
 * heap. Unknown tokens resolve to empty string (mirrors [AgentMemory.interpolateVariables]).
 */
internal fun interpolateBroadcastArgs(
  action: String,
  extras: List<BroadcastExtra>,
  memory: AgentMemory,
): Pair<String, List<BroadcastExtra>> =
  memory.interpolateVariables(action) to
    extras.map { it.copy(value = memory.interpolateVariables(it.value)) }
