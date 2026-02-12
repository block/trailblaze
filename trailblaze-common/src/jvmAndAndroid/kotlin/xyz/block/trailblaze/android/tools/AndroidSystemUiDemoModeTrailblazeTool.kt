package xyz.block.trailblaze.android.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "androidSystemUiDemoMode",
  isForLlm = false
)
@LLMDescription("Use this to enable demo mode on the device which will freeze the clock and prevent it from changing.")
data class AndroidSystemUiDemoModeTrailblazeTool(
  @param:LLMDescription("If we should enable demo mode on the device.")
  val enable: Boolean = true,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val adbShellCommands = if (enable) {
      listOf(
        "settings put global sysui_demo_allowed 1",
        "am broadcast -a com.android.systemui.demo -e command enter",
        "am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200",
        "am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4",
        "am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype lte -e level 4",
        "am broadcast -a com.android.systemui.demo -e command battery -e plugged false -e level 100",
        "am broadcast -a com.android.systemui.demo -e command notifications -e visible false",
        "am broadcast -a com.android.systemui.demo -e command status -e volume vibrate -e bluetooth connected -e location show -e alarm false",
      )
    } else {
      listOf("am broadcast -a com.android.systemui.demo -e command exit")
    }
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { adbExecutor ->
      adbShellCommands.forEach { adbShellCommand ->
        adbExecutor.executeShellCommand(adbShellCommand)
      }
      TrailblazeToolResult.Success
    }

  }
}
