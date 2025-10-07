package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest

class RunOnHostParams(
  val runYamlRequest: RunYamlRequest,
  val trailblazeDevicePlatform: TrailblazeDevicePlatform,
  val onProgressMessage: (String) -> Unit,
  val targetTestApp: TargetTestApp,
) {
  val trailblazeDriverType = when (trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_HOST
    TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
    TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.WEB_PLAYWRIGHT_HOST
  }
}
