package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import maestro.orchestra.PasteTextCommand
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolAvailabilityMessages
import xyz.block.trailblaze.toolcalls.ToolAvailabilityMessages.MOBILE_PLATFORMS
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

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
 * - **iOS**: mirrors the Android approach — reads the simulator pasteboard directly via
 *   [IosHostSimctlUtils.getPasteboard] (`xcrun simctl pbpaste`) and types it via
 *   [InputTextCommand]. This used to delegate to Maestro's [PasteTextCommand] (long-press
 *   + tap "Paste" on the system edit menu), but that doesn't reliably complete in
 *   simulator/CI environments — verified on-device that the field stays empty afterward
 *   even with focus independently confirmed. Reading the pasteboard directly sidesteps
 *   that UI-interaction reliability gap entirely, same as the Android branch already does
 *   for its own reason.
 */
@Serializable
@TrailblazeToolClass(name = "mobile_pasteClipboard")
@LLMDescription("Pastes the current clipboard contents into the focused text field.")
data object PasteClipboardTrailblazeTool : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val platform = toolExecutionContext.trailblazeDeviceInfo.platform
    // Before the agent check: web and desktop contexts carry no Maestro agent, so checking it
    // first would report a missing agent instead of naming the device and where the tool runs.
    if (platform !in MOBILE_PLATFORMS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = ToolAvailabilityMessages.unsupportedPlatform(
          "mobile_pasteClipboard",
          MOBILE_PLATFORMS,
          toolExecutionContext.trailblazeDeviceInfo,
        ),
      )
    }
    val maestroTrailblazeAgent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "mobile_pasteClipboard requires a Maestro agent but none is available.",
      )
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

      TrailblazeDevicePlatform.IOS -> {
        val deviceId = toolExecutionContext.trailblazeDeviceInfo.trailblazeDeviceId.instanceId
        val clipboardText = IosHostSimctlUtils.getPasteboard(deviceId)
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

      TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.DESKTOP ->
        error("unreachable: non-mobile platforms return before the agent check")
    }
  }
}
