package xyz.block.trailblaze.android.tools

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "androidSystemUiDemoMode",
  description = """
Use this to enable demo mode on the device which will freeze the clock and prevent it from changing.
    """,
)
data class AndroidSystemUiDemoModeTrailblazeTool(
  @TrailblazeToolProperty("If we should enable demo mode on the device.")
  val enable: Boolean = true,
) : ExecutableTrailblazeTool {
  override fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
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
    adbShellCommands.forEach { adbShellCommand ->
      AdbCommandUtil.execShellCommand(adbShellCommand)
    }

    return TrailblazeToolResult.Success
  }
}
