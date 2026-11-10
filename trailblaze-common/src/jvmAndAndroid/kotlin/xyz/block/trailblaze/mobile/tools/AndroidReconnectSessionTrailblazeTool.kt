package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.DeviceSessionReconnectSupport
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Reconnects the host session to an Android device that has rebooted, so the trail's later steps
 * keep running against it. Drops the host's cached adb state for the device, relaunches the
 * on-device runner through the same connect the session was bound with, and resumes the
 * recording's logcat and video on the new boot.
 *
 * The caller owns the reboot. This tool does not trigger or wait for one: a trailmap scripted tool
 * reboots or detects the reboot over `exec adb` (the on-device tools are down until the device is
 * back), waits for `sys.boot_completed=1`, then calls this once. See
 * `docs/internal/targets/android.md`.
 *
 * Does not survive the reboot: anything set up on the running system without persisting it, e.g.
 * the mitmproxy CA that `TRAILBLAZE_ANDROID_PROXY_CAPTURE` bind-mounts on a rooted emulator. A
 * trail that needs HTTPS capture afterwards must re-arm it.
 *
 * `requiresHost = true` + [HostLocalExecutableTrailblazeTool]: only a host-driven session can
 * reconnect (the on-device runner died with the reboot), and the reconnect itself is the host
 * agent's ([DeviceSessionReconnectSupport]). Not recordable, so it is typed by hand in the
 * TypeScript SDK's `built-in-tools.ts` like `android_adbShell`.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_reconnectSession",
  surfaceToLlm = false,
  isRecordable = false,
  requiresHost = true,
)
@LLMDescription(
  "Reconnects the host session to an Android device that has rebooted, so later steps keep running " +
    "against it. The caller triggers or detects the reboot and waits for boot completed first.",
)
data object AndroidReconnectSessionTrailblazeTool : HostLocalExecutableTrailblazeTool {

  const val TOOL_NAME = "android_reconnectSession"

  override val advertisedToolName: String get() = "android_reconnectSession"

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "$TOOL_NAME is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
        command = this,
      )
    }
    val support = toolExecutionContext.maestroTrailblazeAgent as? DeviceSessionReconnectSupport
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "$TOOL_NAME needs a host-driven Android session that can reconnect to the " +
          "device; this session's agent cannot. Run the trail from the host " +
          "(`trailblaze run`) rather than inside the on-device runner.",
        command = this,
      )
    return try {
      TrailblazeToolResult.Success(message = support.reconnectSession())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "$TOOL_NAME failed: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
