package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import maestro.orchestra.PasteTextCommand
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Pastes the current device-clipboard contents into the focused text field.
 *
 * - **Android**: reads the OS clipboard via
 *   [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.getClipboard] and types
 *   the resulting text via Maestro's [InputTextCommand]. We deliberately do **not**
 *   delegate to Maestro's [PasteTextCommand] on Android because the vendored on-device
 *   Maestro Orchestra reads its own private `copiedText` field — populated only by
 *   Maestro's `SetClipboardCommand` / `CopyTextFromCommand` — rather than the system
 *   clipboard. Reading [AndroidDeviceCommandExecutor.getClipboard] is the only way to
 *   guarantee paste sees the value that [SetClipboardTrailblazeTool] wrote.
 * - **iOS**: delegates to Maestro's [PasteTextCommand], whose iOS implementation
 *   reads the simulator/device pasteboard directly via idb / simctl, so the round
 *   trip with `xcrun simctl pasteboard set` is intact.
 */
@Serializable
@TrailblazeToolClass(name = "mobile_pasteClipboard")
@LLMDescription("Pastes the current clipboard contents into the focused text field.")
data object PasteClipboardTrailblazeTool : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val maestroTrailblazeAgent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "mobile_pasteClipboard requires a Maestro agent but none is available.",
      )
    val platform = toolExecutionContext.trailblazeDeviceInfo.platform
    return when (platform) {
      TrailblazeDevicePlatform.ANDROID -> {
        val executor = toolExecutionContext.androidDeviceCommandExecutor
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "AndroidDeviceCommandExecutor is not provided",
          )
        val clipboardText = executor.getClipboard()
        if (clipboardText.isEmpty()) {
          return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage =
              "mobile_pasteClipboard: device clipboard is empty. Call mobile_setClipboard first " +
                "(or copy from a UI element) before pasting.",
          )
        }
        maestroTrailblazeAgent.runMaestroCommands(
          maestroCommands = listOf(InputTextCommand(text = clipboardText)),
          traceId = toolExecutionContext.traceId,
        )
      }

      TrailblazeDevicePlatform.IOS ->
        maestroTrailblazeAgent.runMaestroCommands(
          maestroCommands = listOf(PasteTextCommand()),
          traceId = toolExecutionContext.traceId,
        )

      TrailblazeDevicePlatform.WEB ->
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_pasteClipboard is not supported for web devices.",
        )

      TrailblazeDevicePlatform.DESKTOP ->
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_pasteClipboard is not supported for the Compose desktop driver.",
        )
    }
  }
}
